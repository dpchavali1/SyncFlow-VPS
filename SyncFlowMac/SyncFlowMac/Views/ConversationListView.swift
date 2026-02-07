//
//  ConversationListView.swift
//  SyncFlowMac
//
//  Sidebar view showing all conversations
//
//  =============================================================================
//  PURPOSE:
//  This view displays the conversation list in the sidebar of the SyncFlow app.
//  It serves as the primary navigation interface for users to browse, search,
//  filter, and select message conversations. The view supports multiple features
//  including conversation search, label-based filtering, bulk selection/deletion,
//  spam management, and continuity suggestions for cross-device workflows.
//
//  USER INTERACTIONS:
//  - Tap a conversation to select it and view messages
//  - Use the search field to filter conversations and search within messages
//  - Toggle filter buttons (All, Unread, Archived, Spam) to filter conversations
//  - Long-press/right-click for context menu options (pin, archive, delete, etc.)
//  - Use selection mode to bulk-select and delete multiple conversations
//  - Tap label chips to filter by assigned labels
//  - Tap "Load More" to paginate and load older conversations
//  - Interact with continuity banner to continue conversations from other devices
//
//  STATE MANAGEMENT:
//  - Uses @EnvironmentObject for MessageStore (data) and AppState (app-wide state)
//  - Uses @Binding for searchText and selectedConversation (parent-managed state)
//  - Local @State for UI-specific state like selection mode, search focus, etc.
//  - @FocusState for keyboard focus management on search field
//
//  PERFORMANCE CONSIDERATIONS:
//  - Computed properties (filteredConversations, pinnedConversations, etc.) are
//    re-evaluated on every state change; keep them efficient
//  - LazyVStack used for conversation list to virtualize off-screen items
//  - Message search limited to 20 results to prevent UI lag
//  - Background color and styling use design system constants for consistency
//  =============================================================================

import SwiftUI

// MARK: - Main Conversation List View

/// The sidebar view that displays all user conversations with search, filter, and selection capabilities.
/// This is the primary navigation component for the messaging interface.
struct ConversationListView: View {

    // MARK: - Environment Objects

    /// The message store containing all conversations and messages
    @EnvironmentObject var messageStore: MessageStore
    /// App-wide state including continuity suggestions and user preferences
    @EnvironmentObject var appState: AppState

    // MARK: - Bindings (Parent-Managed State)

    /// Search text bound to parent view for global search coordination
    @Binding var searchText: String
    /// Currently selected conversation; changes trigger message view updates
    @Binding var selectedConversation: Conversation?

    // MARK: - Local State

    /// Whether to show message search results in addition to conversation results
    @State private var showMessageResults = false
    /// ID of the currently selected label filter (nil = no label filter)
    @State private var selectedLabelId: String? = nil
    /// Cached list of available labels for filtering UI
    @State private var availableLabels: [PreferencesService.ConversationLabel] = []
    /// Whether bulk selection mode is active
    @State private var isSelectionMode = false
    /// Set of conversation IDs currently selected for bulk operations
    @State private var selectedConversationIds: Set<String> = []
    /// Controls visibility of bulk delete confirmation alert
    @State private var showBulkDeleteConfirmation = false
    /// Tracks whether search UI elements should be expanded
    @State private var isSearchActive = false

    // MARK: - Focus State

    /// Manages keyboard focus for the search text field
    @FocusState private var isSearchFieldFocused: Bool

    // MARK: - Services

    /// Shared preferences service for label management
    private let preferences = PreferencesService.shared

    // MARK: - Computed Properties

    /// Returns filtered conversations based on search text and selected label.
    /// This is the main data source for the conversation list.
    var filteredConversations: [Conversation] {
        // Start with either all conversations or search-filtered results
        var conversations = searchText.isEmpty
            ? messageStore.displayedConversations
            : messageStore.search(query: searchText, in: messageStore.displayedConversations)

        // Apply label filter if a label is selected
        if let labelId = selectedLabelId {
            let labeledAddresses = Set(preferences.getConversations(with: labelId))
            conversations = conversations.filter { labeledAddresses.contains($0.address) }
        }

        return conversations
    }

    /// Returns messages matching the search query.
    /// Limited to 20 results for performance; requires at least 2 characters.
    var messageSearchResults: [Message] {
        // Require minimum 2 characters to avoid expensive searches on single chars
        guard searchText.count >= 2 else { return [] }
        return Array(messageStore.searchMessages(query: searchText).prefix(20))
    }

