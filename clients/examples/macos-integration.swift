/**
 * Example: Integrating SyncFlow VPS into a macOS App
 *
 * This shows how to replace Firebase with VPS backend.
 */

import SwiftUI
import Combine
import UserNotifications

// ==================== App Entry Point ====================

@main
struct SyncFlowMacApp: App {
    @StateObject private var appState = AppState()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(appState)
        }
        .commands {
            SidebarCommands()
        }

        Settings {
            SettingsView()
                .environmentObject(appState)
        }
    }

    init() {
        // Request notification permissions
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }
}

// ==================== App State ====================

class AppState: ObservableObject {
    let service = SyncFlowService.shared

    @Published var selectedConversation: String?
    @Published var showSettings = false

    private var cancellables = Set<AnyCancellable>()

    init() {
        // Listen for new messages and show notifications
        service.messageReceived
            .sink { [weak self] message in
                self?.showNotification(for: message)
            }
            .store(in: &cancellables)

        // Initialize service
        Task {
            await service.initialize()
        }
    }

    private func showNotification(for message: Message) {
        let content = UNMutableNotificationContent()
        content.title = message.contactName ?? message.address
        content.body = message.body
        content.sound = .default

        let request = UNNotificationRequest(
            identifier: message.id,
            content: content,
            trigger: nil
        )

        UNUserNotificationCenter.current().add(request)
    }
}

// ==================== Main Content View ====================

struct ContentView: View {
    @EnvironmentObject var appState: AppState
    @ObservedObject var service = SyncFlowService.shared

    var body: some View {
        Group {
            if service.state.isAuthenticated {
                NavigationView {
                    SidebarView()
                    DetailView()
                }
                .frame(minWidth: 900, minHeight: 600)
            } else {
                OnboardingView()
            }
        }
    }
}

// ==================== Onboarding / Pairing View ====================

struct OnboardingView: View {
    @ObservedObject var service = SyncFlowService.shared
    @State private var deviceName = Host.current().localizedName ?? "Mac"
    @State private var pairingToken: String?
    @State private var isLoading = false
    @State private var error: String?
    @State private var pollingTask: Task<Void, Never>?

