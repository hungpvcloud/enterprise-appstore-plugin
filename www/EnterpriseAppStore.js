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
    
    setBadgeNumber: function (count, success, error) {
        // Normalize input
        if (typeof count !== "number") {
            count = parseInt(count, 10) || 0;
        }

        exec(
            success,
            error,
            "EnterpriseAppStore",   // class name native
            "setBadgeNumber",       // action name
            [count]                 // arguments
        );
    },

};

module.exports = EnterpriseAppStore;
