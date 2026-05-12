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
     * @param count Badge count. Use 0 to clear badge.
     * @return JSONObject with result details.
     */
    public JSONObject setBadgeNumber(int count) {
        JSONObject result = new JSONObject();
        JSONArray appliedStrategies = new JSONArray();

        try {
            int safeCount = Math.max(count, 0);

            Log.d(TAG, "setBadgeNumber called. count=" + safeCount
                    + ", manufacturer=" + Build.MANUFACTURER
                    + ", model=" + Build.MODEL
                    + ", sdkVersion=" + Build.VERSION.SDK_INT
                    + ", packageName=" + packageName
                    + ", launcherClassName=" + launcherClassName);

            if (launcherClassName == null) {
                Log.e(TAG, "Launcher class not found");

                result.put("success", false);
                result.put("badge", safeCount);
                result.put("manufacturer", Build.MANUFACTURER);
                result.put("model", Build.MODEL);
                result.put("sdkVersion", Build.VERSION.SDK_INT);
                result.put("error", "Launcher not found");

                return result;
            }

            if (safeCount <= 0) {
                // Use a dedicated clear flow when badge count is 0.
                // This is important because many Android launchers calculate badge count
                // from active notifications instead of using the explicit badge number only.
                return clearBadgeNumber();
            }

            boolean anySuccess = false;

            String manufacturer = Build.MANUFACTURER == null
                    ? ""
                    : Build.MANUFACTURER.toLowerCase(Locale.ROOT);

            boolean isSamsungDevice = manufacturer.contains("samsung");

            boolean isHuaweiDevice = manufacturer.contains("huawei")
                    || manufacturer.contains("honor");

            boolean isXiaomiDevice = manufacturer.contains("xiaomi")
                    || manufacturer.contains("redmi")
                    || manufacturer.contains("poco");

            boolean isOppoDevice = manufacturer.contains("oppo")
                    || manufacturer.contains("realme")
                    || manufacturer.contains("oneplus");

            boolean isVivoDevice = manufacturer.contains("vivo")
                    || manufacturer.contains("iqoo");

            boolean isZteDevice = manufacturer.contains("zte");
            boolean isSonyDevice = manufacturer.contains("sony");
            boolean isHtcDevice = manufacturer.contains("htc");
            boolean isAsusDevice = manufacturer.contains("asus");

            // For Xiaomi/Redmi/POCO, do not run the generic notification badge first.
            // Running both generic notification badge and Xiaomi-specific badge logic
            // can make MIUI/HyperOS badge count unstable or duplicated.
            if (!isXiaomiDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    setNotificationBadge(safeCount);
                    appliedStrategies.put("notification_badge");
                    anySuccess = true;

                    Log.d(TAG, "Generic Android notification badge applied. count=" + safeCount);
                } catch (Exception e) {
                    Log.w(TAG, "Generic Android notification badge failed: " + e.getMessage());
                }
            }

            // Apply vendor-specific badge strategy.
            // The vendor strategy is still executed after the generic strategy
            // because some launchers require proprietary APIs or broadcasts.
            if (isSamsungDevice) {
                if (trySetBadgeSamsung(safeCount, appliedStrategies)) {
                    anySuccess = true;
                }
            } else if (isHuaweiDevice) {
                if (trySetBadgeHuawei(safeCount, appliedStrategies)) {
                    anySuccess = true;
                }
            } else if (isXiaomiDevice) {
                // Xiaomi/Redmi/POCO use a dedicated flow only.
                // Do not combine this with the generic badge flow above.
                if (trySetBadgeXiaomi(safeCount, appliedStrategies)) {
                    anySuccess = true;
                }
            } else if (isOppoDevice) {
                if (trySetBadgeOPPO(safeCount, appliedStrategies)) {
                    anySuccess = true;
                }
            } else if (isVivoDevice) {
                if (trySetBadgeVivo(safeCount, appliedStrategies)) {
                    anySuccess = true;
                }
            } else if (isZteDevice) {
                if (trySetBadgeZTE(safeCount, appliedStrategies)) {
                    anySuccess = true;
                }
            } else if (isSonyDevice) {
                if (trySetBadgeSony(safeCount, appliedStrategies)) {
                    anySuccess = true;
                }
            } else if (isHtcDevice) {
                if (trySetBadgeHTC(safeCount, appliedStrategies)) {
                    anySuccess = true;
                }
            } else if (isAsusDevice) {
                if (trySetBadgeASUS(safeCount, appliedStrategies)) {
                    anySuccess = true;
                }
            }

            // Send generic badge broadcasts as a final fallback for third-party launchers.
            // For Xiaomi, this is intentionally skipped here because Xiaomi-specific
            // logic already sends its own legacy broadcast fallback.
            if (!isXiaomiDevice) {
                try {
                    trySetBadgeGenericBroadcast(safeCount, appliedStrategies);
                    anySuccess = true;

                    Log.d(TAG, "Generic badge broadcasts sent. count=" + safeCount);
                } catch (Exception e) {
                    Log.w(TAG, "Generic badge broadcasts failed: " + e.getMessage());
                }
            }

            result.put("success", anySuccess);
            result.put("badge", safeCount);
            result.put("manufacturer", Build.MANUFACTURER);
            result.put("model", Build.MODEL);
            result.put("sdkVersion", Build.VERSION.SDK_INT);
            result.put("launcherClassName", launcherClassName);
            result.put("strategies", appliedStrategies);

            if (anySuccess) {
                Log.d(TAG, "Badge update completed successfully. count=" + safeCount
                        + ", strategies=" + appliedStrategies.toString());
            } else {
                result.put("error", "No badge strategy worked");

                Log.e(TAG, "Badge update failed. No strategy worked. count=" + safeCount);
            }

        } catch (JSONException e) {
            Log.e(TAG, "JSON error in setBadgeNumber", e);

            try {
                result.put("success", false);
                result.put("badge", Math.max(count, 0));
                result.put("error", e.getMessage());
            } catch (JSONException ignored) {
            }
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error in setBadgeNumber", e);

            try {
                result.put("success", false);
                result.put("badge", Math.max(count, 0));
                result.put("error", e.getMessage());
            } catch (JSONException ignored) {
            }
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
            int safeCount = Math.max(count, 0);

            Log.d(TAG, "Xiaomi badge update started. count=" + safeCount
                    + ", manufacturer=" + Build.MANUFACTURER
                    + ", model=" + Build.MODEL);

            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);

            if (nm == null) {
                Log.w(TAG, "NotificationManager is null on Xiaomi badge update");
                return false;
            }

            if (safeCount == 0) {
                // Cancel the internal badge notification.
                nm.cancel(BADGE_NOTIFICATION_ID);

                try {
                    // Xiaomi launcher often calculates badge count from active notifications.
                    // Therefore, all app notifications must be cleared when badge count is 0.
                    nm.cancelAll();
                    Log.d(TAG, "All app notifications were cleared on Xiaomi because badge count is 0");
                } catch (Exception e) {
                    Log.w(TAG, "Failed to clear all notifications on Xiaomi: " + e.getMessage());
                }

                // Send generic badge clear broadcasts as additional fallback.
                sendXiaomiBadgeBroadcast(0);

                strategies.put("xiaomi_clear_notifications");
                strategies.put("xiaomi_broadcast_clear");

                Log.d(TAG, "Xiaomi badge cleared");
                return true;
            }

            // For Xiaomi, update the internal badge notification.
            // Some MIUI/HyperOS versions use notification count instead of setNumber().
            // Keeping only one internal badge notification helps reduce incorrect badge increment.
            setXiaomiNotificationBadge(safeCount);

            // Send legacy broadcast fallback.
            sendXiaomiBadgeBroadcast(safeCount);

            strategies.put("xiaomi_notification");
            strategies.put("xiaomi_broadcast");

            Log.d(TAG, "Xiaomi badge update completed. count=" + safeCount);
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

    private void setXiaomiNotificationBadge(int count) {
        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);

        if (nm == null) {
            Log.w(TAG, "NotificationManager is null when setting Xiaomi notification badge");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = nm.getNotificationChannel(BADGE_CHANNEL_ID);

            if (channel == null) {
                channel = new NotificationChannel(
                        BADGE_CHANNEL_ID,
                        BADGE_CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_MIN
                );

                // Badge must be enabled for this notification channel.
                channel.setShowBadge(true);

                // Disable sound, vibration, and lights because this notification is only used for badge update.
                channel.setSound(null, null);
                channel.enableVibration(false);
                channel.enableLights(false);

                nm.createNotificationChannel(channel);

                Log.d(TAG, "Xiaomi badge channel created");
            } else {
                // Important:
                // If the user disabled badge for this channel in system settings,
                // the app cannot force-enable it programmatically after channel creation.
                Log.d(TAG, "Xiaomi badge channel exists. canShowBadge=" + channel.canShowBadge());
            }
        }

        Notification notification =
                new NotificationCompat.Builder(context, BADGE_CHANNEL_ID)
                        .setSmallIcon(getAppIconResourceId())

                        // Xiaomi may ignore empty title/text on some versions.
                        .setContentTitle(context.getApplicationInfo().loadLabel(context.getPackageManager()))
                        .setContentText("")

                        // Badge count hint. Xiaomi may or may not respect this value depending on launcher settings.
                        .setNumber(count)

                        // Keep this notification low priority and silent.
                        .setPriority(NotificationCompat.PRIORITY_MIN)
                        .setSilent(true)

                        // Avoid repeated sound/vibration when updating the same notification.
                        .setOnlyAlertOnce(true)

                        // Keep this notification controlled by badge logic.
                        .setAutoCancel(false)
                        .setOngoing(false)
                        .setLocalOnly(true)

                        // Group internal badge notification separately.
                        .setGroup("badge_group")
                        .setGroupSummary(true)
                        .build();

        // Apply Xiaomi legacy message count by reflection.
        // Some MIUI versions read extraNotification.messageCount instead of NotificationCompat.setNumber().
        applyXiaomiLegacyBadgeCount(notification, count);

        // Always use the same notification ID to avoid creating multiple active notifications.
        nm.notify(BADGE_NOTIFICATION_ID, notification);

        Log.d(TAG, "Xiaomi notification badge posted. count=" + count);
    }

    private void applyXiaomiLegacyBadgeCount(Notification notification, int count) {
        try {
            Object extraNotification = notification.getClass()
                    .getDeclaredField("extraNotification")
                    .get(notification);

            if (extraNotification != null) {
                extraNotification.getClass()
                        .getDeclaredMethod("setMessageCount", int.class)
                        .invoke(extraNotification, count);

                Log.d(TAG, "Xiaomi legacy message count applied. count=" + count);
            }
        } catch (Exception e) {
            // This is expected on many Android versions because extraNotification is MIUI-specific.
            // Do not treat it as a fatal error.
            Log.d(TAG, "Xiaomi legacy message count is not available: " + e.getMessage());
        }
    }

    private void sendXiaomiBadgeBroadcast(int count) {
        try {
            Intent intent = new Intent("android.intent.action.APPLICATION_MESSAGE_UPDATE");

            // Xiaomi legacy launcher expects this format:
            // packageName/className
            intent.putExtra(
                    "android.intent.extra.update_application_component_name",
                    packageName + "/" + launcherClassName
            );

            intent.putExtra(
                    "android.intent.extra.update_application_message_text",
                    count == 0 ? "" : String.valueOf(count)
            );

            context.sendBroadcast(intent);

            Log.d(TAG, "Xiaomi legacy badge broadcast sent. count=" + count);
        } catch (Exception e) {
            Log.w(TAG, "Failed to send Xiaomi legacy badge broadcast: " + e.getMessage());
        }
    }
}
