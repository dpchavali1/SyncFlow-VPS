//
//  NotificationService.swift
//  SyncFlowMac
//
//  Handles desktop notifications for incoming messages
//

import Foundation
import UserNotifications
import AppKit

class NotificationService: NSObject, UNUserNotificationCenterDelegate {

    static let shared = NotificationService()

    private override init() {
        super.init()
        setupNotifications()
    }

    // MARK: - Setup

    private func setupNotifications() {
        let center = UNUserNotificationCenter.current()
        center.delegate = self

        // Request authorization
        center.requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in
            // Silent - no logging needed
        }

        // Define quick reply action
        let replyAction = UNTextInputNotificationAction(
            identifier: "REPLY_ACTION",
            title: "Reply",
            options: [.foreground],
            textInputButtonTitle: "Send",
            textInputPlaceholder: "Type a message..."
        )

        let category = UNNotificationCategory(
            identifier: "MESSAGE_CATEGORY",
            actions: [replyAction],
            intentIdentifiers: [],
            options: [.customDismissAction]
        )

        // Define Answer and Decline actions for calls
        let answerAction = UNNotificationAction(
            identifier: "ANSWER_CALL_ACTION",
            title: "Answer",
            options: [.foreground]  // Bring app to foreground when answered
        )

        let answerVideoAction = UNNotificationAction(
            identifier: "ANSWER_VIDEO_ACTION",
            title: "Answer Video",
            options: [.foreground]
        )

        let declineAction = UNNotificationAction(
            identifier: "DECLINE_CALL_ACTION",
            title: "Decline",
            options: [.destructive]  // Red/destructive appearance
        )

        let callCategory = UNNotificationCategory(
            identifier: "CALL_CATEGORY",
            actions: [answerAction, declineAction],
            intentIdentifiers: [],
            options: []
        )

        let videoCallCategory = UNNotificationCategory(
            identifier: "VIDEO_CALL_CATEGORY",
            actions: [answerVideoAction, answerAction, declineAction],
            intentIdentifiers: [],
            options: []
        )

        // Phone call (cellular) actions
        let answerPhoneCallAction = UNNotificationAction(
            identifier: "ANSWER_PHONE_CALL_ACTION",
            title: "Answer",
            options: [.foreground]
        )

        let declinePhoneCallAction = UNNotificationAction(
            identifier: "DECLINE_PHONE_CALL_ACTION",
            title: "Decline",
            options: [.destructive]
        )

        let phoneCallCategory = UNNotificationCategory(
            identifier: "PHONE_CALL_CATEGORY",
            actions: [answerPhoneCallAction, declinePhoneCallAction],
            intentIdentifiers: [],
            options: []
        )

