package com.phoneintegration.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneintegration.app.ui.theme.Spacing
import com.phoneintegration.app.ui.theme.SyncFlowPadding
import com.phoneintegration.app.ui.theme.SyncFlowTypography

/**
 * Conversation list item
 *
 * Displays a conversation preview with:
 * - Avatar
 * - Contact name
 * - Last message preview
 * - Timestamp
 * - Unread badge
 */
@Composable
fun SyncFlowConversationItem(
    name: String,
    preview: String,
    timestamp: String,
    modifier: Modifier = Modifier,
    avatarUrl: String? = null,
    unreadCount: Int = 0,
    isGroup: Boolean = false,
    isPinned: Boolean = false,
    isMuted: Boolean = false,
    simSlot: Int? = null,
    messageType: MessageType = MessageType.Normal,
    selected: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(150),
        label = "bg_color"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Spacing.radiusSm))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(SyncFlowPadding.listItem),
        verticalAlignment = Alignment.Top
    ) {
        // Avatar
        SyncFlowAvatar(
            name = name,
            imageUrl = avatarUrl,
            size = AvatarSize.Medium
        )

        Spacer(Modifier.width(Spacing.listItemContentGap))

        // Content
        Column(
            modifier = Modifier.weight(1f)
        ) {
            // Top row: Name + badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = name,
                    style = SyncFlowTypography.conversationTitle,
                    color = if (unreadCount > 0) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.87f)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // SIM indicator
                if (simSlot != null) {
                    Spacer(Modifier.width(Spacing.xxs))
                    SyncFlowSimBadge(simSlot = simSlot)
                }

                // Timestamp
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = timestamp,
                    style = SyncFlowTypography.timestamp,
                    color = if (unreadCount > 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    }
                )
            }

            Spacer(Modifier.height(Spacing.xxxs))

            // Bottom row: Preview + unread badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Message type badge
                if (messageType != MessageType.Normal) {
                    SyncFlowMessageTypeBadge(type = messageType)
                    Spacer(Modifier.width(Spacing.xxs))
                }

                Text(
                    text = preview,
                    style = SyncFlowTypography.conversationPreview,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (unreadCount > 0) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier.weight(1f)
                )

                // Unread badge
                if (unreadCount > 0) {
                    Spacer(Modifier.width(Spacing.xs))
                    SyncFlowBadge(count = unreadCount)
                }
            }
        }
    }
}

/**
 * Contact list item
 */
@Composable
fun SyncFlowContactItem(
    name: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    avatarUrl: String? = null,
    status: OnlineStatus = OnlineStatus.None,
    selected: Boolean = false,
    onClick: () -> Unit = {}
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(150),
        label = "bg_color"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Spacing.radiusSm))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(SyncFlowPadding.listItem),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SyncFlowAvatar(
            name = name,
            imageUrl = avatarUrl,
            size = AvatarSize.Medium,
            status = status
        )

        Spacer(Modifier.width(Spacing.listItemContentGap))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = SyncFlowTypography.conversationTitle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (subtitle != null) {
                Spacer(Modifier.height(Spacing.xxxs))
                Text(
                    text = subtitle,
                    style = SyncFlowTypography.conversationPreview,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Spacing.iconMd)
            )
        }
    }
}

/**
 * Settings list item with icon
 */
@Composable
fun SyncFlowSettingsItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    trailing: @Composable (() -> Unit)? = null,
    showChevron: Boolean = true,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null && enabled) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(SyncFlowPadding.listItem),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(iconTint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(Spacing.iconSm)
                )
            }
            Spacer(Modifier.width(Spacing.listItemContentGap))
        }

        // Title & Subtitle
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
            )

            if (subtitle != null) {
                Spacer(Modifier.height(Spacing.xxxs))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
                    }
                )
            }
        }

        // Trailing content
        if (trailing != null) {
            trailing()
        } else if (showChevron && onClick != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.size(Spacing.iconMd)
            )
        }
    }
}

/**
 * Settings toggle item
 */
@Composable
fun SyncFlowSettingsToggle(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true
) {
    SyncFlowSettingsItem(
        title = title,
        subtitle = subtitle,
        icon = icon,
        iconTint = iconTint,
        modifier = modifier,
        showChevron = false,
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled
            )
        }
    )
}

/**
 * Section header for list groups
 */
@Composable
fun SyncFlowSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = Spacing.listItemHorizontal,
                vertical = Spacing.xs
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title.uppercase(),
            style = SyncFlowTypography.sectionHeader,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 0.8.sp
        )

        action?.invoke()
    }
}

/**
 * List divider with optional inset
 */
@Composable
fun SyncFlowListDivider(
    modifier: Modifier = Modifier,
    startIndent: Boolean = false
) {
    HorizontalDivider(
        modifier = modifier.padding(
            start = if (startIndent) Spacing.dividerInsetStart else 0.dp
        ),
        thickness = Spacing.dividerHeight,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}
