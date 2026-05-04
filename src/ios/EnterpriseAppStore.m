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
 *   - setBadgeNumber     : Set app icon badge (iOS 10-18+ compatible)
 ****************************************************************/

#import <Cordova/CDV.h>
#import <UIKit/UIKit.h>
#import <Security/Security.h>
#import <sys/utsname.h>
#import <sys/sysctl.h>
#import <UserNotifications/UserNotifications.h>

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
- (void)setBadgeNumber:(CDVInvokedUrlCommand *)command;
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
        @"versionName": @"",
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

- (BOOL)checkIsJailbroken {
#if TARGET_OS_SIMULATOR
    return NO;
#else
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

    NSString *testPath = [NSString stringWithFormat:@"/private/jailbreak_test_%@.txt",
                          [[NSUUID UUID] UUIDString]];
    NSError *error = nil;
    [@"test" writeToFile:testPath atomically:YES encoding:NSUTF8StringEncoding error:&error];
    if (!error) {
        [[NSFileManager defaultManager] removeItemAtPath:testPath error:nil];
        NSLog(@"[EnterpriseAppStore] Jailbreak detected: write outside sandbox succeeded");
        return YES;
    }

    NSURL *cydiaUrl = [NSURL URLWithString:@"cydia://package/com.example.package"];
    if (cydiaUrl && [[UIApplication sharedApplication] canOpenURL:cydiaUrl]) {
        NSLog(@"[EnterpriseAppStore] Jailbreak detected: Cydia URL scheme available");
        return YES;
    }

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
    return [[[NSProcessInfo processInfo] environment] objectForKey:@"SIMULATOR_DEVICE_NAME"] != nil;
#endif
}

#pragma mark - Developer Mode Detection

- (BOOL)checkIsDeveloperMode {
#if DEBUG
    return YES;
#else
    NSString *provisionPath = [[NSBundle mainBundle] pathForResource:@"embedded"
                                                              ofType:@"mobileprovision"];
    return (provisionPath != nil);
#endif
}

#pragma mark - App Store Installation Check

- (BOOL)checkIsInstalledFromStore {
    NSString *provisionPath = [[NSBundle mainBundle] pathForResource:@"embedded"
                                                              ofType:@"mobileprovision"];
    if (provisionPath != nil) {
        return NO;
    }

    NSURL *receiptURL = [[NSBundle mainBundle] appStoreReceiptURL];
    if (receiptURL && [[NSFileManager defaultManager] fileExistsAtPath:[receiptURL path]]) {
        return YES;
    }

    return NO;
}

#pragma mark - Get Device Info