    /// Filtered conversations that are pinned, shown in a separate section
    private var pinnedConversations: [Conversation] {
        filteredConversations.filter { $0.isPinned }
    }

    /// Filtered conversations that are not pinned, shown in the main section
    private var regularConversations: [Conversation] {
        filteredConversations.filter { !$0.isPinned }
    }

    /// Returns appropriate SF Symbol name for empty state based on current context
    var emptyStateIcon: String {
        if !searchText.isEmpty {
            return "magnifyingglass"
        }
        switch messageStore.currentFilter {
        case .all:
            return "tray"
        case .unread:
            return "envelope.open"
        case .archived:
            return "archivebox"
        case .spam:
            return "shield.lefthalf.filled"
        }
    }

    /// Returns appropriate empty state message based on current context
    var emptyStateMessage: String {
        if !searchText.isEmpty {
            return "No results found"
        }
        switch messageStore.currentFilter {
        case .all:
            return "No conversations yet"
        case .unread:
            return "All caught up!\nNo unread messages"
        case .archived:
            return "No archived conversations"
        case .spam:
            return "No spam messages"
        }
    }

    // MARK: - Helper Methods

    /// Attempts to find a conversation matching the continuity state from another device.
    /// Uses multiple matching strategies: exact match, normalized phone number match, and name match.
    /// - Parameter state: The continuity state containing address/contact info from another device
    /// - Returns: The matching conversation if found, nil otherwise
    private func resolveConversation(for state: ContinuityService.ContinuityState) -> Conversation? {
        // Strategy 1: Exact address match
        if let exact = messageStore.conversations.first(where: { $0.address == state.address }) {
            return exact
        }

        // Strategy 2: Normalized phone number match (strips non-numeric characters)
        let normalizedTarget = normalizeAddress(state.address)
        if !normalizedTarget.isEmpty,
           let normalizedMatch = messageStore.conversations.first(where: {
               normalizeAddress($0.address) == normalizedTarget
           }) {
            return normalizedMatch
        }

        // Strategy 3: Contact name match (case-insensitive)
        if let name = state.contactName?.lowercased(),
           let nameMatch = messageStore.conversations.first(where: {
               $0.contactName?.lowercased() == name
           }) {
            return nameMatch
        }

        return nil
    }

    /// Checks if a conversation matches the given continuity state.
    /// Used to avoid showing continuity banner for already-selected conversations.
    /// - Parameters:
    ///   - conversation: The conversation to check
    ///   - state: The continuity state to compare against
    /// - Returns: True if the conversation matches the state
    private func isSameConversation(_ conversation: Conversation?, state: ContinuityService.ContinuityState) -> Bool {
        guard let conversation = conversation else { return false }
        if conversation.address == state.address {
            return true
        }
        // Fall back to normalized comparison for phone number format differences
        let normalizedConversation = normalizeAddress(conversation.address)
        let normalizedState = normalizeAddress(state.address)
        return !normalizedConversation.isEmpty && normalizedConversation == normalizedState
    }

    /// Strips non-numeric characters from an address/phone number for comparison.
    /// - Parameter value: The address string to normalize
    /// - Returns: String containing only numeric characters
    private func normalizeAddress(_ value: String) -> String {
        return value.filter { $0.isNumber }
    }

    // MARK: - Body

