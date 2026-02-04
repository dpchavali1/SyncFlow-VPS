package com.phoneintegration.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneintegration.app.ui.theme.Spacing

/**
 * Badge variants
 */
enum class BadgeVariant {
    Primary,   // Blue
    Secondary, // Grey
    Error,     // Red
    Success,   // Green
    Warning    // Amber
}

/**
 * Unread count badge
 *
 * Shows notification count with:
 * - Circular shape for single digits
 * - Pill shape for 10+
 * - Maximum display of 99+
 */
@Composable
fun SyncFlowBadge(
    count: Int,
    modifier: Modifier = Modifier,
    variant: BadgeVariant = BadgeVariant.Primary,
    maxCount: Int = 99
) {
    if (count <= 0) return

    val displayText = if (count > maxCount) "$maxCount+" else count.toString()
    val backgroundColor = getVariantColor(variant)

    Box(
        modifier = modifier
            .widthIn(min = Spacing.badgeSize)
            .clip(RoundedCornerShape(Spacing.radiusFull))
            .background(backgroundColor)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = displayText,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Animated badge that scales in/out
 */
@Composable
fun SyncFlowAnimatedBadge(
    count: Int,
    modifier: Modifier = Modifier,
    variant: BadgeVariant = BadgeVariant.Primary,
    maxCount: Int = 99
) {
    AnimatedVisibility(
        visible = count > 0,
        enter = scaleIn() + fadeIn(),
        exit = scaleOut() + fadeOut(),
        modifier = modifier
    ) {
        SyncFlowBadge(
            count = count,
            variant = variant,
            maxCount = maxCount
        )
    }
}

/**
 * Simple dot indicator
 */
@Composable
fun SyncFlowDotBadge(
    visible: Boolean,
    modifier: Modifier = Modifier,
    variant: BadgeVariant = BadgeVariant.Primary,
    size: Dp = Spacing.badgeSmallSize
) {
    AnimatedVisibility(
        visible = visible,
        enter = scaleIn() + fadeIn(),
        exit = scaleOut() + fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(getVariantColor(variant))
        )
    }
}

/**
 * Status badge with text label
 */
@Composable
fun SyncFlowStatusBadge(
    text: String,
    modifier: Modifier = Modifier,
    variant: BadgeVariant = BadgeVariant.Primary
) {
    val backgroundColor = getVariantColor(variant).copy(alpha = 0.15f)
    val textColor = getVariantColor(variant)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Spacing.radiusSm))
            .background(backgroundColor)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text.uppercase(),
            color = textColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp
        )
    }
}

/**
 * Badge anchor - wraps content and positions badge
 */
@Composable
fun SyncFlowBadgeAnchor(
    badge: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    badgeAlignment: Alignment = Alignment.TopEnd,
    badgeOffset: Dp = Spacing.badgeOffset,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = modifier) {
        content()
        Box(
            modifier = Modifier
                .align(badgeAlignment)
                .offset(
                    x = if (badgeAlignment == Alignment.TopEnd || badgeAlignment == Alignment.BottomEnd) badgeOffset else (-badgeOffset),
                    y = if (badgeAlignment == Alignment.TopEnd || badgeAlignment == Alignment.TopStart) badgeOffset else (-badgeOffset)
                )
        ) {
            badge()
        }
    }
}

/**
 * Message type indicator (OTP, Transaction, etc.)
 */
@Composable
fun SyncFlowMessageTypeBadge(
    type: MessageType,
    modifier: Modifier = Modifier
) {
    val (text, color) = when (type) {
        MessageType.OTP -> "OTP" to Color(0xFF1976D2)
        MessageType.Transaction -> "Transaction" to Color(0xFF2E7D32)
        MessageType.Alert -> "Alert" to Color(0xFFF57C00)
        MessageType.Promotion -> "Promo" to Color(0xFF7B1FA2)
        MessageType.Normal -> return // Don't show badge for normal messages
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Spacing.radiusXs))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Message types for categorization
 */
enum class MessageType {
    Normal,
    OTP,
    Transaction,
    Alert,
    Promotion
}

/**
 * Get color for badge variant
 */
@Composable
private fun getVariantColor(variant: BadgeVariant): Color {
    return when (variant) {
        BadgeVariant.Primary -> MaterialTheme.colorScheme.primary
        BadgeVariant.Secondary -> MaterialTheme.colorScheme.secondary
        BadgeVariant.Error -> MaterialTheme.colorScheme.error
        BadgeVariant.Success -> Color(0xFF2E7D32)
        BadgeVariant.Warning -> Color(0xFFF57C00)
    }
}

/**
 * SIM indicator badge
 */
@Composable
fun SyncFlowSimBadge(
    simSlot: Int,
    modifier: Modifier = Modifier
) {
    val colors = listOf(
        Color(0xFF1976D2), // SIM 1 - Blue
        Color(0xFF7B1FA2), // SIM 2 - Purple
        Color(0xFF00796B)  // SIM 3 - Teal
    )

    val color = colors.getOrElse(simSlot - 1) { colors[0] }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Spacing.radiusXs))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 4.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "SIM$simSlot",
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
