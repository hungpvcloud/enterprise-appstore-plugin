var exec = require('cordova/exec');

var EnterpriseAppStore = {

    // ────────────────────────────────────────────────────────
    // 1. DOWNLOAD AND INSTALL
    // status: DOWNLOADING → DOWNLOAD_COMPLETE → INSTALL_PROMPT
    // ────────────────────────────────────────────────────────
    downloadAndInstall: function(url, fileName, progressCallback, errorCallback) {
        if (fileName && !fileName.toLowerCase().endsWith('.apk')) {
            fileName = fileName + '.apk';
        }
        exec(progressCallback, errorCallback,
            'EnterpriseAppStore', 'downloadAndInstall',
            [url, fileName || 'app.apk']);
    },

    // ────────────────────────────────────────────────────────
    // 2. CHECK INSTALL PERMISSION
    // Returns: { hasPermission: bool }
    // ────────────────────────────────────────────────────────
    checkInstallPermission: function(successCallback, errorCallback) {
        exec(successCallback, errorCallback,
            'EnterpriseAppStore', 'checkInstallPermission', []);
    },

    // ────────────────────────────────────────────────────────
    // 3. REQUEST INSTALL PERMISSION (+ auto resume download)
    // ────────────────────────────────────────────────────────
    requestInstallPermission: function(url, fileName,
                                       progressCallback, errorCallback) {
        if (fileName && !fileName.toLowerCase().endsWith('.apk')) {
            fileName = fileName + '.apk';
        }
        exec(progressCallback, errorCallback,
            'EnterpriseAppStore', 'requestInstallPermission',
            [url || '', fileName || '']);
    },

    // ────────────────────────────────────────────────────────
    // 4. GET APP VERSION
    // Returns: { packageName, versionName, versionCode }
    // Error:   "APP_NOT_FOUND"
    // ────────────────────────────────────────────────────────
    getAppVersion: function(packageName, successCallback, errorCallback) {
        exec(successCallback, errorCallback,
            'EnterpriseAppStore', 'getAppVersion', [packageName]);
    },

    // ────────────────────────────────────────────────────────
    // 5. IS APP INSTALLED
    // Returns: { isInstalled, packageName, versionName, versionCode }
    // ────────────────────────────────────────────────────────
    isAppInstalled: function(packageName, successCallback, errorCallback) {
        exec(successCallback, errorCallback,
            'EnterpriseAppStore', 'isAppInstalled', [packageName]);
    },

    // ────────────────────────────────────────────────────────
    // 6. CHECK APP SHIELD
    // Returns: {
    //   isRooted, isDeveloperMode, isUsbDebugging,
    //   isEmulator, isInstalledFromStore, isSafe
    // }
    // ────────────────────────────────────────────────────────
    checkAppShield: function(successCallback, errorCallback) {
        exec(successCallback, errorCallback,
            'EnterpriseAppStore', 'checkAppShield', []);
    },

    // ────────────────────────────────────────────────────────
    // 7. GET DEVICE INFO
    // Returns full device information object
    // ────────────────────────────────────────────────────────
    getDeviceInfo: function(successCallback, errorCallback) {
        exec(successCallback, errorCallback,
            'EnterpriseAppStore', 'getDeviceInfo', []);
    },

    // ────────────────────────────────────────────────────────
    // 8. CHECK UPDATE
    // packageName: com.example.app
    // latestVersion: "2.0.0" (from your server API)
    // Returns: { needsUpdate, installedVersion, latestVersion,
    //            packageName, isInstalled }
    // ────────────────────────────────────────────────────────
    checkUpdate: function(packageName, latestVersion,
                          successCallback, errorCallback) {
        exec(successCallback, errorCallback,
            'EnterpriseAppStore', 'checkUpdate',
            [packageName, latestVersion]);
    },

    // ────────────────────────────────────────────────────────
    // 9. CANCEL DOWNLOAD
    // ────────────────────────────────────────────────────────
    cancelDownload: function(successCallback, errorCallback) {
        exec(successCallback, errorCallback,
            'EnterpriseAppStore', 'cancelDownload', []);
    }
};

module.exports = EnterpriseAppStore;
