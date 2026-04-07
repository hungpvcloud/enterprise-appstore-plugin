import Foundation
import UIKit

@objc(EnterpriseAppStore)
class EnterpriseAppStore: CDVPlugin {

    // ════════════════════════════════════════════════════════
    // DOWNLOAD AND INSTALL
    // Triggers iOS OTA installation via itms-services:// protocol
    // Parameter: manifestUrl — HTTPS URL pointing to manifest.plist
    // ════════════════════════════════════════════════════════
    @objc(downloadAndInstall:)
    func downloadAndInstall(command: CDVInvokedUrlCommand) {

        // Validate manifest URL
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

        print("[EnterpriseAppStore] manifestUrl = \(manifestUrl)")

        // Percent-encode the manifest URL before embedding it as a query parameter.
        // This is required because the manifest URL may contain its own query params
        // (e.g. ?IPAUrl=...&BundleId=...) which must be encoded to avoid breaking
        // the outer itms-services URL structure.
        guard let encodedManifestUrl = manifestUrl
            .addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) else {
            sendStatus(callbackId: command.callbackId,
                       status: "ERROR",
                       progress: 0,
                       message: "URL_ENCODING_FAILED",
                       filePath: "",
                       keepCallback: false)
            return
        }

        // Build the itms-services URL using "&" (not "&amp;") as the query separator
        let itsUrlString = "itms-services://?action=download-manifest&url=\(manifestUrl)"

        print("[EnterpriseAppStore] itsUrlString = \(itsUrlString)")

        guard let itsUrl = URL(string: itsUrlString) else {
            sendStatus(callbackId: command.callbackId,
                       status: "ERROR",
                       progress: 0,
                       message: "INVALID_URL_FORMAT: \(itsUrlString)",
                       filePath: "",
                       keepCallback: false)
            return
        }

