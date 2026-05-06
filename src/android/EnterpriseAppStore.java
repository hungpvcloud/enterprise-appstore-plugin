package com.enterprise.appstore;

import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

public class EnterpriseAppStore extends CordovaPlugin {

    // ════════════════════════════════════════════════════════
    // CONSTANTS & FIELDS
    // ════════════════════════════════════════════════════════
    private static final String TAG                   = "EnterpriseAppStore";
    private static final int    REQUEST_INSTALL_PERM  = 1001;
    private static final int    REQUEST_STORAGE_PERM  = 1002;
    private static final int    REQUEST_NOTIF_PERM    = 2001;
    private static final int    POLL_INTERVAL_MS      = 800;
    private static final int    MAX_POLL_COUNT        = 1500; // ~20 min

    // Badge notification constants
    private static final String BADGE_CHANNEL_ID      = "badge_channel";
    private static final String BADGE_CHANNEL_NAME    = "App Badge";
    private static final int    BADGE_NOTIFICATION_ID = 9999;

    // Counter to force notification uniqueness on each badge update
    private static int badgeUpdateCounter = 0;

    // Download state
    private long            downloadId       = -1;
    private CallbackContext downloadCallback = null;
    private Handler         progressHandler  = null;
    private Runnable        progressRunnable = null;
    private int             pollCount        = 0;

    // Pending install state (resume after returning from Settings)
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

            case "openApp":
                openApp(args.getString(0), callbackContext);
                return true;

            case "checkStoragePermission":
                checkStoragePermission(callbackContext);
                return true;

            case "requestStoragePermission":
                requestStoragePermissionOnly(callbackContext);
                return true;

            case "setBadgeNumber": {
                int count = args.getInt(0);
                setBadgeNumber(count, callbackContext);
                return true;
            }

