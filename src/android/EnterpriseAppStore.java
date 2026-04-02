package com.enterprise.appstore;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

public class EnterpriseAppStore extends CordovaPlugin {

    private static final String TAG                      = "EnterpriseAppStore";
    private static final int    REQUEST_INSTALL_PERMISSION = 1001;

    // ── Download state ──────────────────────────────────────
    private long               downloadId       = -1;
    private CallbackContext    downloadCallback = null;
    private Handler            progressHandler  = null;
    private Runnable           progressRunnable = null;
    private BroadcastReceiver  downloadReceiver = null;

    // ── Pending install state (after Settings returns) ──────
    private String          pendingInstallUrl      = null;
    private String          pendingInstallFileName = null;
    private CallbackContext pendingInstallCallback = null;
    private File            pendingApkFile         = null;
    private String          pendingFilePath        = null;

    // ════════════════════════════════════════════════════════
    // EXECUTE — Action router
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
                if (!fileName.isEmpty() && !fileName.toLowerCase().endsWith(".apk")) {
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

            case "cancelDownload":
                cancelDownload(callbackContext);
                return true;
        }
        return false;
    }

    // ════════════════════════════════════════════════════════
    // DOWNLOAD AND INSTALL
    // ════════════════════════════════════════════════════════
    private void downloadAndInstall(String url, String fileName,
                                    CallbackContext callbackContext) {
        this.downloadCallback = callbackContext;

        cordova.getThreadPool().execute(() -> {
            try {
                Context context = cordova.getContext();
                DownloadManager dm = (DownloadManager)
                        context.getSystemService(Context.DOWNLOAD_SERVICE);

                // ✅ Public Downloads — dễ tìm, không bị MIUI chặn
                File downloadDir = Environment
                        .getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS);
                if (!downloadDir.exists()) downloadDir.mkdirs();

                // Xóa file cũ nếu tồn tại
                File apkFile = new File(downloadDir, fileName);
                if (apkFile.exists()) {
                    apkFile.delete();
                    Log.d(TAG, "Deleted old file: " + apkFile.getAbsolutePath());
                }

                // Cấu hình DownloadManager request
                DownloadManager.Request request =
                        new DownloadManager.Request(Uri.parse(url));
                request.setTitle(fileName);
                request.setDescription("Downloading " + fileName);
                request.setMimeType("application/vnd.android.package-archive");
                request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS, fileName);

                downloadId = dm.enqueue(request);
                Log.d(TAG, "Download enqueued. ID=" + downloadId
                         + " file=" + apkFile.getAbsolutePath());

                // ✅ Gửi DOWNLOADING 0% — KHÔNG phải DOWNLOAD_COMPLETE
                sendStatus(callbackContext, "DOWNLOADING", 0,
                        "Download started...", "", true);

                // Bắt đầu track progress
                startProgressTracking(dm, callbackContext);

                // Đăng ký receiver chờ download hoàn tất
                registerDownloadReceiver(dm, apkFile, callbackContext);

            } catch (Exception e) {
                Log.e(TAG, "downloadAndInstall error: " + e.getMessage(), e);
                sendStatus(callbackContext, "ERROR", 0,
                        "Download error: " + e.getMessage(), "", false);
            }
        });
    }

    // ════════════════════════════════════════════════════════
    // PROGRESS TRACKING — Cập nhật mỗi 500ms
    // ════════════════════════════════════════════════════════
    private void startProgressTracking(DownloadManager dm,
                                       CallbackContext callbackContext) {
        progressHandler = new Handler(Looper.getMainLooper());
        progressRunnable = new Runnable() {
            @Override
            public void run() {
                if (downloadId == -1) return;

                DownloadManager.Query query = new DownloadManager.Query();
                query.setFilterById(downloadId);
                Cursor cursor = dm.query(query);

                if (cursor != null && cursor.moveToFirst()) {
                    long downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    long total = cursor.getLong(cursor.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    int status = cursor.getInt(cursor.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_STATUS));
                    cursor.close();

                    if (status == DownloadManager.STATUS_RUNNING && total > 0) {
                        int progress = (int) ((downloaded * 100L) / total);
                        String msg = "Downloading " + formatBytes(downloaded)
                                   + " / " + formatBytes(total);
                        // ✅ Gửi DOWNLOADING với progress thực
                        sendStatus(callbackContext, "DOWNLOADING",
                                progress, msg, "", true);
                        progressHandler.postDelayed(this, 500);

                    } else if (status == DownloadManager.STATUS_PAUSED) {
                        sendStatus(callbackContext, "DOWNLOADING",
                                0, "Download paused...", "", true);
                        progressHandler.postDelayed(this, 1000);

                    } else if (status == DownloadManager.STATUS_FAILED) {
                        sendStatus(callbackContext, "ERROR",
                                0, "Download failed", "", false);
                    }
                    // STATUS_SUCCESSFUL / STATUS_PENDING → stop tracking
                }
            }
        };
        progressHandler.postDelayed(progressRunnable, 500);
    }

    // ════════════════════════════════════════════════════════
    // BROADCAST RECEIVER — Nhận khi download HOÀN TẤT
    // ════════════════════════════════════════════════════════
    private void registerDownloadReceiver(DownloadManager dm, File apkFile,
                                          CallbackContext callbackContext) {
        Context context = cordova.getContext();

        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                long id = intent.getLongExtra(
                        DownloadManager.EXTRA_DOWNLOAD_ID, -1);

                Log.d(TAG, "BroadcastReceiver: id=" + id
                         + " expected=" + downloadId);

                if (id != downloadId) return;

                // Dừng progress tracking
                if (progressHandler != null) {
                    progressHandler.removeCallbacks(progressRunnable);
                }

                // Query kết quả download
                DownloadManager.Query query = new DownloadManager.Query();
                query.setFilterById(downloadId);
                Cursor cursor = dm.query(query);

                if (cursor == null || !cursor.moveToFirst()) {
                    Log.e(TAG, "Cursor null after download!");
                    sendStatus(callbackContext, "ERROR", 0,
                            "Cannot read download result", "", false);
                    cleanup(context);
                    return;
                }

                int    status   = cursor.getInt(cursor.getColumnIndexOrThrow(
                                        DownloadManager.COLUMN_STATUS));
                String localUri = cursor.getString(cursor.getColumnIndexOrThrow(
                                        DownloadManager.COLUMN_LOCAL_URI));
                int    reason   = cursor.getInt(cursor.getColumnIndexOrThrow(
                                        DownloadManager.COLUMN_REASON));
                cursor.close();

                Log.d(TAG, "Download result: status=" + status
                         + " localUri=" + localUri
                         + " reason=" + reason);

                if (status == DownloadManager.STATUS_SUCCESSFUL) {

                    // ✅ Resolve đường dẫn file thực tế
                    File realFile = resolveDownloadedFile(localUri, apkFile);
                    String filePath = realFile.getAbsolutePath();

                    Log.d(TAG, "Real file: " + filePath);
                    Log.d(TAG, "File exists: " + realFile.exists());
                    Log.d(TAG, "File size: " + realFile.length() + " bytes");

                    if (!realFile.exists() || realFile.length() == 0) {
                        Log.e(TAG, "File not found or empty after download!");
                        sendStatus(callbackContext, "ERROR", 0,
                                "Downloaded file not found: " + filePath,
                                filePath, false);
                        cleanup(context);
                        return;
                    }

                    // ✅ Gửi DOWNLOAD_COMPLETE kèm filePath
                    sendStatus(callbackContext, "DOWNLOAD_COMPLETE", 100,
                            "Download complete. Starting installation...",
                            filePath, true);

                    // ✅ Gọi installApk trên UI Thread
                    final File   finalFile = realFile;
                    final String finalPath = filePath;
                    cordova.getActivity().runOnUiThread(() -> {
                        Log.d(TAG, "installApk() on UI thread: " + finalPath);
                        installApk(finalFile, finalPath, callbackContext);
                    });

                } else {
                    String errMsg = getDownloadErrorReason(reason);
                    Log.e(TAG, "Download FAILED: " + errMsg);
                    sendStatus(callbackContext, "ERROR", 0,
                            "Download failed: " + errMsg, "", false);
                }

                cleanup(context);
            }
        };

        IntentFilter filter = new IntentFilter(
                DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(downloadReceiver, filter,
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(downloadReceiver, filter);
        }
    }

    // ════════════════════════════════════════════════════════
    // RESOLVE FILE PATH
    // ════════════════════════════════════════════════════════
    private File resolveDownloadedFile(String localUri, File fallbackFile) {
        // Cách 1: Parse từ localUri
        if (localUri != null && !localUri.isEmpty()) {
            try {
                Uri uri = Uri.parse(localUri);
                if ("file".equals(uri.getScheme())) {
                    File f = new File(uri.getPath());
                    if (f.exists()) {
                        Log.d(TAG, "Resolved from localUri: " + f.getAbsolutePath());
                        return f;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Parse localUri failed: " + e.getMessage());
            }
        }

        // Cách 2: Fallback file object
        if (fallbackFile != null && fallbackFile.exists()) {
            Log.d(TAG, "Using fallback: " + fallbackFile.getAbsolutePath());
            return fallbackFile;
        }

        // Cách 3: Tìm trong Public Downloads
        if (fallbackFile != null) {
            File pub = new File(
                    Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS),
                    fallbackFile.getName());
            if (pub.exists()) {
                Log.d(TAG, "Found in public downloads: " + pub.getAbsolutePath());
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

        // Check file tồn tại
        if (apkFile == null || !apkFile.exists()) {
            Log.e(TAG, "APK file not found: " + filePath);
            sendStatus(callbackContext, "ERROR", 0,
                    "APK file not found: " + filePath, filePath, false);
            return;
        }

        Context context = cordova.getContext();

        // Check permission Android 8+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.getPackageManager().canRequestPackageInstalls()) {
                Log.w(TAG, "No INSTALL permission — saving pending state");

                // Lưu pending để resume sau khi user bật permission
                pendingApkFile         = apkFile;
                pendingFilePath        = filePath;
                pendingInstallCallback = callbackContext;
                pendingInstallUrl      = null; // Đã download rồi

                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + context.getPackageName()));
                cordova.setActivityResultCallback(this);
                cordova.getActivity().startActivityForResult(
                        intent, REQUEST_INSTALL_PERMISSION);

                sendStatus(callbackContext, "WAITING_PERMISSION", 100,
                        "Enable 'Install unknown apps' then return to app.",
                        filePath, true);
                return;
            }
        }

        // Tiến hành install
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
            } else {
                apkUri = Uri.fromFile(apkFile);
            }

            intent.setDataAndType(apkUri,
                    "application/vnd.android.package-archive");

            Log.d(TAG, "Starting install: uri=" + apkUri);
            context.startActivity(intent);
            Log.d(TAG, "Install activity started ✅");

            // ✅ INSTALL_PROMPT — callback CUỐI, keepCallback=false
            sendStatus(callbackContext, "INSTALL_PROMPT", 100,
                    "Installation dialog opened.", filePath, false);

        } catch (Exception e) {
            Log.e(TAG, "installApk error: " + e.getMessage(), e);
            sendStatus(callbackContext, "ERROR", 0,
                    "Install error: " + e.getMessage(), filePath, false);
        }
    }

    // ════════════════════════════════════════════════════════
    // ON ACTIVITY RESULT — Nhận kết quả từ Settings
    // ════════════════════════════════════════════════════════
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_INSTALL_PERMISSION) return;

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
                // ✅ Đã download rồi → install thẳng
                Log.d(TAG, "Resume install: " + pendingApkFile.getName());
                final File   f = pendingApkFile;
                final String p = pendingFilePath;
                final CallbackContext cb = pendingInstallCallback;
                cordova.getActivity().runOnUiThread(() -> installApk(f, p, cb));

            } else if (pendingInstallUrl != null
                    && !pendingInstallUrl.isEmpty()) {
                // ✅ Chưa download → download + install
                Log.d(TAG, "Resume download: " + pendingInstallFileName);
                downloadAndInstall(pendingInstallUrl,
                        pendingInstallFileName, pendingInstallCallback);
            }
        } else {
            // User từ chối
            sendStatus(pendingInstallCallback, "PERMISSION_DENIED", 0,
                    "Install permission denied. Cannot install APK.",
                    "", false);
        }

        // Reset tất cả pending state
        pendingInstallUrl      = null;
        pendingInstallFileName = null;
        pendingInstallCallback = null;
        pendingApkFile         = null;
        pendingFilePath        = null;
    }

    // ════════════════════════════════════════════════════════
    // CHECK PERMISSION
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
    // REQUEST PERMISSION
    // ════════════════════════════════════════════════════════
    private void requestInstallPermission(String url, String fileName,
                                          CallbackContext callbackContext) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Context context = cordova.getContext();

            if (!context.getPackageManager().canRequestPackageInstalls()) {
                // Lưu pending
                pendingInstallUrl      = url;
                pendingInstallFileName = fileName;
                pendingInstallCallback = callbackContext;

                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + context.getPackageName()));
                cordova.setActivityResultCallback(this);
                cordova.getActivity().startActivityForResult(
                        intent, REQUEST_INSTALL_PERMISSION);

                sendStatus(callbackContext, "WAITING_PERMISSION", 0,
                        "Enable 'Install unknown apps' then return to app.",
                        "", true);
            } else {
                // Đã có permission → download luôn nếu có URL
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
    // ════════════════════════════════════════════════════════
    private void getAppVersion(String packageName,
                               CallbackContext callbackContext) {
        try {
            String version = cordova.getContext().getPackageManager()
                    .getPackageInfo(packageName, 0).versionName;
            callbackContext.success(version);
        } catch (Exception e) {
            callbackContext.error("APP_NOT_FOUND: " + packageName);
        }
    }

    // ════════════════════════════════════════════════════════
    // IS APP INSTALLED
    // ════════════════════════════════════════════════════════
    private void isAppInstalled(String packageName,
                                CallbackContext callbackContext) {
        try {
            cordova.getContext().getPackageManager()
                    .getPackageInfo(packageName, 0);
            callbackContext.success(1);
        } catch (Exception e) {
            callbackContext.success(0);
        }
    }

    // ════════════════════════════════════════════════════════
    // CANCEL DOWNLOAD
    // ════════════════════════════════════════════════════════
    private void cancelDownload(CallbackContext callbackContext) {
        if (downloadId != -1) {
            DownloadManager dm = (DownloadManager) cordova.getContext()
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
    // HELPER: Gửi status callback
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

            Log.d(TAG, "sendStatus: " + status + " " + progress + "% "
                     + (filePath != null && !filePath.isEmpty()
                        ? "path=" + filePath : ""));
        } catch (JSONException e) {
            Log.e(TAG, "sendStatus error: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════
    // HELPER: Cleanup receiver
    // ════════════════════════════════════════════════════════
    private void cleanup(Context context) {
        try {
            if (downloadReceiver != null) {
                context.unregisterReceiver(downloadReceiver);
                downloadReceiver = null;
            }
        } catch (Exception ignored) {}
        downloadId = -1;
    }

    // ════════════════════════════════════════════════════════
    // HELPER: Format bytes
    // ════════════════════════════════════════════════════════
    private String formatBytes(long bytes) {
        if (bytes <= 0)           return "0 B";
        if (bytes < 1024)         return bytes + " B";
        if (bytes < 1024 * 1024)  return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    // ════════════════════════════════════════════════════════
    // HELPER: Download error reason
    // ════════════════════════════════════════════════════════
    private String getDownloadErrorReason(int reason) {
        switch (reason) {
            case DownloadManager.ERROR_CANNOT_RESUME:       return "Cannot resume";
            case DownloadManager.ERROR_DEVICE_NOT_FOUND:    return "Storage not found";
            case DownloadManager.ERROR_FILE_ALREADY_EXISTS: return "File already exists";
            case DownloadManager.ERROR_FILE_ERROR:          return "File error";
            case DownloadManager.ERROR_HTTP_DATA_ERROR:     return "HTTP data error";
            case DownloadManager.ERROR_INSUFFICIENT_SPACE:  return "Insufficient space";
            case DownloadManager.ERROR_TOO_MANY_REDIRECTS:  return "Too many redirects";
            case DownloadManager.ERROR_UNHANDLED_HTTP_CODE: return "Unhandled HTTP code";
            default: return "Unknown error (code: " + reason + ")";
        }
    }
}
