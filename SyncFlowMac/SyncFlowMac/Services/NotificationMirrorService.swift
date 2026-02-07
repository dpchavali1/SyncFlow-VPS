//
//  NotificationMirrorService.swift
//  SyncFlowMac
//
//  Created for SyncFlow notification mirroring functionality.
//
//  OVERVIEW:
//  =========
//  This service mirrors Android notifications to macOS by listening for notification
//  metadata in Firebase Realtime Database. When Android posts a notification, it's
//  captured by the Android app and synced to Firebase, where this service picks it
//  up and displays a corresponding macOS notification.
//
//  SYNC MECHANISM:
//  ===============
//  1. Android app captures notifications via NotificationListenerService
//  2. Notification metadata is pushed to Firebase: users/{userId}/mirrored_notifications
//  3. This service listens for new entries and displays macOS notifications
//  4. Dismissing on either platform removes from Firebase (synced dismissal)
//
//  DATA FLOW:
//  ==========
//  Android NotificationListenerService
//       -> Firebase Realtime DB (mirrored_notifications collection)
//            -> NotificationMirrorService (.childAdded listener)
//                 -> UNUserNotificationCenter (macOS notification)
//
//  FIREBASE REAL-TIME LISTENER PATTERNS:
//  =====================================
//  - Uses queryOrdered(byChild: "syncedAt").queryLimited(toLast: 20) for initial fetch
//  - .childAdded observer fires for new notifications
//  - .childRemoved observer handles dismissal sync from Android
//  - Listeners are managed via DatabaseHandle for proper cleanup
//  - Query limits initial load to 20 most recent notifications
//
//  THREADING/ASYNC CONSIDERATIONS:
//  ===============================
//  - Firebase callbacks fire on main thread by default
//  - UI updates (recentNotifications) dispatched to main thread explicitly
//  - UNUserNotificationCenter operations are thread-safe
//  - displayedNotificationIds Set accessed only from callback thread (main)
//
//  DEDUPLICATION:
//  ==============
//  - displayedNotificationIds Set tracks shown notification IDs
//  - Prevents duplicate display if same notification fires multiple times
//  - Cleared when notifications are manually cleared via clearNotifications()
//
//  ERROR HANDLING:
//  ===============
//  - Missing required fields in notification data results in nil return from parseNotification
//  - UNUserNotificationCenter errors are logged but don't affect other notifications
//  - Firebase operation errors are logged but don't throw
//
//  NOTIFICATION IDENTIFIERS:
//  =========================
//  - macOS notification identifier format: "mirrored-{firebaseKey}"
//  - Allows targeted removal when Android dismisses notification
//

import Foundation
import AppKit
import UserNotifications
import Combine
// FirebaseDatabase - using FirebaseStubs.swift

// MARK: - NotificationMirrorService

/// Service responsible for mirroring Android notifications to macOS.
///
/// This singleton service manages:
/// - Listening for notification metadata from Firebase Realtime Database
/// - Displaying macOS notifications via UNUserNotificationCenter
/// - Tracking displayed notifications to prevent duplicates
/// - Syncing notification dismissals between devices
///
/// Usage:
/// ```swift
/// // Start mirroring after user authentication
/// NotificationMirrorService.shared.startSync(userId: "user123")
///
/// // Observe mirrored notifications for UI display
/// NotificationMirrorService.shared.$recentNotifications
///     .sink { notifications in /* update notification list */ }
///
/// // Dismiss a notification (syncs to Android)
/// NotificationMirrorService.shared.dismissNotification(notification)
///
/// // Stop sync on logout
/// NotificationMirrorService.shared.stopSync()
/// ```
class NotificationMirrorService: ObservableObject {

    // MARK: - Singleton

    /// Shared singleton instance for app-wide notification mirroring
    static let shared = NotificationMirrorService()

    // MARK: - Published Properties

    /// Array of mirrored notifications for UI display (newest first)
    @Published var recentNotifications: [MirroredNotification] = []
    /// Whether notification mirroring is enabled (toggled via UI)
    @Published var isEnabled: Bool = true
    /// Timestamp of the last received notification
    @Published var lastSyncTime: Date?

    // MARK: - Private Properties

    /// Firebase Realtime Database instance
    private let database = Database.database()
    /// Handle for .childAdded observer on notifications query
    private var notificationsHandle: DatabaseHandle?
    /// Handle for .childRemoved observer on notifications reference
    private var notificationsRemovedHandle: DatabaseHandle?
    /// Query for fetching notifications ordered by syncedAt, limited to last 20
    private var notificationsQuery: DatabaseQuery?
    /// Reference to the mirrored_notifications node for removal listener
    private var notificationsRef: DatabaseReference?
    /// Current authenticated user's ID
    private var currentUserId: String?

    // MARK: - Deduplication

    /// Set of notification IDs that have already been displayed.
    /// Prevents duplicate macOS notifications if Firebase delivers same childAdded multiple times.
    private var displayedNotificationIds = Set<String>()

    // MARK: - Initialization