    var body: some View {
        VStack(spacing: 32) {
            Spacer()

            // App icon
            Image(systemName: "arrow.triangle.2.circlepath")
                .font(.system(size: 80))
                .foregroundColor(.accentColor)

            Text("SyncFlow")
                .font(.largeTitle)
                .fontWeight(.bold)

            Text("Sync your phone messages to your Mac")
                .font(.title3)
                .foregroundColor(.secondary)

            Spacer()

            if let token = pairingToken {
                // Show pairing code
                VStack(spacing: 16) {
                    Text("Enter this code on your Android phone:")
                        .font(.headline)

                    Text(token)
                        .font(.system(size: 48, weight: .bold, design: .monospaced))
                        .padding()
                        .background(Color.secondary.opacity(0.2))
                        .cornerRadius(12)

                    ProgressView()
                        .progressViewStyle(.circular)

                    Text("Waiting for approval...")
                        .foregroundColor(.secondary)

                    Button("Cancel") {
                        cancelPairing()
                    }
                    .buttonStyle(.plain)
                    .foregroundColor(.red)
                }
            } else {
                // Pairing form
                VStack(spacing: 16) {
                    TextField("Device Name", text: $deviceName)
                        .textFieldStyle(.roundedBorder)
                        .frame(width: 300)

                    Button(action: startPairing) {
                        if isLoading {
                            ProgressView()
                                .progressViewStyle(.circular)
                                .scaleEffect(0.7)
                        } else {
                            Text("Connect to Android Phone")
                                .frame(width: 200)
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .disabled(isLoading || deviceName.isEmpty)
                }
            }

            if let error = error {
                Text(error)
                    .foregroundColor(.red)
                    .font(.caption)
            }

            Spacer()
        }
        .frame(width: 500, height: 600)
        .onDisappear {
            pollingTask?.cancel()
        }
    }

    private func startPairing() {
        isLoading = true
        error = nil

        Task {
            do {
                let token = try await service.initiatePairing(deviceName: deviceName)
                await MainActor.run {
                    pairingToken = token
                    isLoading = false
                }
                startPolling(token: token)
            } catch let e {
                await MainActor.run {
                    error = e.localizedDescription
                    isLoading = false
                }
            }
        }
    }

    private func startPolling(token: String) {
        pollingTask = Task {
            while !Task.isCancelled {
                do {
                    try await Task.sleep(nanoseconds: 2_000_000_000)
                    let approved = try await service.checkPairingStatus(token: token)
                    if approved {
                        _ = try await service.redeemPairing(token: token)
                        break
                    }
                } catch {
                    await MainActor.run {
                        self.error = error.localizedDescription
                        pairingToken = nil
                    }
                    break
                }
            }
        }
    }

    private func cancelPairing() {
        pollingTask?.cancel()
        pairingToken = nil
    }
}

// ==================== Sidebar View ====================

struct SidebarView: View {
    @EnvironmentObject var appState: AppState
    @ObservedObject var service = SyncFlowService.shared

    // Group messages by conversation
    var conversations: [Conversation] {
        let grouped = Dictionary(grouping: service.state.messages) { $0.address }
        return grouped.map { address, messages in
            let sorted = messages.sorted { $0.date > $1.date }
            return Conversation(
                address: address,
                contactName: sorted.first?.contactName,
                lastMessage: sorted.first!,
                unreadCount: sorted.filter { !$0.read }.count
            )
        }
        .sorted { $0.lastMessage.date > $1.lastMessage.date }
    }

    var body: some View {
        List(selection: $appState.selectedConversation) {
            Section("Messages") {
                ForEach(conversations, id: \.address) { conversation in
                    ConversationRow(conversation: conversation)
                        .tag(conversation.address)
                }
            }
        }
        .listStyle(.sidebar)
        .frame(minWidth: 250)
        .toolbar {
            ToolbarItem(placement: .navigation) {
                Button(action: {
                    NSApp.keyWindow?.firstResponder?.tryToPerform(
                        #selector(NSSplitViewController.toggleSidebar(_:)),
                        with: nil
                    )
                }) {
                    Image(systemName: "sidebar.left")
                }
            }
        }
        .onAppear {
            Task {
                _ = try? await service.loadMessages()
            }
        }
    }
}

struct Conversation: Identifiable {
    let address: String
    let contactName: String?
    let lastMessage: Message
    let unreadCount: Int

    var id: String { address }
    var displayName: String { contactName ?? address }
}

struct ConversationRow: View {
    let conversation: Conversation

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(conversation.displayName)
                        .fontWeight(conversation.unreadCount > 0 ? .bold : .regular)

                    Spacer()

                    Text(formatDate(conversation.lastMessage.date))
                        .font(.caption)
                        .foregroundColor(.secondary)
                }

                Text(conversation.lastMessage.body)
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .lineLimit(1)
            }

            if conversation.unreadCount > 0 {
                Text("\(conversation.unreadCount)")
                    .font(.caption2)
                    .foregroundColor(.white)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(Color.accentColor)
                    .clipShape(Capsule())
            }
        }
        .padding(.vertical, 4)
    }

    private func formatDate(_ timestamp: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(timestamp) / 1000)
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(for: date, relativeTo: Date())
    }
}

// ==================== Detail View ====================

struct DetailView: View {
    @EnvironmentObject var appState: AppState
    @ObservedObject var service = SyncFlowService.shared
    @State private var messageText = ""

    var messages: [Message] {
        guard let address = appState.selectedConversation else { return [] }
        return service.state.messages
            .filter { $0.address == address }
            .sorted { $0.date < $1.date }
    }

    var contactName: String {
        messages.first?.contactName ?? appState.selectedConversation ?? ""
    }

