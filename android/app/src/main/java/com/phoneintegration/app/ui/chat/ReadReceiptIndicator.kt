package com.phoneintegration.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.*

/**
 * Message status for display
 */
enum class MessageStatus {
    SENDING,
    SENT,
    DELIVERED,
    READ
}

/**
 * Read receipt indicator for sent messages
 */
@Composable
fun ReadReceiptIndicator(
    status: MessageStatus,
    readTime: Long? = null,
    readBy: String? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End
    ) {
        when (status) {
            MessageStatus.SENDING -> {
                // Clock icon for sending
                Icon(
                    imageVector = Icons.Default.Done,
                    contentDescription = "Sending",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
            MessageStatus.SENT -> {
                // Single check for sent
                Icon(
                    imageVector = Icons.Default.Done,
                    contentDescription = "Sent",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            MessageStatus.DELIVERED -> {
                // Double check for delivered (gray)
                Icon(
                    imageVector = Icons.Default.DoneAll,
                    contentDescription = "Delivered",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            MessageStatus.READ -> {
                // Double check for read (blue)
                Icon(
                    imageVector = Icons.Default.DoneAll,
                    contentDescription = "Read",
                    modifier = Modifier.size(14.dp),
                    tint = Color(0xFF4FC3F7) // Light blue for read
                )

                // Show read time if available
                if (readTime != null && readTime > 0) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = formatReadTime(readTime),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

/**
 * Compact read receipt (just the icon)
 */
@Composable
fun CompactReadReceipt(
    isRead: Boolean,
    modifier: Modifier = Modifier
) {
    Icon(
        imageVector = Icons.Default.DoneAll,
        contentDescription = if (isRead) "Read" else "Delivered",
        modifier = modifier.size(12.dp),
        tint = if (isRead) Color(0xFF4FC3F7) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    )
}

/**
 * Format read time for display
 */
private fun formatReadTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "just now"
        diff < 3600_000 -> "${diff / 60_000}m ago"
        diff < 86400_000 -> {
            val format = SimpleDateFormat("h:mm a", Locale.getDefault())
            format.format(Date(timestamp))
        }
        else -> {
            val format = SimpleDateFormat("MMM d", Locale.getDefault())
            format.format(Date(timestamp))
        }
    }
}

/**
 * Read receipt with device info
 */
@Composable
fun DetailedReadReceipt(
    readTime: Long,
    readBy: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.DoneAll,
            contentDescription = "Read",
            modifier = Modifier.size(14.dp),
            tint = Color(0xFF4FC3F7)
        )

        Spacer(modifier = Modifier.width(4.dp))

        Text(
            text = "Read on $readBy • ${formatReadTime(readTime)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}
