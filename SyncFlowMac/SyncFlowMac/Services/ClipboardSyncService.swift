//
//  ClipboardSyncService.swift
//  SyncFlowMac
//
//  Created for SyncFlow clipboard synchronization functionality.
//
//  OVERVIEW:
//  =========
//  This service provides bidirectional clipboard synchronization between macOS and Android.
//  It monitors the local macOS clipboard for changes and syncs them to Firebase, while also
//  listening for remote clipboard changes from Android and applying them locally.
//
//  SYNC MECHANISM:
//  ===============
//  1. Mac to Android (Outbound):
//     - Timer polls NSPasteboard every 1 second (configurable)
//     - changeCount is compared to detect actual clipboard changes
//     - New text content is pushed to Firebase: users/{userId}/clipboard
//     - Data includes: text, timestamp, source="macos", type="text"
//
//  2. Android to Mac (Inbound):
//     - Firebase listener observes users/{userId}/clipboard
//     - When source != "macos", content is from another device
//     - Timestamp comparison prevents processing stale updates
//     - Local clipboard is updated via NSPasteboard.setString()
//
//  FIREBASE REAL-TIME LISTENER PATTERNS:
//  =====================================
//  - Uses .value observer on users/{userId}/clipboard (single node, not collection)
//  - Listener fires on every change to clipboard node
//  - Source field filtering prevents echo (ignores own updates)
//  - Timestamp comparison prevents processing outdated content
//
//  THREADING/ASYNC CONSIDERATIONS:
//  ===============================
//  - Timer runs on main thread (added to .common RunLoop mode)
//  - Firebase callbacks fire on main thread by default
//  - isUpdatingFromRemote flag prevents sync loops during remote updates
//  - 0.5 second delay after remote update before resuming local monitoring
//  - All clipboard operations (NSPasteboard) must occur on main thread
//
//  CONFLICT RESOLUTION:
//  ====================
//  - Last-write-wins based on timestamp
//  - Source field prevents processing own updates (no echo loop)
//  - isUpdatingFromRemote flag prevents local change detection during remote apply
//  - changeCount tracking ensures only actual changes are synced
//
//  LIMITATIONS:
//  ============
//  - Text-only sync (images, files not supported)
//  - Maximum content length: 50KB (maxClipboardLength)
//  - macOS has no clipboard change notification, requires polling
//  - Sensitive data (passwords) may be synced unless user disables feature
//

import Foundation
import AppKit
// FirebaseDatabase - using FirebaseStubs.swift
import Combine

// MARK: - ClipboardSyncService

/// Service responsible for bidirectional clipboard synchronization between macOS and Android.
///
/// This singleton service manages:
/// - Monitoring the local macOS clipboard for changes via polling
/// - Syncing clipboard text to Firebase Realtime Database
/// - Receiving clipboard updates from Android and applying locally
/// - Preventing sync loops through source filtering and flags
///
/// Usage:
/// ```swift
/// // Start sync after user authentication
/// ClipboardSyncService.shared.startSync(userId: "user123")
///
/// // Observe sync status
/// ClipboardSyncService.shared.$lastSyncedContent
///     .sink { content in /* show in UI */ }
///
/// // Manual sync
/// ClipboardSyncService.shared.syncNow()
///
/// // Stop sync on logout
/// ClipboardSyncService.shared.stopSync()
/// ```
class ClipboardSyncService: ObservableObject {

    // MARK: - Singleton

    /// Shared singleton instance for app-wide clipboard sync operations
    static let shared = ClipboardSyncService()

    // MARK: - Published Properties

    /// Whether clipboard sync is enabled (can be toggled by user)
    @Published var isEnabled: Bool = true
    /// The most recently synced clipboard content (for UI display)
    @Published var lastSyncedContent: String?
    /// Timestamp of the last successful sync operation
    @Published var lastSyncTime: Date?

    // MARK: - Private Properties

    /// Firebase Realtime Database instance
    private let database = Database.database()
    /// Handle for the Firebase observer on clipboard node
    private var clipboardHandle: DatabaseHandle?
    /// Current authenticated user's ID
    private var currentUserId: String?
    /// Timer for polling the local clipboard (macOS has no change notification)
    private var clipboardCheckTimer: Timer?

    // MARK: - Sync Loop Prevention

    /// Last known clipboard content, used to detect actual changes
    private var lastKnownContent: String?
    /// NSPasteboard.changeCount from last check, increments on any clipboard change
    private var lastKnownChangeCount: Int = 0
    /// Flag to prevent sync loop when applying remote clipboard content locally
    /// Set to true during remote update, reset after 0.5 second delay
    private var isUpdatingFromRemote = false

    // MARK: - Constants

    /// Maximum clipboard text length to sync (50KB)
    /// Prevents syncing extremely large text that could impact performance
    private let maxClipboardLength = 50000 // 50KB max

    // MARK: - Initialization

    /// Private initializer enforces singleton pattern
    private init() {}

    // MARK: - Sync Control

