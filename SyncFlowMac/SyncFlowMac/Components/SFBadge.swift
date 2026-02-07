//
//  SFBadge.swift
//  SyncFlowMac
//
//  Reusable badge components for SyncFlow macOS app
//

import SwiftUI

// MARK: - Badge Variant

enum SFBadgeVariant {
    case primary
    case secondary
    case error
    case success
    case warning

    var color: Color {
        switch self {
        case .primary: return SyncFlowColors.primary
        case .secondary: return SyncFlowColors.secondary
        case .error: return SyncFlowColors.error
        case .success: return SyncFlowColors.success
        case .warning: return SyncFlowColors.warning
        }
    }
}

// MARK: - Count Badge

struct SFBadge: View {
    let count: Int
    var variant: SFBadgeVariant = .primary
    var maxCount: Int = 99

    var body: some View {
        if count > 0 {
            Text(displayText)
                .font(.system(size: 10, weight: .bold))
                .foregroundColor(.white)
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .frame(minWidth: SyncFlowSpacing.badgeSize)
                .background(variant.color)
                .clipShape(Capsule())
        }
    }

    private var displayText: String {
        count > maxCount ? "\(maxCount)+" : "\(count)"
    }
}

// MARK: - Animated Badge

struct SFAnimatedBadge: View {
    let count: Int
    var variant: SFBadgeVariant = .primary
    var maxCount: Int = 99

    var body: some View {
        if count > 0 {
            SFBadge(count: count, variant: variant, maxCount: maxCount)
                .transition(.scale.combined(with: .opacity))
        }
    }
}

// MARK: - Dot Badge

struct SFDotBadge: View {
    var visible: Bool = true
    var variant: SFBadgeVariant = .primary
    var size: CGFloat = SyncFlowSpacing.badgeSmallSize

    var body: some View {
        if visible {
            Circle()
                .fill(variant.color)
                .frame(width: size, height: size)
                .transition(.scale.combined(with: .opacity))
        }
    }
}

// MARK: - Status Badge

struct SFStatusBadge: View {
    let text: String
    var variant: SFBadgeVariant = .primary

    var body: some View {
        Text(text.uppercased())
            .font(.system(size: 10, weight: .semibold))
            .foregroundColor(variant.color)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(variant.color.opacity(0.15))
            .clipShape(RoundedRectangle(cornerRadius: SyncFlowSpacing.radiusSm))
    }
}

// MARK: - Message Type Badge

enum SFMessageType {
    case normal
    case otp
    case transaction
    case alert
    case promotion

    var text: String {
        switch self {
        case .normal: return ""
        case .otp: return "OTP"
        case .transaction: return "Transaction"
        case .alert: return "Alert"
        case .promotion: return "Promo"
        }
    }

    var color: Color {
        switch self {
        case .normal: return .clear
        case .otp: return SyncFlowColors.otpAccent
        case .transaction: return SyncFlowColors.transactionAccent
        case .alert: return SyncFlowColors.alertAccent
        case .promotion: return SyncFlowColors.promotionAccent
        }
    }
}

struct SFMessageTypeBadge: View {
    let type: SFMessageType

    var body: some View {
        if type != .normal {
            Text(type.text)
                .font(.system(size: 9, weight: .semibold))
                .foregroundColor(type.color)
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .background(type.color.opacity(0.12))
                .clipShape(RoundedRectangle(cornerRadius: SyncFlowSpacing.radiusXs))
        }
    }
}

// MARK: - SIM Badge

struct SFSimBadge: View {
    let simSlot: Int

    private var color: Color {
        switch simSlot {
        case 1: return SyncFlowColors.simSlot1
        case 2: return SyncFlowColors.simSlot2
        case 3: return SyncFlowColors.simSlot3
        default: return SyncFlowColors.simSlot1
        }
    }

    var body: some View {
        Text("SIM\(simSlot)")
            .font(.system(size: 9, weight: .medium))
            .foregroundColor(color)
            .padding(.horizontal, 4)
            .padding(.vertical, 1)
            .background(color.opacity(0.15))
            .clipShape(RoundedRectangle(cornerRadius: SyncFlowSpacing.radiusXs))
    }
}

// MARK: - Badge Anchor

struct SFBadgeAnchor<Content: View, Badge: View>: View {
    var alignment: Alignment = .topTrailing
    var offset: CGPoint = CGPoint(x: SyncFlowSpacing.badgeOffset, y: SyncFlowSpacing.badgeOffset)
    @ViewBuilder var badge: () -> Badge
    @ViewBuilder var content: () -> Content

    var body: some View {
        ZStack(alignment: alignment) {
            content()

            badge()
                .offset(x: offset.x, y: offset.y)
        }
    }
}

// MARK: - Preview

#if DEBUG
struct SFBadge_Previews: PreviewProvider {
    static var previews: some View {
        VStack(spacing: 20) {
            HStack(spacing: 16) {
                SFBadge(count: 5)
                SFBadge(count: 42)
                SFBadge(count: 100)
                SFBadge(count: 3, variant: .error)
            }

            HStack(spacing: 16) {
                SFDotBadge()
                SFDotBadge(variant: .error)
                SFDotBadge(variant: .success)
                SFDotBadge(variant: .warning)
            }

            HStack(spacing: 12) {
                SFStatusBadge(text: "New", variant: .primary)
                SFStatusBadge(text: "Pending", variant: .warning)
                SFStatusBadge(text: "Error", variant: .error)
            }

            HStack(spacing: 12) {
                SFMessageTypeBadge(type: .otp)
                SFMessageTypeBadge(type: .transaction)
                SFMessageTypeBadge(type: .alert)
            }

            HStack(spacing: 12) {
                SFSimBadge(simSlot: 1)
                SFSimBadge(simSlot: 2)
            }

            // Badge anchor example
            SFBadgeAnchor(
                badge: { SFBadge(count: 5) },
                content: {
                    Image(systemName: "bell.fill")
                        .font(.system(size: 24))
                        .foregroundColor(SyncFlowColors.textPrimary)
                }
            )
        }
        .padding()
    }
}
#endif
