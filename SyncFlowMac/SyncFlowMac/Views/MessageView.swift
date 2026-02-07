//
//  MessageView.swift
//  SyncFlowMac
//
//  Main message view showing conversation and compose bar
//
//  =============================================================================
//  PURPOSE:
//  This is the primary conversation view that displays messages for a selected
//  conversation. It renders the message history, handles sending new messages
//  (SMS/MMS), supports attachments, voice recording, reply threading, and
//  provides message management features like selection and deletion.
//
//  USER INTERACTIONS:
//  - Scroll through message history (with lazy loading for older messages)
//  - Type and send SMS messages via the compose bar
//  - Attach images/videos/audio via file picker or drag-and-drop
//  - Record and send voice messages
//  - Reply to specific messages (inline quote format)
//  - Long-press/right-click messages for context menu (react, copy, pin, delete)
//  - Use keyboard shortcuts for common actions
//  - Select multiple messages for bulk operations
//  - Search within the conversation
//
//  STATE MANAGEMENT:
//  - @EnvironmentObject for AppState (app-wide state) and MessageStore (data)
//  - Conversation passed as a parameter, triggers view updates on change
//  - Message caching done asynchronously to prevent UI blocking
//  - Local @State for compose text, attachments, selection state, etc.
//
//  PERFORMANCE CONSIDERATIONS:
//  - Messages are loaded incrementally (initial 50, then 30 more per "load more")
//  - Message filtering and caching done on background thread
//  - Conversation ID checked after async processing to avoid stale data
//  - LazyVStack used for message virtualization
//  - Link previews loaded with 500ms delay to avoid blocking initial render
//  =============================================================================

import SwiftUI
import AppKit
// FirebaseFunctions - using FirebaseStubs.swift

// Theme imports for design system
// SyncFlowColors, SyncFlowTypography, SyncFlowSpacing are defined in Theme/

// MARK: - Main Message View

/// The primary conversation view displaying message history and compose functionality.
/// Handles SMS/MMS messaging, attachments, voice recording, and message management.
struct MessageView: View {

    // MARK: - Environment Objects

    /// App-wide state including user ID, continuity service, and feature flags
    @EnvironmentObject var appState: AppState
    /// Message data store containing all conversations and messages
    @EnvironmentObject var messageStore: MessageStore

    // MARK: - Properties

    /// The conversation being displayed
    let conversation: Conversation

    // MARK: - Compose State

    /// Current text in the compose field
    @State private var messageText = ""
    /// Whether a message is currently being sent
    @State private var isSending = false
    /// Trigger to scroll to the bottom of the message list
    @State private var scrollToBottom = false
    /// Whether the emoji picker popover is shown
    @State private var showEmojiPicker = false

    // MARK: - Search State

    /// Search text for filtering messages in conversation
    @State private var searchText = ""
    /// Whether the search bar is visible
    @State private var showSearch = false

    // MARK: - Reply and Attachment State

    /// Message being replied to (nil if not replying)
    @State private var replyToMessage: Message? = nil
    /// Currently selected attachment to send
    @State private var selectedAttachment: SelectedAttachment? = nil
    /// Whether the attachment file picker is shown
    @State private var showAttachmentPicker = false
    /// Whether a file is being dragged over the view
    @State private var isDragOver = false

    // MARK: - Voice Recording State

    /// Whether voice recording mode is active
    @State private var isRecordingVoice = false
    /// Audio recorder service for voice messages
    @StateObject private var audioRecorder = AudioRecorderService.shared

    // MARK: - Selection State

    /// Whether bulk selection mode is active
    @State private var isSelectionMode = false
    /// Set of message IDs currently selected for bulk operations
    @State private var selectedMessageIds: Set<String> = []
    /// Whether to show bulk delete confirmation alert
    @State private var showBulkDeleteConfirmation = false

    // MARK: - Message Cache State
    // These properties optimize performance by caching filtered messages
    // and avoiding expensive recomputation on every render

    /// Cached array of messages to display (filtered and limited)
    @State private var cachedMessages: [Message] = []
    /// Cached array of pinned messages
    @State private var cachedPinnedMessages: [Message] = []
    /// Last conversation ID used for cache validation
    @State private var lastConversationId: String = ""
    /// Last search text used for cache validation
    @State private var lastSearchText: String = ""
    /// Last total message count for cache validation
    @State private var lastMessageCount: Int = 0
    /// Number of messages currently displayed (for pagination)
    @State private var displayedMessageCount: Int = 50 // Start with 50 messages
    /// Whether messages are currently being loaded
    @State private var isLoadingMessages: Bool = true
    /// Whether more messages are being loaded (pagination)
    @State private var isLoadingMore: Bool = false
    /// Total count of messages in this conversation
    @State private var totalMessageCount: Int = 0
    /// Preferred phone number to send from (for multi-SIM contacts)
    @State private var preferredSendAddress: String? = nil
    /// E2EE key sync state
    @State private var isKeySyncInProgress: Bool = false
    @State private var keySyncStatusMessage: String? = nil

    // MARK: - E2EE Actions

    private func startKeySync(userId: String, deviceId: String) {
        isKeySyncInProgress = true
        keySyncStatusMessage = nil

        let isVPS = UserDefaults.standard.bool(forKey: "useVPSMode")

        Task {
            do {
                try await E2EEManager.shared.initializeKeys()

                if isVPS {
                    // VPS mode: fetch device E2EE key from VPS server
                    await MainActor.run {
                        keySyncStatusMessage = "Fetching keys from VPS server..."
                    }

                    let encryptedKey = try await VPSService.shared.waitForDeviceE2eeKey(timeout: 60, pollInterval: 3, initialDelay: 0)

                    guard let encryptedKey = encryptedKey else {
                        await MainActor.run {
                            keySyncStatusMessage = "Timeout. Is Android paired and online?"
                        }
                        return
                    }

                    let payloadData = try E2EEManager.shared.decryptDataKey(from: encryptedKey)
                    let payload = try JSONSerialization.jsonObject(with: payloadData) as? [String: Any]

                    guard let privateKeyPKCS8 = payload?["privateKeyPKCS8"] as? String,
                          let publicKeyX963 = payload?["publicKeyX963"] as? String else {
                        throw NSError(domain: "E2EE", code: 1, userInfo: [NSLocalizedDescriptionKey: "Invalid key payload from VPS"])
                    }

                    try E2EEManager.shared.importSyncGroupKeypair(
                        privateKeyPKCS8Base64: privateKeyPKCS8,
                        publicKeyX963Base64: publicKeyX963
                    )

                    print("[E2EE] VPS keys synced, reloading messages")
                    IncrementalSyncManager.shared.clearCache(userId: userId)
                    NotificationCenter.default.post(name: .e2eeKeysUpdated, object: nil)

                    await MainActor.run {
                        keySyncStatusMessage = "Keys synced successfully! All messages accessible."
                        appState.e2eeKeyMismatch = false
                    }
                } else {
                    // Firebase mode: request key sync from Android via Firebase
                    try await FirebaseService.shared.requestE2eeKeySync(userId: userId, deviceId: deviceId)
                    _ = try await FirebaseService.shared.waitForE2eeKeySyncResponse(userId: userId, deviceId: deviceId)

                    await MainActor.run {
                        keySyncStatusMessage = "Keys synced successfully! All messages accessible."
                        appState.e2eeKeyMismatch = false
                    }
                }
                appState.refreshE2eeKeyStatus()
            } catch {
                await MainActor.run {
                    keySyncStatusMessage = "Key sync failed: \(error.localizedDescription)"
                }
            }
            await MainActor.run {
                isKeySyncInProgress = false
            }
        }
    }

    private func requestResync(userId: String) {
        Task {
            do {
                try await FirebaseService.shared.requestHistorySync(userId: userId)
                await MainActor.run {
                    keySyncStatusMessage = "Resync requested from phone."
                }
            } catch {
                await MainActor.run {
                    keySyncStatusMessage = "Resync request failed: \(error.localizedDescription)"
                }
            }
        }
    }

