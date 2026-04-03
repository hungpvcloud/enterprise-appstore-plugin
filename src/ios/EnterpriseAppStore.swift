import Foundation
import UIKit

@objc(EnterpriseAppStore)
class EnterpriseAppStore: CDVPlugin {

    // ════════════════════════════════════════════════════════
    // DOWNLOAD AND INSTALL (iOS OTA via itms-services)
    // manifestUrl: HTTPS URL to manifest.plist
    // ════════════════════════════════════════════════════════
    @objc(downloadAndInstall:)
    func downloadAndInstall(command: CDVInvokedUrlCommand) {
        guard let manifestUrl = command.arguments[0] as? String,
              !manifestUrl.isEmpty else {
            sendStatus(callbackId: command.callbackId,
                       status: "ERROR",
                       progress: 0,
                       message: "INVALID_MANIFEST_URL",
                       filePath: "",
                       keepCallback: false)
            return
        }

        // iOS Enterprise install dùng itms-services:// protocol
        let itsUrlString = "itms-services://?action=download-manifest&url=\(manifestUrl)"

        guard let itsUrl = URL(string: itsUrlString) else {
            sendStatus(callbackId: command.callbackId,
                       status: "ERROR",
                       progress: 0,
                       message: "INVALID_URL_FORMAT",
                       filePath: "",
                       keepCallback: false)
            return
        }

        DispatchQueue.main.async {
            // Thông báo bắt đầu
            self.sendStatus(callbackId: command.callbackId,
                            status: "INSTALLING",
                            progress: 0,
                            message: "Opening iOS installation dialog...",
                            filePath: "",
                            keepCallback: true)

            if UIApplication.shared.canOpenURL(itsUrl) {
                UIApplication.shared.open(itsUrl, options: [:]) { success in
                    if success {
                        // Gửi INSTALL_PROMPT — keepCallback=true
                        self.sendStatus(callbackId: command.callbackId,
                                        status: "INSTALL_PROMPT",
                                        progress: 100,
                                        message: "iOS installation dialog opened.",
                                        filePath: "",
                                        keepCallback: true)

                        // Gửi SUCCESS — keepCallback=false (FINAL)
                        self.sendStatus(callbackId: command.callbackId,
                                        status: "SUCCESS",
                                        progress: 100,
                                        message: "iOS OTA installation initiated.",
                                        filePath: "",
                                        keepCallback: false)
                    } else {
                        self.sendStatus(callbackId: command.callbackId,
                                        status: "ERROR",
                                        progress: 0,
                                        message: "INSTALL_FAILED: Cannot open itms-services URL",
                                        filePath: "",
                                        keepCallback: false)
                    }
                }
            } else {
                self.sendStatus(callbackId: command.callbackId,
                                status: "ERROR",
                                progress: 0,
                                message: "ITMS_NOT_SUPPORTED: Device cannot handle itms-services",
                                filePath: "",
                                keepCallback: false)
            }
        }
    }

    // ════════════════════════════════════════════════════════
    // GET APP VERSION
    // iOS chỉ lấy được version của chính app hiện tại
    // Returns: { packageName, versionName, versionCode }
    // ════════════════════════════════════════════════════════
    @objc(getAppVersion:)
    func getAppVersion(command: CDVInvokedUrlCommand) {
        let versionName = Bundle.main.infoDictionary?["CFBundleShortVersionString"]
                          as? String ?? "Unknown"
        let versionCode = Bundle.main.infoDictionary?["CFBundleVersion"]
                          as? String ?? "0"
        let packageName = Bundle.main.bundleIdentifier ?? "Unknown"

        let result: [String: Any] = [
            "packageName":  packageName,
            "versionName":  versionName,
            "versionCode":  Int(versionCode) ?? 0
        ]

        let pluginResult = CDVPluginResult(
            status: CDVCommandStatus_OK,
            messageAs: result
        )
        pluginResult?.setKeepCallbackAs(false)
        self.commandDelegate.send(pluginResult, callbackId: command.callbackId)
    }

