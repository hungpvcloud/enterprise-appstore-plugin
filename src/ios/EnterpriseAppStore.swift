import Foundation
import UIKit
import Security

@objc(EnterpriseAppStore)
class EnterpriseAppStore: CDVPlugin {

    // ════════════════════════════════════════════════════════
    // MARK: - Constants for Model Cache
    // ════════════════════════════════════════════════════════

    /// UserDefaults key prefix for cached model names
    private let modelCachePrefix     = "eas_device_model_"
    /// UserDefaults key for the cache timestamp
    private let modelCacheTimestamp   = "eas_device_model_cache_ts"
    /// Cache time-to-live: 30 days (IPSW API is very stable)
    private let modelCacheTTL: TimeInterval = 30 * 24 * 60 * 60

    // ════════════════════════════════════════════════════════
    // MARK: - DOWNLOAD AND INSTALL
    // Triggers iOS OTA installation via itms-services:// protocol.
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

        // Build the itms-services URL
        let itsUrlString = "itms-services://?action=download-manifest&url=\(encodedManifestUrl)"

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
            self.sendStatus(callbackId: command.callbackId,
                            status: "INSTALLING",
                            progress: 0,
                            message: "Opening iOS installation dialog...",
                            filePath: "\(itsUrl)",
                            keepCallback: true)

            guard UIApplication.shared.canOpenURL(itsUrl) else {
                self.sendStatus(callbackId: command.callbackId,
                                status: "ERROR",
                                progress: 0,
                                message: "ITMS_NOT_SUPPORTED: Device cannot handle itms-services",
                                filePath: "\(itsUrl)",
                                keepCallback: false)
                return
            }

            UIApplication.shared.open(itsUrl, options: [:]) { success in
                print("[EnterpriseAppStore] open itms-services result = \(success)")
                if success {
                    self.sendStatus(callbackId: command.callbackId,
                                    status: "INSTALL_PROMPT",
                                    progress: 100,
                                    message: "iOS installation dialog opened.",
                                    filePath: "\(itsUrl)",
                                    keepCallback: true)
                    self.sendStatus(callbackId: command.callbackId,
                                    status: "SUCCESS",
                                    progress: 100,
                                    message: "iOS OTA installation initiated.",
                                    filePath: "\(itsUrl)",
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
        }
    }

    // ════════════════════════════════════════════════════════
    // MARK: - GET APP VERSION
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
    // MARK: - IS APP INSTALLED
    // Returns: { isInstalled, packageName, versionName, versionCode }
    // ════════════════════════════════════════════════════════

    @objc(isAppInstalled:)
    func isAppInstalled(command: CDVInvokedUrlCommand) {
        guard let urlScheme = command.arguments[0] as? String,
              !urlScheme.isEmpty,
              let url = URL(string: "\(urlScheme)://") else {
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

        let isInstalled = UIApplication.shared.canOpenURL(url)
        let result: [String: Any] = [
            "isInstalled": isInstalled,
            "packageName": urlScheme,
            "versionName": "",
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
    // MARK: - CHECK APP SHIELD
    // Returns: { isJailbroken, isDeveloperMode, isSimulator,
    //            isInstalledFromStore, isSafe }
    // ════════════════════════════════════════════════════════

    @objc(checkAppShield:)
    func checkAppShield(command: CDVInvokedUrlCommand) {
        DispatchQueue.global(qos: .userInitiated).async {
            let isJailbroken    = self.checkIsJailbroken()
            let isSimulator     = self.checkIsSimulator()
            let isDeveloperMode = self.checkIsDeveloperMode()
            let isFromStore     = self.checkIsInstalledFromStore()
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

            DispatchQueue.main.async {
                let pluginResult = CDVPluginResult(
                    status: CDVCommandStatus_OK,
                    messageAs: result
                )
                pluginResult?.setKeepCallbackAs(false)
                self.commandDelegate.send(pluginResult, callbackId: command.callbackId)
            }
        }
    }

    // ── Jailbreak Detection ─────────────────────────────────
    private func checkIsJailbroken() -> Bool {
        #if targetEnvironment(simulator)
        return false
        #else
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
                return true
            }
        }

        let testPath = "/private/jailbreak_test_\(UUID().uuidString).txt"
        do {
            try "test".write(toFile: testPath, atomically: true, encoding: .utf8)
            try FileManager.default.removeItem(atPath: testPath)
            return true
        } catch {}

        if let cydiaUrl = URL(string: "cydia://package/com.example.package"),
           UIApplication.shared.canOpenURL(cydiaUrl) {
            return true
        }

        let suspiciousLibs = ["SubstrateLoader", "cycript",
                              "MobileSubstrate", "SSLKillSwitch"]
        for lib in suspiciousLibs {
            if Bundle(identifier: lib) != nil {
                return true
            }
        }

        return false
        #endif
    }

    // ── Simulator Detection ─────────────────────────────────
    private func checkIsSimulator() -> Bool {
        #if targetEnvironment(simulator)
        return true
        #else
        return ProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] != nil
        #endif
    }

    // ── Developer Mode Detection ────────────────────────────
    private func checkIsDeveloperMode() -> Bool {
        if #available(iOS 16.0, *) {
            #if DEBUG
            return true
            #else
            return false
            #endif
        }
        if Bundle.main.path(forResource: "embedded", ofType: "mobileprovision") != nil {
            return true
        }
        return false
    }

