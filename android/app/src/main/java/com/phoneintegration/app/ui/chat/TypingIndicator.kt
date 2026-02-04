package com.phoneintegration.app.ui.chat

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp

/**
 * Animated typing indicator showing when someone is typing on another device
 */
@Composable
fun TypingIndicator(
    contactName: String? = null,
    device: String = "desktop",
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Bubble with animated dots
        Box(
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Three animated dots
                repeat(3) { index ->
                    AnimatedDot(delayMillis = index * 150)
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Label
        Text(
            text = "${contactName ?: "Someone"} is typing from $device...",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AnimatedDot(delayMillis: Int) {
    val infiniteTransition = rememberInfiniteTransition(label = "dot")

    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1200
                0.3f at 0
                1f at 300
                0.3f at 600
                0.3f at 1200
            },
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(delayMillis)
        ),
        label = "alpha"
    )

    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1200
                0.8f at 0
                1f at 300
                0.8f at 600
                0.8f at 1200
            },
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(delayMillis)
        ),
        label = "scale"
    )

    Box(
        modifier = Modifier
            .size((8 * scale).dp)
            .alpha(alpha)
            .background(
                MaterialTheme.colorScheme.onSurfaceVariant,
                CircleShape
            )
    )
}

/**
 * Compact typing indicator for conversation list
 */
@Composable
fun CompactTypingIndicator(
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            SmallAnimatedDot(delayMillis = index * 100)
        }
    }
}

@Composable
private fun SmallAnimatedDot(delayMillis: Int) {
    val infiniteTransition = rememberInfiniteTransition(label = "small_dot")

    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(300),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(delayMillis)
        ),
        label = "alpha"
    )

    Box(
        modifier = Modifier
            .size(4.dp)
            .alpha(alpha)
            .background(
                MaterialTheme.colorScheme.primary,
                CircleShape
            )
    )
}
