package com.phoneintegration.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.phoneintegration.app.ui.theme.Spacing
import com.phoneintegration.app.ui.theme.SyncFlowBlue
import com.phoneintegration.app.ui.theme.Blue100
import com.phoneintegration.app.ui.theme.Blue700

/**
 * Avatar size variants
 */
enum class AvatarSize(val size: Dp, val fontSize: Int, val badgeSize: Dp) {
    Small(Spacing.avatarSm, 12, 10.dp),
    Medium(Spacing.avatarMd, 14, 12.dp),
    Large(Spacing.avatarLg, 16, 14.dp),
    ExtraLarge(Spacing.avatarXl, 20, 16.dp),
    DoubleExtraLarge(Spacing.avatarXxl, 24, 18.dp)
}

/**
 * Online status indicator
 */
enum class OnlineStatus {
    Online,
    Away,
    Busy,
    Offline,
    None
}

/**
 * SyncFlow Avatar Component
 *
 * A reusable avatar component with:
 * - Photo support via URL
 * - Initials fallback
 * - Online status indicator
 * - Multiple sizes
 * - Customizable colors
 */
@Composable
fun SyncFlowAvatar(
    modifier: Modifier = Modifier,
    name: String,
    imageUrl: String? = null,
    size: AvatarSize = AvatarSize.Medium,
    status: OnlineStatus = OnlineStatus.None,
    backgroundColor: Color = Blue100,
    textColor: Color = Blue700,
    borderColor: Color? = null
) {
    Box(
        modifier = modifier.size(size.size),
        contentAlignment = Alignment.Center
    ) {
        // Avatar circle
        if (imageUrl != null && imageUrl.isNotEmpty()) {
            // Photo avatar
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = "Avatar for $name",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size.size)
                    .clip(CircleShape)
                    .then(
                        if (borderColor != null) {
                            Modifier.border(2.dp, borderColor, CircleShape)
                        } else {
                            Modifier
                        }
                    )
            )
        } else {
            // Initials avatar
            Box(
                modifier = Modifier
                    .size(size.size)
                    .clip(CircleShape)
                    .background(backgroundColor, CircleShape)
                    .then(
                        if (borderColor != null) {
                            Modifier.border(2.dp, borderColor, CircleShape)
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = getInitials(name),
                    color = textColor,
                    fontSize = size.fontSize.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Status indicator
        if (status != OnlineStatus.None) {
            Box(
                modifier = Modifier
                    .size(size.badgeSize)
                    .align(Alignment.BottomEnd)
                    .offset(x = (-2).dp, y = (-2).dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
            ) {
                Box(
                    modifier = Modifier
                        .size(size.badgeSize - 4.dp)
                        .align(Alignment.Center)
                        .clip(CircleShape)
                        .background(getStatusColor(status), CircleShape)
                )
            }
        }
    }
}

/**
 * Extract initials from name (up to 2 characters)
 */
private fun getInitials(name: String): String {
    val cleanName = name.trim()
    if (cleanName.isEmpty()) return "?"

    val words = cleanName.split(" ").filter { it.isNotEmpty() }
    return when {
        words.size >= 2 -> "${words[0].first()}${words[1].first()}".uppercase()
        words.isNotEmpty() -> words[0].take(2).uppercase()
        else -> "?"
    }
}

/**
 * Get color for online status
 */
@Composable
private fun getStatusColor(status: OnlineStatus): Color {
    return when (status) {
        OnlineStatus.Online -> Color(0xFF4CAF50)  // Green
        OnlineStatus.Away -> Color(0xFFFFC107)    // Amber
        OnlineStatus.Busy -> Color(0xFFF44336)    // Red
        OnlineStatus.Offline -> Color(0xFF9E9E9E) // Grey
        OnlineStatus.None -> Color.Transparent
    }
}

/**
 * Avatar with group indicator (shows +N for group chats)
 */
@Composable
fun SyncFlowGroupAvatar(
    modifier: Modifier = Modifier,
    names: List<String>,
    size: AvatarSize = AvatarSize.Medium,
    maxVisible: Int = 3
) {
    val displayNames = names.take(maxVisible)
    val overflow = names.size - maxVisible

    Box(modifier = modifier.size(size.size)) {
        if (displayNames.isEmpty()) {
            // Default group avatar
            SyncFlowAvatar(
                name = "Group",
                size = size,
                backgroundColor = Blue100,
                textColor = Blue700
            )
        } else if (displayNames.size == 1) {
            SyncFlowAvatar(
                name = displayNames[0],
                size = size
            )
        } else {
            // Stack avatars for groups
            displayNames.forEachIndexed { index, name ->
                val smallerSize = when (size) {
                    AvatarSize.Small -> AvatarSize.Small
                    AvatarSize.Medium -> AvatarSize.Small
                    AvatarSize.Large -> AvatarSize.Medium
                    AvatarSize.ExtraLarge -> AvatarSize.Large
                    AvatarSize.DoubleExtraLarge -> AvatarSize.ExtraLarge
                }

                SyncFlowAvatar(
                    name = name,
                    size = smallerSize,
                    borderColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.offset(
                        x = (index * 12).dp,
                        y = 0.dp
                    )
                )
            }

            // Overflow indicator
            if (overflow > 0) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .offset(
                            x = (displayNames.size * 12 + 4).dp,
                            y = (size.size.value / 2 - 10).dp
                        )
                        .clip(CircleShape)
                        .background(SyncFlowBlue, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "+$overflow",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
