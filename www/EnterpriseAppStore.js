var exec = require('cordova/exec');

// ════════════════════════════════════════════════════════════
// EnterpriseAppStore Cordova Plugin — JavaScript Bridge
// Supports: Android + iOS
// Version:  1.2.1
// ════════════════════════════════════════════════════════════

var EnterpriseAppStore = {

    // ── Platform Detection Helpers ───────────────────────────
    _isAndroid: function() {
        return (typeof cordova !== 'undefined')
            && (cordova.platformId === 'android'
                || cordova.platformId === 'Amazon-fireos');
    },

    _isIOS: function() {
        return (typeof cordova !== 'undefined')
            && (cordova.platformId === 'ios');
    },

    // ════════════════════════════════════════════════════════
    // 1. DOWNLOAD AND INSTALL
    // ────────────────────────────────────────────────────────
    // Android: url      = direct APK download URL
    //          fileName = "app.apk" (auto-add .apk if missing)
    //          Statuses: DOWNLOADING → DOWNLOAD_COMPLETE
    //                    → INSTALL_PROMPT (final)
    // ────────────────────────────────────────────────────────
    // iOS:     url      = HTTPS URL to manifest.plist
    //          fileName = ignored
    //          Statuses: INSTALLING → INSTALL_PROMPT → SUCCESS (final)
    // ════════════════════════════════════════════════════════
    downloadAndInstall: function(url, fileName, progressCallback, errorCallback) {
        if (EnterpriseAppStore._isAndroid()) {
            // Auto-add .apk extension if missing
            if (fileName && !fileName.toLowerCase().endsWith('.apk')) {
                fileName = fileName + '.apk';
            }
            exec(progressCallback, errorCallback,
                'EnterpriseAppStore', 'downloadAndInstall',
                [url, fileName || 'app.apk']);
        } else {
            // iOS: url is manifest.plist URL, fileName not used
            exec(progressCallback, errorCallback,
                'EnterpriseAppStore', 'downloadAndInstall',
                [url, '']);
        }
    },

    // ════════════════════════════════════════════════════════
    // 2. CHECK INSTALL PERMISSION
    // ────────────────────────────────────────────────────────
    // Android: checks REQUEST_INSTALL_PACKAGES permission
    //          Returns: { hasPermission: bool }
    // ────────────────────────────────────────────────────────
    // iOS:     always returns { hasPermission: true }
    //          (iOS handles install via itms-services, no permission needed)
    // ════════════════════════════════════════════════════════
    checkInstallPermission: function(successCallback, errorCallback) {
        if (EnterpriseAppStore._isAndroid()) {
            exec(successCallback, errorCallback,
                'EnterpriseAppStore', 'checkInstallPermission', []);
        } else {
            // iOS always has permission
            if (typeof successCallback === 'function') {
                successCallback({ hasPermission: true });
            }
        }
    },

    // ════════════════════════════════════════════════════════
    // 3. REQUEST INSTALL PERMISSION (+ auto resume)
    // ────────────────────────────────────────────────────────
    // Android: opens Settings → waits for user → auto resume download
    //          Statuses: WAITING_PERMISSION → PERMISSION_GRANTED/DENIED
    //                    → (if granted) DOWNLOADING → INSTALL_PROMPT
    // ────────────────────────────────────────────────────────
    // iOS:     no permission needed → calls downloadAndInstall directly
    // ════════════════════════════════════════════════════════
    requestInstallPermission: function(url, fileName, progressCallback, errorCallback) {
        if (EnterpriseAppStore._isAndroid()) {
            if (fileName && !fileName.toLowerCase().endsWith('.apk')) {
                fileName = fileName + '.apk';
            }
            exec(progressCallback, errorCallback,
                'EnterpriseAppStore', 'requestInstallPermission',
                [url || '', fileName || '']);
        } else {
            // iOS: no permission needed → start download directly
            EnterpriseAppStore.downloadAndInstall(
                url, fileName, progressCallback, errorCallback);
        }
    },

    // ════════════════════════════════════════════════════════
    // 4. GET APP VERSION
    // ────────────────────────────────────────────────────────
    // Android: gets version of any installed app by packageName
    //          Error: "APP_NOT_FOUND" if not installed
    // ────────────────────────────────────────────────────────
    // iOS:     packageName is ignored
    //          always returns version of current running app
    // ────────────────────────────────────────────────────────
    // Returns: { packageName, versionName, versionCode }
    // ════════════════════════════════════════════════════════
    getAppVersion: function(packageName, successCallback, errorCallback) {
        exec(successCallback, errorCallback,
            'EnterpriseAppStore', 'getAppVersion',
            [packageName || '']);
    },

    // ════════════════════════════════════════════════════════
    // 5. IS APP INSTALLED
    // ────────────────────────────────────────────────────────
    // Android: pass packageName  e.g. "com.example.myapp"
    //          Returns full version info if installed
    // ────────────────────────────────────────────────────────
    // iOS:     pass URL scheme   e.g. "myapp"  (without ://)
    //          ⚠️ Scheme must be declared in LSApplicationQueriesSchemes
    //          versionName always empty (iOS cannot read other app versions)
    // ────────────────────────────────────────────────────────
    // Returns: { isInstalled, packageName, versionName, versionCode }
    // ════════════════════════════════════════════════════════
    isAppInstalled: function(packageNameOrScheme, successCallback, errorCallback) {
        exec(successCallback, errorCallback,
            'EnterpriseAppStore', 'isAppInstalled',
            [packageNameOrScheme || '']);
    },

    // ════════════════════════════════════════════════════════
    // 6. CHECK APP SHIELD
    // ────────────────────────────────────────────────────────
    // Android returns:
    // {
    //   isRooted:             bool,
    //   isDeveloperMode:      bool,
    //   isUsbDebugging:       bool,
    //   isEmulator:           bool,
    //   isInstalledFromStore: bool,
    //   isSafe:               bool  (!isRooted && !isEmulator)
    // }
    // ────────────────────────────────────────────────────────
    // iOS returns:
    // {
    //   isJailbroken:         bool,
    //   isDeveloperMode:      bool,
    //   isSimulator:          bool,
    //   isInstalledFromStore: bool,
    //   isSafe:               bool  (!isJailbroken && !isSimulator)
    // }
    // ════════════════════════════════════════════════════════
    checkAppShield: function(successCallback, errorCallback) {
        exec(successCallback, errorCallback,
            'EnterpriseAppStore', 'checkAppShield', []);
    },

    // ════════════════════════════════════════════════════════
    // 7. GET DEVICE INFO
    // ────────────────────────────────────────────────────────
    // Both platforms return same JSON structure:
    // {
    //   manufacturer:        string  ("Samsung" | "Apple")
    //   brand:               string
    //   model:               string
    //   device:              string
    //   androidVersion:      string  (iOS: same as systemVersion)
    //   systemVersion:       string  (iOS only)
    //   sdkVersion:          number  (Android only, iOS = 0)
    //   appVersion:          string
    //   appVersionCode:      number
    //   packageName:         string
    //   screenWidth:         number
    //   screenHeight:        number
    //   screenDensity:       number
    //   locale:              string
    //   timezone:            string
    //   batteryLevel:        number  (0-100, -1 if unknown)
    //   isCharging:          bool
    //   availableStorageMB:  number
    //   totalStorageMB:      number
    // }
    // ════════════════════════════════════════════════════════
    getDeviceInfo: function(successCallback, errorCallback) {
        exec(successCallback, errorCallback,
            'EnterpriseAppStore', 'getDeviceInfo', []);
    },

    // ════════════════════════════════════════════════════════
    // 8. CHECK UPDATE
    // ────────────────────────────────────────────────────────
    // Android: compares version of any app by packageName
    //          packageName: "com.example.app"
    //          latestVersion: "2.0.0" from your server API
    // ────────────────────────────────────────────────────────
    // iOS:     compares version of current app only
    //          packageName: ignored
    //          latestVersion: "2.0.0" from your server API
    // ────────────────────────────────────────────────────────
    // Returns:
    // {
    //   needsUpdate:      bool,
    //   installedVersion: string,
    //   latestVersion:    string,
    //   packageName:      string,
    //   isInstalled:      bool
    // }
    // ════════════════════════════════════════════════════════
    checkUpdate: function(packageName, latestVersion, successCallback, errorCallback) {
        exec(successCallback, errorCallback,
            'EnterpriseAppStore', 'checkUpdate',
            [packageName || '', latestVersion || '']);
    },

    // ════════════════════════════════════════════════════════
    // 9. CANCEL DOWNLOAD
    // ────────────────────────────────────────────────────────
    // Android: cancels active DownloadManager task
    // iOS:     no file download → returns success immediately
    // ════════════════════════════════════════════════════════
    cancelDownload: function(successCallback, errorCallback) {
        if (EnterpriseAppStore._isAndroid()) {
            exec(successCallback, errorCallback,
                'EnterpriseAppStore', 'cancelDownload', []);
        } else {
            // iOS has no download task to cancel
            if (typeof successCallback === 'function') {
                successCallback('NO_DOWNLOAD_ON_IOS');
            }
        }
    },

    // ════════════════════════════════════════════════════════
    // 10. OPEN APP  ★★★ NEW ★★★
    // ────────────────────────────────────────────────────────
    // Launch an installed app by package name (Android)
    // or URL scheme (iOS)
    //
    // ── Android ─────────────────────────────────────────────
    //   packageNameOrScheme: "com.example.myapp"
    //   Uses getLaunchIntentForPackage() to open the app
    //
    //   Success: {
    //     success:     true,
    //     packageName: "com.example.myapp",
    //     message:     "App launched successfully"
    //   }
    //
    //   Error: {
    //     success:     false,
    //     packageName: "com.example.myapp",
    //     message:     "...",
    //     errorCode:   "APP_NOT_INSTALLED"
    //                | "NO_LAUNCH_INTENT"
    //                | "ACTIVITY_NOT_FOUND"
    //                | "SECURITY_ERROR"
    //                | "OPEN_APP_ERROR"
    //   }
    //
    // ── iOS ─────────────────────────────────────────────────
    //   packageNameOrScheme: "myapp" (URL scheme without ://)
    //   Uses UIApplication.openURL("myapp://") to open the app
    //
    //   ⚠️ Scheme must be declared in LSApplicationQueriesSchemes
    //      in Info.plist
    //
    //   Success: {
    //     success:     true,
    //     scheme:      "myapp",
    //     message:     "App launched successfully"
    //   }
    //
    //   Error: {
    //     success:     false,
    //     scheme:      "myapp",
    //     message:     "...",
    //     errorCode:   "APP_NOT_INSTALLED"
    //                | "INVALID_SCHEME"
    //                | "OPEN_APP_ERROR"
    //   }
    // ════════════════════════════════════════════════════════
    openApp: function(packageNameOrScheme, successCallback, errorCallback) {
        if (!packageNameOrScheme || packageNameOrScheme.trim() === '') {
            if (typeof errorCallback === 'function') {
                errorCallback({
                    success:   false,
                    message:   'Package name or URL scheme is required',
                    errorCode: 'INVALID_ARGUMENT'
                });
            }
            return;
        }

        exec(successCallback, errorCallback,
            'EnterpriseAppStore', 'openApp',
            [packageNameOrScheme.trim()]);
    },
    
    // ════════════════════════════════════════════════════════
    // 11. SET BADGE NUMBER — Multi-vendor compatible
    // ────────────────────────────────────────────────────────
    // Set the app icon badge number on the home screen.
    //
    // ── Android ─────────────────────────────────────────────
    //   Uses multi-strategy approach for maximum compatibility:
    //     1. Android 8+ Notification Badge (standard)
    //     2. Vendor-specific methods (Samsung, Huawei, Xiaomi,
    //        OPPO, vivo, ZTE, Sony, HTC, ASUS)
    //     3. Generic broadcast fallback (Nova Launcher, etc.)
    //
    //   count: 0     = clear badge
    //   count: 1-999 = set badge number
    //
    //   ⚠️ Android 13+ (API 33) requires POST_NOTIFICATIONS
    //      permission for notification-based badge to work.
    //      Make sure your app requests this permission.
    //
    //   Success: {
    //     badge:        number,
    //     manufacturer: string,   // e.g. "samsung"
    //     model:        string,   // e.g. "SM-S931B"
    //     sdkVersion:   number,   // e.g. 35
    //     strategies:   string[], // e.g. ["notification_badge","samsung","generic_broadcast"]
    //     success:      true
    //   }
    //
    //   Error: {
    //     badge:        number,
    //     manufacturer: string,
    //     model:        string,
    //     sdkVersion:   number,
    //     strategies:   string[],
    //     success:      false,
    //     error:        string
    //   }
    //   — OR —
    //   "SET_BADGE_ERROR: ..."
    //   "LAUNCHER_NOT_FOUND"
    //
    // ── iOS ─────────────────────────────────────────────────
    //   Uses UIApplication.applicationIconBadgeNumber
    //   Always works (no special permission needed on iOS < 16)
    //
    //   ⚠️ iOS 16+ may require notification authorization
    //      for badge to be visible.
    //
    //   count: 0     = clear badge
    //   count: 1-999 = set badge number
    //
    //   Success: {
    //     badge:    number,
    //     platform: "ios"
    //   }
    //
    //   Error: "SET_BADGE_ERROR: ..."
    // ════════════════════════════════════════════════════════
    setBadgeNumber: function(count, successCallback, errorCallback) {
        // Normalize input: ensure count is a non-negative integer
        if (typeof count !== 'number') {
            count = parseInt(count, 10);
        }
        if (isNaN(count) || count < 0) {
            count = 0;
        }
        // Clamp to reasonable maximum (some launchers have limits)
        if (count > 9999) {
            count = 9999;
        }

        exec(
            successCallback,
            errorCallback,
            'EnterpriseAppStore',
            'setBadgeNumber',
            [count]
        );
    },

    // ════════════════════════════════════════════════════════
    // 12. CLEAR BADGE — Convenience method
    // ────────────────────────────────────────────────────────
    // Shorthand for setBadgeNumber(0)
    // Clears the badge from the app icon.
    //
    // Both platforms: removes badge completely.
    // On Android, also cancels the silent badge notification.
    // ════════════════════════════════════════════════════════
    clearBadge: function(successCallback, errorCallback) {
        EnterpriseAppStore.setBadgeNumber(0, successCallback, errorCallback);
    },
    // ════════════════════════════════════════════════════════
    // 13. CHECK STORAGE PERMISSION
    // ────────────────────────────────────────────────────────
    // Android: checks storage permission based on SDK version
    //          SDK 30+ (Android 11+): MANAGE_EXTERNAL_STORAGE
    //          SDK 23-29: WRITE_EXTERNAL_STORAGE
    //          SDK < 23: always true
    // ────────────────────────────────────────────────────────
    // iOS:     not applicable, returns { hasPermission: true }
    // ────────────────────────────────────────────────────────
    // Returns: { hasPermission: bool, sdkVersion: number }
    // ════════════════════════════════════════════════════════
    checkStoragePermission: function(successCallback, errorCallback) {
        if (EnterpriseAppStore._isAndroid()) {
            exec(successCallback, errorCallback,
                'EnterpriseAppStore', 'checkStoragePermission', []);
        } else {
            if (typeof successCallback === 'function') {
                successCallback({ hasPermission: true, sdkVersion: 0 });
            }
        }
    },

    // ════════════════════════════════════════════════════════
    // 14. REQUEST STORAGE PERMISSION
    // ────────────────────────────────────────────────────────
    // Android: requests storage permission
    //          SDK 30+: opens MANAGE_EXTERNAL_STORAGE settings
    //          SDK 23-29: runtime permission dialog
    // ────────────────────────────────────────────────────────
    // iOS:     not applicable, returns success immediately
    // ════════════════════════════════════════════════════════
    requestStoragePermission: function(successCallback, errorCallback) {
        if (EnterpriseAppStore._isAndroid()) {
            exec(successCallback, errorCallback,
                'EnterpriseAppStore', 'requestStoragePermission', []);
        } else {
            if (typeof successCallback === 'function') {
                successCallback({
                    status: 'PERMISSION_NOT_NEEDED',
                    hasPermission: true
                });
            }
        }
    }

};

module.exports = EnterpriseAppStore;