- (void)getDeviceInfo:(CDVInvokedUrlCommand *)command {

    NSString *machineId = [self getMachineIdentifier];

    [self resolveModelName:machineId completion:^(NSString *modelName) {

        dispatch_async(dispatch_get_main_queue(), ^{

            [UIDevice currentDevice].batteryMonitoringEnabled = YES;

            NSString *deviceName = [UIDevice currentDevice].name;
            NSString *sysName    = [UIDevice currentDevice].systemName;
            NSString *sysVersion = [UIDevice currentDevice].systemVersion;

            NSString *appVersion  = [[NSBundle mainBundle] objectForInfoDictionaryKey:@"CFBundleShortVersionString"] ?: @"Unknown";
            NSString *appBuild    = [[NSBundle mainBundle] objectForInfoDictionaryKey:@"CFBundleVersion"] ?: @"0";
            NSString *packageName = [[NSBundle mainBundle] bundleIdentifier] ?: @"Unknown";

            CGFloat screenWidth  = [UIScreen mainScreen].bounds.size.width;
            CGFloat screenHeight = [UIScreen mainScreen].bounds.size.height;
            CGFloat screenScale  = [UIScreen mainScreen].scale;

            NSString *locale   = [[NSLocale currentLocale] localeIdentifier];
            NSString *timezone = [[NSTimeZone localTimeZone] name];

            float batteryLevel = [UIDevice currentDevice].batteryLevel;
            int batteryPct     = (batteryLevel >= 0) ? (int)(batteryLevel * 100) : -1;
            UIDeviceBatteryState batteryState = [UIDevice currentDevice].batteryState;
            BOOL isCharging    = (batteryState == UIDeviceBatteryStateCharging ||
                                  batteryState == UIDeviceBatteryStateFull);

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

            [UIDevice currentDevice].batteryMonitoringEnabled = NO;

            NSString *uuid = [self getStableUUID];

            NSDictionary *result = @{
                @"uuid":               uuid,
                @"machineIdentifier":  machineId,
                @"manufacturer":       @"Apple",
                @"brand":              @"Apple",
                @"model":              modelName,
                @"device":             deviceName,
                @"product":            modelName,
                @"systemName":         sysName,
                @"androidVersion":     sysVersion,
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

- (NSString *)getMachineIdentifier {
    size_t size;
    sysctlbyname("hw.machine", NULL, &size, NULL, 0);
    char *machine = malloc(size);
    sysctlbyname("hw.machine", machine, &size, NULL, 0);
    NSString *identifier = [NSString stringWithCString:machine encoding:NSUTF8StringEncoding];
    free(machine);

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

- (NSString *)getStableUUID {

    NSString *existingUUID = [self keychainRead:kKeychainUUIDKey];
    if (existingUUID && [existingUUID length] > 0) {
        return existingUUID;
    }

    NSString *newUUID = [[[UIDevice currentDevice] identifierForVendor] UUIDString];
    if (!newUUID || [newUUID length] == 0) {
        newUUID = [[NSUUID UUID] UUIDString];
    }

    [self keychainSave:kKeychainUUIDKey value:newUUID];

    NSLog(@"[EnterpriseAppStore] UUID generated and saved to Keychain: %@", newUUID);
    return newUUID;
}

#pragma mark - Model Name Resolution (3-Layer Automatic)

- (void)resolveModelName:(NSString *)identifier
              completion:(void (^)(NSString *modelName))completion {

    if ([self isCacheValid]) {
        NSString *cached = [self getModelFromCache:identifier];
        if (cached && [cached length] > 0) {
            NSLog(@"[EnterpriseAppStore] Model CACHE hit: %@", cached);
            completion(cached);
            return;
        }
    }

    [self fetchModelFromIPSW:identifier completion:^(NSString *apiName) {

        if (apiName && [apiName length] > 0) {
            NSLog(@"[EnterpriseAppStore] Model IPSW API: %@", apiName);
            [self saveModelToCache:identifier modelName:apiName];
            completion(apiName);
            return;
        }

        NSString *fallback = [self fallbackModelName:identifier];
        NSLog(@"[EnterpriseAppStore] Model FALLBACK: %@", fallback);
        [self saveModelToCache:identifier modelName:fallback];
        completion(fallback);
    }];
}

#pragma mark - Layer 1: Local Cache Helpers

- (NSString *)getModelFromCache:(NSString *)identifier {
    NSString *key = [kModelCachePrefix stringByAppendingString:identifier];
    return [[NSUserDefaults standardUserDefaults] stringForKey:key];
}

- (void)saveModelToCache:(NSString *)identifier modelName:(NSString *)modelName {
    NSString *key = [kModelCachePrefix stringByAppendingString:identifier];
    [[NSUserDefaults standardUserDefaults] setObject:modelName forKey:key];
    [[NSUserDefaults standardUserDefaults] setDouble:[[NSDate date] timeIntervalSince1970]
                                              forKey:kModelCacheTimestamp];
}

- (BOOL)isCacheValid {
    double timestamp = [[NSUserDefaults standardUserDefaults] doubleForKey:kModelCacheTimestamp];
    if (timestamp <= 0) return NO;
    return ([[NSDate date] timeIntervalSince1970] - timestamp) < kModelCacheTTL;
}

#pragma mark - Layer 2: IPSW.me API

- (void)fetchModelFromIPSW:(NSString *)identifier
                completion:(void (^)(NSString *name))completion {

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

        if (error) {
            NSLog(@"[EnterpriseAppStore] IPSW: Network error: %@", error.localizedDescription);
            completion(nil);
            return;
        }

        NSHTTPURLResponse *httpResponse = (NSHTTPURLResponse *)response;
        if (httpResponse.statusCode != 200) {
            NSLog(@"[EnterpriseAppStore] IPSW: HTTP %ld for %@",
                  (long)httpResponse.statusCode, identifier);
            completion(nil);
            return;
        }

        if (!data) {
            NSLog(@"[EnterpriseAppStore] IPSW: Empty response data");
            completion(nil);
            return;
        }

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

- (NSString *)fallbackModelName:(NSString *)identifier {

    if (!identifier || [identifier length] == 0) {
        return @"Unknown";
    }

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

    if ([suffix length] == 0) {
        return ([deviceType length] > 0) ? deviceType : identifier;
    }

    return [NSString stringWithFormat:@"%@ (%@)", deviceType, suffix];
}

#pragma mark - Check Update

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

- (void)openApp:(CDVInvokedUrlCommand *)command {

    NSString *urlScheme = [command.arguments objectAtIndex:0];

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

// ════════════════════════════════════════════════════════════
// MARK: - ⭐ Set Badge Number (iOS 10-18+ compatible)
// ════════════════════════════════════════════════════════════
//
// Badge behavior across iOS versions:
//
// ┌──────────┬──────────────────────────────────────────────────┐
// │ iOS Ver  │ Badge Mechanism                                  │
// ├──────────┼──────────────────────────────────────────────────┤
// │ 10 - 15  │ applicationIconBadgeNumber works directly        │
// │ 16 - 17  │ Requires notification authorization for badge    │
// │ 18+      │ applicationIconBadgeNumber deprecated,           │
// │          │ use UNUserNotificationCenter.setBadgeCount       │
// └──────────┴──────────────────────────────────────────────────┘
//
// Strategy:
//   1. Request notification authorization (provisional if available)
//   2. Use setBadgeCount on iOS 16+ (modern API)
//   3. Fallback to applicationIconBadgeNumber for older iOS
// ════════════════════════════════════════════════════════════

#pragma mark - Set Badge Number

/**
 * Sets the app icon badge number with full iOS version compatibility.
 *
 * Automatically handles:
 *   - Notification permission request (iOS 16+ requires this for badge)
 *   - UNUserNotificationCenter.setBadgeCount (iOS 16+)
 *   - UIApplication.applicationIconBadgeNumber (iOS 10-15 fallback)
 *   - Provisional authorization (non-intrusive permission request)
 *
 * Parameter: count (integer) — 0 to clear badge, >0 to set badge number
 *
 * Returns: {
 *   badge:                number,
 *   platform:             "ios",
 *   method:               "setBadgeCount" | "applicationIconBadgeNumber",
 *   notificationStatus:   "authorized" | "provisional" | "denied" | "notDetermined",
 *   iosVersion:           string
 * }
 */
- (void)setBadgeNumber:(CDVInvokedUrlCommand *)command {

    NSInteger badge = 0;
    if (command.arguments.count > 0) {
        badge = [[command.arguments objectAtIndex:0] integerValue];
    }

    // Clamp to non-negative value
    if (badge < 0) {
        badge = 0;
    }

    NSString *iosVersion = [[UIDevice currentDevice] systemVersion];
    NSLog(@"[EnterpriseAppStore] setBadgeNumber: count=%ld iOS=%@",
          (long)badge, iosVersion);

    // Capture badge value for use in blocks
    NSInteger badgeCount = badge;
    NSString *callbackId = command.callbackId;

    // Step 1: Check and request notification authorization
    // iOS 16+ requires notification permission for badge to display
    UNUserNotificationCenter *center = [UNUserNotificationCenter currentNotificationCenter];

    [center getNotificationSettingsWithCompletionHandler:^(UNNotificationSettings *settings) {

        NSLog(@"[EnterpriseAppStore] Notification authorization status: %ld",
              (long)settings.authorizationStatus);

        // Determine if we need to request authorization
        if (settings.authorizationStatus == UNAuthorizationStatusNotDetermined) {
            // First time: request provisional authorization (non-intrusive)
            // Provisional auth delivers notifications quietly and allows badge
            [self requestNotificationAuthAndSetBadge:badgeCount
                                          callbackId:callbackId
                                          iosVersion:iosVersion];
        }
        else if (settings.authorizationStatus == UNAuthorizationStatusDenied) {
            // Permission denied: try to set badge anyway (works on iOS < 16)
            // On iOS 16+ badge may not show, but we still try
            NSLog(@"[EnterpriseAppStore] Notification permission denied, "
                  @"attempting badge set anyway");
            [self applyBadgeCount:badgeCount
                       callbackId:callbackId
                       iosVersion:iosVersion
               notificationStatus:@"denied"];
        }
        else {
            // Authorized or Provisional: set badge directly
            NSString *statusStr = [self notificationStatusToString:settings.authorizationStatus];
            [self applyBadgeCount:badgeCount
                       callbackId:callbackId
                       iosVersion:iosVersion
               notificationStatus:statusStr];
        }
    }];
}

#pragma mark - Badge Helper: Request Notification Auth Then Set Badge

/**
 * Requests provisional notification authorization, then sets badge.
 * Provisional authorization (iOS 12+) does NOT show a permission dialog
 * to the user — it silently grants limited notification permission
 * which includes badge capability.
 */
- (void)requestNotificationAuthAndSetBadge:(NSInteger)badgeCount
                                callbackId:(NSString *)callbackId
                                iosVersion:(NSString *)iosVersion {

    UNUserNotificationCenter *center = [UNUserNotificationCenter currentNotificationCenter];

    // Request authorization options: badge is the primary need
    // Include provisional to avoid showing permission dialog
    UNAuthorizationOptions options = UNAuthorizationOptionBadge;

    // iOS 12+ supports provisional authorization (silent, no dialog)
    if (@available(iOS 12.0, *)) {
        options = UNAuthorizationOptionBadge | UNAuthorizationOptionProvisional;
    }

    NSLog(@"[EnterpriseAppStore] Requesting notification authorization for badge...");

    [center requestAuthorizationWithOptions:options
                          completionHandler:^(BOOL granted, NSError *error) {

        if (error) {
            NSLog(@"[EnterpriseAppStore] Notification auth error: %@",
                  error.localizedDescription);
        }

        NSString *statusStr = granted ? @"authorized" : @"denied";

        // Check actual status after request (may be provisional)
        if (@available(iOS 12.0, *)) {
            [center getNotificationSettingsWithCompletionHandler:^(UNNotificationSettings *settings) {
                NSString *actualStatus = [self notificationStatusToString:settings.authorizationStatus];
                NSLog(@"[EnterpriseAppStore] Notification auth result: granted=%d status=%@",
                      granted, actualStatus);

                [self applyBadgeCount:badgeCount
                           callbackId:callbackId
                           iosVersion:iosVersion
                   notificationStatus:actualStatus];
            }];
        } else {
            NSLog(@"[EnterpriseAppStore] Notification auth result: granted=%d", granted);

            [self applyBadgeCount:badgeCount
                       callbackId:callbackId
                       iosVersion:iosVersion
               notificationStatus:statusStr];
        }
    }];
}

#pragma mark - Badge Helper: Apply Badge Count

/**
 * Actually sets the badge number using the appropriate API for the iOS version.
 *
 * iOS 16+ (API available): UNUserNotificationCenter.setBadgeCount
 *   - This is the modern, non-deprecated API
 *   - Works with notification authorization
 *
 * iOS 10-15 (fallback): UIApplication.applicationIconBadgeNumber
 *   - Legacy API, still functional on older iOS
 *   - Does not require notification authorization
 */
- (void)applyBadgeCount:(NSInteger)badgeCount
             callbackId:(NSString *)callbackId
             iosVersion:(NSString *)iosVersion
     notificationStatus:(NSString *)notificationStatus {

    dispatch_async(dispatch_get_main_queue(), ^{

        NSString *method = @"unknown";
        BOOL success = YES;
        NSString *errorMessage = @"";

        // Strategy: try modern API first, then fallback

        if (@available(iOS 16.0, *)) {
            // ── iOS 16+: Use setBadgeCount (modern API) ──
            // This API respects notification authorization and is
            // the officially supported way to set badge on iOS 16+

            method = @"setBadgeCount";

            UNUserNotificationCenter *center =
                [UNUserNotificationCenter currentNotificationCenter];

            // setBadgeCount is synchronous-ish via completion handler
            // but we also set the legacy property as double-insurance
            [center setBadgeCount:badgeCount
                withCompletionHandler:^(NSError *error) {
                if (error) {
                    NSLog(@"[EnterpriseAppStore] setBadgeCount error: %@",
                          error.localizedDescription);

                    // Fallback: try legacy API even on iOS 16+
                    dispatch_async(dispatch_get_main_queue(), ^{
                        // Suppress deprecation warning — this is intentional fallback
                        #pragma clang diagnostic push
                        #pragma clang diagnostic ignored "-Wdeprecated-declarations"
                        [UIApplication sharedApplication].applicationIconBadgeNumber = badgeCount;
                        #pragma clang diagnostic pop

                        NSLog(@"[EnterpriseAppStore] Fallback to applicationIconBadgeNumber");

                        [self sendBadgeResultWithCallbackId:callbackId
                                                      badge:badgeCount
                                                     method:@"applicationIconBadgeNumber_fallback"
                                         notificationStatus:notificationStatus
                                                 iosVersion:iosVersion
                                                    success:YES
                                               errorMessage:error.localizedDescription];
                    });
                } else {
                    NSLog(@"[EnterpriseAppStore] setBadgeCount success: %ld",
                          (long)badgeCount);

                    [self sendBadgeResultWithCallbackId:callbackId
                                                  badge:badgeCount
                                                 method:@"setBadgeCount"
                                     notificationStatus:notificationStatus
                                             iosVersion:iosVersion
                                                success:YES
                                           errorMessage:@""];
                }
            }];

            // Return early — result sent in completion handler above
            return;
        }

        // ── iOS 10-15: Use applicationIconBadgeNumber (legacy API) ──
        // No deprecation issue on these versions
        method = @"applicationIconBadgeNumber";
        [UIApplication sharedApplication].applicationIconBadgeNumber = badgeCount;
        NSLog(@"[EnterpriseAppStore] applicationIconBadgeNumber set: %ld",
              (long)badgeCount);

        [self sendBadgeResultWithCallbackId:callbackId
                                      badge:badgeCount
                                     method:method
                         notificationStatus:notificationStatus
                                 iosVersion:iosVersion
                                    success:success
                               errorMessage:errorMessage];
    });
}

#pragma mark - Badge Helper: Send Result to JavaScript

/**
 * Sends the badge operation result back to the JavaScript layer.
 * Provides detailed information about which method was used
 * and the notification permission status.
 */
- (void)sendBadgeResultWithCallbackId:(NSString *)callbackId
                                badge:(NSInteger)badge
                               method:(NSString *)method
                   notificationStatus:(NSString *)notificationStatus
                           iosVersion:(NSString *)iosVersion
                              success:(BOOL)success
                         errorMessage:(NSString *)errorMessage {

    dispatch_async(dispatch_get_main_queue(), ^{

        NSMutableDictionary *result = [NSMutableDictionary dictionaryWithDictionary:@{
            @"badge":              @(badge),
            @"platform":           @"ios",
            @"method":             method,
            @"notificationStatus": notificationStatus,
            @"iosVersion":         iosVersion,
            @"success":            @(success)
        }];

        // Include error message if present
        if (errorMessage && [errorMessage length] > 0) {
            result[@"errorMessage"] = errorMessage;
        }

        NSLog(@"[EnterpriseAppStore] setBadgeNumber result: badge=%ld method=%@ "
              @"notifStatus=%@ success=%d",
              (long)badge, method, notificationStatus, success);

        CDVPluginResult *pluginResult;
        if (success) {
            pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK
                                         messageAsDictionary:result];
        } else {
            pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR
                                         messageAsDictionary:result];
        }

        [pluginResult setKeepCallbackAsBool:NO];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:callbackId];
    });
}

#pragma mark - Badge Helper: Notification Status to String

/**
 * Converts UNAuthorizationStatus enum to human-readable string.
 */
- (NSString *)notificationStatusToString:(UNAuthorizationStatus)status {
    switch (status) {
        case UNAuthorizationStatusAuthorized:
            return @"authorized";
        case UNAuthorizationStatusDenied:
            return @"denied";
        case UNAuthorizationStatusNotDetermined:
            return @"notDetermined";
        default:
            break;
    }

    // iOS 12+ statuses
    if (@available(iOS 12.0, *)) {
        if (status == UNAuthorizationStatusProvisional) {
            return @"provisional";
        }
    }

    // iOS 14+ statuses
    if (@available(iOS 14.0, *)) {
        if (status == UNAuthorizationStatusEphemeral) {
            return @"ephemeral";
        }
    }

    return @"unknown";
}

#pragma mark - Semantic Version Comparison

- (BOOL)compareVersion:(NSString *)v1 isLessThan:(NSString *)v2 {

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
        if (p1 < p2) return YES;
        if (p1 > p2) return NO;
    }
    return NO;
}

#pragma mark - Send Status Helper

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

#pragma mark - Keychain Helpers

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

    SecItemDelete((__bridge CFDictionaryRef)query);

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
        NSLog(@"[KeychainHelper] read: key='%@' failed with OSStatus %d", key, (int)status);
    }

    return nil;
}

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
