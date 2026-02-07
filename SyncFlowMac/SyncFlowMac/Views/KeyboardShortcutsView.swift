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
                VStack(alignment: .leading, spacing: 20) {
                    // Navigation
                    ShortcutSection(title: "Navigation", shortcuts: [
                        Shortcut(keys: ["Cmd", "1"], description: "Go to Messages"),
                        Shortcut(keys: ["Cmd", "2"], description: "Go to Contacts"),
                        Shortcut(keys: ["Cmd", "3"], description: "Go to Calls"),
                        Shortcut(keys: ["Cmd", "N"], description: "New message"),
                        Shortcut(keys: ["Cmd", "D"], description: "Open dialer"),
                        Shortcut(keys: ["Cmd", ","], description: "Open settings")
                    ])

                    // Messages
                    ShortcutSection(title: "Messages", shortcuts: [
                        Shortcut(keys: ["Cmd", "F"], description: "Search in conversation"),
                        Shortcut(keys: ["Cmd", "T"], description: "Toggle message templates"),
                        Shortcut(keys: ["Return"], description: "Send message"),
                        Shortcut(keys: ["Shift", "Return"], description: "New line in message")
                    ])

                    // Actions
                    ShortcutSection(title: "Actions", shortcuts: [
                        Shortcut(keys: ["Cmd", "C"], description: "Copy selected text"),
                        Shortcut(keys: ["Cmd", "V"], description: "Paste"),
                        Shortcut(keys: ["Cmd", "A"], description: "Select all text"),
                        Shortcut(keys: ["Double-click"], description: "Quick react with thumbs up")
                    ])

                    // Window
                    ShortcutSection(title: "Window", shortcuts: [
                        Shortcut(keys: ["Cmd", "M"], description: "Minimize window"),
                        Shortcut(keys: ["Cmd", "W"], description: "Close window"),
                        Shortcut(keys: ["Cmd", "Q"], description: "Quit SyncFlow"),
                        Shortcut(keys: ["Cmd", "?"], description: "Show this help")
                    ])
                }
                .padding()
            }

            Divider()

            // Footer
            Text("Press Escape or click outside to close")
                .font(.caption)
                .foregroundColor(.secondary)
                .padding()
        }
        .frame(width: 400, height: 500)
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
        Text(symbolizedKey)
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

    private var symbolizedKey: String {
        switch key.lowercased() {
        case "cmd", "command": return "\u{2318}"
        case "shift": return "\u{21E7}"
        case "option", "alt": return "\u{2325}"
        case "control", "ctrl": return "\u{2303}"
        case "return", "enter": return "\u{21A9}"
        case "escape", "esc": return "\u{238B}"
        case "tab": return "\u{21E5}"
        case "delete", "backspace": return "\u{232B}"
        case "up": return "\u{2191}"
        case "down": return "\u{2193}"
        case "left": return "\u{2190}"
        case "right": return "\u{2192}"
        case "space": return "\u{2423}"
        default: return key
        }
    }
}

// MARK: - Preview

struct KeyboardShortcutsView_Previews: PreviewProvider {
    static var previews: some View {
        KeyboardShortcutsView(isPresented: .constant(true))
    }
}
