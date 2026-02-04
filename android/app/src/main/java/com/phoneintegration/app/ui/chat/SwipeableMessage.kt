package com.phoneintegration.app.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
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

    LaunchedEffect(Unit) {
        // Reset animation when dismissed
    }

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

@Composable
private fun Box(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
    ) {
        content()
    }
}
