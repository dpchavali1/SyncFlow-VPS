//
//  ContentView.swift
//  SyncFlowMac
//
//  Main content view - shows either pairing screen or main interface
//

// =============================================================================
// MARK: - Architecture Overview
// =============================================================================
//
// ContentView is the root view of the SyncFlow macOS application. It acts as
// the primary routing view, determining which interface to display based on
// the application's pairing state.
//
// VIEW HIERARCHY:
// ---------------
// ContentView
// ├── PairingView (when not paired)
// │   └── QR code scanner / pairing flow
// └── MainView (when paired)
//     ├── SideRail (navigation tabs)
//     ├── MessagesTabView / ContactsView / CallHistoryView / DealsView
//     └── Various overlays (incoming calls, banners, sheets)
//
// STATE MANAGEMENT:
// -----------------
// - AppState (via @EnvironmentObject): Central app state from SyncFlowMacApp
// - MessageStore (via @StateObject in MainView): SMS message data and operations
// - SubscriptionService: Premium subscription status
// - UsageTracker: Usage limits and statistics
//
// OVERLAY SYSTEM:
// ---------------
// ContentView uses ZStack to layer overlays on top of the main content:
// 1. IncomingCallView - Full-screen phone call incoming overlay
// 2. CallInProgressBanner - Top banner for active calls
// 3. IncomingSyncFlowCallView - Device-to-device WebRTC incoming call
// 4. SyncFlowCallView - Active WebRTC call UI
//
// Overlays are controlled by AppState published properties and use zIndex
// to ensure proper layering order.
//
// SHEET PRESENTATIONS:
// --------------------
// - Photo Gallery: showPhotoGallery
// - Notifications: showNotifications
// - Scheduled Messages: showScheduledMessages
//
// ANIMATION:
// ----------
// Spring and easeInOut animations are applied to overlay transitions
// for smooth user experience.
//
// =============================================================================

import SwiftUI
import AppKit

// =============================================================================
// MARK: - ContentView (Root View)
// =============================================================================

/// The root content view that routes between pairing and main interfaces.
///
/// ContentView observes AppState to determine whether to show:
/// - `VPSPairingView` or `PairingView` when `appState.isPaired` is false (depending on VPS mode)
/// - `MainView` when `appState.isPaired` is true
///
/// Additionally, it manages overlay presentations for incoming calls
/// and active call UI that need to appear above all other content.
struct ContentView: View {

    /// The central application state injected from SyncFlowMacApp
    @EnvironmentObject var appState: AppState

    /// VPS mode is always enabled
    private var isVPSMode: Bool { true }

    // =========================================================================
    // MARK: - View Body
    // =========================================================================

    var body: some View {
        ZStack {
            mainContent
            incomingCallOverlay
            callInProgressBanner
            incomingSyncFlowCallOverlay
            activeSyncFlowCallOverlay
        }
        .animation(.spring(), value: appState.incomingCall != nil)
        .animation(.spring(), value: appState.lastAnsweredCallId)
        .animation(.easeInOut, value: appState.incomingSyncFlowCall != nil)
        .animation(.easeInOut, value: appState.showSyncFlowCallView)
        .sheet(isPresented: $appState.showPhotoGallery) {
            PhotoGalleryView(photoService: appState.photoSyncService)
                .frame(minWidth: 500, minHeight: 400)
        }
        .sheet(isPresented: $appState.showNotifications) {
            NotificationListView(notificationService: appState.notificationMirrorService)
                .frame(minWidth: 400, minHeight: 350)
        }
        .sheet(isPresented: $appState.showScheduledMessages) {
            ScheduledMessagesView()
                .environmentObject(appState)
        }
    }

    @ViewBuilder
    private var mainContent: some View {
        if appState.isPaired {
            MainView()
        } else if isVPSMode {
            VPSPairingView()
        } else {
            PairingView()
        }
    }

    @ViewBuilder
    private var incomingCallOverlay: some View {
        if let incomingCall = appState.incomingCall {
            Color.black.opacity(0.4)
                .ignoresSafeArea()

            IncomingCallView(
                call: incomingCall,
                onAnswer: {
                    appState.answerCall(incomingCall)
                },
                onReject: {
                    appState.rejectCall(incomingCall)
                }
            )
            .transition(.scale.combined(with: .opacity))
            .zIndex(1000)
        }
    }

