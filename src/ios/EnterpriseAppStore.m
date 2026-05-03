/****************************************************************
 * EnterpriseAppStore - Cordova Plugin for iOS (Objective-C)
 *
 * Features:
 *   - downloadAndInstall : OTA installation via itms-services://
 *   - getAppVersion      : Current app version info
 *   - isAppInstalled     : Check if app is installed via URL scheme
 *   - checkAppShield     : Device security checks
 *   - getDeviceInfo      : Comprehensive device information
 *                          with auto model name resolution (IPSW API)
 *                          and persistent UUID (Keychain)
 *   - checkUpdate        : Compare installed vs latest version
 *   - openApp            : Launch another app by URL scheme
 ****************************************************************/

#import <Cordova/CDV.h>
#import <UIKit/UIKit.h>
#import <Security/Security.h>
#import <sys/utsname.h>
#import <sys/sysctl.h>

// ════════════════════════════════════════════════════════════
// MARK: - Constants
// ════════════════════════════════════════════════════════════

static NSString *const kModelCachePrefix    = @"eas_device_model_";
static NSString *const kModelCacheTimestamp  = @"eas_device_model_cache_ts";
static NSString *const kKeychainUUIDKey     = @"com.enterprise.appstore.device.uuid";
static NSTimeInterval  const kModelCacheTTL = 30 * 24 * 60 * 60; // 30 days

// ════════════════════════════════════════════════════════════
// MARK: - Interface
// ════════════════════════════════════════════════════════════

@interface EnterpriseAppStore : CDVPlugin
- (void)downloadAndInstall:(CDVInvokedUrlCommand *)command;
- (void)getAppVersion:(CDVInvokedUrlCommand *)command;
- (void)isAppInstalled:(CDVInvokedUrlCommand *)command;
- (void)checkAppShield:(CDVInvokedUrlCommand *)command;
- (void)getDeviceInfo:(CDVInvokedUrlCommand *)command;
- (void)checkUpdate:(CDVInvokedUrlCommand *)command;
- (void)openApp:(CDVInvokedUrlCommand *)command;
@end

// ════════════════════════════════════════════════════════════
// MARK: - Implementation
// ════════════════════════════════════════════════════════════

@implementation EnterpriseAppStore

#pragma mark - Download and Install (OTA)

/**
 * Triggers iOS OTA installation via itms-services:// protocol.
 * Parameter: manifestUrl — HTTPS URL pointing to manifest.plist
 */
- (void)downloadAndInstall:(CDVInvokedUrlCommand *)command {

    NSString *manifestUrl = [command.arguments objectAtIndex:0];

    // Validate manifest URL
    if (!manifestUrl || [manifestUrl length] == 0) {
        [self sendStatusWithCallbackId:command.callbackId
                                status:@"ERROR"
                              progress:0
                               message:@"INVALID_MANIFEST_URL"
                              filePath:@""
                          keepCallback:NO];
        return;
    }

    NSLog(@"[EnterpriseAppStore] manifestUrl = %@", manifestUrl);

    // Percent-encode the manifest URL
    NSString *encodedManifestUrl = [manifestUrl stringByAddingPercentEncodingWithAllowedCharacters:
                                    [NSCharacterSet URLQueryAllowedCharacterSet]];
    if (!encodedManifestUrl) {
        [self sendStatusWithCallbackId:command.callbackId
                                status:@"ERROR"
                              progress:0
                               message:@"URL_ENCODING_FAILED"
                              filePath:@""
                          keepCallback:NO];
        return;
    }

    // Build itms-services URL
    NSString *itsUrlString = [NSString stringWithFormat:
        @"itms-services://?action=download-manifest&url=%@", encodedManifestUrl];

    NSLog(@"[EnterpriseAppStore] itsUrlString = %@", itsUrlString);

    NSURL *itsUrl = [NSURL URLWithString:itsUrlString];
    if (!itsUrl) {
        [self sendStatusWithCallbackId:command.callbackId
                                status:@"ERROR"
                              progress:0
                               message:[NSString stringWithFormat:
                                        @"INVALID_URL_FORMAT: %@", itsUrlString]
                              filePath:@""
                          keepCallback:NO];
        return;
    }

    dispatch_async(dispatch_get_main_queue(), ^{
        // Notify JS layer that installation started
        [self sendStatusWithCallbackId:command.callbackId
                                status:@"INSTALLING"
                              progress:0
                               message:@"Opening iOS installation dialog..."
                              filePath:[itsUrl absoluteString]
                          keepCallback:YES];

        // Check itms-services support
        if (![[UIApplication sharedApplication] canOpenURL:itsUrl]) {
            [self sendStatusWithCallbackId:command.callbackId
                                    status:@"ERROR"
                                  progress:0
                                   message:@"ITMS_NOT_SUPPORTED: Device cannot handle itms-services"
                                  filePath:[itsUrl absoluteString]
                              keepCallback:NO];
            return;
        }

        // Open itms-services URL
        [[UIApplication sharedApplication] openURL:itsUrl
                                           options:@{}
                                 completionHandler:^(BOOL success) {
            NSLog(@"[EnterpriseAppStore] open itms-services result = %d", success);
            if (success) {
                [self sendStatusWithCallbackId:command.callbackId
                                        status:@"INSTALL_PROMPT"
                                      progress:100
                                       message:@"iOS installation dialog opened."
                                      filePath:[itsUrl absoluteString]
                                  keepCallback:YES];
                [self sendStatusWithCallbackId:command.callbackId
                                        status:@"SUCCESS"
                                      progress:100
                                       message:@"iOS OTA installation initiated."
                                      filePath:[itsUrl absoluteString]
                                  keepCallback:NO];
            } else {
                [self sendStatusWithCallbackId:command.callbackId
                                        status:@"ERROR"
                                      progress:0
                                       message:@"INSTALL_FAILED: Cannot open itms-services URL"
                                      filePath:@""
                                  keepCallback:NO];
            }
        }];
    });
}

