import Foundation
import UIKit

@objc(EnterpriseAppStore)
class EnterpriseAppStore: CDVPlugin {

    // ─────────────────────────────────────────────
    // DOWNLOAD AND INSTALL (iOS OTA via itms-services)
    // ─────────────────────────────────────────────
    @objc(downloadAndInstall:)
    func downloadAndInstall(command: CDVInvokedUrlCommand) {
        guard let manifestUrl = command.arguments[0] as? String else {
            let result = CDVPluginResult(
                status: CDVCommandStatus_ERROR,
                messageAs: "INVALID_MANIFEST_URL"
            )
            self.commandDelegate.send(result, callbackId: command.callbackId)
            return
        }

        // iOS dùng itms-services:// protocol để cài Enterprise IPA
        // manifestUrl phải là HTTPS URL đến file manifest.plist
        let itsUrlString = "itms-services://?action=download-manifest&url=\(manifestUrl)"

        guard let itsUrl = URL(string: itsUrlString) else {
            let result = CDVPluginResult(
                status: CDVCommandStatus_ERROR,
                messageAs: "INVALID_URL_FORMAT"
            )
            self.commandDelegate.send(result, callbackId: command.callbackId)
            return
        }

        DispatchQueue.main.async {
            // Gửi progress: bắt đầu
            self.sendProgress(
                callbackId: command.callbackId,
                status: "INSTALLING",
                progress: 0,
                message: "Opening iOS installation dialog..."
            )

            if UIApplication.shared.canOpenURL(itsUrl) {
                UIApplication.shared.open(itsUrl, options: [:]) { success in
                    if success {
                        self.sendProgress(
                            callbackId: command.callbackId,
                            status: "INSTALL_PROMPT",
                            progress: 100,
                            message: "iOS installation dialog opened successfully"
                        )
                        // Final success
                        let finalResult = CDVPluginResult(
                            status: CDVCommandStatus_OK,
                            messageAs: [
                                "status": "SUCCESS",
                                "message": "iOS OTA installation initiated"
                            ] as [String: Any]
                        )
                        finalResult?.setKeepCallbackAs(false)
                        self.commandDelegate.send(
                            finalResult,
                            callbackId: command.callbackId
                        )
                    } else {
                        let result = CDVPluginResult(
                            status: CDVCommandStatus_ERROR,
                            messageAs: "INSTALL_FAILED: Cannot open itms-services URL"
                        )
                        self.commandDelegate.send(
                            result,
                            callbackId: command.callbackId
                        )
                    }
                }
            } else {
                let result = CDVPluginResult(
                    status: CDVCommandStatus_ERROR,
                    messageAs: "ITMS_NOT_SUPPORTED: Device cannot handle itms-services"
                )
                self.commandDelegate.send(result, callbackId: command.callbackId)
            }
        }
    }

    // ─────────────────────────────────────────────
    // GET APP VERSION (iOS)
    // ─────────────────────────────────────────────
    @objc(getAppVersion:)
    func getAppVersion(command: CDVInvokedUrlCommand) {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"]
                      as? String ?? "Unknown"
        let build = Bundle.main.infoDictionary?["CFBundleVersion"]
                    as? String ?? "Unknown"
        let result = CDVPluginResult(
            status: CDVCommandStatus_OK,
            messageAs: "\(version) (\(build))"
        )
        self.commandDelegate.send(result, callbackId: command.callbackId)
    }

    // ─────────────────────────────────────────────
    // IS APP INSTALLED — iOS không cho check app khác
    // Chỉ check bằng URL scheme nếu app đã khai báo
    // ─────────────────────────────────────────────
    @objc(isAppInstalled:)
    func isAppInstalled(command: CDVInvokedUrlCommand) {
        guard let urlScheme = command.arguments[0] as? String,
              let url = URL(string: "\(urlScheme)://") else {
            let result = CDVPluginResult(
                status: CDVCommandStatus_OK,
                messageAs: 0
            )
            self.commandDelegate.send(result, callbackId: command.callbackId)
            return
        }

        let isInstalled = UIApplication.shared.canOpenURL(url) ? 1 : 0
        let result = CDVPluginResult(
            status: CDVCommandStatus_OK,
            messageAs: isInstalled
        )
        self.commandDelegate.send(result, callbackId: command.callbackId)
    }

    // ─────────────────────────────────────────────
    // HELPER: Send Progress
    // ─────────────────────────────────────────────
    private func sendProgress(callbackId: String, status: String,
                               progress: Int, message: String) {
        let data: [String: Any] = [
            "status": status,
            "progress": progress,
            "message": message
        ]
        let result = CDVPluginResult(status: CDVCommandStatus_OK,
                                     messageAs: data)
        result?.setKeepCallbackAs(true) // Giữ callback
        self.commandDelegate.send(result, callbackId: callbackId)
    }
}