    @ViewBuilder
    private var callInProgressBanner: some View {
        if let bannerCall = appState.activeCalls.first(where: { $0.id == appState.lastAnsweredCallId && $0.callState == .active }) {
            VStack {
                CallInProgressBanner(
                    call: bannerCall,
                    onEndCall: {
                        appState.endCall(bannerCall)
                    },
                    onDismiss: {
                        appState.lastAnsweredCallId = nil
                    }
                )
                .padding(.horizontal, 16)
                .padding(.top, 20)

                Spacer()
            }
            .transition(.move(edge: .top).combined(with: .opacity))
            .zIndex(900)
        }
    }

    @ViewBuilder
    private var incomingSyncFlowCallOverlay: some View {
        if let incomingSyncFlowCall = appState.incomingSyncFlowCall {
            IncomingSyncFlowCallView(
                call: incomingSyncFlowCall,
                onAcceptVideo: {
                    appState.answerSyncFlowCall(incomingSyncFlowCall, withVideo: true)
                },
                onAcceptAudio: {
                    appState.answerSyncFlowCall(incomingSyncFlowCall, withVideo: false)
                },
                onDecline: {
                    appState.rejectSyncFlowCall(incomingSyncFlowCall)
                }
            )
            .transition(.opacity)
            .zIndex(1100)
        }
    }

    @ViewBuilder
    private var activeSyncFlowCallOverlay: some View {
        if appState.showSyncFlowCallView {
            SyncFlowCallView(callManager: appState.syncFlowCallManager)
                .transition(.opacity)
                .zIndex(1050)
        }
    }
}

// =============================================================================
// MARK: - MainView (Primary Interface)
// =============================================================================

/// The main application interface shown after successful pairing.
///
/// MainView provides the primary user interface with:
/// - Side rail navigation for switching between tabs
/// - Content area that changes based on selected tab
/// - Usage and subscription banners for free tier users
/// - Media control bar for remote phone media playback
///
/// ## State Objects
/// - `messageStore`: Manages SMS message data, created once per MainView instance
/// - `subscriptionService`: Tracks premium subscription status
/// - `usageTracker`: Monitors usage limits for free tier
/// - `mediaControlService`: Remote media playback control
///
/// ## Tab Navigation
/// The side rail provides access to four main sections:
/// 1. **Messages**: SMS/MMS conversations (MessagesTabView)
/// 2. **Contacts**: Phone contacts synced from device (ContactsView)
/// 3. **Calls**: Call history and dialer (CallHistoryView)
/// 4. **Deals**: Promotional offers (DealsView)
///
/// ## Lifecycle
/// On appear, starts listening for messages if userId is available.
/// Listens for userId changes to handle re-pairing scenarios.
struct MainView: View {

    // =========================================================================
    // MARK: - Environment & State
    // =========================================================================

    /// Central app state from parent view
    @EnvironmentObject var appState: AppState

    /// Message data store - manages conversations and messages
    /// Created as @StateObject to persist across view updates
    @StateObject private var messageStore = MessageStore()

    /// Subscription service for premium feature gating
    @StateObject private var subscriptionService = SubscriptionService.shared

    /// Usage tracking for free tier limits
    @StateObject private var usageTracker = UsageTracker.shared

    /// Media control service for remote playback control
    @ObservedObject private var mediaControlService = MediaControlService.shared

    /// Local search text for filtering conversations
    @State private var searchText = ""

    /// Modal presentation flags
    @State private var showKeyboardShortcuts = false
    @State private var showAIAssistant = false
    // showSupportChat is on appState so Help menu can trigger it too

    // =========================================================================
    // MARK: - View Body
    // =========================================================================

