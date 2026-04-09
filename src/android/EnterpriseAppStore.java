package com.enterprise.appstore;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.TimeZone;

public class EnterpriseAppStore extends CordovaPlugin {

    // ════════════════════════════════════════════════════════
    // CONSTANTS & FIELDS
    // ════════════════════════════════════════════════════════
    private static final String TAG                  = "EnterpriseAppStore";
    private static final int    REQUEST_INSTALL_PERM = 1001;
    private static final int    POLL_INTERVAL_MS     = 800;
    private static final int    MAX_POLL_COUNT       = 1500; // ~20 min

    // Download state
    private long            downloadId       = -1;
    private CallbackContext downloadCallback = null;
    private Handler         progressHandler  = null;
    private Runnable        progressRunnable = null;
    private int             pollCount        = 0;

    // Pending install state (resume after Settings)
    private String          pendingInstallUrl      = null;
    private String          pendingInstallFileName = null;
    private CallbackContext pendingInstallCallback = null;
    private File            pendingApkFile         = null;
    private String          pendingFilePath        = null;

    // ════════════════════════════════════════════════════════
    // EXECUTE — Action Router
    // ════════════════════════════════════════════════════════
    @Override
    public boolean execute(String action, JSONArray args,
                           CallbackContext callbackContext)
            throws JSONException {

        switch (action) {

            case "downloadAndInstall": {
                String url      = args.getString(0);
                String fileName = args.getString(1);
                if (!fileName.toLowerCase().endsWith(".apk")) {
                    fileName = fileName + ".apk";
                }
                downloadAndInstall(url, fileName, callbackContext);
                return true;
            }

            case "checkInstallPermission":
                checkInstallPermission(callbackContext);
                return true;

            case "requestInstallPermission": {
                String url      = args.optString(0, "");
                String fileName = args.optString(1, "");
                if (!fileName.isEmpty()
                        && !fileName.toLowerCase().endsWith(".apk")) {
                    fileName = fileName + ".apk";
                }
                requestInstallPermission(url, fileName, callbackContext);
                return true;
            }

            case "getAppVersion":
                getAppVersion(args.getString(0), callbackContext);
                return true;

            case "isAppInstalled":
                isAppInstalled(args.getString(0), callbackContext);
                return true;

            case "checkAppShield":
                checkAppShield(callbackContext);
                return true;

            case "getDeviceInfo":
                getDeviceInfo(callbackContext);
                return true;

            case "checkUpdate":
                checkUpdate(args.getString(0), args.getString(1),
                        callbackContext);
                return true;

            case "cancelDownload":
                cancelDownload(callbackContext);
                return true;
        }
        return false;
    }

