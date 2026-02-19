//
//  SFCard.swift
//  SyncFlowMac
//
//  Reusable card component for SyncFlow macOS app
//

import SwiftUI

// MARK: - Card Variant

enum SFCardVariant {
    case elevated   // Shadow elevation
    case outlined   // Border outline
    case filled     // Solid background
    case flat       // No elevation, minimal styling
    case glass      // Glass morphism with vibrancy
}

// MARK: - SFCard

struct SFCard<Content: View>: View {
    var variant: SFCardVariant = .elevated
    var cornerRadius: CGFloat = SyncFlowSpacing.radiusPremium
    var onClick: (() -> Void)? = nil
    @ViewBuilder var content: () -> Content

    @State private var isHovered = false
    @State private var isPressed = false

    var body: some View {
        contentView
            .background(backgroundColor)
            .background(variant == .glass ? AnyView(VisualEffectBackground(material: .popover, blendingMode: .withinWindow)) : AnyView(EmptyView()))
            .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
            .overlay(borderOverlay)
            .shadow(
                color: shadowColor,
                radius: shadowRadius,
                x: 0,
                y: shadowY
            )
            .offset(y: variant == .elevated && isHovered ? -1 : 0)
            .onHover { hovering in
                withAnimation(SFAnimations.snappy) {
                    isHovered = hovering
                }
            }
            .onTapGesture {
                if let onClick = onClick {
                    withAnimation(SFAnimations.micro) {
                        isPressed = true
                    }
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                        isPressed = false
                        onClick()
                    }
                }
            }
            .scaleEffect(isPressed ? 0.98 : 1.0)
    }

    @ViewBuilder
    private var contentView: some View {
        content()
    }

    private var backgroundColor: Color {
        switch variant {
        case .elevated:
            return isHovered ? SyncFlowColors.surfaceElevated.opacity(0.95) : SyncFlowColors.surfaceElevated
        case .outlined:
            return isHovered ? SyncFlowColors.hoverWarm : SyncFlowColors.surface
        case .filled:
            return isHovered ? SyncFlowColors.surfaceTertiary.opacity(0.9) : SyncFlowColors.surfaceTertiary
        case .flat:
            return isHovered ? SyncFlowColors.hoverWarm : .clear
        case .glass:
            return SyncFlowColors.glassBackground
        }
    }

    @ViewBuilder
    private var borderOverlay: some View {
        switch variant {
        case .outlined:
            RoundedRectangle(cornerRadius: cornerRadius)
                .strokeBorder(SyncFlowColors.glassBorder, lineWidth: 1)
        case .glass:
            RoundedRectangle(cornerRadius: cornerRadius)
                .strokeBorder(SyncFlowColors.glassBorder, lineWidth: 1)
        default:
            EmptyView()
        }
    }

    private var shadowColor: Color {
        switch variant {
        case .elevated:
            return .black.opacity(0.06)
        case .glass:
            return .black.opacity(0.04)
        default:
            return .clear
        }
    }

    private var shadowRadius: CGFloat {
        switch variant {
        case .elevated:
            return isHovered ? 10 : 6
        case .glass:
            return isHovered ? 8 : 4
        default:
            return 0
        }
    }

    private var shadowY: CGFloat {
        switch variant {
        case .elevated:
            return isHovered ? 4 : 2
        case .glass:
            return isHovered ? 3 : 2
        default:
            return 0
        }
    }
}

// MARK: - Padded Card

struct SFPaddedCard<Content: View>: View {
    var variant: SFCardVariant = .elevated
    var cornerRadius: CGFloat = SyncFlowSpacing.radiusPremium
    var padding: EdgeInsets = SyncFlowPadding.card
    var onClick: (() -> Void)? = nil
    @ViewBuilder var content: () -> Content

    var body: some View {
        SFCard(variant: variant, cornerRadius: cornerRadius, onClick: onClick) {
            content()
                .padding(padding)
        }
    }
}

// MARK: - Section Card

struct SFSectionCard<Content: View>: View {
    let title: String
    var variant: SFCardVariant = .outlined
    @ViewBuilder var content: () -> Content

    var body: some View {
        SFCard(variant: variant) {
            VStack(alignment: .leading, spacing: SyncFlowSpacing.sm) {
                Text(title)
                    .font(SyncFlowTypography.titleMedium)
                    .foregroundColor(SyncFlowColors.textPrimary)

                content()
            }
            .padding(SyncFlowPadding.card)
        }
    }
}

// MARK: - Highlight Card

struct SFHighlightCard<Content: View>: View {
    var accentColor: Color = SyncFlowColors.primary
    var backgroundColor: Color? = nil
    var onClick: (() -> Void)? = nil
    @ViewBuilder var content: () -> Content

    var body: some View {
        HStack(spacing: 0) {
            Rectangle()
                .fill(accentColor)
                .frame(width: 4)

            content()
                .padding(SyncFlowPadding.card)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(backgroundColor ?? accentColor.opacity(0.1))
        .clipShape(RoundedRectangle(cornerRadius: SyncFlowSpacing.radiusMd))
        .onTapGesture {
            onClick?()
        }
    }
}

// MARK: - List Card

struct SFListCard<Content: View>: View {
    var selected: Bool = false
    var onClick: (() -> Void)? = nil
    @ViewBuilder var content: () -> Content

    @State private var isHovered = false

    var body: some View {
        content()
            .background(
                RoundedRectangle(cornerRadius: SyncFlowSpacing.radiusMd)
                    .fill(listCardBackgroundColor)
            )
            .onHover { hovering in
                withAnimation(SFAnimations.micro) {
                    isHovered = hovering
                }
            }
            .onTapGesture {
                onClick?()
            }
    }

    private var listCardBackgroundColor: Color {
        if selected {
            return SyncFlowColors.warmHighlight
        } else if isHovered {
            return SyncFlowColors.hoverWarm
        } else {
            return .clear
        }
    }
}

// MARK: - Preview

#if DEBUG
struct SFCard_Previews: PreviewProvider {
    static var previews: some View {
        VStack(spacing: 16) {
            SFPaddedCard(variant: .elevated) {
                Text("Elevated Card")
            }

            SFPaddedCard(variant: .outlined) {
                Text("Outlined Card")
            }

            SFPaddedCard(variant: .filled) {
                Text("Filled Card")
            }

            SFSectionCard(title: "Section Title") {
                Text("Section content goes here")
            }

            SFHighlightCard(accentColor: SyncFlowColors.success) {
                Text("Highlight card with accent")
            }
        }
        .padding()
        .frame(width: 300)
    }
}
#endif
