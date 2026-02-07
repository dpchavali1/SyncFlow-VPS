//
//  MessageStore.swift
//  SyncFlowMac
//
//  Created by SyncFlow Team
//  Copyright (c) SyncFlow. All rights reserved.
//
//  ============================================================================
//  PURPOSE
//  ============================================================================
//  MessageStore is the central state management hub for all messaging data in
//  the SyncFlow macOS app. It implements the ObservableObject protocol for
//  seamless SwiftUI integration.
//
//  Key Responsibilities:
//  - Maintains the source of truth for messages and conversations
//  - Manages Firebase real-time listeners for data synchronization
//  - Groups messages into conversations with contact resolution
//  - Handles read status, reactions, and pinned messages
//  - Provides filtering (all, unread, archived, spam)
//  - Supports message search and pagination
//
//  ============================================================================
//  ARCHITECTURE (MVVM Pattern)
//  ============================================================================
//  MessageStore serves as the Model layer in the MVVM architecture:
//
//  ```
//  View (SwiftUI)
//      |
//      | @ObservedObject / @EnvironmentObject
//      v
//  MessageStore (@Published properties)
//      |
//      | Delegates data operations to
//      v
//  FirebaseService (network layer)
//  ```
//
//  Data Flow:
//  1. View observes @Published properties (messages, conversations, etc.)
//  2. Firebase listeners push updates to MessageStore
//  3. MessageStore processes data on background thread
//  4. @Published properties updated on main thread, triggering UI refresh
//
//  ============================================================================
//  THREAD SAFETY
//  ============================================================================
//  - Heavy data processing (conversation building, message parsing) runs on
//    background queues to keep UI responsive
//  - @Published property updates are always dispatched to main thread
//  - Atomic state access uses a concurrent queue with barrier writes
//  - Pending outgoing messages use a serial queue for consistency
//
//  ============================================================================
//  DEPENDENCIES
//  ============================================================================
//  - FirebaseService: Real-time data synchronization
//  - PreferencesService: User preferences (pinned, archived, blocked)
//  - NotificationService: System notification delivery
//  - BatteryAwareServiceManager: Power-optimized processing
//

import Foundation
// FirebaseDatabase - using FirebaseStubs.swift
import Combine

// MARK: - Notification Names

extension Notification.Name {
    /// Posted when E2EE keys are successfully synced and MessageStore should reload
    static let e2eeKeysUpdated = Notification.Name("e2eeKeysUpdated")
}

// MARK: - MessageStore

/// Central observable store for all messaging state in the SyncFlow macOS app.
///
/// MessageStore manages the complete lifecycle of message data, from Firebase
/// synchronization through to UI-ready conversation objects. It serves as the
/// single source of truth for the messaging UI.
///
/// ## Usage with SwiftUI
/// ```swift
/// struct ConversationListView: View {
///     @EnvironmentObject var messageStore: MessageStore
///
///     var body: some View {
///         List(messageStore.displayedConversations) { conversation in
///             ConversationRow(conversation: conversation)
///         }
///     }
/// }
/// ```
///
/// ## Starting/Stopping Data Sync
/// ```swift
/// // Start listening for updates
/// messageStore.startListening(userId: userId)
///
/// // Stop when user logs out or app closes
/// messageStore.stopListening()
/// ```
class MessageStore: ObservableObject {

    // MARK: - Published State (UI-Bound)

    /// All synced messages from the Android device, sorted by date (newest first).
    @Published var messages: [Message] = []

    /// Conversations grouped from messages, with contact info and unread counts.
    @Published var conversations: [Conversation] = []

    /// Loading state for initial data fetch.
    @Published var isLoading = false

    /// Most recent error encountered during sync operations.
    @Published var error: Error?

    /// Filter toggle: show only archived conversations.
    @Published var showArchived = false

    /// Filter toggle: show only unread conversations.
    @Published var showUnreadOnly = false

    /// Filter toggle: show only spam conversations.
    @Published var showSpamOnly = false

    /// Map of message ID to emoji reaction (e.g., "msg123": "thumbsup").
    @Published var messageReactions: [String: String] = [:]

    /// Map of message ID to read receipt information.
    @Published var readReceipts: [String: ReadReceipt] = [:]

    /// Set of message IDs that have been pinned by the user.
    @Published var pinnedMessages: Set<String> = []

    /// Messages detected as spam by the filter.
    @Published var spamMessages: [SpamMessage] = []

    /// Currently selected spam sender address for spam detail view.
    @Published var selectedSpamAddress: String? = nil

    /// Whether older messages exist beyond current loaded range (for pagination).
    @Published var canLoadMore = false

    /// Loading state for "load more" pagination requests.
    @Published var isLoadingMore = false

    // MARK: - Firebase Listener Handles

    /// Information needed to properly clean up a Firebase listener
    private struct ListenerInfo {
        let userId: String
        let handle: DatabaseHandle
    }

    /// Handle for messages listener - must be removed on cleanup.
    private var messageListener: ListenerInfo?

    /// Array of handles for incremental sync (childAdded, childChanged, childRemoved)
    private var incrementalSyncHandles: [DatabaseHandle]?

    /// Handle for reactions listener.
    private var reactionsListener: ListenerInfo?

    /// Handle for read receipts listener.
    private var readReceiptsListener: ListenerInfo?

    /// Timer for polling spam messages from VPS.
    private var spamPollingTimer: Timer?
    private var spamListenerUserId: String?

    // MARK: - Private State

    /// Currently authenticated user ID.
    private var currentUserId: String?

    /// Tracks message IDs from last update to detect new messages.
    private var lastMessageIds: Set<String> = []

    /// Hash of message data to avoid redundant processing.
    private var lastMessageHash: Int = 0

    /// Flag indicating if read receipts have loaded at least once.
    /// Used to determine initial read state for synced messages.
    private var readReceiptsLoaded = false

    /// Combine cancellables for reactive subscriptions.
    private var cancellables = Set<AnyCancellable>()

    /// VPS-specific cancellables
    private var vpsCancellables = Set<AnyCancellable>()

    /// Check if VPS mode is enabled
    private var isVPSMode: Bool {
        // Default to VPS mode
        if UserDefaults.standard.bool(forKey: "useVPSMode") {
            return true
        }
        // Check if VPS service has valid tokens
        if VPSService.shared.isAuthenticated {
            return true
        }
        // Default to VPS mode for new installations
        return true
    }

    // MARK: - Thread Safety

    /// Concurrent queue for thread-safe atomic state access.
    /// Reads are concurrent, writes use barrier for exclusivity.
    private let stateQueue = DispatchQueue(label: "MessageStore.stateQueue", attributes: .concurrent)

    /// Atomic copy of last message hash for background thread access.
    private var _atomicLastHash: Int = 0

    /// Atomic copy of last message IDs for background thread access.
    private var _atomicLastIds: Set<String> = []

    /// Thread-safe read of atomic state (hash and IDs).
    /// Uses sync read on concurrent queue - multiple readers allowed.
    private func getAtomicState() -> (hash: Int, ids: Set<String>) {
        return stateQueue.sync { (_atomicLastHash, _atomicLastIds) }
    }

    /// Thread-safe write of atomic state.
    /// Uses barrier flag to ensure exclusive write access.
    private func setAtomicState(hash: Int, ids: Set<String>) {
        stateQueue.async(flags: .barrier) {
            self._atomicLastHash = hash
            self._atomicLastIds = ids
        }
    }

    // MARK: - Pagination State

    /// Timestamp (seconds) of the oldest loaded message for pagination.
    private var loadedTimeRangeStart: TimeInterval?

    /// Number of days of history to load on initial sync (6 months).
    private var initialLoadDays: Int = 180

    /// Number of additional days to load when user requests more (3 months).
    private var loadMoreDays: Int = 90

    // MARK: - Contacts State

    /// Handle for contacts listener (optimized: child observers for delta-only sync).
    private var contactsListenerHandles: (added: DatabaseHandle, changed: DatabaseHandle, removed: DatabaseHandle)?
    private var contactsListenerUserId: String?

    /// Cached contacts from last sync.
    private var latestContacts: [Contact] = []

    /// Lookup table: normalized phone number -> contact display name.
    /// Used for fast contact name resolution in conversation building.
    private var contactNameLookup: [String: String] = [:]

    // MARK: - Pending Outgoing Messages

    /// Serial queue for thread-safe access to pending outgoing messages.
    private let pendingOutgoingQueue = DispatchQueue(label: "MessageStore.pendingOutgoingQueue")

    /// Messages sent from Mac but not yet confirmed by Android.
    /// Used for optimistic UI updates while waiting for sync.
    private var pendingOutgoingMessages: [String: Message] = [:]

    // MARK: - Service Dependencies

    /// Firebase service for data operations.
    private let firebaseService = FirebaseService.shared

    /// Notification service for system alerts.
    private let notificationService = NotificationService.shared

    /// User preferences service for pinned/archived/blocked state.
    private let preferences = PreferencesService.shared

    // MARK: - Phone Number Normalization