    var body: some View {
        HStack(spacing: 0) {
            SideRail(
                selectedTab: $appState.selectedTab,
                onNewMessage: { appState.showNewMessage = true },
                onAIAssistant: { showAIAssistant = true },
                onSupportChat: { appState.showSupportChat = true }
            )

            Divider()
                .background(SyncFlowColors.divider)

            VStack(spacing: 0) {
                // Usage limit warning banner
                if usageTracker.showLimitWarning, let stats = usageTracker.usageStats {
                    UsageLimitWarningBanner(stats: stats, onRefresh: {
                        usageTracker.refreshUsageStats()
                    })
                        .padding(.horizontal, 16)
                        .padding(.top, 8)
                }

                // Subscription status banner (shown for all non-premium users)
                if !subscriptionService.isPremium {
                    SubscriptionStatusBanner()
                        .padding(.horizontal, 16)
                        .padding(.top, 8)
                }

                if appState.showMediaBar {
                    MediaControlBar(mediaService: mediaControlService)
                        .padding(.horizontal, 16)
                        .padding(.top, 8)
                }

                // Content based on selected tab
                Group {
                    switch appState.selectedTab {
                    case .messages:
                        MessagesTabView(searchText: $searchText)
                            .environmentObject(messageStore)
                    case .contacts:
                        ContactsView()
                    case .callHistory:
                        CallHistoryView()
                    case .deals:
                        DealsView()
                    }
                }

                // Bottom upgrade banner for free/trial users
                if !subscriptionService.isPremium {
                    UpgradeBanner()
                }
            }
        }
        .navigationTitle("SyncFlow")
        .toolbar {
            ToolbarItem(placement: .navigation) {
                if appState.selectedTab == .messages {
                    Button(action: { appState.showNewMessage.toggle() }) {
                        Image(systemName: "square.and.pencil")
                    }
                    .help("New Message")
                }
            }
        }
        .sheet(isPresented: $appState.showNewMessage) {
            NewMessageView()
                .environmentObject(messageStore)
                .frame(width: 500, height: 400)
        }
        .onAppear {
            if let userId = appState.userId {
                messageStore.startListening(userId: userId)
                // Fetch usage stats
                Task {
                    await usageTracker.fetchUsageStats(userId: userId)
                }
            }
        }
        .onChange(of: appState.userId) { _, newUserId in
            if let userId = newUserId {
                // Start listening when user is paired (userId becomes available)
                print("[ContentView] User paired, starting message listener for userId: \(userId)")
                messageStore.startListening(userId: userId)
                // Fetch usage stats
                Task {
                    await usageTracker.fetchUsageStats(userId: userId)
                }
            } else {
                // Stop listening when user is unpaired (userId becomes nil)
                print("[ContentView] User unpaired, stopping message listener")
                messageStore.stopListening()
            }
        }
        // Keyboard shortcuts help overlay
        .overlay(
            Group {
                if showKeyboardShortcuts {
                    Color.black.opacity(0.3)
                        .ignoresSafeArea()
                        .onTapGesture {
                            showKeyboardShortcuts = false
                        }

                    KeyboardShortcutsView(isPresented: $showKeyboardShortcuts)
                        .transition(.scale.combined(with: .opacity))
                }
            }
        )
        .animation(.spring(), value: showKeyboardShortcuts)
        // Keyboard shortcut: Cmd+? to show help
        .background(
            Button("") {
                showKeyboardShortcuts = true
            }
            .keyboardShortcut("/", modifiers: [.command, .shift])
            .hidden()
        )
        // Keyboard shortcut: Cmd+Shift+A for AI Assistant
        .background(
            Button("") {
                showAIAssistant = true
            }
            .keyboardShortcut("a", modifiers: [.command, .shift])
            .hidden()
        )
        // AI Assistant sheet
        .sheet(isPresented: $showAIAssistant) {
            AIAssistantView()
                .environmentObject(messageStore)
                .frame(minWidth: 500, minHeight: 600)
        }
        // Support Chat sheet
        .sheet(isPresented: $appState.showSupportChat) {
            SupportChatView()
                .environmentObject(appState)
        }
    }
}

// =============================================================================
// MARK: - SideRail (Navigation Component)
// =============================================================================

/// The vertical navigation rail on the left side of the main interface.
///
/// SideRail provides:
/// - New message button at top
/// - Tab navigation buttons (Messages, Contacts, Calls, Deals)
/// - AI Assistant and Support Chat buttons at bottom
/// - Settings button linking to preferences
///
/// The Deals tab has special styling with gradient and pulse animation
/// to draw attention to promotional content.
struct SideRail: View {

