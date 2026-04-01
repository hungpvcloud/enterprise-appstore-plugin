var exec = require('cordova/exec');

var EnterpriseAppStore = {

    /**
     * Download và Install app
     * Android : url = direct APK URL, fileName = "app.apk"
     * iOS     : url = HTTPS URL đến manifest.plist, fileName = ignored
     *
     * @param {string}   url              - APK URL (Android) | manifest.plist URL (iOS)
     * @param {string}   fileName         - Tên file APK (Android only)
     * @param {function} progressCallback - Nhận nhiều lần: { status, progress, message }
     * @param {function} errorCallback    - Nhận 1 lần khi lỗi
     */
    downloadAndInstall: function(url, fileName, progressCallback, errorCallback) {
        exec(
            progressCallback,
            errorCallback,
            'EnterpriseAppStore',
            'downloadAndInstall',
            [url, fileName || 'app.apk']
        );
    },

    /**
     * Lấy version của app theo packageName
     * Android: com.example.app
     * iOS    : trả về version của app hiện tại
     */
    getAppVersion: function(packageName, successCallback, errorCallback) {
        exec(
            successCallback,
            errorCallback,
            'EnterpriseAppStore',
            'getAppVersion',
            [packageName]
        );
    },

    /**
     * Kiểm tra app đã được cài chưa
     * Android: packageName (com.example.app)
     * iOS    : URL scheme (myapp)
     */
    isAppInstalled: function(packageName, successCallback, errorCallback) {
        exec(
            successCallback,
            errorCallback,
            'EnterpriseAppStore',
            'isAppInstalled',
            [packageName]
        );
    },

    /**
     * Hủy download đang chạy (Android only)
     */
    cancelDownload: function(successCallback, errorCallback) {
        exec(
            successCallback,
            errorCallback,
            'EnterpriseAppStore',
            'cancelDownload',
            []
        );
    },
    
    checkInstallPermission: function(successCallback, errorCallback) {
        exec(
            successCallback,
            errorCallback,
            'EnterpriseAppStore',
            'checkInstallPermission',
            []
        );
    },
    
    requestInstallPermission: function(successCallback, errorCallback) {
        exec(
            successCallback,
            errorCallback,
            'EnterpriseAppStore',
            'requestInstallPermission',
            []
        );
    }


};

module.exports = EnterpriseAppStore;