    // ── App Store Installation Check ────────────────────────
    private func checkIsInstalledFromStore() -> Bool {
        if Bundle.main.path(forResource: "embedded",
                            ofType: "mobileprovision") != nil {
            return false
        }
        if let receiptURL = Bundle.main.appStoreReceiptURL,
           FileManager.default.fileExists(atPath: receiptURL.path) {
            return true
        }
        return false
    }

    // ════════════════════════════════════════════════════════
    // MARK: - ⭐ GET DEVICE INFO (FULLY AUTOMATIC)
    //
    // Model Name Resolution — 3-Layer Automatic Strategy:
    //
    //   Layer 1: Local Cache (UserDefaults)
    //            → Fastest, works offline
    //
    //   Layer 2: IPSW.me Public API
    //            → https://api.ipsw.me/v4/device/{identifier}
    //            → Free, no API key, auto-updated within hours
    //              of new Apple device announcements
    //            → Returns official Apple marketing name
    //            → Running reliably since 2015
    //            → You NEVER need to maintain any mapping file
    //
    //   Layer 3: Fallback auto-parse
    //            → "iPhone17,1" → "iPhone (17,1)"
    //            → Never fails, always returns a value
    //
    // UUID Strategy:
    //   Keychain-backed so the UUID persists even if the user
    //   deletes and reinstalls the app.
    // ════════════════════════════════════════════════════════

