/**
 * SyncFlow macOS App - SwiftUI Views
 *
 * Complete SwiftUI app using VPS backend only.
 */

import SwiftUI
import Combine

// MARK: - Main App View

struct SyncFlowMainView: View {
    @StateObject private var service = SyncFlowService.shared
    @State private var selectedTab = 0

    var body: some View {
        Group {
            if service.state.isAuthenticated {
                NavigationView {
                    Sidebar(selectedTab: $selectedTab)

                    switch selectedTab {
                    case 0:
                        MessagesView(service: service)
                    case 1:
                        ContactsView(service: service)
                    case 2:
                        CallHistoryView(service: service)
                    case 3:
                        DevicesView(service: service)
                    default:
                        MessagesView(service: service)
                    }
                }
                .frame(minWidth: 800, minHeight: 600)
            } else {
                AuthView(service: service)
            }
        }
    }
}

// MARK: - Sidebar

struct Sidebar: View {
    @Binding var selectedTab: Int

    var body: some View {
        List {
            Label("Messages", systemImage: "message")
                .tag(0)
                .onTapGesture { selectedTab = 0 }
                .listRowBackground(selectedTab == 0 ? Color.accentColor.opacity(0.2) : nil)

            Label("Contacts", systemImage: "person.2")
                .tag(1)
                .onTapGesture { selectedTab = 1 }
                .listRowBackground(selectedTab == 1 ? Color.accentColor.opacity(0.2) : nil)

            Label("Calls", systemImage: "phone")
                .tag(2)
                .onTapGesture { selectedTab = 2 }
                .listRowBackground(selectedTab == 2 ? Color.accentColor.opacity(0.2) : nil)

            Label("Devices", systemImage: "laptopcomputer.and.iphone")
                .tag(3)
                .onTapGesture { selectedTab = 3 }
                .listRowBackground(selectedTab == 3 ? Color.accentColor.opacity(0.2) : nil)
        }
        .listStyle(SidebarListStyle())
        .frame(minWidth: 200)
    }
}

// MARK: - Auth View

struct AuthView: View {
    @ObservedObject var service: SyncFlowService
    @State private var pairingToken: String?
    @State private var isLoading = false
    @State private var error: String?
    @State private var deviceName = Host.current().localizedName ?? "Mac"
    @State private var checkPairingTask: Task<Void, Never>?

    var body: some View {
        VStack(spacing: 32) {
            Spacer()

            Image(systemName: "arrow.triangle.2.circlepath")
                .font(.system(size: 80))
                .foregroundColor(.accentColor)

            Text("SyncFlow VPS")
                .font(.largeTitle)
                .fontWeight(.bold)

            Text("Connect your devices securely")
                .font(.title3)
                .foregroundColor(.secondary)

            if let token = pairingToken {
                VStack(spacing: 16) {
                    Text("Enter this code on your Android device:")
                        .font(.headline)

                    Text(token)
                        .font(.system(size: 32, weight: .bold, design: .monospaced))
                        .padding()
                        .background(Color.secondary.opacity(0.2))
                        .cornerRadius(8)

                    Text("Waiting for approval...")
                        .font(.caption)
                        .foregroundColor(.secondary)

                    Button("Cancel") {
                        cancelPairing()
                    }
                    .buttonStyle(.plain)
                    .foregroundColor(.red)
                }
                .padding()
            } else {
                VStack(spacing: 16) {
                    TextField("Device Name", text: $deviceName)
                        .textFieldStyle(.roundedBorder)
                        .frame(width: 250)

                    Button(action: startPairing) {
                        if isLoading {
                            ProgressView()
                                .scaleEffect(0.7)
                        } else {
                            Text("Start Pairing")
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(isLoading || deviceName.isEmpty)
                }
            }

            if let error = error {
                Text(error)
                    .font(.caption)
                    .foregroundColor(.red)
            }

            Spacer()
        }
        .frame(width: 400, height: 500)
        .onDisappear {
            checkPairingTask?.cancel()
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
            } catch {
                await MainActor.run {
                    self.error = error.localizedDescription
                    isLoading = false
                }
            }
        }
    }

    private func startPolling(token: String) {
        checkPairingTask = Task {
            while !Task.isCancelled {
                do {
                    try await Task.sleep(nanoseconds: 2_000_000_000) // 2 seconds
                    let approved = try await service.checkPairingStatus(token: token)
                    if approved {
                        _ = try await service.redeemPairing(token: token)
                        break
                    }
                } catch {
                    await MainActor.run {
                        self.error = error.localizedDescription
                    }
                    break
                }
            }
        }
    }

    private func cancelPairing() {
        checkPairingTask?.cancel()
        pairingToken = nil
        error = nil
    }
}

// MARK: - Messages View

struct MessagesView: View {
    @ObservedObject var service: SyncFlowService
    @State private var isLoading = false
    @State private var selectedMessage: Message?

