var exec = require('cordova/exec');

var EnterpriseAppStore = {

    /**
     * Download và Install APK — Full flow tự động:
     * 1. Check permission
     * 2. Nếu chưa có → Mở Settings → Chờ user bật → Tự động resume
     * 3. Download với progress
     * 4. Install APK
     *
     * Callback statuses:
     *   WAITING_PERMISSION → Đang chờ user bật permission
     *   PERMISSION_GRANTED → User đã bật, bắt đầu download
     *   PERMISSION_DENIED  → User từ chối
     *   DOWNLOADING        → Đang download (progress: 0-99)
     *   DOWNLOAD_COMPLETE  → Download xong, bắt đầu install
     *   INSTALL_PROMPT     → Dialog install đã mở ✅ (final)
     *   ERROR              → Lỗi (final)
     */
    downloadAndInstall: function(url, fileName, progressCallback, errorCallback) {
        if (fileName && !fileName.toLowerCase().endsWith('.apk')) {
            fileName = fileName + '.apk';
        }
        exec(progressCallback, errorCallback,
            'EnterpriseAppStore', 'downloadAndInstall',
            [url, fileName || 'app.apk']);
    },

    /**
     * Request permission + tự động resume download sau khi bật
     * @param {string} url      - APK URL (để tự động resume)
     * @param {string} fileName - Tên file APK
     */
    requestInstallPermission: function(url, fileName,
                                       progressCallback, errorCallback) {
        if (fileName && !fileName.toLowerCase().endsWith('.apk')) {
            fileName = fileName + '.apk';
        }
        exec(progressCallback, errorCallback,
            'EnterpriseAppStore', 'requestInstallPermission',
            [url || '', fileName || '']);
    },

    checkInstallPermission: function(successCallback, errorCallback) {
        exec(successCallback, errorCallback,
            'EnterpriseAppStore', 'checkInstallPermission', []);
    },

    getAppVersion: function(packageName, successCallback, errorCallback) {
        exec(successCallback, errorCallback,
            'EnterpriseAppStore', 'getAppVersion', [packageName]);
    },

    isAppInstalled: function(packageName, successCallback, errorCallback) {
        exec(successCallback, errorCallback,
            'EnterpriseAppStore', 'isAppInstalled', [packageName]);
    },

    cancelDownload: function(successCallback, errorCallback) {
        exec(successCallback, errorCallback,
            'EnterpriseAppStore', 'cancelDownload', []);
    }
};

module.exports = EnterpriseAppStore;
