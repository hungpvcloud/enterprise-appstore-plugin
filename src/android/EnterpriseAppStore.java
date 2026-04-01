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
                if (id != downloadId) return;

                // Dừng progress tracking
                if (progressHandler != null) {
                    progressHandler.removeCallbacks(progressRunnable);
                }

                DownloadManager.Query query = new DownloadManager.Query();
                query.setFilterById(downloadId);
                Cursor cursor = dm.query(query);

                if (cursor != null && cursor.moveToFirst()) {
                    int status = cursor.getInt(cursor.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_STATUS));

                    // ✅ Lấy filePath thực tế
                    String localUri = cursor.getString(cursor.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_LOCAL_URI));
                    cursor.close();

                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        // Resolve filePath
                        String filePath = apkFile.getAbsolutePath();
                        try {
                            if (localUri != null) {
                                Uri uri = Uri.parse(localUri);
                                if ("file".equals(uri.getScheme())) {
                                    filePath = uri.getPath();
                                }
                            }
                        } catch (Exception ignored) {}

                        Log.d(TAG, "✅ Download complete: " + filePath);

                        // ✅ Step 1: Thông báo download xong (keepCallback=true)
                        sendProgressWithPath(callbackContext,
                                "DOWNLOAD_COMPLETE", 100,
                                "Download complete. Starting install...",
                                filePath);

                        // ✅ Step 2: Tiến hành install
                        // (installApk sẽ gửi callback CUỐI với keepCallback=false)
                        installApk(apkFile, filePath, callbackContext);

                    } else {
                        // ✅ Download thất bại → callback cuối
                        try {
                            JSONObject err = new JSONObject();
                            err.put("status",   "ERROR");
                            err.put("message",  "Download failed. Status: " + status);
                            err.put("progress", 0);
                            err.put("filePath", "");

                            PluginResult pr = new PluginResult(
                                    PluginResult.Status.ERROR, err);
                            pr.setKeepCallback(false);
                            callbackContext.sendPluginResult(pr);
                        } catch (JSONException e) {
                            callbackContext.error("DOWNLOAD_FAILED");
                        }
                    }
                }

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
    // INSTALL APK — FileProvider cho Android 7+
    // ─────────────────────────────────────────────
    
    
    private void installApk(File apkFile, String filePath,
                            CallbackContext callbackContext) {
        try {
            Context context = cordova.getContext();

            // ── Kiểm tra permission ──
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.getPackageManager().canRequestPackageInstalls()) {
                    try {
                        JSONObject err = new JSONObject();
                        err.put("status",   "ERROR");
                        err.put("error",    "INSTALL_PERMISSION_REQUIRED");
                        err.put("filePath", filePath);
                        err.put("message",
                            "Enable 'Install unknown apps' in Settings first.");
                        err.put("progress", 0);

                        // ✅ keepCallback = false → đây là callback CUỐI
                        PluginResult pr = new PluginResult(
                                PluginResult.Status.ERROR, err);
                        pr.setKeepCallback(false);
                        callbackContext.sendPluginResult(pr);
                    } catch (JSONException e) {
                        callbackContext.error("INSTALL_PERMISSION_REQUIRED");
                    }
                    return;
                }
            }

            // ── Build intent ──
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            Uri apkUri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                apkUri = FileProvider.getUriForFile(
                        context,
                        context.getPackageName() + ".enterprise.appstore.provider",
                        apkFile);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                apkUri = Uri.fromFile(apkFile);
            }

            intent.setDataAndType(apkUri,
                    "application/vnd.android.package-archive");
            context.startActivity(intent);

            // ✅ Gửi INSTALL_PROMPT — keepCallback = false → callback CUỐI
            try {
                JSONObject result = new JSONObject();
                result.put("status",   "INSTALL_PROMPT"); // ← JS sẽ $resolve() tại đây
                result.put("message",  "Installation dialog opened");
                result.put("progress", 100);
                result.put("filePath", filePath);
                result.put("fileUri",  apkUri.toString());

                PluginResult pr = new PluginResult(
                        PluginResult.Status.OK, result);
                pr.setKeepCallback(false); // ✅ QUAN TRỌNG — Đánh dấu callback cuối
                callbackContext.sendPluginResult(pr);

            } catch (JSONException e) {
                callbackContext.success(filePath);
            }

        } catch (Exception e) {
            Log.e(TAG, "Install error: " + e.getMessage());
            callbackContext.error("INSTALL_ERROR: " + e.getMessage());
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
        if (requestCode == REQUEST_INSTALL_PERMISSION) {

            Context context = cordova.getContext();
            boolean hasPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && context.getPackageManager().canRequestPackageInstalls();

            Log.d(TAG, "onActivityResult: hasPermission = " + hasPermission);

            if (pendingInstallCallback == null) return;

            if (hasPermission) {
                //  User đã cấp permission
                if (pendingInstallUrl != null && !pendingInstallUrl.isEmpty()) {
                    //  Tự động resume download + install
                    Log.d(TAG, "Permission granted → Resume download: "
                            + pendingInstallFileName);

                    sendStatus(pendingInstallCallback,
                            "PERMISSION_GRANTED", 0,
                            "Permission granted. Starting download...",
                            "", true);

                    downloadAndInstall(
                            pendingInstallUrl,
                            pendingInstallFileName,
                            pendingInstallCallback);
                } else {
                    // Không có URL pending → chỉ báo permission OK
                    try {
                        JSONObject result = new JSONObject();
                        result.put("status", "PERMISSION_GRANTED");
                        result.put("hasPermission", true);
                        result.put("message", "Permission granted successfully.");

                        PluginResult pr = new PluginResult(
                                PluginResult.Status.OK, result);
                        pr.setKeepCallback(false);
                        pendingInstallCallback.sendPluginResult(pr);
                    } catch (JSONException e) {
                        pendingInstallCallback.success("PERMISSION_GRANTED");
                    }
                }
            } else {
                //  User từ chối / không bật permission
                try {
                    JSONObject result = new JSONObject();
                    result.put("status", "PERMISSION_DENIED");
                    result.put("hasPermission", false);
                    result.put("message",
                            "Install permission denied. " +
                            "Cannot install APK without this permission.");

                    PluginResult pr = new PluginResult(
                            PluginResult.Status.ERROR, result);
                    pr.setKeepCallback(false);
                    pendingInstallCallback.sendPluginResult(pr);
                } catch (JSONException e) {
                    pendingInstallCallback.error("PERMISSION_DENIED");
                }
            }

            //  Reset pending state
            pendingInstallUrl      = null;
            pendingInstallFileName = null;
            pendingInstallCallback = null;
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