    @ViewBuilder
    private var e2eeKeyMismatchBanner: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: "exclamationmark.shield.fill")
                    .foregroundColor(.orange)
                Text("Encryption keys changed")
                    .font(.subheadline)
                    .fontWeight(.semibold)
                Spacer()
            }
            Text(appState.e2eeKeyMismatchMessage ?? "Encryption keys are syncing automatically. Keep your phone online to finish.")
                .font(.caption)
                .foregroundColor(.secondary)

            HStack(spacing: 8) {
                Button(isKeySyncInProgress ? "Syncing..." : "Retry Now") {
                    guard let userId = appState.userId,
                          let deviceId = UserDefaults.standard.string(forKey: "syncflow_device_id") else {
                        return
                    }
                    startKeySync(userId: userId, deviceId: deviceId)
                }
                .disabled(isKeySyncInProgress)

                Button("Resync Messages") {
                    guard let userId = appState.userId else { return }
                    requestResync(userId: userId)
                }

                Button("Dismiss") {
                    appState.e2eeKeyMismatch = false
                }
                .buttonStyle(.plain)
                .foregroundColor(.secondary)
            }

            if let status = keySyncStatusMessage {
                Text(status)
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
        }
        .padding(12)
        .background(Color(nsColor: .controlBackgroundColor))
        .overlay(
            Rectangle()
                .fill(Color.orange)
                .frame(width: 3),
            alignment: .leading
        )
    }

    // MARK: - Constants

    /// Number of messages to load initially
    private let initialMessageCount = 50
    /// Number of additional messages to load when paginating
    private let loadMoreIncrement = 30

    // MARK: - Computed Properties

    /// All phone numbers associated with this contact (for multi-number contacts)
    private var allSendAddresses: [String] {
        if !conversation.allAddresses.isEmpty {
            return conversation.allAddresses
        }
        return [conversation.address]
    }

    /// The phone number to use when sending messages
    private var activeSendAddress: String {
        return preferredSendAddress ?? conversation.address
    }

    // MARK: - Preferred Address Persistence

    /// UserDefaults key for storing preferred send address
    private func preferredSendKey() -> String {
        return "preferred_send_address_\(conversation.id)"
    }

    /// Loads the user's preferred send address from UserDefaults
    private func loadPreferredSendAddress() {
        let stored = UserDefaults.standard.string(forKey: preferredSendKey())
        if let stored = stored, allSendAddresses.contains(stored) {
            preferredSendAddress = stored
        } else {
            preferredSendAddress = nil
        }
    }

    /// Saves the user's preferred send address to UserDefaults
    /// - Parameter address: The address to save, or nil to clear preference
    private func persistPreferredSendAddress(_ address: String?) {
        if let address = address, !address.isEmpty {
            UserDefaults.standard.set(address, forKey: preferredSendKey())
        } else {
            UserDefaults.standard.removeObject(forKey: preferredSendKey())
        }
        preferredSendAddress = address
    }

    // MARK: - Message Loading & Caching

    /// Asynchronously updates the cached messages array.
    /// Performs filtering on a background thread to avoid blocking the UI.
    /// Includes validation to discard stale results if conversation changed.
    private func updateCachedMessagesAsync() {
        isLoadingMessages = true

        // Capture current state for async processing
        let conversationId = conversation.id
        let currentSearchText = searchText
        let currentDisplayCount = displayedMessageCount
        let allMessages = messageStore.messages

        // Process on background thread for performance
        DispatchQueue.global(qos: .userInitiated).async {
            // Filter messages for this conversation using normalized matching
            let conversationMsgs = messageStore.messages(for: conversation)

            // Apply search filter if active
            let filtered: [Message]
            if currentSearchText.isEmpty {
                filtered = conversationMsgs
            } else {
                filtered = conversationMsgs.filter { $0.body.localizedCaseInsensitiveContains(currentSearchText) }
            }

            // Get pinned messages from UserDefaults
            let pinnedIds = UserDefaults.standard.array(forKey: "pinned_messages") as? [String] ?? []
            let pinnedSet = Set(pinnedIds)
            let pinned = conversationMsgs
                .filter { pinnedSet.contains($0.id) }
                .sorted { $0.date > $1.date }

            // Paginate: only show the most recent messages up to displayedMessageCount
            let displayMessages: [Message]
            if filtered.count > currentDisplayCount {
                displayMessages = Array(filtered.suffix(currentDisplayCount))
            } else {
                displayMessages = filtered
            }

            let totalCount = filtered.count

            // Update UI on main thread
            DispatchQueue.main.async {
                // CRITICAL: Check if conversation changed while we were processing
                // If so, discard these results to avoid showing wrong messages
                guard self.conversation.id == conversationId else {
                    return
                }

                self.cachedMessages = displayMessages
                self.cachedPinnedMessages = pinned
                self.totalMessageCount = totalCount
                self.lastConversationId = conversationId
                self.lastSearchText = currentSearchText
                self.lastMessageCount = allMessages.count
                self.isLoadingMessages = false
            }
        }
    }

    /// Loads additional older messages (pagination).
    /// Increments the display count and triggers a cache refresh.
    private func loadMoreMessages() {
        guard !isLoadingMore else { return }
        isLoadingMore = true
        displayedMessageCount += loadMoreIncrement
        updateCachedMessagesAsync()
        isLoadingMore = false
    }

    /// Returns whether there are more messages available to load.
    private func hasMoreMessages() -> Bool {
        return totalMessageCount > displayedMessageCount
    }

    // MARK: - Body

    var body: some View {
        VStack(spacing: 0) {
            // =================================================================
            // CONVERSATION HEADER
            // Shows contact info, action buttons (search, call, etc.)
            // =================================================================
            ConversationHeader(
                conversation: conversation,
                messageStore: messageStore,
                showSearch: $showSearch,
                allAddresses: allSendAddresses,
                preferredSendAddress: $preferredSendAddress,
                onSelectSendAddress: { address in
                    persistPreferredSendAddress(address)
                },
                isSelectionMode: $isSelectionMode,
                selectedCount: selectedMessageIds.count,
                onDeleteSelected: {
                    showBulkDeleteConfirmation = true
                },
                onClearSelection: {
                    selectedMessageIds.removeAll()
                }
            )

            Rectangle()
                .fill(SyncFlowColors.divider)
                .frame(height: 1)

            if appState.e2eeKeyMismatch {
                e2eeKeyMismatchBanner

                Rectangle()
                    .fill(SyncFlowColors.divider)
                    .frame(height: 1)
            }

            // Search bar
            if showSearch {
                HStack {
                    Image(systemName: "magnifyingglass")
                        .foregroundColor(.secondary)
                    TextField("Search in conversation...", text: $searchText)
                        .textFieldStyle(.plain)
                    if !searchText.isEmpty {
                        Button(action: { searchText = "" }) {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundColor(.secondary)
                        }
                        .buttonStyle(.plain)
                    }
                    Button(action: { showSearch = false; searchText = "" }) {
                        Text("Done")
                    }
                }
                .padding(.horizontal)
                .padding(.vertical, 8)
                .background(Color(nsColor: .controlBackgroundColor))

                Rectangle()
                    .fill(SyncFlowColors.divider)
                    .frame(height: 1)
            }

            if isSelectionMode {
                HStack {
                    Text("\(selectedMessageIds.count) selected")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Spacer()
                    Button("Select All") {
                        let allIds = messageStore.messages(for: conversation).map { $0.id }
                        selectedMessageIds = Set(allIds)
                    }
                    Button("Clear") {
                        selectedMessageIds.removeAll()
                    }
                    Button("Done") {
                        selectedMessageIds.removeAll()
                        isSelectionMode = false
                    }
                    Button("Delete") {
                        showBulkDeleteConfirmation = true
                    }
                    .disabled(selectedMessageIds.isEmpty)
                }
                .padding(.horizontal)
                .padding(.vertical, 6)
                .background(Color(nsColor: .controlBackgroundColor))

                Divider()
            }

            // =================================================================
            // MESSAGE LIST
            // Scrollable area containing message bubbles, pinned messages,
            // and load more button. Uses LazyVStack for performance.
            // =================================================================
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 16) {
                        // Loading indicator
                        if isLoadingMessages && cachedMessages.isEmpty {
                            VStack {
                                ProgressView()
                                    .scaleEffect(0.8)
                                Text("Loading messages...")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                            .padding(.top, 100)
                        }

                        // Pinned messages section
                        if !cachedPinnedMessages.isEmpty {
                            VStack(alignment: .leading, spacing: 8) {
                                HStack {
                                    Image(systemName: "pin.fill")
                                        .foregroundColor(.orange)
                                    Text("Pinned Messages")
                                        .font(.caption)
                                        .fontWeight(.semibold)
                                        .foregroundColor(.secondary)
                                    Spacer()
                                }
                                .padding(.horizontal)

                                ForEach(cachedPinnedMessages) { message in
                                    PinnedMessageRow(
                                        message: message,
                                        onTap: {
                                            // Scroll to message (would need implementation)
                                        },
                                        onUnpin: {
                                            messageStore.togglePinMessage(message)
                                        }
                                    )
                                }
                            }
                            .padding(.vertical, 8)
                            .background(Color.orange.opacity(0.05))
                            .cornerRadius(12)
                            .padding(.horizontal)

                            Divider()
                                .padding(.vertical, 8)
                        }

                        // Load more button at the top
                        if hasMoreMessages() {
                            Button(action: loadMoreMessages) {
                                HStack {
                                    if isLoadingMore {
                                        ProgressView()
                                            .scaleEffect(0.7)
                                    } else {
                                        Image(systemName: "arrow.up.circle")
                                        Text("Load earlier messages")
                                    }
                                }
                                .font(.caption)
                                .foregroundColor(.blue)
                                .padding(.vertical, 8)
                            }
                            .buttonStyle(.plain)
                            .frame(maxWidth: .infinity)
                        }

                        ForEach(cachedMessages) { message in
                            MessageBubble(
                                message: message,
                                searchText: searchText,
                                reaction: messageStore.messageReactions[message.id],
                                readReceipt: messageStore.readReceipts[message.id],
                                onReact: { reaction in
                                    messageStore.setReaction(messageId: message.id, reaction: reaction)
                                },
                                onReply: {
                                    replyToMessage = message
                                },
                                onDelete: {
                                    messageStore.deleteMessage(message)
                                },
                                isPinned: messageStore.isMessagePinned(message),
                                onTogglePin: {
                                    messageStore.togglePinMessage(message)
                                },
                                selectionMode: isSelectionMode,
                                isBulkSelected: selectedMessageIds.contains(message.id),
                                onToggleSelect: {
                                    if selectedMessageIds.contains(message.id) {
                                        selectedMessageIds.remove(message.id)
                                    } else {
                                        selectedMessageIds.insert(message.id)
                                    }
                                }
                            )
                                .id(message.id)
                        }
                    }
                    .padding()
                }
                .onAppear {
                    // Update cached messages first (async)
                    updateCachedMessagesAsync()
                    loadPreferredSendAddress()

                    // Mark as read when opening conversation
                    messageStore.markConversationAsRead(conversation)
                }
                .onChange(of: cachedMessages.count) { newCount in
                    // Scroll to bottom when messages load
                    if newCount > 0, let lastMessage = cachedMessages.last {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                            proxy.scrollTo(lastMessage.id, anchor: .bottom)
                        }
                    }
                }
                .onChange(of: conversation.id) { _ in
                    // Reset message count when switching conversations
                    displayedMessageCount = initialMessageCount
                    isLoadingMessages = true
                    cachedMessages = []
                    cachedPinnedMessages = []
                    replyToMessage = nil
                    isSelectionMode = false
                    selectedMessageIds.removeAll()
                    updateCachedMessagesAsync()
                    loadPreferredSendAddress()
                }
                .onChange(of: searchText) { _ in
                    updateCachedMessagesAsync()
                }
                .onChange(of: messageStore.messages.count) { _ in
                    updateCachedMessagesAsync()
                }
            }

            Divider()

            // =================================================================
            // COMPOSE AREA
            // Templates popover, attachment preview, reply bar, voice recording,
            // and main compose bar
            // =================================================================

            // Templates popover - shows pre-defined message templates
            if appState.showTemplates {
                TemplatesView(
                    onSelect: { template in
                        messageText = template.content
                        appState.showTemplates = false
                    },
                    onDismiss: { appState.showTemplates = false }
                )
                .frame(height: 200)
                .transition(.move(edge: .bottom))
            }

            // Attachment preview
            if let attachment = selectedAttachment {
                AttachmentPreviewBar(
                    attachment: attachment,
                    onRemove: { selectedAttachment = nil }
                )
            }

            if let replyMessage = replyToMessage {
                ReplyPreviewBar(
                    message: replyMessage,
                    onClear: { replyToMessage = nil }
                )
            }

            // Voice recording view or Compose bar
            if isRecordingVoice {
                VoiceRecordingView(
                    audioRecorder: audioRecorder,
                    onSend: { url, duration in
                        isRecordingVoice = false
                        Task {
                            await sendVoiceMessage(url: url, duration: duration)
                        }
                    },
                    onCancel: {
                        isRecordingVoice = false
                    }
                )
                .transition(.move(edge: .bottom).combined(with: .opacity))
            } else {
                ComposeBar(
                    messageText: $messageText,
                    isSending: isSending,
                    showTemplates: $appState.showTemplates,
                    showEmojiPicker: $showEmojiPicker,
                    hasAttachment: selectedAttachment != nil,
                    onAttachmentTap: { showAttachmentPicker = true },
                    onMicrophoneTap: {
                        startVoiceRecording()
                    },
                    onSend: {
                        await sendMessage()
                    },
                    onSchedule: { scheduledDate in
                        scheduleMessage(for: scheduledDate)
                    },
                    recipientName: conversation.contactName,
                    recipientNumber: conversation.address
                )
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
        .background(
            RoundedRectangle(cornerRadius: 32, style: .continuous)
                .fill(SyncFlowColors.surface)
                .shadow(color: Color.black.opacity(0.16), radius: 28, x: 0, y: 12)
        )
        .background(SyncFlowColors.conversationBackground)
        .animation(.spring(response: 0.3), value: isRecordingVoice)
        .fileImporter(
            isPresented: $showAttachmentPicker,
            allowedContentTypes: [.image, .movie, .audio],
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case .success(let urls):
                if let url = urls.first {
                    loadAttachment(from: url)
                }
            case .failure(let error):
                print("Error selecting file: \(error)")
            }
        }
        // Drag & drop support
        .onDrop(of: [.image, .movie, .audio, .fileURL], isTargeted: $isDragOver) { providers in
            handleDrop(providers: providers)
            return true
        }
        .alert("Delete Selected Messages?", isPresented: $showBulkDeleteConfirmation) {
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive) {
                let allMessages = messageStore.messages(for: conversation)
                let toDelete = allMessages.filter { selectedMessageIds.contains($0.id) }
                messageStore.deleteMessages(toDelete)
                selectedMessageIds.removeAll()
                isSelectionMode = false
            }
        } message: {
            Text("This will permanently delete the selected messages and sync the deletion to connected devices.")
        }
        .overlay(
            // Drop zone indicator
            Group {
                if isDragOver {
                    ZStack {
                        Color.blue.opacity(0.1)
                        VStack(spacing: 12) {
                            Image(systemName: "square.and.arrow.down")
                                .font(.system(size: 48))
                                .foregroundColor(.blue)
                            Text("Drop image or file to attach")
                                .font(.headline)
                                .foregroundColor(.blue)
                        }
                    }
                    .cornerRadius(12)
                    .overlay(
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(Color.blue, style: StrokeStyle(lineWidth: 3, dash: [10]))
                    )
                    .padding()
                }
            }
        )
        .onAppear {
            appState.continuityService.publishConversation(
                address: conversation.address,
                contactName: conversation.contactName,
                threadId: nil,
                draft: messageText
            )
            applyContinuityDraftIfNeeded()
            appState.refreshE2eeKeyStatus()
        }
        .onChange(of: conversation.address) { _ in
            appState.continuityService.publishConversation(
                address: conversation.address,
                contactName: conversation.contactName,
                threadId: nil,
                draft: messageText
            )
            applyContinuityDraftIfNeeded()
        }
        .onChange(of: messageText) { newValue in
            appState.continuityService.publishConversation(
                address: conversation.address,
                contactName: conversation.contactName,
                threadId: nil,
                draft: newValue
            )
        }
        .onChange(of: appState.continuitySuggestion?.timestamp ?? 0) { _ in
            applyContinuityDraftIfNeeded()
        }
    }

    // MARK: - Drag & Drop

    /// Handles drag-and-drop of files onto the message view.
    /// Supports images and file URLs; converts dropped items into attachments.
    /// - Parameter providers: Array of NSItemProviders from the drop operation
    private func handleDrop(providers: [NSItemProvider]) {
        for provider in providers {
            // Try to load as image first
            if provider.hasItemConformingToTypeIdentifier("public.image") {
                provider.loadItem(forTypeIdentifier: "public.image", options: nil) { item, error in
                    if let url = item as? URL {
                        DispatchQueue.main.async {
                            self.loadAttachment(from: url)
                        }
                    } else if let data = item as? Data {
                        DispatchQueue.main.async {
                            self.loadAttachmentFromData(data, type: "image")
                        }
                    }
                }
                return
            }

            // Try to load as file URL
            if provider.hasItemConformingToTypeIdentifier("public.file-url") {
                provider.loadItem(forTypeIdentifier: "public.file-url", options: nil) { item, error in
                    if let data = item as? Data,
                       let urlString = String(data: data, encoding: .utf8),
                       let url = URL(string: urlString) {
                        DispatchQueue.main.async {
                            self.loadAttachment(from: url)
                        }
                    } else if let url = item as? URL {
                        DispatchQueue.main.async {
                            self.loadAttachment(from: url)
                        }
                    }
                }
                return
            }
        }
    }

    /// Creates an attachment from raw data (e.g., from drag-and-drop).
    /// Saves data to a temporary file and creates SelectedAttachment object.
    /// - Parameters:
    ///   - data: The file data
    ///   - type: The attachment type (e.g., "image")
    private func loadAttachmentFromData(_ data: Data, type: String) {
        let fileName = "dropped_\(type)_\(Int(Date().timeIntervalSince1970))"
        var contentType = "application/octet-stream"
        var attachmentType = "file"
        var fileExtension = ""

        if type == "image" {
            contentType = "image/png"
            attachmentType = "image"
            fileExtension = ".png"
        }

        // Save to temp file to get a URL
        let tempDir = FileManager.default.temporaryDirectory
        let tempURL = tempDir.appendingPathComponent(fileName + fileExtension)

        do {
            try data.write(to: tempURL)

            var thumbnail: NSImage? = nil
            if attachmentType == "image" {
                thumbnail = NSImage(data: data)
            }

            selectedAttachment = SelectedAttachment(
                url: tempURL,
                data: data,
                fileName: fileName + fileExtension,
                contentType: contentType,
                type: attachmentType,
                thumbnail: thumbnail
            )
        } catch {
            print("Error saving dropped attachment: \(error)")
        }
    }

    // MARK: - Load Attachment

    /// Loads an attachment from a file URL (from file picker or drag-and-drop).
    /// Handles security-scoped resource access for sandbox compliance.
    /// - Parameter url: The file URL to load
    private func loadAttachment(from url: URL) {
        guard url.startAccessingSecurityScopedResource() else {
            print("Failed to access security scoped resource")
            return
        }

        defer { url.stopAccessingSecurityScopedResource() }

        do {
            let data = try Data(contentsOf: url)
            let fileName = url.lastPathComponent
            let contentType = getContentType(for: url)
            let type = getAttachmentType(for: contentType)

            // Load thumbnail for images
            var thumbnail: NSImage? = nil
            if type == "image", let image = NSImage(data: data) {
                thumbnail = image
            }

            selectedAttachment = SelectedAttachment(
                url: url,
                data: data,
                fileName: fileName,
                contentType: contentType,
                type: type,
                thumbnail: thumbnail
            )
        } catch {
            print("Error loading attachment: \(error)")
        }
    }

    /// Determines the MIME content type based on file extension.
    /// - Parameter url: The file URL to analyze
    /// - Returns: MIME type string (e.g., "image/jpeg")
    private func getContentType(for url: URL) -> String {
        let ext = url.pathExtension.lowercased()
        switch ext {
        case "jpg", "jpeg": return "image/jpeg"
        case "png": return "image/png"
        case "gif": return "image/gif"
        case "webp": return "image/webp"
        case "mp4", "m4v": return "video/mp4"
        case "mov": return "video/quicktime"
        case "3gp": return "video/3gpp"
        case "mp3": return "audio/mpeg"
        case "m4a": return "audio/mp4"
        case "wav": return "audio/wav"
        case "ogg": return "audio/ogg"
        default: return "application/octet-stream"
        }
    }

    /// Determines the attachment category from MIME type.
    /// - Parameter contentType: MIME type string
    /// - Returns: Category string ("image", "video", "audio", or "file")
    private func getAttachmentType(for contentType: String) -> String {
        if contentType.hasPrefix("image/") { return "image" }
        if contentType.hasPrefix("video/") { return "video" }
        if contentType.hasPrefix("audio/") { return "audio" }
        return "file"
    }

    // MARK: - Voice Recording

    /// Starts voice recording for a voice message.
    /// Checks microphone permission before starting.
    private func startVoiceRecording() {
        guard audioRecorder.hasPermission else {
            audioRecorder.requestPermission()
            return
        }
        audioRecorder.startRecording()
        isRecordingVoice = true
    }

    /// Sends a recorded voice message as an MMS attachment.
    /// - Parameters:
    ///   - url: URL of the recorded audio file
    ///   - duration: Duration of the recording
    private func sendVoiceMessage(url: URL, duration: TimeInterval) async {
        guard let userId = appState.userId else { return }

        isSending = true

        do {
            let data = try Data(contentsOf: url)
            let fileName = "voice_\(Int(Date().timeIntervalSince1970)).m4a"

            let attachment = SelectedAttachment(
                url: url,
                data: data,
                fileName: fileName,
                contentType: "audio/mp4",
                type: "audio",
                thumbnail: nil
            )

            let replyPrefix = replyToMessage.map { buildReplyPrefix(for: $0) }
            let body = mergeReplyPrefix(prefix: replyPrefix, body: "")

            try await messageStore.sendMmsMessage(
                userId: userId,
                to: activeSendAddress,
                body: body,
                attachment: attachment
            )

            // Cleanup the temp file
            audioRecorder.cleanup()
            replyToMessage = nil
        } catch {
            print("Error sending voice message: \(error)")
        }

        isSending = false
    }

    // MARK: - Send Message

    /// Sends the current message (SMS or MMS with attachment).
    /// Handles reply prefixes, clears compose state on success.
    private func sendMessage() async {
        let hasText = !messageText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        let hasAttachment = selectedAttachment != nil

        guard (hasText || hasAttachment), let userId = appState.userId else {
            return
        }

        let replyPrefix = replyToMessage.map { buildReplyPrefix(for: $0) }
        let body = mergeReplyPrefix(prefix: replyPrefix, body: messageText)
        let attachment = selectedAttachment
        isSending = true

        do {
            if let attachment = attachment {
                // Send MMS with attachment
                try await messageStore.sendMmsMessage(
                    userId: userId,
                    to: activeSendAddress,
                    body: body,
                    attachment: attachment
                )
            } else {
                // Send regular SMS
                try await messageStore.sendMessage(
                    userId: userId,
                    to: activeSendAddress,
                    body: body
                )
            }
            // Clear only after successful send
            messageText = ""
            selectedAttachment = nil
            replyToMessage = nil
        } catch {
            print("Error sending message: \(error)")
            // On error, keep the attachment so user can retry
        }

        isSending = false
    }

    // MARK: - Schedule Message

    /// Schedules a message to be sent at a future time.
    /// - Parameter date: The date/time to send the message
    private func scheduleMessage(for date: Date) {
        let hasText = !messageText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        guard hasText else { return }

        let replyPrefix = replyToMessage.map { buildReplyPrefix(for: $0) }
        let body = mergeReplyPrefix(prefix: replyPrefix, body: messageText)

        Task {
            do {
                try await appState.scheduledMessageService.scheduleMessage(
                    recipientNumber: activeSendAddress,
                    recipientName: conversation.contactName,
                    message: body,
                    scheduledTime: date
                )
                // Clear after scheduling
                await MainActor.run {
                    messageText = ""
                    replyToMessage = nil
                }
            } catch {
                print("Error scheduling message: \(error)")
            }
        }
    }

    // MARK: - Reply Helpers

    /// Combines reply prefix with message body.
    /// - Parameters:
    ///   - prefix: Optional reply quote prefix (e.g., "> John: Hello")
    ///   - body: The main message body
    /// - Returns: Combined message string
    private func mergeReplyPrefix(prefix: String?, body: String) -> String {
        guard let prefix = prefix, !prefix.isEmpty else {
            return body
        }

        let trimmedBody = body.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmedBody.isEmpty {
            return prefix
        }

        return "\(prefix)\n\(trimmedBody)"
    }

    /// Builds a reply prefix string for quoting a message.
    /// Format: "> [Sender]: [Snippet]"
    /// - Parameter message: The message being replied to
    /// - Returns: Formatted reply prefix string
    private func buildReplyPrefix(for message: Message) -> String {
        let sender = replySenderName(for: message)
        let snippet = replySnippet(for: message)
        return "> \(sender): \(snippet)"
    }

    /// Gets the display name for the sender in a reply context.
    /// - Parameter message: The message to get sender name from
    /// - Returns: "You" for sent messages, contact name/address for received
    private func replySenderName(for message: Message) -> String {
        if message.isReceived {
            return message.contactName ?? message.address
        }
        return "You"
    }

    /// Creates a short snippet of the message for the reply quote.
    /// Truncates to 80 characters and handles attachments.
    /// - Parameter message: The message to create snippet from
    /// - Returns: Short preview string
    private func replySnippet(for message: Message) -> String {
        let trimmed = message.body.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty {
            let singleLine = trimmed.replacingOccurrences(of: "\n", with: " ")
            return String(singleLine.prefix(80))
        }

        if message.hasAttachments {
            if let attachment = message.attachments?.first {
                if attachment.isImage {
                    return "Photo"
                }
                if attachment.isVideo {
                    return "Video"
                }
                if attachment.isAudio {
                    return "Audio"
                }
                if attachment.isVCard {
                    return "Contact"
                }
            }
            return "Attachment"
        }

        return "Message"
    }

    // MARK: - Continuity Helpers

    /// Applies a draft message from continuity state if applicable.
    /// Part of Handoff feature - allows continuing draft from another device.
    private func applyContinuityDraftIfNeeded() {
        guard let suggestion = appState.continuitySuggestion else { return }

        let normalizedConversation = normalizeAddress(conversation.address)
        let normalizedSuggestion = normalizeAddress(suggestion.address)
        let matches = conversation.address == suggestion.address ||
            (!normalizedConversation.isEmpty && normalizedConversation == normalizedSuggestion)

        guard matches else { return }

        if messageText.isEmpty, let draft = suggestion.draft, !draft.isEmpty {
            messageText = draft
        }
        appState.dismissContinuitySuggestion()
    }

    /// Normalizes a phone number by keeping only numeric characters.
    /// - Parameter value: The address string to normalize
    /// - Returns: String containing only digits
    private func normalizeAddress(_ value: String) -> String {
        return value.filter { $0.isNumber }
    }
}

