//
//  SFButton.swift
//  SyncFlowMac
//
//  Reusable button components for SyncFlow macOS app
//

import SwiftUI

// MARK: - Button Size

enum SFButtonSize {
    case small
    case medium
    case large

    var height: CGFloat {
        switch self {
        case .small: return 24
        case .medium: return 32
        case .large: return 40
        }
    }

    var horizontalPadding: CGFloat {
        switch self {
        case .small: return 10
        case .medium: return 14
        case .large: return 20
        }
    }

    var fontSize: CGFloat {
        switch self {
        case .small: return 12
        case .medium: return 13
        case .large: return 14
        }
    }

    var iconSize: CGFloat {
        switch self {
        case .small: return 14
        case .medium: return 16
        case .large: return 18
        }
    }
}

// MARK: - Primary Button

struct SFPrimaryButton: View {
    let text: String
    let action: () -> Void
    var size: SFButtonSize = .medium
    var leadingIcon: String? = nil
    var trailingIcon: String? = nil
    var isLoading: Bool = false
    var isDisabled: Bool = false

    @State private var isHovered = false
    @State private var isPressed = false

    var body: some View {
        Button(action: {
            if !isLoading && !isDisabled {
                action()
            }
        }) {
            HStack(spacing: 6) {
                if isLoading {
                    ProgressView()
                        .scaleEffect(0.6)
                        .frame(width: size.iconSize, height: size.iconSize)
                } else if let icon = leadingIcon {
                    Image(systemName: icon)
                        .font(.system(size: size.iconSize, weight: .medium))
                }

                Text(text)
                    .font(.system(size: size.fontSize, weight: .medium))

                if let icon = trailingIcon, !isLoading {
                    Image(systemName: icon)
                        .font(.system(size: size.iconSize, weight: .medium))
                }
            }
            .foregroundColor(.white)
            .padding(.horizontal, size.horizontalPadding)
            .frame(height: size.height)
            .background(backgroundColor)
            .clipShape(RoundedRectangle(cornerRadius: SyncFlowSpacing.radiusSm))
        }
        .buttonStyle(.plain)
        .disabled(isDisabled || isLoading)
        .onHover { hovering in
            withAnimation(.easeInOut(duration: 0.1)) {
                isHovered = hovering
            }
        }
        .scaleEffect(isPressed ? 0.97 : 1.0)
    }

    private var backgroundColor: Color {
        if isDisabled {
            return SyncFlowColors.primary.opacity(0.4)
        } else if isHovered {
            return SyncFlowColors.primaryDark
        } else {
            return SyncFlowColors.primary
        }
    }
}

// MARK: - Secondary Button

struct SFSecondaryButton: View {
    let text: String
    let action: () -> Void
    var size: SFButtonSize = .medium
    var leadingIcon: String? = nil
    var trailingIcon: String? = nil
    var isLoading: Bool = false
    var isDisabled: Bool = false

    @State private var isHovered = false

    var body: some View {
        Button(action: {
            if !isLoading && !isDisabled {
                action()
            }
        }) {
            HStack(spacing: 6) {
                if isLoading {
                    ProgressView()
                        .scaleEffect(0.6)
                        .frame(width: size.iconSize, height: size.iconSize)
                } else if let icon = leadingIcon {
                    Image(systemName: icon)
                        .font(.system(size: size.iconSize, weight: .medium))
                }

                Text(text)
                    .font(.system(size: size.fontSize, weight: .medium))

                if let icon = trailingIcon, !isLoading {
                    Image(systemName: icon)
                        .font(.system(size: size.iconSize, weight: .medium))
                }
            }
            .foregroundColor(isDisabled ? SyncFlowColors.textDisabled : SyncFlowColors.primary)
            .padding(.horizontal, size.horizontalPadding)
            .frame(height: size.height)
            .background(isHovered ? SyncFlowColors.primary.opacity(0.08) : Color.clear)
            .clipShape(RoundedRectangle(cornerRadius: SyncFlowSpacing.radiusSm))
            .overlay(
                RoundedRectangle(cornerRadius: SyncFlowSpacing.radiusSm)
                    .strokeBorder(
                        isDisabled ? SyncFlowColors.border.opacity(0.5) : SyncFlowColors.border,
                        lineWidth: 1
                    )
            )
        }
        .buttonStyle(.plain)
        .disabled(isDisabled || isLoading)
        .onHover { hovering in
            withAnimation(.easeInOut(duration: 0.1)) {
                isHovered = hovering
            }
        }
    }
}

// MARK: - Ghost Button

struct SFGhostButton: View {
    let text: String
    let action: () -> Void
    var size: SFButtonSize = .medium
    var leadingIcon: String? = nil
    var trailingIcon: String? = nil
    var color: Color = SyncFlowColors.primary
    var isDisabled: Bool = false

    @State private var isHovered = false