    // ════════════════════════════════════════════════════════
    // IS APP INSTALLED
    // iOS không cho phép check app khác trực tiếp
    // Dùng URL Scheme (app phải khai báo trong LSApplicationQueriesSchemes)
    // Returns: { isInstalled, packageName, versionName, versionCode }
    // ════════════════════════════════════════════════════════
    @objc(isAppInstalled:)
    func isAppInstalled(command: CDVInvokedUrlCommand) {
        guard let urlScheme = command.arguments[0] as? String,
              !urlScheme.isEmpty,
              let url = URL(string: "\(urlScheme)://") else {
            let result: [String: Any] = [
                "isInstalled":  false,
                "packageName":  command.arguments[0] as? String ?? "",
                "versionName":  "",
                "versionCode":  0
            ]
            let pluginResult = CDVPluginResult(
                status: CDVCommandStatus_OK,
                messageAs: result
            )
            pluginResult?.setKeepCallbackAs(false)
            self.commandDelegate.send(pluginResult, callbackId: command.callbackId)
            return
        }

        let isInstalled = UIApplication.shared.canOpenURL(url)
        let result: [String: Any] = [
            "isInstalled":  isInstalled,
            "packageName":  urlScheme,
            "versionName":  "",    // iOS không lấy được version app khác
            "versionCode":  0
        ]

        let pluginResult = CDVPluginResult(
            status: CDVCommandStatus_OK,
            messageAs: result
        )
        pluginResult?.setKeepCallbackAs(false)
        self.commandDelegate.send(pluginResult, callbackId: command.callbackId)
    }

    // ════════════════════════════════════════════════════════
    // CHECK APP SHIELD
    // Kiểm tra bảo mật thiết bị iOS
    // Returns: {
    //   isJailbroken, isDeveloperMode, isSimulator,
    //   isInstalledFromStore, isSafe
    // }
    // ════════════════════════════════════════════════════════
    @objc(checkAppShield:)
    func checkAppShield(command: CDVInvokedUrlCommand) {
        DispatchQueue.global(qos: .userInitiated).async {
            let isJailbroken       = self.checkIsJailbroken()
            let isSimulator        = self.checkIsSimulator()
            let isDeveloperMode    = self.checkIsDeveloperMode()
            let isFromStore        = self.checkIsInstalledFromStore()

            // isSafe: không bị jailbreak và không chạy trên simulator
            let isSafe = !isJailbroken && !isSimulator

            let result: [String: Any] = [
                "isJailbroken":         isJailbroken,
                "isDeveloperMode":       isDeveloperMode,
                "isSimulator":           isSimulator,
                "isInstalledFromStore":  isFromStore,
                "isSafe":                isSafe
            ]

            print("[EnterpriseAppStore] checkAppShield:"
                + " jailbroken=\(isJailbroken)"
                + " devMode=\(isDeveloperMode)"
                + " simulator=\(isSimulator)"
                + " fromStore=\(isFromStore)"
                + " safe=\(isSafe)")

            DispatchQueue.main.async {
                let pluginResult = CDVPluginResult(
                    status: CDVCommandStatus_OK,
                    messageAs: result
                )
                pluginResult?.setKeepCallbackAs(false)
                self.commandDelegate.send(
                    pluginResult,
                    callbackId: command.callbackId
                )
            }
        }
    }

    // ── Helper: Check Jailbroken ─────────────────────────────
    private func checkIsJailbroken() -> Bool {
        #if targetEnvironment(simulator)
        return false
        #else
        // Check 1: Cydia và các file suspicious
        let suspiciousPaths = [
            "/Applications/Cydia.app",
            "/Library/MobileSubstrate/MobileSubstrate.dylib",
            "/bin/bash",
            "/usr/sbin/sshd",
            "/etc/apt",
            "/private/var/lib/apt/",
            "/usr/bin/ssh",
            "/private/var/stash",
            "/private/var/mobile/Library/SBSettings/Themes",
            "/Library/MobileSubstrate/DynamicLibraries/Veency.plist",
            "/Library/MobileSubstrate/DynamicLibraries/LiveClock.plist",
            "/System/Library/LaunchDaemons/com.ikey.bbot.plist",
            "/System/Library/LaunchDaemons/com.saurik.Cydia.Startup.plist"
        ]

        for path in suspiciousPaths {
            if FileManager.default.fileExists(atPath: path) {
                print("[EnterpriseAppStore] Jailbreak detected: \(path)")
                return true
            }
        }

        // Check 2: Thử ghi file ngoài sandbox
        let testPath = "/private/jailbreak_test_\(UUID().uuidString).txt"
        do {
            try "test".write(toFile: testPath,
                             atomically: true,
                             encoding: .utf8)
            try FileManager.default.removeItem(atPath: testPath)
            print("[EnterpriseAppStore] Jailbreak detected: can write outside sandbox")
            return true
        } catch {
            // Không ghi được → bình thường
        }

        // Check 3: Cydia URL scheme
        if let cydiaUrl = URL(string: "cydia://package/com.example.package"),
           UIApplication.shared.canOpenURL(cydiaUrl) {
            print("[EnterpriseAppStore] Jailbreak detected: Cydia URL scheme")
            return true
        }

        // Check 4: Dynamic library injection
        let suspiciousLibs = ["SubstrateLoader", "cycript",
                              "MobileSubstrate", "SSLKillSwitch"]
        for lib in suspiciousLibs {
            if let _ = Bundle(identifier: lib) {
                print("[EnterpriseAppStore] Jailbreak detected: lib \(lib)")
                return true
            }
        }

        return false
        #endif
    }