    var body: some View {
        VStack(spacing: 0) {
            if appState.selectedConversation != nil {
                // Header
                HStack {
                    VStack(alignment: .leading) {
                        Text(contactName)
                            .font(.headline)
                        Text(appState.selectedConversation ?? "")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    Spacer()
                    ConnectionStatus(isConnected: service.state.isConnected)
                }
                .padding()
                .background(Color(NSColor.controlBackgroundColor))

                Divider()

                // Messages
                ScrollView {
                    ScrollViewReader { proxy in
                        LazyVStack(spacing: 8) {
                            ForEach(messages, id: \.id) { message in
                                MessageBubble(message: message)
                            }
                        }
                        .padding()
                        .onChange(of: messages.count) { _ in
                            if let lastId = messages.last?.id {
                                withAnimation {
                                    proxy.scrollTo(lastId, anchor: .bottom)
                                }
                            }
                        }
                    }
                }

                Divider()

                // Input
                HStack {
                    TextField("Message", text: $messageText)
                        .textFieldStyle(.roundedBorder)
                        .onSubmit {
                            sendMessage()
                        }

                    Button("Send") {
                        sendMessage()
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(messageText.isEmpty)
                }
                .padding()
            } else {
                Text("Select a conversation")
                    .foregroundColor(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .frame(minWidth: 400)
    }

    private func sendMessage() {
        guard let address = appState.selectedConversation,
              !messageText.isEmpty else { return }

        let text = messageText
        messageText = ""

        Task {
            try? await service.sendMessage(address: address, body: text)
        }
    }
}

struct MessageBubble: View {
    let message: Message

    var isSent: Bool { message.type == 2 }

    var body: some View {
        HStack {
            if isSent { Spacer() }

            VStack(alignment: isSent ? .trailing : .leading, spacing: 4) {
                Text(message.body)
                    .padding(10)
                    .background(isSent ? Color.accentColor : Color.secondary.opacity(0.2))
                    .foregroundColor(isSent ? .white : .primary)
                    .cornerRadius(12)

                Text(formatTime(message.date))
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }

            if !isSent { Spacer() }
        }
        .id(message.id)
    }

    private func formatTime(_ timestamp: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(timestamp) / 1000)
        let formatter = DateFormatter()
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }
}

struct ConnectionStatus: View {
    let isConnected: Bool

    var body: some View {
        HStack(spacing: 4) {
            Circle()
                .fill(isConnected ? Color.green : Color.red)
                .frame(width: 8, height: 8)
            Text(isConnected ? "Connected" : "Disconnected")
                .font(.caption)
                .foregroundColor(.secondary)
        }
    }
}

// ==================== Settings View ====================

struct SettingsView: View {
    @EnvironmentObject var appState: AppState
    @ObservedObject var service = SyncFlowService.shared

    var body: some View {
        TabView {
            AccountSettings()
                .tabItem {
                    Label("Account", systemImage: "person")
                }

            DeviceSettings()
                .tabItem {
                    Label("Devices", systemImage: "laptopcomputer.and.iphone")
                }
        }
        .frame(width: 450, height: 300)
    }
}

struct AccountSettings: View {
    @ObservedObject var service = SyncFlowService.shared

    var body: some View {
        Form {
            Section {
                LabeledContent("User ID") {
                    Text(service.state.userId ?? "Not signed in")
                        .foregroundColor(.secondary)
                }

                LabeledContent("Device ID") {
                    Text(service.state.deviceId ?? "Not registered")
                        .foregroundColor(.secondary)
                }
            }

            Section {
                Button("Sign Out", role: .destructive) {
                    service.logout()
                }
            }
        }
        .formStyle(.grouped)
        .padding()
    }
}

struct DeviceSettings: View {
    @ObservedObject var service = SyncFlowService.shared
    @State private var isLoading = false

    var body: some View {
        VStack {
            if service.state.devices.isEmpty {
                Text("No devices paired")
                    .foregroundColor(.secondary)
            } else {
                List(service.state.devices, id: \.id) { device in
                    HStack {
                        Image(systemName: deviceIcon(for: device.deviceType))
                        VStack(alignment: .leading) {
                            Text(device.deviceName)
                            Text(device.deviceType)
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                        Spacer()
                        if device.id == service.state.deviceId {
                            Text("This device")
                                .font(.caption)
                                .foregroundColor(.accentColor)
                        }
                    }
                }
            }
        }
        .padding()
        .onAppear {
            Task {
                isLoading = true
                _ = try? await service.loadDevices()
                isLoading = false
            }
        }
    }

    private func deviceIcon(for type: String) -> String {
        switch type.lowercased() {
        case "android": return "candybarphone"
        case "ios", "iphone": return "iphone"
        case "macos", "mac": return "laptopcomputer"
        case "web": return "globe"
        default: return "desktopcomputer"
        }
    }
}