    /// Private initializer enforces singleton pattern.
    /// Requests notification permission on first access.
    private init() {
        // Request notification permissions
        requestNotificationPermission()
    }

    // MARK: - Notification Permissions

    /// Requests macOS notification permission via UNUserNotificationCenter.
    /// Called once during singleton initialization.
    ///
    /// Requested permissions: alert, sound, badge
    private func requestNotificationPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { granted, error in
            if granted {
            } else if let error = error {
            }
        }
    }

    // MARK: - Sync Control

    /// Starts listening for mirrored notifications from Firebase.
    ///
    /// - Parameter userId: The sync group user ID
    func startSync(userId: String) {
        currentUserId = userId
        startListeningForNotifications(userId: userId)
    }

    /// Stops notification mirroring and cleans up Firebase listeners.
    func stopSync() {
        stopListeningForNotifications()
        currentUserId = nil
    }

    // MARK: - Firebase Real-Time Listeners

    /// Sets up Firebase listeners for notification changes.
    ///
    /// Firebase Listener Pattern:
    /// 1. Creates reference to users/{userId}/mirrored_notifications
    /// 2. Creates query ordered by syncedAt, limited to last 20
    /// 3. Attaches .childAdded observer to query for new notifications
    /// 4. Attaches .childRemoved observer to reference for dismissal sync
    ///
    /// Why separate query and reference?
    /// - Query limits initial load and orders by syncedAt
    /// - Reference is needed for .childRemoved which doesn't work on queries
    ///
    /// Threading: All callbacks fire on main thread
    ///
    /// - Parameter userId: The sync group user ID
    private func startListeningForNotifications(userId: String) {
        let notificationsRef = database.reference()
            .child("users")
            .child(userId)
            .child("mirrored_notifications")
        self.notificationsRef = notificationsRef

        // Only listen for new notifications (ordered by syncedAt)
        let query = notificationsRef
            .queryOrdered(byChild: "syncedAt")
            .queryLimited(toLast: 20)
        notificationsQuery = query
        notificationsHandle = query.observe(.childAdded) { [weak self] snapshot, _ in
                guard let self = self,
                      self.isEnabled else { return }

                guard let data = snapshot.value as? [String: Any] else { return }

                if let notification = self.parseNotification(id: snapshot.key, data: data) {
                    // Check if we've already displayed this
                    if !self.displayedNotificationIds.contains(notification.id) {
                        self.displayedNotificationIds.insert(notification.id)

                        DispatchQueue.main.async {
                            // Add to list (keep most recent at top)
                            self.recentNotifications.insert(notification, at: 0)

                            // Limit list size
                            if self.recentNotifications.count > 50 {
                                self.recentNotifications.removeLast()
                            }

                            self.lastSyncTime = Date()
                        }

                        // Show macOS notification
                        self.showNotification(notification)
                    }
                }
            }

        // Also listen for removals
        notificationsRemovedHandle = notificationsRef.observe(.childRemoved) { [weak self] snapshot, _ in
            DispatchQueue.main.async {
                self?.recentNotifications.removeAll { $0.id == snapshot.key }
            }
            let identifier = "mirrored-\(snapshot.key)"
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [identifier])
        }
    }

    /// Removes all Firebase listeners and cleans up handles.
    /// Safe to call even if listeners are not active.
    private func stopListeningForNotifications() {
        guard currentUserId != nil else { return }

        if let handle = notificationsHandle {
            notificationsQuery?.removeObserver(withHandle: handle)
        }
        if let handle = notificationsRemovedHandle {
            notificationsRef?.removeObserver(withHandle: handle)
        }

        notificationsHandle = nil
        notificationsRemovedHandle = nil
        notificationsQuery = nil
        notificationsRef = nil
    }

    // MARK: - Data Parsing

    /// Parses a notification dictionary from Firebase into a MirroredNotification model.
    ///
    /// Required fields: appPackage, appName, title, timestamp
    /// Optional fields: text, appIcon (base64), syncedAt
    ///
    /// App Icon Handling:
    /// - appIcon field contains base64-encoded PNG data
    /// - Decoded to NSImage for display in notification list
    /// - Not included in macOS notification (would require saving to file)
    ///
    /// - Parameters:
    ///   - id: Firebase snapshot key (notification ID)
    ///   - data: Dictionary of notification fields
    /// - Returns: MirroredNotification or nil if required fields missing
    private func parseNotification(id: String, data: [String: Any]) -> MirroredNotification? {
        guard let appPackage = data["appPackage"] as? String,
              let appName = data["appName"] as? String,
              let title = data["title"] as? String,
              let timestamp = data["timestamp"] as? Double else {
            return nil
        }

        let text = data["text"] as? String ?? ""
        let appIconBase64 = data["appIcon"] as? String
        let syncedAt = data["syncedAt"] as? Double ?? Date().timeIntervalSince1970 * 1000

        // Decode icon if available
        var appIcon: NSImage? = nil
        if let iconData = appIconBase64,
           let data = Data(base64Encoded: iconData) {
            appIcon = NSImage(data: data)
        }

        return MirroredNotification(
            id: id,
            appPackage: appPackage,
            appName: appName,
            appIcon: appIcon,
            title: title,
            text: text,
            timestamp: Date(timeIntervalSince1970: timestamp / 1000),
            syncedAt: Date(timeIntervalSince1970: syncedAt / 1000)
        )
    }

    // MARK: - macOS Notification Display

    /// Displays a macOS notification for a mirrored Android notification.
    ///
    /// Notification Content:
    /// - Title: Android app name (e.g., "WhatsApp")
    /// - Subtitle: Notification title (e.g., "John Doe")
    /// - Body: Notification text (e.g., "Hey, how are you?")
    /// - Sound: Default system sound
    ///
    /// Identifier Format: "mirrored-{firebaseKey}"
    /// This allows targeted removal when notification is dismissed on Android.
    ///
    /// - Parameter notification: The MirroredNotification to display
    private func showNotification(_ notification: MirroredNotification) {
        let content = UNMutableNotificationContent()
        content.title = notification.appName
        content.subtitle = notification.title
        content.body = notification.text
        content.sound = .default
        content.categoryIdentifier = "MIRRORED_NOTIFICATION"

        // Add app icon if available
        // Note: UNNotificationAttachment requires a file URL, so we'd need to save the icon first
        // For simplicity, we skip this for now

        let request = UNNotificationRequest(
            identifier: "mirrored-\(notification.id)",
            content: content,
            trigger: nil // Deliver immediately
        )

        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                print("NotificationMirrorService: Error showing notification: \(error)")
            } else {
            }
        }
    }

    // MARK: - Notification Management

    /// Clears all mirrored notifications from Firebase and local state.
    /// Removes the entire mirrored_notifications node for the user.
    ///
    /// This affects all devices - notifications will be removed on Android as well.
    func clearNotifications() {
        guard let userId = currentUserId else { return }

        database.goOnline()

        let notificationsRef = database.reference()
            .child("users")
            .child(userId)
            .child("mirrored_notifications")

        notificationsRef.removeValue { error, _ in
            if let error = error {
                print("NotificationMirrorService: Error clearing notifications: \(error)")
            } else {
                DispatchQueue.main.async {
                    self.recentNotifications.removeAll()
                    self.displayedNotificationIds.removeAll()
                }
            }
        }
    }

    /// Dismisses a single notification from Firebase.
    /// The .childRemoved listener will automatically remove it from recentNotifications
    /// and the macOS notification center.
    ///
    /// - Parameter notification: The notification to dismiss
    func dismissNotification(_ notification: MirroredNotification) {
        guard let userId = currentUserId else { return }

        database.goOnline()

        let notificationRef = database.reference()
            .child("users")
            .child(userId)
            .child("mirrored_notifications")
            .child(notification.id)

        notificationRef.removeValue { error, _ in
            if let error = error {
                print("NotificationMirrorService: Error dismissing notification: \(error)")
            } else {
                DispatchQueue.main.async {
                    self.recentNotifications.removeAll { $0.id == notification.id }
                }
            }
        }
    }

    // MARK: - Settings

    /// Toggles notification mirroring on/off.
    /// Persists setting to UserDefaults.
    ///
    /// - Parameter enabled: Whether to enable notification mirroring
    func setEnabled(_ enabled: Bool) {
        isEnabled = enabled
        UserDefaults.standard.set(enabled, forKey: "notification_mirror_enabled")
    }

    // MARK: - Battery Optimization

    /// Reduces update frequency for battery saving.
    /// Note: Currently a placeholder - Firebase listeners are push-based
    /// and don't have configurable frequency.
    func reduceUpdateFrequency() {
        // Reduce the frequency of notification updates
    }

    /// Pauses notification mirroring by setting isEnabled to false.
    /// Firebase listener remains active but isEnabled check prevents display.
    func pauseMirroring() {
        isEnabled = false
    }

    /// Resumes notification mirroring after a pause.
    /// Re-enables and restarts sync with stored userId.
    func resumeMirroring() {
        isEnabled = true
        if let userId = currentUserId {
            startSync(userId: userId)
        }
    }
}

// MARK: - MirroredNotification Model

/// Model representing a notification mirrored from Android.
///
/// Contains all metadata from the Android notification including:
/// - Source app information (package, name, icon)
/// - Notification content (title, text)
/// - Timestamps (when posted on Android, when synced to Firebase)
struct MirroredNotification: Identifiable {
    let id: String           // Firebase snapshot key
    let appPackage: String   // Android app package name (e.g., "com.whatsapp")
    let appName: String      // Human-readable app name (e.g., "WhatsApp")
    let appIcon: NSImage?    // App icon decoded from base64, optional
    let title: String        // Notification title
    let text: String         // Notification body text
    let timestamp: Date      // When notification was posted on Android
    let syncedAt: Date       // When notification was synced to Firebase

    // MARK: - Computed Properties

    /// Relative time string for UI display (e.g., "2m ago", "1h ago")
    var formattedTime: String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(for: timestamp, relativeTo: Date())
    }
}
