package com.phoneintegration.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phoneintegration.app.ui.theme.Spacing
import com.phoneintegration.app.ui.theme.SyncFlowPadding

/**
 * Card variants for different use cases
 */
enum class CardVariant {
    Elevated,   // Shadow elevation
    Outlined,   // Border outline
    Filled,     // Solid background
    Flat        // No elevation, minimal styling
}

/**
 * SyncFlow Card Component
 *
 * A consistent card component with:
 * - Multiple variants (elevated, outlined, filled, flat)
 * - Customizable corners and padding
 * - Optional click handling with ripple
 * - Hover and press states
 */
@Composable
fun SyncFlowCard(
    modifier: Modifier = Modifier,
    variant: CardVariant = CardVariant.Elevated,
    shape: Shape = RoundedCornerShape(Spacing.radiusMd),
    elevation: Dp = 2.dp,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()

    val containerColor = when (variant) {
        CardVariant.Elevated -> MaterialTheme.colorScheme.surface
        CardVariant.Outlined -> MaterialTheme.colorScheme.surface
        CardVariant.Filled -> MaterialTheme.colorScheme.surfaceVariant
        CardVariant.Flat -> Color.Transparent
    }

    val targetColor = when {
        isPressed -> containerColor.copy(alpha = 0.88f)
        isHovered -> containerColor.copy(alpha = 0.95f)
        else -> containerColor
    }

    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 150),
        label = "card_color"
    )

    val border = when (variant) {
        CardVariant.Outlined -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        else -> null
    }

    val cardElevation = when (variant) {
        CardVariant.Elevated -> elevation
        else -> 0.dp
    }

    Card(
        modifier = modifier
            .then(
                if (onClick != null && enabled) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            ),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = animatedColor,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = cardElevation,
            pressedElevation = cardElevation + 2.dp,
            hoveredElevation = cardElevation + 1.dp
        ),
        border = border,
        content = content
    )
}

/**
 * Card with preset padding
 */
@Composable
fun SyncFlowPaddedCard(
    modifier: Modifier = Modifier,
    variant: CardVariant = CardVariant.Elevated,
    shape: Shape = RoundedCornerShape(Spacing.radiusMd),
    elevation: Dp = 2.dp,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    SyncFlowCard(
        modifier = modifier,
        variant = variant,
        shape = shape,
        elevation = elevation,
        onClick = onClick,
        enabled = enabled
    ) {
        Column(
            modifier = Modifier.padding(SyncFlowPadding.card),
            content = content
        )
    }
}

/**
 * Full-width card for list items
 */
@Composable
fun SyncFlowListCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val backgroundColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    } else {
        Color.Transparent
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Spacing.radiusSm))
            .then(
                if (onClick != null && enabled) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            ),
        color = backgroundColor,
        content = { Column(content = content) }
    )
}

/**
 * Highlight card for important content (OTPs, transactions, etc.)
 */
@Composable
fun SyncFlowHighlightCard(
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    backgroundColor: Color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Spacing.radiusMd))
            .background(backgroundColor)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
    ) {
        // Left accent border
        Box(
            modifier = Modifier
                .padding(start = 0.dp)
                .background(
                    accentColor,
                    RoundedCornerShape(
                        topStart = Spacing.radiusMd,
                        bottomStart = Spacing.radiusMd
                    )
                )
        )

        Column(
            modifier = Modifier.padding(SyncFlowPadding.card),
            content = content
        )
    }
}

/**
 * Section card with header
 */
@Composable
fun SyncFlowSectionCard(
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
    variant: CardVariant = CardVariant.Outlined,
    content: @Composable ColumnScope.() -> Unit
) {
    SyncFlowCard(
        modifier = modifier.fillMaxWidth(),
        variant = variant
    ) {
        Column(
            modifier = Modifier.padding(SyncFlowPadding.card)
        ) {
            title()
            Column(
                modifier = Modifier.padding(top = Spacing.sm),
                content = content
            )
        }
    }
}