    var body: some View {
        VStack(spacing: 0) {
            // =================================================================
            // SEARCH BAR AND FILTER TOGGLE SECTION
            // Contains: search field, selection mode toggle, filter buttons,
            // label chips, and selection toolbar
            // =================================================================
            VStack(spacing: 8) {
                HStack(spacing: 8) {
                    // Search field
                    HStack {
                        Image(systemName: "magnifyingglass")
                            .foregroundColor(.secondary)
                        TextField("Search conversations", text: $searchText)
                            .textFieldStyle(.plain)
                            .focused($isSearchFieldFocused)
                            .onChange(of: isSearchFieldFocused) { _, focused in
                                withAnimation(.easeInOut(duration: 0.2)) {
                                    isSearchActive = focused
                                }
                            }

                        if !searchText.isEmpty || isSearchActive {
                            Button(action: {
                                searchText = ""
                                isSearchFieldFocused = false
                                isSearchActive = false
                                selectedLabelId = nil
                            }) {
                                Image(systemName: "xmark.circle.fill")
                                    .foregroundColor(.secondary)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(8)
                    .background(SyncFlowColors.surfaceSecondary)
                    .cornerRadius(8)

                    // Selection mode toggle button (compact icon)
                    if !isSelectionMode {
                        Button(action: {
                            withAnimation(.easeInOut(duration: 0.2)) {
                                isSelectionMode = true
                            }
                        }) {
                            Image(systemName: "checkmark.circle")
                                .font(.system(size: 16))
                                .foregroundColor(.secondary)
                        }
                        .buttonStyle(.plain)
                        .help("Select conversations")
                    }
                }

                // Filter buttons - only show when search is active
                if isSearchActive || !searchText.isEmpty {
                    VStack(spacing: 8) {
                        HStack(spacing: 8) {
                            ForEach(MessageStore.ConversationFilter.allCases, id: \.self) { filter in
                                FilterButton(
                                    filter: filter,
                                    isSelected: messageStore.currentFilter == filter,
                                    badgeCount: filter == .unread ? messageStore.totalUnreadCount : (filter == .spam ? messageStore.spamMessages.count : 0)
                                ) {
                                    messageStore.currentFilter = filter
                                }
                            }

                            Spacer()
                        }

                        // Label filter chips
                        if !availableLabels.isEmpty {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 6) {
                                    // Clear filter button
                                    if selectedLabelId != nil {
                                        Button(action: { selectedLabelId = nil }) {
                                            HStack(spacing: 4) {
                                                Image(systemName: "xmark")
                                                    .font(.caption2)
                                                Text("Clear")
                                                    .font(.caption)
                                            }
                                            .padding(.horizontal, 8)
                                            .padding(.vertical, 4)
                                            .background(Color.secondary.opacity(0.2))
                                            .cornerRadius(12)
                                        }
                                        .buttonStyle(.plain)
                                    }

                                    ForEach(availableLabels) { label in
                                        LabelFilterChip(
                                            label: label,
                                            isSelected: selectedLabelId == label.id
                                        ) {
                                            if selectedLabelId == label.id {
                                                selectedLabelId = nil
                                            } else {
                                                selectedLabelId = label.id
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    .transition(.opacity.combined(with: .move(edge: .top)))
                }

                // Selection toolbar - only visible when in selection mode
                if isSelectionMode {
                    HStack(spacing: 12) {
                        // Selection count
                        Text("\(selectedConversationIds.count) selected")
                            .font(.caption)
                            .fontWeight(.medium)
                            .foregroundColor(.primary)

                        Spacer()

                        // Action buttons
                        Button(action: {
                            selectedConversationIds = Set(filteredConversations.map { $0.id })
                        }) {
                            Label("All", systemImage: "checkmark.circle.fill")
                                .font(.caption)
                        }
                        .buttonStyle(.plain)
                        .foregroundColor(.blue)

                        if !selectedConversationIds.isEmpty {
                            Button(action: {
                                showBulkDeleteConfirmation = true
                            }) {
                                Label("Delete", systemImage: "trash")
                                    .font(.caption)
                            }
                            .buttonStyle(.plain)
                            .foregroundColor(.red)
                        }

                        Button(action: {
                            withAnimation(.easeInOut(duration: 0.2)) {
                                selectedConversationIds.removeAll()
                                isSelectionMode = false
                            }
                        }) {
                            Text("Done")
                                .font(.caption)
                                .fontWeight(.semibold)
                        }
                        .buttonStyle(.plain)
                        .foregroundColor(.blue)
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(Color.blue.opacity(0.08))
                    .cornerRadius(8)
                    .transition(.opacity.combined(with: .move(edge: .top)))
                }
            }
            .padding()

            // =================================================================
            // CONTINUITY BANNER
            // Shown when there's a suggestion to continue from another device
            // =================================================================
            if let state = appState.continuitySuggestion,
               !isSameConversation(selectedConversation, state: state) {
                ContinuityBannerView(
                    state: state,
                    onOpen: {
                        if let conversation = resolveConversation(for: state) {
                            selectedConversation = conversation
                        }
                    },
                    onDismiss: {
                        appState.dismissContinuitySuggestion()
                    }
                )
                .padding(.horizontal)
                .padding(.bottom, 8)
            }

            // Quick drop zone for drag-and-drop functionality
            QuickDropView()
                .padding(.horizontal)
                .padding(.bottom, 8)

            Divider()

            // =================================================================
            // CONVERSATIONS LIST
            // Main scrollable area containing conversation rows
            // Handles: loading state, spam view, empty state, and normal list
            // Uses LazyVStack for virtualized rendering performance
            // =================================================================
            if messageStore.isLoading && messageStore.conversations.isEmpty && messageStore.spamMessages.isEmpty {
                VStack(spacing: 10) {
                    ProgressView()
                    Text("Loading conversations...")
                        .foregroundColor(.secondary)
                        .font(.caption)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if messageStore.currentFilter == .spam {
                if messageStore.spamConversations.isEmpty {
                    VStack(spacing: 10) {
                        Image(systemName: emptyStateIcon)
                            .font(.system(size: 40))
                            .foregroundColor(.secondary)
                        Text(emptyStateMessage)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    ScrollView {
                        LazyVStack(spacing: 0) {
                            ForEach(Array(messageStore.spamConversations.enumerated()), id: \.element.id) { index, spamConversation in
                                SpamConversationRow(
                                    conversation: spamConversation,
                                    isSelected: messageStore.selectedSpamAddress == spamConversation.address,
                                    onSelect: {
                                        messageStore.selectedSpamAddress = spamConversation.address
                                        appState.selectedConversation = nil
                                    },
                                    onMarkNotSpam: {
                                        Task {
                                            await messageStore.markSpamAsNotSpam(address: spamConversation.address)
                                        }
                                    },
                                    onDelete: {
                                        Task {
                                            await messageStore.deleteSpamMessages(for: spamConversation.address)
                                        }
                                    }
                                )

                                if index < messageStore.spamConversations.count - 1 {
                                    Divider()
                                        .padding(.leading, SyncFlowSpacing.dividerInsetStart)
                                }
                            }
                        }
                    }
                }
            } else if filteredConversations.isEmpty {
                VStack(spacing: 10) {
                    Image(systemName: emptyStateIcon)
                        .font(.system(size: 40))
                        .foregroundColor(.secondary)
                    Text(emptyStateMessage)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)

                    if messageStore.currentFilter != .all {
                        Button("Show All") {
                            messageStore.currentFilter = .all
                        }
                        .buttonStyle(.borderedProminent)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        // Show message results when searching
                        if !searchText.isEmpty && !messageSearchResults.isEmpty {
                            VStack(alignment: .leading, spacing: 4) {
                                HStack {
                                    SidebarSectionTitle(title: "Messages")
                                    Spacer()
                                    Text("\(messageSearchResults.count) results")
                                        .font(.caption2)
                                        .foregroundColor(.secondary)
                                }
                                .padding(.horizontal, 12)
                                .padding(.top, 8)

                                ForEach(messageSearchResults) { message in
                                    MessageSearchResultRow(
                                        message: message,
                                        searchText: searchText
                                    )
                                    .onTapGesture {
                                        // Find and select the conversation for this message
                                        if let conversation = messageStore.conversations.first(where: { $0.address == message.address }) {
                                            selectedConversation = conversation
                                        }
                                    }
                                }

                                Divider()
                                    .padding(.vertical, 8)

                                SidebarSectionHeader(title: "Conversations")
                            }
                        }

                        if !pinnedConversations.isEmpty {
                            SidebarSectionHeader(title: "Pinned")
                                .padding(.top, searchText.isEmpty ? 6 : 2)

                            let pinned = pinnedConversations
                            ForEach(pinned) { conversation in
                                ConversationRow(
                                    conversation: conversation,
                                    isSelected: selectedConversation?.id == conversation.id,
                                    selectionMode: isSelectionMode,
                                    isBulkSelected: selectedConversationIds.contains(conversation.id),
                                    onToggleSelect: {
                                        if selectedConversationIds.contains(conversation.id) {
                                            selectedConversationIds.remove(conversation.id)
                                        } else {
                                            selectedConversationIds.insert(conversation.id)
                                        }
                                    }
                                )
                                .onTapGesture {
                                    if isSelectionMode {
                                        if selectedConversationIds.contains(conversation.id) {
                                            selectedConversationIds.remove(conversation.id)
                                        } else {
                                            selectedConversationIds.insert(conversation.id)
                                        }
                                    } else {
                                        selectedConversation = conversation
                                        messageStore.markConversationAsRead(conversation)
                                    }
                                }
                                .contextMenu {
                                    ConversationContextMenu(
                                        conversation: conversation,
                                        messageStore: messageStore
                                    )
                                }

                                if conversation.id != pinned.last?.id {
                                    ConversationSeparator()
                                }
                            }
                        }

                        if !regularConversations.isEmpty {
                            SidebarSectionHeader(title: "Conversations")
                                .padding(.top, pinnedConversations.isEmpty ? 6 : 4)

                            let regular = regularConversations
                            ForEach(regular) { conversation in
                                ConversationRow(
                                    conversation: conversation,
                                    isSelected: selectedConversation?.id == conversation.id,
                                    selectionMode: isSelectionMode,
                                    isBulkSelected: selectedConversationIds.contains(conversation.id),
                                    onToggleSelect: {
                                        if selectedConversationIds.contains(conversation.id) {
                                            selectedConversationIds.remove(conversation.id)
                                        } else {
                                            selectedConversationIds.insert(conversation.id)
                                        }
                                    }
                                )
                                .onTapGesture {
                                    if isSelectionMode {
                                        if selectedConversationIds.contains(conversation.id) {
                                            selectedConversationIds.remove(conversation.id)
                                        } else {
                                            selectedConversationIds.insert(conversation.id)
                                        }
                                    } else {
                                        selectedConversation = conversation
                                        messageStore.markConversationAsRead(conversation)
                                    }
                                }
                                .contextMenu {
                                    ConversationContextMenu(
                                        conversation: conversation,
                                        messageStore: messageStore
                                    )
                                }

                                if conversation.id != regular.last?.id {
                                    ConversationSeparator()
                                }
                            }
                        }

                        // Load More button for pagination
                        if messageStore.canLoadMore {
                            VStack(spacing: 8) {
                                Divider()
                                    .padding(.vertical, 8)

                                if messageStore.isLoadingMore {
                                    HStack {
                                        ProgressView()
                                            .scaleEffect(0.8)
                                        Text("Loading older messages...")
                                            .font(.caption)
                                            .foregroundColor(.secondary)
                                    }
                                    .padding()
                                } else {
                                    Button(action: {
                                        messageStore.loadMoreMessages()
                                    }) {
                                        HStack {
                                            Image(systemName: "arrow.clockwise")
                                            Text("Load More Conversations (30 days)")
                                        }
                                        .font(.callout)
                                        .padding(.vertical, 10)
                                        .padding(.horizontal, 16)
                                        .background(Color.accentColor.opacity(0.1))
                                        .cornerRadius(10)
                                    }
                                    .buttonStyle(.plain)
                                    .padding()
                                }
                            }
                        }
                    }
                    .padding(.bottom, 6)
                }
            }
        }
        .background(SyncFlowColors.sidebarBackground)
        .alert("Delete Selected Conversations?", isPresented: $showBulkDeleteConfirmation) {
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive) {
                let toDelete = messageStore.conversations.filter { selectedConversationIds.contains($0.id) }
                messageStore.deleteConversations(toDelete)
                selectedConversationIds.removeAll()
                isSelectionMode = false
                if let selected = selectedConversation, toDelete.contains(where: { $0.id == selected.id }) {
                    selectedConversation = nil
                }
            }
        } message: {
            Text("This will permanently delete the selected conversations and sync the deletion to connected devices.")
        }
        .onAppear {
            availableLabels = preferences.getLabels()
        }
    }
}

// MARK: - Spam Conversation Row

/// A row component for displaying spam conversation entries.
/// Shows contact initials, name, preview text, and message count with spam-themed styling.
struct SpamConversationRow: View {
    let conversation: SpamConversation
    let isSelected: Bool
    let onSelect: () -> Void
    let onMarkNotSpam: () -> Void
    let onDelete: () -> Void

    var body: some View {
        Button(action: onSelect) {
            HStack(spacing: 12) {
                Circle()
                    .fill(Color.red.opacity(0.15))
                    .frame(width: 36, height: 36)
                    .overlay(
                        Text(String(conversation.contactName.prefix(2)).uppercased())
                            .font(.caption)
                            .foregroundColor(.red)
                    )

                VStack(alignment: .leading, spacing: 2) {
                    Text(conversation.contactName)
                        .font(.subheadline)
                        .fontWeight(.semibold)
                        .foregroundColor(.primary)
                        .lineLimit(1)
                    Text(conversation.latestMessage)
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .lineLimit(1)
                }

                Spacer()

                Text("\(conversation.messageCount)")
                    .font(.caption2)
                    .foregroundColor(.red)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(Capsule().fill(Color.red.opacity(0.12)))
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(isSelected ? Color.red.opacity(0.08) : Color.clear)
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button(action: onMarkNotSpam) {
                Label("Not Spam", systemImage: "checkmark.shield")
            }

            Divider()

            Button(role: .destructive, action: onDelete) {
                Label("Delete", systemImage: "trash")
            }
        }
    }
}

// MARK: - Message Search Result Row

/// Displays individual message search results with highlighted search terms.
/// Shows avatar, contact name, message preview with highlighted text, and timestamp.
struct MessageSearchResultRow: View {
    /// The message to display
    let message: Message
    /// The search text to highlight within the message body
    let searchText: String

    var body: some View {
        HStack(spacing: 10) {
            // Avatar
            Circle()
                .fill(Color.blue.opacity(0.2))
                .frame(width: 32, height: 32)
                .overlay(
                    Image(systemName: "text.bubble")
                        .font(.system(size: 14))
                        .foregroundColor(.blue)
                )

            VStack(alignment: .leading, spacing: 2) {
                // Contact name
                Text(message.contactName ?? message.address)
                    .font(.caption)
                    .fontWeight(.medium)
                    .lineLimit(1)

                // Message preview with highlighted search term
                highlightedText(message.body, searchText: searchText)
                    .font(.caption2)
                    .foregroundColor(.secondary)
                    .lineLimit(2)
            }

            Spacer()

            // Timestamp
            Text(formatDate(message.date))
                .font(.caption2)
                .foregroundColor(.secondary)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(SyncFlowColors.surfaceSecondary)
        .contentShape(Rectangle())
    }

    // MARK: - Subviews / Helpers

    /// Creates a Text view with the search term highlighted in blue.
    /// Performs case-insensitive search and highlights only the first occurrence.
    /// - Parameters:
    ///   - text: The full text to display
    ///   - searchText: The term to highlight
    /// - Returns: A styled Text view with highlighting applied
    private func highlightedText(_ text: String, searchText: String) -> Text {
        guard !searchText.isEmpty else {
            return Text(text)
        }

        let lowercasedText = text.lowercased()
        let lowercasedSearch = searchText.lowercased()

        guard let range = lowercasedText.range(of: lowercasedSearch) else {
            return Text(text)
        }

        let startIndex = text.index(text.startIndex, offsetBy: lowercasedText.distance(from: lowercasedText.startIndex, to: range.lowerBound))
        let endIndex = text.index(startIndex, offsetBy: searchText.count)

        let before = String(text[..<startIndex])
        let match = String(text[startIndex..<endIndex])
        let after = String(text[endIndex...])

        return Text(before) + Text(match).bold().foregroundColor(.blue) + Text(after)
    }

    /// Formats a timestamp into a human-readable date string.
    /// Shows time for today, "Yesterday" for yesterday, and date for older messages.
    /// - Parameter timestamp: Unix timestamp in milliseconds
    /// - Returns: Formatted date string
    private func formatDate(_ timestamp: Double) -> String {
        let date = Date(timeIntervalSince1970: timestamp / 1000)
        let formatter = DateFormatter()

        if Calendar.current.isDateInToday(date) {
            formatter.dateFormat = "h:mm a"
        } else if Calendar.current.isDateInYesterday(date) {
            return "Yesterday"
        } else {
            formatter.dateFormat = "MMM d"
        }

        return formatter.string(from: date)
    }
}

// MARK: - Conversation Context Menu

/// Context menu for conversation rows providing quick actions.
/// Includes: pin/unpin, mark as read, labels, archive, block, and delete options.
struct ConversationContextMenu: View {
    /// The conversation this menu operates on
    let conversation: Conversation
    /// The message store for executing actions
    let messageStore: MessageStore

    /// Preferences service for label management
    private let preferences = PreferencesService.shared

    var body: some View {
        Button(action: {
            messageStore.togglePin(conversation)
        }) {
            Label(conversation.isPinned ? "Unpin" : "Pin", systemImage: conversation.isPinned ? "pin.slash" : "pin")
        }

        Button(action: {
            messageStore.markConversationAsRead(conversation)
        }) {
            Label("Mark as Read", systemImage: "envelope.open")
        }
        .disabled(conversation.unreadCount == 0)

        Divider()

        // Labels submenu
        Menu {
            let allLabels = preferences.getLabels()
            let assignedIds = Set((preferences.getLabelAssignments()[conversation.address] ?? []))

            ForEach(allLabels) { label in
                Button(action: {
                    preferences.toggleLabel(label.id, for: conversation.address)
                }) {
                    HStack {
                        Image(systemName: label.icon)
                        Text(label.name)
                        if assignedIds.contains(label.id) {
                            Spacer()
                            Image(systemName: "checkmark")
                        }
                    }
                }
            }

            Divider()

            Button(action: {
                messageStore.markConversationAsSpam(conversation)
            }) {
                Label("Spam", systemImage: "shield.lefthalf.filled")
            }
        } label: {
            Label("Labels", systemImage: "tag")
        }

        Divider()

        Button(action: {
            messageStore.toggleArchive(conversation)
        }) {
            Label(conversation.isArchived ? "Unarchive" : "Archive", systemImage: conversation.isArchived ? "tray.and.arrow.up" : "archivebox")
        }

        Button(action: {
            messageStore.toggleBlock(conversation)
        }) {
            Label(conversation.isBlocked ? "Unblock" : "Block", systemImage: conversation.isBlocked ? "hand.raised.slash" : "hand.raised")
        }

        Divider()

        Button(role: .destructive, action: {
            messageStore.deleteConversation(conversation)
        }) {
            Label("Delete Conversation", systemImage: "trash")
        }
    }
}

// MARK: - Conversation Row

/// Individual conversation row component displaying contact info, preview, and status indicators.
/// Supports selection mode for bulk operations and shows labels, pin status, and unread count.
struct ConversationRow: View {

    // MARK: - Properties

    /// The conversation data to display
    let conversation: Conversation
    /// Whether this row is currently selected (highlighted)
    let isSelected: Bool
    /// Whether bulk selection mode is active
    let selectionMode: Bool
    /// Whether this conversation is selected in bulk mode
    let isBulkSelected: Bool
    /// Callback when selection checkbox is toggled
    let onToggleSelect: () -> Void

    /// Preferences service for label access
    private let preferences = PreferencesService.shared

    /// Reusable rounded rectangle shape for row styling
    private var rowShape: RoundedRectangle {
        RoundedRectangle(cornerRadius: 12, style: .continuous)
    }

    /// Labels assigned to this conversation
    var conversationLabels: [PreferencesService.ConversationLabel] {
        preferences.getLabels(for: conversation.address)
    }

    private var encryptionIconName: String {
        conversation.lastMessageEncrypted ? "lock.fill" : "lock.open"
    }

    private var encryptionIconColor: Color {
        if conversation.lastMessageEncrypted {
            return .green
        }
        if conversation.lastMessageE2eeFailed {
            return .orange
        }
        return .secondary
    }

    // MARK: - Body

    var body: some View {
        HStack(spacing: 12) {
            if selectionMode {
                Button(action: onToggleSelect) {
                    Image(systemName: isBulkSelected ? "checkmark.circle.fill" : "circle")
                        .foregroundColor(isBulkSelected ? .blue : .secondary)
                }
                .buttonStyle(.plain)
            }
            // Avatar with color
            ZStack(alignment: .topTrailing) {
                Circle()
                    .fill(Color(hex: conversation.avatarColor ?? "#2196F3") ?? .blue)
                    .frame(width: 40, height: 40)
                    .overlay(
                        Text(conversation.initials)
                            .font(.subheadline)
                            .foregroundColor(.white)
                    )

                // Pin indicator
                if conversation.isPinned {
                    Circle()
                        .fill(Color.orange)
                        .frame(width: 16, height: 16)
                        .overlay(
                            Image(systemName: "pin.fill")
                                .font(.system(size: 8))
                                .foregroundColor(.white)
                        )
                        .offset(x: 4, y: -4)
                }
            }

            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(conversation.displayName)
                        .font(.headline)
                        .fontWeight(conversation.unreadCount > 0 ? .bold : .regular)
                        .lineLimit(1)

                    Spacer()

                    Text(conversation.formattedTime)
                        .font(.caption)
                        .foregroundColor(conversation.unreadCount > 0 ? .blue : .secondary)
                        .fontWeight(conversation.unreadCount > 0 ? .semibold : .regular)
                }

                HStack(alignment: .center, spacing: 4) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(conversation.preview)
                            .font(.subheadline)
                            .foregroundColor(conversation.unreadCount > 0 ? .primary : .secondary)
                            .fontWeight(conversation.unreadCount > 0 ? .medium : .regular)
                            .lineLimit(1)

                        // Labels row
                        if !conversationLabels.isEmpty {
                            HStack(spacing: 4) {
                                ForEach(conversationLabels.prefix(3)) { label in
                                    LabelBadge(label: label)
                                }
                                if conversationLabels.count > 3 {
                                    Text("+\(conversationLabels.count - 3)")
                                        .font(.caption2)
                                        .foregroundColor(.secondary)
                                }
                            }
                        }
                    }

                    Spacer()

                    Image(systemName: encryptionIconName)
                        .font(.caption2)
                        .foregroundColor(encryptionIconColor)
                        .help(conversation.lastMessageEncrypted ? "Encrypted" : "Not encrypted")

                    if conversation.unreadCount > 0 {
                        ZStack {
                            Capsule()
                                .fill(Color.blue)
                                .frame(minWidth: 20, maxHeight: 18)

                            Text("\(conversation.unreadCount)")
                                .font(.caption2)
                                .fontWeight(.bold)
                                .foregroundColor(.white)
                                .padding(.horizontal, 6)
                        }
                    }
                }
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(
            ZStack(alignment: .leading) {
                if isSelected {
                    rowShape
                        .fill(Color.accentColor.opacity(0.12))

                    rowShape
                        .fill(Color.accentColor)
                        .frame(width: 3)
                }
            }
        )
        .clipShape(rowShape)
        .contentShape(Rectangle())
    }
}

/// Visual separator between conversation rows with proper indentation.
struct ConversationSeparator: View {
    var body: some View {
        Rectangle()
            .fill(SyncFlowColors.divider)
            .frame(height: 1)
            .padding(.leading, SyncFlowSpacing.avatarMd + SyncFlowSpacing.listItemContentGap + SyncFlowSpacing.listItemHorizontal)
            .padding(.trailing, SyncFlowSpacing.listItemHorizontal)
    }
}

// MARK: - Sidebar Section Header

/// Section header for the sidebar with title and padding.
struct SidebarSectionHeader: View {
    /// The section title to display
    let title: String

    var body: some View {
        SidebarSectionTitle(title: title)
            .padding(.horizontal, 12)
            .padding(.top, 4)
            .padding(.bottom, 6)
    }
}

/// Styled section title text (uppercase, small, secondary color).
struct SidebarSectionTitle: View {
    /// The title text to display
    let title: String

    var body: some View {
        Text(title.uppercased())
            .font(.caption2)
            .fontWeight(.semibold)
            .foregroundColor(.secondary)
            .tracking(0.8)
    }
}

// MARK: - Filter Button

/// A toggleable filter button for conversation filtering (All, Unread, Archived, Spam).
/// Shows icon, label, and optional badge count. Highlights when selected.
struct FilterButton: View {
    /// The filter type this button represents
    let filter: MessageStore.ConversationFilter
    /// Whether this filter is currently active
    let isSelected: Bool
    /// Badge count to show (e.g., unread count); only shown for specific filter types
    let badgeCount: Int
    /// Callback when button is tapped
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                Image(systemName: isSelected ? "\(filter.icon).fill" : filter.icon)
                    .font(.system(size: 12))
                Text(filter.rawValue)
                    .font(.caption)

                if badgeCount > 0 && (filter == .unread || filter == .spam) {
                    let badgeColor = filter == .spam ? Color.red : Color.blue
                    Text("\(badgeCount)")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundColor(.white)
                        .padding(.horizontal, 5)
                        .padding(.vertical, 2)
                        .background(Capsule().fill(badgeColor))
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(
                RoundedRectangle(cornerRadius: 8)
                    .fill(isSelected ? Color.accentColor.opacity(0.15) : Color.clear)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(isSelected ? Color.accentColor : Color.gray.opacity(0.3), lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
        .foregroundColor(isSelected ? .accentColor : .secondary)
    }
}

// MARK: - Continuity Banner

/// Banner view suggesting the user continue a conversation from another device.
/// Part of the Handoff/Continuity feature for cross-device workflow.
struct ContinuityBannerView: View {
    /// The continuity state containing device name and conversation info
    let state: ContinuityService.ContinuityState
    /// Callback when user taps "Open" to continue the conversation
    let onOpen: () -> Void
    /// Callback when user dismisses the banner
    let onDismiss: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "rectangle.on.rectangle")
                .font(.system(size: 18, weight: .semibold))
                .foregroundColor(.blue)

            VStack(alignment: .leading, spacing: 2) {
                Text("Continue from \(state.deviceName)")
                    .font(.subheadline)
                    .fontWeight(.semibold)
                Text(state.contactName ?? state.address)
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            Spacer()

            Button("Open", action: onOpen)
                .buttonStyle(.borderedProminent)

            Button("Dismiss", action: onDismiss)
                .buttonStyle(.bordered)
        }
        .padding(10)
        .background(Color.blue.opacity(0.08))
        .cornerRadius(12)
    }
}