#pragma mark - Get App Version

/**
 * Returns current app version info.
 * Returns: { packageName, versionName, versionCode }
 */
- (void)getAppVersion:(CDVInvokedUrlCommand *)command {

    NSString *versionName = [[NSBundle mainBundle] objectForInfoDictionaryKey:@"CFBundleShortVersionString"] ?: @"Unknown";
    NSString *versionCode = [[NSBundle mainBundle] objectForInfoDictionaryKey:@"CFBundleVersion"] ?: @"0";
    NSString *packageName = [[NSBundle mainBundle] bundleIdentifier] ?: @"Unknown";

    NSDictionary *result = @{
        @"packageName": packageName,
        @"versionName": versionName,
        @"versionCode": @([versionCode integerValue])
    };

    CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK
                                                  messageAsDictionary:result];
    [pluginResult setKeepCallbackAsBool:NO];
    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

#pragma mark - Is App Installed

/**
 * Check if an app is installed via URL scheme.
 * iOS does not allow direct inspection of other apps.
 * The target scheme must be declared in LSApplicationQueriesSchemes.
 * Returns: { isInstalled, packageName, versionName, versionCode }
 */
- (void)isAppInstalled:(CDVInvokedUrlCommand *)command {

    NSString *urlScheme = [command.arguments objectAtIndex:0];

    if (!urlScheme || [urlScheme length] == 0) {
        NSDictionary *result = @{
            @"isInstalled": @NO,
            @"packageName": urlScheme ?: @"",
            @"versionName": @"",
            @"versionCode": @0
        };
        CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK
                                                      messageAsDictionary:result];
        [pluginResult setKeepCallbackAsBool:NO];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
        return;
    }

    NSURL *url = [NSURL URLWithString:[NSString stringWithFormat:@"%@://", urlScheme]];
    if (!url) {
        NSDictionary *result = @{
            @"isInstalled": @NO,
            @"packageName": urlScheme,
            @"versionName": @"",
            @"versionCode": @0
        };
        CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK
                                                      messageAsDictionary:result];
        [pluginResult setKeepCallbackAsBool:NO];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
        return;
    }

    BOOL isInstalled = [[UIApplication sharedApplication] canOpenURL:url];

    NSDictionary *result = @{
        @"isInstalled": @(isInstalled),
        @"packageName": urlScheme,
        @"versionName": @"",    // iOS cannot read version of other apps
        @"versionCode": @0
    };

    CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK
                                                  messageAsDictionary:result];
    [pluginResult setKeepCallbackAsBool:NO];
    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

#pragma mark - Check App Shield

/**
 * Performs device security checks.
 * Returns: { isJailbroken, isDeveloperMode, isSimulator, isInstalledFromStore, isSafe }
 */
- (void)checkAppShield:(CDVInvokedUrlCommand *)command {

    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{

        BOOL isJailbroken    = [self checkIsJailbroken];
        BOOL isSimulator     = [self checkIsSimulator];
        BOOL isDeveloperMode = [self checkIsDeveloperMode];
        BOOL isFromStore     = [self checkIsInstalledFromStore];
        BOOL isSafe          = !isJailbroken && !isSimulator;

        NSDictionary *result = @{
            @"isJailbroken":         @(isJailbroken),
            @"isDeveloperMode":      @(isDeveloperMode),
            @"isSimulator":          @(isSimulator),
            @"isInstalledFromStore": @(isFromStore),
            @"isSafe":               @(isSafe)
        };

        NSLog(@"[EnterpriseAppStore] checkAppShield: jailbroken=%d devMode=%d simulator=%d fromStore=%d safe=%d",
              isJailbroken, isDeveloperMode, isSimulator, isFromStore, isSafe);

        dispatch_async(dispatch_get_main_queue(), ^{
            CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK
                                                          messageAsDictionary:result];
            [pluginResult setKeepCallbackAsBool:NO];
            [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
        });
    });
}

#pragma mark - Jailbreak Detection

/**
 * Multi-method jailbreak detection for reliability.
 * Checks: suspicious files, sandbox escape, Cydia URL scheme, dynamic libraries
 */
