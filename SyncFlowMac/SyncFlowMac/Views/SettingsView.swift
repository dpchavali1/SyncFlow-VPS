//
//  SettingsView.swift
//  SyncFlowMac
//
//  Settings and preferences view
//
//  =============================================================================
//  PURPOSE:
//  This view provides the main settings interface for the SyncFlow macOS app.
//  It uses a TabView to organize settings into categories: General, Sync,
//  Notifications, Subscription, Usage, Support, and About.
//
//  USER INTERACTIONS:
//  - Navigate between settings tabs by clicking tab items
//  - Toggle switches for various preferences
//  - Configure notification sounds and custom colors
//  - Manage subscription status and upgrade
//  - Unpair device or delete account
//  - Access support chat and external links
//
//  STATE MANAGEMENT:
//  - @EnvironmentObject for AppState (pairing status, user ID)
//  - @AppStorage for persisting preferences to UserDefaults
//  - @State for local UI state (alerts, sheets, etc.)
//  - @StateObject for service instances (subscription, sounds)
//
//  PERFORMANCE CONSIDERATIONS:
//  - Settings are loaded lazily via @AppStorage
//  - Subscription status checked asynchronously
//  - Tab content only rendered when tab is selected
//  =============================================================================

import SwiftUI
import ServiceManagement

// MARK: - Settings Navigation

enum SettingsSection: String, CaseIterable, Identifiable {
    case overview
    case general
    case appearance
    case sync
    case notifications
    case usage
    case subscription
    case support
    case about

    var id: String { rawValue }

    var title: String {
        switch self {
        case .overview: return "Overview"
        case .general: return "General"
        case .appearance: return "Appearance"
        case .sync: return "Sync"
        case .notifications: return "Notifications"
        case .usage: return "Usage"
        case .subscription: return "Subscription"
        case .support: return "Support"
        case .about: return "About"
        }
    }

    var icon: String {
        switch self {
        case .overview: return "square.grid.2x2"
        case .general: return "gear"
        case .appearance: return "paintbrush"
        case .sync: return "arrow.triangle.2.circlepath"
        case .notifications: return "bell"
        case .usage: return "chart.bar"
        case .subscription: return "creditcard"
        case .support: return "questionmark.circle"
        case .about: return "info.circle"
        }
    }
}

// MARK: - Main Settings View

/// Root settings view containing tabbed navigation for all preference categories.
struct SettingsView: View {

    // MARK: - Environment

    /// App-wide state for pairing status and user info
    @EnvironmentObject var appState: AppState

    // MARK: - Persisted Settings (UserDefaults via @AppStorage)

    /// Whether push notifications are enabled
    @AppStorage("notifications_enabled") private var notificationsEnabled = true
    /// Whether notification sounds are enabled
    @AppStorage("sound_enabled") private var soundEnabled = true
    /// Whether message previews appear in notifications
    @AppStorage("show_previews") private var showPreviews = true
    /// Currently selected settings section
    @State private var selection: SettingsSection? = .overview

    // MARK: - Body

    var body: some View {
        if #available(macOS 13.0, *) {
            NavigationSplitView {
                List(selection: $selection) {
                    ForEach(SettingsSection.allCases) { section in
                        Label(section.title, systemImage: section.icon)
                            .tag(section)
                    }
                }
                .listStyle(.sidebar)
                .navigationTitle("Settings")
                .navigationSplitViewColumnWidth(min: 180, ideal: 200)
            } detail: {
                let current = selection ?? .overview
                Group {
                    switch current {
                    case .overview:
                        SettingsOverviewView(selection: $selection)
                            .environmentObject(appState)
                    case .general:
                        GeneralSettingsView()
                            .environmentObject(appState)
                    case .appearance:
                        AppearanceSettingsView()
                    case .sync:
                        SyncSettingsView()
                            .environmentObject(appState)
                    case .notifications:
                        NotificationSettingsView(
                            notificationsEnabled: $notificationsEnabled,
                            soundEnabled: $soundEnabled,
                            showPreviews: $showPreviews
                        )
                    case .usage:
                        UsageSettingsView()
                            .environmentObject(appState)
                    case .subscription:
                        SubscriptionSettingsView()
                    case .support:
                        SupportSettingsView()
                    case .about:
                        AboutView()
                    }
                }
                .navigationTitle(current.title)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .frame(minWidth: 820, minHeight: 600)
        } else {
            legacyTabView
        }
    }

