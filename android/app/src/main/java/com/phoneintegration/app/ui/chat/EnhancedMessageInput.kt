package com.phoneintegration.app.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.phoneintegration.app.speech.VoiceRecognitionState
import com.phoneintegration.app.speech.VoiceToTextManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

data class ReplyPreview(
    val sender: String,
    val snippet: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnhancedMessageInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onSchedule: (String, Long) -> Unit,
    onAttach: () -> Unit,
    smartReplies: List<String> = emptyList(),
    replyPreview: ReplyPreview? = null,
    onClearReply: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Voice-to-text
    val voiceManager = remember { VoiceToTextManager(context) }
    val voiceState by voiceManager.state.collectAsState()
    val amplitude by voiceManager.amplitude.collectAsState()
    var isVoiceActive by remember { mutableStateOf(false) }

    // Scheduling
    var showScheduleDialog by remember { mutableStateOf(false) }

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
            Toast.makeText(context, "Microphone permission required for voice input", Toast.LENGTH_SHORT).show()
        }
    }

    // Initialize voice manager
    DisposableEffect(Unit) {
        if (VoiceToTextManager.isAvailable(context)) {
            voiceManager.initialize()
        }
        onDispose {
            voiceManager.release()
        }
    }

    // Handle voice state changes
    LaunchedEffect(voiceState) {
        when (val state = voiceState) {
            is VoiceRecognitionState.Result -> {
                if (state.isFinal) {
                    isVoiceActive = false
                }
            }
            is VoiceRecognitionState.Error -> {
                isVoiceActive = false
                if (state.errorCode != 7) { // 7 = no match, ignore
                    Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
                }
            }
            is VoiceRecognitionState.Idle -> {
                isVoiceActive = false
            }
            else -> {}
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // Word Prediction Bar (shows when typing)
        WordPredictionBar(
            currentInput = value,
            onSuggestionSelected = onValueChange,
            isKeyboardVisible = value.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        )

        // Smart Replies Row
        if (smartReplies.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                smartReplies.take(3).forEach { suggestion ->
                    SuggestionChip(
                        onClick = { onValueChange(suggestion) },
                        label = { Text(suggestion, maxLines = 1) }
                    )
                }
            }
        }

        replyPreview?.let { reply ->
            ReplyPreviewBar(
                replyPreview = reply,
                onClear = onClearReply,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        // Voice recording indicator
        AnimatedVisibility(visible = isVoiceActive) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // Animated recording indicator
                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                val scale by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 1.3f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(500),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pulse"
                )

                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .scale(scale)
                        .background(Color.Red, CircleShape)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = "Listening...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )

                Spacer(modifier = Modifier.width(16.dp))

                // Cancel button
                TextButton(onClick = {
                    voiceManager.cancel()
                    isVoiceActive = false
                }) {
                    Text("Cancel")
                }
            }
        }

        // Input Row
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 2.dp,
            shadowElevation = 2.dp,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalIconButton(
                    onClick = {
                        if (isVoiceActive) {
                            voiceManager.stopListening()
                            isVoiceActive = false
                        } else {
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
                        }
                    },
                    modifier = Modifier.size(40.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (isVoiceActive) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (isVoiceActive) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                ) {
                    Icon(
                        imageVector = if (isVoiceActive) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (isVoiceActive) "Stop" else "Voice input"
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Write a message…") },
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        autoCorrect = true,
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (value.isNotBlank()) {
                                onSend(value)
                                onValueChange("")
                            }
                        }
                    ),
                    trailingIcon = {
                        if (value.isNotBlank()) {
                            Text(
                                text = "${value.length}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (value.length > 160) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        errorContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        errorIndicatorColor = Color.Transparent
                    )
                )

                Spacer(modifier = Modifier.width(6.dp))

                FilledTonalIconButton(
                    onClick = onAttach,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Default.AttachFile, contentDescription = "Attach")
                }

                Spacer(modifier = Modifier.width(4.dp))

                FilledTonalIconButton(
                    onClick = { showScheduleDialog = true },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Default.Schedule, contentDescription = "Schedule")
                }

                Spacer(modifier = Modifier.width(6.dp))

                FilledIconButton(
                    onClick = {
                        if (value.isNotBlank()) {
                            onSend(value)
                            onValueChange("")
                        }
                    },
                    enabled = value.isNotBlank(),
                    modifier = Modifier.size(44.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (value.isNotBlank()) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (value.isNotBlank()) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send"
                    )
                }
            }
        }
    }

    // Schedule dialog
    if (showScheduleDialog) {
        ScheduleMessageDialog(
            message = value,
            onDismiss = { showScheduleDialog = false },
            onSchedule = { time ->
                if (value.isNotBlank()) {
                    onSchedule(value, time)
                    onValueChange("")
                    showScheduleDialog = false
                }
            }
        )
    }
}

