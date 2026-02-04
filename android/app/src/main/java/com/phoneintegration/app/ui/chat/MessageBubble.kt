package com.phoneintegration.app.ui.chat

import android.content.Intent
import android.net.Uri
import android.util.Patterns
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.phoneintegration.app.MessageCategory
import com.phoneintegration.app.SmsMessage
import com.phoneintegration.app.realtime.ReadReceipt
import com.phoneintegration.app.ui.shared.formatTimestamp
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun MessageBubble(
    sms: SmsMessage,
    onLongPress: () -> Unit,
    onRetryMms: (SmsMessage) -> Unit = {},
    reaction: String? = null,
    onQuickReact: () -> Unit = {},
    readReceipt: ReadReceipt? = null,
    modifier: Modifier = Modifier
) {
    val isSent = sms.type == 2   // 1 = received, 2 = sent
    val (replyInfo, displayBody) = splitReplyBody(sms.body)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = if (isSent) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 260.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = { onLongPress() },
                        onDoubleTap = { onQuickReact() }
                    )
                }
                .background(
                    color = bubbleColor(sms, isSent),
                    shape = RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 12.dp,
                        bottomStart = if (isSent) 12.dp else 4.dp,
                        bottomEnd = if (isSent) 4.dp else 12.dp
                    )
                )
                .padding(12.dp)
        ) {
            Column {

                if (sms.isMms && sms.id < 0) {
                    Text(
                        text = "MMS send failed",
                        color = Color.Red,
                        style = MaterialTheme.typography.labelMedium
                    )

                    TextButton(onClick = { onRetryMms(sms) }) {
                        Text("Retry")
                    }
                }

                if (replyInfo != null) {
                    ReplyQuoteBlock(replyInfo = replyInfo)
                    Spacer(modifier = Modifier.height(6.dp))
                }

                // MMS subject (if present)
                if (sms.isMms && !sms.mmsSubject.isNullOrBlank()) {
                    Row(
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "📌",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            text = sms.mmsSubject!!,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                // MMS attachments block
                if (sms.isMms && sms.mmsAttachments.isNotEmpty()) {

                    sms.mmsAttachments.take(1).forEach { attach ->
                        when {
                            attach.isImage() -> {
                                AsyncImage(
                                    model = attach.filePath,
                                    contentDescription = "MMS Image",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 350.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                )
                            }
                            attach.isVideo() -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(250.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                ) {
                                    AsyncImage(
                                        model = attach.filePath,
                                        contentDescription = "MMS Video Thumbnail",
                                        modifier = Modifier.matchParentSize()
                                    )
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier
                                            .size(48.dp)
                                            .align(Alignment.Center)
                                    )
                                }
                            }
                            attach.isAudio() -> {
                                Text(
                                    "🎵 Audio attachment (${attach.fileName ?: "audio"})",
                                    color = bubbleTextColor(isSent)
                                )
                            }
                            attach.isVCard() -> {
                                Text(
                                    "📇 Contact Card (${attach.fileName ?: "contact"})",
                                    color = bubbleTextColor(isSent)
                                )
                            }
                        }
                    }

                    // If more attachments exist, show "+3 more"
                    if (sms.mmsAttachments.size > 1) {
                        Text(
                            text = "+${sms.mmsAttachments.size - 1} more",
                            color = bubbleTextColor(isSent),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }

                    if (!sms.body.isNullOrBlank())
                        Spacer(modifier = Modifier.height(6.dp))
                }

                // TEXT part (SMS or MMS text) with clickable links and formatting
                if (displayBody.isNotBlank()) {
                    SelectionContainer {
                        ClickableMessageText(
                            text = displayBody,
                            textColor = bubbleTextColor(isSent),
                            linkColor = if (isSent) Color(0xFF64B5F6) else MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // TIMESTAMP + CHECKMARK
                Row(
                    modifier = Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (sms.isE2ee) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "E2EE",
                            modifier = Modifier.size(12.dp),
                            tint = bubbleTextColor(isSent).copy(alpha = 0.7f)
                        )
                    }
                    Text(
                        text = formatTimestamp(sms.date),
                        color = bubbleTextColor(isSent).copy(alpha = 0.65f),
                        style = MaterialTheme.typography.labelSmall
                    )

                    if (isSent) {
                        Icon(
                            imageVector = if (sms.id < 0) {
                                Icons.Default.Schedule
                            } else {
                                Icons.Default.Done
                            },
                            contentDescription = if (sms.id < 0) "Sending" else "Sent",
                            modifier = Modifier.size(12.dp),
                            tint = bubbleTextColor(isSent).copy(alpha = 0.7f)
                        )
                    }
                }

                if (!isSent && readReceipt != null && readReceipt.readBy != "android" && readReceipt.readAt > 0) {
                    val readBy = readReceipt.readDeviceName ?: readReceipt.readBy
                    DetailedReadReceipt(
                        readTime = readReceipt.readAt,
                        readBy = readBy,
                        modifier = Modifier.align(Alignment.End)
                    )
                }

                if (!reaction.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.align(if (isSent) Alignment.End else Alignment.Start)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = reaction,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplyQuoteBlock(
    replyInfo: ReplyInfo,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(2.dp)
                )
        )

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = replyInfo.sender,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = replyInfo.snippet,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
    }
}

/**
 * BUBBLE BACKGROUND COLOR
 * Dark-mode safe auto colors.
 */
@Composable
private fun bubbleColor(sms: SmsMessage, isSent: Boolean): Color {
    val isDark = isSystemInDarkTheme()

    return if (isSent) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        when (sms.category) {
            MessageCategory.OTP ->
                if (isDark) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFD6EAF8)
            MessageCategory.TRANSACTION ->
                if (isDark) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFD1F2EB)
            MessageCategory.PERSONAL ->
                if (isDark) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFFDEDEC)
            MessageCategory.PROMOTION ->
                if (isDark) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFF9E79F)
            MessageCategory.ALERT ->
                if (isDark) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFFADBD8)

            else -> MaterialTheme.colorScheme.surfaceVariant
        }
    }
}