- (BOOL)checkIsJailbroken {
#if TARGET_OS_SIMULATOR
    return NO;
#else
    // Check 1: Known jailbreak file paths
    NSArray *suspiciousPaths = @[
        @"/Applications/Cydia.app",
        @"/Library/MobileSubstrate/MobileSubstrate.dylib",
        @"/bin/bash",
        @"/usr/sbin/sshd",
        @"/etc/apt",
        @"/private/var/lib/apt/",
        @"/usr/bin/ssh",
        @"/private/var/stash",
        @"/private/var/mobile/Library/SBSettings/Themes",
        @"/Library/MobileSubstrate/DynamicLibraries/Veency.plist",
        @"/Library/MobileSubstrate/DynamicLibraries/LiveClock.plist",
        @"/System/Library/LaunchDaemons/com.ikey.bbot.plist",
        @"/System/Library/LaunchDaemons/com.saurik.Cydia.Startup.plist"
    ];

    for (NSString *path in suspiciousPaths) {
        if ([[NSFileManager defaultManager] fileExistsAtPath:path]) {
            NSLog(@"[EnterpriseAppStore] Jailbreak detected: %@", path);
            return YES;
        }
    }

    // Check 2: Write outside sandbox
    NSString *testPath = [NSString stringWithFormat:@"/private/jailbreak_test_%@.txt",
                          [[NSUUID UUID] UUIDString]];
    NSError *error = nil;
    [@"test" writeToFile:testPath atomically:YES encoding:NSUTF8StringEncoding error:&error];
    if (!error) {
        [[NSFileManager defaultManager] removeItemAtPath:testPath error:nil];
        NSLog(@"[EnterpriseAppStore] Jailbreak detected: write outside sandbox succeeded");
        return YES;
    }

    // Check 3: Cydia URL scheme
    NSURL *cydiaUrl = [NSURL URLWithString:@"cydia://package/com.example.package"];
    if (cydiaUrl && [[UIApplication sharedApplication] canOpenURL:cydiaUrl]) {
        NSLog(@"[EnterpriseAppStore] Jailbreak detected: Cydia URL scheme available");
        return YES;
    }

    // Check 4: Suspicious dynamic libraries
    NSArray *suspiciousLibs = @[@"SubstrateLoader", @"cycript",
                                @"MobileSubstrate", @"SSLKillSwitch"];
    for (NSString *lib in suspiciousLibs) {
        if ([NSBundle bundleWithIdentifier:lib] != nil) {
            NSLog(@"[EnterpriseAppStore] Jailbreak detected: library %@ found", lib);
            return YES;
        }
    }

    return NO;
#endif
}

#pragma mark - Simulator Detection

- (BOOL)checkIsSimulator {
#if TARGET_OS_SIMULATOR
    return YES;
#else
    // Secondary check via environment variable
    return [[[NSProcessInfo processInfo] environment] objectForKey:@"SIMULATOR_DEVICE_NAME"] != nil;
#endif
}

#pragma mark - Developer Mode Detection

/**
 * iOS 16+: returns YES only for DEBUG builds.
 * iOS < 16: checks for embedded provisioning profile.
 */
- (BOOL)checkIsDeveloperMode {
#if DEBUG
    return YES;
#else
    // Presence of provisioning profile indicates dev/enterprise build
    NSString *provisionPath = [[NSBundle mainBundle] pathForResource:@"embedded"
                                                              ofType:@"mobileprovision"];
    return (provisionPath != nil);
#endif
}

#pragma mark - App Store Installation Check

/**
 * Returns YES if installed from App Store.
 * Enterprise/dev builds include provisioning profile.
 * App Store builds include StoreKit receipt.
 */
- (BOOL)checkIsInstalledFromStore {
    // Provisioning profile = not from App Store
    NSString *provisionPath = [[NSBundle mainBundle] pathForResource:@"embedded"
                                                              ofType:@"mobileprovision"];
    if (provisionPath != nil) {
        return NO;
    }

    // Check for App Store receipt
    NSURL *receiptURL = [[NSBundle mainBundle] appStoreReceiptURL];
    if (receiptURL && [[NSFileManager defaultManager] fileExistsAtPath:[receiptURL path]]) {
        return YES;
    }

    return NO;
}

#pragma mark - ⭐ Get Device Info (Auto Model Name + Persistent UUID)

/**
 * Returns comprehensive device information.
 *
 * Model Name Resolution — 3-Layer Automatic Strategy:
 *   Layer 1: Local Cache (NSUserDefaults) — fastest, offline
 *   Layer 2: IPSW.me Public API — auto-updated, no maintenance
 *            https://api.ipsw.me/v4/device/{identifier}
 *   Layer 3: Fallback auto-parse — "iPhone17,1" → "iPhone (17,1)"
 *
 * UUID Strategy:
 *   Keychain-backed for persistence across app reinstalls.
 */
