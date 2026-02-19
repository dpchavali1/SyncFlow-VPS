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
//  - Manages VPS real-time listeners for data synchronization
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
//  VPSService (network layer)
//  ```
//
//  Data Flow:
//  1. View observes @Published properties (messages, conversations, etc.)
//  2. VPS listeners push updates to MessageStore
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
//  - VPSService: Real-time data synchronization
//  - PreferencesService: User preferences (pinned, archived, blocked)
//  - NotificationService: System notification delivery
//  - BatteryAwareServiceManager: Power-optimized processing
//
//  ============================================================================
//  FILE ORGANIZATION (Extensions)
//  ============================================================================
//  This class is split across multiple files for maintainability:
//  - MessageStore.swift              — Core state, properties, init, helpers
//  - MessageStore+VPSSync.swift      — VPS listening, polling, message sync
//  - MessageStore+ReadStatus.swift   — Read status management
//  - MessageStore+SendMessage.swift  — Send SMS/MMS functionality
//  - MessageStore+ConversationActions.swift — Pin, archive, block actions
//  - MessageStore+Search.swift       — Search functionality
//  - MessageStore+Spam.swift         — Spam detection and management
//  - MessageStore+Contacts.swift     — Contact resolution helpers

import Foundation
// VPS backend only
import Combine

// MARK: - Notification Names

extension Notification.Name {
    /// Posted when E2EE keys are successfully synced and MessageStore should reload
    static let e2eeKeysUpdated = Notification.Name("e2eeKeysUpdated")
}

// MARK: - MessageStore

