package com.phoneintegration.app.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Forward
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.util.Patterns
import android.net.Uri
import com.phoneintegration.app.SmsMessage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionsSheet(
    message: SmsMessage,
    currentReaction: String?,
    isStarred: Boolean = false,
    onSetReaction: (String?) -> Unit,
    onReply: () -> Unit,
    onForward: () -> Unit = {},
    onStar: () -> Unit = {},
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    
    ModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                "Message Actions",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Text(
                "Reactions",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                val reactions = listOf("👍", "❤️", "😂", "😮", "😢", "😡")
                reactions.forEach { reaction ->
                    val isSelected = reaction == currentReaction
                    Text(
                        text = reaction,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .clickable {
                                if (isSelected) {
                                    onSetReaction(null)
                                } else {
                                    onSetReaction(reaction)
                                }
                                onDismiss()
                            }
                    )
                }

                if (!currentReaction.isNullOrBlank()) {
                    Text(
                        text = "Clear",
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .clickable {
                                onSetReaction(null)
                                onDismiss()
                            }
                    )
                }
            }

            // Copy
            ListItem(
                headlineContent = { Text("Copy Message") },
                supportingContent = { Text("Copy entire message text") },
                leadingContent = { Icon(Icons.Default.ContentCopy, null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        copyToClipboard(context, message.body)
                        Toast.makeText(context, "Message copied!", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
            )

            // Copy Links (if message contains links)
            if (containsLinks(message.body)) {
                ListItem(
                    headlineContent = { Text("Copy Links") },
                    supportingContent = { Text("Copy all links in message") },
                    leadingContent = { Icon(Icons.Default.Link, null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            copyLinks(context, message.body)
                            Toast.makeText(context, "Links copied!", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                )
            }

            // Select All
            ListItem(
                headlineContent = { Text("Select Text") },
                supportingContent = { Text("Copy and select text") },
                leadingContent = { Icon(Icons.Default.SelectAll, null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        copyToClipboard(context, message.body)
                        Toast.makeText(context, "Text selected & copied!", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
            )

            // Reply
            ListItem(
                headlineContent = { Text("Reply") },
                supportingContent = { Text("Quote this message") },
                leadingContent = { Icon(Icons.AutoMirrored.Filled.Reply, null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onReply()
                        onDismiss()
                    }
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // Forward
            ListItem(
                headlineContent = { Text("Forward") },
                supportingContent = { Text("Forward to another contact") },
                leadingContent = { Icon(Icons.Default.Forward, null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onForward()
                        onDismiss()
                    }
            )

            // Star
            ListItem(
                headlineContent = { Text(if (isStarred) "Unstar Message" else "Star Message") },
                supportingContent = { Text(if (isStarred) "Remove from starred" else "Add to starred messages") },
                leadingContent = {
                    Icon(
                        if (isStarred) Icons.Default.Star else Icons.Default.StarBorder,
                        null,
                        tint = if (isStarred) MaterialTheme.colorScheme.primary else LocalContentColor.current
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onStar()
                        onDismiss()
                    }
            )

            // Share
            ListItem(
                headlineContent = { Text("Share") },
                supportingContent = { Text("Share via other apps") },
                leadingContent = { Icon(Icons.Default.Share, null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        shareMessage(context, message.body)
                        onDismiss()
                    }
            )

            // Message Info
            ListItem(
                headlineContent = { Text("Message Info") },
                supportingContent = { Text("View message details") },
                leadingContent = { Icon(Icons.Default.Info, null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        showMessageInfo(context, message)
                        onDismiss()
                    }
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // Delete
            ListItem(
                headlineContent = { Text("Delete Message") },
                supportingContent = { Text("Remove from conversation") },
                leadingContent = { Icon(Icons.Default.Delete, null) },
                colors = ListItemDefaults.colors(
                    headlineColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onDelete()
                        onDismiss()
                    }
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("SMS", text)
    clipboard.setPrimaryClip(clip)
}

private fun shareMessage(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Share message"))
}

private fun containsLinks(text: String): Boolean {
    val urlPattern = Patterns.WEB_URL
    val phonePattern = Patterns.PHONE
    return urlPattern.matcher(text).find() || phonePattern.matcher(text).find()
}

private fun copyLinks(context: Context, text: String) {
    val urlPattern = Patterns.WEB_URL
    val phonePattern = Patterns.PHONE

    val urls = mutableListOf<String>()

    // Find URLs
    val urlMatcher = urlPattern.matcher(text)
    while (urlMatcher.find()) {
        urls.add(urlMatcher.group())
    }

    // Find phone numbers
    val phoneMatcher = phonePattern.matcher(text)
    while (phoneMatcher.find()) {
        urls.add(phoneMatcher.group())
    }

    if (urls.isNotEmpty()) {
        val allLinks = urls.joinToString("\n")
        val clipboard = context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Links", allLinks)
        clipboard.setPrimaryClip(clip)
    }
}

private fun showMessageInfo(context: Context, message: SmsMessage) {
    val dateStr = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm:ss", java.util.Locale.getDefault())
        .format(java.util.Date(message.date))

    val info = buildString {
        appendLine("Message Type: ${if (message.type == 2) "Sent" else "Received"}")
        appendLine("Date: $dateStr")
        appendLine("Length: ${message.body.length} characters")
        if (message.isMms) {
            appendLine("Type: MMS")
            appendLine("Attachments: ${message.mmsAttachments.size}")
        } else {
            appendLine("Type: SMS")
        }
        if (message.category != null) {
            appendLine("Category: ${message.category}")
        }
    }

    Toast.makeText(context, info, Toast.LENGTH_LONG).show()
}