    /// Currently selected navigation tab (binding to parent)
    @Binding var selectedTab: AppTab

    /// Callback to show new message composer
    let onNewMessage: () -> Void

    /// Optional callback to show AI assistant
    var onAIAssistant: (() -> Void)? = nil

    /// Optional callback to show support chat
    var onSupportChat: (() -> Void)? = nil

    /// Animation state for Deals icon pulse effect
    @State private var dealsIconPulse = false

    var body: some View {
        VStack(spacing: 18) {
            Button(action: onNewMessage) {
                Image(systemName: "square.and.pencil")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(SyncFlowColors.primary)
            }
            .buttonStyle(.plain)
            .padding(.top, 14)

            Divider()
                .background(SyncFlowColors.divider)

            ForEach(AppTab.allCases, id: \.self) { tab in
                if tab == .deals {
                    // Prominent Deals button with gradient and animation
                    Button(action: { selectedTab = tab }) {
                        ZStack {
                            // Animated glow effect
                            Circle()
                                .fill(
                                    RadialGradient(
                                        colors: [Color.orange.opacity(0.4), Color.clear],
                                        center: .center,
                                        startRadius: 0,
                                        endRadius: 25
                                    )
                                )
                                .frame(width: 44, height: 44)
                                .scaleEffect(dealsIconPulse ? 1.2 : 1.0)
                                .opacity(dealsIconPulse ? 0.6 : 0.3)

                            // Icon with gradient
                            Image(systemName: selectedTab == tab ? "tag.fill" : "tag.fill")
                                .font(.system(size: 18, weight: .bold))
                                .foregroundStyle(
                                    LinearGradient(
                                        colors: [Color.orange, Color.pink],
                                        startPoint: .topLeading,
                                        endPoint: .bottomTrailing
                                    )
                                )
                                .frame(width: 36, height: 36)
                                .background(
                                    Circle()
                                        .fill(
                                            selectedTab == tab
                                                ? LinearGradient(colors: [Color.orange.opacity(0.25), Color.pink.opacity(0.2)], startPoint: .topLeading, endPoint: .bottomTrailing)
                                                : LinearGradient(colors: [Color.orange.opacity(0.12), Color.pink.opacity(0.08)], startPoint: .topLeading, endPoint: .bottomTrailing)
                                        )
                                )
                        }
                    }
                    .buttonStyle(.plain)
                    .help("Discover Deals")
                    .onAppear {
                        // Subtle pulse animation
                        withAnimation(.easeInOut(duration: 2).repeatForever(autoreverses: true)) {
                            dealsIconPulse = true
                        }
                    }
                } else {
                    Button(action: { selectedTab = tab }) {
                        Image(systemName: tab.icon)
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundColor(selectedTab == tab ? SyncFlowColors.primary : SyncFlowColors.textSecondary)
                            .frame(width: 36, height: 36)
                            .background(
                                Circle()
                                    .fill(selectedTab == tab ? SyncFlowColors.primary.opacity(0.18) : Color.clear)
                            )
                    }
                    .buttonStyle(.plain)
                }
            }

            Spacer()

            // AI Assistant button
            if let onAIAssistant = onAIAssistant {
                Button(action: onAIAssistant) {
                    Image(systemName: "sparkles")
                        .font(.system(size: 16))
                        .foregroundColor(SyncFlowColors.textSecondary)
                        .frame(width: 36, height: 36)
                        .background(
                            Circle()
                                .fill(Color.clear)
                        )
                }
                .buttonStyle(.plain)
                .help("AI Assistant (Cmd+Shift+A)")
            }

            // Support Chat button
            if let onSupportChat = onSupportChat {
                Button(action: onSupportChat) {
                    Image(systemName: "questionmark.bubble")
                        .font(.system(size: 16))
                        .foregroundColor(SyncFlowColors.textSecondary)
                        .frame(width: 36, height: 36)
                        .background(
                            Circle()
                                .fill(Color.clear)
                        )
                }
                .buttonStyle(.plain)
                .help("AI Support Chat")
                .padding(.bottom, 8)
            }

            SettingsLink {
                Image(systemName: "gearshape")
                    .font(.system(size: 16))
                    .foregroundColor(SyncFlowColors.textSecondary)
            }
            .buttonStyle(.plain)
            .padding(.bottom, 16)
        }
        .frame(width: 52)
        .background(SyncFlowColors.sidebarRailBackground)
    }
}