// MARK: - Selected Attachment Model

/// Represents an attachment selected by the user for sending.
/// Contains all necessary data for MMS transmission.
struct SelectedAttachment {
    /// URL of the attachment file
    let url: URL
    /// Raw file data
    let data: Data
    /// Original file name
    let fileName: String
    /// MIME content type (e.g., "image/jpeg")
    let contentType: String
    /// Category: "image", "video", "audio", or "file"
    let type: String
    /// Preview thumbnail for images (nil for other types)
    let thumbnail: NSImage?
}

// MARK: - Attachment Preview Bar

/// Preview bar shown above compose area when an attachment is selected.
/// Displays thumbnail/icon, file name, size, and remove button.
struct AttachmentPreviewBar: View {
    /// The attachment to preview
    let attachment: SelectedAttachment
    /// Callback when remove button is tapped
    let onRemove: () -> Void

    /// Hover state for remove button animation
    @State private var isHoveringRemove = false

    // MARK: - Body

    var body: some View {
        HStack(spacing: 14) {
            // Thumbnail or icon with modern styling
            ZStack {
                if let thumbnail = attachment.thumbnail {
                    Image(nsImage: thumbnail)
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                        .frame(width: 56, height: 56)
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 10, style: .continuous)
                                .stroke(Color.primary.opacity(0.08), lineWidth: 1)
                        )
                } else {
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(
                            LinearGradient(
                                colors: [Color.accentColor.opacity(0.15), Color.accentColor.opacity(0.08)],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                        .frame(width: 56, height: 56)
                        .overlay(
                            Image(systemName: iconForType)
                                .font(.system(size: 22, weight: .medium))
                                .foregroundColor(.accentColor)
                        )
                }

                // File type badge
                if attachment.thumbnail != nil {
                    VStack {
                        Spacer()
                        HStack {
                            Spacer()
                            Image(systemName: iconForType)
                                .font(.system(size: 9, weight: .semibold))
                                .foregroundColor(.white)
                                .padding(4)
                                .background(
                                    Circle()
                                        .fill(Color.black.opacity(0.5))
                                )
                        }
                    }
                    .frame(width: 56, height: 56)
                    .padding(4)
                }
            }

            // File info
            VStack(alignment: .leading, spacing: 4) {
                Text(attachment.fileName)
                    .font(.system(size: 13, weight: .medium))
                    .lineLimit(1)
                    .foregroundColor(.primary)

                HStack(spacing: 6) {
                    Text(formattedSize)
                        .font(.system(size: 11))
                        .foregroundColor(.secondary)

                    if attachment.type == "video" || attachment.type == "audio" {
                        Text("•")
                            .foregroundColor(.secondary.opacity(0.5))
                        Text(attachment.type.capitalized)
                            .font(.system(size: 11))
                            .foregroundColor(.secondary)
                    }
                }
            }

            Spacer()

            // Remove button
            Button(action: onRemove) {
                Image(systemName: "xmark")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundColor(.secondary)
                    .frame(width: 24, height: 24)
                    .background(
                        Circle()
                            .fill(isHoveringRemove ? Color.primary.opacity(0.1) : Color.primary.opacity(0.05))
                    )
            }
            .buttonStyle(.plain)
            .help("Remove attachment")
            .onHover { hovering in
                withAnimation(.easeInOut(duration: 0.12)) {
                    isHoveringRemove = hovering
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(Color(nsColor: .controlBackgroundColor))
                .shadow(color: Color.black.opacity(0.04), radius: 2, x: 0, y: 1)
        )
        .padding(.horizontal, 16)
        .padding(.bottom, 4)
    }

    private var iconForType: String {
        switch attachment.type {
        case "video": return "video.fill"
        case "audio": return "waveform"
        default: return "doc.fill"
        }
    }

    private var formattedSize: String {
        let bytes = attachment.data.count
        if bytes < 1024 {
            return "\(bytes) B"
        } else if bytes < 1024 * 1024 {
            return String(format: "%.1f KB", Double(bytes) / 1024)
        } else {
            return String(format: "%.1f MB", Double(bytes) / (1024 * 1024))
        }
    }
}

// MARK: - Conversation Header

/// Header component for the message view displaying contact info and action buttons.
/// Includes: avatar, name, phone number, search, selection mode, call buttons, and more menu.
struct ConversationHeader: View {

    // MARK: - Properties

    /// The conversation being displayed
    let conversation: Conversation
    /// Message store for actions like pin/archive
    let messageStore: MessageStore
    /// Whether search bar is visible
    @Binding var showSearch: Bool
    /// All phone numbers associated with this contact
    let allAddresses: [String]
    /// User's preferred send address for multi-number contacts
    @Binding var preferredSendAddress: String?
    /// Callback when user selects a send address
    let onSelectSendAddress: (String?) -> Void
    /// Whether bulk selection mode is active
    @Binding var isSelectionMode: Bool
    /// Number of messages currently selected
    let selectedCount: Int
    /// Callback when delete button is tapped in selection mode
    let onDeleteSelected: () -> Void
    /// Callback when selection is cleared
    let onClearSelection: () -> Void

    // MARK: - Environment

    @EnvironmentObject var appState: AppState
    @State private var showCallAlert = false
    @State private var callStatus: CallRequestStatus? = nil
    @State private var isCallInProgress = false
    @State private var availableSims: [SimInfo] = []
    @State private var selectedSim: SimInfo? = nil
    @State private var showSimSelector = false
    @State private var hasLoadedSims = false
    @State private var pairedDevices: [SyncFlowDevice] = []
    @State private var hasLoadedDevices = false
    @State private var syncFlowCallError: String? = nil
    @State private var showSyncFlowCallError = false
    @State private var showContactInfo = false
    private var effectiveSendAddress: String {
        return preferredSendAddress ?? conversation.address
    }

    private var isPinned: Bool {
        messageStore.isConversationPinned(conversation, allAddresses: allAddresses)
    }

    var body: some View {
        HStack(spacing: 16) {
            // Modern avatar with gradient ring
            ZStack {
                Circle()
                    .fill(
                        LinearGradient(
                            colors: [
                                Color(hex: conversation.avatarColor ?? "#2196F3"),
                                Color(hex: conversation.avatarColor ?? "#2196F3").opacity(0.7)
                            ],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .frame(width: 44, height: 44)

                Text(conversation.initials)
                    .font(.system(size: 16, weight: .semibold, design: .rounded))
                    .foregroundColor(.white)
            }
            .shadow(color: Color(hex: conversation.avatarColor ?? "#2196F3").opacity(0.3), radius: 4, x: 0, y: 2)

            // Contact info
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(conversation.displayName)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(.primary)

                    if isPinned {
                        Image(systemName: "pin.fill")
                            .font(.system(size: 10))
                            .foregroundColor(.orange)
                    }
                }

                HStack(spacing: 4) {
                    Text(conversation.address)
                        .font(.system(size: 12))
                        .foregroundColor(.secondary)

                    if allAddresses.count > 1 {
                        Text("•")
                            .foregroundColor(.secondary.opacity(0.5))
                        Text("\(allAddresses.count) numbers")
                            .font(.system(size: 11))
                            .foregroundColor(.secondary.opacity(0.8))
                    }
                }
            }

            Spacer()

            // Action buttons in a grouped container
            HStack(spacing: 2) {
                // Search button
                HeaderActionButton(
                    icon: "magnifyingglass",
                    isActive: showSearch,
                    action: { showSearch.toggle() },
                    help: "Search in conversation"
                )

                // Selection mode button
                HeaderActionButton(
                    icon: isSelectionMode ? "checkmark.circle.fill" : "checkmark.circle",
                    isActive: isSelectionMode,
                    action: {
                        isSelectionMode.toggle()
                        if !isSelectionMode {
                            onClearSelection()
                        }
                    },
                    help: isSelectionMode ? "Exit selection" : "Select messages"
                )

                if isSelectionMode {
                    HeaderActionButton(
                        icon: "trash",
                        isActive: false,
                        activeColor: .red,
                        action: { onDeleteSelected() },
                        help: "Delete selected"
                    )
                    .disabled(selectedCount == 0)
                    .opacity(selectedCount > 0 ? 1 : 0.4)
                }

                // Info button
                HeaderActionButton(
                    icon: "info.circle",
                    isActive: showContactInfo,
                    action: { showContactInfo.toggle() },
                    help: "Contact info"
                )
                .popover(isPresented: $showContactInfo) {
                    ContactInfoPopover(
                        conversation: conversation,
                        allAddresses: allAddresses,
                        preferredSendAddress: preferredSendAddress,
                        onSelectSendAddress: onSelectSendAddress,
                        onCopyNumber: copyContactNumber
                    )
                }
            }
            .padding(.horizontal, 4)
            .padding(.vertical, 4)
            .background(Color(nsColor: .controlBackgroundColor).opacity(0.6))
            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))

            // Call buttons
            HStack(spacing: 2) {
                // Phone call button
                if availableSims.count > 1 {
                    Menu {
                        ForEach(availableSims) { sim in
                            Button(action: {
                                selectedSim = sim
                                initiateCall()
                            }) {
                                HStack {
                                    Text(sim.formattedDisplayName)
                                    if selectedSim?.id == sim.id {
                                        Image(systemName: "checkmark")
                                    }
                                }
                            }
                        }
                    } label: {
                        HeaderCallButton(
                            icon: isCallInProgress ? "phone.fill.arrow.up.right" : "phone.fill",
                            color: isCallInProgress ? .green : .blue,
                            isDisabled: isCallInProgress
                        )
                    }
                    .buttonStyle(.plain)
                    .help("Choose SIM card to call from")
                } else {
                    Button(action: { initiateCall() }) {
                        HeaderCallButton(
                            icon: isCallInProgress ? "phone.fill.arrow.up.right" : "phone.fill",
                            color: isCallInProgress ? .green : .blue,
                            isDisabled: isCallInProgress
                        )
                    }
                    .buttonStyle(.plain)
                    .help("Call via Android phone")
                    .disabled(isCallInProgress)
                }

                // Video call button
                Button(action: {
                    initiateSyncFlowCall(isVideo: true)
                }) {
                    HeaderCallButton(
                        icon: "video.fill",
                        color: .green,
                        isDisabled: appState.userId == nil
                    )
                }
                .buttonStyle(.plain)
                .help("SyncFlow video call")
                .disabled(appState.userId == nil)
            }
            .padding(.horizontal, 4)
            .padding(.vertical, 4)
            .background(Color(nsColor: .controlBackgroundColor).opacity(0.6))
            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))

