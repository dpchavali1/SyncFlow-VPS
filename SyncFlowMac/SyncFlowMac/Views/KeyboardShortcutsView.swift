//
//  KeyboardShortcutsView.swift
//  SyncFlowMac
//
//  Shows keyboard shortcuts help overlay
//

import SwiftUI

struct KeyboardShortcutsView: View {
    @Binding var isPresented: Bool

    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                Text("Keyboard Shortcuts")
                    .font(.title2)
                    .fontWeight(.bold)

                Spacer()

                Button(action: { isPresented = false }) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.title2)
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.plain)
                .keyboardShortcut(.escape, modifiers: [])
            }
            .padding()

            Divider()

            ScrollView {
                HStack(alignment: .top, spacing: 32) {
                    // Left column
                    VStack(alignment: .leading, spacing: 20) {
                        ShortcutSection(title: "Navigation", shortcuts: [
                            Shortcut(keys: ["\u{2318}", "1"], description: "Messages"),
                            Shortcut(keys: ["\u{2318}", "2"], description: "Contacts"),
                            Shortcut(keys: ["\u{2318}", "3"], description: "Call History"),
                            Shortcut(keys: ["\u{2318}", ","], description: "Settings"),
                        ])

                        ShortcutSection(title: "Messages", shortcuts: [
                            Shortcut(keys: ["\u{2318}", "N"], description: "New message"),
                            Shortcut(keys: ["\u{2318}", "F"], description: "Search conversations"),
                            Shortcut(keys: ["\u{2318}", "T"], description: "Message templates"),
                            Shortcut(keys: ["\u{21A9}"], description: "Send message"),
                            Shortcut(keys: ["\u{21E7}", "\u{21A9}"], description: "New line"),
                        ])

                        ShortcutSection(title: "Calls", shortcuts: [
                            Shortcut(keys: ["\u{2318}", "\u{21E7}", "D"], description: "Open dialer"),
                            Shortcut(keys: ["\u{2318}", "\u{21A9}"], description: "Answer call"),
                            Shortcut(keys: ["\u{2318}", "\u{238B}"], description: "Reject call"),
                            Shortcut(keys: ["\u{2318}", "\u{21E7}", "E"], description: "End call"),
                        ])

                        ShortcutSection(title: "Phone Features", shortcuts: [
                            Shortcut(keys: ["\u{2318}", "\u{21E7}", "P"], description: "Quick Drop files"),
                            Shortcut(keys: ["\u{2318}", "\u{21E7}", "N"], description: "Notifications"),
                            Shortcut(keys: ["\u{2318}", "\u{21E7}", "S"], description: "Scheduled messages"),
                            Shortcut(keys: ["\u{2318}", "\u{21E7}", "V"], description: "Voicemails"),
                            Shortcut(keys: ["\u{2318}", "\u{21E7}", "L"], description: "Find my phone"),
                        ])
                    }

                    // Right column
                    VStack(alignment: .leading, spacing: 20) {
                        ShortcutSection(title: "Media Control", shortcuts: [
                            Shortcut(keys: ["\u{2318}", "\u{21E7}", "\u{2423}"], description: "Play / Pause"),
                            Shortcut(keys: ["\u{2318}", "\u{21E7}", "\u{2190}"], description: "Previous track"),
                            Shortcut(keys: ["\u{2318}", "\u{21E7}", "\u{2192}"], description: "Next track"),
                            Shortcut(keys: ["\u{2318}", "\u{21E7}", "\u{2191}"], description: "Volume up"),
                            Shortcut(keys: ["\u{2318}", "\u{21E7}", "\u{2193}"], description: "Volume down"),
                            Shortcut(keys: ["\u{2318}", "\u{21E7}", "M"], description: "Toggle media bar"),
                        ])

                        ShortcutSection(title: "Tools", shortcuts: [
                            Shortcut(keys: ["\u{2318}", "\u{21E7}", "A"], description: "AI Assistant"),
                        ])

                        ShortcutSection(title: "Editing", shortcuts: [
                            Shortcut(keys: ["\u{2318}", "C"], description: "Copy"),
                            Shortcut(keys: ["\u{2318}", "V"], description: "Paste"),
                            Shortcut(keys: ["\u{2318}", "A"], description: "Select all"),
                            Shortcut(keys: ["Double-click"], description: "Quick react"),
                        ])

                        ShortcutSection(title: "Window", shortcuts: [
                            Shortcut(keys: ["\u{2318}", "M"], description: "Minimize"),
                            Shortcut(keys: ["\u{2318}", "W"], description: "Close window"),
                            Shortcut(keys: ["\u{2318}", "Q"], description: "Quit SyncFlow"),
                            Shortcut(keys: ["\u{2318}", "\u{21E7}", "/"], description: "Show this help"),
                        ])
                    }
                }
                .padding()
            }

            Divider()

            // Footer
            Text("Press Escape to close")
                .font(.caption)
                .foregroundColor(.secondary)
                .padding()
        }
        .frame(width: 620, height: 560)
        .background(Color(nsColor: .windowBackgroundColor))
        .cornerRadius(16)
        .shadow(radius: 20)
    }
}

struct ShortcutSection: View {
    let title: String
    let shortcuts: [Shortcut]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.headline)
                .foregroundColor(.secondary)

            VStack(spacing: 6) {
                ForEach(shortcuts) { shortcut in
                    ShortcutRow(shortcut: shortcut)
                }
            }
        }
    }
}

struct Shortcut: Identifiable {
    let id = UUID()
    let keys: [String]
    let description: String
}

struct ShortcutRow: View {
    let shortcut: Shortcut

    var body: some View {
        HStack {
            Text(shortcut.description)
                .foregroundColor(.primary)

            Spacer()

            HStack(spacing: 4) {
                ForEach(shortcut.keys, id: \.self) { key in
                    KeyCap(key: key)
                }
            }
        }
    }
}

struct KeyCap: View {
    let key: String

    var body: some View {
        Text(key)
            .font(.system(size: 12, weight: .medium, design: .rounded))
            .foregroundColor(.primary)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(
                RoundedRectangle(cornerRadius: 6)
                    .fill(Color(nsColor: .controlBackgroundColor))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 6)
                    .stroke(Color.gray.opacity(0.3), lineWidth: 1)
            )
    }
}

// MARK: - Preview

struct KeyboardShortcutsView_Previews: PreviewProvider {
    static var previews: some View {
        KeyboardShortcutsView(isPresented: .constant(true))
    }
}