            case "requestNotificationPermission":
                requestNotificationPermission(callbackContext);
                return true;
        }
        return false;
    }

    // ════════════════════════════════════════════════════════
    // STORAGE PERMISSION — Check & Request
    // ════════════════════════════════════════════════════════

    /** Check if storage write permission is granted */
    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return cordova.hasPermission(
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        return true;
    }

    /** Return storage permission status to JS */
    private void checkStoragePermission(CallbackContext cb) {
        try {
            JSONObject result = new JSONObject();
            result.put("hasPermission", hasStoragePermission());
            result.put("sdkVersion", Build.VERSION.SDK_INT);
            cb.success(result);
        } catch (JSONException e) {
            cb.error("ERROR_CHECK_STORAGE_PERMISSION");
        }
    }

    /** Request storage permission (standalone, no download) */
    private void requestStoragePermissionOnly(CallbackContext cb) {
        if (hasStoragePermission()) {
            try {
                JSONObject result = new JSONObject();
                result.put("status", "PERMISSION_ALREADY_GRANTED");
                result.put("hasPermission", true);
                cb.success(result);
            } catch (JSONException e) {
                cb.success("PERMISSION_ALREADY_GRANTED");
            }
            return;
        }

        pendingInstallCallback = cb;
        pendingInstallUrl      = null;
        pendingInstallFileName = null;
        requestStoragePermissionInternal();
    }

    /** Open system storage permission dialog */
    private void requestStoragePermissionInternal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ : Manage All Files
            try {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:"
                        + cordova.getContext().getPackageName()));
                cordova.setActivityResultCallback(this);
                cordova.getActivity().startActivityForResult(
                        intent, REQUEST_STORAGE_PERM);
            } catch (Exception e) {
                Log.e(TAG, "Cannot open storage settings", e);
                if (pendingInstallCallback != null) {
                    sendStatus(pendingInstallCallback, "ERROR", 0,
                            "Cannot open storage settings: " + e.getMessage(),
                            "", false);
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6-10 : runtime permission
            cordova.requestPermissions(this, REQUEST_STORAGE_PERM,
                    new String[]{
                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    });
        }
    }

    /** Handle runtime permission results */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        if (requestCode == REQUEST_STORAGE_PERM) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;

            Log.d(TAG, "Storage permission result: " + granted);

            if (pendingInstallCallback == null) return;

            if (granted) {
                // If there's a pending download, resume it
                if (pendingInstallUrl != null && !pendingInstallUrl.isEmpty()) {
                    downloadAndInstall(pendingInstallUrl,
                            pendingInstallFileName, pendingInstallCallback);
                } else {
                    try {
                        JSONObject result = new JSONObject();
                        result.put("status", "PERMISSION_GRANTED");
                        result.put("hasPermission", true);
                        pendingInstallCallback.success(result);
                    } catch (JSONException e) {
                        pendingInstallCallback.success("PERMISSION_GRANTED");
                    }
                }
            } else {
                sendStatus(pendingInstallCallback, "PERMISSION_DENIED", 0,
                        "Storage permission denied.", "", false);
            }

            // Clean up if no pending download
            if (pendingInstallUrl == null) {
                pendingInstallCallback = null;
            }
            pendingInstallUrl      = null;
            pendingInstallFileName = null;
        }
        // Handle notification permission result (Android 13+)
        else if (requestCode == REQUEST_NOTIF_PERM) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;

            if (pendingInstallCallback != null) {
                try {
                    JSONObject result = new JSONObject();
                    result.put("granted", granted);
                    pendingInstallCallback.success(result);
                } catch (JSONException e) {
                    pendingInstallCallback.success(granted ? "GRANTED" : "DENIED");
                }
                pendingInstallCallback = null;
            }
        }
    }

    // ════════════════════════════════════════════════════════
    // DOWNLOAD AND INSTALL APK
    // ════════════════════════════════════════════════════════

    /** Download APK from URL, track progress, then install */
    private void downloadAndInstall(String url, String fileName,
                                    CallbackContext cb) {
        this.downloadCallback = cb;
        this.pollCount        = 0;

        cordova.getThreadPool().execute(() -> {
            try {
                Context context = cordova.getContext();
                DownloadManager dm = (DownloadManager)
                        context.getSystemService(Context.DOWNLOAD_SERVICE);

                // Prepare download directory
                File downloadDir = new File(
                        context.getExternalFilesDir(null), "Downloads");
                if (!downloadDir.exists()) downloadDir.mkdirs();

                // Delete old file if exists
                File apkFile = new File(downloadDir, fileName);
                if (apkFile.exists()) apkFile.delete();

                // Configure download request
                DownloadManager.Request request =
                        new DownloadManager.Request(Uri.parse(url));
                request.setTitle(fileName);
                request.setDescription("Downloading " + fileName);
                request.setMimeType("application/vnd.android.package-archive");
                request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationUri(Uri.fromFile(apkFile));

                downloadId = dm.enqueue(request);
                Log.d(TAG, "Download started: id=" + downloadId);

                sendStatus(cb, "DOWNLOADING", 0,
                        "Download started...", "", true);
                startPolling(dm, apkFile, cb);

            } catch (Exception e) {
                Log.e(TAG, "downloadAndInstall error", e);
                sendStatus(cb, "ERROR", 0,
                        "Download error: " + e.getMessage(), "", false);
            }
        });
    }

    // ════════════════════════════════════════════════════════
    // DOWNLOAD PROGRESS POLLING
    // ════════════════════════════════════════════════════════

    /** Poll DownloadManager periodically for progress */
    private void startPolling(DownloadManager dm, File apkFile,
                              CallbackContext cb) {

        progressHandler  = new Handler(Looper.getMainLooper());
        progressRunnable = new Runnable() {
            @Override
            public void run() {
                if (downloadId == -1) return;

                pollCount++;
                if (pollCount > MAX_POLL_COUNT) {
                    sendStatus(cb, "ERROR", 0,
                            "Download timeout after 20 minutes", "", false);
                    downloadId = -1;
                    return;
                }

                DownloadManager.Query query = new DownloadManager.Query();
                query.setFilterById(downloadId);
                Cursor cursor = dm.query(query);

                if (cursor == null || !cursor.moveToFirst()) {
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

                switch (status) {

                    case DownloadManager.STATUS_RUNNING:
                    case DownloadManager.STATUS_PENDING: {
                        int progress = (total > 0)
                                ? (int) ((downloaded * 100L) / total) : 0;
                        String msg = (total > 0)
                                ? "Downloading " + formatBytes(downloaded)
                                  + " / " + formatBytes(total)
                                : "Downloading...";
                        sendStatus(cb, "DOWNLOADING", progress, msg, "", true);
                        progressHandler.postDelayed(this, POLL_INTERVAL_MS);
                        break;
                    }

                    case DownloadManager.STATUS_PAUSED: {
                        sendStatus(cb, "DOWNLOADING", 0,
                                "Download paused. Waiting...", "", true);
                        progressHandler.postDelayed(this, POLL_INTERVAL_MS * 2);
                        break;
                    }

                    case DownloadManager.STATUS_SUCCESSFUL: {
                        File realFile   = resolveDownloadedFile(localUri, apkFile);
                        String filePath = realFile.getAbsolutePath();
                        downloadId      = -1;

                        if (!realFile.exists() || realFile.length() == 0) {
                            sendStatus(cb, "ERROR", 0,
                                    "Downloaded file not found: " + filePath,
                                    filePath, false);
                            return;
                        }

                        sendStatus(cb, "DOWNLOAD_COMPLETE", 100,
                                "Download complete. Starting install...",
                                filePath, true);

                        // Install on UI thread
                        cordova.getActivity().runOnUiThread(
                                () -> installApk(realFile, filePath, cb));
                        break;
                    }

                    case DownloadManager.STATUS_FAILED: {
                        downloadId = -1;
                        sendStatus(cb, "ERROR", 0,
                                "Download failed: " + getDownloadErrorReason(reason),
                                "", false);
                        break;
                    }

                    default:
                        progressHandler.postDelayed(this, POLL_INTERVAL_MS);
                        break;
                }
            }
        };

        progressHandler.postDelayed(progressRunnable, POLL_INTERVAL_MS);
    }

    // ════════════════════════════════════════════════════════
    // RESOLVE DOWNLOADED FILE
    // ════════════════════════════════════════════════════════

    /** Try to get actual file from localUri, fallback to expected path */
    private File resolveDownloadedFile(String localUri, File fallbackFile) {
        if (localUri != null && !localUri.isEmpty()) {
            try {
                Uri uri = Uri.parse(localUri);
                if ("file".equals(uri.getScheme())) {
                    File f = new File(uri.getPath());
                    if (f.exists()) return f;
                }
            } catch (Exception ignored) {}
        }
        return (fallbackFile != null && fallbackFile.exists())
                ? fallbackFile
                : (fallbackFile != null ? fallbackFile : new File(""));
    }

    // ════════════════════════════════════════════════════════
    // INSTALL APK
    // ════════════════════════════════════════════════════════

    /** Launch system APK installer. Handles install permission if needed. */
    private void installApk(File apkFile, String filePath,
                            CallbackContext cb) {
        if (apkFile == null || !apkFile.exists()) {
            sendStatus(cb, "ERROR", 0,
                    "APK file not found: " + filePath, filePath, false);
            return;
        }

        Context context = cordova.getContext();

        // Check install unknown apps permission (Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.getPackageManager().canRequestPackageInstalls()) {
                // Save state and open settings
                pendingApkFile         = apkFile;
                pendingFilePath        = filePath;
                pendingInstallCallback = cb;
                pendingInstallUrl      = null;

                Intent settingsIntent = new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + context.getPackageName()));
                cordova.setActivityResultCallback(this);
                cordova.getActivity().startActivityForResult(
                        settingsIntent, REQUEST_INSTALL_PERM);

                sendStatus(cb, "WAITING_PERMISSION", 100,
                        "Please enable 'Install unknown apps'.",
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
                apkUri = FileProvider.getUriForFile(context,
                        context.getPackageName()
                                + ".enterprise.appstore.provider",
                        apkFile);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                apkUri = Uri.fromFile(apkFile);
            }

            intent.setDataAndType(apkUri,
                    "application/vnd.android.package-archive");
            context.startActivity(intent);

            sendStatus(cb, "INSTALL_PROMPT", 100,
                    "Installation dialog opened.", filePath, false);

        } catch (Exception e) {
            Log.e(TAG, "installApk error", e);
            sendStatus(cb, "ERROR", 0,
                    "Install error: " + e.getMessage(), filePath, false);
        }
    }

    // ════════════════════════════════════════════════════════
    // ACTIVITY RESULT — Resume after returning from Settings
    // ════════════════════════════════════════════════════════
    @Override
    public void onActivityResult(int requestCode, int resultCode,
                                 Intent data) {

        // Install permission result
        if (requestCode == REQUEST_INSTALL_PERM) {
            boolean granted = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                granted = cordova.getContext()
                        .getPackageManager().canRequestPackageInstalls();
            }

            if (pendingInstallCallback == null) return;

            if (granted) {
                sendStatus(pendingInstallCallback, "PERMISSION_GRANTED", 0,
                        "Permission granted. Resuming...", "", true);

                if (pendingApkFile != null && pendingApkFile.exists()) {
                    // Resume install
                    final File f = pendingApkFile;
                    final String p = pendingFilePath;
                    final CallbackContext cb = pendingInstallCallback;
                    cordova.getActivity().runOnUiThread(
                            () -> installApk(f, p, cb));
                } else if (pendingInstallUrl != null
                        && !pendingInstallUrl.isEmpty()) {
                    // Resume download
                    downloadAndInstall(pendingInstallUrl,
                            pendingInstallFileName, pendingInstallCallback);
                }
            } else {
                sendStatus(pendingInstallCallback, "PERMISSION_DENIED", 0,
                        "Install permission denied.", "", false);
            }

            // Clean up
            pendingInstallUrl      = null;
            pendingInstallFileName = null;
            pendingInstallCallback = null;
            pendingApkFile         = null;
            pendingFilePath        = null;
        }

        // Storage permission result (Android 11+)
        else if (requestCode == REQUEST_STORAGE_PERM) {
            boolean granted = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                granted = Environment.isExternalStorageManager();
            }

            if (pendingInstallCallback == null) return;

            if (granted) {
                if (pendingInstallUrl != null && !pendingInstallUrl.isEmpty()) {
                    downloadAndInstall(pendingInstallUrl,
                            pendingInstallFileName, pendingInstallCallback);
                } else {
                    try {
                        JSONObject result = new JSONObject();
                        result.put("status", "PERMISSION_GRANTED");
                        result.put("hasPermission", true);
                        pendingInstallCallback.success(result);
                    } catch (JSONException e) {
                        pendingInstallCallback.success("PERMISSION_GRANTED");
                    }
                    pendingInstallCallback = null;
                }
            } else {
                sendStatus(pendingInstallCallback, "PERMISSION_DENIED", 0,
                        "Storage permission denied.", "", false);
                pendingInstallCallback = null;
            }

            pendingInstallUrl      = null;
            pendingInstallFileName = null;
        }
    }

    // ════════════════════════════════════════════════════════
    // CHECK / REQUEST INSTALL PERMISSION
    // ════════════════════════════════════════════════════════

    /** Check if app can install unknown APKs */
    private void checkInstallPermission(CallbackContext cb) {
        try {
            boolean canInstall = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                canInstall = cordova.getContext()
                        .getPackageManager().canRequestPackageInstalls();
            }
            JSONObject result = new JSONObject();
            result.put("hasPermission", canInstall);
            cb.success(result);
        } catch (JSONException e) {
            cb.error("ERROR_CHECK_PERMISSION");
        }
    }

    /** Request install permission, optionally start download after granted */
    private void requestInstallPermission(String url, String fileName,
                                          CallbackContext cb) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Context context = cordova.getContext();

            if (!context.getPackageManager().canRequestPackageInstalls()) {
                pendingInstallUrl      = url;
                pendingInstallFileName = fileName;
                pendingInstallCallback = cb;
                pendingApkFile         = null;

                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + context.getPackageName()));
                cordova.setActivityResultCallback(this);
                cordova.getActivity().startActivityForResult(
                        intent, REQUEST_INSTALL_PERM);

                sendStatus(cb, "WAITING_PERMISSION", 0,
                        "Please enable 'Install unknown apps'.", "", true);
            } else {
                // Already granted
                if (url != null && !url.isEmpty()) {
                    downloadAndInstall(url, fileName, cb);
                } else {
                    try {
                        JSONObject r = new JSONObject();
                        r.put("status", "PERMISSION_ALREADY_GRANTED");
                        r.put("hasPermission", true);
                        cb.success(r);
                    } catch (JSONException e) {
                        cb.success("PERMISSION_ALREADY_GRANTED");
                    }
                }
            }
        } else {
            // Pre-Oreo: no permission needed
            if (url != null && !url.isEmpty()) {
                downloadAndInstall(url, fileName, cb);
            } else {
                cb.success("PERMISSION_NOT_NEEDED");
            }
        }
    }

    // ════════════════════════════════════════════════════════
    // NOTIFICATION PERMISSION (Android 13+)
    // Required for badge notifications to work
    // ════════════════════════════════════════════════════════

    /** Request POST_NOTIFICATIONS permission for Android 13+ */
    private void requestNotificationPermission(CallbackContext cb) {
        if (Build.VERSION.SDK_INT >= 33) {
            if (!cordova.hasPermission("android.permission.POST_NOTIFICATIONS")) {
                pendingInstallCallback = cb;
                cordova.requestPermission(this, REQUEST_NOTIF_PERM,
                        "android.permission.POST_NOTIFICATIONS");
                return;
            }
        }
        // Already granted or not needed
        try {
            JSONObject result = new JSONObject();
            result.put("granted", true);
            cb.success(result);
        } catch (JSONException e) {
            cb.success("GRANTED");
        }
    }

    // ════════════════════════════════════════════════════════
    // OPEN APP
    // ════════════════════════════════════════════════════════

    /** Launch another app by package name */
    private void openApp(String packageName, CallbackContext cb) {
        try {
            Context context = cordova.getContext();
            PackageManager pm = context.getPackageManager();

            // Verify app is installed
            try {
                pm.getPackageInfo(packageName, 0);
            } catch (PackageManager.NameNotFoundException e) {
                JSONObject result = new JSONObject();
                result.put("success", false);
                result.put("packageName", packageName);
                result.put("message", "App is not installed");
                result.put("errorCode", "APP_NOT_INSTALLED");
                cb.error(result);
                return;
            }

            // Get launch intent
            Intent launchIntent = pm.getLaunchIntentForPackage(packageName);
            if (launchIntent == null) {
                JSONObject result = new JSONObject();
                result.put("success", false);
                result.put("packageName", packageName);
                result.put("message", "App has no launchable activity");
                result.put("errorCode", "NO_LAUNCH_INTENT");
                cb.error(result);
                return;
            }

            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            context.startActivity(launchIntent);

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("packageName", packageName);
            result.put("message", "App launched successfully");
            cb.success(result);

        } catch (Exception e) {
            Log.e(TAG, "openApp error", e);
            try {
                JSONObject result = new JSONObject();
                result.put("success", false);
                result.put("packageName", packageName);
                result.put("message", "Error: " + e.getMessage());
                result.put("errorCode", "OPEN_APP_ERROR");
                cb.error(result);
            } catch (JSONException je) {
                cb.error("OPEN_APP_ERROR: " + e.getMessage());
            }
        }
    }

    // ════════════════════════════════════════════════════════
    // GET APP VERSION
    // ════════════════════════════════════════════════════════

    /** Get installed app version by package name */
    private void getAppVersion(String packageName, CallbackContext cb) {
        try {
            android.content.pm.PackageInfo info =
                    cordova.getContext().getPackageManager()
                            .getPackageInfo(packageName, 0);

            JSONObject result = new JSONObject();
            result.put("packageName", packageName);
            result.put("versionName", info.versionName);
            result.put("versionCode",
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                            ? info.getLongVersionCode()
                            : info.versionCode);
            cb.success(result);

        } catch (PackageManager.NameNotFoundException e) {
            cb.error("APP_NOT_FOUND");
        } catch (JSONException e) {
            cb.error("ERROR: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════
    // IS APP INSTALLED
    // ════════════════════════════════════════════════════════

    /** Check if an app is installed and return version info */
    private void isAppInstalled(String packageName, CallbackContext cb) {
        try {
            JSONObject result = new JSONObject();
            try {
                android.content.pm.PackageInfo info =
                        cordova.getContext().getPackageManager()
                                .getPackageInfo(packageName, 0);

                result.put("isInstalled", true);
                result.put("packageName", packageName);
                result.put("versionName", info.versionName);
                result.put("versionCode",
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                                ? info.getLongVersionCode()
                                : info.versionCode);
            } catch (PackageManager.NameNotFoundException e) {
                result.put("isInstalled", false);
                result.put("packageName", packageName);
                result.put("versionName", "");
                result.put("versionCode", 0);
            }
            cb.success(result);
        } catch (JSONException e) {
            cb.error("ERROR: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════
    // CHECK APP SHIELD (Security checks)
    // ════════════════════════════════════════════════════════

    /** Run device security checks: root, emulator, dev mode, etc. */
    private void checkAppShield(CallbackContext cb) {
        cordova.getThreadPool().execute(() -> {
            try {
                JSONObject result = new JSONObject();

                boolean isRooted = checkIsRooted();
                result.put("isRooted", isRooted);

                boolean isDeveloperMode = Settings.Global.getInt(
                        cordova.getContext().getContentResolver(),
                        Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
                result.put("isDeveloperMode", isDeveloperMode);

                boolean isUsbDebugging = Settings.Global.getInt(
                        cordova.getContext().getContentResolver(),
                        Settings.Global.ADB_ENABLED, 0) == 1;
                result.put("isUsbDebugging", isUsbDebugging);

                boolean isEmulator = checkIsEmulator();
                result.put("isEmulator", isEmulator);

                boolean isFromStore = checkInstalledFromStore();
                result.put("isInstalledFromStore", isFromStore);

                result.put("isSafe", !isRooted && !isEmulator);

                cb.success(result);
            } catch (Exception e) {
                cb.error("SHIELD_CHECK_ERROR: " + e.getMessage());
            }
        });
    }

    /** Check if device is rooted */
    private boolean checkIsRooted() {
        // Check build tags
        String buildTags = Build.TAGS;
        if (buildTags != null && buildTags.contains("test-keys")) return true;

        // Check common su binary paths
        String[] suPaths = {
                "/system/app/Superuser.apk", "/sbin/su",
                "/system/bin/su", "/system/xbin/su",
                "/data/local/xbin/su", "/data/local/bin/su",
                "/system/sd/xbin/su", "/system/bin/failsafe/su",
                "/data/local/su", "/su/bin/su"
        };
        for (String path : suPaths) {
            if (new File(path).exists()) return true;
        }

        // Try executing su
        try {
            Process p = Runtime.getRuntime().exec("su");
            p.destroy();
            return true;
        } catch (Exception ignored) {}

        return false;
    }

    /** Check if running on emulator */
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

    /** Check if app was installed from a known app store */
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
    // ════════════════════════════════════════════════════════

    /** Collect comprehensive device information */
    private void getDeviceInfo(CallbackContext cb) {
        cordova.getThreadPool().execute(() -> {
            try {
                Context context = cordova.getContext();
                JSONObject result = new JSONObject();

                // Device info
                result.put("manufacturer",   Build.MANUFACTURER);
                result.put("brand",          Build.BRAND);
                result.put("model",          Build.MODEL);
                result.put("device",         Build.DEVICE);
                result.put("product",        Build.PRODUCT);
                result.put("androidVersion", Build.VERSION.RELEASE);
                result.put("sdkVersion",     Build.VERSION.SDK_INT);

                // Unique device ID
                result.put("uuid", Settings.Secure.getString(
                        context.getContentResolver(),
                        Settings.Secure.ANDROID_ID));

                // App version
                try {
                    android.content.pm.PackageInfo pkgInfo =
                            context.getPackageManager()
                                    .getPackageInfo(context.getPackageName(), 0);
                    result.put("appVersion", pkgInfo.versionName);
                    result.put("packageName", context.getPackageName());
                    result.put("appVersionCode",
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                                    ? pkgInfo.getLongVersionCode()
                                    : pkgInfo.versionCode);
                } catch (Exception e) {
                    result.put("appVersion", "Unknown");
                    result.put("packageName", context.getPackageName());
                }

                // Screen info
                DisplayMetrics dm = context.getResources().getDisplayMetrics();
                result.put("screenWidth",   dm.widthPixels);
                result.put("screenHeight",  dm.heightPixels);
                result.put("screenDensity", dm.densityDpi);

                // Locale & timezone
                result.put("locale",   Locale.getDefault().toString());
                result.put("timezone", TimeZone.getDefault().getID());

                // Battery info
                IntentFilter ifilter = new IntentFilter(
                        Intent.ACTION_BATTERY_CHANGED);
                Intent batteryStatus = context.registerReceiver(null, ifilter);
                if (batteryStatus != null) {
                    int level = batteryStatus.getIntExtra(
                            BatteryManager.EXTRA_LEVEL, -1);
                    int scale = batteryStatus.getIntExtra(
                            BatteryManager.EXTRA_SCALE, -1);
                    int bStatus = batteryStatus.getIntExtra(
                            BatteryManager.EXTRA_STATUS, -1);
                    result.put("batteryLevel",
                            (scale > 0)
                                    ? (int) ((level / (float) scale) * 100)
                                    : -1);
                    result.put("isCharging",
                            bStatus == BatteryManager.BATTERY_STATUS_CHARGING
                            || bStatus == BatteryManager.BATTERY_STATUS_FULL);
                } else {
                    result.put("batteryLevel", -1);
                    result.put("isCharging", false);
                }

                // Storage info
                StatFs stat = new StatFs(
                        Environment.getExternalStorageDirectory().getPath());
                long blockSize = stat.getBlockSizeLong();
                result.put("availableStorageMB",
                        (stat.getAvailableBlocksLong() * blockSize)
                                / (1024 * 1024));
                result.put("totalStorageMB",
                        (stat.getBlockCountLong() * blockSize)
                                / (1024 * 1024));

                cb.success(result);
            } catch (Exception e) {
                cb.error("DEVICE_INFO_ERROR: " + e.getMessage());
            }
        });
    }

    // ════════════════════════════════════════════════════════
    // CHECK UPDATE
    // ════════════════════════════════════════════════════════

    /** Compare installed version with latest version */
    private void checkUpdate(String packageName, String latestVersion,
                             CallbackContext cb) {
        try {
            JSONObject result = new JSONObject();
            result.put("packageName", packageName);
            result.put("latestVersion", latestVersion);

            try {
                android.content.pm.PackageInfo info =
                        cordova.getContext().getPackageManager()
                                .getPackageInfo(packageName, 0);

                String installed = info.versionName;
                result.put("isInstalled", true);
                result.put("installedVersion", installed);
                result.put("needsUpdate",
                        compareVersions(installed, latestVersion) < 0);

            } catch (PackageManager.NameNotFoundException e) {
                result.put("isInstalled", false);
                result.put("installedVersion", "");
                result.put("needsUpdate", true);
            }

            cb.success(result);
        } catch (JSONException e) {
            cb.error("CHECK_UPDATE_ERROR: " + e.getMessage());
        }
    }

    /** Compare two semver strings. Returns -1, 0, or 1. */
    private int compareVersions(String v1, String v2) {
        if (v1 == null) v1 = "0";
        if (v2 == null) v2 = "0";
        v1 = v1.replaceAll("[^0-9.]", "");
        v2 = v2.replaceAll("[^0-9.]", "");

        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int maxLen = Math.max(parts1.length, parts2.length);

        for (int i = 0; i < maxLen; i++) {
            int p1 = 0, p2 = 0;
            try { if (i < parts1.length) p1 = Integer.parseInt(parts1[i]); }
            catch (NumberFormatException ignored) {}
            try { if (i < parts2.length) p2 = Integer.parseInt(parts2[i]); }
            catch (NumberFormatException ignored) {}

            if (p1 < p2) return -1;
            if (p1 > p2) return  1;
        }
        return 0;
    }

    // ════════════════════════════════════════════════════════
    // CANCEL DOWNLOAD
    // ════════════════════════════════════════════════════════

    /** Cancel active download */
    private void cancelDownload(CallbackContext cb) {
        if (downloadId != -1) {
            DownloadManager dm = (DownloadManager)
                    cordova.getContext()
                            .getSystemService(Context.DOWNLOAD_SERVICE);
            dm.remove(downloadId);

            if (progressHandler != null && progressRunnable != null) {
                progressHandler.removeCallbacks(progressRunnable);
            }

            downloadId = -1;
            cb.success("DOWNLOAD_CANCELLED");
        } else {
            cb.error("NO_ACTIVE_DOWNLOAD");
        }
    }

    // ════════════════════════════════════════════════════════
    // SET BADGE NUMBER — Multi-vendor compatible
    //
    // FIX: Badge only worked once on non-Samsung devices.
    // Root causes:
    //   1. MIUI/HyperOS deduplicates identical notifications
    //   2. Old notification must be cancelled before re-posting
    //   3. Xiaomi method was calling notify() twice (race condition)
    //
    // Solution:
    //   - Always cancel old notification first with a small delay
    //   - Make notification content unique on each update
    //   - Single notify() call per update (no double-posting)
    // ════════════════════════════════════════════════════════

    /** Set app icon badge number. Uses multi-strategy approach. */
    private void setBadgeNumber(int count, CallbackContext cb) {
        try {
            Context context    = cordova.getContext();
            String packageName = context.getPackageName();

            // Resolve launcher activity class name
            String launcherClass = getLauncherClassName();
            if (launcherClass == null) {
                cb.error("LAUNCHER_NOT_FOUND");
                return;
            }

            String manufacturer = Build.MANUFACTURER.toLowerCase(Locale.ROOT);
            Log.d(TAG, "setBadgeNumber: count=" + count
                    + " manufacturer=" + manufacturer);

            JSONArray appliedStrategies = new JSONArray();
            boolean anySuccess = false;

            // ── Step 1: Cancel old notification to force refresh ──
            // This is critical for MIUI/HyperOS which deduplicates
            // identical notifications and ignores badge updates.
            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.cancel(BADGE_NOTIFICATION_ID);

            // Small delay so system processes the cancellation
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}

            // ── Step 2: Standard Android 8+ notification badge ──
            // Most reliable method for modern devices.
            // Samsung, Pixel, and many others read badge from this.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    postBadgeNotification(context, count);
                    appliedStrategies.put("notification_badge");
                    anySuccess = true;
                } catch (Exception e) {
                    Log.w(TAG, "Notification badge failed: " + e.getMessage());
                }
            }

            // ── Step 3: Vendor-specific badge methods ──
            if (manufacturer.contains("samsung")) {
                try {
                    setBadgeSamsung(context, count, packageName, launcherClass);
                    appliedStrategies.put("samsung");
                    anySuccess = true;
                } catch (Exception e) {
                    Log.w(TAG, "Samsung badge failed: " + e.getMessage());
                }
            }
            else if (manufacturer.contains("huawei")
                    || manufacturer.contains("honor")) {
                try {
                    setBadgeHuawei(context, count, packageName, launcherClass);
                    appliedStrategies.put("huawei");
                    anySuccess = true;
                } catch (Exception e) {
                    Log.w(TAG, "Huawei badge failed: " + e.getMessage());
                }
            }
            else if (manufacturer.contains("xiaomi")
                    || manufacturer.contains("redmi")
                    || manufacturer.contains("poco")) {
                try {
                    // Use fixed version — single notify, no race condition
                    setBadgeXiaomi(context, count, packageName, launcherClass);
                    appliedStrategies.put("xiaomi");
                    anySuccess = true;
                } catch (Exception e) {
                    Log.w(TAG, "Xiaomi badge failed: " + e.getMessage());
                }
            }
            else if (manufacturer.contains("oppo")
                    || manufacturer.contains("realme")
                    || manufacturer.contains("oneplus")) {
                try {
                    setBadgeOPPO(context, count, packageName, launcherClass);
                    appliedStrategies.put("oppo");
                    anySuccess = true;
                } catch (Exception e) {
                    Log.w(TAG, "OPPO badge failed: " + e.getMessage());
                }
            }
            else if (manufacturer.contains("vivo")
                    || manufacturer.contains("iqoo")) {
                try {
                    setBadgeVivo(context, count, packageName, launcherClass);
                    appliedStrategies.put("vivo");
                    anySuccess = true;
                } catch (Exception e) {
                    Log.w(TAG, "vivo badge failed: " + e.getMessage());
                }
            }
            else if (manufacturer.contains("zte")) {
                try {
                    setBadgeZTE(context, count, packageName, launcherClass);
                    appliedStrategies.put("zte");
                    anySuccess = true;
                } catch (Exception e) {
                    Log.w(TAG, "ZTE badge failed: " + e.getMessage());
                }
            }
            else if (manufacturer.contains("sony")) {
                try {
                    setBadgeSony(context, count, packageName, launcherClass);
                    appliedStrategies.put("sony");
                    anySuccess = true;
                } catch (Exception e) {
                    Log.w(TAG, "Sony badge failed: " + e.getMessage());
                }
            }
            else if (manufacturer.contains("htc")) {
                try {
                    setBadgeHTC(context, count, packageName, launcherClass);
                    appliedStrategies.put("htc");
                    anySuccess = true;
                } catch (Exception e) {
                    Log.w(TAG, "HTC badge failed: " + e.getMessage());
                }
            }
            else if (manufacturer.contains("asus")) {
                try {
                    setBadgeASUS(context, count, packageName, launcherClass);
                    appliedStrategies.put("asus");
                    anySuccess = true;
                } catch (Exception e) {
                    Log.w(TAG, "ASUS badge failed: " + e.getMessage());
                }
            }

            // ── Step 4: Generic broadcast for third-party launchers ──
            try {
                setBadgeGenericBroadcast(context, count,
                        packageName, launcherClass);
                appliedStrategies.put("generic_broadcast");
                anySuccess = true;
            } catch (Exception e) {
                Log.w(TAG, "Generic broadcast failed: " + e.getMessage());
            }

            // ── Build response ──
            JSONObject result = new JSONObject();
            result.put("badge", count);
            result.put("manufacturer", Build.MANUFACTURER);
            result.put("model", Build.MODEL);
            result.put("sdkVersion", Build.VERSION.SDK_INT);
            result.put("strategies", appliedStrategies);
            result.put("success", anySuccess);
            result.put("updateCounter", badgeUpdateCounter);

            if (anySuccess) {
                cb.success(result);
            } else {
                result.put("error", "No badge strategy worked");
                cb.error(result);
            }

        } catch (Exception e) {
            Log.e(TAG, "setBadgeNumber error", e);
            cb.error("SET_BADGE_ERROR: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════
    // BADGE: Android 8+ Notification (standard method)
    //
    // Posts a silent notification with setNumber(count).
    // The launcher reads this to display the badge.
    //
    // KEY FIX: Content must change each time, otherwise
    // MIUI/HyperOS treats it as duplicate and ignores it.
    // ════════════════════════════════════════════════════════

    /** Post a silent notification that carries the badge count */
    private void postBadgeNotification(Context context, int count) {
        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);

        // If count is zero, just cancel — badge cleared
        if (count <= 0) {
            nm.cancel(BADGE_NOTIFICATION_ID);
            return;
        }

        // Increment counter — makes each notification unique
        badgeUpdateCounter++;

        // Ensure notification channel exists with badge enabled
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel =
                    nm.getNotificationChannel(BADGE_CHANNEL_ID);
            if (channel == null) {
                channel = new NotificationChannel(
                        BADGE_CHANNEL_ID,
                        BADGE_CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_DEFAULT);
                channel.setShowBadge(true);
                channel.setSound(null, null);
                channel.enableVibration(false);
                channel.enableLights(false);
                nm.createNotificationChannel(channel);
            }
        }

        // Get app display name for notification content
        String appName = context.getApplicationInfo()
                .loadLabel(context.getPackageManager()).toString();

        // Build notification with UNIQUE content each time.
        // Changing text + timestamp + sortKey forces the system
        // to treat this as a new notification, not a duplicate.
        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(context, BADGE_CHANNEL_ID)
                        .setSmallIcon(getAppIconResourceId(context))
                        .setContentTitle(appName)
                        .setContentText("You have " + count + " updates")
                        .setNumber(count)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .setSilent(true)
                        .setOngoing(false)
                        .setAutoCancel(false)
                        // Unique timestamp prevents deduplication
                        .setWhen(System.currentTimeMillis())
                        .setShowWhen(false)
                        .setGroup("badge_group")
                        .setGroupSummary(true)
                        // Unique sort key for extra differentiation
                        .setSortKey(String.valueOf(badgeUpdateCounter));

        // Add unique extras — some systems check extras for changes
        Bundle extras = new Bundle();
        extras.putInt("badge_count", count);
        extras.putInt("update_id", badgeUpdateCounter);
        extras.putLong("timestamp", System.currentTimeMillis());
        builder.addExtras(extras);

        Notification notification = builder.build();
        notification.flags |= Notification.FLAG_NO_CLEAR;

        nm.notify(BADGE_NOTIFICATION_ID, notification);
        Log.d(TAG, "Badge notification posted: count=" + count
                + " counter=" + badgeUpdateCounter);
    }

    /** Get the app's launcher icon resource ID */
    private int getAppIconResourceId(Context context) {
        try {
            return context.getPackageManager()
                    .getApplicationInfo(context.getPackageName(), 0).icon;
        } catch (PackageManager.NameNotFoundException e) {
            return android.R.drawable.ic_dialog_info;
        }
    }

    // ════════════════════════════════════════════════════════
    // BADGE: Samsung (OneUI / TouchWiz)
    // Uses BadgeProvider ContentResolver + legacy broadcast.
    // ════════════════════════════════════════════════════════

    private void setBadgeSamsung(Context context, int count,
                                 String packageName,
                                 String launcherClass) {
        // Method 1: Samsung BadgeProvider ContentResolver
        try {
            Uri uri = Uri.parse("content://com.sec.badge/apps");
            ContentValues cv = new ContentValues();
            cv.put("package", packageName);
            cv.put("class", launcherClass);
            cv.put("badgecount", count);

            Cursor cursor = context.getContentResolver().query(
                    uri, null, "package=?",
                    new String[]{packageName}, null);

            if (cursor != null) {
                if (cursor.moveToFirst()) {
                    if (count > 0) {
                        context.getContentResolver().update(
                                uri, cv, "package=?",
                                new String[]{packageName});
                    } else {
                        context.getContentResolver().delete(
                                uri, "package=?",
                                new String[]{packageName});
                    }
                } else if (count > 0) {
                    context.getContentResolver().insert(uri, cv);
                }
                cursor.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "Samsung BadgeProvider failed: " + e.getMessage());
        }

        // Method 2: Legacy broadcast (older Samsung devices)
        try {
            Intent intent = new Intent(
                    "android.intent.action.BADGE_COUNT_UPDATE");
            intent.putExtra("badge_count", count);
            intent.putExtra("badge_count_package_name", packageName);
            intent.putExtra("badge_count_class_name", launcherClass);
            context.sendBroadcast(intent);
        } catch (Exception e) {
            Log.w(TAG, "Samsung broadcast failed: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════
    // BADGE: Huawei / Honor (EMUI)
    // Uses Huawei launcher ContentProvider.
    // ════════════════════════════════════════════════════════

    private void setBadgeHuawei(Context context, int count,
                                String packageName,
                                String launcherClass) {
        try {
            Bundle bundle = new Bundle();
            bundle.putString("package", packageName);
            bundle.putString("class", launcherClass);
            bundle.putInt("badgenumber", count);

            context.getContentResolver().call(
                    Uri.parse("content://com.huawei.android.launcher"
                            + ".settings/badge/"),
                    "change_badge", null, bundle);
        } catch (Exception e) {
            Log.w(TAG, "Huawei ContentProvider failed: " + e.getMessage());

            // Fallback: broadcast
            try {
                Intent intent = new Intent(
                        "com.huawei.android.launcher.action.UPDATE_BADGENUMBER");
                intent.putExtra("badge_number", count);
                intent.putExtra("package_name", packageName);
                intent.putExtra("class_name", launcherClass);
                intent.setComponent(new ComponentName(
                        "com.huawei.android.launcher",
                        "com.huawei.android.launcher.LauncherProvider"));
                context.sendBroadcast(intent);
            } catch (Exception e2) {
                throw e; // Re-throw original
            }
        }
    }

    // ════════════════════════════════════════════════════════
    // BADGE: Xiaomi / Redmi / POCO (MIUI / HyperOS)
    //
    // FIXED VERSION:
    //   - Creates ONE notification only (no double-posting)
    //   - Sets MIUI extraNotification.setMessageCount via reflection
    //   - Falls back to notification.number for HyperOS
    //   - Content is unique each time to avoid deduplication
    // ════════════════════════════════════════════════════════

    private void setBadgeXiaomi(Context context, int count,
                                String packageName,
                                String launcherClass) {

        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);

        // Clear badge
        if (count <= 0) {
            nm.cancel(BADGE_NOTIFICATION_ID);
            return;
        }

        badgeUpdateCounter++;

        // Ensure channel exists (do NOT call postBadgeNotification here
        // to avoid double-notify race condition)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel =
                    nm.getNotificationChannel(BADGE_CHANNEL_ID);
            if (channel == null) {
                channel = new NotificationChannel(
                        BADGE_CHANNEL_ID,
                        BADGE_CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_DEFAULT);
                channel.setShowBadge(true);
                channel.setSound(null, null);
                channel.enableVibration(false);
                channel.enableLights(false);
                nm.createNotificationChannel(channel);
            }
        }

        // Build notification using native Notification.Builder
        // (required for MIUI reflection to work on the object)
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(context, BADGE_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(context);
        }

        String appName = context.getApplicationInfo()
                .loadLabel(context.getPackageManager()).toString();

        // Unique content each time to prevent MIUI deduplication
        builder.setSmallIcon(getAppIconResourceId(context))
                .setContentTitle(appName)
                .setContentText(count + " pending updates")
                .setNumber(count)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setPriority(Notification.PRIORITY_LOW);
        }

        Notification notification = builder.build();

        // MIUI-specific: set messageCount via reflection.
        // On MIUI, the badge reads from extraNotification.messageCount
        // instead of notification.number.
        try {
            java.lang.reflect.Field field =
                    notification.getClass().getDeclaredField("extraNotification");
            field.setAccessible(true);
            Object extraNotif = field.get(notification);

            if (extraNotif != null) {
                java.lang.reflect.Method setCount =
                        extraNotif.getClass().getDeclaredMethod(
                                "setMessageCount", int.class);
                setCount.setAccessible(true);
                setCount.invoke(extraNotif, count);
                Log.d(TAG, "Xiaomi setMessageCount(" + count + ") OK");
            }
        } catch (NoSuchFieldException e) {
            // Expected on HyperOS (Xiaomi 14+) — uses notification.number
            Log.d(TAG, "Xiaomi: no extraNotification field (HyperOS mode)");
        } catch (Exception e) {
            Log.w(TAG, "Xiaomi reflection failed: " + e.getMessage());
        }

        // Post notification ONCE — no race condition
        nm.notify(BADGE_NOTIFICATION_ID, notification);
        Log.d(TAG, "Xiaomi badge posted: count=" + count);
    }

    // ════════════════════════════════════════════════════════
    // BADGE: OPPO / Realme / OnePlus (ColorOS / OxygenOS)
    // ════════════════════════════════════════════════════════

    private void setBadgeOPPO(Context context, int count,
                              String packageName,
                              String launcherClass) {
        // Method 1: ContentProvider
        try {
            Bundle extras = new Bundle();
            extras.putInt("app_badge_count", count);
            context.getContentResolver().call(
                    Uri.parse("content://com.android.badge/badge"),
                    "setAppBadgeCount", null, extras);
        } catch (Exception e) {
            Log.w(TAG, "OPPO ContentProvider failed: " + e.getMessage());
        }

        // Method 2: Broadcast (note: "pakeageName" typo is intentional — OPPO's API)
        try {
            Intent intent = new Intent("com.oppo.unsettledevent");
            intent.putExtra("pakeageName", packageName);
            intent.putExtra("packageName", packageName);
            intent.putExtra("number", count);
            intent.putExtra("upgradeNumber", count);
            context.sendBroadcast(intent);
        } catch (Exception e) {
            Log.w(TAG, "OPPO broadcast failed: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════
    // BADGE: vivo / iQOO (FuntouchOS / OriginOS)
    // ════════════════════════════════════════════════════════

    private void setBadgeVivo(Context context, int count,
                              String packageName,
                              String launcherClass) {
        Intent intent = new Intent(
                "launcher.action.CHANGE_APPLICATION_NOTIFICATION_NUM");
        intent.putExtra("packageName", packageName);
        intent.putExtra("className", launcherClass);
        intent.putExtra("notificationNum", count);
        context.sendBroadcast(intent);
    }

    // ════════════════════════════════════════════════════════
    // BADGE: ZTE
    // ════════════════════════════════════════════════════════

    private void setBadgeZTE(Context context, int count,
                             String packageName,
                             String launcherClass) {
        Bundle extras = new Bundle();
        extras.putInt("app_badge_count", count);
        extras.putString("app_badge_component_name",
                new ComponentName(packageName, launcherClass)
                        .flattenToString());
        context.getContentResolver().call(
                Uri.parse("content://com.android.launcher3.badge/badge"),
                "setAppBadgeCount", null, extras);
    }

    // ════════════════════════════════════════════════════════
    // BADGE: Sony (Xperia)
    // ════════════════════════════════════════════════════════

    private void setBadgeSony(Context context, int count,
                              String packageName,
                              String launcherClass) {
        ContentValues cv = new ContentValues();
        cv.put("badge_count", count);
        cv.put("package_name", packageName);
        cv.put("activity_name", launcherClass);

        // Try current Sony launcher, then legacy
        String providerUri = isPackageInstalled(context, "com.sonymobile.home")
                ? "content://com.sonymobile.home.resourceprovider/badge"
                : "content://com.sonyericsson.home.resourceprovider/badge";

        context.getContentResolver().insert(Uri.parse(providerUri), cv);
    }

    // ════════════════════════════════════════════════════════
    // BADGE: HTC (Sense)
    // ════════════════════════════════════════════════════════

    private void setBadgeHTC(Context context, int count,
                             String packageName,
                             String launcherClass) {
        ComponentName component =
                new ComponentName(packageName, launcherClass);

        // Primary HTC broadcast
        Intent intent = new Intent(
                "com.htc.launcher.action.SET_NOTIFICATION");
        intent.putExtra("com.htc.launcher.extra.COMPONENT",
                component.flattenToShortString());
        intent.putExtra("com.htc.launcher.extra.COUNT", count);
        context.sendBroadcast(intent);

        // Alternative HTC broadcast
        Intent intent2 = new Intent(
                "com.htc.launcher.action.UPDATE_SHORTCUT");
        intent2.putExtra("packagename", packageName);
        intent2.putExtra("count", count);
        context.sendBroadcast(intent2);
    }

    // ════════════════════════════════════════════════════════
    // BADGE: ASUS (ZenUI)
    // ════════════════════════════════════════════════════════

    private void setBadgeASUS(Context context, int count,
                              String packageName,
                              String launcherClass) {
        ContentValues cv = new ContentValues();
        cv.put("badge_count", count);
        cv.put("package_name", packageName);
        cv.put("activity_name", launcherClass);

        context.getContentResolver().insert(
                Uri.parse("content://com.asus.launcher.badge/badge"), cv);
    }

    // ════════════════════════════════════════════════════════
    // BADGE: Generic broadcast (third-party launchers)
    // Supports Nova Launcher, Action Launcher, etc.
    // ════════════════════════════════════════════════════════

    private void setBadgeGenericBroadcast(Context context, int count,
                                          String packageName,
                                          String launcherClass) {
        // Standard badge broadcast
        try {
            Intent intent = new Intent(
                    "android.intent.action.BADGE_COUNT_UPDATE");
            intent.putExtra("badge_count", count);
            intent.putExtra("badge_count_package_name", packageName);
            intent.putExtra("badge_count_class_name", launcherClass);
            context.sendBroadcast(intent);
        } catch (Exception e) {
            Log.w(TAG, "Generic broadcast failed: " + e.getMessage());
        }

        // ShortcutBadger-style broadcast
        try {
            Intent intent2 = new Intent(
                    "me.leolin.shortcutbadger.BADGE_COUNT_UPDATE");
            intent2.putExtra("badge_count", count);
            intent2.putExtra("badge_count_package_name", packageName);
            intent2.putExtra("badge_count_class_name", launcherClass);
            context.sendBroadcast(intent2);
        } catch (Exception e) {
            Log.w(TAG, "ShortcutBadger broadcast failed: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════
    // HELPER: Check if a package is installed
    // ════════════════════════════════════════════════════════

    private boolean isPackageInstalled(Context context, String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    // ════════════════════════════════════════════════════════
    // HELPER: Get launcher activity class name
    // ════════════════════════════════════════════════════════

    /** Find the LAUNCHER activity class for this app */
    private String getLauncherClassName() {
        Context context = cordova.getContext();
        PackageManager pm = context.getPackageManager();

        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> resolveInfos =
                pm.queryIntentActivities(intent, 0);

        for (ResolveInfo info : resolveInfos) {
            if (info.activityInfo.packageName
                    .equals(context.getPackageName())) {
                return info.activityInfo.name;
            }
        }
        return null;
    }

    // ════════════════════════════════════════════════════════
    // HELPER: Send status callback to JavaScript
    // ════════════════════════════════════════════════════════

    /** Send structured status update. keepCallback=true for streaming. */
    private void sendStatus(CallbackContext cb, String status,
                            int progress, String message,
                            String filePath, boolean keepCallback) {
        try {
            JSONObject data = new JSONObject();
            data.put("status", status);
            data.put("progress", progress);
            data.put("message", message);
            data.put("filePath", filePath != null ? filePath : "");

            PluginResult result = new PluginResult(
                    PluginResult.Status.OK, data);
            result.setKeepCallback(keepCallback);
            cb.sendPluginResult(result);
        } catch (JSONException e) {
            Log.e(TAG, "sendStatus error: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════
    // HELPER: Format byte count for display
    // ════════════════════════════════════════════════════════

    private String formatBytes(long bytes) {
        if (bytes <= 0)          return "0 B";
        if (bytes < 1024)        return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    // ════════════════════════════════════════════════════════
    // HELPER: Download error reason to human-readable text
    // ════════════════════════════════════════════════════════

    private String getDownloadErrorReason(int reason) {
        switch (reason) {
            case DownloadManager.ERROR_CANNOT_RESUME:       return "Cannot resume";
            case DownloadManager.ERROR_DEVICE_NOT_FOUND:    return "Storage not found";
            case DownloadManager.ERROR_FILE_ALREADY_EXISTS:  return "File exists";
            case DownloadManager.ERROR_FILE_ERROR:           return "File error";
            case DownloadManager.ERROR_HTTP_DATA_ERROR:      return "HTTP data error";
            case DownloadManager.ERROR_INSUFFICIENT_SPACE:   return "Insufficient space";
            case DownloadManager.ERROR_TOO_MANY_REDIRECTS:   return "Too many redirects";
            case DownloadManager.ERROR_UNHANDLED_HTTP_CODE:  return "Unhandled HTTP code";
            default: return "Unknown error (code: " + reason + ")";
        }
    }
}
