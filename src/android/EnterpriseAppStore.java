package com.enterprise.appstore;

import android.app.Activity;
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

    private static final String TAG = "EnterpriseAppStore";
    private long downloadId = -1;
    private CallbackContext downloadCallback;
    private Handler progressHandler;
    private Runnable progressRunnable;
    private BroadcastReceiver downloadReceiver;
    
    private static final int REQUEST_INSTALL_PERMISSION = 1001;

     // ✅ Lưu lại thông tin pending install
    // khi user đi Settings rồi quay lại
    private String pendingInstallUrl      = null;
    private String pendingInstallFileName = null;
    private CallbackContext pendingInstallCallback = null;
    private File   pendingApkFile   = null;
    private String pendingFilePath  = null;


    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext)
            throws JSONException {

        switch (action) {
            case "downloadAndInstall":
                String url      = args.getString(0);
                String fileName = args.getString(1);
                // Tự động thêm .apk
                if (!fileName.toLowerCase().endsWith(".apk")) {
                    fileName = fileName + ".apk";
                }
                this.downloadAndInstall(url, fileName, callbackContext);
                return true;

            case "checkInstallPermission":
                this.checkInstallPermission(callbackContext);
                return true;

            case "requestInstallPermission":
                String reqUrl      = args.optString(0, null);
                String reqFileName = args.optString(1, null);
                this.requestInstallPermission(
                        reqUrl, reqFileName, callbackContext);
                return true;

            case "getAppVersion":
                this.getAppVersion(args.getString(0), callbackContext);
                return true;

            case "isAppInstalled":
                this.isAppInstalled(args.getString(0), callbackContext);
                return true;

            case "cancelDownload":
                this.cancelDownload(callbackContext);
                return true;
        }
        return false;
    }

    // ─────────────────────────────────────────────
    // DOWNLOAD & INSTALL APK
    // ─────────────────────────────────────────────
    private void downloadAndInstall(String url, String fileName,
                                    CallbackContext callbackContext) {
        this.downloadCallback = callbackContext;

        cordova.getThreadPool().execute(() -> {
            try {
                Context context = cordova.getContext();
                DownloadManager dm = (DownloadManager)
                        context.getSystemService(Context.DOWNLOAD_SERVICE);

                // Tạo thư mục Download riêng
                File downloadDir = context.getExternalFilesDir(
                        Environment.DIRECTORY_DOWNLOADS);
                if (downloadDir != null && !downloadDir.exists()) {
                    downloadDir.mkdirs();
                }

                // Xóa file cũ nếu tồn tại
                File apkFile = new File(downloadDir, fileName);
                if (apkFile.exists()) apkFile.delete();

                // Cấu hình DownloadManager Request
                DownloadManager.Request request = new DownloadManager.Request(
                        Uri.parse(url));
                request.setTitle(fileName);
                request.setDescription("Downloading application...");
                request.setMimeType("application/vnd.android.package-archive");
                request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE);
                request.setDestinationInExternalFilesDir(
                        context,
                        Environment.DIRECTORY_DOWNLOADS,
                        fileName);

                downloadId = dm.enqueue(request);
                Log.d(TAG, "Download started. ID: " + downloadId);

                // Gửi trạng thái ban đầu
                sendProgress(callbackContext, "DOWNLOADING", 0, "Download started");

                // Bắt đầu track progress
                startProgressTracking(dm, callbackContext);

                // Register receiver khi download hoàn tất
                registerDownloadReceiver(dm, apkFile, callbackContext);

            } catch (Exception e) {
                Log.e(TAG, "Download error: " + e.getMessage());
                callbackContext.error("DOWNLOAD_ERROR: " + e.getMessage());
            }
        });
    }

    // ─────────────────────────────────────────────
    // TRACK PROGRESS mỗi 500ms
    // ─────────────────────────────────────────────
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
                    int bytesDownloaded = cursor.getInt(
                            cursor.getColumnIndexOrThrow(
                                    DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    int bytesTotal = cursor.getInt(
                            cursor.getColumnIndexOrThrow(
                                    DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    int status = cursor.getInt(
                            cursor.getColumnIndexOrThrow(
                                    DownloadManager.COLUMN_STATUS));
                    cursor.close();

                    if (status == DownloadManager.STATUS_RUNNING && bytesTotal > 0) {
                        int progress = (int) ((bytesDownloaded * 100L) / bytesTotal);
                        sendProgress(callbackContext, "DOWNLOADING", progress,
                                "Downloaded " + formatBytes(bytesDownloaded)
                                        + " / " + formatBytes(bytesTotal));
                        // Tiếp tục track
                        progressHandler.postDelayed(this, 500);
                    } else if (status == DownloadManager.STATUS_PAUSED) {
                        sendProgress(callbackContext, "PAUSED", 0, "Download paused");
                        progressHandler.postDelayed(this, 1000);
                    } else if (status == DownloadManager.STATUS_FAILED) {
                        callbackContext.error("DOWNLOAD_FAILED");
                    }
                }
            }
        };
        progressHandler.postDelayed(progressRunnable, 500);
    }

    // ─────────────────────────────────────────────
    // BROADCAST RECEIVER — Khi download xong → Install
    // ─────────────────────────────────────────────

    
    private void registerDownloadReceiver(DownloadManager dm, File apkFile,
                                        CallbackContext callbackContext) {
        Context context = cordova.getContext();

        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                long id = intent.getLongExtra(
                        DownloadManager.EXTRA_DOWNLOAD_ID, -1);

                Log.d(TAG, "onReceive downloadId=" + id
                        + " | expected=" + downloadId);

                if (id != downloadId) return;

                // Dừng progress tracking
                if (progressHandler != null) {
                    progressHandler.removeCallbacks(progressRunnable);
                }

                // Query download status
                DownloadManager.Query query = new DownloadManager.Query();
                query.setFilterById(downloadId);
                Cursor cursor = dm.query(query);

                if (cursor == null || !cursor.moveToFirst()) {
                    Log.e(TAG, "Cursor null or empty!");
                    callbackContext.error("DOWNLOAD_ERROR: Cannot query status");
                    return;
                }

                int status = cursor.getInt(cursor.getColumnIndexOrThrow(
                        DownloadManager.COLUMN_STATUS));
                String localUri = cursor.getString(cursor.getColumnIndexOrThrow(
                        DownloadManager.COLUMN_LOCAL_URI));
                int reason = cursor.getInt(cursor.getColumnIndexOrThrow(
                        DownloadManager.COLUMN_REASON));
                cursor.close();

                Log.d(TAG, "Download status=" + status
                        + " | localUri=" + localUri
                        + " | reason=" + reason);

                if (status == DownloadManager.STATUS_SUCCESSFUL) {

                    // ── Resolve file thực tế ──────────────────────
                    File realFile = resolveDownloadedFile(localUri, apkFile);

                    Log.d(TAG, "realFile path     = " + realFile.getAbsolutePath());
                    Log.d(TAG, "realFile exists() = " + realFile.exists());
                    Log.d(TAG, "realFile size     = " + realFile.length() + " bytes");

                    if (!realFile.exists()) {
                        Log.e(TAG, "File NOT FOUND after download!");
                        sendStatus(callbackContext, "ERROR", 0,
                                "File not found after download: "
                                + realFile.getAbsolutePath(), "", false);
                        return;
                    }

                    String filePath = realFile.getAbsolutePath();

                    // ── Gửi DOWNLOAD_COMPLETE ─────────────────────
                    sendStatus(callbackContext, "DOWNLOAD_COMPLETE", 100,
                            "Download complete. Starting install...",
                            filePath, true); // keepCallback=true

                    // ── Gọi installApk trên UI Thread ────────────
                    // ✅ QUAN TRỌNG: startActivity() PHẢI chạy trên UI thread
                    final File fileToInstall = realFile;
                    final String finalPath   = filePath;

                    cordova.getActivity().runOnUiThread(() -> {
                        Log.d(TAG, "installApk() called on UI thread");
                        installApk(fileToInstall, finalPath, callbackContext);
                    });

                } else {
                    // Download thất bại
                    String errMsg = getDownloadErrorReason(reason);
                    Log.e(TAG, "Download FAILED: status=" + status
                            + " reason=" + reason + " → " + errMsg);

                    sendStatus(callbackContext, "ERROR", 0,
                            "Download failed: " + errMsg, "", false);
                }

                // Unregister receiver
                try { context.unregisterReceiver(this); }
                catch (Exception ignored) {}
                downloadId = -1;
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


    // ─────────────────────────────────────────────
    // HELPER: Resolve đường dẫn file thực tế
    // ─────────────────────────────────────────────
    private File resolveDownloadedFile(String localUri, File fallbackFile) {
        // Cách 1: Parse từ localUri của DownloadManager
        if (localUri != null) {
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
                Log.w(TAG, "Cannot parse localUri: " + e.getMessage());
            }
        }

        // Cách 2: Dùng fallback file object
        if (fallbackFile != null && fallbackFile.exists()) {
            Log.d(TAG, "Using fallback file: " + fallbackFile.getAbsolutePath());
            return fallbackFile;
        }

        // Cách 3: Tìm trong Public Downloads
        File publicDownloads = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        if (fallbackFile != null) {
            File inPublic = new File(publicDownloads, fallbackFile.getName());
            if (inPublic.exists()) {
                Log.d(TAG, "Found in public downloads: " + inPublic.getAbsolutePath());
                return inPublic;
            }
        }

        // Không tìm thấy → trả về fallback để xử lý lỗi
        Log.e(TAG, "File not found anywhere!");
        return fallbackFile != null ? fallbackFile : new File("");
    }


    // ─────────────────────────────────────────────
    // INSTALL APK — FileProvider cho Android 7+
    // ─────────────────────────────────────────────
    
    private void installApk(File apkFile, String filePath,
                        CallbackContext callbackContext) {
        // ✅ Double-check chạy trên UI thread
        if (cordova.getActivity().isFinishing()) {
            Log.e(TAG, "Activity is finishing, cannot install");
            sendStatus(callbackContext, "ERROR", 0,
                    "Activity is finishing", filePath, false);
            return;
        }

        // ✅ Check file tồn tại lần cuối
        if (apkFile == null || !apkFile.exists()) {
            Log.e(TAG, "APK file not found: "
                    + (apkFile != null ? apkFile.getAbsolutePath() : "null"));
            sendStatus(callbackContext, "ERROR", 0,
                    "APK file not found: " + filePath, filePath, false);
            return;
        }

        Log.d(TAG, "installApk() file=" + apkFile.getAbsolutePath()
                + " size=" + apkFile.length());

        // ✅ Check permission (Android 8+)
        Context context = cordova.getContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.getPackageManager().canRequestPackageInstalls()) {
                Log.w(TAG, "No INSTALL permission → requesting...");

                // Lưu pending để resume sau
                pendingInstallUrl      = null; // Không cần download lại
                pendingInstallFileName = apkFile.getName();
                pendingInstallCallback = callbackContext;
                pendingApkFile         = apkFile; // ✅ Thêm field này
                pendingFilePath        = filePath; // ✅ Thêm field này

                Intent settingsIntent = new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + context.getPackageName()));
                cordova.setActivityResultCallback(this);
                cordova.getActivity().startActivityForResult(
                        settingsIntent, REQUEST_INSTALL_PERMISSION);

                sendStatus(callbackContext, "WAITING_PERMISSION", 100,
                        "Please enable 'Install unknown apps' then return.",
                        filePath, true);
                return;
            }
        }

        // ✅ Tiến hành install
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            Uri apkUri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Android 7+ → FileProvider
                apkUri = FileProvider.getUriForFile(
                        context,
                        context.getPackageName()
                                + ".enterprise.appstore.provider",
                        apkFile);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Log.d(TAG, "Using FileProvider URI: " + apkUri);
            } else {
                apkUri = Uri.fromFile(apkFile);
                Log.d(TAG, "Using file URI: " + apkUri);
            }

            intent.setDataAndType(apkUri,
                    "application/vnd.android.package-archive");

            Log.d(TAG, "Starting install activity...");
            context.startActivity(intent);
            Log.d(TAG, "Install activity started ✅");

            // ✅ Gửi INSTALL_PROMPT — callback CUỐI (keepCallback=false)
            sendStatus(callbackContext, "INSTALL_PROMPT", 100,
                    "Installation dialog opened.", filePath, false);

        } catch (Exception e) {
            Log.e(TAG, "installApk ERROR: " + e.getMessage(), e);
            sendStatus(callbackContext, "ERROR", 0,
                    "Install error: " + e.getMessage(), filePath, false);
        }
    }

    
    private void sendProgressWithPath(CallbackContext cb, String status,
                                    int percent, String message,
                                    String filePath) {
        try {
            JSONObject data = new JSONObject();
            data.put("status", status);
            data.put("progress", percent);
            data.put("message", message);
            data.put("filePath", filePath); // ✅ Thêm filePath vào mọi callback

            PluginResult result = new PluginResult(
                    PluginResult.Status.OK, data);
            result.setKeepCallback(true);
            cb.sendPluginResult(result);
        } catch (JSONException e) {
            Log.e(TAG, "sendProgressWithPath error: " + e.getMessage());
        }
    }



    // ─────────────────────────────────────────────
    // GET APP VERSION
    // ─────────────────────────────────────────────
    private void getAppVersion(String packageName, CallbackContext callbackContext) {
        try {
            Context context = cordova.getContext();
            String version = context.getPackageManager()
                    .getPackageInfo(packageName, 0).versionName;
            callbackContext.success(version);
        } catch (Exception e) {
            callbackContext.error("APP_NOT_FOUND");
        }
    }

    // ─────────────────────────────────────────────
    // CHECK APP INSTALLED
    // ─────────────────────────────────────────────
    private void isAppInstalled(String packageName, CallbackContext callbackContext) {
        try {
            cordova.getContext().getPackageManager()
                    .getPackageInfo(packageName, 0);
            callbackContext.success(1); // installed
        } catch (Exception e) {
            callbackContext.success(0); // not installed
        }
    }

    // ─────────────────────────────────────────────
    // CANCEL DOWNLOAD
    // ─────────────────────────────────────────────
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

    // ─────────────────────────────────────────────
    // HELPER: Gửi progress callback (keepCallback=true)
    // ─────────────────────────────────────────────
    private void sendProgress(CallbackContext cb, String status,
                               int percent, String message) {
        try {
            JSONObject data = new JSONObject();
            data.put("status", status);
            data.put("progress", percent);
            data.put("message", message);

            PluginResult result = new PluginResult(
                    PluginResult.Status.OK, data);
            result.setKeepCallback(true); // ← Giữ callback để gửi nhiều lần
            cb.sendPluginResult(result);
        } catch (JSONException e) {
            Log.e(TAG, "sendProgress error: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    // HELPER: Format bytes
    // ─────────────────────────────────────────────
    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    
    private void checkInstallPermission(CallbackContext callbackContext) {
        try {
            boolean canInstall = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                canInstall = cordova.getContext()
                        .getPackageManager()
                        .canRequestPackageInstalls();
            }
            JSONObject result = new JSONObject();
            result.put("hasPermission", canInstall);
            callbackContext.success(result);
        } catch (JSONException e) {
            callbackContext.error("ERROR_CHECK_PERMISSION");
        }
    }

    
    // ─────────────────────────────────────────────
    // REQUEST PERMISSION
    // ✅ Lưu url + fileName để tự động resume sau khi
    //    user bật permission và quay lại app
    // ─────────────────────────────────────────────
    private void requestInstallPermission(String url, String fileName,
                                          CallbackContext callbackContext) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Context context = cordova.getContext();

            if (!context.getPackageManager().canRequestPackageInstalls()) {

                // ✅ Lưu lại thông tin để resume sau khi user back
                pendingInstallUrl      = url;
                pendingInstallFileName = fileName;
                pendingInstallCallback = callbackContext;

                // Mở Settings → trang permission của app này
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + context.getPackageName())
                );

                // Dùng startActivityForResult để nhận callback
                // khi user quay lại
                cordova.setActivityResultCallback(this);
                cordova.getActivity().startActivityForResult(
                        intent, REQUEST_INSTALL_PERMISSION);

                // Thông báo đang chờ user cấp permission
                sendStatus(callbackContext,
                        "WAITING_PERMISSION", 0,
                        "Please enable 'Install unknown apps' then return to app.",
                        "", true); // keepCallback = true → chờ tiếp

            } else {
                // Đã có permission rồi
                if (url != null && !url.isEmpty()) {
                    // Có url → tiến hành download luôn
                    downloadAndInstall(url, fileName, callbackContext);
                } else {
                    // Không có url → chỉ báo đã có permission
                    try {
                        JSONObject result = new JSONObject();
                        result.put("status", "PERMISSION_ALREADY_GRANTED");
                        result.put("hasPermission", true);
                        callbackContext.success(result);
                    } catch (JSONException e) {
                        callbackContext.success("PERMISSION_ALREADY_GRANTED");
                    }
                }
            }
        } else {
            // Android < 8 không cần permission
            if (url != null && !url.isEmpty()) {
                downloadAndInstall(url, fileName, callbackContext);
            } else {
                callbackContext.success("PERMISSION_NOT_NEEDED");
            }
        }
    }

    // ─────────────────────────────────────────────
    //  onActivityResult — Nhận kết quả từ Settings
    //    Tự động resume download+install sau khi
    //    user bật permission và back về app
    // ─────────────────────────────────────────────
   @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_INSTALL_PERMISSION) return;

        boolean hasPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && cordova.getContext().getPackageManager()
                        .canRequestPackageInstalls();

        Log.d(TAG, "onActivityResult: hasPermission=" + hasPermission);

        if (pendingInstallCallback == null) return;

        if (hasPermission) {
            sendStatus(pendingInstallCallback, "PERMISSION_GRANTED", 0,
                    "Permission granted. Resuming...", "", true);

            if (pendingApkFile != null && pendingApkFile.exists()) {
                // ✅ Case: Đã download rồi → install luôn
                Log.d(TAG, "Resume install for: " + pendingApkFile.getName());
                final File f  = pendingApkFile;
                final String p = pendingFilePath;
                cordova.getActivity().runOnUiThread(() ->
                        installApk(f, p, pendingInstallCallback));

            } else if (pendingInstallUrl != null
                    && !pendingInstallUrl.isEmpty()) {
                // ✅ Case: Chưa download → download + install
                Log.d(TAG, "Resume download for: " + pendingInstallFileName);
                downloadAndInstall(pendingInstallUrl,
                        pendingInstallFileName, pendingInstallCallback);
            }
        } else {
            // User từ chối
            sendStatus(pendingInstallCallback, "PERMISSION_DENIED", 0,
                    "Install permission denied.", "", false);
        }

        // Reset tất cả pending state
        pendingInstallUrl      = null;
        pendingInstallFileName = null;
        pendingInstallCallback = null;
        pendingApkFile         = null;
        pendingFilePath        = null;
    }

    // ─────────────────────────────────────────────
    // HELPER: Download error reason
    // ─────────────────────────────────────────────
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
    // ─────────────────────────────────────────────
    // HELPER: Gửi status callback
    // ─────────────────────────────────────────────
    private void sendStatus(CallbackContext cb, String status,
                             int progress, String message,
                             String filePath, boolean keepCallback) {
        try {
            JSONObject data = new JSONObject();
            data.put("status",   status);
            data.put("progress", progress);
            data.put("message",  message);
            data.put("filePath", filePath);

            PluginResult result = new PluginResult(
                    PluginResult.Status.OK, data);
            result.setKeepCallback(keepCallback);
            cb.sendPluginResult(result);
        } catch (JSONException e) {
            Log.e(TAG, "sendStatus error: " + e.getMessage());
        }
    }


}
