package com.enterprise.appstore;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * BadgeNumberManager - Dedicated manager for setting app icon badges
 * across all Android devices and launchers.
 *
 * Supports:
 * - Samsung (OneUI 6-7+, TouchWiz)
 * - Huawei/Honor (EMUI, HarmonyOS)
 * - Xiaomi/Redmi/POCO (MIUI, HyperOS)
 * - OPPO/Realme/OnePlus (ColorOS/OxygenOS)
 * - vivo/iQOO (FuntouchOS, OriginOS)
 * - ZTE, Sony, HTC, ASUS
 * - Generic launchers (Nova, Action, etc.)
 * - Android 8+ Notification Badge (Standard)
 */
public class BadgeNumberManager {

    private static final String TAG = "BadgeNumberManager";
    private static final String BADGE_CHANNEL_ID = "badge_channel";
    private static final String BADGE_CHANNEL_NAME = "App Badge";
    private static final int BADGE_NOTIFICATION_ID = 9999;
    private static final boolean CLEAR_ALL_NOTIFICATIONS_WHEN_BADGE_ZERO = true;

    private Context context;
    private String packageName;
    private String launcherClassName;

    public BadgeNumberManager(Context context) {
        this.context = context;
        this.packageName = context.getPackageName();
        this.launcherClassName = getLauncherClassName();
    }

    /**
     * Main method to set badge number with automatic strategy detection
     * and fallback handling.
     *
     * @param count Badge count (0 to clear)
     * @return JSONObject with result details
     */
    public JSONObject setBadgeNumber(int count) {
        JSONObject result = new JSONObject();
        JSONArray appliedStrategies = new JSONArray();
        
        try {
            Log.d(TAG, "setBadgeNumber: count=" + count 
                    + " manufacturer=" + Build.MANUFACTURER 
                    + " pkg=" + packageName);

            if (launcherClassName == null) {
                Log.e(TAG, "Launcher class not found");
                result.put("success", false);
                result.put("error", "Launcher not found");
                return result;
            }

            
            if (count <= 0) {
                // Use dedicated clear flow for badge count 0.
                // This prevents stale badge count caused by active notifications.
                return clearBadgeNumber();
            }


            boolean anySuccess = false;

            // Strategy 1: Android 8+ Notification Badge (Primary)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    setNotificationBadge(count);
                    appliedStrategies.put("notification_badge");
                    anySuccess = true;
                    Log.d(TAG, "Notification badge set ✅");
                } catch (Exception e) {
                    Log.w(TAG, "Notification badge failed: " + e.getMessage());
                }
            }

            // Strategy 2: Vendor-specific methods
            String manufacturer = Build.MANUFACTURER.toLowerCase(Locale.ROOT);
            
