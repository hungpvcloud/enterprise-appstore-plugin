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

        if (count <= 0) {
            nm.cancel(BADGE_NOTIFICATION_ID);
            Log.d(TAG, "Badge notification cleared");
            return;
        }

        // Create channel if not exists
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = nm.getNotificationChannel(BADGE_CHANNEL_ID);
            if (channel == null) {
                channel = new NotificationChannel(
                        BADGE_CHANNEL_ID,
                        BADGE_CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_MIN
                );
                channel.setShowBadge(true);
                channel.setSound(null, null);
                channel.enableVibration(false);
                channel.enableLights(false);
                nm.createNotificationChannel(channel);
                Log.d(TAG, "Badge channel created");
            }
        }

        // Build silent badge notification
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, BADGE_CHANNEL_ID)
                .setSmallIcon(getAppIconResourceId())
                .setContentTitle("")
                .setContentText("")
                .setNumber(count)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setSilent(true)
                .setOngoing(false)
                .setAutoCancel(false)
                .setGroup("badge_group")
                .setGroupSummary(true);

        nm.notify(BADGE_NOTIFICATION_ID, builder.build());
        Log.d(TAG, "Notification badge set: " + count);
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
            // Method 1: Samsung BadgeProvider
            Uri uri = Uri.parse("content://com.sec.badge/apps");
            ContentValues cv = new ContentValues();
            cv.put("package", packageName);
            cv.put("class", launcherClassName);
            cv.put("badgecount", count);

            try {
                android.database.Cursor cursor = context.getContentResolver().query(
                        uri, null, "package=?",
                        new String[]{packageName}, null);

                if (cursor != null) {
                    if (cursor.moveToFirst()) {
                        if (count > 0) {
                            context.getContentResolver().update(uri, cv, "package=?",
                                    new String[]{packageName});
                        } else {
                            context.getContentResolver().delete(uri, "package=?",
                                    new String[]{packageName});
                        }
                    } else if (count > 0) {
                        context.getContentResolver().insert(uri, cv);
                    }
                    cursor.close();
                }
                strategies.put("samsung_provider");
                Log.d(TAG, "Samsung badge set via provider");
                return true;
            } catch (Exception e1) {
                // Fallback to broadcast
                Intent intent = new Intent("android.intent.action.BADGE_COUNT_UPDATE");
                intent.putExtra("badge_count", count);
                intent.putExtra("badge_count_package_name", packageName);
                intent.putExtra("badge_count_class_name", launcherClassName);
                context.sendBroadcast(intent);
                strategies.put("samsung_broadcast");
                Log.d(TAG, "Samsung badge set via broadcast");
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Samsung badge failed: " + e.getMessage());
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
}