            // More options menu
            Menu {
                Button(action: {
                    messageStore.togglePin(conversation)
                }) {
                    Label(isPinned ? "Unpin" : "Pin", systemImage: isPinned ? "pin.slash" : "pin")
                }

                Button(action: {
                    messageStore.toggleArchive(conversation)
                }) {
                    Label("Archive", systemImage: "archivebox")
                }

                Divider()

                Button(role: .destructive, action: {
                    messageStore.toggleBlock(conversation)
                }) {
                    Label("Block", systemImage: "hand.raised")
                }
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundColor(.secondary)
                    .frame(width: 32, height: 32)
                    .background(Color(nsColor: .controlBackgroundColor).opacity(0.6))
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            }
            .buttonStyle(.plain)
            .help("More options")
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(
            ZStack {
                SyncFlowColors.chatHeaderBackground

                // Subtle bottom border
                VStack {
                    Spacer()
                    Rectangle()
                        .fill(Color.primary.opacity(0.06))
                        .frame(height: 1)
                }
            }
        )
        .alert("Calling \(conversation.displayName)", isPresented: $showCallAlert) {
            Button("OK") {
                showCallAlert = false
                isCallInProgress = false
            }
        } message: {
            if let status = callStatus {
                Text(status.description)
            }
        }
        .alert("Call Failed", isPresented: $showSyncFlowCallError) {
            Button("OK") {
                showSyncFlowCallError = false
                syncFlowCallError = nil
            }
        } message: {
            if let error = syncFlowCallError {
                Text(error)
            }
        }
        .onAppear {
            // Only load SIMs once per conversation
            if !hasLoadedSims {
                loadAvailableSims()
            }
            // Load paired devices for SyncFlow calls
            if !hasLoadedDevices {
                loadPairedDevices()
            }
        }
    }

    private func loadAvailableSims() {
        guard let userId = appState.userId else { return }
        guard !hasLoadedSims else { return } // Prevent multiple loads

        hasLoadedSims = true

        Task {
            do {
                let sims = try await FirebaseService.shared.getAvailableSims(userId: userId)
                await MainActor.run {
                    availableSims = sims
                    // Select first SIM as default if available
                    if selectedSim == nil {
                        selectedSim = sims.first
                    }
                    // SIMs loaded
                }
            } catch {
                print("Error loading SIMs: \(error)")
                hasLoadedSims = false // Allow retry on error
            }
        }
    }

    private func initiateCall() {
        guard let userId = appState.userId else {
            print("Error: No user ID")
            return
        }

        isCallInProgress = true

        Task {
            do {
                // Use selected SIM if available and there are multiple SIMs
                let simId = (availableSims.count > 1) ? selectedSim?.subscriptionId : nil

                try await FirebaseService.shared.requestCall(
                    userId: userId,
                    to: effectiveSendAddress,
                    contactName: conversation.contactName,
                    simSubscriptionId: simId
                )

                // Show success with SIM info
                let simInfo = selectedSim.map { " using \($0.displayName)" } ?? ""
                callStatus = .completed
                showCallAlert = true

                // Call initiated

                // Auto-dismiss after 2 seconds
                DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                    showCallAlert = false
                    isCallInProgress = false
                }
            } catch {
                callStatus = .failed(error: error.localizedDescription)
                showCallAlert = true
                isCallInProgress = false
            }
        }
    }

    private func loadPairedDevices() {
        guard let userId = appState.userId else { return }
        guard !hasLoadedDevices else { return }

        hasLoadedDevices = true

        Task {
            do {
                let devices = try await FirebaseService.shared.getPairedDevices(userId: userId)
                await MainActor.run {
                    pairedDevices = devices
                    // Devices loaded
                }
            } catch {
                print("Error loading paired devices: \(error)")
                hasLoadedDevices = false
            }
        }
    }

    private func initiateSyncFlowCall(isVideo: Bool) {
        // Start user-to-user video call using the phone number
        let phoneNumber = conversation.address
        let recipientName = conversation.displayName

        Task {
            do {
                let callId = try await appState.syncFlowCallManager.startCallToUser(
                    recipientPhoneNumber: phoneNumber,
                    recipientName: recipientName,
                    isVideo: isVideo
                )
                // Call started

                // Show the call view
                await MainActor.run {
                    appState.showSyncFlowCallView = true
                }
            } catch {
                print("Failed to start call: \(error.localizedDescription)")
                await MainActor.run {
                    syncFlowCallError = error.localizedDescription
                    showSyncFlowCallError = true
                }
            }
        }
    }

    private func copyContactNumber() {
        let target = preferredSendAddress ?? conversation.address
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(target, forType: .string)
    }
}

// MARK: - Pinned Message Row

/// Compact row for displaying pinned messages at the top of the message list.
/// Shows sender, timestamp, preview, and unpin button.
struct PinnedMessageRow: View {
    /// The pinned message to display
    let message: Message
    /// Callback when row is tapped (to scroll to message)
    let onTap: () -> Void
    /// Callback when unpin button is tapped
    let onUnpin: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            // Message preview
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(message.isReceived ? (message.contactName ?? message.address) : "You")
                        .font(.caption)
                        .fontWeight(.medium)
                        .foregroundColor(.primary)

                    Spacer()

                    Text(message.formattedTime)
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }

                Text(message.body)
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .lineLimit(2)
            }

            // Unpin button
            Button(action: onUnpin) {
                Image(systemName: "pin.slash")
                    .font(.caption)
                    .foregroundColor(.orange)
            }
            .buttonStyle(.plain)
            .help("Unpin message")
        }
        .padding(.horizontal)
        .padding(.vertical, 6)
        .background(Color(nsColor: .controlBackgroundColor).opacity(0.5))
        .cornerRadius(8)
        .padding(.horizontal)
        .onTapGesture {
            onTap()
        }
    }
}

// MARK: - Message Bubble

/// Individual message bubble component displaying message content with interactions.
/// Supports text, attachments, reactions, reply quotes, link previews, and context menu.
///
/// Features:
/// - Adaptive styling for sent vs received messages
/// - Search text highlighting
/// - Reaction emoji display and interaction
/// - Read receipt indicators
/// - Link detection and preview cards
/// - Reply quote parsing and display
/// - Context menu for copy, reply, pin, delete, etc.
/// - Double-tap for quick thumbs-up reaction
///
/// Performance Note: Link previews are loaded with a 500ms delay to avoid
/// blocking initial render. Formatted body is cached to avoid re-computation.
struct MessageBubble: View {

    // MARK: - Properties

    /// The message to display
    let message: Message
    /// Search text for highlighting (empty string if not searching)
    var searchText: String = ""
    /// Current reaction emoji on this message (nil if none)
    let reaction: String?
    /// Read receipt data for this message
    let readReceipt: ReadReceipt?
    /// Callback when user reacts to message (nil to remove reaction)
    let onReact: (String?) -> Void
    /// Callback when user taps reply (optional)
    var onReply: (() -> Void)? = nil
    /// Callback when user deletes message (optional, only for sent messages)
    var onDelete: (() -> Void)? = nil
    /// Whether this message is pinned
    var isPinned: Bool = false
    /// Callback to toggle pin status
    var onTogglePin: (() -> Void)? = nil
    /// Whether bulk selection mode is active
    var selectionMode: Bool = false
    /// Whether this message is selected in bulk mode
    var isBulkSelected: Bool = false
    /// Callback to toggle selection state
    var onToggleSelect: (() -> Void)? = nil

    // MARK: - Local State

    /// Whether mouse is hovering over this bubble
    @State private var isHovering = false
    /// Whether delete confirmation alert is shown
    @State private var showDeleteConfirmation = false
    /// Loaded link preview data for URLs in message
    @State private var linkPreviews: [LinkPreview] = []
    /// Whether link previews are currently loading
    @State private var loadingPreviews = false
    /// Cached formatted message body (for search highlighting)
    @State private var cachedFormattedBody: AttributedString? = nil
    /// Last search text used for cache validation
    @State private var lastSearchText: String = ""

    // MARK: - Constants

    /// Available reaction emoji options
    private let reactionOptions = ["👍", "❤️", "😂", "😮", "😢", "😡"]

    // MARK: - Helper Types

    /// Represents parsed reply quote content
    private struct ReplyContent {
        let sender: String
        let snippet: String
    }

    /// Represents a parsed message with optional reply and body
    private struct ParsedMessage {
        let reply: ReplyContent?
        let body: String
    }

    // MARK: - Computed Properties

    /// Parses the message body to extract reply quote (if present)
    private var parsedMessage: ParsedMessage {
        parseReplyBody(message.body)
    }

    /// The reply quote content (nil if not a reply)
    private var replyContent: ReplyContent? {
        parsedMessage.reply
    }

    /// The main message body (without reply prefix)
    private var displayBody: String {
        parsedMessage.body
    }

    /// Whether the display body contains any URLs
    private var displayBodyHasLinks: Bool {
        hasLinks(in: displayBody)
    }

    /// Reusable bubble shape with consistent corner radius
    private var bubbleShape: RoundedRectangle {
        RoundedRectangle(cornerRadius: SyncFlowSpacing.bubbleRadius, style: .continuous)
    }

    // MARK: - Subviews