/**
 * BUBBLE TEXT COLOR
 */
@Composable
private fun bubbleTextColor(isSent: Boolean): Color {
    return if (isSent)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onSurface
}

/**
 * Clickable Message Text with Links and Formatting
 */
@Composable
fun ClickableMessageText(
    text: String,
    textColor: Color,
    linkColor: Color,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val annotatedString = buildFormattedAnnotatedString(text, textColor, linkColor)

    ClickableText(
        text = annotatedString,
        style = MaterialTheme.typography.bodyMedium.copy(color = textColor),
        modifier = modifier,
        onClick = { offset ->
            // Handle link clicks
            annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                .firstOrNull()?.let { annotation ->
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(annotation.item))
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        // Handle error silently
                    }
                }

            // Handle phone number clicks
            annotatedString.getStringAnnotations(tag = "PHONE", start = offset, end = offset)
                .firstOrNull()?.let { annotation ->
                    try {
                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${annotation.item}"))
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        // Handle error silently
                    }
                }
        }
    )
}

/**
 * Build annotated string with links and text formatting
 */
private fun buildFormattedAnnotatedString(
    text: String,
    textColor: Color,
    linkColor: Color
): AnnotatedString {
    return buildAnnotatedString {
        var currentIndex = 0
        var processedText = text

        // Find all URLs
        val urlPattern = Patterns.WEB_URL
        val urlMatcher = urlPattern.matcher(text)
        val urlRanges = mutableListOf<Pair<IntRange, String>>()

        while (urlMatcher.find()) {
            val url = urlMatcher.group()
            val start = urlMatcher.start()
            val end = urlMatcher.end()
            urlRanges.add(Pair(start until end, url))
        }

        // Find all phone numbers
        val phonePattern = Patterns.PHONE
        val phoneMatcher = phonePattern.matcher(text)
        val phoneRanges = mutableListOf<Pair<IntRange, String>>()

        while (phoneMatcher.find()) {
            val phone = phoneMatcher.group()
            val start = phoneMatcher.start()
            val end = phoneMatcher.end()
            // Only add if not overlapping with URL
            if (urlRanges.none { it.first.contains(start) || it.first.contains(end - 1) }) {
                phoneRanges.add(Pair(start until end, phone))
            }
        }

        // Combine and sort all special ranges
        val allRanges = (urlRanges.map { Triple(it.first, it.second, "URL") } +
                        phoneRanges.map { Triple(it.first, it.second, "PHONE") })
            .sortedBy { it.first.first }

        // Build the annotated string
        var lastIndex = 0

        for ((range, value, type) in allRanges) {
            // Add text before the link
            if (lastIndex < range.first) {
                append(formatTextWithStyles(text.substring(lastIndex, range.first), textColor))
            }

            // Add the clickable link
            pushStringAnnotation(tag = type, annotation = value)
            withStyle(
                style = SpanStyle(
                    color = linkColor,
                    textDecoration = TextDecoration.Underline
                )
            ) {
                append(value)
            }
            pop()

            lastIndex = range.last + 1
        }

        // Add remaining text (or entire text if no links found)
        if (lastIndex < text.length) {
            append(formatTextWithStyles(text.substring(lastIndex), textColor))
        } else if (allRanges.isEmpty()) {
            // Only if no links were processed at all, format the entire text
            append(formatTextWithStyles(text, textColor))
        }
    }
}

