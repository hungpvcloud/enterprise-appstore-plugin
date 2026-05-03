/****************************************************************
 * EnterpriseAppStore.h
 *
 * Cordova Plugin Header for iOS (Objective-C)
 *
 * Features:
 *   - downloadAndInstall : OTA installation via itms-services://
 *   - getAppVersion      : Current app version info
 *   - isAppInstalled     : Check app via URL scheme
 *   - checkAppShield     : Device security checks
 *   - getDeviceInfo      : Device info with auto model name
 *                          resolution (IPSW API) and persistent
 *                          UUID (Keychain)
 *   - checkUpdate        : Compare installed vs latest version
 *   - openApp            : Launch another app by URL scheme
 *
 * Model Name Resolution (3-Layer Automatic):
 *   Layer 1: Local Cache (NSUserDefaults)
 *   Layer 2: IPSW.me Public API (auto-updated, free)
 *   Layer 3: Fallback auto-parse (never fails)
 *
 * UUID Strategy:
 *   Keychain-backed for persistence across app reinstalls.
 ****************************************************************/

#import <Cordova/CDV.h>
#import <UIKit/UIKit.h>
#import <Security/Security.h>

@interface EnterpriseAppStore : CDVPlugin

// ════════════════════════════════════════════════════════════
// MARK: - Public Plugin Methods (called from JavaScript)
// ════════════════════════════════════════════════════════════

/**
 * Triggers iOS OTA installation via itms-services:// protocol.
 * @param command.arguments[0] manifestUrl — HTTPS URL to manifest.plist
 */
- (void)downloadAndInstall:(CDVInvokedUrlCommand *)command;

/**
 * Returns current app version info.
 * @return { packageName, versionName, versionCode }
 */
- (void)getAppVersion:(CDVInvokedUrlCommand *)command;

/**
 * Checks if an app is installed via URL scheme.
 * @param command.arguments[0] urlScheme — URL scheme of target app
 * @return { isInstalled, packageName, versionName, versionCode }
 */
- (void)isAppInstalled:(CDVInvokedUrlCommand *)command;

/**
 * Performs device security checks (jailbreak, simulator, etc).
 * @return { isJailbroken, isDeveloperMode, isSimulator, isInstalledFromStore, isSafe }
 */
- (void)checkAppShield:(CDVInvokedUrlCommand *)command;

/**
 * Returns comprehensive device and app information.
 * Model name is resolved automatically via IPSW.me API.
 * UUID is persisted in Keychain across reinstalls.
 * @return { uuid, model, machineIdentifier, systemVersion, ... }
 */
- (void)getDeviceInfo:(CDVInvokedUrlCommand *)command;

/**
 * Compares installed app version against latest version.
 * @param command.arguments[0] packageName (ignored on iOS)
 * @param command.arguments[1] latestVersion — version string to compare
 * @return { needsUpdate, installedVersion, latestVersion, packageName, isInstalled }
 */
- (void)checkUpdate:(CDVInvokedUrlCommand *)command;

/**
 * Launches another app by URL scheme.
 * No LSApplicationQueriesSchemes needed.
 * @param command.arguments[0] urlScheme — e.g. "myapp" or "myapp://path"
 * @return { success, scheme, packageName, message }
 */
- (void)openApp:(CDVInvokedUrlCommand *)command;

- (void)setBadgeNumber:(CDVInvokedUrlCommand *)command;
@end