    /// Creates the background fill for a message bubble.
    /// Applies gradient if enabled in settings, otherwise solid color.
    @ViewBuilder
    private func bubbleBackground(isReceived: Bool) -> some View {
        if SyncFlowColors.bubbleGradientEnabled {
            LinearGradient(
                colors: SyncFlowColors.bubbleGradientColors(isReceived: isReceived),
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        } else {
            SyncFlowColors.bubbleFill(isReceived: isReceived)
        }
    }

    /// Creates a styled message bubble container with padding and background.
    /// - Parameters:
    ///   - isReceived: Whether this is a received (vs sent) message
    ///   - content: The content to display inside the bubble
    @ViewBuilder
    private func messageBubble<Content: View>(isReceived: Bool, @ViewBuilder content: () -> Content) -> some View {
        content()
            .padding(.horizontal, SyncFlowSpacing.bubbleHorizontal)
            .padding(.vertical, SyncFlowSpacing.bubbleVertical)
            .foregroundColor(SyncFlowColors.bubbleTextColor(isReceived: isReceived))
            .background {
                bubbleBackground(isReceived: isReceived)
            }
            .clipShape(bubbleShape)
    }

    // MARK: - Body

    var body: some View {
        HStack(alignment: .bottom, spacing: 8) {
            if selectionMode && message.isReceived {
                Button(action: { onToggleSelect?() }) {
                    Image(systemName: isBulkSelected ? "checkmark.circle.fill" : "circle")
                        .foregroundColor(isBulkSelected ? .blue : .secondary)
                }
                .buttonStyle(.plain)
            }

            if !message.isReceived {
                Spacer()
            }

            VStack(alignment: message.isReceived ? .leading : .trailing, spacing: 4) {
                if let replyContent = replyContent {
                    ReplyQuoteBlock(
                        reply: replyContent,
                        isReceived: message.isReceived
                    )
                    if message.hasAttachments || !displayBody.isEmpty {
                        Spacer(minLength: 4)
                    }
                }

                // MMS Attachments
                if message.hasAttachments, let attachments = message.attachments {
                    ForEach(attachments) { attachment in
                        AttachmentView(attachment: attachment)
                    }
                }

                if !displayBody.isEmpty {
                    if displayBodyHasLinks {
                        messageBubble(isReceived: message.isReceived) {
                            ClickableMessageText(text: displayBody)
                        }

                        // Link previews
                        ForEach(linkPreviews) { preview in
                            LinkPreviewCard(preview: preview, isSent: !message.isReceived)
                        }
                    } else {
                        // Use simple text for performance - only format if searching
                        if searchText.isEmpty {
                            messageBubble(isReceived: message.isReceived) {
                                Text(displayBody)
                            }
                        } else {
                            messageBubble(isReceived: message.isReceived) {
                                Text(formattedMessageBody)
                            }
                        }
                    }
                }

                HStack(spacing: 4) {
                    // Pin indicator
                    if isPinned {
                        Image(systemName: "pin.fill")
                            .font(.caption2)
                            .foregroundColor(.orange)
                    }

                    Text(message.formattedTime)
                        .font(.caption2)
                        .foregroundColor(.secondary)

                    // Delivered/Read indicator for sent messages
                    if !message.isReceived {
                        Image(systemName: "checkmark")
                            .font(.caption2)
                            .foregroundColor(.secondary)
                    }
                }

                // E2EE failure warning
                if message.e2eeFailed {
                    HStack(spacing: 4) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .font(.caption2)
                            .foregroundColor(.orange)
                        Text("Not encrypted")
                            .font(.caption2)
                            .foregroundColor(.orange)
                    }
                    .help(message.e2eeFailureReason ?? "End-to-end encryption failed")
                }

                if message.isReceived,
                   let receipt = readReceipt,
                   receipt.readBy != "macos",
                   receipt.readAt > 0 {
                    Text("Read on \(receipt.readDeviceName ?? receipt.readBy) at \(formattedReadTime(receipt.readAt))")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                        .frame(maxWidth: .infinity, alignment: .trailing)
                }

                if let reaction = reaction, !reaction.isEmpty {
                    Text(reaction)
                        .font(.caption)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 2)
                        .background(Color(nsColor: .controlBackgroundColor))
                        .cornerRadius(10)
                }
            }
            .frame(maxWidth: 500, alignment: message.isReceived ? .leading : .trailing)
            .contextMenu {
                ForEach(reactionOptions, id: \.self) { option in
                    Button(action: {
                        onReact(option)
                    }) {
                        Text("React \(option)")
                    }
                }

                if reaction != nil {
                    Button(action: {
                        onReact(nil)
                    }) {
                        Text("Remove Reaction")
                    }
                }

                Divider()

                Button(action: {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(message.body, forType: .string)
                }) {
                    Label("Copy Message", systemImage: "doc.on.doc")
                }

                Button(action: {
                    onReply?()
                }) {
                    Label("Reply", systemImage: "arrowshape.turn.up.left")
                }

                Divider()

                if displayBodyHasLinks {
                    Button(action: {
                        copyAllLinks()
                    }) {
                        Label("Copy Links", systemImage: "link")
                    }
                }

                Button(action: {
                    selectMessage()
                }) {
                    Label("Select Text", systemImage: "selection.pin.in.out")
                }

                Divider()

                Button(action: {
                    shareMessage()
                }) {
                    Label("Share...", systemImage: "square.and.arrow.up")
                }

                Divider()

                Button(action: {
                    onTogglePin?()
                }) {
                    Label(isPinned ? "Unpin Message" : "Pin Message", systemImage: isPinned ? "pin.slash" : "pin")
                }

                if !message.isReceived, let onDelete = onDelete {
                    Divider()

                    Button(role: .destructive, action: {
                        showDeleteConfirmation = true
                    }) {
                        Label("Delete Message", systemImage: "trash")
                    }
                }
            }
            .alert("Delete Message", isPresented: $showDeleteConfirmation) {
                Button("Cancel", role: .cancel) { }
                Button("Delete", role: .destructive) {
                    onDelete?()
                }
            } message: {
                Text("Are you sure you want to delete this message? This cannot be undone.")
            }
            .onHover { hovering in
                isHovering = hovering
            }
            .onTapGesture(count: 2) {
                toggleQuickReaction()
            }

            // Quick action buttons on hover
            if isHovering && !message.isReceived {
                Button(action: {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(message.body, forType: .string)
                }) {
                    Image(systemName: "doc.on.doc")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.plain)
                .help("Copy")
            }

            if message.isReceived {
                Spacer()
            }

            if selectionMode && !message.isReceived {
                Button(action: { onToggleSelect?() }) {
                    Image(systemName: isBulkSelected ? "checkmark.circle.fill" : "circle")
                        .foregroundColor(isBulkSelected ? .blue : .secondary)
                }
                .buttonStyle(.plain)
            }
        }
        .contentShape(Rectangle())
        .onTapGesture {
            if selectionMode {
                onToggleSelect?()
            }
        }
        .task {
            // Only load link previews after a delay to avoid blocking UI
            if displayBodyHasLinks && linkPreviews.isEmpty && !loadingPreviews {
                try? await Task.sleep(nanoseconds: 500_000_000) // 500ms delay
                await loadLinkPreviews()
            }
        }
    }

    // MARK: - Reply Quote Block

    /// Displays a quoted reply reference at the top of a message.
    /// Shows sender name and message snippet with accent color bar.
    private struct ReplyQuoteBlock: View {
        let reply: ReplyContent
        let isReceived: Bool

        var body: some View {
            HStack(alignment: .top, spacing: 8) {
                Rectangle()
                    .fill(Color.accentColor)
                    .frame(width: 3)
                    .cornerRadius(2)

                VStack(alignment: .leading, spacing: 2) {
                    Text(reply.sender)
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .lineLimit(1)
                    Text(reply.snippet)
                        .font(.caption2)
                        .foregroundColor(.secondary)
                        .lineLimit(2)
                }

                Spacer(minLength: 0)
            }
            .padding(.horizontal, SyncFlowSpacing.bubbleHorizontal)
            .padding(.vertical, 6)
            .background(
                (isReceived ? SyncFlowColors.receivedBubble : SyncFlowColors.sentBubble)
                    .opacity(isReceived ? 0.4 : 0.25)
            )
            .clipShape(RoundedRectangle(cornerRadius: SyncFlowSpacing.radiusLg, style: .continuous))
        }
    }

    // MARK: - Helper Methods

    /// Formats a timestamp into a short time string.
    private func formattedReadTime(_ timestamp: Double) -> String {
        guard timestamp > 0 else { return "just now" }
        let date = Date(timeIntervalSince1970: timestamp / 1000.0)
        let formatter = DateFormatter()
        formatter.dateStyle = .none
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }

    /// Asynchronously loads link previews for URLs in the message.
    /// Limited to 1 preview per message for performance.
    @MainActor
    private func loadLinkPreviews() async {
        guard displayBodyHasLinks, linkPreviews.isEmpty, !loadingPreviews else { return }

        loadingPreviews = true
        let urls = LinkPreviewService.extractURLs(from: displayBody)

        var previews: [LinkPreview] = []
        for url in urls.prefix(1) { // Limit to 1 preview per message for performance
            if let preview = await LinkPreviewService.shared.fetchPreview(for: url) {
                previews.append(preview)
            }
        }
        linkPreviews = previews
        loadingPreviews = false
    }

    // MARK: - Reaction Actions

    /// Sets a reaction on this message.
    private func setReaction(_ value: String) {
        onReact(value)
    }

    /// Removes the reaction from this message.
    private func clearReaction() {
        onReact(nil)
    }

    /// Toggles the quick thumbs-up reaction (double-tap gesture).
    private func toggleQuickReaction() {
        if reaction == "👍" {
            clearReaction()
        } else {
            setReaction("👍")
        }
    }

    // MARK: - Text Formatting

    /// Creates an AttributedString with search highlighting and markdown formatting.
    /// Supports *bold* and _italic_ text, plus search term highlighting.
    private var formattedMessageBody: AttributedString {
        var attributedString = AttributedString(displayBody)

        // Highlight search results
        if !searchText.isEmpty {
            let lowercasedBody = displayBody.lowercased()
            let lowercasedSearch = searchText.lowercased()
            var searchStartIndex = lowercasedBody.startIndex

            while let range = lowercasedBody.range(of: lowercasedSearch, range: searchStartIndex..<lowercasedBody.endIndex) {
                let distance = lowercasedBody.distance(from: lowercasedBody.startIndex, to: range.lowerBound)
                let length = lowercasedSearch.count

                if let stringIndex = lowercasedBody.index(lowercasedBody.startIndex, offsetBy: distance, limitedBy: lowercasedBody.endIndex),
                   let endIndex = lowercasedBody.index(stringIndex, offsetBy: length, limitedBy: lowercasedBody.endIndex) {
                    let actualRange = stringIndex..<endIndex
                    let matchedText = String(displayBody[actualRange])

                    if let attrRange = attributedString.range(of: matchedText) {
                        attributedString[attrRange].backgroundColor = .yellow.opacity(0.5)
                        attributedString[attrRange].foregroundColor = .black
                    }
                }

                searchStartIndex = range.upperBound
            }
        }

        // Support for *bold* text
        if let boldRegex = try? NSRegularExpression(pattern: "\\*([^*]+)\\*", options: []) {
            let matches = boldRegex.matches(in: displayBody, range: NSRange(displayBody.startIndex..., in: displayBody))
            for match in matches.reversed() {
                if let range = Range(match.range, in: displayBody) {
                    let boldText = String(displayBody[range]).trimmingCharacters(in: CharacterSet(charactersIn: "*"))
                    if let attrRange = attributedString.range(of: String(displayBody[range])) {
                        attributedString.replaceSubrange(attrRange, with: AttributedString(boldText))
                        if let newRange = attributedString.range(of: boldText) {
                            attributedString[newRange].font = .body.bold()
                        }
                    }
                }
            }
        }

        // Support for _italic_ text
        if let italicRegex = try? NSRegularExpression(pattern: "_([^_]+)_", options: []) {
            let matches = italicRegex.matches(in: displayBody, range: NSRange(displayBody.startIndex..., in: displayBody))
            for match in matches.reversed() {
                if let range = Range(match.range, in: displayBody) {
                    let italicText = String(displayBody[range]).trimmingCharacters(in: CharacterSet(charactersIn: "_"))
                    if let attrRange = attributedString.range(of: String(displayBody[range])) {
                        attributedString.replaceSubrange(attrRange, with: AttributedString(italicText))
                        if let newRange = attributedString.range(of: italicText) {
                            attributedString[newRange].font = .body.italic()
                        }
                    }
                }
            }
        }

        return attributedString
    }

    // MARK: - Context Menu Actions

    /// Copies all URLs from the message to the clipboard.
    private func copyAllLinks() {
        guard let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue) else { return }
        let matches = detector.matches(in: displayBody, range: NSRange(displayBody.startIndex..., in: displayBody))
        let links = matches.compactMap { match -> String? in
            if let url = match.url {
                return url.absoluteString
            }
            return nil
        }
        if !links.isEmpty {
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString(links.joined(separator: "\n"), forType: .string)
        }
    }

    /// Copies the full message body to the clipboard.
    private func selectMessage() {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(message.body, forType: .string)
    }

    /// Shows the macOS share sheet for the message.
    private func shareMessage() {
        let picker = NSSharingServicePicker(items: [message.body])
        if let window = NSApp.keyWindow {
            picker.show(relativeTo: .zero, of: window.contentView!, preferredEdge: .minY)
        }
    }

    // MARK: - Reply Parsing

    /// Parses message text to extract reply quote prefix.
    /// Looks for "> Sender: snippet" format at the start of the message.
    /// - Parameter text: The full message text
    /// - Returns: ParsedMessage with optional reply content and remaining body
    private func parseReplyBody(_ text: String) -> ParsedMessage {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix("> ") else {
            return ParsedMessage(reply: nil, body: text)
        }

        let lines = trimmed.components(separatedBy: "\n")
        guard let headerLine = lines.first else {
            return ParsedMessage(reply: nil, body: text)
        }

        let header = headerLine.replacingOccurrences(of: "> ", with: "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !header.isEmpty else {
            return ParsedMessage(reply: nil, body: text)
        }

        let parts = header.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: true)
        let sender = parts.first.map { String($0).trimmingCharacters(in: .whitespacesAndNewlines) } ?? "Reply"
        let snippet = parts.count > 1
            ? String(parts[1]).trimmingCharacters(in: .whitespacesAndNewlines)
            : header

        let remainder = lines.dropFirst().joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
        let reply = ReplyContent(
            sender: sender.isEmpty ? "Reply" : sender,
            snippet: snippet
        )
        return ParsedMessage(reply: reply, body: remainder)
    }

    /// Checks if text contains any URLs using NSDataDetector.
    /// - Parameter text: The text to check
    /// - Returns: True if at least one URL is found
    private func hasLinks(in text: String) -> Bool {
        let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)
        let matches = detector?.matches(in: text, range: NSRange(text.startIndex..., in: text))
        return !(matches?.isEmpty ?? true)
    }
}

// MARK: - Attachment View

/// Displays MMS attachments (images, videos, audio, contacts, files).
/// Supports full-screen preview, video/audio playback, and save to downloads.
struct AttachmentView: View {
    /// The MMS attachment to display
    let attachment: MmsAttachment

    // MARK: - State

    /// Whether mouse is hovering (shows action buttons)
    @State private var isHovering = false
    /// Loaded image data (for images)
    @State private var loadedImage: NSImage? = nil
    /// Whether the attachment is currently loading
    @State private var isLoading = true
    /// Whether loading failed
    @State private var loadError = false
    /// Whether full-screen image viewer is shown
    @State private var showFullScreen = false
    /// Whether video player sheet is shown
    @State private var showVideoPlayer = false
    /// Whether audio player sheet is shown
    @State private var showAudioPlayer = false
    /// Whether save operation is in progress
    @State private var isSaving = false
    /// Whether save completed successfully
    @State private var saveSuccess = false

    // MARK: - Body

    var body: some View {
        Group {
            if attachment.isImage {
                imageView
            } else if attachment.isVideo {
                videoView
            } else if attachment.isAudio {
                audioView
            } else if attachment.isVCard {
                vcardPlaceholder
            } else {
                filePlaceholder
            }
        }
        .cornerRadius(12)
        .onHover { hovering in
            isHovering = hovering
        }
        .contextMenu {
            if attachment.isImage, let image = loadedImage {
                Button("Copy Image") {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.writeObjects([image])
                }
            }

            Button("Save to Downloads") {
                saveAttachment()
            }

            Divider()

            if let urlString = attachment.url, let url = URL(string: urlString) {
                Button("Open in Browser") {
                    NSWorkspace.shared.open(url)
                }
                Button("Copy URL") {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(urlString, forType: .string)
                }
            }
        }
        .sheet(isPresented: $showFullScreen) {
            FullScreenImageView(image: loadedImage, attachment: attachment)
        }
        .sheet(isPresented: $showVideoPlayer) {
            VideoPlayerView(attachment: attachment)
        }
        .sheet(isPresented: $showAudioPlayer) {
            AudioPlayerView(attachment: attachment)
        }
    }