/**
 * Format text with bold and italic styles
 */
private fun formatTextWithStyles(text: String, textColor: Color): AnnotatedString {
    return buildAnnotatedString {
        var currentText = text
        var currentIndex = 0

        // Process bold text (*text*)
        val boldRegex = Regex("\\*([^*]+)\\*")
        val boldMatches = boldRegex.findAll(currentText).toList()

        // Process italic text (_text_)
        val italicRegex = Regex("_([^_]+)_")
        val italicMatches = italicRegex.findAll(currentText).toList()

        // Combine matches and sort by position
        val allMatches = (boldMatches.map { Triple(it.range, it.groupValues[1], "bold") } +
                         italicMatches.map { Triple(it.range, it.groupValues[1], "italic") })
            .sortedBy { it.first.first }

        for ((range, innerText, style) in allMatches) {
            // Add text before the styled text
            if (currentIndex < range.first) {
                append(currentText.substring(currentIndex, range.first))
            }

            // Add styled text
            when (style) {
                "bold" -> {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(innerText)
                    }
                }
                "italic" -> {
                    withStyle(style = SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(innerText)
                    }
                }
            }

            currentIndex = range.last + 1
        }

        // Add remaining text (or entire text if no formatting found)
        if (currentIndex < currentText.length) {
            append(currentText.substring(currentIndex))
        } else if (allMatches.isEmpty()) {
            // Only if no formatting was processed at all, append the entire text
            append(currentText)
        }
    }
}

private data class ReplyInfo(
    val sender: String,
    val snippet: String
)

private fun splitReplyBody(body: String): Pair<ReplyInfo?, String> {
    val trimmed = body.trimStart()
    if (!trimmed.startsWith("> ")) {
        return null to body
    }

    val lines = trimmed.split("\n")
    val header = lines.firstOrNull()?.removePrefix("> ")?.trim().orEmpty()
    if (header.isEmpty()) {
        return null to body
    }

    val parts = header.split(": ", limit = 2)
    val sender = if (parts.size == 2) parts[0].ifBlank { "Reply" } else "Reply"
    val snippet = if (parts.size == 2) parts[1] else header
    val remainder = lines.drop(1).joinToString("\n").trimStart()
    return ReplyInfo(sender = sender, snippet = snippet) to remainder
}