    // ════════════════════════════════════════════════════════
    // DOWNLOAD AND INSTALL
    // Uses POLLING — compatible with Xiaomi HyperOS
    // ════════════════════════════════════════════════════════
    private void downloadAndInstall(String url, String fileName,
                                    CallbackContext callbackContext) {
        this.downloadCallback = callbackContext;
        this.pollCount        = 0;

        cordova.getThreadPool().execute(() -> {
            try {
                Context context = cordova.getContext();
                DownloadManager dm = (DownloadManager)
                        context.getSystemService(Context.DOWNLOAD_SERVICE);

                // Public Downloads — not blocked by MIUI/HyperOS
                File downloadDir = Environment
                        .getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS);
                if (!downloadDir.exists()) downloadDir.mkdirs();

                // Remove old APK if exists
                File apkFile = new File(downloadDir, fileName);
                if (apkFile.exists()) {
                    apkFile.delete();
                    Log.d(TAG, "Deleted old APK: " + apkFile.getAbsolutePath());
                }

                // Configure DownloadManager request
                DownloadManager.Request request =
                        new DownloadManager.Request(Uri.parse(url));
                request.setTitle(fileName);
                request.setDescription("Downloading " + fileName);
                request.setMimeType(
                        "application/vnd.android.package-archive");
                request.setNotificationVisibility(
                        DownloadManager.Request
                                .VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS, fileName);

                downloadId = dm.enqueue(request);

                Log.d(TAG, "Download enqueued:"
                        + " id=" + downloadId
                        + " file=" + apkFile.getAbsolutePath());

                // Initial status
                sendStatus(callbackContext,
                        "DOWNLOADING", 0, "Download started...", "", true);

                // Start polling loop
                startPolling(dm, apkFile, callbackContext);

            } catch (Exception e) {
                Log.e(TAG, "downloadAndInstall error: " + e.getMessage(), e);
                sendStatus(callbackContext, "ERROR", 0,
                        "Download error: " + e.getMessage(), "", false);
            }
        });
    }

    // ════════════════════════════════════════════════════════
    // POLLING — Query DownloadManager every 800ms
    // Replaces BroadcastReceiver for Xiaomi HyperOS
    // ════════════════════════════════════════════════════════
    private void startPolling(DownloadManager dm, File apkFile,
                              CallbackContext callbackContext) {

        progressHandler  = new Handler(Looper.getMainLooper());
        progressRunnable = new Runnable() {
            @Override
            public void run() {
                if (downloadId == -1) {
                    Log.d(TAG, "Polling stopped: downloadId=-1");
                    return;
                }

                // Timeout check
                pollCount++;
                if (pollCount > MAX_POLL_COUNT) {
                    Log.e(TAG, "Download TIMEOUT after " + pollCount + " polls");
                    sendStatus(callbackContext, "ERROR", 0,
                            "Download timeout after 20 minutes", "", false);
                    downloadId = -1;
                    return;
                }

                // Query DownloadManager
                DownloadManager.Query query = new DownloadManager.Query();
                query.setFilterById(downloadId);
                Cursor cursor = dm.query(query);

                if (cursor == null || !cursor.moveToFirst()) {
                    Log.w(TAG, "Cursor null at poll #" + pollCount
                            + " — retrying...");
                    if (cursor != null) cursor.close();
                    progressHandler.postDelayed(this, POLL_INTERVAL_MS);
                    return;
                }

                int    status     = cursor.getInt(cursor.getColumnIndexOrThrow(
                                        DownloadManager.COLUMN_STATUS));
                long   downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(
                                        DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                long   total      = cursor.getLong(cursor.getColumnIndexOrThrow(
                                        DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                String localUri   = cursor.getString(cursor.getColumnIndexOrThrow(
                                        DownloadManager.COLUMN_LOCAL_URI));
                int    reason     = cursor.getInt(cursor.getColumnIndexOrThrow(
                                        DownloadManager.COLUMN_REASON));
                cursor.close();

                Log.d(TAG, "Poll #" + pollCount
                        + " status=" + status
                        + " " + formatBytes(downloaded)
                        + "/" + formatBytes(total)
                        + " localUri=" + localUri);

                switch (status) {

                    case DownloadManager.STATUS_RUNNING:
                    case DownloadManager.STATUS_PENDING: {
                        int progress = (total > 0)
                                ? (int) ((downloaded * 100L) / total) : 0;
                        String msg = (total > 0)
                                ? "Downloading "
                                  + formatBytes(downloaded)
                                  + " / " + formatBytes(total)
                                : "Downloading...";
                        sendStatus(callbackContext, "DOWNLOADING",
                                progress, msg, "", true);
                        progressHandler.postDelayed(this, POLL_INTERVAL_MS);
                        break;
                    }

                    case DownloadManager.STATUS_PAUSED: {
                        sendStatus(callbackContext, "DOWNLOADING",
                                0, "Download paused. Waiting...", "", true);
                        progressHandler.postDelayed(
                                this, POLL_INTERVAL_MS * 2);
                        break;
                    }

                    case DownloadManager.STATUS_SUCCESSFUL: {
                        Log.d(TAG, "=== DOWNLOAD SUCCESSFUL ===");
                        Log.d(TAG, "localUri = " + localUri);

                        File realFile   = resolveDownloadedFile(localUri, apkFile);
                        String filePath = realFile.getAbsolutePath();

                        Log.d(TAG, "realFile = " + filePath);
                        Log.d(TAG, "exists   = " + realFile.exists());
                        Log.d(TAG, "size     = " + realFile.length() + " bytes");

                        // Stop polling
                        downloadId = -1;

                        if (!realFile.exists() || realFile.length() == 0) {
                            Log.e(TAG, "File missing after download!");
                            sendStatus(callbackContext, "ERROR", 0,
                                    "Downloaded file not found: " + filePath,
                                    filePath, false);
                            return;
                        }

                        // Send DOWNLOAD_COMPLETE with real filePath
                        sendStatus(callbackContext,
                                "DOWNLOAD_COMPLETE", 100,
                                "Download complete. Starting installation...",
                                filePath, true);

                        // Run installApk on UI thread
                        final File   finalFile = realFile;
                        final String finalPath = filePath;
                        cordova.getActivity().runOnUiThread(() -> {
                            Log.d(TAG, "installApk() on UI thread: " + finalPath);
                            installApk(finalFile, finalPath, callbackContext);
                        });
                        break;
                    }

                    case DownloadManager.STATUS_FAILED: {
                        String errMsg = getDownloadErrorReason(reason);
                        Log.e(TAG, "Download FAILED: " + errMsg
                                + " (reason=" + reason + ")");
                        downloadId = -1;
                        sendStatus(callbackContext, "ERROR", 0,
                                "Download failed: " + errMsg, "", false);
                        break;
                    }

                    default:
                        Log.w(TAG, "Unknown status=" + status + " — retrying...");
                        progressHandler.postDelayed(this, POLL_INTERVAL_MS);
                        break;
                }
            }
        };

        progressHandler.postDelayed(progressRunnable, POLL_INTERVAL_MS);
        Log.d(TAG, "Polling started for downloadId=" + downloadId);
    }

    // ════════════════════════════════════════════════════════
    // RESOLVE DOWNLOADED FILE PATH
    // ════════════════════════════════════════════════════════
    private File resolveDownloadedFile(String localUri, File fallbackFile) {
        // Method 1: Parse from DownloadManager localUri
        if (localUri != null && !localUri.isEmpty()) {
            try {
                Uri uri = Uri.parse(localUri);
                if ("file".equals(uri.getScheme())) {
                    File f = new File(uri.getPath());
                    if (f.exists()) {
                        Log.d(TAG, "Resolved from localUri: "
                                + f.getAbsolutePath());
                        return f;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Cannot parse localUri: " + e.getMessage());
            }
        }

        // Method 2: Use fallback file object
        if (fallbackFile != null && fallbackFile.exists()) {
            Log.d(TAG, "Using fallback: " + fallbackFile.getAbsolutePath());
            return fallbackFile;
        }

        // Method 3: Search in Public Downloads
        if (fallbackFile != null) {
            File pub = new File(
                    Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS),
                    fallbackFile.getName());
            if (pub.exists()) {
                Log.d(TAG, "Found in public downloads: "
                        + pub.getAbsolutePath());
                return pub;
            }
        }

        Log.e(TAG, "File not found anywhere!");
        return fallbackFile != null ? fallbackFile : new File("");
    }

    // ════════════════════════════════════════════════════════
    // INSTALL APK
    // ════════════════════════════════════════════════════════
    private void installApk(File apkFile, String filePath,
                            CallbackContext callbackContext) {
        Log.d(TAG, "installApk() called: " + filePath);

        // Verify file exists
        if (apkFile == null || !apkFile.exists()) {
            Log.e(TAG, "APK file not found: " + filePath);
            sendStatus(callbackContext, "ERROR", 0,
                    "APK file not found: " + filePath, filePath, false);
            return;
        }

        Log.d(TAG, "APK size: " + apkFile.length() + " bytes");

        Context context = cordova.getContext();

        // Check REQUEST_INSTALL_PACKAGES permission (Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.getPackageManager().canRequestPackageInstalls()) {
                Log.w(TAG, "No INSTALL permission — opening Settings...");

                // Save pending state to resume after Settings
                pendingApkFile         = apkFile;
                pendingFilePath        = filePath;
                pendingInstallCallback = callbackContext;
                pendingInstallUrl      = null;

                Intent settingsIntent = new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + context.getPackageName()));
                cordova.setActivityResultCallback(this);
                cordova.getActivity().startActivityForResult(
                        settingsIntent, REQUEST_INSTALL_PERM);

                sendStatus(callbackContext, "WAITING_PERMISSION", 100,
                        "Please enable 'Install unknown apps' "
                        + "then return to app.",
                        filePath, true);
                return;
            }
        }

        // Launch install intent
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                          | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            Uri apkUri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                apkUri = FileProvider.getUriForFile(
                        context,
                        context.getPackageName()
                                + ".enterprise.appstore.provider",
                        apkFile);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Log.d(TAG, "FileProvider URI: " + apkUri);
            } else {
                apkUri = Uri.fromFile(apkFile);
                Log.d(TAG, "File URI: " + apkUri);
            }

            intent.setDataAndType(apkUri,
                    "application/vnd.android.package-archive");

            Log.d(TAG, "Starting install activity...");
            context.startActivity(intent);
            Log.d(TAG, "Install activity started ✅");

            // Final callback — keepCallback=false → JS will $resolve()
            sendStatus(callbackContext, "INSTALL_PROMPT", 100,
                    "Installation dialog opened.", filePath, false);

        } catch (Exception e) {
            Log.e(TAG, "installApk error: " + e.getMessage(), e);
            sendStatus(callbackContext, "ERROR", 0,
                    "Install error: " + e.getMessage(), filePath, false);
        }
    }

    // ════════════════════════════════════════════════════════
    // ON ACTIVITY RESULT — Called when user returns from Settings
    // ════════════════════════════════════════════════════════
    @Override
    public void onActivityResult(int requestCode, int resultCode,
                                 Intent data) {
        if (requestCode != REQUEST_INSTALL_PERM) return;

        boolean hasPermission = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            hasPermission = cordova.getContext()
                    .getPackageManager().canRequestPackageInstalls();
        }

        Log.d(TAG, "onActivityResult: hasPermission=" + hasPermission);

        if (pendingInstallCallback == null) {
            Log.w(TAG, "pendingInstallCallback is null!");
            return;
        }

        if (hasPermission) {
            sendStatus(pendingInstallCallback, "PERMISSION_GRANTED", 0,
                    "Permission granted. Resuming...", "", true);

            if (pendingApkFile != null && pendingApkFile.exists()) {
                // File already downloaded — install directly
                Log.d(TAG, "Resume install: " + pendingApkFile.getName());
                final File   f  = pendingApkFile;
                final String p  = pendingFilePath;
                final CallbackContext cb = pendingInstallCallback;
                cordova.getActivity().runOnUiThread(
                        () -> installApk(f, p, cb));

            } else if (pendingInstallUrl != null
                    && !pendingInstallUrl.isEmpty()) {
                // Not downloaded yet — start download + install
                Log.d(TAG, "Resume download: " + pendingInstallFileName);
                downloadAndInstall(pendingInstallUrl,
                        pendingInstallFileName, pendingInstallCallback);
            }

        } else {
            sendStatus(pendingInstallCallback, "PERMISSION_DENIED", 0,
                    "Install permission denied. "
                    + "Cannot install APK without this permission.",
                    "", false);
        }

        // Reset all pending state
        pendingInstallUrl      = null;
        pendingInstallFileName = null;
        pendingInstallCallback = null;
        pendingApkFile         = null;
        pendingFilePath        = null;
    }

    // ════════════════════════════════════════════════════════
    // CHECK INSTALL PERMISSION
    // ════════════════════════════════════════════════════════
    private void checkInstallPermission(CallbackContext callbackContext) {
        try {
            boolean canInstall = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                canInstall = cordova.getContext()
                        .getPackageManager().canRequestPackageInstalls();
            }
            JSONObject result = new JSONObject();
            result.put("hasPermission", canInstall);
            callbackContext.success(result);
        } catch (JSONException e) {
            callbackContext.error("ERROR_CHECK_PERMISSION");
        }
    }

    // ════════════════════════════════════════════════════════
    // REQUEST INSTALL PERMISSION
    // ════════════════════════════════════════════════════════
    private void requestInstallPermission(String url, String fileName,
                                          CallbackContext callbackContext) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Context context = cordova.getContext();

            if (!context.getPackageManager().canRequestPackageInstalls()) {
                pendingInstallUrl      = url;
                pendingInstallFileName = fileName;
                pendingInstallCallback = callbackContext;
                pendingApkFile         = null;

                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + context.getPackageName()));
                cordova.setActivityResultCallback(this);
                cordova.getActivity().startActivityForResult(
                        intent, REQUEST_INSTALL_PERM);

                sendStatus(callbackContext, "WAITING_PERMISSION", 0,
                        "Please enable 'Install unknown apps' "
                        + "then return to app.", "", true);

            } else {
                if (url != null && !url.isEmpty()) {
                    downloadAndInstall(url, fileName, callbackContext);
                } else {
                    try {
                        JSONObject r = new JSONObject();
                        r.put("status", "PERMISSION_ALREADY_GRANTED");
                        r.put("hasPermission", true);
                        callbackContext.success(r);
                    } catch (JSONException e) {
                        callbackContext.success("PERMISSION_ALREADY_GRANTED");
                    }
                }
            }
        } else {
            if (url != null && !url.isEmpty()) {
                downloadAndInstall(url, fileName, callbackContext);
            } else {
                callbackContext.success("PERMISSION_NOT_NEEDED");
            }
        }
    }

    // ════════════════════════════════════════════════════════
    // GET APP VERSION
    // Returns: { packageName, versionName, versionCode }
    // Error:   "APP_NOT_FOUND"
    // ════════════════════════════════════════════════════════
    private void getAppVersion(String packageName,
                               CallbackContext callbackContext) {
        try {
            android.content.pm.PackageInfo info =
                    cordova.getContext()
                            .getPackageManager()
                            .getPackageInfo(packageName, 0);

            JSONObject result = new JSONObject();
            result.put("packageName",  packageName);
            result.put("versionName",  info.versionName);
            result.put("versionCode",
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                            ? info.getLongVersionCode()
                            : info.versionCode);

            Log.d(TAG, "getAppVersion: " + packageName
                    + " v" + info.versionName);
            callbackContext.success(result);

        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            Log.w(TAG, "getAppVersion: APP_NOT_FOUND " + packageName);
            callbackContext.error("APP_NOT_FOUND");
        } catch (JSONException e) {
            callbackContext.error("ERROR: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════
    // IS APP INSTALLED
    // Returns: { isInstalled, packageName, versionName, versionCode }
    // ════════════════════════════════════════════════════════
    private void isAppInstalled(String packageName,
                                CallbackContext callbackContext) {
        try {
            JSONObject result = new JSONObject();
            try {
                android.content.pm.PackageInfo info =
                        cordova.getContext()
                                .getPackageManager()
                                .getPackageInfo(packageName, 0);

                result.put("isInstalled",  true);
                result.put("packageName",  packageName);
                result.put("versionName",  info.versionName);
                result.put("versionCode",
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                                ? info.getLongVersionCode()
                                : info.versionCode);

                Log.d(TAG, "isAppInstalled: " + packageName + " = TRUE"
                        + " v" + info.versionName);

            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                result.put("isInstalled",  false);
                result.put("packageName",  packageName);
                result.put("versionName",  "");
                result.put("versionCode",  0);
                Log.d(TAG, "isAppInstalled: " + packageName + " = FALSE");
            }

            callbackContext.success(result);

        } catch (JSONException e) {
            callbackContext.error("ERROR: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════
    // CHECK APP SHIELD
    // Returns: {
    //   isRooted, isDeveloperMode, isUsbDebugging,
    //   isEmulator, isInstalledFromStore, isSafe
    // }
    // ════════════════════════════════════════════════════════
    private void checkAppShield(CallbackContext callbackContext) {
        cordova.getThreadPool().execute(() -> {
            try {
                JSONObject result = new JSONObject();

                // Check 1: Rooted
                boolean isRooted = checkIsRooted();
                result.put("isRooted", isRooted);

                // Check 2: Developer Mode
                boolean isDeveloperMode = Settings.Global.getInt(
                        cordova.getContext().getContentResolver(),
                        Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                        0) == 1;
                result.put("isDeveloperMode", isDeveloperMode);

                // Check 3: USB Debugging
                boolean isUsbDebugging = Settings.Global.getInt(
                        cordova.getContext().getContentResolver(),
                        Settings.Global.ADB_ENABLED,
                        0) == 1;
                result.put("isUsbDebugging", isUsbDebugging);

                // Check 4: Emulator
                boolean isEmulator = checkIsEmulator();
                result.put("isEmulator", isEmulator);

                // Check 5: Installed from Store
                boolean isFromStore = checkInstalledFromStore();
                result.put("isInstalledFromStore", isFromStore);

                // Overall safety — blocking: rooted or emulator
                boolean isSafe = !isRooted && !isEmulator;
                result.put("isSafe", isSafe);

                Log.d(TAG, "checkAppShield:"
                        + " rooted="    + isRooted
                        + " devMode="   + isDeveloperMode
                        + " usbDebug="  + isUsbDebugging
                        + " emulator="  + isEmulator
                        + " fromStore=" + isFromStore
                        + " safe="      + isSafe);

                callbackContext.success(result);

            } catch (Exception e) {
                Log.e(TAG, "checkAppShield error: " + e.getMessage(), e);
                callbackContext.error("SHIELD_CHECK_ERROR: " + e.getMessage());
            }
        });
    }

    // ── Helper: Check Rooted ─────────────────────────────────
    private boolean checkIsRooted() {
        // Check 1: Build tags
        String buildTags = Build.TAGS;
        if (buildTags != null && buildTags.contains("test-keys")) {
            Log.d(TAG, "Root detected: test-keys");
            return true;
        }

        // Check 2: Su binary paths
        String[] suPaths = {
            "/system/app/Superuser.apk",
            "/sbin/su", "/system/bin/su",
            "/system/xbin/su", "/data/local/xbin/su",
            "/data/local/bin/su", "/system/sd/xbin/su",
            "/system/bin/failsafe/su", "/data/local/su",
            "/su/bin/su"
        };
        for (String path : suPaths) {
            if (new File(path).exists()) {
                Log.d(TAG, "Root detected: su found at " + path);
                return true;
            }
        }

        // Check 3: Try execute su
        try {
            Process process = Runtime.getRuntime().exec("su");
            process.destroy();
            Log.d(TAG, "Root detected: su executable");
            return true;
        } catch (Exception ignored) {}

        // Check 4: which su
        try {
            Process process = Runtime.getRuntime()
                    .exec(new String[]{"/system/xbin/which", "su"});
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            if (reader.readLine() != null) {
                Log.d(TAG, "Root detected: which su returned result");
                return true;
            }
        } catch (Exception ignored) {}

        return false;
    }

    // ── Helper: Check Emulator ───────────────────────────────
    private boolean checkIsEmulator() {
        return Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || (Build.BRAND.startsWith("generic")
                    && Build.DEVICE.startsWith("generic"))
                || "google_sdk".equals(Build.PRODUCT)
                || Build.HARDWARE.equals("goldfish")
                || Build.HARDWARE.equals("ranchu");
    }

    // ── Helper: Check Installed From Store ───────────────────
    private boolean checkInstalledFromStore() {
        try {
            Context context = cordova.getContext();
            String installer;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                installer = context.getPackageManager()
                        .getInstallSourceInfo(context.getPackageName())
                        .getInstallingPackageName();
            } else {
                installer = context.getPackageManager()
                        .getInstallerPackageName(context.getPackageName());
            }
            return installer != null && (
                    installer.equals("com.android.vending")
                    || installer.equals("com.google.android.feedback")
                    || installer.equals("com.huawei.appmarket")
                    || installer.equals("com.xiaomi.market")
                    || installer.equals("com.oppo.market"));
        } catch (Exception e) {
            return false;
        }
    }

    // ════════════════════════════════════════════════════════
    // GET DEVICE INFO
    // Returns full device information
    // ════════════════════════════════════════════════════════
    private void getDeviceInfo(CallbackContext callbackContext) {
        cordova.getThreadPool().execute(() -> {
            try {
                Context context = cordova.getContext();
                JSONObject result = new JSONObject();

                // Hardware info
                result.put("manufacturer",   Build.MANUFACTURER);
                result.put("brand",          Build.BRAND);
                result.put("model",          Build.MODEL);
                result.put("device",         Build.DEVICE);
                result.put("product",        Build.PRODUCT);

                // OS info
                result.put("androidVersion", Build.VERSION.RELEASE);
                result.put("sdkVersion",     Build.VERSION.SDK_INT);

                
                String androidId = Settings.Secure.getString(
                    cordova.getContext().getContentResolver(),
                    Settings.Secure.ANDROID_ID
                );

                result.put("uuid", androidId);


                // App info
                try {
                    android.content.pm.PackageInfo pkgInfo =
                            context.getPackageManager()
                                    .getPackageInfo(context.getPackageName(), 0);
                    result.put("appVersion",     pkgInfo.versionName);
                    result.put("packageName",    context.getPackageName());
                    result.put("appVersionCode",
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                                    ? pkgInfo.getLongVersionCode()
                                    : pkgInfo.versionCode);
                } catch (Exception e) {
                    result.put("appVersion",  "Unknown");
                    result.put("packageName", context.getPackageName());
                }

                // Screen info
                DisplayMetrics dm =
                        context.getResources().getDisplayMetrics();
                result.put("screenWidth",    dm.widthPixels);
                result.put("screenHeight",   dm.heightPixels);
                result.put("screenDensity",  dm.densityDpi);

                // Locale & Timezone
                result.put("locale",   Locale.getDefault().toString());
                result.put("timezone", TimeZone.getDefault().getID());

                // Battery
                IntentFilter ifilter = new IntentFilter(
                        Intent.ACTION_BATTERY_CHANGED);
                Intent batteryStatus =
                        context.registerReceiver(null, ifilter);
                if (batteryStatus != null) {
                    int level  = batteryStatus.getIntExtra(
                            BatteryManager.EXTRA_LEVEL, -1);
                    int scale  = batteryStatus.getIntExtra(
                            BatteryManager.EXTRA_SCALE, -1);
                    int bStatus = batteryStatus.getIntExtra(
                            BatteryManager.EXTRA_STATUS, -1);
                    int batteryPct = (scale > 0)
                            ? (int)((level / (float) scale) * 100) : -1;
                    boolean isCharging =
                            bStatus == BatteryManager.BATTERY_STATUS_CHARGING
                            || bStatus == BatteryManager.BATTERY_STATUS_FULL;
                    result.put("batteryLevel", batteryPct);
                    result.put("isCharging",   isCharging);
                } else {
                    result.put("batteryLevel", -1);
                    result.put("isCharging",   false);
                }

                // Storage
                StatFs stat = new StatFs(
                        Environment.getExternalStorageDirectory().getPath());
                long blockSize    = stat.getBlockSizeLong();
                long availBlocks  = stat.getAvailableBlocksLong();
                long totalBlocks  = stat.getBlockCountLong();
                result.put("availableStorageMB",
                        (availBlocks * blockSize) / (1024 * 1024));
                result.put("totalStorageMB",
                        (totalBlocks * blockSize) / (1024 * 1024));

                Log.d(TAG, "getDeviceInfo: "
                        + Build.MANUFACTURER + " " + Build.MODEL
                        + " Android " + Build.VERSION.RELEASE);

                callbackContext.success(result);

            } catch (Exception e) {
                Log.e(TAG, "getDeviceInfo error: " + e.getMessage(), e);
                callbackContext.error("DEVICE_INFO_ERROR: " + e.getMessage());
            }
        });
    }

    // ════════════════════════════════════════════════════════
    // CHECK UPDATE
    // Compare installed version vs latest version from server
    // Returns: { needsUpdate, installedVersion, latestVersion,
    //            packageName, isInstalled }
    // ════════════════════════════════════════════════════════
    private void checkUpdate(String packageName, String latestVersion,
                             CallbackContext callbackContext) {
        try {
            JSONObject result = new JSONObject();
            result.put("packageName",   packageName);
            result.put("latestVersion", latestVersion);

            try {
                android.content.pm.PackageInfo info =
                        cordova.getContext()
                                .getPackageManager()
                                .getPackageInfo(packageName, 0);

                String installedVersion = info.versionName;
                boolean needsUpdate     = compareVersions(
                        installedVersion, latestVersion) < 0;

                result.put("isInstalled",      true);
                result.put("installedVersion", installedVersion);
                result.put("needsUpdate",      needsUpdate);

                Log.d(TAG, "checkUpdate: " + packageName
                        + " installed=" + installedVersion
                        + " latest="    + latestVersion
                        + " needsUpdate=" + needsUpdate);

            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                // App not installed — needs install
                result.put("isInstalled",      false);
                result.put("installedVersion", "");
                result.put("needsUpdate",      true);
                Log.d(TAG, "checkUpdate: " + packageName + " not installed");
            }

            callbackContext.success(result);

        } catch (JSONException e) {
            callbackContext.error("CHECK_UPDATE_ERROR: " + e.getMessage());
        }
    }

    // ── Helper: Compare semantic versions ────────────────────
    // Returns: -1 (v1 < v2), 0 (equal), 1 (v1 > v2)
    private int compareVersions(String v1, String v2) {
        if (v1 == null) v1 = "0";
        if (v2 == null) v2 = "0";

        // Remove non-numeric prefix e.g. "v1.2.3" → "1.2.3"
        v1 = v1.replaceAll("[^0-9.]", "");
        v2 = v2.replaceAll("[^0-9.]", "");

        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int maxLen = Math.max(parts1.length, parts2.length);

        for (int i = 0; i < maxLen; i++) {
            int p1 = 0, p2 = 0;
            try { p1 = i < parts1.length
                    ? Integer.parseInt(parts1[i]) : 0; }
            catch (NumberFormatException ignored) {}
            try { p2 = i < parts2.length
                    ? Integer.parseInt(parts2[i]) : 0; }
            catch (NumberFormatException ignored) {}

            if (p1 < p2) return -1;
            if (p1 > p2) return  1;
        }
        return 0;
    }

    // ════════════════════════════════════════════════════════
    // CANCEL DOWNLOAD
    // ════════════════════════════════════════════════════════
    private void cancelDownload(CallbackContext callbackContext) {
        if (downloadId != -1) {
            DownloadManager dm = (DownloadManager)
                    cordova.getContext()
                            .getSystemService(Context.DOWNLOAD_SERVICE);
            dm.remove(downloadId);

            if (progressHandler != null) {
                progressHandler.removeCallbacks(progressRunnable);
            }

            downloadId = -1;
            callbackContext.success("DOWNLOAD_CANCELLED");
        } else {
            callbackContext.error("NO_ACTIVE_DOWNLOAD");
        }
    }

    // ════════════════════════════════════════════════════════
    // HELPER: Send status callback
    // ════════════════════════════════════════════════════════
    private void sendStatus(CallbackContext cb, String status,
                             int progress, String message,
                             String filePath, boolean keepCallback) {
        try {
            JSONObject data = new JSONObject();
            data.put("status",   status);
            data.put("progress", progress);
            data.put("message",  message);
            data.put("filePath", filePath != null ? filePath : "");

            PluginResult result = new PluginResult(
                    PluginResult.Status.OK, data);
            result.setKeepCallback(keepCallback);
            cb.sendPluginResult(result);

            Log.d(TAG, "sendStatus: " + status
                    + " " + progress + "%"
                    + (filePath != null && !filePath.isEmpty()
                       ? " path=" + filePath : "")
                    + " keep=" + keepCallback);

        } catch (JSONException e) {
            Log.e(TAG, "sendStatus error: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════
    // HELPER: Format bytes
    // ════════════════════════════════════════════════════════
    private String formatBytes(long bytes) {
        if (bytes <= 0)          return "0 B";
        if (bytes < 1024)        return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    // ════════════════════════════════════════════════════════
    // HELPER: Download error reason text
    // ════════════════════════════════════════════════════════
    private String getDownloadErrorReason(int reason) {
        switch (reason) {
            case DownloadManager.ERROR_CANNOT_RESUME:
                return "Cannot resume download";
            case DownloadManager.ERROR_DEVICE_NOT_FOUND:
                return "Storage device not found";
            case DownloadManager.ERROR_FILE_ALREADY_EXISTS:
                return "File already exists";
            case DownloadManager.ERROR_FILE_ERROR:
                return "File error";
            case DownloadManager.ERROR_HTTP_DATA_ERROR:
                return "HTTP data error";
            case DownloadManager.ERROR_INSUFFICIENT_SPACE:
                return "Insufficient storage space";
            case DownloadManager.ERROR_TOO_MANY_REDIRECTS:
                return "Too many redirects";
            case DownloadManager.ERROR_UNHANDLED_HTTP_CODE:
                return "Unhandled HTTP code";
            case DownloadManager.ERROR_UNKNOWN:
            default:
                return "Unknown error (code: " + reason + ")";
        }
    }
}
