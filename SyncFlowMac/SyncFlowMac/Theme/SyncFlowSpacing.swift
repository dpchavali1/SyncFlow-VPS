//
//  SyncFlowSpacing.swift
//  SyncFlowMac
//
//  Spacing system for SyncFlow macOS app
//  Based on 4pt base unit for an 8-point grid system
//

import SwiftUI

// MARK: - SyncFlow Spacing

struct SyncFlowSpacing {

    // ============================================
    // Core Spacing Scale (4pt base)
    // ============================================

    static let none: CGFloat = 0
    static let xxxs: CGFloat = 2      // Micro spacing
    static let xxs: CGFloat = 4       // 1x base
    static let xs: CGFloat = 8        // 2x base
    static let sm: CGFloat = 12       // 3x base
    static let md: CGFloat = 16       // 4x base (standard)
    static let lg: CGFloat = 24       // 6x base
    static let xl: CGFloat = 32       // 8x base
    static let xxl: CGFloat = 48      // 12x base
    static let xxxl: CGFloat = 64     // 16x base

    // ============================================
    // Window Padding
    // ============================================

    static let windowHorizontal: CGFloat = 16
    static let windowVertical: CGFloat = 16
    static let windowTop: CGFloat = 16
    static let windowBottom: CGFloat = 24

    // ============================================
    // Sidebar
    // ============================================

    static let sidebarWidth: CGFloat = 280
    static let sidebarMinWidth: CGFloat = 200
    static let sidebarMaxWidth: CGFloat = 400
    static let sidebarPadding: CGFloat = 12

    // ============================================
    // Card Padding
    // ============================================

    static let cardHorizontal: CGFloat = 16
    static let cardVertical: CGFloat = 12
    static let cardInner: CGFloat = 12

    // ============================================
    // List Item Spacing
    // ============================================

    static let listItemHorizontal: CGFloat = 12
    static let listItemVertical: CGFloat = 10
    static let listItemGap: CGFloat = 8        // Space between items
    static let listItemContentGap: CGFloat = 10 // Space between avatar and text

    // ============================================
    // Message Bubble Spacing
    // ============================================

    static let bubbleHorizontal: CGFloat = 12
    static let bubbleVertical: CGFloat = 8
    static let bubbleGap: CGFloat = 4          // Space between bubbles
    static let bubbleGroupGap: CGFloat = 12    // Space between message groups
    static let bubbleMaxWidth: CGFloat = 320   // Max bubble width

    // ============================================
    // Input Field Spacing
    // ============================================

    static let inputHorizontal: CGFloat = 12
    static let inputVertical: CGFloat = 8
    static let inputIconGap: CGFloat = 8
    static let inputHeight: CGFloat = 32       // Standard macOS text field height

    // ============================================
    // Button Spacing
    // ============================================

    static let buttonHorizontal: CGFloat = 16
    static let buttonVertical: CGFloat = 8
    static let buttonIconGap: CGFloat = 6
    static let buttonGroupGap: CGFloat = 8     // Space between buttons
    static let buttonHeight: CGFloat = 28      // Standard macOS button height

    // ============================================
    // Icon Sizes
    // ============================================

    static let iconXs: CGFloat = 12
    static let iconSm: CGFloat = 16
    static let iconMd: CGFloat = 20
    static let iconLg: CGFloat = 24
    static let iconXl: CGFloat = 32

    // ============================================
    // Avatar Sizes
    // ============================================

    static let avatarSm: CGFloat = 28
    static let avatarMd: CGFloat = 36
    static let avatarLg: CGFloat = 44
    static let avatarXl: CGFloat = 56
    static let avatarXxl: CGFloat = 72

    // ============================================
    // Corner Radius
    // ============================================

    static let radiusNone: CGFloat = 0
    static let radiusXs: CGFloat = 4
    static let radiusSm: CGFloat = 6
    static let radiusMd: CGFloat = 8
    static let radiusLg: CGFloat = 12
    static let radiusXl: CGFloat = 16
    static let radiusFull: CGFloat = 999       // Pill shape

    // Message bubble specific radius
    static let bubbleRadius: CGFloat = 16
    static let bubbleCornerSmall: CGFloat = 4  // Small corner for consecutive messages

    // ============================================
    // Toolbar & Navigation
    // ============================================

    static let toolbarHeight: CGFloat = 52
    static let toolbarPadding: CGFloat = 12
    static let navigationBarHeight: CGFloat = 44

    // ============================================
    // Menu Bar
    // ============================================

    static let menuBarPopoverWidth: CGFloat = 320
    static let menuBarPopoverMaxHeight: CGFloat = 480
    static let menuBarItemHeight: CGFloat = 36
    static let menuBarPadding: CGFloat = 8

