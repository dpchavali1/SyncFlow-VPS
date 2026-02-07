//
//  SFListRow.swift
//  SyncFlowMac
//
//  Reusable list row components for SyncFlow macOS app
//

import SwiftUI

// MARK: - Conversation Row

struct SFConversationRow: View {
    let name: String
    let preview: String
    let timestamp: String
    var avatarURL: URL? = nil
    var unreadCount: Int = 0
    var messageType: SFMessageType = .normal
    var simSlot: Int? = nil
    var selected: Bool = false
    var onClick: () -> Void = {}

    @State private var isHovered = false

    var body: some View {
        HStack(spacing: SyncFlowSpacing.listItemContentGap) {
            // Avatar
            SFAvatar(
                name: name,
                imageURL: avatarURL,
                size: .medium
            )

            // Content
            VStack(alignment: .leading, spacing: SyncFlowSpacing.xxxs) {
                // Top row: Name + SIM + Timestamp
                HStack {
                    Text(name)
                        .font(SyncFlowTypography.conversationTitle)
                        .foregroundColor(unreadCount > 0 ? SyncFlowColors.textPrimary : SyncFlowColors.textPrimary.opacity(0.87))
                        .lineLimit(1)

                    if let sim = simSlot {
                        SFSimBadge(simSlot: sim)
                    }

                    Spacer()

                    Text(timestamp)
                        .font(SyncFlowTypography.timestamp)
                        .foregroundColor(unreadCount > 0 ? SyncFlowColors.primary : SyncFlowColors.textTertiary)
                }

                // Bottom row: Preview + Badge
                HStack {
                    if messageType != .normal {
                        SFMessageTypeBadge(type: messageType)
                    }

                    Text(preview)
                        .font(SyncFlowTypography.conversationPreview)
                        .foregroundColor(SyncFlowColors.textSecondary)
                        .fontWeight(unreadCount > 0 ? .medium : .regular)
                        .lineLimit(1)

                    Spacer()

                    if unreadCount > 0 {
                        SFBadge(count: unreadCount)
                    }
                }
            }
        }
        .padding(SyncFlowPadding.listItem)
        .background(backgroundColor)
        .clipShape(RoundedRectangle(cornerRadius: SyncFlowSpacing.radiusSm))
        .onHover { hovering in
            withAnimation(.easeInOut(duration: 0.1)) {
                isHovered = hovering
            }
        }
        .onTapGesture {
            onClick()
        }
    }

    private var backgroundColor: Color {
        if selected {
            return SyncFlowColors.primary.opacity(0.12)
        } else if isHovered {
            return SyncFlowColors.hover
        } else {
            return .clear
        }
    }
}

// MARK: - Contact Row

struct SFContactRow: View {
    let name: String
    var subtitle: String? = nil
    var avatarURL: URL? = nil
    var status: SFOnlineStatus = .none
    var selected: Bool = false
    var showCheckmark: Bool = false
    var onClick: () -> Void = {}

    @State private var isHovered = false

    var body: some View {
        HStack(spacing: SyncFlowSpacing.listItemContentGap) {
            SFAvatar(
                name: name,
                imageURL: avatarURL,
                size: .medium,
                status: status
            )

            VStack(alignment: .leading, spacing: SyncFlowSpacing.xxxs) {
                Text(name)
                    .font(SyncFlowTypography.conversationTitle)
                    .foregroundColor(SyncFlowColors.textPrimary)
                    .lineLimit(1)

                if let subtitle = subtitle {
                    Text(subtitle)
                        .font(SyncFlowTypography.conversationPreview)
                        .foregroundColor(SyncFlowColors.textSecondary)
                        .lineLimit(1)
                }
            }

            Spacer()

            if showCheckmark && selected {
                Image(systemName: "checkmark")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(SyncFlowColors.primary)
            }
        }
        .padding(SyncFlowPadding.listItem)
        .background(backgroundColor)
        .clipShape(RoundedRectangle(cornerRadius: SyncFlowSpacing.radiusSm))
        .onHover { hovering in
            withAnimation(.easeInOut(duration: 0.1)) {
                isHovered = hovering
            }
        }
        .onTapGesture {
            onClick()
        }
    }

    private var backgroundColor: Color {
        if selected {
            return SyncFlowColors.primary.opacity(0.12)
        } else if isHovered {
            return SyncFlowColors.hover
        } else {
            return .clear
        }
    }
}

// MARK: - Settings Row

