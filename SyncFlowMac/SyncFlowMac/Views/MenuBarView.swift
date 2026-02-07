//
//  MenuBarView.swift
//  SyncFlowMac
//
//  Menu bar dropdown view for quick access
//

import SwiftUI

struct MenuBarView: View {
    @EnvironmentObject var appState: AppState

    var body: some View {
        VStack(spacing: 0) {
            // Phone Status Widget
            if appState.isPaired {
                PhoneStatusWidget(status: appState.phoneStatus, unreadCount: appState.unreadCount)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)

                Divider()
            }

            // Active call indicator
            if let call = appState.activeCalls.first(where: { $0.callState == .active }) {
                VStack(spacing: 6) {
                    Button(action: { openApp() }) {
                        HStack {
                            Image(systemName: "phone.fill")
                                .foregroundColor(.green)
                            Text("Call with \(call.displayName)")
                                .lineLimit(1)
                            Spacer()
                        }
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)

                Divider()
            }

            // Incoming call
            if let incomingCall = appState.incomingCall {
                VStack(spacing: 8) {
                    HStack {
                        Image(systemName: "phone.arrow.down.left")
                            .foregroundColor(.green)
                        Text("Incoming: \(incomingCall.displayName)")
                            .lineLimit(1)
                        Spacer()
                    }

                    HStack(spacing: 12) {
                        Button("Answer") {
                            appState.answerCall(incomingCall)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(.green)

                        Button("Decline") {
                            appState.rejectCall(incomingCall)
                        }
                        .buttonStyle(.bordered)
                        .tint(.red)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)

                Divider()
            }

            // Quick actions
            Button(action: { openApp() }) {
                Label("Open SyncFlow", systemImage: "message.fill")
            }
            .keyboardShortcut("o")

            Button(action: { openAppAndNewMessage() }) {
                Label("New Message", systemImage: "square.and.pencil")
            }
            .keyboardShortcut("n")

            Button(action: { openAppAndDial() }) {
                Label("Make a Call", systemImage: "phone.badge.plus")
            }
            .keyboardShortcut("d")

            // Find My Phone
            if appState.isPhoneRinging {
                Button(action: { appState.stopFindingPhone() }) {
                    Label("Stop Ringing", systemImage: "bell.slash.fill")
                }
                .foregroundColor(.orange)
            } else {
                Button(action: { appState.findMyPhone() }) {
                    Label("Find My Phone", systemImage: "bell.and.waves.left.and.right")
                }
            }

            Divider()

            // Recent conversations (if any)
            if !appState.recentConversations.isEmpty {
                Text("Recent")
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 12)
                    .padding(.top, 4)

                ForEach(appState.recentConversations.prefix(3)) { conversation in
                    Button(action: { openConversation(conversation) }) {
                        HStack {
                            Text(conversation.displayName)
                                .lineLimit(1)
                            Spacer()
                            if conversation.unreadCount > 0 {
                                Text("\(conversation.unreadCount)")
                                    .font(.caption2)
                                    .padding(.horizontal, 6)
                                    .padding(.vertical, 2)
                                    .background(Color.blue)
                                    .foregroundColor(.white)
                                    .clipShape(Capsule())
                            }
                        }
                    }
                }

                Divider()
            }

            // Settings and quit
            SettingsLink {
                Label("Settings...", systemImage: "gear")
            }
            .keyboardShortcut(",")

            Divider()

            Button(action: { NSApplication.shared.terminate(nil) }) {
                Label("Quit SyncFlow", systemImage: "power")
            }
            .keyboardShortcut("q")
        }
        .frame(width: 250)
    }

    private func openApp() {
        NSApplication.shared.activate(ignoringOtherApps: true)
        if let window = NSApplication.shared.windows.first {
            window.makeKeyAndOrderFront(nil)
        }
    }

    private func openAppAndNewMessage() {
        openApp()
        appState.showNewMessage = true
    }

    private func openAppAndDial() {
        openApp()
        appState.selectedTab = .callHistory
        // The dial sheet will need to be triggered from CallHistoryView
    }

    private func openConversation(_ conversation: Conversation) {
        openApp()
        appState.selectedTab = .messages
        appState.selectedConversation = conversation
    }
}

// MARK: - Phone Status Widget

struct PhoneStatusWidget: View {
    let status: PhoneStatus
    let unreadCount: Int

    var body: some View {
        VStack(spacing: 6) {
            // Device name and connection status
            HStack {
                Circle()
                    .fill(status.isConnected ? Color.green : Color.orange)
                    .frame(width: 8, height: 8)
                Text(status.deviceName)
                    .font(.caption)
                    .fontWeight(.medium)
                Spacer()
                if unreadCount > 0 {
                    Text("\(unreadCount) unread")
                        .font(.caption)
                        .foregroundColor(.blue)
                }
            }

            // Status indicators
            if status.isConnected {
                HStack(spacing: 12) {
                    // Battery
                    HStack(spacing: 4) {
                        Image(systemName: status.batteryIcon)
                            .foregroundColor(batteryColor)
                            .font(.system(size: 14))
                        Text("\(max(0, status.batteryLevel))%")
                            .font(.caption2)
                            .foregroundColor(.secondary)
                    }

                    // Signal/WiFi
                    HStack(spacing: 4) {
                        if status.wifiConnected {
                            Image(systemName: "wifi")
                                .foregroundColor(.blue)
                                .font(.system(size: 12))
                            Text(status.wifiSsid.isEmpty ? "WiFi" : truncateSSID(status.wifiSsid))
                                .font(.caption2)
                                .foregroundColor(.secondary)
                                .lineLimit(1)
                        } else if status.cellularConnected {
                            signalBarsView
                            Text(status.networkType)
                                .font(.caption2)
                                .foregroundColor(.secondary)
                        } else {
                            Image(systemName: "antenna.radiowaves.left.and.right.slash")
                                .foregroundColor(.gray)
                                .font(.system(size: 12))
                            Text("No Network")
                                .font(.caption2)
                                .foregroundColor(.secondary)
                        }
                    }

                    Spacer()
                }
            } else {
                HStack {
                    Image(systemName: "exclamationmark.triangle")
                        .foregroundColor(.orange)
                        .font(.system(size: 12))
                    Text("Phone not connected")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                    Spacer()
                }
            }
        }
    }

    private var batteryColor: Color {
        if status.isCharging { return .green }
        if status.batteryLevel < 20 { return .red }
        if status.batteryLevel < 50 { return .orange }
        return .green
    }

    private var signalBarsView: some View {
        HStack(spacing: 1) {
            ForEach(0..<4) { bar in
                RoundedRectangle(cornerRadius: 1)
                    .fill(bar < status.signalStrength ? Color.primary : Color.gray.opacity(0.3))
                    .frame(width: 3, height: CGFloat(4 + bar * 2))
            }
        }
    }

    private func truncateSSID(_ ssid: String) -> String {
        if ssid.count > 12 {
            return String(ssid.prefix(10)) + "..."
        }
        return ssid
    }
}

struct MenuBarView_Previews: PreviewProvider {
    static var previews: some View {
        MenuBarView()
            .environmentObject(AppState())
    }
}