- (void)getDeviceInfo:(CDVInvokedUrlCommand *)command {

    // Get raw machine identifier (e.g. "iPhone17,1")
    NSString *machineId = [self getMachineIdentifier];

    // Resolve model name asynchronously (may call IPSW API)
    [self resolveModelName:machineId completion:^(NSString *modelName) {

        // All UIKit calls must be on main thread
        dispatch_async(dispatch_get_main_queue(), ^{

            // ── Enable battery monitoring ──
            [UIDevice currentDevice].batteryMonitoringEnabled = YES;

            // ── Hardware info ──
            NSString *deviceName = [UIDevice currentDevice].name;
            NSString *sysName    = [UIDevice currentDevice].systemName;
            NSString *sysVersion = [UIDevice currentDevice].systemVersion;

            // ── App bundle info ──
            NSString *appVersion  = [[NSBundle mainBundle] objectForInfoDictionaryKey:@"CFBundleShortVersionString"] ?: @"Unknown";
            NSString *appBuild    = [[NSBundle mainBundle] objectForInfoDictionaryKey:@"CFBundleVersion"] ?: @"0";
            NSString *packageName = [[NSBundle mainBundle] bundleIdentifier] ?: @"Unknown";

            // ── Screen dimensions ──
            CGFloat screenWidth  = [UIScreen mainScreen].bounds.size.width;
            CGFloat screenHeight = [UIScreen mainScreen].bounds.size.height;
            CGFloat screenScale  = [UIScreen mainScreen].scale;

            // ── Locale and timezone ──
            NSString *locale   = [[NSLocale currentLocale] localeIdentifier];
            NSString *timezone = [[NSTimeZone localTimeZone] name];

            // ── Battery status ──
            float batteryLevel = [UIDevice currentDevice].batteryLevel;
            int batteryPct     = (batteryLevel >= 0) ? (int)(batteryLevel * 100) : -1;
            UIDeviceBatteryState batteryState = [UIDevice currentDevice].batteryState;
            BOOL isCharging    = (batteryState == UIDeviceBatteryStateCharging ||
                                  batteryState == UIDeviceBatteryStateFull);

            // ── Storage (in MB) ──
            long long availableStorageMB = 0;
            long long totalStorageMB     = 0;
            NSError *fsError = nil;
            NSDictionary *fsAttrs = [[NSFileManager defaultManager]
                                     attributesOfFileSystemForPath:NSHomeDirectory()
                                     error:&fsError];
            if (fsAttrs && !fsError) {
                NSNumber *freeSize  = fsAttrs[NSFileSystemFreeSize];
                NSNumber *totalSize = fsAttrs[NSFileSystemSize];
                if (freeSize)  availableStorageMB = [freeSize longLongValue] / (1024 * 1024);
                if (totalSize) totalStorageMB     = [totalSize longLongValue] / (1024 * 1024);
            }

            // ── Disable battery monitoring ──
            [UIDevice currentDevice].batteryMonitoringEnabled = NO;

            // ── Get persistent UUID ──
            NSString *uuid = [self getStableUUID];

            // ── Build result ──
            NSDictionary *result = @{
                @"uuid":               uuid,
                @"machineIdentifier":  machineId,
                @"manufacturer":       @"Apple",
                @"brand":              @"Apple",
                @"model":              modelName,
                @"device":             deviceName,
                @"product":            modelName,
                @"systemName":         sysName,
                @"androidVersion":     sysVersion,    // Shared key for JS compatibility
                @"systemVersion":      sysVersion,
                @"sdkVersion":         @0,
                @"appVersion":         appVersion,
                @"appBuild":           appBuild,
                @"appVersionCode":     @([appBuild integerValue]),
                @"packageName":        packageName,
                @"screenWidth":        @((int)screenWidth),
                @"screenHeight":       @((int)screenHeight),
                @"screenDensity":      @((int)(screenScale * 160)),
                @"locale":             locale,
                @"timezone":           timezone,
                @"batteryLevel":       @(batteryPct),
                @"isCharging":         @(isCharging),
                @"availableStorageMB": @(availableStorageMB),
                @"totalStorageMB":     @(totalStorageMB)
            };

            NSLog(@"[EnterpriseAppStore] getDeviceInfo: %@ (%@) iOS %@ UUID=%@",
                  modelName, machineId, sysVersion, uuid);

            CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK
                                                          messageAsDictionary:result];
            [pluginResult setKeepCallbackAsBool:NO];
            [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
        });
    }];
}

#pragma mark - Machine Identifier

/**
 * Returns raw hardware identifier using sysctlbyname("hw.machine").
 * e.g. "iPhone17,1", "iPad14,8"
 * Handles simulator by reading environment variable.
 */
- (NSString *)getMachineIdentifier {
    size_t size;
    sysctlbyname("hw.machine", NULL, &size, NULL, 0);
    char *machine = malloc(size);
    sysctlbyname("hw.machine", machine, &size, NULL, 0);
    NSString *identifier = [NSString stringWithCString:machine encoding:NSUTF8StringEncoding];
    free(machine);
        // On simulator, hw.machine returns "x86_64" or "arm64"
    // Read simulated device model from environment instead
    if ([identifier isEqualToString:@"x86_64"] || [identifier isEqualToString:@"arm64"]) {
        NSString *simModel = [[[NSProcessInfo processInfo] environment]
                              objectForKey:@"SIMULATOR_MODEL_IDENTIFIER"];
        if (simModel) {
            return simModel;
        }
    }

    return identifier;
}

#pragma mark - Stable UUID (Keychain-backed)

/**
 * Returns a persistent UUID stored in the Keychain.
 * Unlike identifierForVendor, this survives app reinstalls.
 * Falls back to identifierForVendor or random UUID if Keychain is empty.
 */
- (NSString *)getStableUUID {

    // Step 1: Try reading existing UUID from Keychain
    NSString *existingUUID = [self keychainRead:kKeychainUUIDKey];
    if (existingUUID && [existingUUID length] > 0) {
        return existingUUID;
    }

    // Step 2: Generate new UUID
    NSString *newUUID = [[[UIDevice currentDevice] identifierForVendor] UUIDString];
    if (!newUUID || [newUUID length] == 0) {
        newUUID = [[NSUUID UUID] UUIDString];
    }

    // Step 3: Save to Keychain for persistence
    [self keychainSave:kKeychainUUIDKey value:newUUID];

    NSLog(@"[EnterpriseAppStore] UUID generated and saved to Keychain: %@", newUUID);
    return newUUID;
}

#pragma mark - ⭐ Model Name Resolution (3-Layer Automatic)