    // ============================================
    // Chip/Tag
    // ============================================

    static let chipHeight: CGFloat = 24
    static let chipHorizontalPadding: CGFloat = 10
    static let chipGap: CGFloat = 6
    static let chipIconSize: CGFloat = 14

    // ============================================
    // Badge
    // ============================================

    static let badgeSize: CGFloat = 18
    static let badgeSmallSize: CGFloat = 8     // Dot indicator
    static let badgeOffset: CGFloat = -4       // Overlap offset

    // ============================================
    // Divider
    // ============================================

    static let dividerHeight: CGFloat = 1
    static let dividerInsetStart: CGFloat = 56 // For list items with avatars

    // ============================================
    // FAB (Floating Action Button)
    // ============================================

    static let fabSize: CGFloat = 56
    static let fabSmallSize: CGFloat = 40
    static let fabMargin: CGFloat = 16

    // ============================================
    // Call UI
    // ============================================

    static let callButtonSize: CGFloat = 56
    static let callButtonSmall: CGFloat = 44
    static let callButtonSpacing: CGFloat = 24
    static let callAvatarSize: CGFloat = 96

    // ============================================
    // Dialer
    // ============================================

    static let dialPadButtonSize: CGFloat = 64
    static let dialPadSpacing: CGFloat = 16
    static let dialPadLetterSpacing: CGFloat = 2

    // ============================================
    // Animation Durations (seconds)
    // ============================================

    static let animationFast: Double = 0.15
    static let animationNormal: Double = 0.25
    static let animationSlow: Double = 0.35

    // ============================================
    // Touch Targets (Accessibility)
    // ============================================

    static let minTouchTarget: CGFloat = 44    // Minimum for accessibility
    static let comfortableTouchTarget: CGFloat = 48
}

// MARK: - Edge Insets Convenience

struct SyncFlowPadding {
    static let window = EdgeInsets(
        top: SyncFlowSpacing.windowTop,
        leading: SyncFlowSpacing.windowHorizontal,
        bottom: SyncFlowSpacing.windowBottom,
        trailing: SyncFlowSpacing.windowHorizontal
    )

    static let card = EdgeInsets(
        top: SyncFlowSpacing.cardVertical,
        leading: SyncFlowSpacing.cardHorizontal,
        bottom: SyncFlowSpacing.cardVertical,
        trailing: SyncFlowSpacing.cardHorizontal
    )

    static let listItem = EdgeInsets(
        top: SyncFlowSpacing.listItemVertical,
        leading: SyncFlowSpacing.listItemHorizontal,
        bottom: SyncFlowSpacing.listItemVertical,
        trailing: SyncFlowSpacing.listItemHorizontal
    )

    static let button = EdgeInsets(
        top: SyncFlowSpacing.buttonVertical,
        leading: SyncFlowSpacing.buttonHorizontal,
        bottom: SyncFlowSpacing.buttonVertical,
        trailing: SyncFlowSpacing.buttonHorizontal
    )

    static let chip = EdgeInsets(
        top: SyncFlowSpacing.xxs,
        leading: SyncFlowSpacing.chipHorizontalPadding,
        bottom: SyncFlowSpacing.xxs,
        trailing: SyncFlowSpacing.chipHorizontalPadding
    )

    static let bubble = EdgeInsets(
        top: SyncFlowSpacing.bubbleVertical,
        leading: SyncFlowSpacing.bubbleHorizontal,
        bottom: SyncFlowSpacing.bubbleVertical,
        trailing: SyncFlowSpacing.bubbleHorizontal
    )

    static let input = EdgeInsets(
        top: SyncFlowSpacing.inputVertical,
        leading: SyncFlowSpacing.inputHorizontal,
        bottom: SyncFlowSpacing.inputVertical,
        trailing: SyncFlowSpacing.inputHorizontal
    )
}

// MARK: - View Modifiers

extension View {
    func windowPadding() -> some View {
        self.padding(SyncFlowPadding.window)
    }

    func cardPadding() -> some View {
        self.padding(SyncFlowPadding.card)
    }

    func listItemPadding() -> some View {
        self.padding(SyncFlowPadding.listItem)
    }

    func bubblePadding() -> some View {
        self.padding(SyncFlowPadding.bubble)
    }

    func standardCornerRadius() -> some View {
        self.cornerRadius(SyncFlowSpacing.radiusMd)
    }

    func bubbleCornerRadius() -> some View {
        self.cornerRadius(SyncFlowSpacing.bubbleRadius)
    }
}