            if (manufacturer.contains("samsung")) {
                if (trySetBadgeSamsung(count, appliedStrategies)) {
                    anySuccess = true;
                }
            } else if (manufacturer.contains("huawei") || manufacturer.contains("honor")) {
                if (trySetBadgeHuawei(count, appliedStrategies)) {
                    anySuccess = true;
                }
            } else if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi") 
                    || manufacturer.contains("poco")) {
                if (trySetBadgeXiaomi(count, appliedStrategies)) {
                    anySuccess = true;
                }
            } else if (manufacturer.contains("oppo") || manufacturer.contains("realme") 
                    || manufacturer.contains("oneplus")) {
                if (trySetBadgeOPPO(count, appliedStrategies)) {
                    anySuccess = true;
                }
            } else if (manufacturer.contains("vivo") || manufacturer.contains("iqoo")) {
                if (trySetBadgeVivo(count, appliedStrategies)) {
                    anySuccess = true;
                }
            } else if (manufacturer.contains("zte")) {
                if (trySetBadgeZTE(count, appliedStrategies)) {
                    anySuccess = true;
                }
            } else if (manufacturer.contains("sony")) {
                if (trySetBadgeSony(count, appliedStrategies)) {
                    anySuccess = true;
                }
            } else if (manufacturer.contains("htc")) {
                if (trySetBadgeHTC(count, appliedStrategies)) {
                    anySuccess = true;
                }
            } else if (manufacturer.contains("asus")) {
                if (trySetBadgeASUS(count, appliedStrategies)) {
                    anySuccess = true;
                }
            }

            // Strategy 3: Generic broadcast (works with third-party launchers)
            try {
                trySetBadgeGenericBroadcast(count, appliedStrategies);
                anySuccess = true;
            } catch (Exception e) {
                Log.w(TAG, "Generic broadcast failed: " + e.getMessage());
            }

            result.put("badge", count);
            result.put("manufacturer", Build.MANUFACTURER);
            result.put("model", Build.MODEL);
            result.put("sdkVersion", Build.VERSION.SDK_INT);
            result.put("strategies", appliedStrategies);
            result.put("success", anySuccess);

            if (anySuccess) {
                Log.d(TAG, "Badge set successfully: count=" + count 
                        + " strategies=" + appliedStrategies.toString());
            } else {
                result.put("error", "No badge strategy worked");
                Log.e(TAG, "All strategies failed");
            }

        } catch (JSONException e) {
            Log.e(TAG, "JSON error in setBadgeNumber", e);
            try {
                result.put("success", false);
                result.put("error", e.getMessage());
            } catch (JSONException ignored) {}
        }

        return result;
    }

    // ═══════════════════════════════════════════════════════════
    // Strategy 1: Android 8+ Notification Badge (Standard)
    // ═══════════════════════════════════════════════════════════

    private void setNotificationBadge(int count) {
            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);

            if (nm == null) {
                Log.w(TAG, "NotificationManager is null");
                return;
            }

            if (count <= 0) {
                // Cancel the internal badge notification used by this plugin.
                nm.cancel(BADGE_NOTIFICATION_ID);

                // Important:
                // On many Android launchers, especially Samsung OneUI and modern Android versions,
                // the app icon badge is calculated from active notifications.
                // If active push notifications still exist in the notification tray,
                // the launcher may continue showing badge count even after setting badge to 0.
                if (CLEAR_ALL_NOTIFICATIONS_WHEN_BADGE_ZERO) {
                    try {
                        nm.cancelAll();
                        Log.d(TAG, "All app notifications were cleared because badge count is 0");
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to clear all notifications: " + e.getMessage());
                    }
                }

                Log.d(TAG, "Badge notification cleared");
                return;
            }

            // Create notification channel for Android 8.0+.
            // The channel must allow badge display, otherwise launcher may ignore badge count.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = nm.getNotificationChannel(BADGE_CHANNEL_ID);

                if (channel == null) {
                    channel = new NotificationChannel(
                            BADGE_CHANNEL_ID,
                            BADGE_CHANNEL_NAME,
                            NotificationManager.IMPORTANCE_MIN
                    );

                    // Allow this notification channel to show app icon badge.
                    channel.setShowBadge(true);

                    // Disable sound, vibration, and light because this notification is used only for badge update.
                    channel.setSound(null, null);
                    channel.enableVibration(false);
                    channel.enableLights(false);

                    nm.createNotificationChannel(channel);
                    Log.d(TAG, "Badge notification channel created");
                }
            }

            NotificationCompat.Builder builder =
                    new NotificationCompat.Builder(context, BADGE_CHANNEL_ID)
                            .setSmallIcon(getAppIconResourceId())

                            // Keep title valid. Some Android versions do not like empty title/text.
                            .setContentTitle(context.getApplicationInfo().loadLabel(context.getPackageManager()))

                            // Keep content minimal because this notification is only for badge count.
                            .setContentText("")

                            // Set the badge number hint for supported launchers.
                            .setNumber(count)

                            // Keep notification low priority and silent.
                            .setPriority(NotificationCompat.PRIORITY_MIN)
                            .setSilent(true)

                            // Do not make this notification persistent.
                            .setOngoing(false)

                            // Do not auto cancel because this notification is controlled by badge logic.
                            .setAutoCancel(false)

                            // Avoid alerting again when updating the same badge notification.
                            .setOnlyAlertOnce(true)

                            // Keep notification local to this device.
                            .setLocalOnly(true)

                            // Group this internal badge notification separately.
                            .setGroup("badge_group")
                            .setGroupSummary(true);

            nm.notify(BADGE_NOTIFICATION_ID, builder.build());

            Log.d(TAG, "Notification badge updated: " + count);
        }

    private int getAppIconResourceId() {
        try {
            return context.getPackageManager()
                    .getApplicationInfo(packageName, 0)
                    .icon;
        } catch (PackageManager.NameNotFoundException e) {
            return android.R.drawable.ic_dialog_info;
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Strategy 2: Vendor-specific methods with error handling
    // ═══════════════════════════════════════════════════════════

    private boolean trySetBadgeSamsung(int count, JSONArray strategies) {
        try {
            Uri uri = Uri.parse("content://com.sec.badge/apps");

            ContentValues cv = new ContentValues();

            // Use non-negative badge count only.
            int safeCount = Math.max(count, 0);

            cv.put("package", packageName);
            cv.put("class", launcherClassName);
            cv.put("badgecount", safeCount);

            try {
                android.database.Cursor cursor = context.getContentResolver().query(
                        uri,
                        null,
                        "package=?",
                        new String[]{packageName},
                        null
                );

                boolean exists = false;

                if (cursor != null) {
                    exists = cursor.moveToFirst();
                    cursor.close();
                }

                if (exists) {
                    // Update badge count first.
                    // For count = 0, updating badgecount to 0 is safer than deleting only.
                    int updated = context.getContentResolver().update(
                            uri,
                            cv,
                            "package=?",
                            new String[]{packageName}
                    );

                    Log.d(TAG, "Samsung badge provider updated. rows=" + updated + ", count=" + safeCount);

                    if (safeCount == 0) {
                        try {
                            // Some older Samsung launchers clear badge only after deleting the provider row.
                            // Therefore, we update to 0 first, then try deleting as an additional cleanup.
                            int deleted = context.getContentResolver().delete(
                                    uri,
                                    "package=?",
                                    new String[]{packageName}
                            );

                            Log.d(TAG, "Samsung badge provider row deleted for clear. rows=" + deleted);
                        } catch (Exception deleteEx) {
                            Log.w(TAG, "Samsung badge provider delete failed: " + deleteEx.getMessage());
                        }
                    }
                } else {
                    if (safeCount > 0) {
                        // Insert badge row only when badge count is greater than 0.
                        context.getContentResolver().insert(uri, cv);
                        Log.d(TAG, "Samsung badge provider inserted. count=" + safeCount);
                    } else {
                        // Do not insert a new row with 0 count.
                        Log.d(TAG, "Samsung badge provider row not found and count is 0. Insert skipped.");
                    }
                }

                // Send Samsung-compatible badge broadcast as an additional fallback.
                Intent intent = new Intent("android.intent.action.BADGE_COUNT_UPDATE");
                intent.putExtra("badge_count", safeCount);
                intent.putExtra("badge_count_package_name", packageName);
                intent.putExtra("badge_count_class_name", launcherClassName);
                context.sendBroadcast(intent);

                strategies.put("samsung_provider");
                strategies.put("samsung_broadcast");

                Log.d(TAG, "Samsung badge update completed. count=" + safeCount);
                return true;

            } catch (Exception providerEx) {
                // Fallback to Samsung badge broadcast when content provider is not available.
                Intent intent = new Intent("android.intent.action.BADGE_COUNT_UPDATE");
                intent.putExtra("badge_count", safeCount);
                intent.putExtra("badge_count_package_name", packageName);
                intent.putExtra("badge_count_class_name", launcherClassName);
                context.sendBroadcast(intent);

                strategies.put("samsung_broadcast");

                Log.d(TAG, "Samsung badge updated via broadcast fallback. count=" + safeCount);
                return true;
            }

        } catch (Exception e) {
            Log.w(TAG, "Samsung badge update failed: " + e.getMessage());
            return false;
        }
    }

    private boolean trySetBadgeHuawei(int count, JSONArray strategies) {
        try {
            android.os.Bundle bundle = new android.os.Bundle();
            bundle.putString("package", packageName);
            bundle.putString("class", launcherClassName);
            bundle.putInt("badgenumber", count);

            context.getContentResolver().call(
                    Uri.parse("content://com.huawei.android.launcher.settings/badge/"),
                    "change_badge", null, bundle);

            strategies.put("huawei");
            Log.d(TAG, "Huawei badge set");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Huawei badge failed: " + e.getMessage());
            return false;
        }
    }

    private boolean trySetBadgeXiaomi(int count, JSONArray strategies) {
        try {
            // Xiaomi relies on notification badge number
            setNotificationBadge(count);
            strategies.put("xiaomi_notification");
            Log.d(TAG, "Xiaomi badge set via notification");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Xiaomi badge failed: " + e.getMessage());
            return false;
        }
    }

    private boolean trySetBadgeOPPO(int count, JSONArray strategies) {
        try {
            // Method 1: OPPO ContentProvider
            try {
                android.os.Bundle extras = new android.os.Bundle();
                extras.putInt("app_badge_count", count);
                context.getContentResolver().call(
                        Uri.parse("content://com.android.badge/badge"),
                        "setAppBadgeCount", null, extras);
                strategies.put("oppo_provider");
                return true;
            } catch (Exception e1) {
                // Method 2: OPPO broadcast
                Intent intent = new Intent("com.oppo.unsettledevent");
                intent.putExtra("pakeageName", packageName);
                intent.putExtra("packageName", packageName);
                intent.putExtra("number", count);
                intent.putExtra("upgradeNumber", count);
                context.sendBroadcast(intent);
                strategies.put("oppo_broadcast");
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "OPPO badge failed: " + e.getMessage());
            return false;
        }
    }

    private boolean trySetBadgeVivo(int count, JSONArray strategies) {
        try {
            Intent intent = new Intent("launcher.action.CHANGE_APPLICATION_NOTIFICATION_NUM");
            intent.putExtra("packageName", packageName);
            intent.putExtra("className", launcherClassName);
            intent.putExtra("notificationNum", count);
            context.sendBroadcast(intent);
            strategies.put("vivo");
            Log.d(TAG, "vivo badge set");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "vivo badge failed: " + e.getMessage());
            return false;
        }
    }

    private boolean trySetBadgeZTE(int count, JSONArray strategies) {
        try {
            android.os.Bundle extras = new android.os.Bundle();
            extras.putInt("app_badge_count", count);
            extras.putString("app_badge_component_name",
                    new ComponentName(packageName, launcherClassName).flattenToString());

            context.getContentResolver().call(
                    Uri.parse("content://com.android.launcher3.badge/badge"),
                    "setAppBadgeCount", null, extras);

            strategies.put("zte");
            Log.d(TAG, "ZTE badge set");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "ZTE badge failed: " + e.getMessage());
            return false;
        }
    }

    private boolean trySetBadgeSony(int count, JSONArray strategies) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("badge_count", count);
            cv.put("package_name", packageName);
            cv.put("activity_name", launcherClassName);

            boolean isSonyShell = isPackageInstalled("com.sonymobile.home");

            if (isSonyShell) {
                context.getContentResolver().insert(
                        Uri.parse("content://com.sonymobile.home.resourceprovider/badge"),
                        cv);
            } else {
                context.getContentResolver().insert(
                        Uri.parse("content://com.sonyericsson.home.resourceprovider/badge"),
                        cv);
            }

            strategies.put("sony");
            Log.d(TAG, "Sony badge set");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Sony badge failed: " + e.getMessage());
            return false;
        }
    }

    private boolean trySetBadgeHTC(int count, JSONArray strategies) {
        try {
            ComponentName component = new ComponentName(packageName, launcherClassName);

            Intent intent = new Intent("com.htc.launcher.action.SET_NOTIFICATION");
            intent.putExtra("com.htc.launcher.extra.COMPONENT", component.flattenToShortString());
            intent.putExtra("com.htc.launcher.extra.COUNT", count);
            context.sendBroadcast(intent);

            strategies.put("htc");
            Log.d(TAG, "HTC badge set");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "HTC badge failed: " + e.getMessage());
            return false;
        }
    }

    private boolean trySetBadgeASUS(int count, JSONArray strategies) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("badge_count", count);
            cv.put("package_name", packageName);
            cv.put("activity_name", launcherClassName);

            context.getContentResolver().insert(
                    Uri.parse("content://com.asus.launcher.badge/badge"),
                    cv);

            strategies.put("asus");
            Log.d(TAG, "ASUS badge set");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "ASUS badge failed: " + e.getMessage());
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Strategy 3: Generic broadcast for third-party launchers
    // ═══════════════════════════════════════════════════════════

    private void trySetBadgeGenericBroadcast(int count, JSONArray strategies) {
        // Generic badge broadcast
        try {
            Intent intent = new Intent("android.intent.action.BADGE_COUNT_UPDATE");
            intent.putExtra("badge_count", count);
            intent.putExtra("badge_count_package_name", packageName);
            intent.putExtra("badge_count_class_name", launcherClassName);
            context.sendBroadcast(intent);
            strategies.put("generic_broadcast");
            Log.d(TAG, "Generic broadcast sent");
        } catch (Exception e) {
            Log.w(TAG, "Generic broadcast failed: " + e.getMessage());
        }

        // ShortcutBadger broadcast
        try {
            Intent intent2 = new Intent("me.leolin.shortcutbadger.BADGE_COUNT_UPDATE");
            intent2.putExtra("badge_count", count);
            intent2.putExtra("badge_count_package_name", packageName);
            intent2.putExtra("badge_count_class_name", launcherClassName);
            context.sendBroadcast(intent2);
            strategies.put("shortcutbadger");
            Log.d(TAG, "ShortcutBadger broadcast sent");
        } catch (Exception e) {
            Log.w(TAG, "ShortcutBadger broadcast failed: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Helper methods
    // ═══════════════════════════════════════════════════════════

    private boolean isPackageInstalled(String pkgName) {
        try {
            context.getPackageManager().getPackageInfo(pkgName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private String getLauncherClassName() {
        PackageManager pm = context.getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> resolveInfos = pm.queryIntentActivities(intent, 0);

        for (ResolveInfo resolveInfo : resolveInfos) {
            if (resolveInfo.activityInfo.packageName.equals(packageName)) {
                return resolveInfo.activityInfo.name;
            }
        }
        return null;
    }

    public JSONObject clearBadgeNumber() {
        JSONObject result = new JSONObject();
        JSONArray appliedStrategies = new JSONArray();

        try {
            Log.d(TAG, "clearBadgeNumber called");

            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);

            if (nm != null) {
                // Cancel the internal badge notification used by this plugin.
                nm.cancel(BADGE_NOTIFICATION_ID);

                try {
                    // Clear all active notifications of this app.
                    // This is important because many launchers calculate badge count
                    // based on active notifications in the notification tray.
                    nm.cancelAll();
                    appliedStrategies.put("notification_cancel_all");

                    Log.d(TAG, "All app notifications were cleared");
                } catch (Exception e) {
                    Log.w(TAG, "Failed to clear all notifications: " + e.getMessage());
                }
            }

            String manufacturer = Build.MANUFACTURER.toLowerCase(Locale.ROOT);

            // Apply vendor-specific clear logic.
            if (manufacturer.contains("samsung")) {
                trySetBadgeSamsung(0, appliedStrategies);
            } else if (manufacturer.contains("huawei") || manufacturer.contains("honor")) {
                trySetBadgeHuawei(0, appliedStrategies);
            } else if (manufacturer.contains("xiaomi")
                    || manufacturer.contains("redmi")
                    || manufacturer.contains("poco")) {
                trySetBadgeXiaomi(0, appliedStrategies);
            } else if (manufacturer.contains("oppo")
                    || manufacturer.contains("realme")
                    || manufacturer.contains("oneplus")) {
                trySetBadgeOPPO(0, appliedStrategies);
            } else if (manufacturer.contains("vivo") || manufacturer.contains("iqoo")) {
                trySetBadgeVivo(0, appliedStrategies);
            } else if (manufacturer.contains("zte")) {
                trySetBadgeZTE(0, appliedStrategies);
            } else if (manufacturer.contains("sony")) {
                trySetBadgeSony(0, appliedStrategies);
            } else if (manufacturer.contains("htc")) {
                trySetBadgeHTC(0, appliedStrategies);
            } else if (manufacturer.contains("asus")) {
                trySetBadgeASUS(0, appliedStrategies);
            }

            // Send generic badge clear broadcasts as final fallback.
            trySetBadgeGenericBroadcast(0, appliedStrategies);

            result.put("success", true);
            result.put("badge", 0);
            result.put("manufacturer", Build.MANUFACTURER);
            result.put("model", Build.MODEL);
            result.put("sdkVersion", Build.VERSION.SDK_INT);
            result.put("strategies", appliedStrategies);

            Log.d(TAG, "Badge cleared successfully. strategies=" + appliedStrategies.toString());

        } catch (Exception e) {
            Log.e(TAG, "clearBadgeNumber failed", e);

            try {
                result.put("success", false);
                result.put("badge", 0);
                result.put("error", e.getMessage());
            } catch (JSONException ignored) {
            }
        }

        return result;
    }
}