    private var imageView: some View {
        Group {
            if let image = loadedImage {
                ZStack(alignment: .topTrailing) {
                    Image(nsImage: image)
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(maxWidth: 300, maxHeight: 300)
                        .onTapGesture {
                            showFullScreen = true
                        }

                    if isHovering {
                        HStack(spacing: 4) {
                            Button(action: { showFullScreen = true }) {
                                Image(systemName: "arrow.up.left.and.arrow.down.right")
                                    .font(.caption)
                                    .padding(6)
                                    .background(Color.black.opacity(0.6))
                                    .foregroundColor(.white)
                                    .clipShape(Circle())
                            }
                            .buttonStyle(.plain)

                            Button(action: { saveAttachment() }) {
                                Image(systemName: isSaving ? "arrow.down.circle" : (saveSuccess ? "checkmark.circle.fill" : "arrow.down.to.line"))
                                    .font(.caption)
                                    .padding(6)
                                    .background(Color.black.opacity(0.6))
                                    .foregroundColor(saveSuccess ? .green : .white)
                                    .clipShape(Circle())
                            }
                            .buttonStyle(.plain)
                        }
                        .padding(8)
                    }
                }
            } else if loadError {
                errorPlaceholder
            } else {
                ProgressView()
                    .frame(width: 150, height: 150)
                    .background(Color.gray.opacity(0.2))
                    .onAppear {
                        loadImage()
                    }
            }
        }
    }

    private var videoView: some View {
        ZStack(alignment: .topTrailing) {
            ZStack {
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color.black.opacity(0.8))
                    .frame(width: 280, height: 180)

                VStack(spacing: 12) {
                    Image(systemName: "play.circle.fill")
                        .font(.system(size: 50))
                        .foregroundColor(.white)
                    Text(attachment.fileName ?? "Video")
                        .font(.caption)
                        .foregroundColor(.white.opacity(0.8))
                        .lineLimit(1)
                }
            }
            .onTapGesture {
                showVideoPlayer = true
            }

            if isHovering {
                Button(action: { saveAttachment() }) {
                    Image(systemName: isSaving ? "arrow.down.circle" : (saveSuccess ? "checkmark.circle.fill" : "arrow.down.to.line"))
                        .font(.caption)
                        .padding(6)
                        .background(Color.black.opacity(0.6))
                        .foregroundColor(saveSuccess ? .green : .white)
                        .clipShape(Circle())
                }
                .buttonStyle(.plain)
                .padding(8)
            }
        }
    }

    private var audioView: some View {
        ZStack(alignment: .topTrailing) {
            HStack(spacing: 12) {
                Button(action: { showAudioPlayer = true }) {
                    Image(systemName: "play.circle.fill")
                        .font(.system(size: 32))
                        .foregroundColor(.blue)
                }
                .buttonStyle(.plain)

                VStack(alignment: .leading, spacing: 2) {
                    Text(attachment.fileName ?? "Audio")
                        .font(.subheadline)
                        .lineLimit(1)
                    Text("Tap to play")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }

                Spacer()

                Image(systemName: "waveform")
                    .font(.title3)
                    .foregroundColor(.secondary)
            }
            .padding()
            .frame(width: 250)
            .background(Color.gray.opacity(0.15))
            .onTapGesture {
                showAudioPlayer = true
            }

            if isHovering {
                Button(action: { saveAttachment() }) {
                    Image(systemName: isSaving ? "arrow.down.circle" : (saveSuccess ? "checkmark.circle.fill" : "arrow.down.to.line"))
                        .font(.caption)
                        .padding(4)
                        .background(Color.black.opacity(0.6))
                        .foregroundColor(saveSuccess ? .green : .white)
                        .clipShape(Circle())
                }
                .buttonStyle(.plain)
                .padding(4)
            }
        }
    }

    private var vcardPlaceholder: some View {
        HStack(spacing: 8) {
            Image(systemName: "person.crop.rectangle")
                .font(.title2)
                .foregroundColor(.blue)
            VStack(alignment: .leading) {
                Text(attachment.fileName ?? "Contact Card")
                    .font(.subheadline)
                Text("vCard")
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
        }
        .padding()
        .background(Color.gray.opacity(0.15))
        .onTapGesture {
            if let urlString = attachment.url, let url = URL(string: urlString) {
                NSWorkspace.shared.open(url)
            }
        }
    }

    private var filePlaceholder: some View {
        HStack(spacing: 8) {
            Image(systemName: "doc.fill")
                .font(.title2)
                .foregroundColor(.gray)
            VStack(alignment: .leading) {
                Text(attachment.fileName ?? "File")
                    .font(.subheadline)
                Text(attachment.contentType)
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
        }
        .padding()
        .background(Color.gray.opacity(0.15))
        .onTapGesture {
            if let urlString = attachment.url, let url = URL(string: urlString) {
                NSWorkspace.shared.open(url)
            }
        }
    }

    private var errorPlaceholder: some View {
        VStack(spacing: 8) {
            Image(systemName: "photo")
                .font(.system(size: 30))
                .foregroundColor(.secondary)
            Text("Failed to load")
                .font(.caption)
                .foregroundColor(.secondary)
            Button("Retry") {
                loadError = false
                isLoading = true
                loadImage()
            }
            .font(.caption)
        }
        .frame(width: 150, height: 120)
        .background(Color.gray.opacity(0.2))
    }

    private func loadAttachmentData() async throws -> Data {
        let rawData: Data

        // Check if we have an R2 key - if so, get presigned download URL first
        if let r2Key = attachment.r2Key {
            // Loading R2 attachment
            let downloadUrl = try await getR2DownloadUrl(r2Key: r2Key)
            rawData = try await AttachmentCacheManager.shared.loadData(from: downloadUrl)
        } else if let urlString = attachment.url {
            // Legacy Firebase Storage URL or direct URL
            rawData = try await AttachmentCacheManager.shared.loadData(from: urlString)
        } else if let inlineData = attachment.inlineData,
                  let decoded = Data(base64Encoded: inlineData) {
            rawData = decoded
        } else {
            throw NSError(domain: "AttachmentView", code: 1, userInfo: [NSLocalizedDescriptionKey: "Missing attachment data"])
        }

        if attachment.isEncrypted {
            if let decrypted = try? E2EEManager.shared.decryptData(rawData) {
                return decrypted
            }
            throw NSError(domain: "AttachmentView", code: 2, userInfo: [NSLocalizedDescriptionKey: "Failed to decrypt attachment"])
        }

        return rawData
    }

    /// Get presigned download URL from VPS server
    private func getR2DownloadUrl(r2Key: String) async throws -> String {
        let downloadUrl = try await VPSService.shared.getFileDownloadUrl(fileKey: r2Key)
        // R2 download URL obtained
        return downloadUrl
    }

    private func loadImage() {
        Task {
            do {
                let data = try await loadAttachmentData()
                if let image = NSImage(data: data) {
                    await MainActor.run {
                        loadedImage = image
                        isLoading = false
                    }
                } else {
                    print("[AttachmentView] NSImage creation failed from \(data.count) bytes")
                    await MainActor.run {
                        loadError = true
                        isLoading = false
                    }
                }
            } catch {
                print("[AttachmentView] Error loading image: \(error)")
                await MainActor.run {
                    loadError = true
                    isLoading = false
                }
            }
        }
    }

    private func saveAttachment() {
        isSaving = true
        saveSuccess = false

        Task {
            do {
                let fileData = try await loadAttachmentData()

                // Get Downloads folder
                let downloadsURL = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first!

                // Generate filename
                let fileName = attachment.fileName ?? "attachment_\(attachment.id)"
                let ext = getFileExtension(for: attachment.contentType)
                let finalName = fileName.hasSuffix(ext) ? fileName : "\(fileName).\(ext)"
                let fileURL = downloadsURL.appendingPathComponent(finalName)

                // Write to file
                try fileData.write(to: fileURL)

                await MainActor.run {
                    isSaving = false
                    saveSuccess = true

                    // Reset success indicator after 2 seconds
                    DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                        saveSuccess = false
                    }
                }
            } catch {
                print("Error saving attachment: \(error)")
                await MainActor.run {
                    isSaving = false
                }
            }
        }
    }

    private func getFileExtension(for contentType: String) -> String {
        switch contentType.lowercased() {
        case let ct where ct.contains("jpeg") || ct.contains("jpg"): return "jpg"
        case let ct where ct.contains("png"): return "png"
        case let ct where ct.contains("gif"): return "gif"
        case let ct where ct.contains("webp"): return "webp"
        case let ct where ct.contains("mp4"): return "mp4"
        case let ct where ct.contains("3gpp") || ct.contains("3gp"): return "3gp"
        case let ct where ct.contains("mpeg") || ct.contains("mp3"): return "mp3"
        case let ct where ct.contains("ogg"): return "ogg"
        case let ct where ct.contains("wav"): return "wav"
        case let ct where ct.contains("vcard"): return "vcf"
        default: return "bin"
        }
    }
}

// MARK: - Full Screen Image View

struct FullScreenImageView: View {
    let image: NSImage?
    let attachment: MmsAttachment
    @Environment(\.dismiss) var dismiss
    @State private var scale: CGFloat = 1.0

    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                Text(attachment.fileName ?? "Image")
                    .font(.headline)
                Spacer()
                Button(action: { dismiss() }) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.title2)
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.plain)
            }
            .padding()
            .background(Color(nsColor: .windowBackgroundColor))

            Divider()

            // Image
            if let image = image {
                ScrollView([.horizontal, .vertical]) {
                    Image(nsImage: image)
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .scaleEffect(scale)
                        .gesture(
                            MagnificationGesture()
                                .onChanged { value in
                                    scale = value
                                }
                                .onEnded { value in
                                    scale = max(0.5, min(value, 3.0))
                                }
                        )
                }
                .background(Color.black)
            }

            // Footer with controls
            HStack {
                Button(action: { scale = max(0.5, scale - 0.25) }) {
                    Image(systemName: "minus.magnifyingglass")
                }
                .buttonStyle(.bordered)

                Button(action: { scale = 1.0 }) {
                    Text("100%")
                }
                .buttonStyle(.bordered)

                Button(action: { scale = min(3.0, scale + 0.25) }) {
                    Image(systemName: "plus.magnifyingglass")
                }
                .buttonStyle(.bordered)

                Spacer()

                Button("Save to Downloads") {
                    saveImage()
                }
                .buttonStyle(.borderedProminent)
            }
            .padding()
            .background(Color(nsColor: .windowBackgroundColor))
        }
        .frame(minWidth: 600, minHeight: 500)
    }

    private func saveImage() {
        guard let image = image else { return }

        let downloadsURL = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first!
        let fileName = attachment.fileName ?? "image_\(attachment.id).png"
        let fileURL = downloadsURL.appendingPathComponent(fileName)

        if let tiffData = image.tiffRepresentation,
           let bitmap = NSBitmapImageRep(data: tiffData),
           let pngData = bitmap.representation(using: .png, properties: [:]) {
            try? pngData.write(to: fileURL)
            NSWorkspace.shared.selectFile(fileURL.path, inFileViewerRootedAtPath: downloadsURL.path)
        }
    }
}

// MARK: - Video Player View

