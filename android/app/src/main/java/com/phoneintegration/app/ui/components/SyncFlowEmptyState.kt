package com.phoneintegration.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phoneintegration.app.ui.theme.Spacing

/**
 * Empty state types with default icons and messages
 */
enum class EmptyStateType(
    val icon: ImageVector,
    val title: String,
    val message: String
) {
    NoMessages(
        Icons.Outlined.Inbox,
        "No Messages",
        "You don't have any messages yet"
    ),
    NoConversations(
        Icons.Outlined.ChatBubbleOutline,
        "No Conversations",
        "Start a new conversation to see it here"
    ),
    NoContacts(
        Icons.Outlined.Contacts,
        "No Contacts",
        "Add contacts to get started"
    ),
    NoSearchResults(
        Icons.Outlined.Search,
        "No Results",
        "Try a different search term"
    ),
    NoHistory(
        Icons.Outlined.History,
        "No History",
        "Your history will appear here"
    ),
    Offline(
        Icons.Outlined.WifiOff,
        "No Connection",
        "Check your internet connection and try again"
    ),
    Error(
        Icons.Outlined.ErrorOutline,
        "Something Went Wrong",
        "An error occurred. Please try again"
    )
}

/**
 * Empty state component
 *
 * Displays:
 * - Large icon
 * - Title
 * - Description message
 * - Optional action button
 */
@Composable
fun SyncFlowEmptyState(
    type: EmptyStateType,
    modifier: Modifier = Modifier,
    title: String = type.title,
    message: String = type.message,
    icon: ImageVector = type.icon,
    iconTint: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = iconTint
        )

        Spacer(Modifier.height(Spacing.lg))

        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(Spacing.xs))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = Spacing.lg)
        )

        if (action != null) {
            Spacer(Modifier.height(Spacing.lg))
            action()
        }
    }
}

/**
 * Animated empty state that fades in/out
 */
@Composable
fun SyncFlowAnimatedEmptyState(
    visible: Boolean,
    type: EmptyStateType,
    modifier: Modifier = Modifier,
    title: String = type.title,
    message: String = type.message,
    action: (@Composable () -> Unit)? = null
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(initialScale = 0.9f),
        exit = fadeOut() + scaleOut(targetScale = 0.9f),
        modifier = modifier
    ) {
        SyncFlowEmptyState(
            type = type,
            title = title,
            message = message,
            action = action
        )
    }
}

/**
 * Custom empty state with flexible content
 */
@Composable
fun SyncFlowCustomEmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    SyncFlowEmptyState(
        type = EmptyStateType.NoMessages, // Base type, will be overridden
        modifier = modifier,
        icon = icon,
        iconTint = iconTint,
        title = title,
        message = message,
        action = if (actionText != null && onAction != null) {
            {
                SyncFlowPrimaryButton(
                    text = actionText,
                    onClick = onAction
                )
            }
        } else null
    )
}

/**
 * Error state with retry action
 */
@Composable
fun SyncFlowErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Something Went Wrong"
) {
    SyncFlowEmptyState(
        type = EmptyStateType.Error,
        modifier = modifier,
        title = title,
        message = message,
        iconTint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
        action = {
            SyncFlowPrimaryButton(
                text = "Try Again",
                onClick = onRetry
            )
        }
    )
}

/**
 * Offline state with retry action
 */
@Composable
fun SyncFlowOfflineState(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    SyncFlowEmptyState(
        type = EmptyStateType.Offline,
        modifier = modifier,
        action = {
            SyncFlowSecondaryButton(
                text = "Retry",
                onClick = onRetry
            )
        }
    )
}

/**
 * Search empty state
 */
@Composable
fun SyncFlowSearchEmptyState(
    query: String,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null
) {
    SyncFlowEmptyState(
        type = EmptyStateType.NoSearchResults,
        modifier = modifier,
        message = "No results found for \"$query\"",
        action = if (onClear != null) {
            {
                SyncFlowGhostButton(
                    text = "Clear Search",
                    onClick = onClear
                )
            }
        } else null
    )
}

/**
 * Loading placeholder with skeleton effect
 * (For use while content is loading)
 */
@Composable
fun SyncFlowLoadingPlaceholder(
    itemCount: Int = 3,
    modifier: Modifier = Modifier
) {
    // Uses ConversationSkeleton from SkeletonLoading.kt
    Column(modifier = modifier) {
        repeat(itemCount) { index ->
            ConversationSkeleton()
            if (index < itemCount - 1) {
                Spacer(Modifier.height(Spacing.xs))
            }
        }
    }
}
