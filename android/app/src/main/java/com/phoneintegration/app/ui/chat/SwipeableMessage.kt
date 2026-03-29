package com.phoneintegration.app.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SwipeableMessageBubble(
    onSwipeReply: () -> Unit,
    content: @Composable () -> Unit
) {
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val swipeThreshold = 100f

    // Calculate reply icon visibility based on drag progress
    val dragProgress = (offsetX.value / swipeThreshold).coerceIn(0f, 1f)

    Box {
        // Reply arrow icon behind the message bubble
        if (offsetX.value > 0f) {
            Icon(
                imageVector = Icons.Default.Reply,
                contentDescription = "Reply",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp)
                    .size(24.dp)
                    .alpha(dragProgress)
                    .scale(0.5f + dragProgress * 0.5f)
            )
        }

        // Message bubble content with swipe offset
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (offsetX.value > swipeThreshold) {
                                    onSwipeReply()
                                }
                                offsetX.animateTo(0f)
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                offsetX.animateTo(0f)
                            }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            scope.launch {
                                val newOffset = offsetX.value + dragAmount
                                if (newOffset >= 0 && newOffset <= swipeThreshold * 1.5f) {
                                    offsetX.snapTo(newOffset)
                                }
                            }
                        }
                    )
                }
        ) {
            content()
        }
    }
}