@Composable
private fun ReplyPreviewBar(
    replyPreview: ReplyPreview,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(IntrinsicSize.Min)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
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
                text = "Replying to ${replyPreview.sender}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = replyPreview.snippet,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }

        IconButton(onClick = onClear) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Cancel reply"
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleMessageDialog(
    message: String,
    onDismiss: () -> Unit,
    onSchedule: (Long) -> Unit
) {
    var selectedDate by remember { mutableStateOf(Calendar.getInstance()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Schedule Message") },
        text = {
            Column {
                // Message preview
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = message.ifBlank { "(No message)" },
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Date selection
                OutlinedCard(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.DateRange, contentDescription = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Date", style = MaterialTheme.typography.labelSmall)
                            Text(dateFormat.format(selectedDate.time))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Time selection
                OutlinedCard(
                    onClick = { showTimePicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Schedule, contentDescription = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Time", style = MaterialTheme.typography.labelSmall)
                            Text(timeFormat.format(selectedDate.time))
                        }
                    }
                }

                // Quick options
                Spacer(modifier = Modifier.height(16.dp))
                Text("Quick options:", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = false,
                        onClick = {
                            selectedDate = Calendar.getInstance().apply {
                                add(Calendar.HOUR, 1)
                            }
                        },
                        label = { Text("1 hour") }
                    )
                    FilterChip(
                        selected = false,
                        onClick = {
                            selectedDate = Calendar.getInstance().apply {
                                add(Calendar.HOUR, 3)
                            }
                        },
                        label = { Text("3 hours") }
                    )
                    FilterChip(
                        selected = false,
                        onClick = {
                            selectedDate = Calendar.getInstance().apply {
                                add(Calendar.DAY_OF_MONTH, 1)
                                set(Calendar.HOUR_OF_DAY, 9)
                                set(Calendar.MINUTE, 0)
                            }
                        },
                        label = { Text("Tomorrow 9AM") }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSchedule(selectedDate.timeInMillis) },
                enabled = message.isNotBlank() && selectedDate.timeInMillis > System.currentTimeMillis()
            ) {
                Text("Schedule")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )

    // Date picker
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedDate.timeInMillis
        )

        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val newDate = Calendar.getInstance().apply { timeInMillis = millis }
                        selectedDate.set(Calendar.YEAR, newDate.get(Calendar.YEAR))
                        selectedDate.set(Calendar.MONTH, newDate.get(Calendar.MONTH))
                        selectedDate.set(Calendar.DAY_OF_MONTH, newDate.get(Calendar.DAY_OF_MONTH))
                    }
                    showDatePicker = false
                }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // Time picker
    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = selectedDate.get(Calendar.HOUR_OF_DAY),
            initialMinute = selectedDate.get(Calendar.MINUTE)
        )

        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Select Time") },
            text = {
                TimePicker(state = timePickerState)
            },
            confirmButton = {
                TextButton(onClick = {
                    selectedDate.set(Calendar.HOUR_OF_DAY, timePickerState.hour)
                    selectedDate.set(Calendar.MINUTE, timePickerState.minute)
                    showTimePicker = false
                }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
