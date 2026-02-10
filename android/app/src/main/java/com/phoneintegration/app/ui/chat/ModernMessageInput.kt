package com.phoneintegration.app.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.phoneintegration.app.speech.VoiceRecognitionState
import com.phoneintegration.app.speech.VoiceToTextManager

/**
 * Modern message input redesign inspired by iMessage/WhatsApp
 * Clean, minimal design with smooth animations
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ModernMessageInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onSchedule: (String, Long) -> Unit = { _, _ -> },
    onAttach: () -> Unit,
    onCamera: () -> Unit = {},
    onGif: () -> Unit = {},
    smartReplies: List<String> = emptyList(),
    replyPreview: ReplyPreview? = null,
    onClearReply: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showScheduleDialog by remember { mutableStateOf(false) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    // Voice-to-text
    val voiceManager = remember { VoiceToTextManager(context) }
    val voiceState by voiceManager.state.collectAsState()
    var isVoiceActive by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }

    // Permission launcher for microphone
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voiceManager.initialize()
            voiceManager.startListening { result ->
                onValueChange(value + result)
            }
            isVoiceActive = true
        } else {
            Toast.makeText(context, "Microphone permission required", Toast.LENGTH_SHORT).show()
        }
    }

    // Initialize voice manager
    DisposableEffect(Unit) {
        if (VoiceToTextManager.isAvailable(context)) {
            voiceManager.initialize()
        }
        onDispose { voiceManager.release() }
    }

    // Handle voice state changes
    LaunchedEffect(voiceState) {
        when (val state = voiceState) {
            is VoiceRecognitionState.Result -> {
                if (state.isFinal) isVoiceActive = false
            }
            is VoiceRecognitionState.Error -> {
                isVoiceActive = false
                if (state.errorCode != 7) {
                    Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
                }
            }
            is VoiceRecognitionState.Idle -> isVoiceActive = false
            else -> {}
        }
    }

    val hasText = value.isNotBlank()
    val showCharCount = value.length > 120

    Column(modifier = modifier.fillMaxWidth()) {
        // Smart Replies - Horizontal scrollable chips
        AnimatedVisibility(
            visible = smartReplies.isNotEmpty() && !hasText,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                smartReplies.forEach { reply ->
                    SuggestionChip(
                        onClick = {
                            onValueChange(reply)
                            focusRequester.requestFocus()
                        },
                        label = {
                            Text(
                                reply,
                                maxLines = 1,
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ),
                        border = null
                    )
                }
            }
        }

        // Reply Preview
        AnimatedVisibility(
            visible = replyPreview != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            replyPreview?.let { reply ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(36.dp)
                            .background(
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(2.dp)
                            )
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = reply.sender,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = reply.snippet,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    IconButton(
                        onClick = onClearReply,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Cancel reply",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Voice Recording Indicator
        AnimatedVisibility(
            visible = isVoiceActive,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            VoiceRecordingBar(
                onCancel = {
                    voiceManager.cancel()
                    isVoiceActive = false
                }
            )
        }

        // Main Input Area
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            // Left Actions (Camera + Attach) - Show when not focused or no text
            AnimatedVisibility(
                visible = !isFocused || !hasText,
                enter = fadeIn() + expandHorizontally(),
                exit = fadeOut() + shrinkHorizontally()
            ) {
                Row {
                    IconButton(
                        onClick = onCamera,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Outlined.CameraAlt,
                            contentDescription = "Camera",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Text Field Container
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                tonalElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    // Emoji button inside text field
                    IconButton(
                        onClick = { showEmojiPicker = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Outlined.EmojiEmotions,
                            contentDescription = "Emoji",
                            tint = if (showEmojiPicker) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // Text Input
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 36.dp, max = 120.dp)
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .onFocusChanged { isFocused = it.isFocused },
                            textStyle = TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 16.sp
                            ),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                autoCorrect = true,
                                imeAction = ImeAction.Send
                            ),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    if (hasText) {
                                        onSend(value)
                                        onValueChange("")
                                    }
                                }
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            maxLines = 6
                        )

                        // Placeholder
                        if (!hasText) {
                            Text(
                                text = "Message",
                                style = TextStyle(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 16.sp
                                )
                            )
                        }
                    }

                    // Character count (show near limit)
                    AnimatedVisibility(
                        visible = showCharCount,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Text(
                            text = "${value.length}/160",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (value.length > 160) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(end = 8.dp, bottom = 8.dp)
                        )
                    }

                    // Attach button inside text field
                    IconButton(
                        onClick = onAttach,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Outlined.AttachFile,
                            contentDescription = "Attach",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // GIF button inside text field (when focused)
                    AnimatedVisibility(
                        visible = isFocused,
                        enter = fadeIn() + expandHorizontally(),
                        exit = fadeOut() + shrinkHorizontally()
                    ) {
                        IconButton(
                            onClick = onGif,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Gif,
                                contentDescription = "GIF",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }

            // Send/Voice Button
            Crossfade(
                targetState = hasText,
                label = "send_voice_transition"
            ) { showSend ->
                if (showSend) {
                    // Send Button with long-press for schedule
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .combinedClickable(
                                onClick = {
                                    onSend(value)
                                    onValueChange("")
                                },
                                onLongClick = {
                                    showScheduleDialog = true
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send (long press to schedule)",
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                } else {
                    // Voice Button
                    FilledTonalIconButton(
                        onClick = {
                            val hasPermission = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED

                            if (hasPermission) {
                                voiceManager.startListening { result ->
                                    onValueChange(value + result)
                                }
                                isVoiceActive = true
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        modifier = Modifier.size(44.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = "Voice message",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }

    // Schedule Dialog
    if (showScheduleDialog) {
        ScheduleMessageDialog(
            onDismiss = { showScheduleDialog = false },
            onSchedule = { scheduledTime ->
                onSchedule(value, scheduledTime)
                onValueChange("")
                showScheduleDialog = false
            }
        )
    }

    // Emoji Picker Bottom Sheet
    if (showEmojiPicker) {
        ModalBottomSheet(
            onDismissRequest = { showEmojiPicker = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            EmojiPickerContent(
                onEmojiSelected = { emoji ->
                    onValueChange(value + emoji)
                },
                onDismiss = { showEmojiPicker = false }
            )
        }
    }
}

@Composable
private fun ScheduleMessageDialog(
    onDismiss: () -> Unit,
    onSchedule: (Long) -> Unit
) {
    var selectedDate by remember { mutableStateOf(System.currentTimeMillis() + 3600000) } // Default: 1 hour from now

    val quickOptions = listOf(
        "In 1 hour" to 3600000L,
        "In 3 hours" to 10800000L,
        "Tomorrow morning" to getNextMorningTime(),
        "Tomorrow evening" to getNextEveningTime()
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Schedule Message") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "When should this message be sent?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                quickOptions.forEach { (label, offset) ->
                    val time = System.currentTimeMillis() + offset
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { selectedDate = time },
                        color = if (selectedDate == time) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = { onSchedule(selectedDate) }) {
                Text("Schedule")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun getNextMorningTime(): Long {
    val calendar = java.util.Calendar.getInstance()
    calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
    calendar.set(java.util.Calendar.HOUR_OF_DAY, 9)
    calendar.set(java.util.Calendar.MINUTE, 0)
    calendar.set(java.util.Calendar.SECOND, 0)
    return calendar.timeInMillis - System.currentTimeMillis()
}

private fun getNextEveningTime(): Long {
    val calendar = java.util.Calendar.getInstance()
    calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
    calendar.set(java.util.Calendar.HOUR_OF_DAY, 18)
    calendar.set(java.util.Calendar.MINUTE, 0)
    calendar.set(java.util.Calendar.SECOND, 0)
    return calendar.timeInMillis - System.currentTimeMillis()
}

@Composable
private fun VoiceRecordingBar(
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "recording")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Pulsing red dot
        Box(
            modifier = Modifier
                .size(12.dp)
                .scale(pulseScale)
                .background(MaterialTheme.colorScheme.error, CircleShape)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = "Listening...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f)
        )

        TextButton(
            onClick = onCancel,
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text("Cancel")
        }
    }
}

@Composable
private fun EmojiPickerContent(
    onEmojiSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val emojiCategories = remember {
        listOf(
            "😀" to listOf("😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂", "🙂", "🙃", "😉", "😊", "😇", "🥰", "😍", "🤩", "😘", "😗", "😚", "😙", "🥲", "😋", "😛", "😜", "🤪", "😝", "🤑", "🤗", "🤭", "🤫", "🤔", "🤐", "🤨", "😐", "😑", "😶", "😏", "😒", "🙄", "😬", "🤥"),
            "❤️" to listOf("❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔", "❤️‍🔥", "❤️‍🩹", "💕", "💞", "💓", "💗", "💖", "💘", "💝", "💟", "♥️", "💌", "💋", "😻", "🫶", "🤟", "🤙", "👍", "👎", "👏", "🙌", "🤝", "🙏"),
            "🎉" to listOf("🎉", "🎊", "🎈", "🎁", "🎀", "🎂", "🍰", "🧁", "🥳", "🪅", "🪄", "✨", "🌟", "⭐", "🌈", "☀️", "🌙", "🔥", "💥", "💫", "🎵", "🎶", "🎤", "🎧", "🎸", "🎹", "🥁", "🎺", "🎷"),
            "👋" to listOf("👋", "🤚", "🖐️", "✋", "🖖", "👌", "🤌", "🤏", "✌️", "🤞", "🤟", "🤘", "🤙", "👈", "👉", "👆", "🖕", "👇", "☝️", "👍", "👎", "✊", "👊", "🤛", "🤜", "👏", "🙌", "👐", "🤲", "🤝", "🙏", "💪", "🦾"),
            "🐶" to listOf("🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐨", "🐯", "🦁", "🐮", "🐷", "🐸", "🐵", "🙈", "🙉", "🙊", "🐔", "🐧", "🐦", "🐤", "🦆", "🦅", "🦉", "🦇", "🐺", "🐗", "🐴", "🦄", "🐝", "🦋", "🐌", "🐞"),
            "🍕" to listOf("🍕", "🍔", "🍟", "🌭", "🍿", "🧂", "🥓", "🥚", "🍳", "🧇", "🥞", "🧈", "🍞", "🥐", "🥨", "🧀", "🥗", "🥙", "🥪", "🌮", "🌯", "🫔", "🥫", "🍝", "🍜", "🍲", "🍛", "🍣", "🍱", "🥟", "🍤", "🍙", "🍚", "🍘"),
            "⚽" to listOf("⚽", "🏀", "🏈", "⚾", "🥎", "🎾", "🏐", "🏉", "🥏", "🎱", "🪀", "🏓", "🏸", "🏒", "🏑", "🥍", "🏏", "🪃", "🥅", "⛳", "🪁", "🏹", "🎣", "🤿", "🥊", "🥋", "🎽", "🛹", "🛼", "🛷", "⛸️", "🥌", "🎿", "⛷️", "🏂")
        )
    }

    var selectedCategory by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
    ) {
        // Category tabs
        ScrollableTabRow(
            selectedTabIndex = selectedCategory,
            modifier = Modifier.fillMaxWidth(),
            edgePadding = 8.dp,
            divider = {}
        ) {
            emojiCategories.forEachIndexed { index, (icon, _) ->
                Tab(
                    selected = selectedCategory == index,
                    onClick = { selectedCategory = index },
                    text = { Text(icon, fontSize = 20.sp) }
                )
            }
        }

        // Emoji grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(8),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(8.dp),
            contentPadding = PaddingValues(4.dp)
        ) {
            val emojis = emojiCategories[selectedCategory].second
            items(emojis) { emoji ->
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            onEmojiSelected(emoji)
                        }
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = emoji,
                        fontSize = 24.sp
                    )
                }
            }
        }
    }
}