struct VideoPlayerView: View {
    let attachment: MmsAttachment
    @Environment(\.dismiss) var dismiss
    @State private var isLoading = true
    @State private var localVideoURL: URL? = nil
    @State private var error: String? = nil

    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                Text(attachment.fileName ?? "Video")
                    .font(.headline)
                Spacer()
                Button(action: { dismiss() }) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.title2)
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.plain)
            }
            .padding()

            Divider()

            // Video content
            if isLoading {
                VStack(spacing: 16) {
                    ProgressView()
                        .scaleEffect(1.5)
                    Text("Loading video...")
                        .foregroundColor(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let error = error {
                VStack(spacing: 16) {
                    Image(systemName: "exclamationmark.triangle")
                        .font(.system(size: 40))
                        .foregroundColor(.orange)
                    Text(error)
                        .foregroundColor(.secondary)
                    if let urlString = attachment.url, let url = URL(string: urlString) {
                        Button("Open in Browser") {
                            NSWorkspace.shared.open(url)
                        }
                        .buttonStyle(.borderedProminent)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let videoURL = localVideoURL {
                VideoPlayerRepresentable(url: videoURL)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }

            Divider()

            // Footer
            HStack {
                if let urlString = attachment.url, let url = URL(string: urlString) {
                    Button("Open in Browser") {
                        NSWorkspace.shared.open(url)
                    }
                }
                Spacer()
                Button("Save to Downloads") {
                    saveVideo()
                }
                .buttonStyle(.borderedProminent)
                .disabled(localVideoURL == nil)
            }
            .padding()
        }
        .frame(minWidth: 640, minHeight: 480)
        .onAppear {
            loadVideo()
        }
    }

    private func loadVideo() {
        Task {
            do {
                let videoData = try await loadAttachmentData()

                // Save to temp file for playback
                let tempDir = FileManager.default.temporaryDirectory
                let fileName = attachment.fileName ?? "video_\(attachment.id).mp4"
                let tempURL = tempDir.appendingPathComponent(fileName)
                try videoData.write(to: tempURL)

                await MainActor.run {
                    localVideoURL = tempURL
                    isLoading = false
                }
            } catch {
                await MainActor.run {
                    self.error = "Failed to load video: \(error.localizedDescription)"
                    isLoading = false
                }
            }
        }
    }

    private func loadAttachmentData() async throws -> Data {
        let rawData: Data
        if let urlString = attachment.url {
            rawData = try await AttachmentCacheManager.shared.loadData(from: urlString)
        } else if let inlineData = attachment.inlineData,
                  let decoded = Data(base64Encoded: inlineData) {
            rawData = decoded
        } else {
            throw NSError(domain: "VideoPlayerView", code: 1, userInfo: [NSLocalizedDescriptionKey: "Missing video data"])
        }

        if attachment.isEncrypted {
            if let decrypted = try? E2EEManager.shared.decryptData(rawData) {
                return decrypted
            }
            throw NSError(domain: "VideoPlayerView", code: 2, userInfo: [NSLocalizedDescriptionKey: "Failed to decrypt video"])
        }

        return rawData
    }

    private func saveVideo() {
        guard let videoURL = localVideoURL else { return }

        let downloadsURL = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first!
        let fileName = attachment.fileName ?? "video_\(attachment.id).mp4"
        let destURL = downloadsURL.appendingPathComponent(fileName)

        do {
            if FileManager.default.fileExists(atPath: destURL.path) {
                try FileManager.default.removeItem(at: destURL)
            }
            try FileManager.default.copyItem(at: videoURL, to: destURL)
            NSWorkspace.shared.selectFile(destURL.path, inFileViewerRootedAtPath: downloadsURL.path)
        } catch {
            print("Error saving video: \(error)")
        }
    }
}

// MARK: - Video Player Representable

import AVKit

struct VideoPlayerRepresentable: NSViewRepresentable {
    let url: URL

    func makeNSView(context: Context) -> AVPlayerView {
        let playerView = AVPlayerView()
        let player = AVPlayer(url: url)
        playerView.player = player
        playerView.controlsStyle = .floating
        return playerView
    }

    func updateNSView(_ nsView: AVPlayerView, context: Context) {
        // No update needed
    }
}

// MARK: - Audio Player View

struct AudioPlayerView: View {
    let attachment: MmsAttachment
    @Environment(\.dismiss) var dismiss
    @State private var isLoading = true
    @State private var audioPlayer: AVPlayer? = nil
    @State private var isPlaying = false
    @State private var duration: Double = 0
    @State private var currentTime: Double = 0
    @State private var error: String? = nil
    @State private var localAudioURL: URL? = nil

    var body: some View {
        VStack(spacing: 20) {
            // Header
            HStack {
                Text(attachment.fileName ?? "Audio")
                    .font(.headline)
                Spacer()
                Button(action: { dismiss() }) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.title2)
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.plain)
            }

            Divider()

            if isLoading {
                VStack(spacing: 16) {
                    ProgressView()
                    Text("Loading audio...")
                        .foregroundColor(.secondary)
                }
                .frame(height: 150)
            } else if let error = error {
                VStack(spacing: 16) {
                    Image(systemName: "exclamationmark.triangle")
                        .font(.system(size: 40))
                        .foregroundColor(.orange)
                    Text(error)
                        .foregroundColor(.secondary)
                }
                .frame(height: 150)
            } else {
                VStack(spacing: 20) {
                    // Waveform visualization
                    Image(systemName: "waveform")
                        .font(.system(size: 60))
                        .foregroundColor(.blue)

                    // Progress slider
                    VStack(spacing: 4) {
                        Slider(value: $currentTime, in: 0...max(duration, 1)) { editing in
                            if !editing {
                                audioPlayer?.seek(to: CMTime(seconds: currentTime, preferredTimescale: 1))
                            }
                        }

                        HStack {
                            Text(formatTime(currentTime))
                                .font(.caption)
                                .foregroundColor(.secondary)
                            Spacer()
                            Text(formatTime(duration))
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }

                    // Playback controls
                    HStack(spacing: 30) {
                        Button(action: { seekBackward() }) {
                            Image(systemName: "gobackward.10")
                                .font(.title2)
                        }
                        .buttonStyle(.plain)

                        Button(action: { togglePlayback() }) {
                            Image(systemName: isPlaying ? "pause.circle.fill" : "play.circle.fill")
                                .font(.system(size: 50))
                                .foregroundColor(.blue)
                        }
                        .buttonStyle(.plain)

                        Button(action: { seekForward() }) {
                            Image(systemName: "goforward.10")
                                .font(.title2)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .frame(height: 200)
            }

            Divider()

            // Footer
            HStack {
                Spacer()
                Button("Save to Downloads") {
                    saveAudio()
                }
                .buttonStyle(.borderedProminent)
                .disabled(localAudioURL == nil)
            }
        }
        .padding()
        .frame(width: 400, height: 350)
        .onAppear {
            loadAudio()
        }
        .onDisappear {
            audioPlayer?.pause()
        }
    }

    private func loadAudio() {
        Task {
            do {
                let audioData = try await loadAttachmentData()

                // Save to temp file
                let tempDir = FileManager.default.temporaryDirectory
                let fileName = attachment.fileName ?? "audio_\(attachment.id).mp3"
                let tempURL = tempDir.appendingPathComponent(fileName)
                try audioData.write(to: tempURL)

                await MainActor.run {
                    localAudioURL = tempURL
                    let player = AVPlayer(url: tempURL)
                    audioPlayer = player

                    // Get duration
                    Task {
                        if let durationCM = try? await player.currentItem?.asset.load(.duration) {
                            await MainActor.run {
                                duration = CMTimeGetSeconds(durationCM)
                            }
                        }
                    }

                    // Observe playback time
                    player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.5, preferredTimescale: 1), queue: .main) { time in
                        currentTime = CMTimeGetSeconds(time)
                    }

                    isLoading = false
                }
            } catch {
                await MainActor.run {
                    self.error = "Failed to load audio: \(error.localizedDescription)"
                    isLoading = false
                }
            }
        }
    }

    private func loadAttachmentData() async throws -> Data {
        let rawData: Data
        if let urlString = attachment.url {
            rawData = try await AttachmentCacheManager.shared.loadData(from: urlString)
        } else if let inlineData = attachment.inlineData,
                  let decoded = Data(base64Encoded: inlineData) {
            rawData = decoded
        } else {
            throw NSError(domain: "AudioPlayerView", code: 1, userInfo: [NSLocalizedDescriptionKey: "Missing audio data"])
        }

        if attachment.isEncrypted {
            if let decrypted = try? E2EEManager.shared.decryptData(rawData) {
                return decrypted
            }
            throw NSError(domain: "AudioPlayerView", code: 2, userInfo: [NSLocalizedDescriptionKey: "Failed to decrypt audio"])
        }

        return rawData
    }

    private func togglePlayback() {
        guard let player = audioPlayer else { return }
        if isPlaying {
            player.pause()
        } else {
            player.play()
        }
        isPlaying.toggle()
    }

    private func seekBackward() {
        guard let player = audioPlayer else { return }
        let newTime = max(0, currentTime - 10)
        player.seek(to: CMTime(seconds: newTime, preferredTimescale: 1))
    }

    private func seekForward() {
        guard let player = audioPlayer else { return }
        let newTime = min(duration, currentTime + 10)
        player.seek(to: CMTime(seconds: newTime, preferredTimescale: 1))
    }

    private func formatTime(_ seconds: Double) -> String {
        let mins = Int(seconds) / 60
        let secs = Int(seconds) % 60
        return String(format: "%d:%02d", mins, secs)
    }

    private func saveAudio() {
        guard let audioURL = localAudioURL else { return }

        let downloadsURL = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first!
        let fileName = attachment.fileName ?? "audio_\(attachment.id).mp3"
        let destURL = downloadsURL.appendingPathComponent(fileName)

        do {
            if FileManager.default.fileExists(atPath: destURL.path) {
                try FileManager.default.removeItem(at: destURL)
            }
            try FileManager.default.copyItem(at: audioURL, to: destURL)
            NSWorkspace.shared.selectFile(destURL.path, inFileViewerRootedAtPath: downloadsURL.path)
        } catch {
            print("Error saving audio: \(error)")
        }
    }
}

// MARK: - Clickable Message Text

struct ClickableMessageText: View {
    let text: String

    @State private var hoveredURL: URL? = nil

    var body: some View {
        Text(attributedString)
            .environment(\.openURL, OpenURLAction { url in
                // Open URLs in default browser
                NSWorkspace.shared.open(url)
                return .handled
            })
            .help(hoveredURL?.absoluteString ?? "")
    }

    private var attributedString: AttributedString {
        guard let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue | NSTextCheckingResult.CheckingType.phoneNumber.rawValue | NSTextCheckingResult.CheckingType.address.rawValue) else {
            return AttributedString(text)
        }

        let matches = detector.matches(in: text, range: NSRange(text.startIndex..., in: text))
        var attributedString = AttributedString(text)

        for match in matches {
            if let range = Range(match.range, in: text) {
                let substring = String(text[range])
                if let attrRange = attributedString.range(of: substring) {
                    // Style the link
                    attributedString[attrRange].foregroundColor = .blue
                    attributedString[attrRange].underlineStyle = .single
                    attributedString[attrRange].cursor = .pointingHand

                    if let url = match.url {
                        attributedString[attrRange].link = url
                    } else if match.resultType == .phoneNumber {
                        // Make phone numbers clickable
                        if let phoneURL = URL(string: "tel:\(substring.filter { $0.isNumber })") {
                            attributedString[attrRange].link = phoneURL
                        }
                    } else if match.resultType == .address {
                        // Make addresses clickable (open in Maps)
                        let encodedAddress = substring.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
                        if let mapsURL = URL(string: "http://maps.apple.com/?q=\(encodedAddress)") {
                            attributedString[attrRange].link = mapsURL
                        }
                    }
                }
            }
        }

        return attributedString
    }
}

// MARK: - Header Helper Views

/// Modern action button for the conversation header
struct HeaderActionButton: View {
    let icon: String
    let isActive: Bool
    var activeColor: Color = .accentColor
    let action: () -> Void
    let help: String

    @State private var isHovered = false

    var body: some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: 13, weight: .medium))
                .foregroundColor(isActive ? activeColor : .secondary)
                .frame(width: 28, height: 28)
                .background(
                    RoundedRectangle(cornerRadius: 6, style: .continuous)
                        .fill(isHovered || isActive ? Color.primary.opacity(0.08) : Color.clear)
                )
        }
        .buttonStyle(.plain)
        .help(help)
        .onHover { hovering in
            withAnimation(.easeInOut(duration: 0.15)) {
                isHovered = hovering
            }
        }
    }
}

/// Call button with colored background
struct HeaderCallButton: View {
    let icon: String
    let color: Color
    let isDisabled: Bool

    var body: some View {
        Image(systemName: icon)
            .font(.system(size: 12, weight: .semibold))
            .foregroundColor(isDisabled ? color.opacity(0.5) : color)
            .frame(width: 28, height: 28)
            .background(
                RoundedRectangle(cornerRadius: 6, style: .continuous)
                    .fill(color.opacity(0.12))
            )
    }
}

/// Contact info popover content
struct ContactInfoPopover: View {
    let conversation: Conversation
    let allAddresses: [String]
    let preferredSendAddress: String?
    let onSelectSendAddress: (String?) -> Void
    let onCopyNumber: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            // Header
            HStack(spacing: 12) {
                Circle()
                    .fill(
                        LinearGradient(
                            colors: [
                                Color(hex: conversation.avatarColor ?? "#2196F3"),
                                Color(hex: conversation.avatarColor ?? "#2196F3").opacity(0.7)
                            ],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .frame(width: 48, height: 48)
                    .overlay(
                        Text(conversation.initials)
                            .font(.system(size: 18, weight: .semibold, design: .rounded))
                            .foregroundColor(.white)
                    )

                VStack(alignment: .leading, spacing: 2) {
                    Text(conversation.displayName)
                        .font(.system(size: 15, weight: .semibold))
                    Text(conversation.address)
                        .font(.system(size: 12))
                        .foregroundColor(.secondary)
                }
            }

            if allAddresses.count > 1 {
                Divider()

                VStack(alignment: .leading, spacing: 8) {
                    Text("Send using")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundColor(.secondary)
                        .textCase(.uppercase)

                    ForEach(allAddresses, id: \.self) { address in
                        Button(action: {
                            onSelectSendAddress(address)
                        }) {
                            HStack {
                                Text(address)
                                    .font(.system(size: 13))
                                Spacer()
                                if (preferredSendAddress ?? conversation.address) == address {
                                    Image(systemName: "checkmark.circle.fill")
                                        .foregroundColor(.accentColor)
                                }
                            }
                            .padding(.vertical, 4)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }

                    Button(action: {
                        onSelectSendAddress(nil)
                    }) {
                        Text("Reset to primary")
                            .font(.system(size: 12))
                            .foregroundColor(.accentColor)
                    }
                    .buttonStyle(.plain)
                }
            }

            Divider()

            Button(action: onCopyNumber) {
                Label("Copy number", systemImage: "doc.on.doc")
                    .font(.system(size: 13))
            }
            .buttonStyle(.plain)
        }
        .padding(16)
        .frame(minWidth: 240)
    }
}

// MARK: - Compose Bar

struct ComposeBar: View {
    @Binding var messageText: String
    let isSending: Bool
    @Binding var showTemplates: Bool
    @Binding var showEmojiPicker: Bool
    var hasAttachment: Bool = false
    var onAttachmentTap: (() -> Void)? = nil
    var onMicrophoneTap: (() -> Void)? = nil
    let onSend: () async -> Void
    var onSchedule: ((Date) -> Void)? = nil
    var recipientName: String? = nil
    var recipientNumber: String? = nil

    @FocusState private var isTextFieldFocused: Bool
    @StateObject private var audioRecorder = AudioRecorderService.shared
    @State private var isHoveringAttachment = false
    @State private var isHoveringTemplates = false
    @State private var isHoveringEmoji = false
    @State private var isHoveringSchedule = false
    @State private var showScheduleSheet = false