// =============================================================================
// MARK: - MessagesTabView (Conversations & Messages)
// =============================================================================

/// The messages tab content showing conversation list and message detail.
///
/// Uses NavigationSplitView for a two-column layout:
/// - **Sidebar**: ConversationListView with search and conversation list
/// - **Detail**: MessageView for selected conversation or SpamDetailView for spam filter
///
/// The view adapts based on `messageStore.currentFilter`:
/// - Normal filter: Shows MessageView for selected conversation
/// - Spam filter: Shows SpamDetailView for managing spam messages
struct MessagesTabView: View {

    /// Central app state for selected conversation
    @EnvironmentObject var appState: AppState

    /// Message store for conversation and message data
    @EnvironmentObject var messageStore: MessageStore

    /// Search text binding for filtering conversations
    @Binding var searchText: String

    var body: some View {
        NavigationSplitView {
            // Conversations List (Sidebar)
            ConversationListView(
                searchText: $searchText,
                selectedConversation: $appState.selectedConversation
            )
            .environmentObject(messageStore)
            .frame(minWidth: 250)
        } detail: {
            // Message View (Detail)
            if messageStore.currentFilter == .spam {
                SpamDetailView()
                    .environmentObject(messageStore)
            } else if let conversation = appState.selectedConversation {
                MessageView(conversation: conversation)
                    .environmentObject(messageStore)
                    .id(conversation.id) // Force fresh view instance when conversation changes
            } else {
                EmptyStateView()
            }
        }
    }
}

/// Detail view for spam message management.
/// Shows spam messages from a selected sender with options to remove.
struct SpamDetailView: View {
    @EnvironmentObject var messageStore: MessageStore

    /// Currently selected spam sender address
    private var selectedAddress: String? {
        messageStore.selectedSpamAddress
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Spam")
                        .font(.title2)
                        .fontWeight(.semibold)
                    if let address = selectedAddress {
                        Text(address)
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                    }
                }

                Spacer()

                if let address = selectedAddress {
                    Button("Remove Sender") {
                        Task { await messageStore.deleteSpamMessages(for: address) }
                    }
                    .buttonStyle(.bordered)
                }

                Button("Clear All") {
                    Task { await messageStore.clearAllSpam() }
                }
                .buttonStyle(.bordered)
            }
            .padding()
            Divider()

            if let address = selectedAddress {
                let messages = messageStore.spamMessages(for: address)

                if messages.isEmpty {
                    Spacer()
                    Text("No spam messages for this sender")
                        .foregroundColor(.secondary)
                    Spacer()
                } else {
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 12) {
                            ForEach(messages) { message in
                                VStack(alignment: .leading, spacing: 6) {
                                    Text(message.body)
                                        .font(.body)
                                    Text(message.timestamp, style: .time)
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }
                                .padding()
                                .background(Color.red.opacity(0.06))
                                .cornerRadius(10)
                            }
                        }
                        .padding()
                    }
                }
            } else {
                Spacer()
                Text("Select a spam sender to view messages")
                    .foregroundColor(.secondary)
                Spacer()
            }
        }
        .background(Color(nsColor: .windowBackgroundColor))
    }
}

// =============================================================================
// MARK: - EmptyStateView
// =============================================================================

/// Placeholder view shown when no conversation is selected.
/// Prompts user to select a conversation from the sidebar.
struct EmptyStateView: View {
    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "message.fill")
                .font(.system(size: 60))
                .foregroundColor(.secondary)

            Text("Select a conversation")
                .font(.title2)
                .foregroundColor(.secondary)

            Text("Choose a conversation from the sidebar to view messages")
                .font(.body)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(nsColor: .windowBackgroundColor))
    }
}

// =============================================================================
// MARK: - UsageLimitWarningBanner
// =============================================================================

