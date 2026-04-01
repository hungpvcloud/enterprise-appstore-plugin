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
import android.util.Log;

import androidx.core.content.FileProvider;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.pm.PackageManager;
import android.provider.Settings;


import java.io.File;

public class EnterpriseAppStore extends CordovaPlugin {

    private static final String TAG = "EnterpriseAppStore";
    private long downloadId = -1;
    private CallbackContext downloadCallback;
    private Handler progressHandler;
    private Runnable progressRunnable;
    private BroadcastReceiver downloadReceiver;
    
    private static final int REQUEST_INSTALL_PERMISSION = 1001;


    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext)
            throws JSONException {

        switch (action) {
            case "downloadAndInstall":
                String url = args.getString(0);
                String fileName = args.getString(1);
                this.downloadAndInstall(url, fileName, callbackContext);
                return true;

            case "getAppVersion":
                String packageName = args.getString(0);
                this.getAppVersion(packageName, callbackContext);
                return true;

            case "isAppInstalled":
                String pkg = args.getString(0);
                this.isAppInstalled(pkg, callbackContext);
                return true;

            case "cancelDownload":
                this.cancelDownload(callbackContext);
                return true;
            

            case "checkInstallPermission":
                this.checkInstallPermission(callbackContext);
                return true;
            
            case "requestInstallPermission":
                this.requestInstallPermission(callbackContext);
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

                // Kiểm tra download status
                DownloadManager.Query query = new DownloadManager.Query();
                query.setFilterById(downloadId);
                Cursor cursor = dm.query(query);

                if (cursor != null && cursor.moveToFirst()) {
                    int status = cursor.getInt(
                            cursor.getColumnIndexOrThrow(
                                    DownloadManager.COLUMN_STATUS));
                    cursor.close();

                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        sendProgress(callbackContext, "INSTALLING", 100,
                                "Download complete. Starting installation...");
                        installApk(apkFile, callbackContext);
                    } else {
                        callbackContext.error("DOWNLOAD_FAILED_STATUS: " + status);
                    }
                }

                try {
                    context.unregisterReceiver(this);
                } catch (Exception ignored) {}
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
    private void installApk(File apkFile, CallbackContext callbackContext) {
        try {
            Context context = cordova.getContext();
            
            // ✅ KIỂM TRA permission trước khi install (Android 8+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.getPackageManager().canRequestPackageInstalls()) {
                    // Chưa có permission → Yêu cầu user bật
                    JSONObject error = new JSONObject();
                    error.put("error", "INSTALL_PERMISSION_REQUIRED");
                    error.put("message", 
                        "App needs permission to install packages. " +
                        "Call requestInstallPermission() first.");
                    callbackContext.error(error.toString());
                    return;
                }
            }
            
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

            sendProgress(callbackContext, "INSTALL_PROMPT", 100,
                    "Installation dialog opened");

            JSONObject result = new JSONObject();
            result.put("status", "SUCCESS");
            result.put("message", "APK installation started");
            PluginResult pluginResult = new PluginResult(
                    PluginResult.Status.OK, result);
            pluginResult.setKeepCallback(false);
            callbackContext.sendPluginResult(pluginResult);

        } catch (Exception e) {
            Log.e(TAG, "Install error: " + e.getMessage());
            callbackContext.error("INSTALL_ERROR: " + e.getMessage());
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Context context = cordova.getContext();
            boolean canInstall = context.getPackageManager()
                    .canRequestPackageInstalls();
            
            JSONObject result = new JSONObject();
            try {
                result.put("hasPermission", canInstall);
                callbackContext.success(result);
            } catch (JSONException e) {
                callbackContext.error("ERROR_CHECK_PERMISSION");
            }
        } else {
            // Android < 8.0 không cần permission này
            try {
                JSONObject result = new JSONObject();
                result.put("hasPermission", true);
                callbackContext.success(result);
            } catch (JSONException e) {
                callbackContext.error("ERROR_CHECK_PERMISSION");
            }
        }
    }

    
    private void requestInstallPermission(CallbackContext callbackContext) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Context context = cordova.getContext();
            
            if (!context.getPackageManager().canRequestPackageInstalls()) {
                // Mở Settings để user cấp permission
                Intent intent = new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.getPackageName())
                );
                cordova.getActivity().startActivityForResult(
                    intent, 
                    REQUEST_INSTALL_PERMISSION
                );
                
                callbackContext.success("PERMISSION_REQUESTED");
            } else {
                callbackContext.success("PERMISSION_ALREADY_GRANTED");
            }
        } else {
            callbackContext.success("PERMISSION_NOT_NEEDED");
        }
    }


}