struct SFSettingsRow: View {
    let title: String
    var subtitle: String? = nil
    var icon: String? = nil
    var iconColor: Color = SyncFlowColors.primary
    var showChevron: Bool = true
    var trailing: AnyView? = nil
    var enabled: Bool = true
    var onClick: (() -> Void)? = nil

    @State private var isHovered = false

    var body: some View {
        HStack(spacing: SyncFlowSpacing.listItemContentGap) {
            // Icon
            if let icon = icon {
                ZStack {
                    Circle()
                        .fill(iconColor.opacity(0.12))
                        .frame(width: 36, height: 36)

                    Image(systemName: icon)
                        .font(.system(size: 16, weight: .medium))
                        .foregroundColor(iconColor)
                }
            }

            // Title & Subtitle
            VStack(alignment: .leading, spacing: SyncFlowSpacing.xxxs) {
                Text(title)
                    .font(SyncFlowTypography.bodyLarge)
                    .foregroundColor(enabled ? SyncFlowColors.textPrimary : SyncFlowColors.textDisabled)

                if let subtitle = subtitle {
                    Text(subtitle)
                        .font(SyncFlowTypography.bodySmall)
                        .foregroundColor(enabled ? SyncFlowColors.textSecondary : SyncFlowColors.textDisabled.opacity(0.7))
                }
            }

            Spacer()

            // Trailing content
            if let trailing = trailing {
                trailing
            } else if showChevron && onClick != nil {
                Image(systemName: "chevron.right")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(SyncFlowColors.textTertiary)
            }
        }
        .padding(SyncFlowPadding.listItem)
        .background(isHovered && onClick != nil ? SyncFlowColors.hover : Color.clear)
        .clipShape(RoundedRectangle(cornerRadius: SyncFlowSpacing.radiusSm))
        .onHover { hovering in
            if onClick != nil && enabled {
                withAnimation(.easeInOut(duration: 0.1)) {
                    isHovered = hovering
                }
            }
        }
        .onTapGesture {
            if enabled {
                onClick?()
            }
        }
    }
}

// MARK: - Settings Toggle Row

struct SFSettingsToggle: View {
    let title: String
    @Binding var isOn: Bool
    var subtitle: String? = nil
    var icon: String? = nil
    var iconColor: Color = SyncFlowColors.primary
    var enabled: Bool = true

    var body: some View {
        SFSettingsRow(
            title: title,
            subtitle: subtitle,
            icon: icon,
            iconColor: iconColor,
            showChevron: false,
            trailing: AnyView(
                Toggle("", isOn: $isOn)
                    .toggleStyle(.switch)
                    .disabled(!enabled)
            ),
            enabled: enabled
        )
    }
}

// MARK: - Section Header

struct SFSectionHeader: View {
    let title: String
    var action: (() -> Void)? = nil
    var actionText: String? = nil

    var body: some View {
        HStack {
            Text(title.uppercased())
                .font(SyncFlowTypography.sectionHeader)
                .foregroundColor(SyncFlowColors.primary)
                .tracking(0.8)

            Spacer()

            if let actionText = actionText, let action = action {
                Button(action: action) {
                    Text(actionText)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundColor(SyncFlowColors.primary)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, SyncFlowSpacing.listItemHorizontal)
        .padding(.vertical, SyncFlowSpacing.xs)
    }
}

// MARK: - List Divider

struct SFListDivider: View {
    var indent: Bool = false

    var body: some View {
        Divider()
            .padding(.leading, indent ? SyncFlowSpacing.dividerInsetStart : 0)
    }
}

// MARK: - Preview

#if DEBUG
struct SFListRow_Previews: PreviewProvider {
    static var previews: some View {
        VStack(spacing: 0) {
            SFSectionHeader(title: "Conversations")

            SFConversationRow(
                name: "John Doe",
                preview: "Hey, how are you doing?",
                timestamp: "10:42 AM",
                unreadCount: 3
            )

            SFConversationRow(
                name: "Jane Smith",
                preview: "Your OTP is 123456",
                timestamp: "Yesterday",
                messageType: .otp,
                simSlot: 1
            )

            SFListDivider(indent: true)

            SFSectionHeader(title: "Contacts")

            SFContactRow(
                name: "Alice Brown",
                subtitle: "+1 234 567 8900",
                status: .online
            )

            SFListDivider()

            SFSectionHeader(title: "Settings")

            SFSettingsRow(
                title: "Notifications",
                subtitle: "Manage notification preferences",
                icon: "bell.fill"
            ) {}

            SFSettingsToggle(
                title: "Dark Mode",
                isOn: .constant(true),
                icon: "moon.fill"
            )
        }
        .padding()
        .frame(width: 350)
    }
}
#endif