/// Warning banner shown when user approaches or exceeds usage limits.
///
/// Displays for free tier users when:
/// - Monthly upload limit is reached (MMS won't sync)
/// - Storage limit is exceeded
///
/// Expandable to show detailed usage breakdown with progress bars.
struct UsageLimitWarningBanner: View {

    /// Current usage statistics
    let stats: UsageStats

    /// Callback to refresh usage stats from server
    let onRefresh: () -> Void

    /// Whether the detailed breakdown is expanded
    @State private var isExpanded = false

    /// Whether a refresh is in progress
    @State private var isRefreshing = false

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.title2)
                    .foregroundColor(.orange)

                VStack(alignment: .leading, spacing: 2) {
                    Text(stats.isMonthlyLimitExceeded ? "Monthly Upload Limit Reached" : "Storage Limit Reached")
                        .font(.headline)

                    Text("MMS and attachments won't sync. Clear data in Settings → Usage or upgrade.")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }

                Spacer()

                // Refresh button
                Button(action: {
                    isRefreshing = true
                    onRefresh()
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
                        isRefreshing = false
                    }
                }) {
                    if isRefreshing {
                        ProgressView()
                            .scaleEffect(0.7)
                    } else {
                        Image(systemName: "arrow.clockwise")
                    }
                }
                .buttonStyle(.plain)
                .help("Refresh usage stats")

                Button(action: { isExpanded.toggle() }) {
                    Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                }
                .buttonStyle(.plain)
            }
            .padding(12)

            if isExpanded {
                Divider()

                VStack(alignment: .leading, spacing: 8) {
                    // Monthly usage
                    HStack {
                        Text("Monthly Upload:")
                            .font(.caption)
                        Spacer()
                        Text(stats.formattedMonthlyUsage)
                            .font(.caption)
                            .foregroundColor(stats.isMonthlyLimitExceeded ? .red : .secondary)
                    }

                    ProgressView(value: min(stats.monthlyUsagePercent / 100, 1.0))
                        .tint(stats.isMonthlyLimitExceeded ? .red : .blue)

                    // Storage usage
                    HStack {
                        Text("Storage:")
                            .font(.caption)
                        Spacer()
                        Text(stats.formattedStorageUsage)
                            .font(.caption)
                            .foregroundColor(stats.isStorageLimitExceeded ? .red : .secondary)
                    }

                    ProgressView(value: min(stats.storageUsagePercent / 100, 1.0))
                        .tint(stats.isStorageLimitExceeded ? .red : .blue)

                    HStack {
                        Text("Go to Settings → Usage to clear MMS data and free up storage.")
                            .font(.caption2)
                            .foregroundColor(.secondary)
                        Spacer()
                    }
                    .padding(.top, 4)
                }
                .padding(12)
            }
        }
        .background(Color.orange.opacity(0.15))
        .cornerRadius(8)
    }
}

// =============================================================================
// MARK: - UpgradeBanner
// =============================================================================

/// Promotional banner shown at the bottom for free tier users.
///
/// Encourages upgrade to SyncFlow Pro with:
/// - Feature highlights (photo sync, 500MB storage)
/// - Current pricing
/// - Direct upgrade button opening paywall
struct UpgradeBanner: View {

    /// Subscription service to check current plan
    @StateObject private var subscriptionService = SubscriptionService.shared

    /// Whether to show the paywall sheet
    @State private var showPaywall = false

    var body: some View {
        HStack(spacing: 16) {
            Image(systemName: "sparkles")
                .font(.title2)
                .foregroundColor(.blue)

            VStack(alignment: .leading, spacing: 2) {
                Text("Upgrade to SyncFlow Pro")
                    .font(.headline)
                Text("Unlock photo sync, 500MB storage, and remove this banner")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            Spacer()

            Text("$4.99/mo")
                .font(.subheadline)
                .foregroundColor(.secondary)

            Button("Upgrade") {
                showPaywall = true
            }
            .buttonStyle(.borderedProminent)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(
            LinearGradient(
                colors: [Color.blue.opacity(0.1), Color.purple.opacity(0.1)],
                startPoint: .leading,
                endPoint: .trailing
            )
        )
        .sheet(isPresented: $showPaywall) {
            PaywallView()
        }
    }
}