    var body: some View {
        Button(action: {
            if !isDisabled {
                action()
            }
        }) {
            HStack(spacing: 6) {
                if let icon = leadingIcon {
                    Image(systemName: icon)
                        .font(.system(size: size.iconSize, weight: .medium))
                }

                Text(text)
                    .font(.system(size: size.fontSize, weight: .medium))

                if let icon = trailingIcon {
                    Image(systemName: icon)
                        .font(.system(size: size.iconSize, weight: .medium))
                }
            }
            .foregroundColor(isDisabled ? SyncFlowColors.textDisabled : color)
            .padding(.horizontal, size.horizontalPadding)
            .frame(height: size.height)
            .background(isHovered ? color.opacity(0.08) : Color.clear)
            .clipShape(RoundedRectangle(cornerRadius: SyncFlowSpacing.radiusSm))
        }
        .buttonStyle(.plain)
        .disabled(isDisabled)
        .onHover { hovering in
            withAnimation(.easeInOut(duration: 0.1)) {
                isHovered = hovering
            }
        }
    }
}

// MARK: - Danger Button

struct SFDangerButton: View {
    let text: String
    let action: () -> Void
    var size: SFButtonSize = .medium
    var leadingIcon: String? = nil
    var isDisabled: Bool = false

    @State private var isHovered = false

    var body: some View {
        Button(action: {
            if !isDisabled {
                action()
            }
        }) {
            HStack(spacing: 6) {
                if let icon = leadingIcon {
                    Image(systemName: icon)
                        .font(.system(size: size.iconSize, weight: .medium))
                }

                Text(text)
                    .font(.system(size: size.fontSize, weight: .medium))
            }
            .foregroundColor(.white)
            .padding(.horizontal, size.horizontalPadding)
            .frame(height: size.height)
            .background(isDisabled ? SyncFlowColors.error.opacity(0.4) : (isHovered ? SyncFlowColors.errorDark : SyncFlowColors.error))
            .clipShape(RoundedRectangle(cornerRadius: SyncFlowSpacing.radiusSm))
        }
        .buttonStyle(.plain)
        .disabled(isDisabled)
        .onHover { hovering in
            withAnimation(.easeInOut(duration: 0.1)) {
                isHovered = hovering
            }
        }
    }
}

// MARK: - Icon Button

struct SFIconButton: View {
    let icon: String
    let action: () -> Void
    var size: CGFloat = SyncFlowSpacing.minTouchTarget
    var iconSize: CGFloat = SyncFlowSpacing.iconMd
    var tint: Color = SyncFlowColors.textPrimary
    var isDisabled: Bool = false

    @State private var isHovered = false

    var body: some View {
        Button(action: {
            if !isDisabled {
                action()
            }
        }) {
            Image(systemName: icon)
                .font(.system(size: iconSize, weight: .regular))
                .foregroundColor(isDisabled ? SyncFlowColors.textDisabled : tint)
                .frame(width: size, height: size)
                .background(isHovered ? SyncFlowColors.hover : Color.clear)
                .clipShape(Circle())
        }
        .buttonStyle(.plain)
        .disabled(isDisabled)
        .onHover { hovering in
            withAnimation(.easeInOut(duration: 0.1)) {
                isHovered = hovering
            }
        }
    }
}

// MARK: - FAB (Floating Action Button)

struct SFFab: View {
    let icon: String
    let action: () -> Void
    var text: String? = nil
    var backgroundColor: Color = SyncFlowColors.primary
    var foregroundColor: Color = .white

    @State private var isHovered = false

    var body: some View {
        Button {
            action()
        } label: {
            HStack(spacing: 8) {
                Image(systemName: icon)
                    .font(.system(size: 20, weight: .medium))

                if let text = text {
                    Text(text)
                        .font(.system(size: 14, weight: .medium))
                }
            }
            .foregroundColor(foregroundColor)
            .padding(.horizontal, text != nil ? 20 : 16)
            .frame(height: SyncFlowSpacing.fabSize)
            .frame(minWidth: SyncFlowSpacing.fabSize)
            .background(isHovered ? backgroundColor.opacity(0.9) : backgroundColor)
            .clipShape(RoundedRectangle(cornerRadius: SyncFlowSpacing.radiusLg))
            .shadow(color: .black.opacity(0.15), radius: isHovered ? 8 : 6, x: 0, y: isHovered ? 4 : 3)
        }
        .buttonStyle(.plain)
        .onHover { hovering in
            withAnimation(.easeInOut(duration: 0.15)) {
                isHovered = hovering
            }
        }
    }
}

// MARK: - Preview

#if DEBUG
struct SFButton_Previews: PreviewProvider {
    static var previews: some View {
        VStack(spacing: 16) {
            HStack(spacing: 12) {
                SFPrimaryButton(text: "Primary", action: {})
                SFPrimaryButton(text: "Loading", action: {}, isLoading: true)
                SFPrimaryButton(text: "Disabled", action: {}, isDisabled: true)
            }

            HStack(spacing: 12) {
                SFSecondaryButton(text: "Secondary", action: {})
                SFSecondaryButton(text: "With Icon", action: {}, leadingIcon: "plus")
            }

            HStack(spacing: 12) {
                SFGhostButton(text: "Ghost", action: {})
                SFGhostButton(text: "With Icon", action: {}, leadingIcon: "arrow.right")
            }

            HStack(spacing: 12) {
                SFDangerButton(text: "Delete", action: {}, leadingIcon: "trash")
            }

            HStack(spacing: 12) {
                SFIconButton(icon: "plus", action: {})
                SFIconButton(icon: "xmark", action: {})
                SFIconButton(icon: "ellipsis", action: {})
            }

            SFFab(icon: "plus", action: {})
            SFFab(icon: "message", action: {}, text: "New Message")
        }
        .padding()
    }
}
#endif
