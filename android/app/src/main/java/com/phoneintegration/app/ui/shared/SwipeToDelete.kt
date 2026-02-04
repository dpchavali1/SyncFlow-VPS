package com.phoneintegration.app.ui.shared

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDelete(
    onDelete: () -> Unit,
    content: @Composable () -> Unit
) {
    val state = rememberSwipeToDismissBoxState()

    if (state.currentValue == SwipeToDismissBoxValue.EndToStart) {
        onDelete()
    }

    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            DeleteBackground(state)
        },
        content = {
            content()
        }
    )
}

@Composable
private fun DeleteBackground(state: SwipeToDismissBoxState) {
    val progress = animateFloatAsState(
        if (state.targetValue == SwipeToDismissBoxValue.EndToStart) 1f else 0f,
        label = "deleteBackground"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Red.copy(alpha = 0.2f)),
        contentAlignment = Alignment.CenterEnd
    ) {
        Icon(
            imageVector = Icons.Default.Delete,
            contentDescription = "Delete",
            tint = Color.Red,
            modifier = Modifier
                .padding(end = 24.dp)
                .scale(progress.value)
        )
    }
}