    @objc(getDeviceInfo:)
    func getDeviceInfo(command: CDVInvokedUrlCommand) {

        // Get the raw machine identifier (e.g. "iPhone17,1")
        let machineId = self.getMachineIdentifier()

        // Resolve marketing model name asynchronously
        // May trigger IPSW API call on first launch or after cache expiry
        self.resolveModelName(identifier: machineId) { [weak self] modelName in
            guard let self = self else { return }

            // All UIKit calls must be on the main thread
            DispatchQueue.main.async {

                // ── Enable battery monitoring ──
                UIDevice.current.isBatteryMonitoringEnabled = true

                // ── Hardware information ──
                let deviceName = UIDevice.current.name
                let sysName    = UIDevice.current.systemName
                let sysVersion = UIDevice.current.systemVersion

                // ── App bundle information ──
                let appVersion  = Bundle.main.infoDictionary?["CFBundleShortVersionString"]
                                  as? String ?? "Unknown"
                let appBuild    = Bundle.main.infoDictionary?["CFBundleVersion"]
                                  as? String ?? "0"
                let packageName = Bundle.main.bundleIdentifier ?? "Unknown"

                // ── Screen dimensions ──
                let screenWidth  = UIScreen.main.bounds.width
                let screenHeight = UIScreen.main.bounds.height
                let screenScale  = UIScreen.main.scale

                // ── Locale and timezone ──
                let locale   = Locale.current.identifier
                let timezone = TimeZone.current.identifier

                // ── Battery status ──
                let batteryLevel = UIDevice.current.batteryLevel
                let batteryPct   = batteryLevel >= 0 ? Int(batteryLevel * 100) : -1
                let batteryState = UIDevice.current.batteryState
                let isCharging   = batteryState == .charging || batteryState == .full

                // ── Storage (in MB) ──
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

                // ── Disable battery monitoring ──
                UIDevice.current.isBatteryMonitoringEnabled = false

                // ── Get persistent UUID from Keychain ──
                let uuid = self.getStableUUID()

                // ── Build result dictionary ──
                let result: [String: Any] = [
                    "uuid":               uuid,
                    "machineIdentifier":  machineId,
                    "manufacturer":       "Apple",
                    "brand":              "Apple",
                    "model":              modelName,
                    "device":             deviceName,
                    "product":            modelName,
                    "systemName":         sysName,
                    "androidVersion":     sysVersion,
                    "systemVersion":      sysVersion,
                    "sdkVersion":         0,
                    "appVersion":         appVersion,
                    "appBuild":           appBuild,
                    "appVersionCode":     Int(appBuild) ?? 0,
                    "packageName":        packageName,
                    "screenWidth":        Int(screenWidth),
                    "screenHeight":       Int(screenHeight),
                    "screenDensity":      Int(screenScale * 160),
                    "locale":             locale,
                    "timezone":           timezone,
                    "batteryLevel":       batteryPct,
                    "isCharging":         isCharging,
                    "availableStorageMB": availableStorageMB,
                    "totalStorageMB":     totalStorageMB
                ]

                print("[EnterpriseAppStore] getDeviceInfo: \(modelName) (\(machineId)) iOS \(sysVersion) UUID=\(uuid)")

                let pluginResult = CDVPluginResult(
                    status: CDVCommandStatus_OK,
                    messageAs: result
                )
                pluginResult?.setKeepCallbackAs(false)
                self.commandDelegate.send(pluginResult, callbackId: command.callbackId)
            }
        }
    }

    // ════════════════════════════════════════════════════════
    // MARK: - Machine Identifier
    // Uses sysctlbyname("hw.machine") to get the raw hardware
    // identifier. e.g. "iPhone17,1", "iPad14,8"
    // Handles simulator by reading environment variable.
    // ════════════════════════════════════════════════════════

    private func getMachineIdentifier() -> String {
        var size = 0
        sysctlbyname("hw.machine", nil, &size, nil, 0)
        var machine = [CChar](repeating: 0, count: size)
        sysctlbyname("hw.machine", &machine, &size, nil, 0)
        let identifier = String(cString: machine)

        // On simulator, hw.machine returns "x86_64" or "arm64"
        // Read the simulated device model from environment instead
        if identifier == "x86_64" || identifier == "arm64" {
            return ProcessInfo.processInfo
                .environment["SIMULATOR_MODEL_IDENTIFIER"] ?? identifier
        }

        return identifier
    }

    // ════════════════════════════════════════════════════════
    // MARK: - Stable UUID (Keychain-backed)
    // Persists across app reinstalls unlike identifierForVendor
    // which resets when user deletes ALL apps
    // from the same vendor.
    // ════════════════════════════════════════════════════════

    private func getStableUUID() -> String {
        let keychainKey = "com.enterprise.appstore.device.uuid"

        // Step 1: Try reading existing UUID from Keychain
        if let existingUUID = KeychainHelper.read(key: keychainKey) {
            return existingUUID
        }

        // Step 2: Generate new UUID from identifierForVendor or random
        let newUUID = UIDevice.current.identifierForVendor?.uuidString
                      ?? UUID().uuidString

        // Step 3: Persist to Keychain for future use
        KeychainHelper.save(key: keychainKey, value: newUUID)

        print("[EnterpriseAppStore] UUID generated and saved to Keychain: \(newUUID)")
        return newUUID
    }