    private var legacyTabView: some View {
        TabView {
            GeneralSettingsView()
                .environmentObject(appState)
                .tabItem {
                    Label("General", systemImage: "gear")
                }

            AppearanceSettingsView()
                .tabItem {
                    Label("Appearance", systemImage: "paintbrush")
                }

            SyncSettingsView()
                .environmentObject(appState)
                .tabItem {
                    Label("Sync", systemImage: "arrow.triangle.2.circlepath")
                }

            NotificationSettingsView(
                notificationsEnabled: $notificationsEnabled,
                soundEnabled: $soundEnabled,
                showPreviews: $showPreviews
            )
            .tabItem {
                Label("Notifications", systemImage: "bell")
            }

            SubscriptionSettingsView()
                .tabItem {
                    Label("Subscription", systemImage: "creditcard")
                }

            UsageSettingsView()
                .environmentObject(appState)
                .tabItem {
                    Label("Usage", systemImage: "chart.bar")
                }

            SupportSettingsView()
                .tabItem {
                    Label("Support", systemImage: "questionmark.circle")
                }

            AboutView()
                .tabItem {
                    Label("About", systemImage: "info.circle")
                }
        }
        .frame(width: 640, height: 520)
    }
}

// MARK: - Settings Overview

struct SettingsOverviewView: View {
    @EnvironmentObject var appState: AppState
    @StateObject private var subscriptionService = SubscriptionService.shared
    @Binding var selection: SettingsSection?

    @AppStorage("notifications_enabled") private var notificationsEnabled = true
    @AppStorage("sound_enabled") private var soundEnabled = true
    @AppStorage("show_previews") private var showPreviews = true
    @AppStorage("e2ee_enabled") private var e2eeEnabled = true
    @AppStorage("auto_start") private var autoStart = false

    private let columns = [GridItem(.adaptive(minimum: 260), spacing: 16)]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                header

                LazyVGrid(columns: columns, spacing: 16) {
                    connectionCard
                    securityCard
                    notificationsCard
                    syncCard
                    subscriptionCard
                    usageCard
                }

