package com.phoneintegration.app.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * SyncFlow Spacing System
 * Based on 4dp base unit for an 8-point grid system
 */
object Spacing {
    // ============================================
    // Core Spacing Scale (4dp base)
    // ============================================
    val none: Dp = 0.dp
    val xxxs: Dp = 2.dp    // Micro spacing
    val xxs: Dp = 4.dp     // 1x base
    val xs: Dp = 8.dp      // 2x base
    val sm: Dp = 12.dp     // 3x base
    val md: Dp = 16.dp     // 4x base (standard)
    val lg: Dp = 24.dp     // 6x base
    val xl: Dp = 32.dp     // 8x base
    val xxl: Dp = 48.dp    // 12x base
    val xxxl: Dp = 64.dp   // 16x base

    // ============================================
    // Screen Padding
    // ============================================
    val screenHorizontal: Dp = 16.dp
    val screenVertical: Dp = 16.dp
    val screenTop: Dp = 16.dp
    val screenBottom: Dp = 24.dp

    // ============================================
    // Card Padding
    // ============================================
    val cardHorizontal: Dp = 16.dp
    val cardVertical: Dp = 12.dp
    val cardInner: Dp = 12.dp

    // ============================================
    // List Item Spacing
    // ============================================
    val listItemHorizontal: Dp = 16.dp
    val listItemVertical: Dp = 12.dp
    val listItemGap: Dp = 12.dp       // Space between items
    val listItemContentGap: Dp = 12.dp // Space between avatar and text

    // ============================================
    // Message Bubble Spacing
    // ============================================
    val bubbleHorizontal: Dp = 14.dp
    val bubbleVertical: Dp = 10.dp
    val bubbleGap: Dp = 4.dp          // Space between bubbles
    val bubbleGroupGap: Dp = 16.dp    // Space between message groups
    val bubbleMaxWidth: Dp = 280.dp   // Max bubble width

    // ============================================
    // Input Field Spacing
    // ============================================
    val inputHorizontal: Dp = 16.dp
    val inputVertical: Dp = 12.dp
    val inputIconGap: Dp = 12.dp

    // ============================================
    // Button Spacing
    // ============================================
    val buttonHorizontal: Dp = 24.dp
    val buttonVertical: Dp = 12.dp
    val buttonIconGap: Dp = 8.dp
    val buttonGroupGap: Dp = 8.dp     // Space between buttons

    // ============================================
    // Icon Sizes
    // ============================================
    val iconXs: Dp = 16.dp
    val iconSm: Dp = 20.dp
    val iconMd: Dp = 24.dp
    val iconLg: Dp = 32.dp
    val iconXl: Dp = 48.dp

    // ============================================
    // Avatar Sizes
    // ============================================
    val avatarSm: Dp = 32.dp
    val avatarMd: Dp = 40.dp
    val avatarLg: Dp = 48.dp
    val avatarXl: Dp = 64.dp
    val avatarXxl: Dp = 80.dp

    // ============================================
    // Corner Radius
    // ============================================
    val radiusNone: Dp = 0.dp
    val radiusXs: Dp = 4.dp
    val radiusSm: Dp = 8.dp
    val radiusMd: Dp = 12.dp
    val radiusLg: Dp = 16.dp
    val radiusXl: Dp = 24.dp
    val radiusFull: Dp = 999.dp       // Pill shape

    // Message bubble specific radius
    val bubbleRadius: Dp = 20.dp
    val bubbleCornerSmall: Dp = 6.dp  // Small corner for consecutive messages

    // ============================================
    // Elevation (Shadow)
    // ============================================
    val elevationNone: Dp = 0.dp
    val elevation1: Dp = 1.dp
    val elevation2: Dp = 2.dp
    val elevation3: Dp = 4.dp
    val elevation4: Dp = 6.dp
    val elevation5: Dp = 8.dp

    // ============================================
    // Divider
    // ============================================
    val dividerHeight: Dp = 1.dp
    val dividerInsetStart: Dp = 72.dp  // For list items with avatars

    // ============================================
    // FAB
    // ============================================
    val fabSize: Dp = 56.dp
    val fabSmallSize: Dp = 40.dp
    val fabMargin: Dp = 16.dp

    // ============================================
    // Bottom Bar
    // ============================================
    val bottomBarHeight: Dp = 64.dp
    val bottomBarPadding: Dp = 8.dp

    // ============================================
    // Top Bar
    // ============================================
    val topBarHeight: Dp = 64.dp
    val topBarHorizontalPadding: Dp = 4.dp

    // ============================================
    // Chip
    // ============================================
    val chipHeight: Dp = 32.dp
    val chipHorizontalPadding: Dp = 12.dp
    val chipGap: Dp = 8.dp
    val chipIconSize: Dp = 18.dp

    // ============================================
    // Badge
    // ============================================
    val badgeSize: Dp = 20.dp
    val badgeSmallSize: Dp = 8.dp    // Dot indicator
    val badgeOffset: Dp = (-4).dp    // Overlap offset

    // ============================================
    // Progress Indicator
    // ============================================
    val progressStrokeWidth: Dp = 4.dp
    val progressSize: Dp = 48.dp
    val progressSmallSize: Dp = 24.dp

    // ============================================
    // Touch Targets (Accessibility)
    // ============================================
    val minTouchTarget: Dp = 48.dp   // Minimum for accessibility
    val comfortableTouchTarget: Dp = 56.dp
}

/**
 * Common padding values for convenience
 */
object SyncFlowPadding {
    val screen = PaddingValues(
        horizontal = Spacing.screenHorizontal,
        vertical = Spacing.screenVertical
    )

    val card = PaddingValues(
        horizontal = Spacing.cardHorizontal,
        vertical = Spacing.cardVertical
    )

    val listItem = PaddingValues(
        horizontal = Spacing.listItemHorizontal,
        vertical = Spacing.listItemVertical
    )

    val button = PaddingValues(
        horizontal = Spacing.buttonHorizontal,
        vertical = Spacing.buttonVertical
    )

    val chip = PaddingValues(
        horizontal = Spacing.chipHorizontalPadding,
        vertical = Spacing.xxs
    )

    val bubble = PaddingValues(
        horizontal = Spacing.bubbleHorizontal,
        vertical = Spacing.bubbleVertical
    )

    val input = PaddingValues(
        horizontal = Spacing.inputHorizontal,
        vertical = Spacing.inputVertical
    )
}

/**
 * Extension for local composition
 */
data class SyncFlowSpacing(
    val xs: Dp = Spacing.xs,
    val sm: Dp = Spacing.sm,
    val md: Dp = Spacing.md,
    val lg: Dp = Spacing.lg,
    val xl: Dp = Spacing.xl
)

val LocalSpacing = staticCompositionLocalOf { SyncFlowSpacing() }

/**
 * Convenient access to spacing in composables
 */
val spacing: SyncFlowSpacing
    @Composable
    @ReadOnlyComposable
    get() = LocalSpacing.current