    // ════════════════════════════════════════════════════════
    // MARK: - ⭐ Model Name Resolution (3-Layer Automatic)
    //
    // Layer 1: Local Cache (UserDefaults) — checked first
    // Layer 2: IPSW.me API — free, public, auto-updated
    // Layer 3: Fallback auto-parse — never fails
    //
    // ★ IPSW.me API Details:
    //   URL:      https://api.ipsw.me/v4/device/{identifier}
    //   Method:   GET
    //   Auth:     None (no API key required)
    //   Response: {
    //     "name": "iPhone 16 Pro",
    //     "identifier": "iPhone17,1",
    //     "boards": [...],
    //     "firmwares": [...]
    //   }
    //   We only need the "name" field.
    //
    // ★ Why IPSW.me?
    //   - Free and public, no registration
    //   - Updated within hours of new device announcements
    //   - Returns official Apple marketing names
    //   - Running reliably since 2015
    //   - You NEVER need to maintain any mapping file or JSON
    //   - Used by thousands of apps and tools worldwide
    // ════════════════════════════════════════════════════════

    /// Main resolver: Layer 1 → Layer 2 → Layer 3
    private func resolveModelName(
        identifier: String,
        completion: @escaping (String) -> Void
    ) {
        // ── Layer 1: Check local cache ──
        if isCacheValid(),
           let cached = getModelFromCache(identifier: identifier) {
            print("[EnterpriseAppStore] Model CACHE hit: \(cached)")
            completion(cached)
            return
        }

        // ── Layer 2: Fetch from IPSW.me API ──
        fetchModelFromIPSW(identifier: identifier) { [weak self] apiName in
            guard let self = self else { return }

            if let name = apiName, !name.isEmpty {
                // API returned a valid name — cache it and return
                print("[EnterpriseAppStore] Model IPSW API: \(name)")
                self.saveModelToCache(identifier: identifier, modelName: name)
                completion(name)
                return
            }

            // ── Layer 3: Fallback auto-parse ──
            let fallback = self.fallbackModelName(identifier: identifier)
            print("[EnterpriseAppStore] Model FALLBACK: \(fallback)")
            // Also cache the fallback so we don't retry the API every time
            self.saveModelToCache(identifier: identifier, modelName: fallback)
            completion(fallback)
        }
    }

    // ── Layer 1: Local Cache Helpers ────────────────────────

    /// Read cached model name for a machine identifier
    private func getModelFromCache(identifier: String) -> String? {
        let key = modelCachePrefix + identifier
        return UserDefaults.standard.string(forKey: key)
    }

    /// Save model name to local cache with timestamp
    private func saveModelToCache(identifier: String, modelName: String) {
        let key = modelCachePrefix + identifier
        UserDefaults.standard.set(modelName, forKey: key)
        UserDefaults.standard.set(Date().timeIntervalSince1970,
                                  forKey: modelCacheTimestamp)
    }

    /// Check whether local cache is still within TTL (30 days)
    private func isCacheValid() -> Bool {
        let timestamp = UserDefaults.standard.double(forKey: modelCacheTimestamp)
        guard timestamp > 0 else { return false }
        return (Date().timeIntervalSince1970 - timestamp) < modelCacheTTL
    }

    // ── Layer 2: IPSW.me API ────────────────────────────────
    //
    // Example request:
    //   GET https://api.ipsw.me/v4/device/iPhone17,1
    //
    // Example response (trimmed):
    //   {
    //     "name": "iPhone 16 Pro",
    //     "identifier": "iPhone17,1",
    //     "boardconfig": "D93AP",
    //     "platform": "t8140",
    //     "cpid": 33024,
    //     "bdid": 2,
    //     "firmwares": [ ... ]
    //   }
    //
    // We only extract the "name" field.

