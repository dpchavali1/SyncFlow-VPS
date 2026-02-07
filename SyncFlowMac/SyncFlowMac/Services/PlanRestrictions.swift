import Foundation
import AppKit

/**
 * Utility class to enforce plan-based feature restrictions
 * Free tier: SMS only
 * Paid tier: All features
 */
class PlanRestrictions {
    enum Feature {
        case smsSend                    // Always allowed (native SMS)
        case smsReceive                 // Always allowed (native SMS)
        case mmsSend                    // Always allowed (native MMS) - aggressively deleted
        case mmsReceive                 // Always allowed (native MMS) - aggressively deleted
        case phoneCall                  // Always allowed (native phone calls via cell)
        case callSync                   // 7-day trial → paid only
        case voiceNote                  // 7-day trial → paid only
        case mediaAttachment            // 7-day trial → paid only
        case fileTransfer               // 7-day trial → paid only
        case endToEndEncryption         // 7-day trial → paid only
        case scheduledMessages          // 7-day trial → paid only
        case desktopSync                // 7-day trial → paid only
        case aiAssistant                // 7-day trial → paid only
        case advancedSearch             // 7-day trial → paid only
        case videoCallWebRTC            // 7-day trial → paid only
    }

    struct FeatureAccessResult {
        let allowed: Bool
        let message: String
        let title: String

        init(allowed: Bool, message: String = "", title: String = "Feature Locked") {
            self.allowed = allowed
            self.message = message
            self.title = title
        }
    }

    static let shared = PlanRestrictions()

    func checkFeatureAccess(feature: Feature, isPaidUser: Bool) -> FeatureAccessResult {
        if isPaidUser {
            return FeatureAccessResult(allowed: true)
        }

        // Free tier: SMS & MMS always works (native features)
        switch feature {
        case .smsSend, .smsReceive, .mmsSend, .mmsReceive, .phoneCall:
            return FeatureAccessResult(allowed: true)

        case .callSync:
            return FeatureAccessResult(
                allowed: false,
                message: "📞 Call synchronization is available in Paid plans only",
                title: "Upgrade to Paid"
            )

        case .voiceNote:
            return FeatureAccessResult(
                allowed: false,
                message: "🎙️ Voice notes are available in Paid plans only",
                title: "Upgrade to Paid"
            )

        case .mediaAttachment:
            return FeatureAccessResult(
                allowed: false,
                message: "📸 Media attachments (photos, videos) are available in Paid plans only",
                title: "Upgrade to Paid"
            )

        case .fileTransfer:
            return FeatureAccessResult(
                allowed: false,
                message: "📁 File transfers are available in Paid plans only",
                title: "Upgrade to Paid"
            )

        case .endToEndEncryption:
            return FeatureAccessResult(
                allowed: false,
                message: "🔐 End-to-end encryption is available in Paid plans only",
                title: "Upgrade to Paid"
            )

        case .scheduledMessages:
            return FeatureAccessResult(
                allowed: false,
                message: "⏰ Scheduled messages are available in Paid plans only",
                title: "Upgrade to Paid"
            )

        case .desktopSync:
            return FeatureAccessResult(
                allowed: false,
                message: "💻 Desktop synchronization is available in Paid plans only",
                title: "Upgrade to Paid"
            )

        case .aiAssistant:
            return FeatureAccessResult(
                allowed: false,
                message: "🤖 AI Assistant is available in Paid plans only",
                title: "Upgrade to Paid"
            )

        case .advancedSearch:
            return FeatureAccessResult(
                allowed: false,
                message: "🔍 Advanced search is available in Paid plans only",
                title: "Upgrade to Paid"
            )

        case .videoCallWebRTC:
            return FeatureAccessResult(
                allowed: false,
                message: "📹 Video calls are available in Paid plans only",
                title: "Upgrade to Paid"
            )
        }
    }

    func showAlertIfFeatureLocked(feature: Feature, isPaidUser: Bool, viewController: NSViewController) {
        let result = checkFeatureAccess(feature: feature, isPaidUser: isPaidUser)
        if !result.allowed {
            let alert = NSAlert()
            alert.messageText = result.title
            alert.informativeText = result.message
            alert.alertStyle = .informational
            alert.addButton(withTitle: "Upgrade")
            alert.addButton(withTitle: "Cancel")

            if alert.runModal() == NSApplication.ModalResponse.alertFirstButtonReturn {
                // Handle upgrade action
                if let url = URL(string: "https://sfweb.app/settings") {
                    NSWorkspace.shared.open(url)
                }
            }
        }
    }
}