/// Central observable store for all messaging state in the SyncFlow macOS app.
///
/// MessageStore manages the complete lifecycle of message data, from VPS
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

    // MARK: - Listener State

    /// Timer for polling spam messages from VPS.
    // spamPollingTimer removed — spam sync is now real-time via WebSocket
    var spamListenerUserId: String?

    // MARK: - Internal State (accessed by extensions)

    /// Currently authenticated user ID.
    var currentUserId: String?

    /// Flag indicating if read receipts have loaded at least once.
    /// Used to determine initial read state for synced messages.
    var readReceiptsLoaded = false

    /// Combine cancellables for reactive subscriptions.
    var cancellables = Set<AnyCancellable>()

    /// Prevents auto-repair from firing more than once per session.
    var hasAttemptedAutoRepair = false

    /// After repair, poll for re-encrypted messages from Android.
    var repairPollTask: Task<Void, Never>?

    /// VPS-specific cancellables
    var vpsCancellables = Set<AnyCancellable>()

    /// Polling fallback task — runs only when WebSocket is disconnected
    var pollFallbackTask: Task<Void, Never>?

    /// Tracks the newest message date for incremental polling
    var lastPolledMessageDate: Double = 0

    /// Serial queue for thread-safe access to pending outgoing messages.
    let pendingOutgoingQueue = DispatchQueue(label: "MessageStore.pendingOutgoingQueue")

    /// Messages sent from Mac but not yet confirmed by Android.
    /// Used for optimistic UI updates while waiting for sync.
    var pendingOutgoingMessages: [String: Message] = [:]

    /// Notification service for system alerts.
    let notificationService = NotificationService.shared

    /// User preferences service for pinned/archived/blocked state.
    let preferences = PreferencesService.shared

    // MARK: - Private State

    /// Tracks message IDs from last update to detect new messages.
    private var lastMessageIds: Set<String> = []

    /// Hash of message data to avoid redundant processing.
    private var lastMessageHash: Int = 0

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
    var loadedTimeRangeStart: TimeInterval?

    /// Number of days of history to load on initial sync (6 months).
    private var initialLoadDays: Int = 180

    /// Number of additional days to load when user requests more (3 months).
    private var loadMoreDays: Int = 90

    // MARK: - Contacts State

    /// User ID for contacts listener.
    var contactsListenerUserId: String?

    /// Cached contacts from last sync.
    var latestContacts: [Contact] = []

    /// Lookup table: normalized phone number -> contact display name.
    /// Used for fast contact name resolution in conversation building.
    var contactNameLookup: [String: String] = [:]

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

    // MARK: - Setup

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
        // No-op: battery optimization handled at app level (AppState)
    }

    @objc private func handleClearMessageCache(_ notification: Notification) {
        if let userInfo = notification.userInfo,
           let olderThan = userInfo["olderThan"] as? TimeInterval {
            clearOldMessageCache(olderThan: olderThan)
        }
    }

    /// Handles E2EE keys sync completion by re-decrypting cached messages locally.
    ///
    /// BANDWIDTH OPTIMIZATION: Instead of re-fetching all messages from VPS,
    /// this method only fetches encryption metadata for encrypted messages and
    /// decrypts them in-place. This uses minimal bandwidth (only nonce + envelope
    /// for encrypted messages, typically < 1KB total).
    ///
    /// When E2EE keys are synced from Android, previously encrypted messages need to be
    /// decrypted using the new keys. This method:
    /// 1. Identifies encrypted messages in cache
    /// 2. Fetches only their encryption metadata (nonce + envelope) from VPS
    /// 3. Decrypts them locally using new keys
    /// 4. Updates UI with decrypted messages
    @objc private func handleE2EEKeysUpdated(_ notification: Notification) {
        #if DEBUG
        print("[MessageStore] E2EE keys updated, hasSyncGroupKeys=\(E2EEManager.shared.hasSyncGroupKeys)")
        #endif

        guard currentUserId != nil else {
            #if DEBUG
            print("[MessageStore] No user ID, skipping re-decryption")
            #endif
            return
        }

        Task {
            let result = await self.fetchAndDecryptMessages()
            guard let result = result else { return }

            let encrypted = result.encrypted
            let failed = result.failed

            // Auto-repair: if ANY encrypted messages fail after key sync,
            // the server has messages encrypted with old keys. Trigger repair once
            // to have Android re-sync messages with the current keys.
            if !self.hasAttemptedAutoRepair && failed > 0 {
                self.hasAttemptedAutoRepair = true
                #if DEBUG
                print("[MessageStore VPS] Decryption failures detected (\(failed)/\(encrypted)) — auto-triggering encryption repair")
                #endif
                do {
                    try await VPSService.shared.repairEncryption()
                    E2EEManager.shared.clearSyncGroupKeys()
                    self.clearMessages()
                    await MainActor.run {
                        NotificationCenter.default.post(
                            name: Notification.Name("triggerE2EERepairSync"),
                            object: nil
                        )
                    }
                } catch {
                    #if DEBUG
                    print("[MessageStore VPS] Auto-repair failed: \(error.localizedDescription)")
                    #endif
                }
                return
            }

            // After repair, if still failing, start polling — Android is still
            // re-syncing messages to the server. Poll every 5s for up to 90s.
            if self.hasAttemptedAutoRepair && failed > 0 {
                self.startRepairPolling()
            }
        }
    }

    /// Fetch messages from VPS, decrypt, update UI, and return stats.
    func fetchAndDecryptMessages() async -> (encrypted: Int, failed: Int)? {
        do {
            let response = try await VPSService.shared.getMessages(limit: 500)
            let fetchedMessages = response.messages.map { self.convertVPSMessage($0) }

            let encrypted = fetchedMessages.filter { $0.isEncrypted == true }
            let failed = encrypted.filter { $0.e2eeFailed == true }
            #if DEBUG
            print("[MessageStore VPS] Re-decryption: \(encrypted.count - failed.count)/\(encrypted.count) decrypted, \(failed.count) failed")
            #endif

            await MainActor.run {
                let processedMessages = self.applyReadStatus(to: fetchedMessages)
                let mergeResult = self.mergeMessagesWithPendingOutgoing(remoteMessages: processedMessages)
                let newConversations = self.buildConversations(from: mergeResult.mergedMessages)

                self.messages = mergeResult.mergedMessages
                self.conversations = newConversations
            }

            return (encrypted: encrypted.count, failed: failed.count)
        } catch {
            #if DEBUG
            print("[MessageStore VPS] Re-decryption fetch error: \(error.localizedDescription)")
            #endif
            return nil
        }
    }

    /// Poll for re-encrypted messages after repair. Android may take 30-60s to
    /// re-sync all messages. We check every 5 seconds for up to 90 seconds.
    func startRepairPolling() {
        repairPollTask?.cancel()
        repairPollTask = Task {
            #if DEBUG
            print("[MessageStore VPS] Starting repair poll — waiting for Android to re-sync messages")
            #endif
            for attempt in 1...18 { // 18 x 5s = 90s max
                try? await Task.sleep(nanoseconds: 5_000_000_000) // 5 seconds
                if Task.isCancelled { return }

                let result = await self.fetchAndDecryptMessages()
                guard let result = result else { continue }

                #if DEBUG
                print("[MessageStore VPS] Repair poll #\(attempt): \(result.encrypted - result.failed)/\(result.encrypted) decrypted")
                #endif

                // Success: all messages now decrypt
                if result.failed == 0 {
                    #if DEBUG
                    print("[MessageStore VPS] Repair poll complete — messages decrypted successfully")
                    #endif
                    return
                }
            }
            #if DEBUG
            print("[MessageStore VPS] Repair poll timed out — some messages may still be encrypted")
            #endif
        }
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

    // MARK: - Start/Stop Listening

    /// Starts real-time synchronization for a user's messaging data.
    ///
    /// This method sets up VPS listeners for:
    /// - Messages (SMS/MMS synced from Android)
    /// - Message reactions (emoji responses)
    /// - Read receipts (cross-device read status)
    /// - Spam messages (filtered messages)
    /// - Contacts (for name resolution)
    ///
    /// - Parameter userId: The user ID to listen for
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

        // Stop old listeners before switching users
        spamListenerUserId = nil
        if currentUserId != nil {
            stopListeningForContacts()
        }

        currentUserId = userId
        isLoading = true
        hasAttemptedAutoRepair = false

        // Reset pagination state for new user
        loadedTimeRangeStart = nil
        canLoadMore = false

        #if DEBUG
        print("[MessageStore] VPS mode active - using VPS for messages")
        #endif
        startListeningVPS(userId: userId)
    }

    /// Stops all listeners and clears state.
    ///
    /// Call when:
    /// - User logs out
    /// - App is terminating
    /// - Switching to a different user
    ///
    /// Failure to call this method results in memory leaks and unnecessary
    /// network traffic from orphaned listeners.
    func stopListening() {
        // Cancel VPS subscriptions (includes spam WebSocket listener)
        vpsCancellables.removeAll()
        spamListenerUserId = nil
        stopListeningForContacts()
        repairPollTask?.cancel()
        repairPollTask = nil
        pollFallbackTask?.cancel()
        pollFallbackTask = nil

        // Clear state
        currentUserId = nil
        messages = []
        conversations = []
        readReceiptsLoaded = false
        loadedTimeRangeStart = nil
        canLoadMore = false
    }

    /// Clear all cached messages and conversations so they can be reloaded
    /// after encryption keys are repaired.
    func clearMessages() {
        DispatchQueue.main.async {
            self.messages = []
            self.conversations = []
            self.isLoading = true
        }
    }

    // MARK: - Cleanup

    deinit {
        stopListening()
        #if DEBUG
        print("[MessageStore] Deinitialized - all listeners removed")
        #endif
    }

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
    func normalizePhoneNumber(_ address: String) -> String {
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

    func isAddressPinned(_ address: String, pinnedSet: Set<String>) -> Bool {
        if pinnedSet.contains(address) { return true }
        let normalized = normalizePhoneNumber(address)
        if pinnedSet.contains(normalized) { return true }
        return pinnedSet.contains { normalizePhoneNumber($0) == normalized }
    }

    // MARK: - Conversation Building

    /// Builds conversation objects from a flat list of messages.
    ///
    /// This is the core algorithm for grouping messages into conversations.
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
    func buildConversations(from messages: [Message]) -> [Conversation] {
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
    func updateConversations(from messages: [Message]) {
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
    func loadPinnedMessages() {
        let defaults = UserDefaults.standard
        if let savedPins = defaults.array(forKey: "pinned_messages") as? [String] {
            pinnedMessages = Set(savedPins)
        }
    }

    /// Save pinned messages to local storage
    func savePinnedMessages() {
        let defaults = UserDefaults.standard
        defaults.set(Array(pinnedMessages), forKey: "pinned_messages")
    }

    // MARK: - Reactions

    func setReaction(messageId: String, reaction: String?) {
        if let reaction = reaction, !reaction.isEmpty {
            messageReactions[messageId] = reaction
        } else {
            messageReactions.removeValue(forKey: messageId)
        }
        // Reactions are stored locally only (VPS does not have a reactions endpoint)
    }

    // MARK: - Delete Messages

    /// Delete a message
    func deleteMessage(_ message: Message) {
        // Optimistically remove from local state
        messages.removeAll { $0.id == message.id }
        updateConversations(from: messages)

        messageReactions.removeValue(forKey: message.id)
        pinnedMessages.remove(message.id)
        savePinnedMessages()

        Task {
            do {
                try await VPSService.shared.deleteMessages(messageIds: [message.id])
            } catch {
                #if DEBUG
                print("Failed to delete message: \(error)")
                #endif
            }
        }
    }

    /// Delete multiple messages
    func deleteMessages(_ messagesToDelete: [Message]) {
        let ids = Set(messagesToDelete.map { $0.id })
        if ids.isEmpty { return }

        messages.removeAll { ids.contains($0.id) }
        updateConversations(from: messages)

        ids.forEach { id in
            messageReactions.removeValue(forKey: id)
            readReceipts.removeValue(forKey: id)
        }
        pinnedMessages.subtract(ids)
        savePinnedMessages()

        Task {
            do {
                try await VPSService.shared.deleteMessages(messageIds: Array(ids))
            } catch {
                #if DEBUG
                print("Failed to delete messages: \(error)")
                #endif
            }
        }
    }

    // MARK: - Filtered Conversations

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

    // MARK: - Pending Message Reconciliation

    /// Reconciles optimistic pending outgoing messages with confirmed remote messages.
    ///
    /// When a user sends a message, a "pending_*" placeholder is added to the UI
    /// immediately. Once Android confirms delivery and the message syncs back via VPS,
    /// this method matches pending messages to their remote counterparts using address +
    /// body + timestamp proximity (within 5 minutes). Matched pending messages are
    /// removed; unmatched ones remain visible as still-sending.
    func mergeMessagesWithPendingOutgoing(remoteMessages: [Message]) -> (mergedMessages: [Message], matchedPendingIds: Set<String>) {
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
        guard !isLoadingMore, canLoadMore else { return }

        isLoadingMore = true

        // Use oldest message timestamp as cursor for VPS pagination
        let oldestDate = messages.min(by: { $0.date < $1.date })?.date

        Task {
            do {
                let response = try await VPSService.shared.getMessages(
                    limit: 200,
                    before: oldestDate.map { Int64($0) }
                )

                let olderMessages = response.messages.map { self.convertVPSMessage($0) }

                await MainActor.run {
                    let existingIds = Set(self.messages.map { $0.id })
                    let newMessages = olderMessages.filter { !existingIds.contains($0.id) }

                    var allMessages = self.messages + newMessages
                    allMessages.sort { $0.date > $1.date }

                    self.messages = self.applyReadStatus(to: allMessages)
                    self.updateConversations(from: self.messages)
                    self.canLoadMore = response.hasMore
                    self.isLoadingMore = false

                    #if DEBUG
                    print("[MessageStore] Loaded \(newMessages.count) more messages via VPS pagination")
                    #endif
                }
            } catch {
                await MainActor.run {
                    #if DEBUG
                    print("[MessageStore] Error loading more messages: \(error)")
                    #endif
                    self.error = error
                    self.isLoadingMore = false
                }
            }
        }
    }

    // MARK: - Contacts Lookup

    func startListeningForContactsVPS(userId: String) {
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

                // Build lookup from ALL phone numbers per contact (not just the first).
                // latestContacts only stores the primary phone, but the VPS response
                // has the full phoneNumbers array. Index every number so conversations
                // on any of a contact's numbers resolve to their name.
                var lookup: [String: String] = [:]
                for vpsContact in response.contacts {
                    guard let name = vpsContact.displayName, !name.isEmpty else { continue }
                    for phone in vpsContact.phoneNumbers ?? [] {
                        let normalized = normalizePhoneNumber(phone)
                        if !normalized.isEmpty {
                            lookup[normalized] = name
                        }
                    }
                }

                await MainActor.run {
                    self.latestContacts = contacts
                    self.contactNameLookup = lookup

                    // Rebuild conversations with contact names
                    let newConversations = self.buildConversations(from: self.messages)
                    self.conversations = newConversations

                    #if DEBUG
                    print("[MessageStore VPS] Loaded \(contacts.count) contacts, \(lookup.count) phone lookup entries")
                    #endif
                }
            } catch {
                #if DEBUG
                print("[MessageStore VPS] Error loading contacts: \(error.localizedDescription)")
                #endif
            }
        }
    }

    func stopListeningForContacts() {
        contactsListenerUserId = nil
        latestContacts = []
        contactNameLookup = [:]
    }

    func rebuildContactLookup() {
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

    func resolveContactName(candidate: String?, normalizedAddress: String) -> String? {
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