/**
 * Resolves machine identifier to marketing name.
 *
 * Layer 1: Local Cache (NSUserDefaults) — fastest, offline
 * Layer 2: IPSW.me API — free, public, auto-updated within hours
 *          of new Apple device announcements. No API key needed.
 *          https://api.ipsw.me/v4/device/{identifier}
 * Layer 3: Fallback auto-parse — never fails
 *          "iPhone17,1" → "iPhone (17,1)"
 *
 * completion block is called with the resolved model name.
 */
- (void)resolveModelName:(NSString *)identifier
              completion:(void (^)(NSString *modelName))completion {

    // ── Layer 1: Check local cache ──
    if ([self isCacheValid]) {
        NSString *cached = [self getModelFromCache:identifier];
        if (cached && [cached length] > 0) {
            NSLog(@"[EnterpriseAppStore] Model CACHE hit: %@", cached);
            completion(cached);
            return;
        }
    }

    // ── Layer 2: Fetch from IPSW.me API ──
    [self fetchModelFromIPSW:identifier completion:^(NSString *apiName) {

        if (apiName && [apiName length] > 0) {
            NSLog(@"[EnterpriseAppStore] Model IPSW API: %@", apiName);
            [self saveModelToCache:identifier modelName:apiName];
            completion(apiName);
            return;
        }

        // ── Layer 3: Fallback auto-parse ──
        NSString *fallback = [self fallbackModelName:identifier];
        NSLog(@"[EnterpriseAppStore] Model FALLBACK: %@", fallback);
        // Cache fallback to avoid repeated API retries
        [self saveModelToCache:identifier modelName:fallback];
        completion(fallback);
    }];
}

#pragma mark - Layer 1: Local Cache Helpers

/// Read cached model name for a machine identifier
- (NSString *)getModelFromCache:(NSString *)identifier {
    NSString *key = [kModelCachePrefix stringByAppendingString:identifier];
    return [[NSUserDefaults standardUserDefaults] stringForKey:key];
}

/// Save model name to local cache with timestamp
- (void)saveModelToCache:(NSString *)identifier modelName:(NSString *)modelName {
    NSString *key = [kModelCachePrefix stringByAppendingString:identifier];
    [[NSUserDefaults standardUserDefaults] setObject:modelName forKey:key];
    [[NSUserDefaults standardUserDefaults] setDouble:[[NSDate date] timeIntervalSince1970]
                                              forKey:kModelCacheTimestamp];
}

/// Check whether local cache is still within TTL (30 days)
- (BOOL)isCacheValid {
    double timestamp = [[NSUserDefaults standardUserDefaults] doubleForKey:kModelCacheTimestamp];
    if (timestamp <= 0) return NO;
    return ([[NSDate date] timeIntervalSince1970] - timestamp) < kModelCacheTTL;
}

#pragma mark - Layer 2: IPSW.me API

/**
 * Fetch marketing name from IPSW.me API.
 *
 * Example request:
 *   GET https://api.ipsw.me/v4/device/iPhone17,1
 *
 * Example response (trimmed):
 *   {
 *     "name": "iPhone 16 Pro",
 *     "identifier": "iPhone17,1",
 *     "firmwares": [ ... ]
 *   }
 *
 * We only extract the "name" field.
 * Timeout is 5 seconds to avoid blocking too long.
 */
- (void)fetchModelFromIPSW:(NSString *)identifier
                completion:(void (^)(NSString *name))completion {

    // URL-encode identifier ("iPhone17,1" → "iPhone17%2C1")
    NSString *encoded = [identifier stringByAddingPercentEncodingWithAllowedCharacters:
                         [NSCharacterSet URLPathAllowedCharacterSet]];
    if (!encoded) {
        NSLog(@"[EnterpriseAppStore] IPSW: Failed to encode identifier: %@", identifier);
        completion(nil);
        return;
    }

    NSString *urlString = [NSString stringWithFormat:@"https://api.ipsw.me/v4/device/%@", encoded];
    NSURL *url = [NSURL URLWithString:urlString];
    if (!url) {
        NSLog(@"[EnterpriseAppStore] IPSW: Invalid URL: %@", urlString);
        completion(nil);
        return;
    }

    NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:url];
    [request setHTTPMethod:@"GET"];
    [request setTimeoutInterval:5.0];
    [request setCachePolicy:NSURLRequestReloadIgnoringLocalCacheData];
    [request setValue:@"application/json" forHTTPHeaderField:@"Accept"];

    NSLog(@"[EnterpriseAppStore] IPSW: Fetching model from %@", urlString);

    NSURLSessionDataTask *task = [[NSURLSession sharedSession]
        dataTaskWithRequest:request
        completionHandler:^(NSData *data, NSURLResponse *response, NSError *error) {

        // Check network error
        if (error) {
            NSLog(@"[EnterpriseAppStore] IPSW: Network error: %@", error.localizedDescription);
            completion(nil);
            return;
        }

        // Validate HTTP status
        NSHTTPURLResponse *httpResponse = (NSHTTPURLResponse *)response;
        if (httpResponse.statusCode != 200) {
            NSLog(@"[EnterpriseAppStore] IPSW: HTTP %ld for %@",
                  (long)httpResponse.statusCode, identifier);
            completion(nil);
            return;
        }

        // Validate data
        if (!data) {
            NSLog(@"[EnterpriseAppStore] IPSW: Empty response data");
            completion(nil);
            return;
        }

                // Parse JSON and extract "name" field
        NSError *jsonError = nil;
        NSDictionary *json = [NSJSONSerialization JSONObjectWithData:data
                                                             options:0
                                                               error:&jsonError];
        if (jsonError) {
            NSLog(@"[EnterpriseAppStore] IPSW: JSON parse error: %@", jsonError.localizedDescription);
            completion(nil);
            return;
        }

        NSString *name = json[@"name"];
        if (name && [name isKindOfClass:[NSString class]] && [name length] > 0) {
            NSLog(@"[EnterpriseAppStore] IPSW: Resolved %@ → %@", identifier, name);
            completion(name);
        } else {
            NSLog(@"[EnterpriseAppStore] IPSW: 'name' field not found in response");
            completion(nil);
        }
    }];

    [task resume];
}