        center.setNotificationCategories([category, callCategory, videoCallCategory, phoneCallCategory])
    }

    // MARK: - Show Notification

    func showMessageNotification(
        from address: String,
        contactName: String?,
        body: String,
        messageId: String
    ) {
        let content = UNMutableNotificationContent()
        content.title = contactName ?? address
        content.body = body
        content.categoryIdentifier = "MESSAGE_CATEGORY"
        content.userInfo = [
            "address": address,
            "messageId": messageId
        ]

        // Use custom sound for contact if set
        let soundService = NotificationSoundService.shared
        let sound = soundService.getSound(for: address)

        // Try to use custom sound, fallback to default
        if let soundURL = getSoundURL(for: sound.systemSound) {
            content.sound = UNNotificationSound(named: UNNotificationSoundName(rawValue: soundURL.lastPathComponent))
        } else {
            content.sound = .default
        }

        let request = UNNotificationRequest(
            identifier: messageId,
            content: content,
            trigger: nil  // Deliver immediately
        )

        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                print("❌ Error showing notification: \(error)")
            }
        }

        // Also play sound manually for better compatibility
        soundService.playSound(for: address)
    }

    func showIncomingCallNotification(
        callerName: String,
        callerPhone: String? = nil,
        isVideo: Bool,
        callId: String
    ) {
        print("🔔 NotificationService: Showing incoming call notification for \(callerName), callId: \(callId)")

        let content = UNMutableNotificationContent()

        // More prominent title and body
        if isVideo {
            content.title = "📹 Incoming Video Call"
            content.subtitle = callerName
            content.body = callerPhone ?? "SyncFlow Video Call"
        } else {
            content.title = "📞 Incoming Call"
            content.subtitle = callerName
            content.body = callerPhone ?? "SyncFlow Call"
        }

        // Use appropriate category based on call type
        content.categoryIdentifier = isVideo ? "VIDEO_CALL_CATEGORY" : "CALL_CATEGORY"

        // Use ringtone sound
        content.sound = .default

        if #available(macOS 12.0, *) {
            content.interruptionLevel = .timeSensitive
        }

        content.userInfo = [
            "type": "call",
            "callId": callId,
            "callerName": callerName,
            "callerPhone": callerPhone ?? "",
            "isVideo": isVideo
        ]

        let request = UNNotificationRequest(
            identifier: "call_\(callId)",
            content: content,
            trigger: nil
        )

        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                print("❌ Error showing call notification: \(error)")
            } else {
                print("✅ Call notification shown for \(callerName)")
            }
        }
    }

    func clearCallNotification(callId: String) {
        UNUserNotificationCenter.current()
            .removeDeliveredNotifications(withIdentifiers: ["call_\(callId)"])
    }

    /// Show notification for incoming phone call (cellular call from Android)
    func showIncomingPhoneCallNotification(
        callerName: String,
        phoneNumber: String,
        callId: String
    ) {
        let content = UNMutableNotificationContent()
        content.title = "📱 Incoming Phone Call"
        content.subtitle = callerName
        content.body = phoneNumber

        content.categoryIdentifier = "PHONE_CALL_CATEGORY"
        content.sound = .default

        if #available(macOS 12.0, *) {
            content.interruptionLevel = .timeSensitive
        }

        content.userInfo = [
            "type": "phone_call",
            "callId": callId,
            "callerName": callerName,
            "phoneNumber": phoneNumber
        ]

        let request = UNNotificationRequest(
            identifier: "phone_call_\(callId)",
            content: content,
            trigger: nil
        )

        UNUserNotificationCenter.current().add(request) { _ in
            // Silent - no logging needed
        }
    }

    func clearPhoneCallNotification(callId: String) {
        UNUserNotificationCenter.current()
            .removeDeliveredNotifications(withIdentifiers: ["phone_call_\(callId)"])
    }

    private func getSoundURL(for soundName: String) -> URL? {
        let systemSounds = URL(fileURLWithPath: "/System/Library/Sounds")
        let extensions = ["aiff", "wav", "mp3", "caf"]

        for ext in extensions {
            let soundURL = systemSounds.appendingPathComponent("\(soundName).\(ext)")
            if FileManager.default.fileExists(atPath: soundURL.path) {
                return soundURL
            }
        }
        return nil
    }

    // MARK: - Badge Management

    func setBadgeCount(_ count: Int) {
        DispatchQueue.main.async {
            NSApp.dockTile.badgeLabel = count > 0 ? "\(count)" : nil
        }
    }

    func clearBadge() {
        setBadgeCount(0)
    }

    // MARK: - UNUserNotificationCenterDelegate

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        // Show notification even when app is in foreground
        return [.banner, .sound, .badge]
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        let userInfo = response.notification.request.content.userInfo

        if response.actionIdentifier == "REPLY_ACTION",
           let textResponse = response as? UNTextInputNotificationResponse {
            // Handle quick reply
            if let address = userInfo["address"] as? String {
                await handleQuickReply(to: address, body: textResponse.userText)
            }
        } else if response.actionIdentifier == "ANSWER_CALL_ACTION" ||
                  response.actionIdentifier == "ANSWER_VIDEO_ACTION" {
            // Handle Answer action from notification
            if let callId = userInfo["callId"] as? String,
               let callerName = userInfo["callerName"] as? String {
                let isVideo = userInfo["isVideo"] as? Bool ?? false
                let withVideo = response.actionIdentifier == "ANSWER_VIDEO_ACTION"

                print("📞 Answer call action: callId=\(callId), withVideo=\(withVideo)")

                DispatchQueue.main.async {
                    NSApp.activate(ignoringOtherApps: true)

                    // Post notification to answer the call and show video screen
                    NotificationCenter.default.post(
                        name: .answerCallFromNotification,
                        object: nil,
                        userInfo: [
                            "callId": callId,
                            "callerName": callerName,
                            "withVideo": withVideo,
                            "isVideoCall": isVideo
                        ]
                    )
                }
            }
        } else if response.actionIdentifier == "DECLINE_CALL_ACTION" {
            // Handle Decline action from notification
            if let callId = userInfo["callId"] as? String {
                print("Decline call action: callId=\(callId)")

                DispatchQueue.main.async {
                    // Post notification to decline the call
                    NotificationCenter.default.post(
                        name: .declineCallFromNotification,
                        object: nil,
                        userInfo: ["callId": callId]
                    )
                }
            }
        } else if response.actionIdentifier == "ANSWER_PHONE_CALL_ACTION" {
            // Handle Answer action for phone calls
            if let callId = userInfo["callId"] as? String {
                print("Phone Answer phone call action: callId=\(callId)")

                DispatchQueue.main.async {
                    NSApp.activate(ignoringOtherApps: true)

                    NotificationCenter.default.post(
                        name: .answerPhoneCallFromNotification,
                        object: nil,
                        userInfo: ["callId": callId]
                    )
                }
            }
        } else if response.actionIdentifier == "DECLINE_PHONE_CALL_ACTION" {
            // Handle Decline action for phone calls
            if let callId = userInfo["callId"] as? String {
                print("Phone Decline phone call action: callId=\(callId)")

                DispatchQueue.main.async {
                    NotificationCenter.default.post(
                        name: .declinePhoneCallFromNotification,
                        object: nil,
                        userInfo: ["callId": callId]
                    )
                }
            }
        } else if response.actionIdentifier == UNNotificationDefaultActionIdentifier,
                  let type = userInfo["type"] as? String,
                  type == "call" {
            // User tapped the notification itself - show the incoming call view
            if let callId = userInfo["callId"] as? String,
               let callerName = userInfo["callerName"] as? String {
                let isVideo = userInfo["isVideo"] as? Bool ?? false

                DispatchQueue.main.async {
                    NSApp.activate(ignoringOtherApps: true)

                    // Post notification to show incoming call UI
                    NotificationCenter.default.post(
                        name: .showIncomingCallUI,
                        object: nil,
                        userInfo: [
                            "callId": callId,
                            "callerName": callerName,
                            "isVideo": isVideo
                        ]
                    )
                }
            }
        } else if response.actionIdentifier == UNNotificationDefaultActionIdentifier {
            // User tapped notification - bring app to foreground and select conversation
            if let address = userInfo["address"] as? String {
                await showConversation(address: address)
            }
        }
    }

    // MARK: - Actions

    private func handleQuickReply(to address: String, body: String) async {
        // This will be handled by MessageStore
        NotificationCenter.default.post(
            name: .quickReply,
            object: nil,
            userInfo: ["address": address, "body": body]
        )
    }

    private func showConversation(address: String) async {
        // Activate app and select conversation
        DispatchQueue.main.async {
            NSApp.activate(ignoringOtherApps: true)

            NotificationCenter.default.post(
                name: .selectConversation,
                object: nil,
                userInfo: ["address": address]
            )
        }
    }
}

// MARK: - Notification Names

extension Notification.Name {
    static let quickReply = Notification.Name("quickReply")
    static let selectConversation = Notification.Name("selectConversation")

    // Call notification actions
    static let answerCallFromNotification = Notification.Name("answerCallFromNotification")
    static let declineCallFromNotification = Notification.Name("declineCallFromNotification")
    static let showIncomingCallUI = Notification.Name("showIncomingCallUI")

    // Phone call notification actions (regular cellular calls from Android)
    static let answerPhoneCallFromNotification = Notification.Name("answerPhoneCallFromNotification")
    static let declinePhoneCallFromNotification = Notification.Name("declinePhoneCallFromNotification")
}