    private func fetchModelFromIPSW(
        identifier: String,
        completion: @escaping (String?) -> Void
    ) {
        // URL-encode the identifier (e.g. "iPhone17,1" → "iPhone17%2C1")
        // The comma in identifiers like "iPhone17,1" must be percent-encoded
        guard let encoded = identifier.addingPercentEncoding(
            withAllowedCharacters: .urlPathAllowed
        ) else {
            print("[EnterpriseAppStore] IPSW: Failed to encode identifier: \(identifier)")
            completion(nil)
            return
        }

        let urlString = "https://api.ipsw.me/v4/device/\(encoded)"

        guard let url = URL(string: urlString) else {
            print("[EnterpriseAppStore] IPSW: Invalid URL: \(urlString)")
            completion(nil)
            return
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 5  // 5 second timeout to avoid blocking
        request.cachePolicy = .reloadIgnoringLocalCacheData
        // IPSW.me requires Accept header for JSON response
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        print("[EnterpriseAppStore] IPSW: Fetching model name from \(urlString)")

        URLSession.shared.dataTask(with: request) { data, response, error in

            // Check for network errors
            if let error = error {
                print("[EnterpriseAppStore] IPSW: Network error: \(error.localizedDescription)")
                completion(nil)
                return
            }

            // Validate HTTP status code
            guard let httpResponse = response as? HTTPURLResponse else {
                print("[EnterpriseAppStore] IPSW: Invalid response type")
                completion(nil)
                return
            }

            guard httpResponse.statusCode == 200 else {
                print("[EnterpriseAppStore] IPSW: HTTP \(httpResponse.statusCode) for \(identifier)")
                completion(nil)
                return
            }

            // Validate response data
            guard let data = data else {
                print("[EnterpriseAppStore] IPSW: Empty response data")
                completion(nil)
                return
            }

            // Parse JSON and extract "name" field
            do {
                if let json = try JSONSerialization.jsonObject(with: data)
                    as? [String: Any],
                   let name = json["name"] as? String {
                    completion(name)
                } else {
                    print("[EnterpriseAppStore] IPSW: 'name' field not found in response")
                    completion(nil)
                }
            } catch {
                print("[EnterpriseAppStore] IPSW: JSON parse error: \(error.localizedDescription)")
                completion(nil)
            }
        }.resume()
    }

    // ── Layer 3: Fallback Auto-Parse ────────────────────────
    //
    // When both cache and IPSW API fail, this method parses
    // the raw machine identifier into a human-readable format.
    // It will NEVER return nil or an empty string.
    //
    // Examples:
    //   "iPhone17,1"        → "iPhone (17,1)"
    //   "iPad14,8"          → "iPad (14,8)"
    //   "iPod9,1"           → "iPod touch (9,1)"
    //   "Watch7,1"          → "Apple Watch (7,1)"
    //   "AudioAccessory6,1" → "HomePod (6,1)"
    //   "AppleTV14,1"       → "Apple TV (14,1)"
    //   "arm64"             → "arm64" (simulator edge case)

    private func fallbackModelName(identifier: String) -> String {
        // Split identifier into letter prefix and numeric suffix
        // e.g. "iPhone17,1" → prefix="iPhone", suffix="17,1"
        var prefix = ""
        var suffix = ""
        var hitDigit = false

        for char in identifier {
            if char.isNumber || char == "," || hitDigit {
                hitDigit = true
                suffix.append(char)
            } else {
                prefix.append(char)
            }
        }

        // Map common prefixes to user-friendly device type names
        let deviceType: String
        switch prefix.lowercased() {
        case "iphone":         deviceType = "iPhone"
        case "ipad":           deviceType = "iPad"
        case "ipod":           deviceType = "iPod touch"
        case "watch":          deviceType = "Apple Watch"
        case "appletv":        deviceType = "Apple TV"
        case "audioaccessory": deviceType = "HomePod"
        case "macbookair":     deviceType = "MacBook Air"
        case "macbookpro":     deviceType = "MacBook Pro"
        case "mac":            deviceType = "Mac"
        default:               deviceType = prefix
        }

        // If no numeric suffix found, return just the device type
        if suffix.isEmpty {
            return deviceType.isEmpty ? identifier : deviceType
        }

        return "\(deviceType) (\(suffix))"
    }

    // ════════════════════════════════════════════════════════
    // MARK: - CHECK UPDATE
    // Compares installed app version against latest version.
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
        let packageName  = Bundle.main.bundleIdentifier ?? ""
        let needsUpdate  = compareVersions(installedVersion, isLessThan: latestVersion)

        let result: [String: Any] = [
            "needsUpdate":      needsUpdate,
            "installedVersion": installedVersion,
            "latestVersion":    latestVersion,
            "packageName":      packageName,
            "isInstalled":      true
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

    // ════════════════════════════════════════════════════════
    // MARK: - OPEN APP
    // Launch an installed app by its URL scheme.
    // No LSApplicationQueriesSchemes needed.
    // ════════════════════════════════════════════════════════

    @objc(openApp:)
    func openApp(command: CDVInvokedUrlCommand) {

        guard let urlScheme = command.arguments[0] as? String,
              !urlScheme.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {

            let result: [String: Any] = [
                "success":     false,
                "scheme":      "",
                "packageName": "",
                "message":     "URL scheme is required",
                "errorCode":   "INVALID_SCHEME"
            ]
            let pluginResult = CDVPluginResult(
                status: CDVCommandStatus_ERROR,
                messageAs: result
            )
            pluginResult?.setKeepCallbackAs(false)
            self.commandDelegate.send(pluginResult, callbackId: command.callbackId)
            return
        }

        let scheme = urlScheme.trimmingCharacters(in: .whitespacesAndNewlines)

        // Build URL: "myapp" → "myapp://", "myapp://path" → as-is
        let urlString: String
        if scheme.contains("://") {
            urlString = scheme
        } else {
            urlString = "\(scheme)://"
        }

        guard let appUrl = URL(string: urlString) else {
            let result: [String: Any] = [
                "success":     false,
                "scheme":      scheme,
                "packageName": scheme,
                "message":     "Cannot create URL from scheme: \(scheme)",
                "errorCode":   "INVALID_SCHEME"
            ]
            let pluginResult = CDVPluginResult(
                status: CDVCommandStatus_ERROR,
                messageAs: result
            )
            pluginResult?.setKeepCallbackAs(false)
            self.commandDelegate.send(pluginResult, callbackId: command.callbackId)
            return
        }

        // Open directly without canOpenURL() — no Info.plist config needed
        DispatchQueue.main.async {
            UIApplication.shared.open(appUrl, options: [:]) { success in
                if success {
                    let result: [String: Any] = [
                        "success":     true,
                        "scheme":      scheme,
                        "packageName": scheme,
                        "message":     "App launched successfully"
                    ]
                    let pluginResult = CDVPluginResult(
                        status: CDVCommandStatus_OK,
                        messageAs: result
                    )
                    pluginResult?.setKeepCallbackAs(false)
                    self.commandDelegate.send(pluginResult, callbackId: command.callbackId)
                } else {
                    let result: [String: Any] = [
                        "success":     false,
                        "scheme":      scheme,
                        "packageName": scheme,
                        "message":     "App not installed or scheme '\(scheme)' is not registered. URL: \(urlString)",
                        "errorCode":   "APP_NOT_INSTALLED"
                    ]
                    let pluginResult = CDVPluginResult(
                        status: CDVCommandStatus_ERROR,
                        messageAs: result
                    )
                    pluginResult?.setKeepCallbackAs(false)
                    self.commandDelegate.send(pluginResult, callbackId: command.callbackId)
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════
    // MARK: - Helper: Semantic Version Comparison
    // Returns true if v1 < v2 (update needed).
    // Supports: "1.2.3", "v2.0", "10.0.1"
    // ════════════════════════════════════════════════════════

    private func compareVersions(_ v1: String, isLessThan v2: String) -> Bool {
        let clean1 = v1.replacingOccurrences(of: "[^0-9.]", with: "",
                                              options: .regularExpression)
        let clean2 = v2.replacingOccurrences(of: "[^0-9.]", with: "",
                                              options: .regularExpression)

        let parts1 = clean1.split(separator: ".").map { Int(\$0) ?? 0 }
        let parts2 = clean2.split(separator: ".").map { Int(\$0) ?? 0 }
        let maxLen  = max(parts1.count, parts2.count)

        for i in 0..<maxLen {
            let p1 = i < parts1.count ? parts1[i] : 0
            let p2 = i < parts2.count ? parts2[i] : 0
            if p1 < p2 { return true  }
            if p1 > p2 { return false }
        }
        return false
    }

    // ════════════════════════════════════════════════════════
    // MARK: - Helper: Send Plugin Result Callback
    // Sends unified status: { status, progress, message, filePath }
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
        result?.setKeepCallbackAs(keepCallback)
        self.commandDelegate.send(result, callbackId: callbackId)

        print("[EnterpriseAppStore] sendStatus: \(status) \(progress)%"
            + (filePath.isEmpty ? "" : " path=\(filePath)")
            + " keep=\(keepCallback)")
    }
}

// ════════════════════════════════════════════════════════════
// MARK: - KeychainHelper
// Lightweight helper for reading/writing string values to
// the iOS Keychain. Data persists across app reinstalls,
// making it ideal for device UUIDs.
//
// Uses kSecClassGenericPassword with:
//   - kSecAttrAccount:    the key name
//   - kSecAttrService:    app's bundle identifier (scoping)
//   - kSecAttrAccessible: kSecAttrAccessibleAfterFirstUnlock
//     (available after first unlock, including background)
// ════════════════════════════════════════════════════════════

class KeychainHelper {

    /// Service name scoped to this app to avoid collisions
    private static let serviceName = Bundle.main.bundleIdentifier
                                     ?? "com.enterprise.appstore"

    // ── Save string value to Keychain ───────────────────────
    // If key already exists, deletes and re-adds to avoid
    // errSecDuplicateItem (-25299).
    @discardableResult
    static func save(key: String, value: String) -> Bool {
        guard let data = value.data(using: .utf8) else {
            print("[KeychainHelper] save: failed to encode value to UTF-8")
            return false
        }

        let query: [String: Any] = [
            kSecClass as String:       kSecClassGenericPassword,
            kSecAttrService as String: serviceName,
            kSecAttrAccount as String: key,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock
        ]

        // Delete existing item first to prevent duplicate error
        SecItemDelete(query as CFDictionary)

        // Add new item with value data
        var addQuery = query
        addQuery[kSecValueData as String] = data

        let status = SecItemAdd(addQuery as CFDictionary, nil)

        if status == errSecSuccess {
            print("[KeychainHelper] save: key='\(key)' saved successfully")
            return true
        } else {
            print("[KeychainHelper] save: key='\(key)' failed with OSStatus \(status)")
            return false
        }
    }

    // ── Read string value from Keychain ─────────────────────
    // Returns nil if key does not exist or cannot be read.
    static func read(key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String:       kSecClassGenericPassword,
            kSecAttrService as String: serviceName,
            kSecAttrAccount as String: key,
            kSecReturnData as String:  true,
            kSecMatchLimit as String:  kSecMatchLimitOne
        ]

        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)

        if status == errSecSuccess,
           let data = result as? Data,
           let value = String(data: data, encoding: .utf8) {
            return value
        }

        if status != errSecItemNotFound {
            print("[KeychainHelper] read: key='\(key)' failed with OSStatus \(status)")
        }

        return nil
    }

    // ── Delete value from Keychain ──────────────────────────
    // Returns true if deleted or did not exist.
    @discardableResult
    static func delete(key: String) -> Bool {
        let query: [String: Any] = [
            kSecClass as String:       kSecClassGenericPassword,
            kSecAttrService as String: serviceName,
            kSecAttrAccount as String: key
        ]

        let status = SecItemDelete(query as CFDictionary)

        if status == errSecSuccess {
            print("[KeychainHelper] delete: key='\(key)' deleted successfully")
            return true
        } else if status == errSecItemNotFound {
            print("[KeychainHelper] delete: key='\(key)' not found (already deleted)")
            return true
        } else {
            print("[KeychainHelper] delete: key='\(key)' failed with OSStatus \(status)")
            return false
        }
    }
}
