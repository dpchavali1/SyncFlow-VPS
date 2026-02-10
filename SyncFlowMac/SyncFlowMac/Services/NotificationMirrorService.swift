//
//  NotificationMirrorService.swift
//  SyncFlowMac
//
//  Mirrors Android notifications to macOS via VPS.
//

import Foundation
import AppKit
import UserNotifications
import Combine

// MARK: - NotificationMirrorService

class NotificationMirrorService: ObservableObject {

    // MARK: - Singleton

    static let shared = NotificationMirrorService()

    // MARK: - Published Properties

    @Published var recentNotifications: [MirroredNotification] = []
    @Published var isEnabled: Bool = true
    @Published var lastSyncTime: Date?

    // MARK: - Private Properties

    private var currentUserId: String?
    private var displayedNotificationIds = Set<String>()
    private var pollTimer: Timer?
    /// Timestamp of the newest notification we've seen, used for incremental polling
    private var lastSeenTimestamp: Int64 = 0

    // MARK: - Initialization

    private init() {
        isEnabled = UserDefaults.standard.object(forKey: "notification_mirror_enabled") as? Bool ?? true
        requestNotificationPermission()
    }

    // MARK: - Notification Permissions

    private func requestNotificationPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    // MARK: - Sync Control

    func startSync(userId: String) {
        currentUserId = userId
        // Fetch initial notifications
        Task {
            await fetchNotifications()
        }
        // Start polling every 30 seconds for new notifications
        startPolling()
    }

    func stopSync() {
        stopPolling()
        currentUserId = nil
    }

    // MARK: - Polling

    private func startPolling() {
        stopPolling()
        DispatchQueue.main.async {
            self.pollTimer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
                guard let self = self, self.isEnabled else { return }
                Task {
                    await self.fetchNotifications(incremental: true)
                }
            }
        }
    }

    private func stopPolling() {
        pollTimer?.invalidate()
        pollTimer = nil
    }

    // MARK: - Fetch Notifications from VPS

    @MainActor
    func fetchNotifications(incremental: Bool = false) async {
        guard currentUserId != nil, isEnabled else { return }

        do {
            let since: Int64? = incremental && lastSeenTimestamp > 0 ? lastSeenTimestamp : nil
            let response = try await VPSService.shared.getNotifications(limit: 50, since: since)

            var newNotifications: [MirroredNotification] = []
            for vpsNotif in response.notifications {
                let notif = MirroredNotification(
                    id: vpsNotif.id,
                    appPackage: vpsNotif.appPackage,
                    appName: vpsNotif.appName ?? vpsNotif.appPackage,
                    appIcon: nil,
                    title: vpsNotif.title ?? "",
                    text: vpsNotif.body ?? "",
                    timestamp: Date(timeIntervalSince1970: TimeInterval(vpsNotif.timestamp) / 1000),
                    syncedAt: Date()
                )
                newNotifications.append(notif)

                // Track newest timestamp
                if vpsNotif.timestamp > lastSeenTimestamp {
                    lastSeenTimestamp = vpsNotif.timestamp
                }
            }

            if incremental {
                // Only add truly new notifications
                for notif in newNotifications {
                    if !displayedNotificationIds.contains(notif.id) {
                        recentNotifications.insert(notif, at: 0)
                        displayedNotificationIds.insert(notif.id)
                        showNotification(notif)
                    }
                }
            } else {
                // Full refresh
                recentNotifications = newNotifications
                displayedNotificationIds = Set(newNotifications.map { $0.id })
            }

            lastSyncTime = Date()
        } catch {
            print("[NotificationMirrorService] Error fetching notifications: \(error.localizedDescription)")
        }
    }

    // MARK: - macOS Notification Display

    private func showNotification(_ notification: MirroredNotification) {
        let content = UNMutableNotificationContent()
        content.title = notification.appName
        content.subtitle = notification.title
        content.body = notification.text
        content.sound = .default
        content.categoryIdentifier = "MIRRORED_NOTIFICATION"

        let request = UNNotificationRequest(
            identifier: "mirrored-\(notification.id)",
            content: content,
            trigger: nil
        )

        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                print("[NotificationMirrorService] Error showing notification: \(error)")
            }
        }
    }

    // MARK: - Notification Management

    func clearNotifications() {
        DispatchQueue.main.async {
            self.recentNotifications.removeAll()
            self.displayedNotificationIds.removeAll()
        }
        Task {
            try? await VPSService.shared.clearAllNotifications()
        }
    }

    func dismissNotification(_ notification: MirroredNotification) {
        DispatchQueue.main.async {
            self.recentNotifications.removeAll { $0.id == notification.id }
        }
        let identifier = "mirrored-\(notification.id)"
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [identifier])
        Task {
            try? await VPSService.shared.deleteNotification(id: notification.id)
        }
    }

    // MARK: - Settings

    func setEnabled(_ enabled: Bool) {
        isEnabled = enabled
        UserDefaults.standard.set(enabled, forKey: "notification_mirror_enabled")
        if enabled, let userId = currentUserId {
            startSync(userId: userId)
        } else if !enabled {
            stopPolling()
        }
    }

    // MARK: - Battery Optimization

    func reduceUpdateFrequency() {}

    func pauseMirroring() {
        isEnabled = false
        stopPolling()
    }

    func resumeMirroring() {
        isEnabled = true
        if let userId = currentUserId {
            startSync(userId: userId)
        }
    }
}

// MARK: - MirroredNotification Model

struct MirroredNotification: Identifiable {
    let id: String
    let appPackage: String
    let appName: String
    let appIcon: NSImage?
    let title: String
    let text: String
    let timestamp: Date
    let syncedAt: Date

    var formattedTime: String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(for: timestamp, relativeTo: Date())
    }
}