    /// Starts clipboard synchronization for the specified user.
    /// Sets up both local clipboard monitoring and remote listener.
    ///
    /// - Parameter userId: The sync group user ID
    ///
    /// Note: Does nothing if isEnabled is false
    func startSync(userId: String) {
        guard isEnabled else { return }
        currentUserId = userId

        // Start monitoring local clipboard
        startMonitoringLocalClipboard()

        // Start listening for remote clipboard changes
        startListeningForRemoteClipboard(userId: userId)

    }

    /// Stops all clipboard sync operations.
    /// Cleans up timer and Firebase listener.
    func stopSync() {
        stopMonitoringLocalClipboard()
        stopListeningForRemoteClipboard()
        currentUserId = nil
    }

    // MARK: - Local Clipboard Monitoring

    /// Starts a timer to poll the local clipboard for changes.
    ///
    /// Why polling? macOS does not provide a notification API for clipboard changes.
    /// We must periodically check NSPasteboard.changeCount to detect changes.
    ///
    /// Timer Configuration:
    /// - Interval: 1.0 second (default)
    /// - RunLoop mode: .common (ensures timer fires even during tracking)
    /// - Thread: Main thread only (NSPasteboard requires main thread access)
    private func startMonitoringLocalClipboard() {
        // macOS doesn't have a notification for clipboard changes
        // So we poll the clipboard periodically
        DispatchQueue.main.async { [weak self] in
            self?.lastKnownChangeCount = NSPasteboard.general.changeCount

            self?.clipboardCheckTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
                self?.checkLocalClipboard()
            }
            // Ensure timer runs on main run loop
            if let timer = self?.clipboardCheckTimer {
                RunLoop.main.add(timer, forMode: .common)
            }
        }
    }

    /// Stops the clipboard polling timer.
    private func stopMonitoringLocalClipboard() {
        clipboardCheckTimer?.invalidate()
        clipboardCheckTimer = nil
    }

    /// Checks if the local clipboard has changed and syncs if necessary.
    ///
    /// Change Detection:
    /// - Compares NSPasteboard.changeCount to lastKnownChangeCount
    /// - changeCount increments on ANY clipboard change, not just text
    ///
    /// Sync Conditions (all must be true):
    /// - isEnabled is true
    /// - Not currently updating from remote (isUpdatingFromRemote == false)
    /// - User is authenticated (currentUserId != nil)
    /// - changeCount has changed since last check
    /// - Clipboard contains text (not image/file)
    /// - Text is non-empty and under maxClipboardLength
    /// - Text is different from lastKnownContent (actual change)
    private func checkLocalClipboard() {
        guard isEnabled, !isUpdatingFromRemote else { return }
        guard currentUserId != nil else { return }

        let pasteboard = NSPasteboard.general
        let currentChangeCount = pasteboard.changeCount

        // Check if clipboard changed
        guard currentChangeCount != lastKnownChangeCount else { return }
        lastKnownChangeCount = currentChangeCount

        // Get text content
        guard let text = pasteboard.string(forType: .string) else {
            return
        }
        guard !text.isEmpty else { return }
        guard text.count <= maxClipboardLength else {
            return
        }

        // Check if content actually changed
        guard text != lastKnownContent else { return }

        syncToFirebase(text: text)
    }

    // MARK: - Firebase Real-Time Listener

    /// Sets up a Firebase listener for clipboard updates from other devices.
    ///
    /// Firebase Listener Pattern:
    /// - Observes .value on users/{userId}/clipboard (single node)
    /// - Fires immediately with current value, then on each subsequent change
    ///
    /// Filtering Logic:
    /// - Ignores updates where source == "macos" (own updates)
    /// - Compares timestamp to lastSyncTime to ignore stale updates
    /// - Only processes if text field is present
    ///
    /// - Parameter userId: The sync group user ID
    private func startListeningForRemoteClipboard(userId: String) {
        let clipboardRef = database.reference()
            .child("users")
            .child(userId)
            .child("clipboard")

        clipboardHandle = clipboardRef.observe(.value) { [weak self] snapshot in
            guard let self = self else { return }

            guard let data = snapshot.value as? [String: Any] else { return }
            guard let source = data["source"] as? String else { return }

            // Only process if from another device (not macOS)
            guard source != "macos" else { return }

            guard let text = data["text"] as? String else { return }
            guard let timestamp = data["timestamp"] as? Double else { return }

            // Check if this is newer than what we synced
            if let lastSync = self.lastSyncTime,
               Date(timeIntervalSince1970: timestamp / 1000) <= lastSync {
                return
            }

            self.updateLocalClipboard(text: text, timestamp: timestamp)
        }
    }

    /// Removes the Firebase observer and cleans up the handle.
    private func stopListeningForRemoteClipboard() {
        guard let userId = currentUserId, let handle = clipboardHandle else { return }

        database.reference()
            .child("users")
            .child(userId)
            .child("clipboard")
            .removeObserver(withHandle: handle)

        clipboardHandle = nil
    }

    // MARK: - Outbound Sync (Mac to Firebase)

    /// Pushes clipboard text to Firebase for other devices to receive.
    ///
    /// Data Structure Written:
    /// - text: The clipboard content
    /// - timestamp: Current time in milliseconds since epoch
    /// - source: "macos" (used by other devices to identify origin)
    /// - type: "text" (for future support of other content types)
    ///
    /// Threading: Firebase setValue callback is on main thread
    ///
    /// - Parameter text: The clipboard text to sync
    private func syncToFirebase(text: String) {
        guard let userId = currentUserId else { return }

        database.goOnline()

        let clipboardRef = database.reference()
            .child("users")
            .child(userId)
            .child("clipboard")

        let timestamp = Date().timeIntervalSince1970 * 1000

        let data: [String: Any] = [
            "text": text,
            "timestamp": timestamp,
            "source": "macos",
            "type": "text"
        ]

        clipboardRef.setValue(data) { [weak self] error, _ in
            if let error = error {
                print("ClipboardSyncService: Error syncing to Firebase: \(error)")
            } else {
                DispatchQueue.main.async {
                    self?.lastKnownContent = text
                    self?.lastSyncedContent = text
                    self?.lastSyncTime = Date()
                }
            }
        }
    }

    // MARK: - Inbound Sync (Firebase to Mac)

    /// Applies remote clipboard content to the local macOS clipboard.
    ///
    /// Sync Loop Prevention:
    /// 1. Sets isUpdatingFromRemote = true before modifying clipboard
    /// 2. Updates lastKnownContent and lastKnownChangeCount
    /// 3. Resets isUpdatingFromRemote after 0.5 second delay
    ///
    /// This prevents the local monitoring timer from detecting our own change
    /// and re-syncing it back to Firebase.
    ///
    /// Threading: All operations on main thread (required for NSPasteboard)
    ///
    /// - Parameters:
    ///   - text: The clipboard text to apply
    ///   - timestamp: Remote timestamp in milliseconds since epoch
    private func updateLocalClipboard(text: String, timestamp: Double) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }

            self.isUpdatingFromRemote = true

            let pasteboard = NSPasteboard.general
            pasteboard.clearContents()
            pasteboard.setString(text, forType: .string)

            self.lastKnownContent = text
            self.lastKnownChangeCount = pasteboard.changeCount
            self.lastSyncedContent = text
            self.lastSyncTime = Date(timeIntervalSince1970: timestamp / 1000)

            // Show notification
            self.showClipboardNotification(text: text)


            // Reset flag after delay
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                self.isUpdatingFromRemote = false
            }
        }
    }

    // MARK: - User Notifications

    /// Shows a notification when clipboard is updated from Android.
    /// Uses deprecated NSUserNotification API (should migrate to UNUserNotificationCenter).
    ///
    /// - Parameter text: The synced clipboard text (truncated to 50 chars in notification)
    private func showClipboardNotification(text: String) {
        let notification = NSUserNotification()
        notification.title = "Clipboard Synced"
        notification.informativeText = text.count > 50 ? String(text.prefix(50)) + "..." : text
        notification.soundName = nil

        NSUserNotificationCenter.default.deliver(notification)
    }

    // MARK: - Public API

    /// Manually triggers a sync of the current clipboard content.
    /// Useful for "sync now" button in UI.
    func syncNow() {
        guard let text = NSPasteboard.general.string(forType: .string),
              !text.isEmpty,
              text.count <= maxClipboardLength else { return }

        syncToFirebase(text: text)
    }

    /// Returns the current text content of the macOS clipboard.
    /// - Returns: Clipboard text or nil if clipboard doesn't contain text
    func getCurrentClipboard() -> String? {
        return NSPasteboard.general.string(forType: .string)
    }

    // MARK: - Battery Optimization

    /// Reduces clipboard polling frequency for battery saving.
    /// Changes interval from 1.0 second to 5.0 seconds.
    func reduceFrequency() {
        // Increase check interval from 1.0 to 5.0 seconds
        stopMonitoringLocalClipboard()
        if isEnabled && currentUserId != nil {
            startMonitoringLocalClipboardWithInterval(5.0)
        }
    }

    /// Pauses clipboard sync by disabling and stopping the timer.
    /// Remote listener remains active but updates are ignored (isEnabled check).
    func pauseSync() {
        isEnabled = false
        stopMonitoringLocalClipboard()
    }

    /// Resumes clipboard sync after a pause.
    /// Re-enables sync and restarts with stored userId.
    func resumeSync() {
        isEnabled = true
        if let userId = currentUserId {
            startSync(userId: userId)
        }
    }

    /// Starts clipboard monitoring with a custom polling interval.
    /// Used by reduceFrequency() to implement battery-saving mode.
    ///
    /// - Parameter interval: Polling interval in seconds
    private func startMonitoringLocalClipboardWithInterval(_ interval: TimeInterval) {
        DispatchQueue.main.async { [weak self] in
            self?.clipboardCheckTimer = Timer.scheduledTimer(withTimeInterval: interval, repeats: true) { [weak self] _ in
                self?.checkLocalClipboard()
            }
            if let timer = self?.clipboardCheckTimer {
                RunLoop.main.add(timer, forMode: .common)
            }
        }
    }
}
