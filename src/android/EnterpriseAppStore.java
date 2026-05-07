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
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.FileProvider;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;

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
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import java.util.List;

public class EnterpriseAppStore extends CordovaPlugin {

    // ════════════════════════════════════════════════════════
    // CONSTANTS & FIELDS
    // ════════════════════════════════════════════════════════
    private static final String TAG                  = "EnterpriseAppStore";
    private static final int    REQUEST_INSTALL_PERM = 1001;
    private static final int    REQUEST_STORAGE_PERM = 1002;
    private static final int    POLL_INTERVAL_MS     = 800;
    private static final int    MAX_POLL_COUNT       = 1500; // ~20 min

    // Badge notification channel & ID
    private static final String BADGE_CHANNEL_ID     = "badge_channel";
    private static final String BADGE_CHANNEL_NAME   = "App Badge";
    private static final int    BADGE_NOTIFICATION_ID = 9999;

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
        }
        return false;
    }

    // ════════════════════════════════════════════════════════
    // STORAGE PERMISSION CHECK & REQUEST
    // ════════════════════════════════════════════════════════
    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return cordova.hasPermission(
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        return true;
    }

    private void checkStoragePermission(CallbackContext callbackContext) {
        try {
            JSONObject result = new JSONObject();
            result.put("hasPermission", hasStoragePermission());
            result.put("sdkVersion", Build.VERSION.SDK_INT);
            callbackContext.success(result);
        } catch (JSONException e) {
            callbackContext.error("ERROR_CHECK_STORAGE_PERMISSION");
        }
    }

    private void requestStoragePermissionOnly(CallbackContext callbackContext) {
        if (hasStoragePermission()) {
            try {
                JSONObject result = new JSONObject();
                result.put("status", "PERMISSION_ALREADY_GRANTED");
                result.put("hasPermission", true);
                callbackContext.success(result);
            } catch (JSONException e) {
                callbackContext.success("PERMISSION_ALREADY_GRANTED");
            }
            return;
        }

        pendingInstallCallback = callbackContext;
        pendingInstallUrl = null;
        pendingInstallFileName = null;

        requestStoragePermissionInternal();
    }

    private void requestStoragePermissionInternal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:"
                        + cordova.getContext().getPackageName()));

                cordova.setActivityResultCallback(this);
                cordova.getActivity().startActivityForResult(
                        intent, REQUEST_STORAGE_PERM);

                Log.d(TAG, "Opening storage permission settings for Android 11+");

            } catch (Exception e) {
                Log.e(TAG, "Cannot open storage settings", e);
                if (pendingInstallCallback != null) {
                    sendStatus(pendingInstallCallback, "ERROR", 0,
                            "Cannot open storage settings: " + e.getMessage(),
                            "", false);
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            cordova.requestPermissions(this, REQUEST_STORAGE_PERM,
                    new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE});

            Log.d(TAG, "Requesting WRITE_EXTERNAL_STORAGE for Android 6-10");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                          String[] permissions,
                                          int[] grantResults) {
        if (requestCode == REQUEST_STORAGE_PERM) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;

            Log.d(TAG, "Storage permission result: " + granted);

            if (granted) {
                if (pendingInstallCallback != null) {
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
                }
            } else {
                if (pendingInstallCallback != null) {
                    sendStatus(pendingInstallCallback, "PERMISSION_DENIED", 0,
                            "Storage permission denied. Cannot download to public folder.",
                            "", false);
                }
            }

            if (pendingInstallUrl == null) {
                pendingInstallCallback = null;
            }
            pendingInstallUrl = null;
            pendingInstallFileName = null;
        }
    }

    // ════════════════════════════════════════════════════════
    // DOWNLOAD AND INSTALL
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

                File downloadDir = new File(context.getExternalFilesDir(null), "Downloads");
                if (!downloadDir.exists()) {
                    boolean created = downloadDir.mkdirs();
                    Log.d(TAG, "Created download dir: " + created
                            + " - " + downloadDir.getAbsolutePath());
                }

                File apkFile = new File(downloadDir, fileName);
                if (apkFile.exists()) {
                    boolean deleted = apkFile.delete();
                    Log.d(TAG, "Deleted old APK: " + deleted
                            + " - " + apkFile.getAbsolutePath());
                }

                DownloadManager.Request request =
                        new DownloadManager.Request(Uri.parse(url));
                request.setTitle(fileName);
                request.setDescription("Downloading " + fileName);
                request.setMimeType("application/vnd.android.package-archive");
                request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

                request.setDestinationUri(Uri.fromFile(apkFile));

                downloadId = dm.enqueue(request);

                Log.d(TAG, "Download enqueued:"
                        + " id=" + downloadId
                        + " file=" + apkFile.getAbsolutePath());

                sendStatus(callbackContext,
                        "DOWNLOADING", 0, "Download started...", "", true);

                startPolling(dm, apkFile, callbackContext);

            } catch (Exception e) {
                Log.e(TAG, "downloadAndInstall error: " + e.getMessage(), e);
                sendStatus(callbackContext, "ERROR", 0,
                        "Download error: " + e.getMessage(), "", false);
            }
        });
    }

    // ════════════════════════════════════════════════════════
    // POLLING
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

                pollCount++;
                if (pollCount > MAX_POLL_COUNT) {
                    Log.e(TAG, "Download TIMEOUT after " + pollCount + " polls");
                    sendStatus(callbackContext, "ERROR", 0,
                            "Download timeout after 20 minutes", "", false);
                    downloadId = -1;
                    return;
                }

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

                        downloadId = -1;

                        if (!realFile.exists() || realFile.length() == 0) {
                            Log.e(TAG, "File missing after download!");
                            sendStatus(callbackContext, "ERROR", 0,
                                    "Downloaded file not found: " + filePath,
                                    filePath, false);
                            return;
                        }

                        sendStatus(callbackContext,
                                "DOWNLOAD_COMPLETE", 100,
                                "Download complete. Starting installation...",
                                filePath, true);

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

        if (fallbackFile != null && fallbackFile.exists()) {
            Log.d(TAG, "Using fallback: " + fallbackFile.getAbsolutePath());
            return fallbackFile;
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

        if (apkFile == null || !apkFile.exists()) {
            Log.e(TAG, "APK file not found: " + filePath);
            sendStatus(callbackContext, "ERROR", 0,
                    "APK file not found: " + filePath, filePath, false);
            return;
        }

        Log.d(TAG, "APK size: " + apkFile.length() + " bytes");

        Context context = cordova.getContext();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.getPackageManager().canRequestPackageInstalls()) {
                Log.w(TAG, "No INSTALL permission — opening Settings...");

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

            sendStatus(callbackContext, "INSTALL_PROMPT", 100,
                    "Installation dialog opened.", filePath, false);

        } catch (Exception e) {
            Log.e(TAG, "installApk error: " + e.getMessage(), e);
            sendStatus(callbackContext, "ERROR", 0,
                    "Install error: " + e.getMessage(), filePath, false);
        }
    }

    // ════════════════════════════════════════════════════════
    // ON ACTIVITY RESULT
    // ════════════════════════════════════════════════════════
    @Override
    public void onActivityResult(int requestCode, int resultCode,
                                 Intent data) {
        if (requestCode == REQUEST_INSTALL_PERM) {
            boolean hasPermission = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                hasPermission = cordova.getContext()
                        .getPackageManager().canRequestPackageInstalls();
            }

            Log.d(TAG, "onActivityResult INSTALL_PERM: hasPermission=" + hasPermission);

            if (pendingInstallCallback == null) {
                Log.w(TAG, "pendingInstallCallback is null!");
                return;
            }

            if (hasPermission) {
                sendStatus(pendingInstallCallback, "PERMISSION_GRANTED", 0,
                        "Permission granted. Resuming...", "", true);

                if (pendingApkFile != null && pendingApkFile.exists()) {
                    Log.d(TAG, "Resume install: " + pendingApkFile.getName());
                    final File   f  = pendingApkFile;
                    final String p  = pendingFilePath;
                    final CallbackContext cb = pendingInstallCallback;
                    cordova.getActivity().runOnUiThread(
                            () -> installApk(f, p, cb));

                } else if (pendingInstallUrl != null
                        && !pendingInstallUrl.isEmpty()) {
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

            pendingInstallUrl      = null;
            pendingInstallFileName = null;
            pendingInstallCallback = null;
            pendingApkFile         = null;
            pendingFilePath        = null;
        }
        else if (requestCode == REQUEST_STORAGE_PERM) {
            boolean hasPermission = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                hasPermission = Environment.isExternalStorageManager();
            }

            Log.d(TAG, "onActivityResult STORAGE_PERM: hasPermission=" + hasPermission);

            if (pendingInstallCallback == null) {
                Log.w(TAG, "pendingInstallCallback is null for storage permission!");
                return;
            }

            if (hasPermission) {
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
                        "Storage permission denied. Cannot download to public folder.",
                        "", false);
                pendingInstallCallback = null;
            }

            if (pendingInstallUrl == null) {
                pendingInstallUrl = null;
                pendingInstallFileName = null;
            }
        }
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
    // OPEN APP
    // ════════════════════════════════════════════════════════
    private void openApp(String packageName, CallbackContext callbackContext) {
        try {
            Context context = cordova.getContext();
            android.content.pm.PackageManager pm = context.getPackageManager();

            try {
                pm.getPackageInfo(packageName, 0);
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                Log.w(TAG, "openApp: APP_NOT_INSTALLED " + packageName);
                try {
                    JSONObject result = new JSONObject();
                    result.put("success",     false);
                    result.put("packageName", packageName);
                    result.put("message",     "App is not installed");
                    result.put("errorCode",   "APP_NOT_INSTALLED");
                    callbackContext.error(result);
                } catch (JSONException je) {
                    callbackContext.error("APP_NOT_INSTALLED");
                }
                return;
            }

            Intent launchIntent = pm.getLaunchIntentForPackage(packageName);

            if (launchIntent == null) {
                Log.w(TAG, "openApp: NO_LAUNCH_INTENT for " + packageName);
                try {
                    JSONObject result = new JSONObject();
                    result.put("success",     false);
                    result.put("packageName", packageName);
                    result.put("message",     "App has no launchable activity");
                    result.put("errorCode",   "NO_LAUNCH_INTENT");
                    callbackContext.error(result);
                } catch (JSONException je) {
                    callbackContext.error("NO_LAUNCH_INTENT");
                }
                return;
            }

            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

            context.startActivity(launchIntent);

            Log.d(TAG, "openApp: Successfully launched " + packageName + " ✅");

            JSONObject result = new JSONObject();
            result.put("success",     true);
            result.put("packageName", packageName);
            result.put("message",     "App launched successfully");
            callbackContext.success(result);

        } catch (android.content.ActivityNotFoundException e) {
            Log.e(TAG, "openApp: ActivityNotFoundException for " + packageName, e);
            try {
                JSONObject result = new JSONObject();
                result.put("success",     false);
                result.put("packageName", packageName);
                result.put("message",     "Activity not found: " + e.getMessage());
                result.put("errorCode",   "ACTIVITY_NOT_FOUND");
                callbackContext.error(result);
            } catch (JSONException je) {
                callbackContext.error("ACTIVITY_NOT_FOUND");
            }
        } catch (SecurityException e) {
            Log.e(TAG, "openApp: SecurityException for " + packageName, e);
            try {
                JSONObject result = new JSONObject();
                result.put("success",     false);
                result.put("packageName", packageName);
                result.put("message",     "Security error: " + e.getMessage());
                result.put("errorCode",   "SECURITY_ERROR");
                callbackContext.error(result);
            } catch (JSONException je) {
                callbackContext.error("SECURITY_ERROR");
            }
        } catch (Exception e) {
            Log.e(TAG, "openApp error: " + e.getMessage(), e);
            try {
                JSONObject result = new JSONObject();
                result.put("success",     false);
                result.put("packageName", packageName);
                result.put("message",     "Error: " + e.getMessage());
                result.put("errorCode",   "OPEN_APP_ERROR");
                callbackContext.error(result);
            } catch (JSONException je) {
                callbackContext.error("OPEN_APP_ERROR: " + e.getMessage());
            }
        }
    }

    // ════════════════════════════════════════════════════════
    // GET APP VERSION
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
    // ════════════════════════════════════════════════════════
    private void checkAppShield(CallbackContext callbackContext) {
        cordova.getThreadPool().execute(() -> {
            try {
                JSONObject result = new JSONObject();

                boolean isRooted = checkIsRooted();
                result.put("isRooted", isRooted);

                boolean isDeveloperMode = Settings.Global.getInt(
                        cordova.getContext().getContentResolver(),
                        Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                        0) == 1;
                result.put("isDeveloperMode", isDeveloperMode);

                boolean isUsbDebugging = Settings.Global.getInt(
                        cordova.getContext().getContentResolver(),
                        Settings.Global.ADB_ENABLED,
                        0) == 1;
                result.put("isUsbDebugging", isUsbDebugging);

                boolean isEmulator = checkIsEmulator();
                result.put("isEmulator", isEmulator);

                boolean isFromStore = checkInstalledFromStore();
                result.put("isInstalledFromStore", isFromStore);

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

    private boolean checkIsRooted() {
        String buildTags = Build.TAGS;
        if (buildTags != null && buildTags.contains("test-keys")) {
            return true;
        }

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
                return true;
            }
        }

        try {
            Process process = Runtime.getRuntime().exec("su");
            process.destroy();
            return true;
        } catch (Exception ignored) {}

        return false;
    }

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
    private void getDeviceInfo(CallbackContext callbackContext) {
        cordova.getThreadPool().execute(() -> {
            try {
                Context context = cordova.getContext();
                JSONObject result = new JSONObject();

                result.put("manufacturer",   Build.MANUFACTURER);
                result.put("brand",          Build.BRAND);
                result.put("model",          Build.MODEL);
                result.put("device",         Build.DEVICE);
                result.put("product",        Build.PRODUCT);
                result.put("androidVersion", Build.VERSION.RELEASE);
                result.put("sdkVersion",     Build.VERSION.SDK_INT);

                String androidId = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ANDROID_ID
                );
                result.put("uuid", androidId);

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

                DisplayMetrics dm = context.getResources().getDisplayMetrics();
                result.put("screenWidth",    dm.widthPixels);
                result.put("screenHeight",   dm.heightPixels);
                result.put("screenDensity",  dm.densityDpi);

                result.put("locale",   Locale.getDefault().toString());
                result.put("timezone", TimeZone.getDefault().getID());

                IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                Intent batteryStatus = context.registerReceiver(null, ifilter);
                if (batteryStatus != null) {
                    int level  = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale  = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                    int bStatus = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
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

                StatFs stat = new StatFs(
                        Environment.getExternalStorageDirectory().getPath());
                long blockSize    = stat.getBlockSizeLong();
                long availBlocks  = stat.getAvailableBlocksLong();
                long totalBlocks  = stat.getBlockCountLong();
                result.put("availableStorageMB",
                        (availBlocks * blockSize) / (1024 * 1024));
                result.put("totalStorageMB",
                        (totalBlocks * blockSize) / (1024 * 1024));

                callbackContext.success(result);

            } catch (Exception e) {
                Log.e(TAG, "getDeviceInfo error: " + e.getMessage(), e);
                callbackContext.error("DEVICE_INFO_ERROR: " + e.getMessage());
            }
        });
    }

    // ════════════════════════════════════════════════════════
    // CHECK UPDATE
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

            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                result.put("isInstalled",      false);
                result.put("installedVersion", "");
                result.put("needsUpdate",      true);
            }

            callbackContext.success(result);

        } catch (JSONException e) {
            callbackContext.error("CHECK_UPDATE_ERROR: " + e.getMessage());
        }
    }

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

            if (progressHandler != null && progressRunnable != null) {
                progressHandler.removeCallbacks(progressRunnable);
            }

            downloadId = -1;
            callbackContext.success("DOWNLOAD_CANCELLED");
        } else {
            callbackContext.error("NO_ACTIVE_DOWNLOAD");
        }
    }

    // ════════════════════════════════════════════════════════
    // SET BADGE NUMBER — Multi-vendor compatible
    // Supports: Samsung, Huawei, Xiaomi, OPPO, vivo, ZTE,
    //           Sony, HTC, ASUS, and standard Android 8+ badge
    // ════════════════════════════════════════════════════════

    /**
     * Main entry point for setting the badge number.
     * Uses a multi-strategy approach:
     *   1. Android 8+ Notification Badge (standard, works on most devices)
     *   2. Vendor-specific broadcast intents (for launchers that use them)
     *   3. Fallback strategies for maximum compatibility
     */
    private void setBadgeNumber(int count, CallbackContext callbackContext) {
        try {
            Context context = cordova.getContext();
            String packageName = context.getPackageName();

            // Resolve launcher activity class name
            String launcherClass = getLauncherClassName();
            if (launcherClass == null) {
                Log.e(TAG, "setBadgeNumber: launcher class not found");
                callbackContext.error("LAUNCHER_NOT_FOUND");
                return;
            }

            String manufacturer = Build.MANUFACTURER.toLowerCase(Locale.ROOT);
            Log.d(TAG, "setBadgeNumber: count=" + count
                    + " manufacturer=" + manufacturer
                    + " pkg=" + packageName
                    + " launcher=" + launcherClass);

            // Track which strategies succeeded
            JSONArray appliedStrategies = new JSONArray();
            boolean anySuccess = false;

            // ─────────────────────────────────────────────
            // Strategy 1: Android 8+ Notification Badge
            // This is the STANDARD method and works on most
            // modern devices including Samsung S25 (OneUI 7)
            // ─────────────────────────────────────────────
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    setNotificationBadge(context, count);
                    appliedStrategies.put("notification_badge");
                    anySuccess = true;
                    Log.d(TAG, "setBadgeNumber: notification badge set ✅");
                } catch (Exception e) {
                    Log.w(TAG, "setBadgeNumber: notification badge failed: "
                            + e.getMessage());
                }
            }

            // ─────────────────────────────────────────────
            // Strategy 2: Vendor-specific broadcast intents
            // ─────────────────────────────────────────────

            // Samsung (all versions including OneUI 6/7)
            if (manufacturer.contains("samsung")) {
                try {
                    setBadgeSamsung(context, count, packageName, launcherClass);
                    appliedStrategies.put("samsung");
                    anySuccess = true;
                } catch (Exception e) {
                    Log.w(TAG, "setBadgeNumber: samsung method failed: "
                            + e.getMessage());
                }
            }

            // Huawei / Honor
            else if (manufacturer.contains("huawei")
                    || manufacturer.contains("honor")) {
                try {
                    setBadgeHuawei(context, count, packageName, launcherClass);
                    appliedStrategies.put("huawei");
                    anySuccess = true;
                } catch (Exception e) {
                    Log.w(TAG, "setBadgeNumber: huawei method failed: "
                            + e.getMessage());
                }
            }

            // Xiaomi / Redmi / POCO
            else if (manufacturer.contains("xiaomi")
                    || manufacturer.contains("Xiaomi")
                    || manufacturer.contains("redmi")
                    || manufacturer.contains("poco")) {
                try {
                    setBadgeXiaomi(context, count, packageName, launcherClass);
                    appliedStrategies.put(manufacturer.contains("xiaomi")?"xiaomi":"Xiaomi");
                    anySuccess = true;
                } catch (Exception e) {
                    Log.w(TAG, "setBadgeNumber: xiaomi method failed: "
                            + e.getMessage());
                }
            }

            // OPPO / Realme / OnePlus (ColorOS / OxygenOS)
            else if (manufacturer.contains("oppo")
                    || manufacturer.contains("realme")
                    || manufacturer.contains("oneplus")) {
                try {
                    setBadgeOPPO(context, count, packageName, launcherClass);
                    appliedStrategies.put("oppo");
                    anySuccess = true;
                } catch (Exception e) {
                    Log.w(TAG, "setBadgeNumber: oppo method failed: "
                            + e.getMessage());
                }
            }

            // vivo / iQOO
            else if (manufacturer.contains("vivo")
                    || manufacturer.contains("iqoo")) {
                try {
                    setBadgeVivo(context, count, packageName, launcherClass);
                    appliedStrategies.put("vivo");
                    anySuccess = true;
                } catch (Exception e) {
                    Log.w(TAG, "setBadgeNumber: vivo method failed: "
                            + e.getMessage());
                }
            }

            // ZTE
            else if (manufacturer.contains("zte")) {
                try {
                    setBadgeZTE(context, count, packageName, launcherClass);
                    appliedStrategies.put("zte");
                    anySuccess = true;
                } catch (Exception e) {
                    Log.w(TAG, "setBadgeNumber: zte method failed: "
                            + e.getMessage());
                }
            }

            // Sony
            else if (manufacturer.contains("sony")) {
                try {
                    setBadgeSony(context, count, packageName, launcherClass);
                    appliedStrategies.put("sony");
                    anySuccess = true;
                } catch (Exception e) {
                    Log.w(TAG, "setBadgeNumber: sony method failed: "
                            + e.getMessage());
                }
            }

            // HTC
            else if (manufacturer.contains("htc")) {
                try {
                    setBadgeHTC(context, count, packageName, launcherClass);
                    appliedStrategies.put("htc");
                    anySuccess = true;
                } catch (Exception e) {
                    Log.w(TAG, "setBadgeNumber: htc method failed: "
                            + e.getMessage());
                }
            }

            // ASUS
            else if (manufacturer.contains("asus")) {
                try {
                    setBadgeASUS(context, count, packageName, launcherClass);
                    appliedStrategies.put("asus");
                    anySuccess = true;
                } catch (Exception e) {
                    Log.w(TAG, "setBadgeNumber: asus method failed: "
                            + e.getMessage());
                }
            } else {                
                try {
                    setBadgeXiaomi(context, count, packageName, launcherClass);
                    appliedStrategies.put(manufacturer.contains("xiaomi")?"xiaomi":"Xiaomi");
                    anySuccess = true;
                } catch (Exception e) {
                    Log.w(TAG, "setBadgeNumber: xiaomi method failed: "
                            + e.getMessage());
                }            
            }

            // ─────────────────────────────────────────────
            // Strategy 3: Generic fallback broadcast
            // Some third-party launchers (Nova, Action, etc.)
            // listen to the generic BadgeProvider
            // ─────────────────────────────────────────────
            try {
                setBadgeGenericBroadcast(context, count,
                        packageName, launcherClass);
                appliedStrategies.put("generic_broadcast");
                anySuccess = true;
            } catch (Exception e) {
                Log.w(TAG, "setBadgeNumber: generic broadcast failed: "
                        + e.getMessage());
            }

            // ─────────────────────────────────────────────
            // Build response
            // ─────────────────────────────────────────────
            JSONObject result = new JSONObject();
            result.put("badge", count);
            result.put("manufacturer", Build.MANUFACTURER);
            result.put("model", Build.MODEL);
            result.put("sdkVersion", Build.VERSION.SDK_INT);
            result.put("strategies", appliedStrategies);
            result.put("success", anySuccess);

            if (anySuccess) {
                callbackContext.success(result);
                Log.d(TAG, "setBadgeNumber: success, count=" + count
                        + " strategies=" + appliedStrategies.toString());
            } else {
                result.put("error", "No badge strategy worked for this device");
                callbackContext.error(result);
                Log.e(TAG, "setBadgeNumber: all strategies failed");
            }

        } catch (Exception e) {
            Log.e(TAG, "setBadgeNumber error", e);
            callbackContext.error(
                "SET_BADGE_ERROR: " + e.getMessage()
            );
        }
    }

    // ════════════════════════════════════════════════════════
    // BADGE STRATEGY: Android 8+ Notification Badge
    // This is the most reliable method for modern Android.
    // The system reads the notification count and displays
    // it as a badge dot or number (depending on launcher).
    // Samsung OneUI 6+ and Google Pixel fully support this.
    // ════════════════════════════════════════════════════════
    private void setNotificationBadge(Context context, int count) {
        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(
                        Context.NOTIFICATION_SERVICE);

        if (count <= 0) {
            // Remove badge by cancelling the notification
            notificationManager.cancel(BADGE_NOTIFICATION_ID);
            Log.d(TAG, "Notification badge cleared");
            return;
        }

        // Create notification channel (required for Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = notificationManager
                    .getNotificationChannel(BADGE_CHANNEL_ID);

            if (channel == null) {
                channel = new NotificationChannel(
                        BADGE_CHANNEL_ID,
                        BADGE_CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_DEFAULT
                );
                // Enable badge for this channel
                channel.setShowBadge(true);
                // Minimize visual/audio disturbance
                channel.setSound(null, null);
                channel.enableVibration(false);
                channel.enableLights(false);
                notificationManager.createNotificationChannel(channel);
                Log.d(TAG, "Badge notification channel created");
            }
        }

        // Build a silent notification that carries the badge count
        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(context, BADGE_CHANNEL_ID)
                        .setSmallIcon(getAppIconResourceId(context))
                        .setContentTitle("")
                        .setContentText("")
                        // Set the badge number explicitly
                        .setNumber(count)
                        // Make it as unobtrusive as possible
                        .setPriority(NotificationCompat.PRIORITY_MIN)
                        .setSilent(true)
                        .setOngoing(false)
                        .setAutoCancel(false)
                        // Group to collapse multiple notifications
                        .setGroup("badge_group")
                        .setGroupSummary(true);

        notificationManager.notify(BADGE_NOTIFICATION_ID, builder.build());
        Log.d(TAG, "Notification badge set: count=" + count);
    }

    /**
     * Get the app's launcher icon resource ID.
     * Falls back to the default Android icon if not found.
     */
    private int getAppIconResourceId(Context context) {
        try {
            return context.getPackageManager()
                    .getApplicationInfo(context.getPackageName(), 0)
                    .icon;
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "App icon not found, using default");
            return android.R.drawable.ic_dialog_info;
        }
    }

    // ════════════════════════════════════════════════════════
    // BADGE STRATEGY: Samsung (OneUI / TouchWiz)
    // Supports both legacy BadgeProvider and new broadcasts.
    // Samsung S25 (OneUI 7) primarily uses notification badge,
    // but we also try the ContentProvider method for older
    // Samsung devices.
    // ════════════════════════════════════════════════════════
    private void setBadgeSamsung(Context context, int count,
                                 String packageName,
                                 String launcherClass) {

        // Method 1: Samsung BadgeProvider ContentResolver
        // This works on Samsung devices with OneUI / TouchWiz
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
                    // Update existing record
                    if (count > 0) {
                        context.getContentResolver().update(
                                uri, cv, "package=?",
                                new String[]{packageName});
                    } else {
                        context.getContentResolver().delete(
                                uri, "package=?",
                                new String[]{packageName});
                    }
                } else {
                    // Insert new record
                    if (count > 0) {
                        context.getContentResolver().insert(uri, cv);
                    }
                }
                cursor.close();
            }
            Log.d(TAG, "Samsung BadgeProvider updated: " + count);
        } catch (Exception e) {
            Log.w(TAG, "Samsung BadgeProvider failed: " + e.getMessage());
        }

        // Method 2: Legacy Samsung broadcast
        // Still works on some older Samsung devices
        try {
            Intent intent = new Intent(
                    "android.intent.action.BADGE_COUNT_UPDATE");
            intent.putExtra("badge_count", count);
            intent.putExtra("badge_count_package_name", packageName);
            intent.putExtra("badge_count_class_name", launcherClass);
            context.sendBroadcast(intent);
            Log.d(TAG, "Samsung broadcast sent: " + count);
        } catch (Exception e) {
            Log.w(TAG, "Samsung broadcast failed: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════
    // BADGE STRATEGY: Huawei / Honor (EMUI)
    // Uses the Huawei Badge ContentProvider
    // ════════════════════════════════════════════════════════
    private void setBadgeHuawei(Context context, int count,
                                String packageName,
                                String launcherClass) {
        try {
            android.os.Bundle bundle = new android.os.Bundle();
            bundle.putString("package", packageName);
            bundle.putString("class", launcherClass);
            bundle.putInt("badgenumber", count);

            context.getContentResolver().call(
                    Uri.parse("content://com.huawei.android.launcher"
                            + ".settings/badge/"),
                    "change_badge", null, bundle);

            Log.d(TAG, "Huawei badge set: " + count);
        } catch (Exception e) {
            Log.w(TAG, "Huawei badge failed: " + e.getMessage());
            // Fallback: Try broadcast method
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
                Log.d(TAG, "Huawei broadcast fallback sent: " + count);
            } catch (Exception e2) {
                Log.w(TAG, "Huawei broadcast fallback also failed: "
                        + e2.getMessage());
                throw e; // Re-throw original exception
            }
        }
    }

    // ════════════════════════════════════════════════════════
    // BADGE STRATEGY: Xiaomi / Redmi / POCO (MIUI / HyperOS)
    // Uses the MIUI notification badge API via reflection
    // ════════════════════════════════════════════════════════
    private void setBadgeXiaomi(Context context, int count,
                                String packageName,
                                String launcherClass) {
        // Xiaomi MIUI uses the notification badge number field.
        // The system reads notification.number to display the badge.
        // This is already handled by setNotificationBadge(),
        // but we also try the MIUI-specific field for older versions.

        try {
            // Try to set via MIUI notification extra field
            // This ensures badge works even on older MIUI versions
            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);

            // Create notification with MIUI-specific extras
            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Ensure channel exists
                setNotificationBadge(context, count);

                builder = new Notification.Builder(context, BADGE_CHANNEL_ID);
            } else {
                builder = new Notification.Builder(context);
            }

            Notification notification = builder
                    .setSmallIcon(getAppIconResourceId(context))
                    .setContentTitle("")
                    .setContentText("")
                    .setNumber(count)
                    .build();

            // Set MIUI-specific messageCount via reflection
            try {
                java.lang.reflect.Field field =
                        notification.getClass().getDeclaredField("extraNotification");
                Object extraNotification = field.get(notification);
                if (extraNotification != null) {
                    java.lang.reflect.Method method =
                            extraNotification.getClass().getDeclaredMethod(
                                    "setMessageCount", int.class);
                    method.invoke(extraNotification, count);
                    Log.d(TAG, "Xiaomi extraNotification.setMessageCount: "
                            + count);
                }
            } catch (Exception reflectEx) {
                // Reflection failed — this is expected on non-MIUI devices
                // or newer HyperOS versions
                Log.w(TAG, "Xiaomi reflection failed (expected on HyperOS): "
                        + reflectEx.getMessage());
            }

            if (count > 0) {
                nm.notify(BADGE_NOTIFICATION_ID, notification);
            } else {
                nm.cancel(BADGE_NOTIFICATION_ID);
            }

            Log.d(TAG, "Xiaomi badge set: " + count);
        } catch (Exception e) {
            Log.w(TAG, "Xiaomi badge failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    // ════════════════════════════════════════════════════════
    // BADGE STRATEGY: OPPO / Realme / OnePlus (ColorOS)
    // Uses the OPPO badge ContentProvider or broadcast
    // ════════════════════════════════════════════════════════
    private void setBadgeOPPO(Context context, int count,
                              String packageName,
                              String launcherClass) {

        // Method 1: OPPO/ColorOS ContentProvider
        try {
            android.os.Bundle extras = new android.os.Bundle();
            extras.putInt("app_badge_count", count);

            context.getContentResolver().call(
                    Uri.parse("content://com.android.badge/badge"),
                    "setAppBadgeCount", null, extras);

            Log.d(TAG, "OPPO badge ContentProvider set: " + count);
        } catch (Exception e) {
            Log.w(TAG, "OPPO ContentProvider failed: " + e.getMessage());
        }

        // Method 2: Intent broadcast for OPPO
        try {
            Intent intent = new Intent(
                    "com.oppo.unsettledevent");
            intent.putExtra("pakeageName", packageName); // Note: OPPO typo is intentional
            intent.putExtra("number", count);
            intent.putExtra("upgradeNumber", count);

            // Also try the correct spelling
            intent.putExtra("packageName", packageName);
            context.sendBroadcast(intent);

            Log.d(TAG, "OPPO broadcast sent: " + count);
        } catch (Exception e) {
            Log.w(TAG, "OPPO broadcast failed: " + e.getMessage());
        }

        // Method 3: OnePlus / newer ColorOS uses standard notification badge
        // Already handled by setNotificationBadge()
    }

    // ════════════════════════════════════════════════════════
    // BADGE STRATEGY: vivo / iQOO (FuntouchOS / OriginOS)
    // ════════════════════════════════════════════════════════
    private void setBadgeVivo(Context context, int count,
                              String packageName,
                              String launcherClass) {
        try {
            Intent intent = new Intent(
                    "launcher.action.CHANGE_APPLICATION_NOTIFICATION_NUM");
            intent.putExtra("packageName", packageName);
            intent.putExtra("className", launcherClass);
            intent.putExtra("notificationNum", count);
            context.sendBroadcast(intent);

            Log.d(TAG, "vivo badge broadcast sent: " + count);
        } catch (Exception e) {
            Log.w(TAG, "vivo badge failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    // ════════════════════════════════════════════════════════
    // BADGE STRATEGY: ZTE
    // ════════════════════════════════════════════════════════
    private void setBadgeZTE(Context context, int count,
                             String packageName,
                             String launcherClass) {
        try {
            android.os.Bundle extras = new android.os.Bundle();
            extras.putInt("app_badge_count", count);
            extras.putString("app_badge_component_name",
                    new ComponentName(packageName, launcherClass)
                            .flattenToString());

            context.getContentResolver().call(
                    Uri.parse("content://com.android.launcher3.badge/badge"),
                    "setAppBadgeCount", null, extras);

            Log.d(TAG, "ZTE badge set: " + count);
        } catch (Exception e) {
            Log.w(TAG, "ZTE badge failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    // ════════════════════════════════════════════════════════
    // BADGE STRATEGY: Sony (Xperia)
    // ════════════════════════════════════════════════════════
    private void setBadgeSony(Context context, int count,
                              String packageName,
                              String launcherClass) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("badge_count", count);
            cv.put("package_name", packageName);
            cv.put("activity_name", launcherClass);

            // Check if using async or sync provider
            boolean isSonyShell = isPackageInstalled(context,
                    "com.sonymobile.home");

            if (isSonyShell) {
                context.getContentResolver().insert(
                        Uri.parse("content://com.sonymobile.home"
                                + ".resourceprovider/badge"),
                        cv);
            } else {
                // Try alternative Sony provider
                context.getContentResolver().insert(
                        Uri.parse("content://com.sonyericsson.home"
                                + ".resourceprovider/badge"),
                        cv);
            }

            Log.d(TAG, "Sony badge set: " + count);
        } catch (Exception e) {
            Log.w(TAG, "Sony badge failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    // ════════════════════════════════════════════════════════
    // BADGE STRATEGY: HTC (Sense)
    // ════════════════════════════════════════════════════════
    private void setBadgeHTC(Context context, int count,
                             String packageName,
                             String launcherClass) {
        try {
            ComponentName component =
                    new ComponentName(packageName, launcherClass);

            Intent intent = new Intent("com.htc.launcher.action.SET_NOTIFICATION");
            intent.putExtra("com.htc.launcher.extra.COMPONENT", component.flattenToShortString());
            intent.putExtra("com.htc.launcher.extra.COUNT", count);
            context.sendBroadcast(intent);

            // Also try alternative HTC action
            Intent intent2 = new Intent("com.htc.launcher.action.UPDATE_SHORTCUT");
            intent2.putExtra("packagename", packageName);
            intent2.putExtra("count", count);
            context.sendBroadcast(intent2);

            Log.d(TAG, "HTC badge set: " + count);
        } catch (Exception e) {
            Log.w(TAG, "HTC badge failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    // ════════════════════════════════════════════════════════
    // BADGE STRATEGY: ASUS (ZenUI)
    // ════════════════════════════════════════════════════════
    private void setBadgeASUS(Context context, int count,
                              String packageName,
                              String launcherClass) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("badge_count", count);
            cv.put("package_name", packageName);
            cv.put("activity_name", launcherClass);

            context.getContentResolver().insert(
                    Uri.parse("content://com.asus.launcher.badge/badge"),
                    cv);

            Log.d(TAG, "ASUS badge set: " + count);
        } catch (Exception e) {
            Log.w(TAG, "ASUS badge failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    // ════════════════════════════════════════════════════════
    // BADGE STRATEGY: Generic broadcast fallback
    // Works with third-party launchers (Nova, Action, etc.)
    // ════════════════════════════════════════════════════════
    private void setBadgeGenericBroadcast(Context context, int count,
                                          String packageName,
                                          String launcherClass) {
        // Generic badge broadcast — supported by many launchers
        try {
            Intent intent = new Intent("android.intent.action.BADGE_COUNT_UPDATE");
            intent.putExtra("badge_count", count);
            intent.putExtra("badge_count_package_name", packageName);
            intent.putExtra("badge_count_class_name", launcherClass);
            context.sendBroadcast(intent);
        } catch (Exception e) {
            Log.w(TAG, "Generic broadcast failed: " + e.getMessage());
        }

        // Alternative generic intent used by some launchers
        try {
            Intent intent2 = new Intent("me.leolin.shortcutbadger.BADGE_COUNT_UPDATE");
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
    private String getLauncherClassName() {
        Context context = cordova.getContext();
        android.content.pm.PackageManager pm = context.getPackageManager();

        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> resolveInfos = pm.queryIntentActivities(intent, 0);

        for (ResolveInfo resolveInfo : resolveInfos) {
            if (resolveInfo.activityInfo.packageName
                    .equals(context.getPackageName())) {
                return resolveInfo.activityInfo.name;
            }
        }
        return null;
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