                quickActions
            }
            .padding(24)
        }
        .onAppear {
            if #available(macOS 13.0, *) {
                autoStart = SMAppService.mainApp.status == .enabled
            }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Settings")
                .font(.system(size: 28, weight: .semibold, design: .rounded))
            Text("Manage sync, notifications, appearance, and your account in one place.")
                .font(.subheadline)
                .foregroundColor(.secondary)
        }
    }

    private var connectionCard: some View {
        GroupBox(label: Label("Connection", systemImage: "link")) {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 6) {
                    Circle()
                        .fill(appState.isPaired ? SyncFlowColors.success : SyncFlowColors.warning)
                        .frame(width: 8, height: 8)
                    Text(appState.isPaired ? "Connected" : "Not paired")
                        .font(.headline)
                }

                if let userId = appState.userId, !userId.isEmpty {
                    Text("User ID: \(userId)")
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .textSelection(.enabled)
                } else {
                    Text("Pair your phone to start syncing messages.")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }

                Button("Manage Connection") {
                    selection = .general
                }
                .buttonStyle(.link)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var securityCard: some View {
        GroupBox(label: Label("Security", systemImage: "lock.shield")) {
            VStack(alignment: .leading, spacing: 8) {
                Toggle("End-to-end encryption", isOn: $e2eeEnabled)
                Text("Keys sync automatically when devices are online.")
                    .font(.caption)
                    .foregroundColor(.secondary)

                Button("Security Settings") {
                    selection = .general
                }
                .buttonStyle(.link)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var notificationsCard: some View {
        GroupBox(label: Label("Notifications", systemImage: "bell.badge")) {
            VStack(alignment: .leading, spacing: 8) {
                Text(notificationsEnabled ? "Enabled" : "Disabled")
                    .font(.headline)
                Text(notificationSummary)
                    .font(.caption)
                    .foregroundColor(.secondary)

                Button("Notification Settings") {
                    selection = .notifications
                }
                .buttonStyle(.link)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var syncCard: some View {
        GroupBox(label: Label("Sync", systemImage: "arrow.triangle.2.circlepath")) {
            VStack(alignment: .leading, spacing: 8) {
                Text("Automatic sync")
                    .font(.headline)
                Text("Messages update in real time when your phone is online.")
                    .font(.caption)
                    .foregroundColor(.secondary)

                Button("Sync Settings") {
                    selection = .sync
                }
                .buttonStyle(.link)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var subscriptionCard: some View {
        GroupBox(label: Label("Subscription", systemImage: "creditcard")) {
            VStack(alignment: .leading, spacing: 8) {
                Text(subscriptionService.subscriptionStatus.displayText)
                    .font(.headline)
                Text(subscriptionService.subscriptionStatus.isActive ? "Active plan" : "Upgrade to unlock Pro features.")
                    .font(.caption)
                    .foregroundColor(.secondary)

                Button("Manage Subscription") {
                    selection = .subscription
                }
                .buttonStyle(.link)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var usageCard: some View {
        GroupBox(label: Label("Usage", systemImage: "chart.bar")) {
            VStack(alignment: .leading, spacing: 8) {
                Text("Storage & uploads")
                    .font(.headline)
                Text("Track monthly usage and manage cloud storage.")
                    .font(.caption)
                    .foregroundColor(.secondary)

                Button("View Usage") {
                    selection = .usage
                }
                .buttonStyle(.link)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var quickActions: some View {
        GroupBox(label: Label("Quick Actions", systemImage: "bolt")) {
            HStack(spacing: 16) {
                Toggle("Launch at login", isOn: $autoStart)
                    .toggleStyle(.switch)
                    .onChange(of: autoStart) { enabled in
                        if #available(macOS 13.0, *) {
                            do {
                                if enabled {
                                    try SMAppService.mainApp.register()
                                } else {
                                    try SMAppService.mainApp.unregister()
                                }
                            } catch {
                                autoStart.toggle()
                            }
                        } else {
                            autoStart = false
                        }
                    }

                Spacer()

                Button("Appearance") { selection = .appearance }
                Button("Support") { selection = .support }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var notificationSummary: String {
        if !notificationsEnabled {
            return "Notifications are turned off."
        }

        var parts: [String] = []
        parts.append(soundEnabled ? "Sound on" : "Sound off")
        parts.append(showPreviews ? "Previews on" : "Previews off")
        return parts.joined(separator: " · ")
    }
}

// MARK: - General Settings

/// General settings tab including connection status, startup options, chat themes, and account actions.
struct GeneralSettingsView: View {

    // MARK: - Environment

    @EnvironmentObject var appState: AppState

    // MARK: - Startup Settings

    /// Whether app launches at system login
    @AppStorage("auto_start") private var autoStart = false

    // MARK: - Alert State

    /// Whether unpair confirmation alert is shown
    @State private var showingUnpairAlert = false
    /// Whether delete account sheet is shown
    @State private var showingDeleteAccountSheet = false

    /// End-to-end encryption toggle (default on)
    @AppStorage("e2ee_enabled") private var e2eeEnabled = true

    // MARK: - Body

    var body: some View {
        Form {
            Section {
                if appState.isPaired {
                    LabeledContent("Status") {
                        HStack {
                            Circle()
                                .fill(SyncFlowColors.success)
                                .frame(width: 8, height: 8)
                            Text("Connected")
                                .foregroundColor(.secondary)
                        }
                    }

                    LabeledContent("User ID") {
                        Text(appState.userId ?? "Unknown")
                            .foregroundColor(.secondary)
                            .font(.caption)
                            .textSelection(.enabled)
                    }
                } else {
                    LabeledContent("Status") {
                        Text("Not paired")
                            .foregroundColor(.secondary)
                    }
                }
            } header: {
                Text("Connection")
            }

            Section {
                Toggle("Launch at login", isOn: $autoStart)
                    .onChange(of: autoStart) { newValue in
                        toggleLaunchAtLogin(newValue)
                    }
            } header: {
                Text("Startup")
            } footer: {
                Text("Automatically start SyncFlow when you log in to your Mac")
            }
            .onAppear {
                // Sync toggle state with actual system state
                if #available(macOS 13.0, *) {
                    autoStart = SMAppService.mainApp.status == .enabled
                }
            }

            Section {
                Toggle("End-to-end encryption", isOn: $e2eeEnabled)
                    .onChange(of: e2eeEnabled) { enabled in
                        if enabled {
                            Task {
                                try? await E2EEManager.shared.initializeKeys()
                            }
                        }
                    }
                Text("Encrypts message bodies and attachments between devices. Metadata (addresses and timestamps) is not encrypted.")
                    .font(.caption)
                    .foregroundColor(.secondary)
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "arrow.triangle.2.circlepath")
                        .foregroundColor(.secondary)
                    Text("Encryption keys sync automatically when your devices are online.")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }

            } header: {
                Text("Security")
            }

            Section {
                Button("Unpair Device", role: .destructive) {
                    showingUnpairAlert = true
                }
                .disabled(!appState.isPaired)

                // Only show Delete Account if user has paired (has an account)
                if appState.isPaired {
                    Divider()
                        .padding(.vertical, 8)

                    Button(action: { showingDeleteAccountSheet = true }) {
                        HStack {
                            Image(systemName: "trash.fill")
                            Text("Delete Account")
                        }
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                        .background(Color.red.opacity(0.8))
                        .cornerRadius(6)
                    }
                    .buttonStyle(.plain)

                    Text("Permanently delete your account and all data")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            } header: {
                Text("Actions")
            }

        }
        .formStyle(.grouped)
        .alert("Unpair this Mac?", isPresented: $showingUnpairAlert) {
            Button("Cancel", role: .cancel) {}
            Button("Unpair", role: .destructive) {
                appState.unpair()
            }
        } message: {
            Text("""
            After unpairing:
            • Messages will no longer sync to this Mac
            • Your Android device will still have all messages
            • You can re-pair this Mac later

            Local message history will be cleared.
            """)
        }
        .sheet(isPresented: $showingDeleteAccountSheet) {
            DeleteAccountView()
        }
    }

    // MARK: - Private Methods

    /// Toggles the "Launch at Login" setting using SMAppService.
    /// Requires macOS 13+; gracefully handles errors.
    /// - Parameter enabled: Whether to enable launch at login
    private func toggleLaunchAtLogin(_ enabled: Bool) {
        // Use SMAppService for macOS 13+
        if #available(macOS 13.0, *) {
            do {
                if enabled {
                    try SMAppService.mainApp.register()
                    print("[Settings] Launch at login enabled")
                } else {
                    try SMAppService.mainApp.unregister()
                    print("[Settings] Launch at login disabled")
                }
            } catch {
                print("[Settings] Failed to toggle launch at login: \(error)")
                // Revert the toggle on failure
                DispatchQueue.main.async {
                    self.autoStart = !enabled
                }
            }
        } else {
            // Fallback for older macOS versions - not supported
            print("[Settings] Launch at login requires macOS 13 or later")
            DispatchQueue.main.async {
                self.autoStart = false
            }
        }
    }

    /// Checks if app is currently set to launch at login.
    /// - Returns: True if launch at login is enabled
    static func isLaunchAtLoginEnabled() -> Bool {
        if #available(macOS 13.0, *) {
            return SMAppService.mainApp.status == .enabled
        }
        return false
    }
}

// MARK: - Appearance Settings

/// Appearance settings for chat themes, custom colors, and interface style.
struct AppearanceSettingsView: View {

    /// Current chat color theme preset
    @AppStorage("chat_color_theme") private var chatColorTheme = ChatColorTheme.apple.rawValue
    /// Whether to use macOS accent color for sent bubbles
    @AppStorage("chat_use_system_accent") private var chatUseSystemAccent = false
    /// Whether bubble gradients are enabled
    @AppStorage("chat_bubble_gradient_enabled") private var chatBubbleGradientEnabled = true
    /// Whether custom colors override theme
    @AppStorage("chat_custom_colors_enabled") private var chatCustomColorsEnabled = false
    /// Custom sent bubble color (hex)
    @AppStorage("chat_sent_custom_color") private var chatSentCustomColorHex = "#0A84FF"
    /// Custom received bubble color (hex)
    @AppStorage("chat_received_custom_color") private var chatReceivedCustomColorHex = "#2C2C2E"
    /// Custom received bubble text color (hex)
    @AppStorage("chat_received_text_color") private var chatReceivedTextColorHex = "#F8F8F8"
    /// Custom conversation window background color (hex)
    @AppStorage("conversation_window_color") private var conversationWindowColorHex = "#0F1119"
    /// Preferred appearance mode (auto, light, dark)
    @AppStorage("preferred_color_scheme") private var preferredColorScheme = "auto"
    /// Whether theme reset confirmation alert is shown
    @State private var showThemeResetAlert = false

    var body: some View {
        Form {
            Section {
                Picker("Appearance", selection: $preferredColorScheme) {
                    Text("System").tag("auto")
                    Text("Light").tag("light")
                    Text("Dark").tag("dark")
                }
            } header: {
                Text("Interface")
            } footer: {
                Text("Choose the appearance mode for the app interface.")
            }

            Section {
                Picker("Chat theme", selection: $chatColorTheme) {
                    ForEach(ChatColorTheme.allCases) { theme in
                        Text(theme.displayName).tag(theme.rawValue)
                    }
                }
                Toggle("Use macOS accent for sent bubbles", isOn: $chatUseSystemAccent)
                Toggle("Enable gradient tint on bubbles", isOn: $chatBubbleGradientEnabled)
            } header: {
                Text("Chat Theme")
            } footer: {
                Text("Customize your message bubble colors and gradients.")
            }

            Section {
                Toggle("Enable custom chat colors", isOn: $chatCustomColorsEnabled)

                if chatCustomColorsEnabled {
                    ColorPicker(
                        "Sent bubble color",
                        selection: colorBinding(for: $chatSentCustomColorHex)
                    )
                    ColorPicker(
                        "Received bubble color",
                        selection: colorBinding(for: $chatReceivedCustomColorHex)
                    )
                    ColorPicker(
                        "Received text color",
                        selection: colorBinding(for: $chatReceivedTextColorHex)
                    )
                }
            } header: {
                Text("Custom Colors")
            } footer: {
                Text("Pick your own sent/received bubble colors and text across macOS.")
            }

            Section {
                HStack {
                    ColorPicker(
                        "Conversation background",
                        selection: colorBinding(for: $conversationWindowColorHex)
                    )
                    if !conversationWindowColorHex.isEmpty {
                        Button(action: {
                            conversationWindowColorHex = ""
                            UserDefaults.standard.removeObject(forKey: "conversation_window_color")
                        }) {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundColor(.secondary)
                        }
                        .buttonStyle(.plain)
                        .help("Clear custom color")
                    }
                }

                Button(action: { showThemeResetAlert = true }) {
                    Label("Reset to Theme Default", systemImage: "arrow.clockwise")
                }
                .foregroundColor(SyncFlowColors.primary)
            } header: {
                Text("Conversation Window")
            } footer: {
                Text("Customize the conversation background. The app automatically adapts to light/dark themes.")
            }
        }
        .formStyle(.grouped)
        .alert("Reset Conversation Background?", isPresented: $showThemeResetAlert) {
            Button("Cancel", role: .cancel) {}
            Button("Reset", role: .destructive) {
                UserDefaults.standard.removeObject(forKey: "conversation_window_color")
                conversationWindowColorHex = ""
            }
        } message: {
            Text("This will reset the conversation background to match your current theme (light or dark). Your custom color will be lost.")
        }
    }

    /// Creates a binding that converts between Color and hex string storage.
    private func colorBinding(for hexStorage: Binding<String>) -> Binding<Color> {
        Binding(
            get: { Color(hex: hexStorage.wrappedValue) },
            set: { newColor in
                if let newHex = newColor.toHex() {
                    hexStorage.wrappedValue = newHex
                }
            }
        )
    }
}

// MARK: - Notification Settings

/// Notification settings tab for configuring alerts, sounds, and contact-specific sounds.
struct NotificationSettingsView: View {

    // MARK: - Bindings (from parent)

    /// Whether notifications are enabled globally
    @Binding var notificationsEnabled: Bool
    /// Whether notification sounds are enabled
    @Binding var soundEnabled: Bool
    /// Whether message previews appear in notifications
    @Binding var showPreviews: Bool

    // MARK: - Services & State

    /// Notification sound service for sound management
    @StateObject private var soundService = NotificationSoundService.shared
    /// Whether sound picker sheet is shown
    @State private var showSoundPicker = false

    // MARK: - Body

    var body: some View {
        Form {
            Section {
                Toggle("Enable notifications", isOn: $notificationsEnabled)
                Toggle("Play sound", isOn: $soundEnabled)
                    .disabled(!notificationsEnabled)
                Toggle("Show message previews", isOn: $showPreviews)
                    .disabled(!notificationsEnabled)
            } header: {
                Text("Notifications")
            } footer: {
                Text("Configure how you receive notifications for new messages")
            }

            Section {
                HStack {
                    Text("Default Sound")
                    Spacer()
                    Button(action: { showSoundPicker = true }) {
                        HStack {
                            Text(currentSoundName)
                                .foregroundColor(.secondary)
                            Image(systemName: "chevron.right")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                    .buttonStyle(.plain)
                }
                .disabled(!soundEnabled || !notificationsEnabled)

                Button(action: {
                    soundService.previewSound(id: soundService.defaultSoundId)
                }) {
                    HStack {
                        Image(systemName: "speaker.wave.2")
                        Text("Preview Sound")
                    }
                }
                .disabled(!soundEnabled || !notificationsEnabled)
            } header: {
                Text("Sound")
            } footer: {
                Text("You can also set custom sounds per contact from the conversation view")
            }

            Section {
                if !soundService.contactSounds.isEmpty {
                    ForEach(Array(soundService.contactSounds.keys.sorted()), id: \.self) { address in
                        HStack {
                            Text(address)
                            Spacer()
                            Text(soundService.getSound(for: address).name)
                                .foregroundColor(.secondary)
                            Button(action: {
                                soundService.removeSound(for: address)
                            }) {
                                Image(systemName: "xmark.circle.fill")
                                    .foregroundColor(.secondary)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                } else {
                    Text("No custom sounds set")
                        .foregroundColor(.secondary)
                }
            } header: {
                Text("Custom Contact Sounds")
            }

            Section {
                Button("Open System Preferences") {
                    if let url = URL(string: "x-apple.systempreferences:com.apple.preference.notifications") {
                        NSWorkspace.shared.open(url)
                    }
                }
            }
        }
        .formStyle(.grouped)
        .sheet(isPresented: $showSoundPicker) {
            NotificationSoundPicker(
                soundService: soundService,
                contactAddress: nil,
                contactName: nil
            )
        }
    }

    // MARK: - Computed Properties

    /// Display name for the currently selected default sound
    private var currentSoundName: String {
        NotificationSoundService.builtInSounds.first { $0.id == soundService.defaultSoundId }?.name ?? "Default"
    }
}

// MARK: - Subscription Settings

/// Subscription management tab showing current plan, pricing, and features.
struct SubscriptionSettingsView: View {

    // MARK: - Services & State

    /// Subscription service for status and purchases
    @StateObject private var subscriptionService = SubscriptionService.shared
    /// Whether paywall sheet is shown
    @State private var showPaywall = false

    // MARK: - Body

    var body: some View {
        Form {
            Section {
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Current Plan")
                            .font(.headline)
                        Text(subscriptionService.subscriptionStatus.displayText)
                            .foregroundColor(.secondary)
                    }

                    Spacer()

                    statusBadge
                }
            } header: {
                Text("Status")
            }

            Section {
                switch subscriptionService.subscriptionStatus {
                case .trial(let days):
                    HStack {
                        Image(systemName: "clock.fill")
                            .foregroundColor(.orange)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("\(days) days remaining in trial")
                                .font(.subheadline)
                            Text("Subscribe to continue using SyncFlow after your trial ends")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }

                    Button("Upgrade to Pro") {
                        showPaywall = true
                    }
                    .buttonStyle(.borderedProminent)

                case .expired:
                    HStack {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundColor(.red)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Your trial has expired")
                                .font(.subheadline)
                            Text("Subscribe to continue using SyncFlow")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }

                    Button("Subscribe Now") {
                        showPaywall = true
                    }
                    .buttonStyle(.borderedProminent)

                case .notSubscribed:
                    HStack {
                        Image(systemName: "sparkles")
                            .foregroundColor(.blue)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Start your free 7-day trial")
                                .font(.subheadline)
                            Text("Try all Pro features for free")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }

                    Button("Start Free Trial") {
                        subscriptionService.startTrialIfNeeded()
                    }
                    .buttonStyle(.borderedProminent)

                case .subscribed(let plan, let expires):
                    HStack {
                        Image(systemName: "checkmark.seal.fill")
                            .foregroundColor(.green)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("You're subscribed to \(plan)")
                                .font(.subheadline)
                            if let expires = expires {
                                Text("Renews on \(expires.formatted(date: .abbreviated, time: .omitted))")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                        }
                    }

                case .threeYear(let expires):
                    HStack {
                        Image(systemName: "star.fill")
                            .foregroundColor(.yellow)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("3-Year Plan")
                                .font(.subheadline)
                            if let expires = expires {
                                Text("Expires on \(expires.formatted(date: .abbreviated, time: .omitted))")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            } else {
                                Text("Thank you for your support!")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                        }
                    }
                }
            } header: {
                Text("Plan Details")
            }

            Section {
                Button("Restore Purchases") {
                    Task {
                        await subscriptionService.restorePurchases()
                    }
                }
                .disabled(subscriptionService.isLoading)

                Button("Manage Subscription") {
                    if let url = URL(string: "macappstores://apps.apple.com/account/subscriptions") {
                        NSWorkspace.shared.open(url)
                    }
                }
            } header: {
                Text("Account")
            } footer: {
                Text("Manage your subscription in the App Store")
            }

            Section {
                VStack(alignment: .leading, spacing: 8) {
                    pricingRow("Monthly", price: "$4.99/month")
                    pricingRow("Yearly", price: "$39.99/year", badge: "Save 33%")
                    pricingRow("3-Year", price: "$79.99", badge: "Best Value")
                }
            } header: {
                Text("Pricing")
            }

            Section {
                VStack(alignment: .leading, spacing: 8) {
                    featureRow("Unlimited SMS & MMS")
                    featureRow("Phone calls from Mac")
                    featureRow("Photo sync (Premium)")
                    featureRow("3GB uploads/month")
                    featureRow("500MB cloud storage")
                    featureRow("End-to-end encryption")
                    featureRow("Priority support")
                }
            } header: {
                Text("Pro Features")
            }
        }
        .formStyle(.grouped)
        .sheet(isPresented: $showPaywall) {
            PaywallView()
        }
    }

    // MARK: - Subviews

    /// Badge showing current subscription status (TRIAL, PRO, EXPIRED, FREE)
    @ViewBuilder
    private var statusBadge: some View {
        switch subscriptionService.subscriptionStatus {
        case .trial:
            Text("TRIAL")
                .font(.caption)
                .fontWeight(.semibold)
                .foregroundColor(.white)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(Color.orange)
                .cornerRadius(4)
        case .subscribed, .threeYear:
            Text("PRO")
                .font(.caption)
                .fontWeight(.semibold)
                .foregroundColor(.white)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(Color.green)
                .cornerRadius(4)
        case .expired:
            Text("EXPIRED")
                .font(.caption)
                .fontWeight(.semibold)
                .foregroundColor(.white)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(Color.red)
                .cornerRadius(4)
        case .notSubscribed:
            Text("FREE")
                .font(.caption)
                .fontWeight(.semibold)
                .foregroundColor(.white)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(Color.gray)
                .cornerRadius(4)
        }
    }

    // MARK: - Helper Views

    /// Row displaying a pricing tier with optional badge
    private func pricingRow(_ name: String, price: String, badge: String? = nil) -> some View {
        HStack {
            Text(name)
            Spacer()
            if let badge = badge {
                Text(badge)
                    .font(.caption2)
                    .foregroundColor(.green)
            }
            Text(price)
                .foregroundColor(.secondary)
        }
        .font(.subheadline)
    }

    /// Row displaying a feature with checkmark
    private func featureRow(_ feature: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "checkmark.circle.fill")
                .foregroundColor(.green)
                .font(.caption)
            Text(feature)
                .font(.subheadline)
        }
    }
}

// MARK: - Sync Settings View

/// Sync settings tab with instructions for loading older messages from Android.
struct SyncSettingsView: View {

    // MARK: - Environment

    @EnvironmentObject var appState: AppState

    // MARK: - Body

    var body: some View {
        Form {
            Section {
                VStack(alignment: .leading, spacing: 12) {
                    HStack {
                        Image(systemName: "iphone.and.arrow.forward")
                            .font(.title)
                            .foregroundColor(.blue)
                        Text("Sync from Android")
                            .font(.headline)
                    }

                    Text("To load older messages, open SyncFlow on your Android phone:")
                        .font(.callout)
                        .foregroundColor(.secondary)

                    VStack(alignment: .leading, spacing: 8) {
                        Label("Open SyncFlow app on your phone", systemImage: "1.circle.fill")
                        Label("Go to Settings", systemImage: "2.circle.fill")
                        Label("Tap \"Sync Message History\"", systemImage: "3.circle.fill")
                        Label("Select the time range and tap Sync", systemImage: "4.circle.fill")
                    }
                    .font(.callout)
                    .padding(.vertical, 8)

                    Text("Messages will automatically appear here once synced.")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                .padding(.vertical, 8)
            } header: {
                Text("Load Older Messages")
            }

            Section {
                HStack {
                    Text("Default sync range")
                    Spacer()
                    Text("Last 30 days")
                        .foregroundColor(.secondary)
                }

                HStack {
                    Text("Connection status")
                    Spacer()
                    if appState.isPaired {
                        HStack(spacing: 4) {
                            Circle()
                                .fill(Color.green)
                                .frame(width: 8, height: 8)
                            Text("Connected")
                                .foregroundColor(.secondary)
                        }
                    } else {
                        HStack(spacing: 4) {
                            Circle()
                                .fill(Color.red)
                                .frame(width: 8, height: 8)
                            Text("Not paired")
                                .foregroundColor(.secondary)
                        }
                    }
                }
            } header: {
                Text("Status")
            } footer: {
                Text("New messages sync automatically when your Android phone is connected to the internet.")
            }
        }
        .formStyle(.grouped)
    }
}

// MARK: - Support Settings View

/// Support settings tab with AI assistant chat and external support links.
struct SupportSettingsView: View {

    // MARK: - State

    /// Whether AI support chat sheet is shown
    @State private var showSupportChat = false

    // MARK: - Body

    var body: some View {
        Form {
            Section {
                VStack(alignment: .leading, spacing: 12) {
                    HStack {
                        Image(systemName: "sparkles")
                            .font(.title)
                            .foregroundColor(.blue)
                        Text("AI Support Assistant")
                            .font(.headline)
                    }

                    Text("Get instant help with SyncFlow. Our AI assistant can answer questions about pairing, syncing, features, and troubleshooting.")
                        .font(.callout)
                        .foregroundColor(.secondary)

                    Button("Open AI Support Chat") {
                        showSupportChat = true
                    }
                    .buttonStyle(.borderedProminent)
                }
                .padding(.vertical, 8)
            } header: {
                Text("Get Help")
            }

            Section {
                Link(destination: URL(string: "mailto:syncflow.contact@gmail.com")!) {
                    HStack {
                        Image(systemName: "envelope")
                        Text("Email Support")
                        Spacer()
                        Image(systemName: "arrow.up.right.square")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }

                Link(destination: URL(string: "https://github.com/dpchavali1/SyncFlow/issues")!) {
                    HStack {
                        Image(systemName: "ladybug")
                        Text("Report an Issue")
                        Spacer()
                        Image(systemName: "arrow.up.right.square")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
            } header: {
                Text("Contact")
            } footer: {
                Text("For complex issues, please email us or report an issue on GitHub")
            }

            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Common Topics")
                        .font(.subheadline)
                        .foregroundColor(.secondary)

                    ForEach(["Device pairing", "Message syncing", "Subscription & billing", "Privacy & security"], id: \.self) { topic in
                        HStack {
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundColor(.green)
                                .font(.caption)
                            Text(topic)
                                .font(.subheadline)
                        }
                    }
                }
            } header: {
                Text("AI Can Help With")
            }
        }
        .formStyle(.grouped)
        .sheet(isPresented: $showSupportChat) {
            SupportChatView()
        }
    }
}

// MARK: - About View

/// About tab showing app info, version, and links.
struct AboutView: View {
    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "message.and.waveform.fill")
                .font(.system(size: 60))
                .foregroundColor(.blue)

            Text("SyncFlow for macOS")
                .font(.title)
                .fontWeight(.bold)

            Text("Version 1.0.0")
                .foregroundColor(.secondary)

            Divider()
                .padding(.horizontal, 40)

            VStack(spacing: 10) {
                Text("Access your Android SMS messages on your Mac")
                    .multilineTextAlignment(.center)

                Text("Built with SwiftUI")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            Spacer()

            VStack(spacing: 8) {
                Link("GitHub", destination: URL(string: "https://github.com/dpchavali1/SyncFlow")!)
                Link("Report an Issue", destination: URL(string: "https://github.com/dpchavali1/SyncFlow/issues")!)
            }
            .font(.caption)
        }
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