    var body: some View {
        HSplitView {
            // Message list
            VStack(spacing: 0) {
                // Connection status header
                HStack {
                    Text("Messages")
                        .font(.headline)
                    Spacer()
                    ConnectionIndicator(isConnected: service.state.isConnected)
                }
                .padding()

                Divider()

                if service.state.messages.isEmpty {
                    Spacer()
                    Text("No messages")
                        .foregroundColor(.secondary)
                    Spacer()
                } else {
                    List(service.state.messages, id: \.id, selection: $selectedMessage) { message in
                        MessageRow(message: message)
                    }
                }
            }
            .frame(minWidth: 300)

            // Message detail
            if let message = selectedMessage {
                MessageDetailView(message: message)
            } else {
                Text("Select a message")
                    .foregroundColor(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .onAppear {
            loadMessages()
        }
        .toolbar {
            ToolbarItem {
                Button(action: loadMessages) {
                    Image(systemName: "arrow.clockwise")
                }
                .disabled(isLoading)
            }
        }
    }

    private func loadMessages() {
        isLoading = true
        Task {
            _ = try? await service.loadMessages()
            await MainActor.run {
                isLoading = false
            }
        }
    }
}

struct MessageRow: View {
    let message: Message

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(message.contactName ?? message.address)
                    .fontWeight(message.read ? .regular : .bold)
                Spacer()
                Text(formatDate(message.date))
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            Text(message.body)
                .font(.subheadline)
                .foregroundColor(.secondary)
                .lineLimit(2)
        }
        .padding(.vertical, 4)
    }

    private func formatDate(_ timestamp: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(timestamp) / 1000)
        let formatter = DateFormatter()
        formatter.dateStyle = .short
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }
}

struct MessageDetailView: View {
    let message: Message

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                // Header
                HStack {
                    VStack(alignment: .leading) {
                        Text(message.contactName ?? message.address)
                            .font(.title2)
                            .fontWeight(.bold)
                        Text(message.address)
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                    }
                    Spacer()
                    Text(message.type == 1 ? "Received" : "Sent")
                        .font(.caption)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(message.type == 1 ? Color.blue.opacity(0.2) : Color.green.opacity(0.2))
                        .cornerRadius(4)
                }

                Divider()

                // Body
                Text(message.body)
                    .textSelection(.enabled)

                // Metadata
                HStack {
                    Text(formatFullDate(message.date))
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Spacer()
                    if message.isMms {
                        Label("MMS", systemImage: "photo")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    if message.encrypted {
                        Label("Encrypted", systemImage: "lock.fill")
                            .font(.caption)
                            .foregroundColor(.green)
                    }
                }
            }
            .padding()
        }
    }

    private func formatFullDate(_ timestamp: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(timestamp) / 1000)
        let formatter = DateFormatter()
        formatter.dateStyle = .full
        formatter.timeStyle = .medium
        return formatter.string(from: date)
    }
}

// MARK: - Contacts View

struct ContactsView: View {
    @ObservedObject var service: SyncFlowService
    @State private var searchText = ""
    @State private var isLoading = false