    var body: some View {
        HStack(spacing: 8) {
            // Left action buttons group
            HStack(spacing: 0) {
                // Attachment button
                ComposeActionButton(
                    icon: hasAttachment ? "photo.fill" : "plus",
                    isActive: hasAttachment,
                    isHovered: $isHoveringAttachment,
                    action: { onAttachmentTap?() },
                    help: "Add photo or video"
                )

                // Templates button
                ComposeActionButton(
                    icon: "text.badge.star",
                    isActive: showTemplates,
                    isHovered: $isHoveringTemplates,
                    action: { showTemplates.toggle() },
                    help: "Message Templates"
                )

                // Emoji picker button
                ComposeActionButton(
                    icon: "face.smiling",
                    isActive: false,
                    isHovered: $isHoveringEmoji,
                    action: { NSApp.orderFrontCharacterPalette(nil) },
                    help: "Emoji & Symbols"
                )
            }
            .padding(4)
            .background(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(Color(nsColor: .controlBackgroundColor))
            )

            // Text input area
            HStack(spacing: 8) {
                ZStack(alignment: .leading) {
                    // Placeholder
                    if messageText.isEmpty {
                        Text(hasAttachment ? "Add a caption..." : "Message")
                            .font(.system(size: 14))
                            .foregroundColor(.secondary.opacity(0.7))
                            .padding(.leading, 4)
                    }

                    // Text editor
                    TextEditor(text: $messageText)
                        .focused($isTextFieldFocused)
                        .font(.system(size: 14))
                        .frame(minHeight: 20, maxHeight: 80)
                        .scrollContentBackground(.hidden)
                        .padding(.vertical, 4)
                }

                // Microphone button (shows when no text)
                if messageText.isEmpty && !hasAttachment {
                    MicrophoneButton(audioRecorder: audioRecorder) {
                        onMicrophoneTap?()
                    }
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .fill(Color(nsColor: .controlBackgroundColor))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .stroke(
                        isTextFieldFocused ? Color.accentColor.opacity(0.5) : Color.clear,
                        lineWidth: 1.5
                    )
            )

            // Schedule button (only show if onSchedule is provided and there's text)
            if onSchedule != nil && canSend {
                Button {
                    showScheduleSheet = true
                } label: {
                    Image(systemName: "clock")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundColor(isHoveringSchedule ? .accentColor : .secondary)
                        .frame(width: 32, height: 32)
                        .background(
                            Circle()
                                .fill(isHoveringSchedule ? Color.accentColor.opacity(0.1) : Color.clear)
                        )
                }
                .buttonStyle(.plain)
                .help("Schedule message for later")
                .onHover { hovering in
                    withAnimation(.easeInOut(duration: 0.12)) {
                        isHoveringSchedule = hovering
                    }
                }
            }

            // Send button
            Button {
                Task {
                    await onSend()
                }
            } label: {
                Group {
                    if isSending {
                        ProgressView()
                            .scaleEffect(0.6)
                            .frame(width: 16, height: 16)
                    } else {
                        Image(systemName: "arrow.up")
                            .font(.system(size: 14, weight: .semibold))
                    }
                }
                .foregroundColor(.white)
                .frame(width: 32, height: 32)
                .background(
                    Circle()
                        .fill(
                            canSend ?
                            LinearGradient(
                                colors: [Color.accentColor, Color.accentColor.opacity(0.8)],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            ) :
                            LinearGradient(
                                colors: [Color.gray.opacity(0.4), Color.gray.opacity(0.3)],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                )
                .shadow(
                    color: canSend ? Color.accentColor.opacity(0.3) : Color.clear,
                    radius: 4,
                    x: 0,
                    y: 2
                )
            }
            .buttonStyle(.plain)
            .disabled(!canSend || isSending)
            .animation(.easeInOut(duration: 0.15), value: canSend)
        }
        .sheet(isPresented: $showScheduleSheet) {
            ScheduleMessageSheet(
                recipientNumber: recipientNumber ?? "",
                recipientName: recipientName,
                messageText: $messageText,
                isPresented: $showScheduleSheet,
                onSchedule: { date in
                    onSchedule?(date)
                }
            )
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(
            ZStack {
                // Top border
                VStack {
                    Rectangle()
                        .fill(Color.primary.opacity(0.06))
                        .frame(height: 1)
                    Spacer()
                }

                // Background
                Color(nsColor: .windowBackgroundColor)
            }
        )
        .onAppear {
            isTextFieldFocused = true
        }
    }

    private var canSend: Bool {
        let hasText = !messageText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        return hasText || hasAttachment
    }
}

/// Action button for compose bar
private struct ComposeActionButton: View {
    let icon: String
    let isActive: Bool
    @Binding var isHovered: Bool
    let action: () -> Void
    let help: String

    var body: some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: 14, weight: .medium))
                .foregroundColor(isActive ? .accentColor : .secondary)
                .frame(width: 30, height: 30)
                .background(
                    RoundedRectangle(cornerRadius: 7, style: .continuous)
                        .fill(isHovered || isActive ? Color.primary.opacity(0.08) : Color.clear)
                )
        }
        .buttonStyle(.plain)
        .help(help)
        .onHover { hovering in
            withAnimation(.easeInOut(duration: 0.12)) {
                isHovered = hovering
            }
        }
    }
}

// MARK: - Reply Preview Bar

struct ReplyPreviewBar: View {
    let message: Message
    let onClear: () -> Void

    private var senderName: String {
        if message.isReceived {
            return message.contactName ?? message.address
        }
        return "You"
    }

    private var snippet: String {
        let trimmed = message.body.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty {
            let singleLine = trimmed.replacingOccurrences(of: "\n", with: " ")
            return String(singleLine.prefix(80))
        }

        if message.hasAttachments {
            if let attachment = message.attachments?.first {
                if attachment.isImage {
                    return "Photo"
                }
                if attachment.isVideo {
                    return "Video"
                }
                if attachment.isAudio {
                    return "Audio"
                }
                if attachment.isVCard {
                    return "Contact"
                }
            }
            return "Attachment"
        }

        return "Message"
    }

    @State private var isHoveringClose = false

    var body: some View {
        HStack(spacing: 12) {
            // Accent bar with gradient
            RoundedRectangle(cornerRadius: 2, style: .continuous)
                .fill(
                    LinearGradient(
                        colors: [Color.accentColor, Color.accentColor.opacity(0.6)],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )
                .frame(width: 3)

            // Reply content
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 4) {
                    Image(systemName: "arrowshape.turn.up.left.fill")
                        .font(.system(size: 9))
                        .foregroundColor(.accentColor)
                    Text("Replying to \(senderName)")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundColor(.secondary)
                }

                Text(snippet)
                    .font(.system(size: 13))
                    .foregroundColor(.primary.opacity(0.9))
                    .lineLimit(2)
            }

            Spacer()

            // Close button
            Button(action: onClear) {
                Image(systemName: "xmark")
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundColor(.secondary)
                    .frame(width: 20, height: 20)
                    .background(
                        Circle()
                            .fill(isHoveringClose ? Color.primary.opacity(0.1) : Color.clear)
                    )
            }
            .buttonStyle(.plain)
            .help("Cancel reply")
            .onHover { hovering in
                withAnimation(.easeInOut(duration: 0.12)) {
                    isHoveringClose = hovering
                }
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(Color(nsColor: .controlBackgroundColor))
                .shadow(color: Color.black.opacity(0.04), radius: 2, x: 0, y: 1)
        )
        .padding(.horizontal, 16)
    }
}

// MARK: - Templates View

struct TemplatesView: View {
    let onSelect: (PreferencesService.MessageTemplate) -> Void
    let onDismiss: () -> Void

    @State private var templates: [PreferencesService.MessageTemplate] = []
    @State private var showAddTemplate = false
    @State private var editingTemplate: PreferencesService.MessageTemplate? = nil
    @State private var newTemplateName = ""
    @State private var newTemplateContent = ""

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("Message Templates")
                    .font(.headline)
                    .padding()

                Spacer()

                Button(action: { showAddTemplate = true }) {
                    Image(systemName: "plus.circle.fill")
                }
                .buttonStyle(.borderless)
                .help("Add new template")
                .padding()

                Button(action: onDismiss) {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.borderless)
                .padding()
            }
            .background(Color(nsColor: .controlBackgroundColor))

            Divider()

            if templates.isEmpty {
                VStack(spacing: 12) {
                    Image(systemName: "text.badge.star")
                        .font(.system(size: 32))
                        .foregroundColor(.secondary)
                    Text("No templates yet")
                        .foregroundColor(.secondary)
                    Text("Create templates for quick replies")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Button("Create Template") {
                        showAddTemplate = true
                    }
                    .buttonStyle(.borderedProminent)
                }
                .frame(maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVStack(spacing: 8) {
                        ForEach(templates, id: \.id) { template in
                            TemplateRow(
                                template: template,
                                onSelect: { onSelect(template) },
                                onEdit: { editingTemplate = template },
                                onDelete: {
                                    PreferencesService.shared.deleteTemplate(id: template.id)
                                    templates = PreferencesService.shared.getTemplates()
                                }
                            )
                        }
                    }
                    .padding()
                }
            }
        }
        .background(Color(nsColor: .windowBackgroundColor))
        .onAppear {
            templates = PreferencesService.shared.getTemplates()
        }
        .sheet(isPresented: $showAddTemplate) {
            AddTemplateView(
                templateName: $newTemplateName,
                templateContent: $newTemplateContent,
                onSave: {
                    PreferencesService.shared.saveTemplate(name: newTemplateName, content: newTemplateContent)
                    templates = PreferencesService.shared.getTemplates()
                    newTemplateName = ""
                    newTemplateContent = ""
                    showAddTemplate = false
                }
            )
        }
        .sheet(item: $editingTemplate) { template in
            EditTemplateView(
                template: template,
                onSave: { name, content in
                    PreferencesService.shared.updateTemplate(id: template.id, name: name, content: content)
                    templates = PreferencesService.shared.getTemplates()
                    editingTemplate = nil
                }
            )
        }
    }
}

struct TemplateRow: View {
    let template: PreferencesService.MessageTemplate
    let onSelect: () -> Void
    let onEdit: () -> Void
    let onDelete: () -> Void

    @State private var isHovered = false

    var body: some View {
        HStack {
            Button(action: onSelect) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(template.name)
                        .font(.headline)
                    Text(template.content)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                        .lineLimit(2)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.plain)

            if isHovered {
                HStack(spacing: 8) {
                    Button(action: onEdit) {
                        Image(systemName: "pencil")
                            .foregroundColor(.blue)
                    }
                    .buttonStyle(.borderless)
                    .help("Edit template")

                    Button(action: onDelete) {
                        Image(systemName: "trash")
                            .foregroundColor(.red)
                    }
                    .buttonStyle(.borderless)
                    .help("Delete template")
                }
            }
        }
        .padding()
        .background(isHovered ? Color(nsColor: .selectedContentBackgroundColor).opacity(0.3) : Color(nsColor: .controlBackgroundColor))
        .cornerRadius(8)
        .onHover { hovering in
            isHovered = hovering
        }
    }
}

struct AddTemplateView: View {
    @Binding var templateName: String
    @Binding var templateContent: String
    let onSave: () -> Void

    @Environment(\.dismiss) var dismiss

    var body: some View {
        VStack(spacing: 16) {
            Text("New Template")
                .font(.title2)
                .fontWeight(.bold)

            VStack(alignment: .leading, spacing: 8) {
                Text("Name:")
                TextField("e.g., Running late", text: $templateName)
                    .textFieldStyle(.roundedBorder)
            }

            VStack(alignment: .leading, spacing: 8) {
                Text("Message:")
                TextEditor(text: $templateContent)
                    .frame(height: 100)
                    .border(Color.secondary.opacity(0.3))
            }

            HStack {
                Button("Cancel") {
                    dismiss()
                }

                Spacer()

                Button("Save") {
                    onSave()
                }
                .buttonStyle(.borderedProminent)
                .disabled(templateName.isEmpty || templateContent.isEmpty)
            }
        }
        .padding()
        .frame(width: 400)
    }
}

struct EditTemplateView: View {
    let template: PreferencesService.MessageTemplate
    let onSave: (String, String) -> Void

    @State private var templateName: String = ""
    @State private var templateContent: String = ""
    @Environment(\.dismiss) var dismiss

    var body: some View {
        VStack(spacing: 16) {
            Text("Edit Template")
                .font(.title2)
                .fontWeight(.bold)

            VStack(alignment: .leading, spacing: 8) {
                Text("Name:")
                TextField("e.g., Running late", text: $templateName)
                    .textFieldStyle(.roundedBorder)
            }

            VStack(alignment: .leading, spacing: 8) {
                Text("Message:")
                TextEditor(text: $templateContent)
                    .frame(height: 100)
                    .border(Color.secondary.opacity(0.3))
            }

            HStack {
                Button("Cancel") {
                    dismiss()
                }

                Spacer()

                Button("Save") {
                    onSave(templateName, templateContent)
                }
                .buttonStyle(.borderedProminent)
                .disabled(templateName.isEmpty || templateContent.isEmpty)
            }
        }
        .padding()
        .frame(width: 400)
        .onAppear {
            templateName = template.name
            templateContent = template.content
        }
    }
}

// MARK: - New Message View

struct NewMessageView: View {
    @EnvironmentObject var appState: AppState
    @EnvironmentObject var messageStore: MessageStore
    @Environment(\.dismiss) var dismiss

    @State private var phoneNumber = ""
    @State private var messageText = ""
    @State private var isSending = false

    var body: some View {
        VStack(spacing: 20) {
            Text("New Message")
                .font(.title2)
                .fontWeight(.bold)

            VStack(alignment: .leading, spacing: 8) {
                Text("To:")
                    .foregroundColor(.secondary)
                TextField("Phone number", text: $phoneNumber)
                    .textFieldStyle(.roundedBorder)
            }

            VStack(alignment: .leading, spacing: 8) {
                Text("Message:")
                    .foregroundColor(.secondary)
                TextEditor(text: $messageText)
                    .frame(height: 150)
                    .border(Color.secondary.opacity(0.3))
            }

            HStack {
                Button("Cancel") {
                    dismiss()
                }
                .keyboardShortcut(.cancelAction)

                Spacer()

                Button("Send") {
                    Task {
                        await sendMessage()
                    }
                }
                .keyboardShortcut(.defaultAction)
                .disabled(phoneNumber.isEmpty || messageText.isEmpty || isSending)
            }
        }
        .padding()
    }

    private func sendMessage() async {
        guard let userId = appState.userId else { return }

        isSending = true

        do {
            try await messageStore.sendMessage(
                userId: userId,
                to: phoneNumber,
                body: messageText
            )
            dismiss()
        } catch {
            print("Error sending message: \(error)")
        }

        isSending = false
    }
}

// MARK: - Link Preview Card

struct LinkPreviewCard: View {
    let preview: LinkPreview
    let isSent: Bool

    @State private var previewImage: NSImage?

    var body: some View {
        Button(action: openLink) {
            HStack(spacing: 12) {
                // Image preview
                if let image = previewImage {
                    Image(nsImage: image)
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                        .frame(width: 60, height: 60)
                        .clipped()
                        .cornerRadius(8)
                } else {
                    RoundedRectangle(cornerRadius: 8)
                        .fill(isSent ? Color.white.opacity(0.2) : Color.gray.opacity(0.2))
                        .frame(width: 60, height: 60)
                        .overlay(
                            Image(systemName: "link")
                                .foregroundColor(isSent ? .white.opacity(0.7) : .gray)
                        )
                }

                VStack(alignment: .leading, spacing: 4) {
                    // Site name
                    if let siteName = preview.siteName {
                        Text(siteName.uppercased())
                            .font(.caption2)
                            .fontWeight(.medium)
                            .foregroundColor(isSent ? .white.opacity(0.8) : .secondary)
                    }

                    // Title
                    if let title = preview.title {
                        Text(title)
                            .font(.caption)
                            .fontWeight(.semibold)
                            .foregroundColor(isSent ? .white : .primary)
                            .lineLimit(2)
                    }

                    // Description
                    if let description = preview.description {
                        Text(description)
                            .font(.caption2)
                            .foregroundColor(isSent ? .white.opacity(0.8) : .secondary)
                            .lineLimit(2)
                    }
                }

                Spacer()
            }
            .padding(10)
            .background(isSent ? Color.white.opacity(0.15) : Color(nsColor: .controlBackgroundColor))
            .cornerRadius(12)
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(isSent ? Color.white.opacity(0.3) : Color.gray.opacity(0.2), lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
        .frame(maxWidth: 280)
        .onAppear {
            loadPreviewImage()
        }
    }

    private func openLink() {
        NSWorkspace.shared.open(preview.url)
    }

    private func loadPreviewImage() {
        guard let imageURL = preview.imageURL else { return }

        Task {
            do {
                let data = try await AttachmentCacheManager.shared.loadData(from: imageURL.absoluteString)
                if let image = NSImage(data: data) {
                    await MainActor.run {
                        previewImage = image
                    }
                }
            } catch {
                print("[LinkPreview] Failed to load image: \(error)")
            }
        }
    }
}