#pragma mark - Layer 3: Fallback Auto-Parse

/**
 * Parses raw machine identifier into a human-readable format.
 * Never returns nil or empty string.
 *
 * Examples:
 *   "iPhone17,1"        → "iPhone (17,1)"
 *   "iPad14,8"          → "iPad (14,8)"
 *   "iPod9,1"           → "iPod touch (9,1)"
 *   "Watch7,1"          → "Apple Watch (7,1)"
 *   "AudioAccessory6,1" → "HomePod (6,1)"
 *   "AppleTV14,1"       → "Apple TV (14,1)"
 *   "arm64"             → "arm64" (simulator edge case)
 */
- (NSString *)fallbackModelName:(NSString *)identifier {

    if (!identifier || [identifier length] == 0) {
        return @"Unknown";
    }

    // Split identifier into letter prefix and numeric suffix
    // e.g. "iPhone17,1" → prefix="iPhone", suffix="17,1"
    NSMutableString *prefix = [NSMutableString string];
    NSMutableString *suffix = [NSMutableString string];
    BOOL hitDigit = NO;

    for (NSUInteger i = 0; i < [identifier length]; i++) {
        unichar c = [identifier characterAtIndex:i];
        if ((c >= '0' && c <= '9') || c == ',' || hitDigit) {
            hitDigit = YES;
            [suffix appendFormat:@"%C", c];
        } else {
            [prefix appendFormat:@"%C", c];
        }
    }

    // Map common prefixes to user-friendly device type names
    NSString *deviceType;
    NSString *lowerPrefix = [prefix lowercaseString];

    if ([lowerPrefix isEqualToString:@"iphone"]) {
        deviceType = @"iPhone";
    } else if ([lowerPrefix isEqualToString:@"ipad"]) {
        deviceType = @"iPad";
    } else if ([lowerPrefix isEqualToString:@"ipod"]) {
        deviceType = @"iPod touch";
    } else if ([lowerPrefix isEqualToString:@"watch"]) {
        deviceType = @"Apple Watch";
    } else if ([lowerPrefix isEqualToString:@"appletv"]) {
        deviceType = @"Apple TV";
    } else if ([lowerPrefix isEqualToString:@"audioaccessory"]) {
        deviceType = @"HomePod";
    } else if ([lowerPrefix isEqualToString:@"macbookair"]) {
        deviceType = @"MacBook Air";
    } else if ([lowerPrefix isEqualToString:@"macbookpro"]) {
        deviceType = @"MacBook Pro";
    } else if ([lowerPrefix isEqualToString:@"mac"]) {
        deviceType = @"Mac";
    } else {
        deviceType = prefix;
    }

    // If no numeric suffix, return just device type
    if ([suffix length] == 0) {
        return ([deviceType length] > 0) ? deviceType : identifier;
    }

    return [NSString stringWithFormat:@"%@ (%@)", deviceType, suffix];
}

#pragma mark - Check Update

/**
 * Compares installed app version against latest version.
 * Returns: { needsUpdate, installedVersion, latestVersion, packageName, isInstalled }
 */
- (void)checkUpdate:(CDVInvokedUrlCommand *)command {

    NSString *latestVersion = nil;
    if ([command.arguments count] > 1) {
        latestVersion = [command.arguments objectAtIndex:1];
    }

    if (!latestVersion || [latestVersion length] == 0) {
        NSDictionary *result = @{
            @"needsUpdate":      @NO,
            @"installedVersion": @"",
            @"latestVersion":    @"",
            @"packageName":      [[NSBundle mainBundle] bundleIdentifier] ?: @"",
            @"isInstalled":      @YES
        };
        CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK
                                                      messageAsDictionary:result];
        [pluginResult setKeepCallbackAsBool:NO];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
        return;
    }

    NSString *installedVersion = [[NSBundle mainBundle]
                                  objectForInfoDictionaryKey:@"CFBundleShortVersionString"] ?: @"0.0.0";
    NSString *packageName = [[NSBundle mainBundle] bundleIdentifier] ?: @"";
    BOOL needsUpdate = [self compareVersion:installedVersion isLessThan:latestVersion];

    NSDictionary *result = @{
        @"needsUpdate":      @(needsUpdate),
        @"installedVersion": installedVersion,
        @"latestVersion":    latestVersion,
        @"packageName":      packageName,
        @"isInstalled":      @YES
    };

    NSLog(@"[EnterpriseAppStore] checkUpdate: installed=%@ latest=%@ needsUpdate=%d",
          installedVersion, latestVersion, needsUpdate);

    CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK
                                                  messageAsDictionary:result];
    [pluginResult setKeepCallbackAsBool:NO];
    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

#pragma mark - Open App

/**
 * Launch another app by URL scheme.
 * Uses UIApplication.open() directly — no LSApplicationQueriesSchemes needed.
 *
 * Parameter: urlScheme — e.g. "myapp" or "myapp://path?key=value"
 */
