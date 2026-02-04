package com.phoneintegration.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Skeleton shimmer effect for loading states
 */
@Composable
fun shimmerBrush(
    showShimmer: Boolean = true,
    targetValue: Float = 1000f
): Brush {
    return if (showShimmer) {
        val shimmerColors = listOf(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        )

        val transition = rememberInfiniteTransition(label = "shimmer")
        val translateAnimation by transition.animateFloat(
            initialValue = 0f,
            targetValue = targetValue,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 1000,
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Restart
            ),
            label = "shimmer"
        )

        Brush.linearGradient(
            colors = shimmerColors,
            start = Offset.Zero,
            end = Offset(x = translateAnimation, y = translateAnimation)
        )
    } else {
        Brush.linearGradient(
            colors = listOf(Color.Transparent, Color.Transparent)
        )
    }
}

/**
 * Skeleton box for loading placeholders
 */
@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    width: Dp = 100.dp,
    height: Dp = 20.dp,
    cornerRadius: Dp = 4.dp
) {
    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(cornerRadius))
            .background(shimmerBrush())
    )
}

/**
 * Circular skeleton for avatars
 */
@Composable
fun SkeletonCircle(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(shimmerBrush())
    )
}

/**
 * Skeleton for conversation list item
 */
@Composable
fun ConversationSkeleton(
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar skeleton
        SkeletonCircle(size = 48.dp)

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            // Name skeleton
            SkeletonBox(
                width = 140.dp,
                height = 18.dp,
                modifier = Modifier.padding(bottom = 6.dp)
            )

            // Last message skeleton
            SkeletonBox(
                width = 200.dp,
                height = 14.dp
            )
        }

        // Timestamp skeleton
        SkeletonBox(
            width = 50.dp,
            height = 12.dp
        )
    }
}

/**
 * Skeleton list for loading conversations
 */
@Composable
fun ConversationListSkeleton(
    modifier: Modifier = Modifier,
    count: Int = 8
) {
    Column(modifier = modifier) {
        repeat(count) {
            ConversationSkeleton()
        }
    }
}

/**
 * Skeleton for message bubble
 */
@Composable
fun MessageBubbleSkeleton(
    modifier: Modifier = Modifier,
    isOutgoing: Boolean = false,
    width: Dp = 200.dp
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        contentAlignment = if (isOutgoing) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column {
            // Message body skeleton
            Box(
                modifier = Modifier
                    .width(width)
                    .height(40.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(shimmerBrush())
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Timestamp skeleton
            SkeletonBox(
                width = 60.dp,
                height = 10.dp,
                modifier = Modifier.align(if (isOutgoing) Alignment.End else Alignment.Start)
            )
        }
    }
}

/**
 * Skeleton for message list
 */
@Composable
fun MessageListSkeleton(
    modifier: Modifier = Modifier,
    count: Int = 10
) {
    Column(modifier = modifier.fillMaxSize()) {
        repeat(count) { index ->
            MessageBubbleSkeleton(
                isOutgoing = index % 3 == 0,
                width = when (index % 4) {
                    0 -> 160.dp
                    1 -> 220.dp
                    2 -> 180.dp
                    else -> 200.dp
                }
            )
        }
    }
}

/**
 * Skeleton for contact/detail view
 */
@Composable
fun ContactDetailSkeleton(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Avatar
        SkeletonCircle(size = 96.dp)

        Spacer(modifier = Modifier.height(16.dp))

        // Name
        SkeletonBox(
            width = 160.dp,
            height = 24.dp
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Phone number
        SkeletonBox(
            width = 120.dp,
            height = 16.dp
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Action buttons
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SkeletonCircle(size = 48.dp)
            SkeletonCircle(size = 48.dp)
            SkeletonCircle(size = 48.dp)
        }
    }
}

/**
 * Generic loading placeholder with shimmer
 */
@Composable
fun ShimmerPlaceholder(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier.background(shimmerBrush())
    ) {
        content()
    }
}