    // ── Helper: Check Simulator ──────────────────────────────
    private func checkIsSimulator() -> Bool {
        #if targetEnvironment(simulator)
        return true
        #else
        // Double-check via ProcessInfo
        return ProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] != nil
        #endif
    }

    // ── Helper: Check Developer Mode ─────────────────────────
    private func checkIsDeveloperMode() -> Bool {
        // iOS 16+ có thể check Developer Mode
        if #available(iOS 16.0, *) {
            // Nếu app được sign với development certificate
            // thì thường đang trong developer mode
            #if DEBUG
            return true
            #else
            return false
            #endif
        }
        // iOS < 16: check via provisioning profile
        if let _ = Bundle.main.path(forResource: "embedded",
                                     ofType: "mobileprovision") {
            return true // Có provisioning profile → development build
        }
        return false
    }

    // ── Helper: Check Installed From Store ───────────────────
    private func checkIsInstalledFromStore() -> Bool {
        // Nếu KHÔNG có embedded.mobileprovision → đến từ App Store
        if Bundle.main.path(forResource: "embedded",
                            ofType: "mobileprovision") != nil {
            return false // Development hoặc Enterprise build
        }
        // Thêm check: App Store receipt
        if let _ = Bundle.main.appStoreReceiptURL,
           FileManager.default.fileExists(
               atPath: Bundle.main.appStoreReceiptURL!.path) {
            return true
        }
        return false
    }

    // ════════════════════════════════════════════════════════
    // GET DEVICE INFO
    // Trả về thông tin đầy đủ của thiết bị iOS
    // ════════════════════════════════════════════════════════
    @objc(getDeviceInfo:)
    func getDeviceInfo(command: CDVInvokedUrlCommand) {
        DispatchQueue.main.async {
            // Bật battery monitoring
            UIDevice.current.isBatteryMonitoringEnabled = true

            // Hardware info
            let model       = UIDevice.current.model           // "iPhone", "iPad"
            let deviceName  = UIDevice.current.name            // "My iPhone"
            let sysName     = UIDevice.current.systemName      // "iOS"
            let sysVersion  = UIDevice.current.systemVersion   // "17.0"

            // App info
            let appVersion  = Bundle.main.infoDictionary?["CFBundleShortVersionString"]
                              as? String ?? "Unknown"
            let appBuild    = Bundle.main.infoDictionary?["CFBundleVersion"]
                              as? String ?? "0"
            let packageName = Bundle.main.bundleIdentifier ?? "Unknown"

            // Screen info
            let screenWidth   = UIScreen.main.bounds.width
            let screenHeight  = UIScreen.main.bounds.height
            let screenScale   = UIScreen.main.scale

            // Locale & Timezone
            let locale   = Locale.current.identifier
            let timezone = TimeZone.current.identifier

            // Battery
            let batteryLevel   = UIDevice.current.batteryLevel  // -1 nếu không xác định
            let batteryPct     = batteryLevel >= 0
                                 ? Int(batteryLevel * 100) : -1
            let batteryState   = UIDevice.current.batteryState
            let isCharging     = batteryState == .charging
                                 || batteryState == .full

            // Storage
            var availableStorageMB: Int64 = 0
            var totalStorageMB: Int64     = 0
            if let attrs = try? FileManager.default.attributesOfFileSystem(
                forPath: NSHomeDirectory()) {
                if let free = attrs[.systemFreeSize] as? Int64 {
                    availableStorageMB = free / (1024 * 1024)
                }
                if let total = attrs[.systemSize] as? Int64 {
                    totalStorageMB = total / (1024 * 1024)
                }
            }

            // Tắt battery monitoring
            UIDevice.current.isBatteryMonitoringEnabled = false

            let result: [String: Any] = [
                "manufacturer":       "Apple",
                "brand":              "Apple",
                "model":              model,
                "device":             deviceName,
                "product":            model,
                "systemName":         sysName,
                "androidVersion":     sysVersion,  // Dùng key giống Android để JS xử lý chung
                "systemVersion":      sysVersion,
                "sdkVersion":         0,           // Không có SDK version trên iOS
                "appVersion":         appVersion,
                "appBuild":           appBuild,
                "appVersionCode":     Int(appBuild) ?? 0,
                "packageName":        packageName,
                "screenWidth":        Int(screenWidth),
                "screenHeight":       Int(screenHeight),
                "screenDensity":      Int(screenScale * 160), // Convert to DPI approx
                "locale":             locale,
                "timezone":           timezone,
                "batteryLevel":       batteryPct,
                "isCharging":         isCharging,
                "availableStorageMB": availableStorageMB,
                "totalStorageMB":     totalStorageMB
            ]

            print("[EnterpriseAppStore] getDeviceInfo: \(model) iOS \(sysVersion)")

            let pluginResult = CDVPluginResult(
                status: CDVCommandStatus_OK,
                messageAs: result
            )
            pluginResult?.setKeepCallbackAs(false)
            self.commandDelegate.send(
                pluginResult,
                callbackId: command.callbackId
            )
        }
    }

    // ════════════════════════════════════════════════════════
    // CHECK UPDATE
    // So sánh version hiện tại với version mới nhất từ server
    // iOS chỉ check được version của chính app
    // Returns: { needsUpdate, installedVersion, latestVersion,
    //            packageName, isInstalled }
    // ════════════════════════════════════════════════════════
    @objc(checkUpdate:)
    func checkUpdate(command: CDVInvokedUrlCommand) {
        guard let latestVersion = command.arguments[1] as? String,
              !latestVersion.isEmpty else {
            let result: [String: Any] = [
                "needsUpdate":      false,
                "installedVersion": "",
                "latestVersion":    "",
                "packageName":      Bundle.main.bundleIdentifier ?? "",
                "isInstalled":      true
            ]
            let pluginResult = CDVPluginResult(
                status: CDVCommandStatus_OK,
                messageAs: result
            )
            pluginResult?.setKeepCallbackAs(false)
            self.commandDelegate.send(pluginResult, callbackId: command.callbackId)
            return
        }

        let installedVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"]
                               as? String ?? "0.0.0"
        let packageName      = Bundle.main.bundleIdentifier ?? ""
        let needsUpdate      = compareVersions(installedVersion,
                                                isLessThan: latestVersion)

        let result: [String: Any] = [
            "needsUpdate":      needsUpdate,
            "installedVersion": installedVersion,
            "latestVersion":    latestVersion,
            "packageName":      packageName,
            "isInstalled":      true  // iOS luôn là true (đang chạy app này)
        ]

        print("[EnterpriseAppStore] checkUpdate:"
            + " installed=\(installedVersion)"
            + " latest=\(latestVersion)"
            + " needsUpdate=\(needsUpdate)")

        let pluginResult = CDVPluginResult(
            status: CDVCommandStatus_OK,
            messageAs: result
        )
        pluginResult?.setKeepCallbackAs(false)
        self.commandDelegate.send(pluginResult, callbackId: command.callbackId)
    }

    // ── Helper: Compare semantic versions ────────────────────
    // Returns true nếu v1 < v2 (cần update)
    private func compareVersions(_ v1: String,
                                  isLessThan v2: String) -> Bool {
        // Xóa prefix không phải số (v1.2.3 → 1.2.3)
        let clean1 = v1.replacingOccurrences(
            of: "[^0-9.]", with: "",
            options: .regularExpression)
        let clean2 = v2.replacingOccurrences(
            of: "[^0-9.]", with: "",
            options: .regularExpression)

        let parts1 = clean1.split(separator: ".").map { Int($0) ?? 0 }
        let parts2 = clean2.split(separator: ".").map { Int($0) ?? 0 }
        let maxLen  = max(parts1.count, parts2.count)

        for i in 0..<maxLen {
            let p1 = i < parts1.count ? parts1[i] : 0
            let p2 = i < parts2.count ? parts2[i] : 0
            if p1 < p2 { return true  }  // v1 < v2 → cần update
            if p1 > p2 { return false }  // v1 > v2 → không cần
        }
        return false // Bằng nhau → không cần update
    }

    // ════════════════════════════════════════════════════════
    // HELPER: Send Status Callback
    // Đồng nhất với Android: { status, progress, message, filePath }
    // ════════════════════════════════════════════════════════
    private func sendStatus(callbackId: String,
                             status: String,
                             progress: Int,
                             message: String,
                             filePath: String,
                             keepCallback: Bool) {
        let data: [String: Any] = [
            "status":   status,
            "progress": progress,
            "message":  message,
            "filePath": filePath  // iOS: luôn empty string (không download file)
        ]

        let result = CDVPluginResult(
            status: CDVCommandStatus_OK,
            messageAs: data
        )
        result?.setKeepCallbackAs(keepCallback)
        self.commandDelegate.send(result, callbackId: callbackId)

        print("[EnterpriseAppStore] sendStatus: \(status) \(progress)%"
            + (filePath.isEmpty ? "" : " path=\(filePath)")
            + " keep=\(keepCallback)")
    }
}