- (void)openApp:(CDVInvokedUrlCommand *)command {

    NSString *urlScheme = [command.arguments objectAtIndex:0];

    // Validate URL scheme
    if (!urlScheme ||
        [[urlScheme stringByTrimmingCharactersInSet:
          [NSCharacterSet whitespaceAndNewlineCharacterSet]] length] == 0) {

        NSLog(@"[EnterpriseAppStore] openApp: INVALID_SCHEME — empty or nil");

        NSDictionary *result = @{
            @"success":     @NO,
            @"scheme":      @"",
            @"packageName": @"",
            @"message":     @"URL scheme is required",
            @"errorCode":   @"INVALID_SCHEME"
        };
        CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR
                                                      messageAsDictionary:result];
        [pluginResult setKeepCallbackAsBool:NO];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
                return;
    }

    NSString *scheme = [urlScheme stringByTrimmingCharactersInSet:
                        [NSCharacterSet whitespaceAndNewlineCharacterSet]];

    // Build URL: "myapp" → "myapp://", "myapp://path" → as-is
    NSString *urlString;
    if ([scheme containsString:@"://"]) {
        urlString = scheme;
    } else {
        urlString = [NSString stringWithFormat:@"%@://", scheme];
    }

    NSURL *appUrl = [NSURL URLWithString:urlString];
    if (!appUrl) {
        NSLog(@"[EnterpriseAppStore] openApp: INVALID_SCHEME — cannot create URL from '%@'", urlString);

        NSDictionary *result = @{
            @"success":     @NO,
            @"scheme":      scheme,
            @"packageName": scheme,
            @"message":     [NSString stringWithFormat:@"Cannot create URL from scheme: %@", scheme],
            @"errorCode":   @"INVALID_SCHEME"
        };
        CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR
                                                      messageAsDictionary:result];
        [pluginResult setKeepCallbackAsBool:NO];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
        return;
    }

    // Open directly — no canOpenURL() check needed, no Info.plist config needed
    dispatch_async(dispatch_get_main_queue(), ^{
        NSLog(@"[EnterpriseAppStore] openApp: Opening '%@' directly...", urlString);

        [[UIApplication sharedApplication] openURL:appUrl
                                           options:@{}
                                 completionHandler:^(BOOL success) {
            NSLog(@"[EnterpriseAppStore] openApp: result = %d", success);

            if (success) {
                NSDictionary *result = @{
                    @"success":     @YES,
                    @"scheme":      scheme,
                    @"packageName": scheme,
                    @"message":     @"App launched successfully"
                };
                CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK
                                                              messageAsDictionary:result];
                [pluginResult setKeepCallbackAsBool:NO];
                [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
            } else {
                NSLog(@"[EnterpriseAppStore] openApp: APP_NOT_INSTALLED for '%@'", urlString);

                NSDictionary *result = @{
                    @"success":     @NO,
                    @"scheme":      scheme,
                    @"packageName": scheme,
                    @"message":     [NSString stringWithFormat:
                                     @"App not installed or scheme '%@' not registered. URL: %@",
                                     scheme, urlString],
                    @"errorCode":   @"APP_NOT_INSTALLED"
                };
                CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR
                                                              messageAsDictionary:result];
                [pluginResult setKeepCallbackAsBool:NO];
                [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
            }
        }];
    });
}


- (void)setBadgeNumber:(CDVInvokedUrlCommand *)command {

    dispatch_async(dispatch_get_main_queue(), ^{

        NSInteger badge = 0;
        if (command.arguments.count > 0) {
            badge = [[command.arguments objectAtIndex:0] integerValue];
        }

        // Set badge number
        [UIApplication sharedApplication].applicationIconBadgeNumber = badge;

        // Optional: update notification badge as well
        NSDictionary *result = @{
            @"badge": @(badge),
            @"platform": @"iOS"
        };

        CDVPluginResult *pluginResult =
            [CDVPluginResult resultWithStatus:CDVCommandStatus_OK
                           messageAsDictionary:result];

        [self.commandDelegate sendPluginResult:pluginResult
                                    callbackId:command.callbackId];
    });
}
#pragma mark - Semantic Version Comparison

/**
 * Returns YES if v1 < v2 (update needed).
 * Supports: "1.2.3", "v2.0", "10.0.1"
 */
- (BOOL)compareVersion:(NSString *)v1 isLessThan:(NSString *)v2 {

    // Strip non-numeric, non-dot characters (e.g. "v1.2.3" → "1.2.3")
    NSRegularExpression *regex = [NSRegularExpression regularExpressionWithPattern:@"[^0-9.]"
                                                                          options:0
                                                                            error:nil];
    NSString *clean1 = [regex stringByReplacingMatchesInString:v1
                                                       options:0
                                                         range:NSMakeRange(0, [v1 length])
                                                  withTemplate:@""];
    NSString *clean2 = [regex stringByReplacingMatchesInString:v2
                                                       options:0
                                                         range:NSMakeRange(0, [v2 length])
                                                  withTemplate:@""];

    NSArray *parts1 = [clean1 componentsSeparatedByString:@"."];
    NSArray *parts2 = [clean2 componentsSeparatedByString:@"."];
    NSUInteger maxLen = MAX([parts1 count], [parts2 count]);

    for (NSUInteger i = 0; i < maxLen; i++) {
        int p1 = (i < [parts1 count]) ? [parts1[i] intValue] : 0;
        int p2 = (i < [parts2 count]) ? [parts2[i] intValue] : 0;
        if (p1 < p2) return YES;   // v1 is older — update needed
        if (p1 > p2) return NO;    // v1 is newer — no update
    }
    return NO; // Equal — no update needed
}

