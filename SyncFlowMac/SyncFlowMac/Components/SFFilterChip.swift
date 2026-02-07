//
//  SFFilterChip.swift
//  SyncFlowMac
//
//  Reusable filter chip components for SyncFlow macOS app
//

import SwiftUI

// MARK: - Filter Chip

struct SFFilterChip: View {
    let label: String
    var icon: String? = nil
    var selected: Bool = false
    var onClick: () -> Void

    @State private var isHovered = false

    var body: some View {
        Button(action: onClick) {
            HStack(spacing: 4) {
                if let icon = icon {
                    Image(systemName: icon)
                        .font(.system(size: SyncFlowSpacing.chipIconSize, weight: .medium))
                }

                Text(label)
                    .font(.system(size: 12, weight: .medium))
            }
            .foregroundColor(foregroundColor)
            .padding(.horizontal, SyncFlowSpacing.chipHorizontalPadding)
            .frame(height: SyncFlowSpacing.chipHeight)
            .background(backgroundColor)
            .clipShape(Capsule())
            .overlay(borderOverlay)
        }
        .buttonStyle(.plain)
        .onHover { hovering in
            withAnimation(.easeInOut(duration: 0.1)) {
                isHovered = hovering
            }
        }
    }

    private var foregroundColor: Color {
        selected ? .white : SyncFlowColors.textPrimary
    }

    private var backgroundColor: Color {
        if selected {
            return SyncFlowColors.primary
        } else if isHovered {
            return SyncFlowColors.hover
        } else {
            return SyncFlowColors.surfaceTertiary
        }
    }

    @ViewBuilder
    private var borderOverlay: some View {
        if !selected {
            Capsule()
                .strokeBorder(SyncFlowColors.border.opacity(0.5), lineWidth: 1)
        }
    }
}

// MARK: - Filter Chip Group

struct SFFilterChipGroup: View {
    let options: [String]
    @Binding var selected: String?
    var icons: [String: String]? = nil

    var body: some View {
        HStack(spacing: SyncFlowSpacing.chipGap) {
            ForEach(options, id: \.self) { option in
                SFFilterChip(
                    label: option,
                    icon: icons?[option],
                    selected: selected == option
                ) {
                    withAnimation(.easeInOut(duration: 0.15)) {
                        if selected == option {
                            selected = nil
                        } else {
                            selected = option
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Multi-Select Filter Chip Group

struct SFMultiFilterChipGroup: View {
    let options: [String]
    @Binding var selected: Set<String>
    var icons: [String: String]? = nil

    var body: some View {
        HStack(spacing: SyncFlowSpacing.chipGap) {
            ForEach(options, id: \.self) { option in
                SFFilterChip(
                    label: option,
                    icon: icons?[option],
                    selected: selected.contains(option)
                ) {
                    withAnimation(.easeInOut(duration: 0.15)) {
                        if selected.contains(option) {
                            selected.remove(option)
                        } else {
                            selected.insert(option)
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Dismissible Chip

struct SFDismissibleChip: View {
    let label: String
    var icon: String? = nil
    var onDismiss: () -> Void

    @State private var isHovered = false

    var body: some View {
        HStack(spacing: 4) {
            if let icon = icon {
                Image(systemName: icon)
                    .font(.system(size: SyncFlowSpacing.chipIconSize, weight: .medium))
            }

            Text(label)
                .font(.system(size: 12, weight: .medium))

            Button(action: onDismiss) {
                Image(systemName: "xmark")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundColor(SyncFlowColors.textSecondary)
            }
            .buttonStyle(.plain)
        }
        .foregroundColor(SyncFlowColors.textPrimary)
        .padding(.horizontal, SyncFlowSpacing.chipHorizontalPadding)
        .frame(height: SyncFlowSpacing.chipHeight)
        .background(isHovered ? SyncFlowColors.hover : SyncFlowColors.surfaceTertiary)
        .clipShape(Capsule())
        .overlay(
            Capsule()
                .strokeBorder(SyncFlowColors.border.opacity(0.5), lineWidth: 1)
        )
        .onHover { hovering in
            withAnimation(.easeInOut(duration: 0.1)) {
                isHovered = hovering
            }
        }
    }
}

// MARK: - Input Chip (for tags)

struct SFInputChip: View {
    let label: String
    var avatar: String? = nil
    var onDelete: (() -> Void)? = nil

    @State private var isHovered = false

    var body: some View {
        HStack(spacing: 4) {
            if let avatar = avatar {
                SFAvatar(name: avatar, size: .small)
                    .scaleEffect(0.7)
            }

            Text(label)
                .font(.system(size: 12, weight: .medium))
                .foregroundColor(SyncFlowColors.textPrimary)

            if let onDelete = onDelete {
                Button(action: onDelete) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 14))
                        .foregroundColor(SyncFlowColors.textTertiary)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.leading, avatar != nil ? 4 : SyncFlowSpacing.chipHorizontalPadding)
        .padding(.trailing, SyncFlowSpacing.chipHorizontalPadding)
        .frame(height: SyncFlowSpacing.chipHeight)
        .background(isHovered ? SyncFlowColors.hover : SyncFlowColors.surfaceTertiary)
        .clipShape(Capsule())
        .overlay(
            Capsule()
                .strokeBorder(SyncFlowColors.border.opacity(0.5), lineWidth: 1)
        )
        .onHover { hovering in
            withAnimation(.easeInOut(duration: 0.1)) {
                isHovered = hovering
            }
        }
    }
}

// MARK: - Suggestion Chip

struct SFSuggestionChip: View {
    let label: String
    var icon: String? = nil
    var onClick: () -> Void

    @State private var isHovered = false

    var body: some View {
        Button(action: onClick) {
            HStack(spacing: 4) {
                if let icon = icon {
                    Image(systemName: icon)
                        .font(.system(size: SyncFlowSpacing.chipIconSize))
                }

                Text(label)
                    .font(.system(size: 12, weight: .regular))
            }
            .foregroundColor(SyncFlowColors.primary)
            .padding(.horizontal, SyncFlowSpacing.chipHorizontalPadding)
            .frame(height: SyncFlowSpacing.chipHeight)
            .background(isHovered ? SyncFlowColors.primary.opacity(0.1) : Color.clear)
            .clipShape(Capsule())
            .overlay(
                Capsule()
                    .strokeBorder(SyncFlowColors.primary.opacity(0.5), lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
        .onHover { hovering in
            withAnimation(.easeInOut(duration: 0.1)) {
                isHovered = hovering
            }
        }
    }
}

// MARK: - Preview

#if DEBUG
struct SFFilterChip_Previews: PreviewProvider {
    @State static var selected: String? = "All"
    @State static var multiSelected: Set<String> = ["SMS"]

    static var previews: some View {
        VStack(spacing: 20) {
            // Single select
            SFFilterChipGroup(
                options: ["All", "Unread", "OTP", "Transactions"],
                selected: $selected
            )

            // Multi select
            SFMultiFilterChipGroup(
                options: ["SMS", "MMS", "Calls"],
                selected: $multiSelected
            )

            // Dismissible chips
            HStack {
                SFDismissibleChip(label: "John Doe", icon: "person") {}
                SFDismissibleChip(label: "Filter 1") {}
            }

            // Input chips
            HStack {
                SFInputChip(label: "Alice", avatar: "Alice Brown") {}
                SFInputChip(label: "Tag 1") {}
            }

            // Suggestion chips
            HStack {
                SFSuggestionChip(label: "Quick Reply", icon: "bubble.left") {}
                SFSuggestionChip(label: "Send OTP") {}
            }
        }
        .padding()
    }
}
#endif