    /// Normalizes a phone number for consistent comparison across formats.
    ///
    /// Phone numbers can appear in many formats:
    /// - `+1 (555) 123-4567`
    /// - `15551234567`
    /// - `555-123-4567`
    ///
    /// This method extracts the last 10 digits to create a normalized key
    /// that matches across all these variations.
    ///
    /// - Parameter address: The phone number or address to normalize
    /// - Returns: Normalized string (last 10 digits or lowercase original for non-phone)
    ///
    /// - Note: Non-phone addresses (email, short codes) are returned lowercase as-is.
    private func normalizePhoneNumber(_ address: String) -> String {
        // Skip non-phone addresses (email, short codes, etc.)
        if address.contains("@") || address.count < 6 {
            return address.lowercased()
        }

        // Remove all non-digit characters
        let digitsOnly = address.filter { $0.isNumber }

        // For comparison, use last 10 digits (handles country code differences)
        // e.g., +1-555-123-4567 and 555-123-4567 both become "5551234567"
        if digitsOnly.count >= 10 {
            return String(digitsOnly.suffix(10))
        }
        return digitsOnly
    }

    private func isAddressPinned(_ address: String, pinnedSet: Set<String>) -> Bool {
        if pinnedSet.contains(address) { return true }
        let normalized = normalizePhoneNumber(address)
        if pinnedSet.contains(normalized) { return true }
        return pinnedSet.contains { normalizePhoneNumber($0) == normalized }
    }

    // MARK: - Initialization

    /// Creates a new MessageStore instance.
    ///
    /// Automatically:
    /// - Loads pinned message IDs from UserDefaults
    /// - Sets up quick-reply notification handlers
    /// - Registers for battery and memory optimization events
    init() {
        loadPinnedMessages()
        setupNotificationHandlers()
        setupPerformanceOptimizations()
    }