    var filteredContacts: [Contact] {
        if searchText.isEmpty {
            return service.state.contacts
        }
        return service.state.contacts.filter { contact in
            contact.displayName.localizedCaseInsensitiveContains(searchText) ||
            contact.phoneNumbers.contains { $0.contains(searchText) }
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            // Search bar
            HStack {
                Image(systemName: "magnifyingglass")
                    .foregroundColor(.secondary)
                TextField("Search contacts", text: $searchText)
                    .textFieldStyle(.plain)
                if !searchText.isEmpty {
                    Button(action: { searchText = "" }) {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundColor(.secondary)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(8)
            .background(Color.secondary.opacity(0.1))
            .cornerRadius(8)
            .padding()

            Divider()

            if filteredContacts.isEmpty {
                Spacer()
                Text("No contacts found")
                    .foregroundColor(.secondary)
                Spacer()
            } else {
                List(filteredContacts, id: \.id) { contact in
                    ContactRow(contact: contact)
                }
            }
        }
        .onAppear {
            loadContacts()
        }
        .toolbar {
            ToolbarItem {
                Button(action: loadContacts) {
                    Image(systemName: "arrow.clockwise")
                }
                .disabled(isLoading)
            }
        }
    }

    private func loadContacts() {
        isLoading = true
        Task {
            _ = try? await service.loadContacts()
            await MainActor.run {
                isLoading = false
            }
        }
    }
}

struct ContactRow: View {
    let contact: Contact

    var body: some View {
        HStack {
            Circle()
                .fill(Color.accentColor.opacity(0.2))
                .frame(width: 40, height: 40)
                .overlay(
                    Text(contact.displayName.prefix(1).uppercased())
                        .font(.headline)
                        .foregroundColor(.accentColor)
                )

            VStack(alignment: .leading) {
                Text(contact.displayName)
                    .fontWeight(.medium)
                if let phone = contact.phoneNumbers.first {
                    Text(phone)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
        }
    }
}

// MARK: - Call History View

struct CallHistoryView: View {
    @ObservedObject var service: SyncFlowService
    @State private var isLoading = false

    var body: some View {
        VStack {
            if service.state.calls.isEmpty {
                Spacer()
                Text("No call history")
                    .foregroundColor(.secondary)
                Spacer()
            } else {
                List(service.state.calls, id: \.id) { call in
                    CallRow(call: call)
                }
            }
        }
        .onAppear {
            loadCalls()
        }
        .toolbar {
            ToolbarItem {
                Button(action: loadCalls) {
                    Image(systemName: "arrow.clockwise")
                }
                .disabled(isLoading)
            }
        }
    }

    private func loadCalls() {
        isLoading = true
        Task {
            _ = try? await service.loadCallHistory()
            await MainActor.run {
                isLoading = false
            }
        }
    }
}

struct CallRow: View {
    let call: CallHistoryEntry

    var body: some View {
        HStack {
            Image(systemName: callIcon)
                .foregroundColor(callColor)
                .frame(width: 24)

            VStack(alignment: .leading) {
                Text(call.contactName ?? call.phoneNumber)
                    .fontWeight(.medium)
                Text(formatDate(call.callDate))
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            Spacer()

            Text(formatDuration(call.duration))
                .font(.caption)
                .foregroundColor(.secondary)
        }
    }

    private var callIcon: String {
        switch call.callType {
        case 1: return "phone.arrow.down.left"
        case 2: return "phone.arrow.up.right"
        case 3: return "phone.arrow.down.left"
        default: return "phone"
        }
    }

    private var callColor: Color {
        call.callType == 3 ? .red : .primary
    }

    private func formatDate(_ timestamp: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(timestamp) / 1000)
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(for: date, relativeTo: Date())
    }

    private func formatDuration(_ seconds: Int) -> String {
        let mins = seconds / 60
        let secs = seconds % 60
        if mins > 0 {
            return "\(mins)m \(secs)s"
        }
        return "\(secs)s"
    }
}

// MARK: - Devices View

struct DevicesView: View {
    @ObservedObject var service: SyncFlowService
    @State private var isLoading = false

    var body: some View {
        VStack {
            if service.state.devices.isEmpty {
                Spacer()
                Text("No paired devices")
                    .foregroundColor(.secondary)
                Spacer()
            } else {
                List(service.state.devices, id: \.id) { device in
                    DeviceRow(
                        device: device,
                        isCurrentDevice: device.id == service.state.deviceId
                    )
                }
            }
        }
        .onAppear {
            loadDevices()
        }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button("Sign Out") {
                    service.logout()
                }
            }
            ToolbarItem {
                Button(action: loadDevices) {
                    Image(systemName: "arrow.clockwise")
                }
                .disabled(isLoading)
            }
        }
    }

    private func loadDevices() {
        isLoading = true
        Task {
            _ = try? await service.loadDevices()
            await MainActor.run {
                isLoading = false
            }
        }
    }
}

struct DeviceRow: View {
    let device: Device
    let isCurrentDevice: Bool

    var body: some View {
        HStack {
            Image(systemName: deviceIcon)
                .font(.title2)
                .frame(width: 40)

            VStack(alignment: .leading) {
                Text(device.deviceName)
                    .fontWeight(.medium)
                Text(device.deviceType.capitalized)
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            Spacer()

            if isCurrentDevice {
                Text("This device")
                    .font(.caption)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(Color.accentColor.opacity(0.2))
                    .cornerRadius(4)
            }
        }
    }

    private var deviceIcon: String {
        switch device.deviceType.lowercased() {
        case "android": return "candybarphone"
        case "ios", "iphone": return "iphone"
        case "macos", "mac": return "laptopcomputer"
        case "web": return "globe"
        default: return "desktopcomputer"
        }
    }
}

// MARK: - Helper Views

struct ConnectionIndicator: View {
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

// MARK: - Preview

#if DEBUG
struct SyncFlowMainView_Previews: PreviewProvider {
    static var previews: some View {
        SyncFlowMainView()
    }
}
#endif