        DispatchQueue.main.async {
            // Notify the JS layer that the installation process has started
            self.sendStatus(callbackId: command.callbackId,
                            status: "INSTALLING",
                            progress: 0,
                            message: "Opening iOS installation dialog...",
                            filePath: "\(itsUrl)",
                            keepCallback: true)

            // Check whether the device supports itms-services://
            guard UIApplication.shared.canOpenURL(itsUrl) else {
                self.sendStatus(callbackId: command.callbackId,
                                status: "ERROR",
                                progress: 0,
                                message: "ITMS_NOT_SUPPORTED: Device cannot handle itms-services",
                                filePath: "\(itsUrl)",
                                keepCallback: false)
                return
            }

            // Open the itms-services URL — iOS system handles the actual download and install
            UIApplication.shared.open(itsUrl, options: [:]) { success in
                print("[EnterpriseAppStore] open itms-services result = \(success)")
                if success {
                    // Installation dialog was successfully opened
                    self.sendStatus(callbackId: command.callbackId,
                                    status: "INSTALL_PROMPT",
                                    progress: 100,
                                    message: "iOS installation dialog opened.",
                                    filePath: "\(itsUrl)",
                                    keepCallback: true)
                    // Final callback — iOS OTA process initiated
                    self.sendStatus(callbackId: command.callbackId,
                                    status: "SUCCESS",
                                    progress: 100,
                                    message: "iOS OTA installation initiated.",
                                    filePath: "\(itsUrl)",
                                    keepCallback: false)
                } else {
                    // Failed to open itms-services URL
                    self.sendStatus(callbackId: command.callbackId,
                                    status: "ERROR",
                                    progress: 0,
                                    message: "INSTALL_FAILED: Cannot open itms-services URL",
                                    filePath: "",
                                    keepCallback: false)
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════
    // GET APP VERSION
    // iOS can only retrieve the version of the currently running app.
    // The packageName argument is ignored on iOS.
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
            "packageName": packageName,
            "versionName": versionName,
            "versionCode": Int(versionCode) ?? 0
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
    // iOS does not allow direct inspection of other apps.
    // Detection is done via URL scheme — the app must declare
    // the target scheme in LSApplicationQueriesSchemes (Info.plist).
    // Returns: { isInstalled, packageName, versionName, versionCode }
    // Note: versionName is always empty on iOS (system restriction)
    // ════════════════════════════════════════════════════════
    @objc(isAppInstalled:)
    func isAppInstalled(command: CDVInvokedUrlCommand) {
        guard let urlScheme = command.arguments[0] as? String,
              !urlScheme.isEmpty,
              let url = URL(string: "\(urlScheme)://") else {
            // Return false if the URL scheme is invalid or empty
            let result: [String: Any] = [
                "isInstalled": false,
                "packageName": command.arguments[0] as? String ?? "",
                "versionName": "",
                "versionCode": 0
            ]
            let pluginResult = CDVPluginResult(
                status: CDVCommandStatus_OK,
                messageAs: result
            )
            pluginResult?.setKeepCallbackAs(false)
            self.commandDelegate.send(pluginResult, callbackId: command.callbackId)
            return
        }

        // Check if the app is installed by testing its URL scheme
        let isInstalled = UIApplication.shared.canOpenURL(url)
        let result: [String: Any] = [
            "isInstalled": isInstalled,
            "packageName": urlScheme,
            "versionName": "", // iOS cannot read version info of other apps
            "versionCode": 0
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
    // Performs device security checks for iOS.
    // Returns: {
    //   isJailbroken, isDeveloperMode, isSimulator,
    //   isInstalledFromStore, isSafe
    // }
    // isSafe = true when device is NOT jailbroken AND NOT a simulator
    // ════════════════════════════════════════════════════════
    @objc(checkAppShield:)
    func checkAppShield(command: CDVInvokedUrlCommand) {
        // Run security checks on a background thread to avoid blocking the UI
        DispatchQueue.global(qos: .userInitiated).async {
            let isJailbroken    = self.checkIsJailbroken()
            let isSimulator     = self.checkIsSimulator()
            let isDeveloperMode = self.checkIsDeveloperMode()
            let isFromStore     = self.checkIsInstalledFromStore()

            // Device is considered safe only if it's not jailbroken and not a simulator
            let isSafe = !isJailbroken && !isSimulator

            let result: [String: Any] = [
                "isJailbroken":         isJailbroken,
                "isDeveloperMode":      isDeveloperMode,
                "isSimulator":          isSimulator,
                "isInstalledFromStore": isFromStore,
                "isSafe":               isSafe
            ]

            print("[EnterpriseAppStore] checkAppShield:"
                + " jailbroken=\(isJailbroken)"
                + " devMode=\(isDeveloperMode)"
                + " simulator=\(isSimulator)"
                + " fromStore=\(isFromStore)"
                + " safe=\(isSafe)")

            // Send result back on the main thread
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

    // ── Helper: Jailbreak Detection ──────────────────────────
    // Uses multiple detection methods for reliability
    private func checkIsJailbroken() -> Bool {
        #if targetEnvironment(simulator)
        // Simulator is never considered jailbroken
        return false
        #else

        // Check 1: Presence of known jailbreak-related files and directories
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

        // Check 2: Attempt to write a file outside the app sandbox
        // On a jailbroken device, this will succeed
        let testPath = "/private/jailbreak_test_\(UUID().uuidString).txt"
        do {
            try "test".write(toFile: testPath, atomically: true, encoding: .utf8)
            try FileManager.default.removeItem(atPath: testPath)
            print("[EnterpriseAppStore] Jailbreak detected: write outside sandbox succeeded")
            return true
        } catch {
            // Write failed — expected behavior on a non-jailbroken device
        }

        // Check 3: Cydia URL scheme availability
        if let cydiaUrl = URL(string: "cydia://package/com.example.package"),
           UIApplication.shared.canOpenURL(cydiaUrl) {
            print("[EnterpriseAppStore] Jailbreak detected: Cydia URL scheme available")
            return true
        }

        // Check 4: Presence of known jailbreak-related dynamic libraries
        let suspiciousLibs = ["SubstrateLoader", "cycript",
                              "MobileSubstrate", "SSLKillSwitch"]
        for lib in suspiciousLibs {
            if Bundle(identifier: lib) != nil {
                print("[EnterpriseAppStore] Jailbreak detected: library \(lib) found")
                return true
            }
        }

        return false
        #endif
    }

    // ── Helper: Simulator Detection ──────────────────────────
    private func checkIsSimulator() -> Bool {
        #if targetEnvironment(simulator)
        return true
        #else
        // Secondary check via environment variable set by the simulator
        return ProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] != nil
        #endif
    }

    // ── Helper: Developer Mode Detection ──────────────────────
    // On iOS 16+, returns true only for DEBUG builds.
    // On iOS < 16, checks for an embedded provisioning profile
    // which indicates a development or enterprise build.
    private func checkIsDeveloperMode() -> Bool {
        if #available(iOS 16.0, *) {
            #if DEBUG
            return true
            #else
            return false
            #endif
        }
        // iOS < 16: presence of provisioning profile indicates non-App Store build
        if Bundle.main.path(forResource: "embedded", ofType: "mobileprovision") != nil {
            return true
        }
        return false
    }

    // ── Helper: App Store Installation Check ─────────────────
    // Returns true if the app was installed from the App Store.
    // Enterprise and development builds include a provisioning profile,
    // while App Store builds include a StoreKit receipt instead.
    private func checkIsInstalledFromStore() -> Bool {
        // Presence of a provisioning profile means development or enterprise build
        if Bundle.main.path(forResource: "embedded",
                            ofType: "mobileprovision") != nil {
            return false
        }
        // Check for an App Store receipt file
        if let receiptURL = Bundle.main.appStoreReceiptURL,
           FileManager.default.fileExists(atPath: receiptURL.path) {
            return true
        }
        return false
    }

    // ════════════════════════════════════════════════════════
    // GET DEVICE INFO
    // Returns comprehensive device and app information.
    // Uses the same JSON key names as Android for consistency
    // in the JavaScript layer.
    // ════════════════════════════════════════════════════════
    @objc(getDeviceInfo:)
    func getDeviceInfo(command: CDVInvokedUrlCommand) {
        DispatchQueue.main.async {
            // Enable battery monitoring to read battery level and state
            UIDevice.current.isBatteryMonitoringEnabled = true

            // Hardware information
            let model      = UIDevice.current.model           // e.g. "iPhone", "iPad"
            let deviceName = UIDevice.current.name            // e.g. "John's iPhone"
            let sysName    = UIDevice.current.systemName      // e.g. "iOS"
            let sysVersion = UIDevice.current.systemVersion   // e.g. "17.0"

            // App bundle information
            let appVersion  = Bundle.main.infoDictionary?["CFBundleShortVersionString"]
                              as? String ?? "Unknown"
            let appBuild    = Bundle.main.infoDictionary?["CFBundleVersion"]
                              as? String ?? "0"
            let packageName = Bundle.main.bundleIdentifier ?? "Unknown"

            // Screen dimensions and scale factor
            let screenWidth  = UIScreen.main.bounds.width
            let screenHeight = UIScreen.main.bounds.height
            let screenScale  = UIScreen.main.scale

            // Locale and timezone
            let locale   = Locale.current.identifier   // e.g. "en_US", "vi_VN"
            let timezone = TimeZone.current.identifier // e.g. "Asia/Ho_Chi_Minh"

            // Battery status
            let batteryLevel = UIDevice.current.batteryLevel // -1 if unavailable
            let batteryPct   = batteryLevel >= 0 ? Int(batteryLevel * 100) : -1
            let batteryState = UIDevice.current.batteryState
            let isCharging   = batteryState == .charging || batteryState == .full

            // Available and total storage (in MB)
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

            // Disable battery monitoring when done
            UIDevice.current.isBatteryMonitoringEnabled = false

            let result: [String: Any] = [
                "manufacturer":      "Apple",
                "brand":             "Apple",
                "model":             model,
                "device":            deviceName,
                "product":           model,
                "systemName":        sysName,
                "androidVersion":    sysVersion, // Shared key with Android for JS compatibility
                "systemVersion":     sysVersion,
                "sdkVersion":        0,          // Not applicable on iOS
                "appVersion":        appVersion,
                "appBuild":          appBuild,
                "appVersionCode":    Int(appBuild) ?? 0,
                "packageName":       packageName,
                "screenWidth":       Int(screenWidth),
                "screenHeight":      Int(screenHeight),
                "screenDensity":     Int(screenScale * 160), // Approximate DPI
                "locale":            locale,
                "timezone":          timezone,
                "batteryLevel":      batteryPct,
                "isCharging":        isCharging,
                "availableStorageMB": availableStorageMB,
                "totalStorageMB":    totalStorageMB
            ]

            print("[EnterpriseAppStore] getDeviceInfo: \(model) iOS \(sysVersion)")

            let pluginResult = CDVPluginResult(
                status: CDVCommandStatus_OK,
                messageAs: result
            )
            pluginResult?.setKeepCallbackAs(false)
            self.commandDelegate.send(pluginResult, callbackId: command.callbackId)
        }
    }

    // ════════════════════════════════════════════════════════
    // CHECK UPDATE
    // Compares the currently installed app version against
    // the latest version provided by the server.
    // Note: On iOS, this only checks the currently running app.
    //       The packageName argument is ignored.
    // Returns: { needsUpdate, installedVersion, latestVersion,
    //            packageName, isInstalled }
    // ════════════════════════════════════════════════════════
    @objc(checkUpdate:)
    func checkUpdate(command: CDVInvokedUrlCommand) {
        // Validate latestVersion argument
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
        let packageName  = Bundle.main.bundleIdentifier ?? ""

        // Compare semantic versions: needsUpdate = true if installed < latest
        let needsUpdate  = compareVersions(installedVersion, isLessThan: latestVersion)

        let result: [String: Any] = [
            "needsUpdate":      needsUpdate,
            "installedVersion": installedVersion,
            "latestVersion":    latestVersion,
            "packageName":      packageName,
            "isInstalled":      true // Always true on iOS — app is currently running
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

    // ── Helper: Semantic Version Comparison ──────────────────
    // Returns true if v1 is less than v2 (i.e. an update is needed).
    // Supports versions like "1.2.3", "v2.0", "10.0.1"
    private func compareVersions(_ v1: String, isLessThan v2: String) -> Bool {
        // Strip non-numeric, non-dot characters (e.g. "v1.2.3" → "1.2.3")
        let clean1 = v1.replacingOccurrences(of: "[^0-9.]", with: "",
                                              options: .regularExpression)
        let clean2 = v2.replacingOccurrences(of: "[^0-9.]", with: "",
                                              options: .regularExpression)

        let parts1 = clean1.split(separator: ".").map { Int($0) ?? 0 }
        let parts2 = clean2.split(separator: ".").map { Int($0) ?? 0 }
        let maxLen  = max(parts1.count, parts2.count)

        for i in 0..<maxLen {
            let p1 = i < parts1.count ? parts1[i] : 0
            let p2 = i < parts2.count ? parts2[i] : 0
            if p1 < p2 { return true  } // v1 is older — update needed
            if p1 > p2 { return false } // v1 is newer — no update needed
        }
        return false // Versions are equal — no update needed
    }

    // ════════════════════════════════════════════════════════
    // HELPER: Send Plugin Result Callback
    // Sends a unified status object to the JavaScript layer.
    // Matches the Android callback format for cross-platform consistency:
    // { status, progress, message, filePath }
    // Note: filePath is always an empty string on iOS
    //       since no file is downloaded locally.
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
            "filePath": filePath
        ]

        let result = CDVPluginResult(
            status: CDVCommandStatus_OK,
            messageAs: data
        )
        // keepCallback=true  → callback can be fired multiple times (progress updates)
        // keepCallback=false → final callback, Cordova will release the callback after this
        result?.setKeepCallbackAs(keepCallback)
        self.commandDelegate.send(result, callbackId: callbackId)

        print("[EnterpriseAppStore] sendStatus: \(status) \(progress)%"
            + (filePath.isEmpty ? "" : " path=\(filePath)")
            + " keep=\(keepCallback)")
    }
}