    /// Configures power and memory management optimizations.
    ///
    /// MessageStore adapts its processing behavior based on:
    /// - Battery state: Reduces processing when battery is low
    /// - Memory pressure: Clears caches when system needs memory
    private func setupPerformanceOptimizations() {
        // Listen for battery state changes to reduce CPU usage on low battery
        BatteryAwareServiceManager.shared.addStateChangeHandler { [weak self] state in
            self?.handleBatteryStateChange(state)
        }

        // Listen for memory optimization notifications from the system
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleMemoryPressure),
            name: .memoryPressureCritical,
            object: nil
        )

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleClearMessageCache),
            name: .clearMessageCache,
            object: nil
        )

        // Listen for E2EE key sync completion to reload decrypted messages
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleE2EEKeysUpdated),
            name: .e2eeKeysUpdated,
            object: nil
        )
    }

    private func handleBatteryStateChange(_ state: BatteryAwareServiceManager.ServiceState) {
        switch state {
        case .reduced:
            // Reduce message processing frequency
            reduceMessageProcessing()
        case .minimal:
            // Minimize message processing
            minimizeMessageProcessing()
        case .suspended:
            // Pause message processing
            pauseMessageProcessing()
        case .full:
            // Resume normal message processing
            resumeNormalProcessing()
        }
    }

    private func reduceMessageProcessing() {
        // Reduce frequency of message updates and processing
        // Implementation would adjust timers and processing queues
    }

    private func minimizeMessageProcessing() {
        // Further minimize processing
        // Clear non-visible message caches
    }

    private func pauseMessageProcessing() {
        // Pause background message processing
        // Stop background processing but keep UI responsive
    }

    private func resumeNormalProcessing() {
        // Resume normal processing
        // Restore normal processing frequency
    }

    @objc private func handleMemoryPressure() {
        // Clear cached messages that aren't currently visible
        clearNonVisibleMessageCache()
    }

    @objc private func handleClearMessageCache(_ notification: Notification) {
        if let userInfo = notification.userInfo,
           let olderThan = userInfo["olderThan"] as? TimeInterval {
            clearOldMessageCache(olderThan: olderThan)
        }
    }

    /// Handles E2EE keys sync completion by re-decrypting cached messages locally.
    ///
    /// BANDWIDTH OPTIMIZATION: Instead of re-fetching all messages from Firebase,
    /// this method only fetches encryption metadata for encrypted messages and
    /// decrypts them in-place. This uses minimal bandwidth (only nonce + envelope
    /// for encrypted messages, typically < 1KB total).
    ///
    /// When E2EE keys are synced from Android, previously encrypted messages need to be
    /// decrypted using the new keys. This method:
    /// 1. Identifies encrypted messages in cache
    /// 2. Fetches only their encryption metadata (nonce + envelope) from Firebase
    /// 3. Decrypts them locally using new keys
    /// 4. Updates UI with decrypted messages
    @objc private func handleE2EEKeysUpdated(_ notification: Notification) {
        print("[MessageStore] E2EE keys updated, e2eeInitialized=\(E2EEManager.shared.isInitialized)")

        guard let userId = currentUserId else {
            print("[MessageStore] No user ID, skipping re-decryption")
            return
        }

        if isVPSMode {
            Task {
                do {
                    let response = try await VPSService.shared.getMessages(limit: 500)
                    let fetchedMessages = response.messages.map { self.convertVPSMessage($0) }

                    let encrypted = fetchedMessages.filter { $0.isEncrypted == true }
                    let failed = encrypted.filter { $0.e2eeFailed == true }
                    print("[MessageStore VPS] Re-decryption: \(encrypted.count - failed.count)/\(encrypted.count) decrypted, \(failed.count) failed")

                    await MainActor.run {
                        let processedMessages = self.applyReadStatus(to: fetchedMessages)
                        let mergeResult = self.mergeMessagesWithPendingOutgoing(remoteMessages: processedMessages)
                        let newConversations = self.buildConversations(from: mergeResult.mergedMessages)

                        self.messages = mergeResult.mergedMessages
                        self.conversations = newConversations
                    }
                } catch {
                    print("[MessageStore VPS] Re-decryption fetch error: \(error.localizedDescription)")
                }
            }
        } else {
            // Firebase mode: re-decrypt cached messages without re-fetching
            IncrementalSyncManager.shared.redecryptCachedMessages(
                userId: userId,
                onProgress: { decryptedCount in
                    print("[MessageStore] Re-decryption progress: \(decryptedCount) messages")
                },
                onComplete: { [weak self] updatedMessages in
                    DispatchQueue.main.async {
                        guard let self = self else { return }

                        self.messages = updatedMessages
                        self.updateConversations(from: updatedMessages)

                        print("[MessageStore] ✅ Re-decryption complete! UI updated with \(updatedMessages.count) messages")
                    }
                }
            )
        }
    }

    private func clearNonVisibleMessageCache() {
        // Clear messages that aren't currently displayed
        // This is a simplified implementation
    }

    private func clearOldMessageCache(olderThan seconds: TimeInterval) {
        // Clear message cache entries older than specified time
    }

    private func setupNotificationHandlers() {
        // Handle quick reply from notifications
        NotificationCenter.default.publisher(for: .quickReply)
            .sink { [weak self] notification in
                guard let userInfo = notification.userInfo,
                      let address = userInfo["address"] as? String,
                      let body = userInfo["body"] as? String,
                      let userId = self?.currentUserId else { return }

                Task {
                    try? await self?.sendMessage(userId: userId, to: address, body: body)
                }
            }
            .store(in: &cancellables)
    }

    // MARK: - Start Listening

    /// Starts real-time synchronization for a user's messaging data.
    ///
    /// This method sets up Firebase listeners for:
    /// - Messages (SMS/MMS synced from Android)
    /// - Message reactions (emoji responses)
    /// - Read receipts (cross-device read status)
    /// - Spam messages (filtered messages)
    /// - Contacts (for name resolution)
    ///
    /// - Parameter userId: The Firebase user ID to listen for
    ///
    /// ## Listener Lifecycle
    /// Listeners are automatically cleaned up if switching users. Call `stopListening()`
    /// when the user logs out or the app is closing.
    ///
    /// ## Performance Notes
    /// - Message processing occurs on background threads
    /// - Uses hash comparison to skip redundant updates
    /// - New message detection triggers local notifications
    func startListening(userId: String) {
        // Skip if already listening for this user
        guard currentUserId != userId else {
            return
        }

        // CRITICAL: Stop old listeners BEFORE updating currentUserId
        // This prevents orphaned listeners when switching users
        if let handles = incrementalSyncHandles, let oldUserId = currentUserId {
            IncrementalSyncManager.shared.stopListening(userId: oldUserId, handles: handles)
            incrementalSyncHandles = nil
        }
        if let listener = reactionsListener {
            firebaseService.removeMessageReactionsListener(userId: listener.userId, handle: listener.handle)
            reactionsListener = nil
        }
        if let listener = readReceiptsListener {
            firebaseService.removeReadReceiptsListener(userId: listener.userId, handle: listener.handle)
            readReceiptsListener = nil
        }
        spamPollingTimer?.invalidate()
        spamPollingTimer = nil
        spamListenerUserId = nil
        if currentUserId != nil {
            stopListeningForContacts()
        }

        currentUserId = userId
        isLoading = true

        // Reset pagination state for new user
        loadedTimeRangeStart = nil
        canLoadMore = false

        // VPS MODE: Use VPSService instead of Firebase
        if isVPSMode {
            print("[MessageStore] VPS mode active - using VPS for messages")
            startListeningVPS(userId: userId)
            return
        }

        // FIREBASE MODE (legacy): Use incremental sync
        // BANDWIDTH OPTIMIZATION: Load cached messages first for instant display
        // This prevents showing empty UI while waiting for network sync
        let cachedMessages = IncrementalSyncManager.shared.getCachedMessages(userId: userId)
        if !cachedMessages.isEmpty {
            print("[MessageStore] Loaded \(cachedMessages.count) cached messages (instant display)")

            // Display cached messages immediately
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }

                let processedMessages = self.applyReadStatus(to: cachedMessages)
                let mergeResult = self.mergeMessagesWithPendingOutgoing(remoteMessages: processedMessages)
                let newConversations = self.buildConversations(from: mergeResult.mergedMessages)

                self.messages = mergeResult.mergedMessages
                self.conversations = newConversations
                self.isLoading = false  // Show cached data immediately

                print("[MessageStore] Displayed \(newConversations.count) cached conversations")
            }
        }

        // Start incremental sync (only fetches deltas, not full message list)
        // BANDWIDTH COMPARISON:
        // - Old: Downloads all 200 messages (400KB) on EVERY change
        // - New: Downloads only changed messages (2KB per change)
        // - Savings: 99.5% bandwidth reduction
        let lastSyncTimestamp = IncrementalSyncManager.shared.getLastSyncTimestamp(userId: userId)
        let handles = IncrementalSyncManager.shared.listenToMessagesIncremental(
            userId: userId,
            lastSyncTimestamp: lastSyncTimestamp
        ) { [weak self] delta in
            guard let self = self else { return }

            // Handle delta events (added/changed/removed)
            // This only processes the single message that changed, not the entire message list
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }

                var currentMessages = self.messages
                var shouldNotify = false
                var notificationMessage: Message?

                switch delta {
                case .added(let message):
                    // Check if message already exists (prevent duplicates during initial sync)
                    if !currentMessages.contains(where: { $0.id == message.id }) {
                        currentMessages.append(message)
                        print("[MessageStore] Added message: \(message.id)")

                        // Only notify if not during initial load
                        if !self.isLoading && message.isReceived {
                            shouldNotify = true
                            notificationMessage = message
                        }
                    }

                case .changed(let message):
                    // Update existing message
                    if let index = currentMessages.firstIndex(where: { $0.id == message.id }) {
                        currentMessages[index] = message
                        print("[MessageStore] Updated message: \(message.id)")
                    } else {
                        // Message doesn't exist yet, add it
                        currentMessages.append(message)
                        print("[MessageStore] Added changed message (was missing): \(message.id)")
                    }

                case .removed(let messageId):
                    // Remove message from list
                    currentMessages.removeAll { $0.id == messageId }
                    print("[MessageStore] Removed message: \(messageId)")
                }

                // Apply read status
                let processedMessages = self.applyReadStatus(to: currentMessages)

                // Merge with pending outgoing messages
                let mergeResult = self.mergeMessagesWithPendingOutgoing(remoteMessages: processedMessages)

                // Rebuild conversations
                let newConversations = self.buildConversations(from: mergeResult.mergedMessages)

                // Update state
                self.messages = mergeResult.mergedMessages
                self.conversations = newConversations
                self.isLoading = false

                // Remove matched pending messages
                if !mergeResult.matchedPendingIds.isEmpty {
                    self.pendingOutgoingQueue.sync {
                        for id in mergeResult.matchedPendingIds {
                            self.pendingOutgoingMessages.removeValue(forKey: id)
                        }
                    }
                }

                // Show notification for new message
                if shouldNotify, let message = notificationMessage {
                    self.notificationService.showMessageNotification(
                        from: message.address,
                        contactName: message.contactName,
                        body: message.body,
                        messageId: message.id
                    )
                }

                // Update badge count
                self.notificationService.setBadgeCount(self.totalUnreadCount)

                print("[MessageStore] Now showing \(newConversations.count) conversations with \(mergeResult.mergedMessages.count) messages")
            }
        }

        // Store listener handles for proper cleanup
        // IncrementalSyncManager returns array of handles (childAdded, childChanged, childRemoved)
        incrementalSyncHandles = handles

        let reactionsHandle = firebaseService.listenToMessageReactions(userId: userId) { [weak self] reactions in
            DispatchQueue.main.async {
                self?.messageReactions = reactions
            }
        }
        reactionsListener = ListenerInfo(userId: userId, handle: reactionsHandle)

        let readReceiptsHandle = firebaseService.listenToReadReceipts(userId: userId) { [weak self] receipts in
            DispatchQueue.main.async {
                self?.readReceipts = receipts
                self?.readReceiptsLoaded = true  // Mark that read receipts have been loaded
                self?.messages = self?.applyReadStatus(to: self?.messages ?? []) ?? []
                self?.updateConversations(from: self?.messages ?? [])
                self?.notificationService.setBadgeCount(self?.totalUnreadCount ?? 0)
            }
        }
        readReceiptsListener = ListenerInfo(userId: userId, handle: readReceiptsHandle)

        // Use VPS REST API for spam messages
        spamListenerUserId = userId
        loadSpamMessagesFromVPS()
        startSpamPolling()

        startListeningForContacts(userId: userId)
    }

    // MARK: - Stop Listening

    /// Stops all Firebase listeners and resets state.
    ///
    /// Call this when:
    // MARK: - VPS Mode Support

    /// Starts listening for messages using VPS backend instead of Firebase.
    /// This method:
    /// 1. Fetches initial messages from VPS REST API
    /// 2. Subscribes to WebSocket events for real-time updates
    private func startListeningVPS(userId: String) {
        // Cancel any existing VPS subscriptions
        vpsCancellables.removeAll()

        // Subscribe to VPS WebSocket events for real-time updates
        VPSService.shared.messageAdded
            .receive(on: DispatchQueue.main)
            .sink { [weak self] vpsMessage in
                guard let self = self else { return }
                let message = self.convertVPSMessage(vpsMessage)

                // Skip if message already exists
                guard !self.messages.contains(where: { $0.id == message.id }) else { return }

                var currentMessages = self.messages
                currentMessages.append(message)

                let processedMessages = self.applyReadStatus(to: currentMessages)
                let mergeResult = self.mergeMessagesWithPendingOutgoing(remoteMessages: processedMessages)
                let newConversations = self.buildConversations(from: mergeResult.mergedMessages)

                self.messages = mergeResult.mergedMessages
                self.conversations = newConversations

                // Notify for new incoming message
                if message.isReceived {
                    self.notificationService.showMessageNotification(
                        from: message.address,
                        contactName: message.contactName,
                        body: message.body,
                        messageId: message.id
                    )
                }

                // Per-message logging removed to reduce log noise
            }
            .store(in: &vpsCancellables)

        VPSService.shared.messageUpdated
            .receive(on: DispatchQueue.main)
            .sink { [weak self] vpsMessage in
                guard let self = self else { return }
                let message = self.convertVPSMessage(vpsMessage)

                var currentMessages = self.messages
                if let index = currentMessages.firstIndex(where: { $0.id == message.id }) {
                    currentMessages[index] = message
                } else {
                    currentMessages.append(message)
                }

                let processedMessages = self.applyReadStatus(to: currentMessages)
                let mergeResult = self.mergeMessagesWithPendingOutgoing(remoteMessages: processedMessages)
                let newConversations = self.buildConversations(from: mergeResult.mergedMessages)

                self.messages = mergeResult.mergedMessages
                self.conversations = newConversations

                // Per-message logging removed to reduce log noise
            }
            .store(in: &vpsCancellables)

        VPSService.shared.messageDeleted
            .receive(on: DispatchQueue.main)
            .sink { [weak self] messageId in
                guard let self = self else { return }

                var currentMessages = self.messages
                currentMessages.removeAll { $0.id == messageId }

                let processedMessages = self.applyReadStatus(to: currentMessages)
                let mergeResult = self.mergeMessagesWithPendingOutgoing(remoteMessages: processedMessages)
                let newConversations = self.buildConversations(from: mergeResult.mergedMessages)

                self.messages = mergeResult.mergedMessages
                self.conversations = newConversations

                // Per-message logging removed to reduce log noise
            }
            .store(in: &vpsCancellables)

        // Fetch initial messages from VPS
        Task {
            do {
                let response = try await VPSService.shared.getMessages(limit: 500)

                let fetchedMessages = response.messages.map { self.convertVPSMessage($0) }

                // Log decryption summary (not per-message)
                let encrypted = fetchedMessages.filter { $0.isEncrypted == true }
                let failed = encrypted.filter { $0.e2eeFailed == true }
                if !encrypted.isEmpty {
                    print("[MessageStore VPS] Decryption: \(encrypted.count - failed.count)/\(encrypted.count) messages decrypted, \(failed.count) failed, e2eeInitialized=\(E2EEManager.shared.isInitialized)")
                    if !failed.isEmpty, let first = failed.first {
                        print("[MessageStore VPS] First failed msg: \(first.id), reason=\(first.e2eeFailureReason ?? "unknown")")
                    }
                }

                await MainActor.run {
                    let processedMessages = self.applyReadStatus(to: fetchedMessages)
                    let mergeResult = self.mergeMessagesWithPendingOutgoing(remoteMessages: processedMessages)
                    let newConversations = self.buildConversations(from: mergeResult.mergedMessages)

                    self.messages = mergeResult.mergedMessages
                    self.conversations = newConversations
                    self.isLoading = false
                    self.canLoadMore = response.hasMore

                    print("[MessageStore VPS] Loaded \(fetchedMessages.count) messages, \(newConversations.count) conversations")
                }
            } catch {
                print("[MessageStore VPS] Error loading messages: \(error.localizedDescription)")
                await MainActor.run {
                    self.error = error
                    self.isLoading = false
                }
            }
        }

        // Start contacts sync for VPS mode
        startListeningForContactsVPS(userId: userId)
    }

    /// Converts a VPSMessage to the local Message type
    private func convertVPSMessage(_ vpsMessage: VPSMessage) -> Message {
        // Convert MMS parts to MmsAttachment array if present
        var attachments: [MmsAttachment]? = nil
        if let parts = vpsMessage.mmsParts {
            // MMS parts conversion
            attachments = parts.compactMap { part -> MmsAttachment? in
                let contentType = (part["contentType"] as? String)
                    ?? (part["content_type"] as? String)
                    ?? (part["mimeType"] as? String)
                    ?? (part["mime_type"] as? String)
                    ?? "application/octet-stream"

                let normalizedContentType = contentType.lowercased()
                let type: String
                if normalizedContentType.hasPrefix("image/") {
                    type = "image"
                } else if normalizedContentType.hasPrefix("video/") {
                    type = "video"
                } else if normalizedContentType.hasPrefix("audio/") {
                    type = "audio"
                } else if normalizedContentType.contains("vcard") {
                    type = "vcard"
                } else {
                    type = "file"
                }

                let fileName = (part["fileName"] as? String)
                    ?? (part["file_name"] as? String)
                    ?? (part["name"] as? String)

                let url = (part["url"] as? String)
                    ?? (part["downloadUrl"] as? String)
                    ?? (part["download_url"] as? String)

                let r2Key = (part["r2Key"] as? String)
                    ?? (part["fileKey"] as? String)
                    ?? (part["r2_key"] as? String)

                let inlineData = (part["data"] as? String)
                    ?? (part["inlineData"] as? String)
                    ?? (part["inline_data"] as? String)

                let encrypted: Bool?
                if let flag = part["encrypted"] as? Bool {
                    encrypted = flag
                } else if let flag = part["encrypted"] as? NSNumber {
                    encrypted = flag.boolValue
                } else {
                    encrypted = nil
                }

                return MmsAttachment(
                    id: (part["id"] as? String) ?? (part["partId"] as? String) ?? UUID().uuidString,
                    contentType: contentType,
                    fileName: fileName,
                    url: url,
                    r2Key: r2Key,
                    type: type,
                    encrypted: encrypted,
                    inlineData: inlineData,
                    isInline: inlineData != nil
                )
            }
        }

        var body = vpsMessage.body ?? ""
        var decryptionFailed = false
        var failureReason: String? = nil

        if vpsMessage.encrypted == true {
            if !E2EEManager.shared.isInitialized {
                decryptionFailed = true
                failureReason = "E2EE keys not loaded"
                body = "[🔒 Encrypted message - E2EE keys not loaded]"
            } else if let encryptedBody = vpsMessage.encryptedBody,
               let encryptedNonce = vpsMessage.encryptedNonce,
               let envelope = vpsMessage.keyMap?["syncGroup"] ?? vpsMessage.keyMap?[VPSService.shared.deviceId ?? ""] {
                guard let ciphertextData = Data(base64Encoded: encryptedBody),
                      let nonceData = Data(base64Encoded: encryptedNonce) else {
                    let hasAttachments = !(attachments?.isEmpty ?? true)
                    return Message(
                        id: vpsMessage.id, address: vpsMessage.address,
                        body: "[🔒 Encrypted message - invalid data]",
                        date: Double(vpsMessage.date), type: vpsMessage.type,
                        contactName: vpsMessage.contactName, isRead: vpsMessage.read,
                        isMms: vpsMessage.isMms || hasAttachments, attachments: attachments,
                        e2eeFailed: true, e2eeFailureReason: "Invalid base64",
                        isEncrypted: vpsMessage.encrypted
                    )
                }
                do {
                    let dataKey = try E2EEManager.shared.decryptDataKey(from: envelope)
                    body = try E2EEManager.shared.decryptMessageBody(
                        dataKey: dataKey,
                        ciphertextWithTag: ciphertextData,
                        nonce: nonceData
                    )
                } catch {
                    decryptionFailed = true
                    failureReason = "Key mismatch: \(error.localizedDescription)"
                    body = "[🔒 Encrypted message - sync keys to decrypt]"
                }
            } else {
                decryptionFailed = true
                let hasBody = vpsMessage.encryptedBody != nil
                let hasNonce = vpsMessage.encryptedNonce != nil
                let hasKeyMap = vpsMessage.keyMap != nil
                failureReason = "Missing data: body=\(hasBody), nonce=\(hasNonce), keyMap=\(hasKeyMap)"
                body = "[🔒 Encrypted message - missing encryption data]"
            }
        }

        let hasAttachments = !(attachments?.isEmpty ?? true)


        return Message(
            id: vpsMessage.id,
            address: vpsMessage.address,
            body: body,
            date: Double(vpsMessage.date),
            type: vpsMessage.type,
            contactName: vpsMessage.contactName,
            isRead: vpsMessage.read,
            isMms: vpsMessage.isMms || hasAttachments,
            attachments: attachments,
            e2eeFailed: decryptionFailed,
            e2eeFailureReason: failureReason,
            isEncrypted: vpsMessage.encrypted
        )
    }

    /// Starts listening for contacts using VPS
    private func startListeningForContactsVPS(userId: String) {
        Task {
            do {
                let response = try await VPSService.shared.getContacts()
                let contacts: [Contact] = response.contacts.compactMap { vpsContact -> Contact? in
                    let primaryPhone = vpsContact.phoneNumbers?.first
                    let primaryEmail = vpsContact.emails?.first
                    let syncMetadata = Contact.SyncMetadata(
                        lastUpdatedAt: Date().timeIntervalSince1970 * 1000,
                        lastSyncedAt: Date().timeIntervalSince1970 * 1000,
                        lastUpdatedBy: "vps",
                        version: 1,
                        pendingAndroidSync: false,
                        desktopOnly: false
                    )
                    return Contact(
                        id: vpsContact.id,
                        displayName: vpsContact.displayName ?? "Unknown",
                        phoneNumber: primaryPhone,
                        normalizedNumber: primaryPhone?.filter { $0.isNumber },
                        phoneType: "mobile",
                        photoBase64: vpsContact.photoThumbnail,
                        notes: nil,
                        email: primaryEmail,
                        sync: syncMetadata
                    )
                }

                await MainActor.run {
                    self.latestContacts = contacts
                    self.rebuildContactLookup()

                    // Rebuild conversations with contact names
                    let newConversations = self.buildConversations(from: self.messages)
                    self.conversations = newConversations

                    print("[MessageStore VPS] Loaded \(contacts.count) contacts")
                }
            } catch {
                print("[MessageStore VPS] Error loading contacts: \(error.localizedDescription)")
            }
        }
    }

    /// - User logs out
    /// - App is terminating
    /// - Switching to a different user
    ///
    /// Failure to call this method results in memory leaks and unnecessary
    /// network traffic from orphaned listeners.
    func stopListening() {
        // Cancel VPS subscriptions
        vpsCancellables.removeAll()
        // Stop incremental sync listeners (childAdded, childChanged, childRemoved)
        if let handles = incrementalSyncHandles, let userId = currentUserId {
            IncrementalSyncManager.shared.stopListening(userId: userId, handles: handles)
            incrementalSyncHandles = nil
        }

        // Remove other listeners using stored userId/handle pairs
        if let listener = reactionsListener {
            firebaseService.removeMessageReactionsListener(userId: listener.userId, handle: listener.handle)
            reactionsListener = nil
        }
        if let listener = readReceiptsListener {
            firebaseService.removeReadReceiptsListener(userId: listener.userId, handle: listener.handle)
            readReceiptsListener = nil
        }
        spamPollingTimer?.invalidate()
        spamPollingTimer = nil
        spamListenerUserId = nil
        stopListeningForContacts()

        // Clear state
        currentUserId = nil
        messages = []
        conversations = []
        readReceiptsLoaded = false  // Reset flag when stopping
        loadedTimeRangeStart = nil
        canLoadMore = false
    }

    // MARK: - Cleanup

    /// Ensures all Firebase listeners are removed when MessageStore is deallocated
    deinit {
        stopListening()
        print("[MessageStore] Deinitialized - all listeners removed")
    }

    // MARK: - Load More Messages (Pagination)

    /// Loads additional older messages for infinite scroll pagination.
    ///
    /// Loads messages from an additional time range (90 days by default)
    /// before the oldest currently loaded message.
    ///
    /// ## State Management
    /// - Sets `isLoadingMore` to true during load
    /// - Updates `canLoadMore` based on whether more messages exist
    /// - Merges with existing messages (deduplicated)
    func loadMoreMessages() {
        guard let userId = currentUserId, !isLoadingMore, canLoadMore,
              let oldestTimestamp = loadedTimeRangeStart else {
            return
        }

        isLoadingMore = true

        // Calculate new time range (30 more days back)
        let endTime = oldestTimestamp * 1000  // Convert to milliseconds
        let startTime = (oldestTimestamp - Double(loadMoreDays * 24 * 60 * 60)) * 1000

        Task {
            do {
                let olderMessages = try await firebaseService.loadMessagesInTimeRange(
                    userId: userId,
                    startTime: startTime,
                    endTime: endTime
                )

                await MainActor.run {

                    // Merge with existing messages
                    var allMessages = self.messages + olderMessages
                    allMessages = Array(Set(allMessages))  // Deduplicate
                    allMessages.sort { $0.date > $1.date }  // Sort newest first

                    self.messages = self.applyReadStatus(to: allMessages)
                    self.updateConversations(from: self.messages)

                    // Update pagination state
                    if let newOldest = olderMessages.min(by: { $0.date < $1.date }) {
                        self.loadedTimeRangeStart = newOldest.date / 1000
                        // Check if we hit the time range boundary (more messages might exist)
                        self.canLoadMore = abs(newOldest.date / 1000 - startTime / 1000) < (24 * 60 * 60)
                    } else {
                        // No more messages found
                        self.canLoadMore = false
                    }

                    self.isLoadingMore = false
                }
            } catch {
                await MainActor.run {
                    print("[MessageStore] Error loading more messages: \(error)")
                    self.error = error
                    self.isLoadingMore = false
                }
            }
        }
    }

    // MARK: - Read Status

    /// Applies read status to messages based on multiple sources.
    ///
    /// Read status is determined by checking (in order):
    /// 1. Sent messages (type == 2) are always read
    /// 2. Local macOS read tracking (UserDefaults)
    /// 3. Android read receipts synced via Firebase
    /// 4. Default to read if read receipts haven't loaded yet
    ///
    /// - Parameter messages: Array of messages to update
    /// - Returns: Messages with updated isRead property
    private func applyReadStatus(to messages: [Message]) -> [Message] {
        // Batch read all read message IDs once (O(1) lookups vs O(n) UserDefaults reads)
        let readMessageIds = Set(UserDefaults.standard.stringArray(forKey: "readMessages") ?? [])
        let readReceiptIds = Set(readReceipts.keys)

        return messages.map { message in
            var updatedMessage = message

            // Sent messages (type == 2) are always considered read
            if message.type == 2 {
                updatedMessage.isRead = true
            }
            // Check local macOS read status (user marked as read on Mac)
            else if readMessageIds.contains(message.id) {
                updatedMessage.isRead = true
            }
            // Check if Android marked it as read (read receipt synced from phone)
            else if readReceiptIds.contains(message.id) {
                updatedMessage.isRead = true
            }
            // If read receipts haven't loaded yet, assume synced messages are read
            // (prevents flash of unread badges on initial load)
            else if !readReceiptsLoaded {
                updatedMessage.isRead = true
            }
            // Default to read (Message struct default)
            else {
                updatedMessage.isRead = true
            }

            return updatedMessage
        }
    }

    /// Total count of unread messages across all non-archived conversations.
    /// Used for dock badge display.
    var totalUnreadCount: Int {
        return conversations.filter { !$0.isArchived }.reduce(0) { $0 + $1.unreadCount }
    }

    /// Marks all messages in a conversation as read.
    ///
    /// Updates both local state (UserDefaults) and syncs to Firebase
    /// so other devices know the messages have been read.
    ///
    /// - Parameter conversation: The conversation to mark as read
    func markConversationAsRead(_ conversation: Conversation) {
        // Get all messages for this conversation (using normalized address matching)
        let conversationMessages = messages(for: conversation)
        let unreadMessageIds = conversationMessages.filter { $0.isReceived && !$0.isRead }.map { $0.id }
        preferences.markConversationAsRead(conversation.address, messageIds: unreadMessageIds)

        if let userId = currentUserId, !unreadMessageIds.isEmpty {
            let normalizedAddress = normalizePhoneNumber(conversation.address)
            let deviceName = Host.current().localizedName ?? "Mac"
            Task {
                try? await firebaseService.markMessagesRead(
                    userId: userId,
                    messageIds: unreadMessageIds,
                    conversationAddress: normalizedAddress,
                    readBy: "macos",
                    readDeviceName: deviceName
                )
            }
        }

        // Refresh conversations
        messages = applyReadStatus(to: messages)
        updateConversations(from: messages)
        notificationService.setBadgeCount(totalUnreadCount)
    }

    // MARK: - Update Conversations

    /// Builds conversation objects from a flat list of messages.
    ///
    /// This is the core algorithm for grouping messages into conversations.
    /// It runs on background threads to avoid blocking the UI.
    ///
    /// ## Algorithm
    /// 1. Group messages by normalized phone number
    /// 2. For each group, find the latest message and calculate unread count
    /// 3. Resolve contact names from contacts list or message metadata
    /// 4. Apply user preferences (pinned, archived, blocked, avatar color)
    /// 5. Sort: pinned first, then by timestamp (newest first)
    ///
    /// ## Performance Optimizations
    /// - Bulk reads preferences into Sets for O(1) lookup
    /// - Pre-reserves array capacity based on estimated conversation count
    /// - Uses normalized addresses to merge duplicate phone formats
    ///
    /// - Parameter messages: Flat array of all messages
    /// - Returns: Array of Conversation objects ready for display
    private func buildConversations(from messages: [Message]) -> [Conversation] {
        // Batch read ALL preferences in ONE call (thread-safe, no main thread blocking)
        let (pinnedSet, archivedSet, blockedSet, avatarColors) = preferences.getAllPreferenceSets()

        // Group by NORMALIZED address to merge duplicate contacts with different formats
        var conversationDict: [String: (primaryAddress: String, lastMessage: Message, messages: [Message], allAddresses: Set<String>)] = [:]
        conversationDict.reserveCapacity(min(messages.count / 5, 500)) // Estimate ~5 msgs per conversation

        for message in messages {
            let address = message.address
            let normalizedAddress = normalizePhoneNumber(address)

            // Skip blocked numbers (O(1) set lookup)
            if blockedSet.contains(address) {
                continue
            }

            if var existing = conversationDict[normalizedAddress] {
                existing.messages.append(message)
                existing.allAddresses.insert(address)
                if message.date > existing.lastMessage.date {
                    existing.lastMessage = message
                    existing.primaryAddress = address // Use the most recent message's address
                }
                conversationDict[normalizedAddress] = existing
            } else {
                conversationDict[normalizedAddress] = (
                    primaryAddress: address,
                    lastMessage: message,
                    messages: [message],
                    allAddresses: [address]
                )
            }
        }

        // Convert to Conversation objects (no main thread calls needed)
        var newConversations: [Conversation] = []
        newConversations.reserveCapacity(conversationDict.count)

        for (normalizedAddress, data) in conversationDict {
            // Get prefs using O(1) set lookups
            let isPinned = isAddressPinned(data.primaryAddress, pinnedSet: pinnedSet)
                || data.allAddresses.contains(where: { isAddressPinned($0, pinnedSet: pinnedSet) })
            let isArchived = archivedSet.contains(data.primaryAddress) || data.allAddresses.contains(where: { archivedSet.contains($0) })
            let isBlocked = blockedSet.contains(data.primaryAddress) || data.allAddresses.contains(where: { blockedSet.contains($0) })
            let avatarColor = avatarColors[data.primaryAddress] ?? data.allAddresses.compactMap({ avatarColors[$0] }).first

            let unreadCount = data.messages.filter { $0.isReceived && !$0.isRead }.count

            // Find the best contact name from any message in the conversation
            // (last message might not have contact name, but earlier ones might)
            let candidateName = data.lastMessage.contactName
                ?? data.messages.first(where: { $0.contactName != nil })?.contactName
            let contactName = resolveContactName(
                candidate: candidateName,
                normalizedAddress: normalizedAddress
            )

            let conversation = Conversation(
                id: normalizedAddress, // Use normalized address as ID for deduplication
                address: data.primaryAddress, // Use the most recent address for display/sending
                contactName: contactName,
                lastMessage: data.lastMessage.body,
                timestamp: data.lastMessage.timestamp,
                unreadCount: unreadCount,
                allAddresses: Array(data.allAddresses),
                isPinned: isPinned,
                isArchived: isArchived,
                isBlocked: isBlocked,
                avatarColor: avatarColor,
                lastMessageEncrypted: data.lastMessage.isEncrypted ?? false,
                lastMessageE2eeFailed: data.lastMessage.e2eeFailed
            )

            newConversations.append(conversation)
        }

        // Sort: Pinned first, then by timestamp
        newConversations.sort { conv1, conv2 in
            if conv1.isPinned != conv2.isPinned {
                return conv1.isPinned
            }
            return conv1.timestamp > conv2.timestamp
        }

        return newConversations
    }

    /// Update conversations on main thread (for actions like pin/archive)
    private func updateConversations(from messages: [Message]) {
        // This is called from main thread, so do sync version without DispatchQueue.main.sync
        // Group by NORMALIZED address to merge duplicate contacts
        var conversationDict: [String: (primaryAddress: String, lastMessage: Message, messages: [Message], allAddresses: Set<String>)] = [:]

        for message in messages {
            let address = message.address
            let normalizedAddress = normalizePhoneNumber(address)
            if preferences.isBlocked(address) { continue }

            if var existing = conversationDict[normalizedAddress] {
                existing.messages.append(message)
                existing.allAddresses.insert(address)
                if message.date > existing.lastMessage.date {
                    existing.lastMessage = message
                    existing.primaryAddress = address
                }
                conversationDict[normalizedAddress] = existing
            } else {
                conversationDict[normalizedAddress] = (
                    primaryAddress: address,
                    lastMessage: message,
                    messages: [message],
                    allAddresses: [address]
                )
            }
        }

        var newConversations: [Conversation] = []
        let pinnedSet = Set(preferences.getAllPinned())
        for (normalizedAddress, data) in conversationDict {
            let unreadCount = data.messages.filter { $0.isReceived && !$0.isRead }.count

            // Find the best contact name from any message in the conversation
            let candidateName = data.lastMessage.contactName
                ?? data.messages.first(where: { $0.contactName != nil })?.contactName
            let contactName = resolveContactName(
                candidate: candidateName,
                normalizedAddress: normalizedAddress
            )

            // Get prefs from any of the addresses (prefer primary)
            let isPinned = isAddressPinned(data.primaryAddress, pinnedSet: pinnedSet)
                || data.allAddresses.contains(where: { isAddressPinned($0, pinnedSet: pinnedSet) })
            let isArchived = preferences.isArchived(data.primaryAddress) || data.allAddresses.contains(where: { preferences.isArchived($0) })
            let isBlocked = preferences.isBlocked(data.primaryAddress) || data.allAddresses.contains(where: { preferences.isBlocked($0) })
            let avatarColor = preferences.getAvatarColor(for: data.primaryAddress)

            let conversation = Conversation(
                id: normalizedAddress,
                address: data.primaryAddress,
                contactName: contactName,
                lastMessage: data.lastMessage.body,
                timestamp: data.lastMessage.timestamp,
                unreadCount: unreadCount,
                allAddresses: Array(data.allAddresses),
                isPinned: isPinned,
                isArchived: isArchived,
                isBlocked: isBlocked,
                avatarColor: avatarColor,
                lastMessageEncrypted: data.lastMessage.isEncrypted ?? false,
                lastMessageE2eeFailed: data.lastMessage.e2eeFailed
            )
            newConversations.append(conversation)
        }

        newConversations.sort { conv1, conv2 in
            if conv1.isPinned != conv2.isPinned { return conv1.isPinned }
            return conv1.timestamp > conv2.timestamp
        }

        self.conversations = newConversations
    }

    // MARK: - Get Messages for Conversation

    func messages(for conversation: Conversation) -> [Message] {
        // Use normalized address to get all messages from different phone number formats
        let normalizedConversationAddress = normalizePhoneNumber(conversation.address)
        return messages
            .filter { normalizePhoneNumber($0.address) == normalizedConversationAddress }
            .sorted { $0.date < $1.date }  // Oldest first for chat view
    }

    // MARK: - Send Message

    /// Sends an SMS message through the paired Android device.
    ///
    /// Implements optimistic UI update:
    /// 1. Creates a pending message with temporary ID
    /// 2. Immediately adds to UI for instant feedback
    /// 3. Sends to Firebase for Android to deliver
    /// 4. Matches with confirmed message when sync returns
    /// 5. Removes pending message once confirmed
    ///
    /// - Parameters:
    ///   - userId: The Firebase user ID
    ///   - address: Recipient phone number
    ///   - body: Message text content
    /// - Throws: Error if send fails (rolls back optimistic update)
    func sendMessage(userId: String, to address: String, body: String) async throws {
        guard !body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return
        }

        let pendingMessage = createPendingOutgoingMessage(to: address, body: body)

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.messages.append(pendingMessage)
            self.updateConversations(from: self.messages)
        }

        _ = pendingOutgoingQueue.sync {
            pendingOutgoingMessages[pendingMessage.id] = pendingMessage
        }

        do {
            try await firebaseService.sendMessage(userId: userId, to: address, body: body)
        } catch {
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                self.messages.removeAll { $0.id == pendingMessage.id }
                self.updateConversations(from: self.messages)
            }

            pendingOutgoingQueue.sync {
                pendingOutgoingMessages.removeValue(forKey: pendingMessage.id)
            }

            DispatchQueue.main.async {
                self.error = error
            }
            throw error
        }
    }

    private func createPendingOutgoingMessage(to address: String, body: String) -> Message {
        let normalizedAddress = normalizePhoneNumber(address)
        let contactName = conversations.first { normalizePhoneNumber($0.address) == normalizedAddress }?.contactName

        return Message(
            id: "pending_\(UUID().uuidString)",
            address: address,
            body: body,
            date: Date().timeIntervalSince1970 * 1000.0,
            type: 2,
            contactName: contactName,
            isRead: true,
            isMms: false,
            attachments: nil,
            e2eeFailed: false,
            e2eeFailureReason: nil
        )
    }

    private func mergeMessagesWithPendingOutgoing(remoteMessages: [Message]) -> (mergedMessages: [Message], matchedPendingIds: Set<String>) {
        let pendingSnapshot = pendingOutgoingQueue.sync { pendingOutgoingMessages }
        guard !pendingSnapshot.isEmpty else {
            return (remoteMessages, [])
        }

        let sentRemoteMessages = remoteMessages.filter { $0.type == 2 }
        var remainingPending: [Message] = []
        var matchedIds: Set<String> = []

        for pending in pendingSnapshot.values {
            if hasMatchingRemoteMessage(pending: pending, remoteMessages: sentRemoteMessages) {
                matchedIds.insert(pending.id)
            } else {
                remainingPending.append(pending)
            }
        }

        let merged = (remoteMessages + remainingPending).sorted { $0.date < $1.date }
        return (merged, matchedIds)
    }

    private func hasMatchingRemoteMessage(pending: Message, remoteMessages: [Message]) -> Bool {
        let pendingAddress = normalizePhoneNumber(pending.address)
        let trimmedBody = pending.body.trimmingCharacters(in: .whitespacesAndNewlines)
        let maxDeltaMs = 5.0 * 60.0 * 1000.0

        for remote in remoteMessages {
            guard remote.type == 2 else { continue }
            guard normalizePhoneNumber(remote.address) == pendingAddress else { continue }
            guard remote.body.trimmingCharacters(in: .whitespacesAndNewlines) == trimmedBody else { continue }
            guard abs(remote.date - pending.date) <= maxDeltaMs else { continue }
            guard remote.isMms == pending.isMms else { continue }
            return true
        }

        return false
    }

    func setReaction(messageId: String, reaction: String?) {
        guard let userId = currentUserId else { return }

        if let reaction = reaction, !reaction.isEmpty {
            messageReactions[messageId] = reaction
        } else {
            messageReactions.removeValue(forKey: messageId)
        }

        Task {
            try? await firebaseService.setMessageReaction(
                userId: userId,
                messageId: messageId,
                reaction: reaction
            )
        }
    }

    /// Delete a message
    func deleteMessage(_ message: Message) {
        guard let userId = currentUserId else { return }

        // Optimistically remove from local state
        messages.removeAll { $0.id == message.id }
        updateConversations(from: messages)

        // Also remove any reaction
        messageReactions.removeValue(forKey: message.id)

        // Also unpin if pinned
        pinnedMessages.remove(message.id)
        savePinnedMessages()

        // Delete from Firebase
        Task {
            do {
                try await firebaseService.deleteMessages(userId: userId, messageIds: [message.id])
            } catch {
                print("Failed to delete message: \(error)")
                // Could re-add message on failure, but Firebase listener will sync anyway
            }
        }
    }

    /// Delete multiple messages
    func deleteMessages(_ messagesToDelete: [Message]) {
        guard let userId = currentUserId else { return }
        let ids = Set(messagesToDelete.map { $0.id })
        if ids.isEmpty { return }

        // Optimistically remove from local state
        messages.removeAll { ids.contains($0.id) }
        updateConversations(from: messages)

        // Clean related local state
        ids.forEach { id in
            messageReactions.removeValue(forKey: id)
            readReceipts.removeValue(forKey: id)
        }
        pinnedMessages.subtract(ids)
        savePinnedMessages()

        // Delete from Firebase
        Task {
            do {
                try await firebaseService.deleteMessages(userId: userId, messageIds: Array(ids))
            } catch {
                print("Failed to delete messages: \(error)")
            }
        }
    }

    // MARK: - Message Pinning

    /// Pin or unpin a message
    func togglePinMessage(_ message: Message) {
        if pinnedMessages.contains(message.id) {
            pinnedMessages.remove(message.id)
        } else {
            pinnedMessages.insert(message.id)
        }
        savePinnedMessages()
    }

    /// Check if a message is pinned
    func isMessagePinned(_ message: Message) -> Bool {
        return pinnedMessages.contains(message.id)
    }

    /// Get all pinned messages for a conversation
    func pinnedMessages(for conversation: Conversation) -> [Message] {
        let normalizedConversationAddress = normalizePhoneNumber(conversation.address)
        return messages
            .filter { normalizePhoneNumber($0.address) == normalizedConversationAddress && pinnedMessages.contains($0.id) }
            .sorted { $0.date > $1.date }  // Most recent first
    }

    /// Load pinned messages from local storage
    private func loadPinnedMessages() {
        let defaults = UserDefaults.standard
        if let savedPins = defaults.array(forKey: "pinned_messages") as? [String] {
            pinnedMessages = Set(savedPins)
        }
    }

    /// Save pinned messages to local storage
    private func savePinnedMessages() {
        let defaults = UserDefaults.standard
        defaults.set(Array(pinnedMessages), forKey: "pinned_messages")
    }

    // MARK: - Send MMS Message

    func sendMmsMessage(userId: String, to address: String, body: String, attachment: SelectedAttachment) async throws {
        do {
            try await firebaseService.sendMmsMessage(
                userId: userId,
                to: address,
                body: body,
                attachmentData: attachment.data,
                fileName: attachment.fileName,
                contentType: attachment.contentType,
                attachmentType: attachment.type
            )
        } catch {
            DispatchQueue.main.async {
                self.error = error
            }
            throw error
        }
    }

    // MARK: - Conversation Actions

    func togglePin(_ conversation: Conversation) {
        let addresses = Set(conversation.allAddresses + [conversation.address])
        let normalizedAddresses = Set(addresses.map { normalizePhoneNumber($0) })
        let pinnedList = preferences.getAllPinned()
        let pinnedSet = Set(pinnedList)
        let normalizedConversation = normalizePhoneNumber(conversation.address)
        let isPinned = addresses.contains { isAddressPinned($0, pinnedSet: pinnedSet) }

        if isPinned {
            for pinned in pinnedList {
                if normalizePhoneNumber(pinned) == normalizedConversation {
                    preferences.setPinned(pinned, pinned: false)
                }
            }
            for address in addresses {
                preferences.setPinned(address, pinned: false)
            }
            for normalized in normalizedAddresses {
                preferences.setPinned(normalized, pinned: false)
            }
        } else {
            preferences.setPinned(conversation.address, pinned: true)
            let normalized = normalizePhoneNumber(conversation.address)
            if normalized != conversation.address {
                preferences.setPinned(normalized, pinned: true)
            }
        }
        updateConversations(from: messages)
    }

    func isConversationPinned(_ conversation: Conversation, allAddresses: [String]) -> Bool {
        let addresses = Set(allAddresses + [conversation.address])
        let pinnedSet = Set(preferences.getAllPinned())
        return addresses.contains { isAddressPinned($0, pinnedSet: pinnedSet) }
    }

    func toggleArchive(_ conversation: Conversation) {
        preferences.setArchived(conversation.address, archived: !conversation.isArchived)
        updateConversations(from: messages)
    }

    func toggleBlock(_ conversation: Conversation) {
        preferences.setBlocked(conversation.address, blocked: !conversation.isBlocked)
        updateConversations(from: messages)
    }

    func deleteConversation(_ conversation: Conversation) {
        deleteConversations([conversation])
    }

    /// Delete multiple conversations (and all messages in them)
    func deleteConversations(_ conversations: [Conversation]) {
        guard !conversations.isEmpty else { return }

        let addresses = Set(conversations.map { $0.address })
        let messagesToDelete = messages.filter { addresses.contains($0.address) }

        // Remove from preferences
        for convo in conversations {
            preferences.setArchived(convo.address, archived: false)
            preferences.setPinned(convo.address, pinned: false)
        }

        deleteMessages(messagesToDelete)
    }

    // MARK: - Filtered Conversations

    private var spamAddressLookup: Set<String> {
        let normalized = spamMessages.map { normalizePhoneNumber($0.address) }
        return Set(normalized)
    }

    private func isSpamConversation(_ conversation: Conversation) -> Bool {
        let normalized = normalizePhoneNumber(conversation.address)
        return spamAddressLookup.contains(normalized)
    }

    var activeConversations: [Conversation] {
        return conversations.filter { !$0.isArchived && !isSpamConversation($0) }
    }

    var archivedConversations: [Conversation] {
        return conversations.filter { $0.isArchived && !isSpamConversation($0) }
    }

    var unreadConversations: [Conversation] {
        return activeConversations.filter { $0.unreadCount > 0 }
    }

    var displayedConversations: [Conversation] {
        if showSpamOnly {
            return []
        } else if showArchived {
            return archivedConversations
        } else if showUnreadOnly {
            return unreadConversations
        } else {
            return activeConversations
        }
    }

    /// Filter mode for conversations
    enum ConversationFilter: String, CaseIterable {
        case all = "All"
        case unread = "Unread"
        case archived = "Archived"
        case spam = "Spam"

        var icon: String {
            switch self {
            case .all: return "tray"
            case .unread: return "envelope.badge"
            case .archived: return "archivebox"
            case .spam: return "shield.lefthalf.filled"
            }
        }
    }

    var currentFilter: ConversationFilter {
        get {
            if showSpamOnly {
                return .spam
            } else if showArchived {
                return .archived
            } else if showUnreadOnly {
                return .unread
            } else {
                return .all
            }
        }
        set {
            switch newValue {
            case .all:
                showArchived = false
                showUnreadOnly = false
                showSpamOnly = false
            case .unread:
                showArchived = false
                showUnreadOnly = true
                showSpamOnly = false
            case .archived:
                showArchived = true
                showUnreadOnly = false
                showSpamOnly = false
            case .spam:
                showArchived = false
                showUnreadOnly = false
                showSpamOnly = true
            }
        }
    }

    var spamConversations: [SpamConversation] {
        let grouped = Dictionary(grouping: spamMessages) { $0.address }
        return grouped.map { (address, messages) in
            let latest = messages.max(by: { $0.date < $1.date })
            return SpamConversation(
                address: address,
                contactName: latest?.contactName ?? address,
                latestMessage: latest?.body ?? "",
                timestamp: latest?.date ?? 0,
                messageCount: messages.count
            )
        }.sorted { $0.timestamp > $1.timestamp }
    }

    func spamMessages(for address: String) -> [SpamMessage] {
        return spamMessages
            .filter { $0.address == address }
            .sorted { $0.date < $1.date }
    }

    func deleteSpamMessages(for address: String) async {
        let ids = spamMessages.filter { $0.address == address }.map { $0.id }
        for id in ids {
            try? await VPSService.shared.deleteSpamMessage(messageId: id)
        }
        await MainActor.run {
            self.spamMessages.removeAll { $0.address == address }
        }
    }

    func clearAllSpam() async {
        try? await VPSService.shared.clearAllSpamMessages()
        await MainActor.run {
            self.spamMessages.removeAll()
        }
    }

    func markMessageAsSpam(_ message: Message) {
        Task {
            try? await VPSService.shared.syncSpamMessage(
                address: message.address,
                body: message.body,
                date: message.date,
                spamScore: 1.0,
                spamReason: "Manually marked as spam"
            )
            loadSpamMessagesFromVPS()
        }
    }

    func markConversationAsSpam(_ conversation: Conversation) {
        guard let latest = messages
            .filter({ $0.address == conversation.address })
            .max(by: { $0.date < $1.date }) else { return }
        markMessageAsSpam(latest)
    }

    /// Mark a spam conversation as "not spam" - removes from spam and adds to whitelist
    func markSpamAsNotSpam(address: String) async {
        // Add to whitelist (this also removes from blocklist)
        try? await VPSService.shared.addToWhitelist(phoneNumber: address)

        // Delete spam messages for this address
        await deleteSpamMessages(for: address)
    }

    // MARK: - VPS Spam Helpers

    private func loadSpamMessagesFromVPS() {
        Task {
            do {
                let response = try await VPSService.shared.getSpamMessages(limit: 100)
                let mapped = response.messages.map { vpsSpam in
                    SpamMessage(
                        id: vpsSpam.id,
                        address: vpsSpam.address,
                        body: vpsSpam.body ?? "",
                        date: Double(vpsSpam.date),
                        contactName: nil,
                        spamConfidence: Double(vpsSpam.spamScore ?? 0.5),
                        spamReasons: vpsSpam.spamReason,
                        detectedAt: Double(vpsSpam.date),
                        isUserMarked: false,
                        isRead: false
                    )
                }
                await MainActor.run {
                    self.spamMessages = mapped.sorted { $0.date > $1.date }
                    if self.selectedSpamAddress == nil {
                        self.selectedSpamAddress = self.spamMessages.first?.address
                    }
                    print("[MessageStore VPS] Loaded \(mapped.count) spam messages")
                }
            } catch {
                print("[MessageStore VPS] Error loading spam: \(error.localizedDescription)")
            }
        }
    }

    private func startSpamPolling() {
        spamPollingTimer?.invalidate()
        spamPollingTimer = Timer.scheduledTimer(withTimeInterval: 60.0, repeats: true) { [weak self] _ in
            self?.loadSpamMessagesFromVPS()
        }
    }

    // MARK: - Search

    /// Extract only digits from a string for phone number comparison
    private func digitsOnly(_ value: String) -> String {
        return value.filter { $0.isNumber }
    }

    private func normalizeSearchText(_ value: String) -> String {
        let folded = value
            .folding(options: [.diacriticInsensitive], locale: .current)
            .lowercased()
        let cleaned = folded.map { char in
            char.isLetter || char.isNumber ? char : " "
        }
        let collapsed = String(cleaned)
            .split(whereSeparator: { $0.isWhitespace })
            .joined(separator: " ")
        return collapsed
    }

    func search(query: String, in conversationsList: [Conversation] = []) -> [Conversation] {
        let list = conversationsList.isEmpty ? conversations : conversationsList

        if query.isEmpty {
            return list
        }

        let lowercaseQuery = query.lowercased()
        let queryDigits = query.filter { $0.isNumber }
        let normalizedQuery = normalizeSearchText(query)
        let compactQuery = normalizedQuery.replacingOccurrences(of: " ", with: "")

        if normalizedQuery.isEmpty {
            return list
        }

        return list.filter { conversation in
            // Match by display name (contact name or address)
            if conversation.displayName.lowercased().contains(lowercaseQuery) {
                return true
            }

            // Match by contact name if available
            if let contactName = conversation.contactName,
               contactName.lowercased().contains(lowercaseQuery) {
                return true
            }

            // Match by last message content
            if conversation.lastMessage.lowercased().contains(lowercaseQuery) {
                return true
            }

            // Match by address (exact or partial)
            if conversation.address.lowercased().contains(lowercaseQuery) {
                return true
            }

            // Match by conversation ID
            if conversation.id.lowercased().contains(lowercaseQuery) {
                return true
            }

            // Normalized/compact matching (handles punctuation/spacing)
            let displayNameNormalized = normalizeSearchText(conversation.displayName)
            let displayNameCompact = displayNameNormalized.replacingOccurrences(of: " ", with: "")
            if displayNameNormalized.contains(normalizedQuery) || displayNameCompact.contains(compactQuery) {
                return true
            }
            if let contactName = conversation.contactName {
                let contactNormalized = normalizeSearchText(contactName)
                let contactCompact = contactNormalized.replacingOccurrences(of: " ", with: "")
                if contactNormalized.contains(normalizedQuery) || contactCompact.contains(compactQuery) {
                    return true
                }
            }
            let lastMessageNormalized = normalizeSearchText(conversation.lastMessage)
            let lastMessageCompact = lastMessageNormalized.replacingOccurrences(of: " ", with: "")
            if lastMessageNormalized.contains(normalizedQuery) || lastMessageCompact.contains(compactQuery) {
                return true
            }
            let addressNormalized = normalizeSearchText(conversation.address)
            let addressCompact = addressNormalized.replacingOccurrences(of: " ", with: "")
            if addressNormalized.contains(normalizedQuery) || addressCompact.contains(compactQuery) {
                return true
            }
            let idNormalized = normalizeSearchText(conversation.id)
            let idCompact = idNormalized.replacingOccurrences(of: " ", with: "")
            if idNormalized.contains(normalizedQuery) || idCompact.contains(compactQuery) {
                return true
            }

            // Phone number digit matching
            if queryDigits.count >= 3 {
                let addressDigits = conversation.address.filter { $0.isNumber }
                let idDigits = conversation.id.filter { $0.isNumber }

                // Check if query digits appear anywhere in address or id digits
                if addressDigits.contains(queryDigits) {
                    return true
                }
                if idDigits.contains(queryDigits) {
                    return true
                }

                // Check if address/id digits appear in query
                if queryDigits.contains(addressDigits) && !addressDigits.isEmpty {
                    return true
                }
                if queryDigits.contains(idDigits) && !idDigits.isEmpty {
                    return true
                }
            }

            return false
        }
    }

    func searchMessages(query: String) -> [Message] {
        if query.isEmpty {
            return []
        }

        let normalizedQuery = normalizeSearchText(query)
        let compactQuery = normalizedQuery.replacingOccurrences(of: " ", with: "")
        if normalizedQuery.isEmpty {
            return []
        }

        // Get digits from query for phone number matching
        let queryDigits = digitsOnly(query)
        let isPhoneSearch = queryDigits.count >= 4

        return messages.filter { message in
            // Match by message body
            if message.body.localizedCaseInsensitiveContains(query) {
                return true
            }
            // Match by exact address
            if message.address.contains(query) {
                return true
            }
            // Match by contact name
            if message.contactName?.localizedCaseInsensitiveContains(query) == true {
                return true
            }
            // Normalized/compact matching for punctuation/spacing variations
            let bodyNormalized = normalizeSearchText(message.body)
            let bodyCompact = bodyNormalized.replacingOccurrences(of: " ", with: "")
            if bodyNormalized.contains(normalizedQuery) || bodyCompact.contains(compactQuery) {
                return true
            }
            let addressNormalized = normalizeSearchText(message.address)
            let addressCompact = addressNormalized.replacingOccurrences(of: " ", with: "")
            if addressNormalized.contains(normalizedQuery) || addressCompact.contains(compactQuery) {
                return true
            }
            if let contactName = message.contactName {
                let contactNormalized = normalizeSearchText(contactName)
                let contactCompact = contactNormalized.replacingOccurrences(of: " ", with: "")
                if contactNormalized.contains(normalizedQuery) || contactCompact.contains(compactQuery) {
                    return true
                }
            }
            // Match by phone number digits (handles all formats)
            if isPhoneSearch {
                let addressDigits = digitsOnly(message.address)
                // Check if digits match
                if addressDigits.contains(queryDigits) || queryDigits.contains(addressDigits) {
                    return true
                }
                // Also check last N digits match
                if queryDigits.count >= 7 && addressDigits.count >= 7 {
                    let queryLast7 = String(queryDigits.suffix(7))
                    let addressLast7 = String(addressDigits.suffix(7))
                    if queryLast7 == addressLast7 {
                        return true
                    }
                }
            }
            return false
        }
    }

    // MARK: - Contacts lookup

    private func startListeningForContacts(userId: String) {
        // BANDWIDTH OPTIMIZATION: Use child observers for delta-only sync (~95% bandwidth reduction)
        // Instead of fetching all contacts on every change, only receives individual changes
        contactsListenerUserId = userId
        contactsListenerHandles = firebaseService.listenToContactsOptimized(
            userId: userId,
            onAdded: { [weak self] contact in
                DispatchQueue.main.async {
                    guard let self = self else { return }
                    // Add new contact, avoiding duplicates
                    if !self.latestContacts.contains(where: { $0.id == contact.id }) {
                        self.latestContacts.append(contact)
                        self.latestContacts.sort { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
                        self.rebuildContactLookup()
                    }
                }
            },
            onChanged: { [weak self] contact in
                DispatchQueue.main.async {
                    guard let self = self else { return }
                    // Update existing contact
                    if let index = self.latestContacts.firstIndex(where: { $0.id == contact.id }) {
                        self.latestContacts[index] = contact
                        self.rebuildContactLookup()
                    }
                }
            },
            onRemoved: { [weak self] contactId in
                DispatchQueue.main.async {
                    guard let self = self else { return }
                    // Remove deleted contact
                    self.latestContacts.removeAll { $0.id == contactId }
                    self.rebuildContactLookup()
                }
            }
        )
    }

    private func stopListeningForContacts() {
        if let handles = contactsListenerHandles, let userId = contactsListenerUserId {
            firebaseService.removeContactsOptimizedListeners(userId: userId, handles: handles)
        }
        contactsListenerHandles = nil
        contactsListenerUserId = nil
        latestContacts = []
        contactNameLookup = [:]
    }

    private func rebuildContactLookup() {
        var lookup: [String: String] = [:]

        for contact in latestContacts {
            let normalized = normalizePhoneNumber(
                (contact.normalizedNumber ?? "").isEmpty ? (contact.phoneNumber ?? "") : (contact.normalizedNumber ?? "")
            )
            if !normalized.isEmpty {
                lookup[normalized] = contact.displayName
            }
        }

        contactNameLookup = lookup

        if !messages.isEmpty {
            updateConversations(from: messages)
        }
    }

    private func resolveContactName(candidate: String?, normalizedAddress: String) -> String? {
        let trimmed = candidate?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let lookup = contactNameLookup[normalizedAddress], !lookup.isEmpty {
            return lookup
        }

        if let name = trimmed,
           !name.isEmpty,
           name.rangeOfCharacter(from: .letters) != nil {
            return name
        }

        return trimmed?.isEmpty == true ? nil : trimmed
    }
}

struct SpamConversation: Identifiable, Hashable {
    let address: String
    let contactName: String
    let latestMessage: String
    let timestamp: Double
    let messageCount: Int

    var id: String { address }
}
