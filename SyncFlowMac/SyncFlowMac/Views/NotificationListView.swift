//
//  NotificationListView.swift
//  SyncFlowMac
//
//  View to display mirrored notifications from Android
//

import SwiftUI

struct NotificationListView: View {
    @ObservedObject var notificationService: NotificationMirrorService
    @Environment(\.dismiss) var dismiss
    @State private var hoveredNotification: String?

    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                Text("Phone Notifications")
                    .font(.headline)

                Spacer()

                Toggle("", isOn: Binding(
                    get: { notificationService.isEnabled },
                    set: { notificationService.setEnabled($0) }
                ))
                .toggleStyle(.switch)
                .labelsHidden()

                if !notificationService.recentNotifications.isEmpty {
                    Button("Clear All") {
                        notificationService.clearNotifications()
                    }
                    .buttonStyle(.borderless)
                    .foregroundColor(.secondary)
                }

                Button("Close") {
                    dismiss()
                }
                .keyboardShortcut(.escape, modifiers: [])
            }
            .padding()

            Divider()

            if notificationService.recentNotifications.isEmpty {
                // Empty state
                VStack(spacing: 16) {
                    Image(systemName: "bell.slash")
                        .font(.system(size: 48))
                        .foregroundColor(.secondary)

                    Text("No Notifications")
                        .font(.headline)
                        .foregroundColor(.secondary)

                    if notificationService.isEnabled {
                        Text("Notifications from your Android phone\nwill appear here")
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)
                    } else {
                        Text("Notification mirroring is disabled")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                // Notification list
                ScrollView {
                    LazyVStack(spacing: 1) {
                        ForEach(notificationService.recentNotifications) { notification in
                            NotificationRow(
                                notification: notification,
                                isHovered: hoveredNotification == notification.id,
                                onDismiss: {
                                    notificationService.dismissNotification(notification)
                                }
                            )
                            .onHover { isHovered in
                                hoveredNotification = isHovered ? notification.id : nil
                            }
                        }
                    }
                    .padding(.vertical, 8)
                }
            }
        }
        .frame(minWidth: 350, minHeight: 300)
    }
}

struct NotificationRow: View {
    let notification: MirroredNotification
    let isHovered: Bool
    let onDismiss: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            // App icon
            if let icon = notification.appIcon {
                Image(nsImage: icon)
                    .resizable()
                    .frame(width: 32, height: 32)
                    .cornerRadius(6)
            } else {
                RoundedRectangle(cornerRadius: 6)
                    .fill(Color.gray.opacity(0.3))
                    .frame(width: 32, height: 32)
                    .overlay(
                        Image(systemName: "app.fill")
                            .foregroundColor(.secondary)
                    )
            }

            // Content
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(notification.appName)
                        .font(.caption)
                        .foregroundColor(.secondary)

                    Spacer()

                    Text(notification.formattedTime)
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }

                if !notification.title.isEmpty {
                    Text(notification.title)
                        .font(.subheadline)
                        .fontWeight(.medium)
                        .lineLimit(1)
                }

                if !notification.text.isEmpty {
                    Text(notification.text)
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .lineLimit(2)
                }
            }

            // Dismiss button (shown on hover)
            if isHovered {
                Button(action: onDismiss) {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.borderless)
                .transition(.opacity)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(isHovered ? Color.gray.opacity(0.1) : Color.clear)
        .contentShape(Rectangle())
    }
}

// Menu bar notification preview
struct NotificationPreviewWidget: View {
    @ObservedObject var notificationService: NotificationMirrorService
    let maxItems: Int = 3

    var body: some View {
        if !notificationService.recentNotifications.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Image(systemName: "bell.fill")
                        .foregroundColor(.secondary)
                    Text("Recent Notifications")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }

                Divider()

                ForEach(Array(notificationService.recentNotifications.prefix(maxItems))) { notification in
                    HStack(spacing: 8) {
                        if let icon = notification.appIcon {
                            Image(nsImage: icon)
                                .resizable()
                                .frame(width: 16, height: 16)
                                .cornerRadius(3)
                        } else {
                            Image(systemName: "app.fill")
                                .frame(width: 16, height: 16)
                                .foregroundColor(.secondary)
                        }

                        VStack(alignment: .leading, spacing: 1) {
                            Text(notification.title.isEmpty ? notification.appName : notification.title)
                                .font(.caption)
                                .lineLimit(1)

                            if !notification.text.isEmpty {
                                Text(notification.text)
                                    .font(.caption2)
                                    .foregroundColor(.secondary)
                                    .lineLimit(1)
                            }
                        }

                        Spacer()

                        Text(notification.formattedTime)
                            .font(.caption2)
                            .foregroundColor(.secondary)
                    }
                }

                if notificationService.recentNotifications.count > maxItems {
                    Text("+ \(notificationService.recentNotifications.count - maxItems) more")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }
            }
            .padding(.vertical, 4)
        }
    }
}

#Preview {
    NotificationListView(notificationService: NotificationMirrorService.shared)
}
