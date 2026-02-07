//
//  SFEmptyState.swift
//  SyncFlowMac
//
//  Reusable empty state components for SyncFlow macOS app
//

import SwiftUI

// MARK: - Empty State Type

enum SFEmptyStateType {
    case noMessages
    case noConversations
    case noContacts
    case noSearchResults
    case noHistory
    case offline
    case error

    var icon: String {
        switch self {
        case .noMessages: return "tray"
        case .noConversations: return "bubble.left.and.bubble.right"
        case .noContacts: return "person.2"
        case .noSearchResults: return "magnifyingglass"
        case .noHistory: return "clock.arrow.circlepath"
        case .offline: return "wifi.slash"
        case .error: return "exclamationmark.triangle"
        }
    }

    var title: String {
        switch self {
        case .noMessages: return "No Messages"
        case .noConversations: return "No Conversations"
        case .noContacts: return "No Contacts"
        case .noSearchResults: return "No Results"
        case .noHistory: return "No History"
        case .offline: return "No Connection"
        case .error: return "Something Went Wrong"
        }
    }

    var message: String {
        switch self {
        case .noMessages: return "You don't have any messages yet"
        case .noConversations: return "Start a new conversation to see it here"
        case .noContacts: return "Add contacts to get started"
        case .noSearchResults: return "Try a different search term"
        case .noHistory: return "Your history will appear here"
        case .offline: return "Check your internet connection and try again"
        case .error: return "An error occurred. Please try again"
        }
    }
}

// MARK: - Empty State

struct SFEmptyState: View {
    var type: SFEmptyStateType
    var title: String? = nil
    var message: String? = nil
    var icon: String? = nil
    var iconColor: Color = SyncFlowColors.primary.opacity(0.6)
    var action: (() -> Void)? = nil
    var actionText: String? = nil

    var body: some View {
        VStack(spacing: SyncFlowSpacing.lg) {
            Image(systemName: icon ?? type.icon)
                .font(.system(size: 64, weight: .light))
                .foregroundColor(iconColor)

            VStack(spacing: SyncFlowSpacing.xs) {
                Text(title ?? type.title)
                    .font(SyncFlowTypography.titleLarge)
                    .foregroundColor(SyncFlowColors.textPrimary)

                Text(message ?? type.message)
                    .font(SyncFlowTypography.bodyMedium)
                    .foregroundColor(SyncFlowColors.textSecondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, SyncFlowSpacing.lg)
            }

            if let action = action, let actionText = actionText {
                SFPrimaryButton(text: actionText, action: action)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(SyncFlowSpacing.xxl)
    }
}

// MARK: - Animated Empty State

struct SFAnimatedEmptyState: View {
    var visible: Bool
    var type: SFEmptyStateType
    var title: String? = nil
    var message: String? = nil
    var action: (() -> Void)? = nil
    var actionText: String? = nil

    var body: some View {
        if visible {
            SFEmptyState(
                type: type,
                title: title,
                message: message,
                action: action,
                actionText: actionText
            )
            .transition(.opacity.combined(with: .scale(scale: 0.95)))
        }
    }
}

// MARK: - Error State

struct SFErrorState: View {
    var title: String = "Something Went Wrong"
    var message: String
    var onRetry: () -> Void

    var body: some View {
        SFEmptyState(
            type: .error,
            title: title,
            message: message,
            iconColor: SyncFlowColors.error.opacity(0.7),
            action: onRetry,
            actionText: "Try Again"
        )
    }
}

// MARK: - Offline State

struct SFOfflineState: View {
    var onRetry: () -> Void

    var body: some View {
        SFEmptyState(
            type: .offline,
            action: onRetry,
            actionText: "Retry"
        )
    }
}

// MARK: - Search Empty State

struct SFSearchEmptyState: View {
    var query: String
    var onClear: (() -> Void)? = nil

    var body: some View {
        SFEmptyState(
            type: .noSearchResults,
            message: "No results found for \"\(query)\"",
            action: onClear,
            actionText: onClear != nil ? "Clear Search" : nil
        )
    }
}

// MARK: - Loading Placeholder

struct SFLoadingPlaceholder: View {
    var text: String = "Loading..."

    var body: some View {
        VStack(spacing: SyncFlowSpacing.md) {
            ProgressView()
                .scaleEffect(1.2)

            Text(text)
                .font(SyncFlowTypography.bodyMedium)
                .foregroundColor(SyncFlowColors.textSecondary)
        }
        .frame(maxWidth: .infinity)
        .padding(SyncFlowSpacing.xxl)
    }
}

// MARK: - Skeleton Row

struct SFSkeletonRow: View {
    @State private var isAnimating = false

    var body: some View {
        HStack(spacing: SyncFlowSpacing.listItemContentGap) {
            // Avatar skeleton
            Circle()
                .fill(skeletonGradient)
                .frame(width: 36, height: 36)

            VStack(alignment: .leading, spacing: SyncFlowSpacing.xxs) {
                // Title skeleton
                RoundedRectangle(cornerRadius: 4)
                    .fill(skeletonGradient)
                    .frame(width: 140, height: 14)

                // Subtitle skeleton
                RoundedRectangle(cornerRadius: 4)
                    .fill(skeletonGradient)
                    .frame(width: 200, height: 12)
            }

            Spacer()

            // Timestamp skeleton
            RoundedRectangle(cornerRadius: 4)
                .fill(skeletonGradient)
                .frame(width: 50, height: 10)
        }
        .padding(SyncFlowPadding.listItem)
        .onAppear {
            withAnimation(.easeInOut(duration: 1.0).repeatForever(autoreverses: true)) {
                isAnimating = true
            }
        }
    }

    private var skeletonGradient: LinearGradient {
        LinearGradient(
            colors: [
                SyncFlowColors.surfaceTertiary.opacity(isAnimating ? 0.3 : 0.6),
                SyncFlowColors.surfaceTertiary.opacity(isAnimating ? 0.6 : 0.3)
            ],
            startPoint: .leading,
            endPoint: .trailing
        )
    }
}

// MARK: - Skeleton List

struct SFSkeletonList: View {
    var count: Int = 5

    var body: some View {
        VStack(spacing: 0) {
            ForEach(0..<count, id: \.self) { _ in
                SFSkeletonRow()
            }
        }
    }
}

// MARK: - Preview

#if DEBUG
struct SFEmptyState_Previews: PreviewProvider {
    static var previews: some View {
        VStack(spacing: 32) {
            SFEmptyState(type: .noMessages)

            SFEmptyState(
                type: .noSearchResults,
                message: "No results for \"hello\"",
                action: {},
                actionText: "Clear Search"
            )

            SFErrorState(
                message: "Failed to load messages",
                onRetry: {}
            )

            SFSkeletonList(count: 3)
        }
        .frame(width: 400)
        .padding()
    }
}
#endif