#pragma mark - Send Status Helper

/**
 * Sends unified status to JavaScript layer.
 * Format: { status, progress, message, filePath }
 * Matches Android callback format for cross-platform consistency.
 */
- (void)sendStatusWithCallbackId:(NSString *)callbackId
                          status:(NSString *)status
                        progress:(int)progress
                         message:(NSString *)message
                        filePath:(NSString *)filePath
                    keepCallback:(BOOL)keepCallback {

    NSDictionary *data = @{
        @"status":   status,
        @"progress": @(progress),
        @"message":  message,
        @"filePath": filePath
    };

    CDVPluginResult *result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK
                                            messageAsDictionary:data];
    [result setKeepCallbackAsBool:keepCallback];
    [self.commandDelegate sendPluginResult:result callbackId:callbackId];

    NSLog(@"[EnterpriseAppStore] sendStatus: %@ %d%%%@%@",
          status, progress,
          ([filePath length] > 0) ? [NSString stringWithFormat:@" path=%@", filePath] : @"",
          keepCallback ? @" keep=YES" : @"");
}

#pragma mark - ⭐ Keychain Helpers (Persistent UUID Storage)

/**
 * Save string value to iOS Keychain.
 * Data persists across app reinstalls.
 * Uses kSecClassGenericPassword with:
 *   - kSecAttrAccount:    key name
 *   - kSecAttrService:    app bundle identifier (scoping)
 *   - kSecAttrAccessible: kSecAttrAccessibleAfterFirstUnlock
 */
- (BOOL)keychainSave:(NSString *)key value:(NSString *)value {

    NSData *data = [value dataUsingEncoding:NSUTF8StringEncoding];
    if (!data) {
        NSLog(@"[KeychainHelper] save: failed to encode value to UTF-8");
        return NO;
    }

    NSString *service = [[NSBundle mainBundle] bundleIdentifier] ?: @"com.enterprise.appstore";

    NSDictionary *query = @{
        (__bridge id)kSecClass:       (__bridge id)kSecClassGenericPassword,
        (__bridge id)kSecAttrService: service,
        (__bridge id)kSecAttrAccount: key,
        (__bridge id)kSecAttrAccessible: (__bridge id)kSecAttrAccessibleAfterFirstUnlock
    };
        // Delete existing item first to prevent errSecDuplicateItem (-25299)
    SecItemDelete((__bridge CFDictionaryRef)query);

    // Add new item with value data
    NSMutableDictionary *addQuery = [query mutableCopy];
    addQuery[(__bridge id)kSecValueData] = data;

    OSStatus status = SecItemAdd((__bridge CFDictionaryRef)addQuery, NULL);

    if (status == errSecSuccess) {
        NSLog(@"[KeychainHelper] save: key='%@' saved successfully", key);
        return YES;
    } else {
        NSLog(@"[KeychainHelper] save: key='%@' failed with OSStatus %d", key, (int)status);
        return NO;
    }
}

/**
 * Read string value from iOS Keychain.
 * Returns nil if key does not exist or cannot be read.
 */
- (NSString *)keychainRead:(NSString *)key {

    NSString *service = [[NSBundle mainBundle] bundleIdentifier] ?: @"com.enterprise.appstore";

    NSDictionary *query = @{
        (__bridge id)kSecClass:       (__bridge id)kSecClassGenericPassword,
        (__bridge id)kSecAttrService: service,
        (__bridge id)kSecAttrAccount: key,
        (__bridge id)kSecReturnData:  @YES,
        (__bridge id)kSecMatchLimit:  (__bridge id)kSecMatchLimitOne
    };

    CFTypeRef result = NULL;
    OSStatus status = SecItemCopyMatching((__bridge CFDictionaryRef)query, &result);

    if (status == errSecSuccess && result != NULL) {
        NSData *data = (__bridge_transfer NSData *)result;
        NSString *value = [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding];
        return value;
    }

    if (status != errSecItemNotFound) {
        // Log unexpected errors (errSecItemNotFound is normal for first run)
        NSLog(@"[KeychainHelper] read: key='%@' failed with OSStatus %d", key, (int)status);
    }

    return nil;
}

/**
 * Delete value from iOS Keychain.
 * Returns YES if deleted or did not exist.
 */
- (BOOL)keychainDelete:(NSString *)key {

    NSString *service = [[NSBundle mainBundle] bundleIdentifier] ?: @"com.enterprise.appstore";

    NSDictionary *query = @{
        (__bridge id)kSecClass:       (__bridge id)kSecClassGenericPassword,
        (__bridge id)kSecAttrService: service,
        (__bridge id)kSecAttrAccount: key
    };

    OSStatus status = SecItemDelete((__bridge CFDictionaryRef)query);

    if (status == errSecSuccess) {
        NSLog(@"[KeychainHelper] delete: key='%@' deleted successfully", key);
        return YES;
    } else if (status == errSecItemNotFound) {
        NSLog(@"[KeychainHelper] delete: key='%@' not found (already deleted)", key);
        return YES;
    } else {
        NSLog(@"[KeychainHelper] delete: key='%@' failed with OSStatus %d", key, (int)status);
        return NO;
    }
}

@end
