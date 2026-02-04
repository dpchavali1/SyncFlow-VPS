package com.phoneintegration.app.ui.conversations

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoneintegration.app.data.database.AppDatabase
import com.phoneintegration.app.data.database.SpamMessage
import com.phoneintegration.app.ui.components.SyncFlowAvatar
import com.phoneintegration.app.ui.components.AvatarSize
import com.phoneintegration.app.ui.components.SyncFlowEmptyState
import com.phoneintegration.app.ui.components.EmptyStateType
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpamFolderScreen(
    onBack: () -> Unit,
    onOpenConversation: (address: String, name: String) -> Unit = { _, _ -> },
    viewModel: com.phoneintegration.app.SmsViewModel? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val database = remember { AppDatabase.getInstance(context) }
    val syncService = remember { com.phoneintegration.app.desktop.DesktopSyncService(context.applicationContext) }
    val spamFilterService = remember { com.phoneintegration.app.spam.SpamFilterService.getInstance(context) }

    // Refresh spam from cloud when screen opens
    LaunchedEffect(Unit) {
        viewModel?.refreshSpamFromCloud()
    }

    val spamMessages by database.spamMessageDao().getAllSpam().collectAsState(initial = emptyList())
    var showClearAllDialog by remember { mutableStateOf(false) }
    var selectedSpamConversation by remember { mutableStateOf<SpamConversation?>(null) }

    // Group spam by address
    val groupedSpam = remember(spamMessages) {
        spamMessages.groupBy { it.address }
            .map { (address, messages) ->
                SpamConversation(
                    address = address,
                    contactName = messages.firstOrNull()?.contactName ?: address,
                    latestMessage = messages.maxByOrNull { it.date },
                    messageCount = messages.size
                )
            }
            .sortedByDescending { it.latestMessage?.date ?: 0 }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Spam")
                        if (spamMessages.isNotEmpty()) {
                            Text(
                                "${spamMessages.size} messages",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (spamMessages.isNotEmpty()) {
                        IconButton(onClick = { showClearAllDialog = true }) {
                            Icon(Icons.Default.DeleteSweep, "Clear all spam")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (groupedSpam.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "🎉",
                        style = MaterialTheme.typography.displayLarge
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No spam messages",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Messages identified as spam will appear here",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(groupedSpam, key = { it.address }) { conversation ->
                    SpamConversationItem(
                        conversation = conversation,
                        onClick = {
                            selectedSpamConversation = conversation
                        },
                        onRestore = {
                            scope.launch {
                                val ids = spamMessages
                                    .filter { it.address == conversation.address }
                                    .map { it.messageId }
                                // Add to whitelist to prevent future detection
                                spamFilterService.addToWhitelist(conversation.address)
                                database.spamMessageDao().deleteByAddress(conversation.address)
                                // Only sync to cloud if devices are paired
                                if (com.phoneintegration.app.desktop.DesktopSyncService.hasPairedDevices(context)) {
                                    ids.forEach { syncService.deleteSpamMessage(it) }
                                }
                                Toast.makeText(context, "Marked as not spam", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onDelete = {
                            scope.launch {
                                val ids = spamMessages
                                    .filter { it.address == conversation.address }
                                    .map { it.messageId }
                                database.spamMessageDao().deleteByAddress(conversation.address)
                                // Only sync to cloud if devices are paired
                                if (com.phoneintegration.app.desktop.DesktopSyncService.hasPairedDevices(context)) {
                                    ids.forEach { syncService.deleteSpamMessage(it) }
                                }
                                Toast.makeText(context, "Deleted permanently", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
        }
    }

    // Spam Message Detail Dialog
    selectedSpamConversation?.let { conversation ->
        val messagesForConversation = remember(conversation, spamMessages) {
            spamMessages.filter { it.address == conversation.address }
                .sortedByDescending { it.date }
        }

        AlertDialog(
            onDismissRequest = { selectedSpamConversation = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(conversation.contactName)
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    messagesForConversation.forEachIndexed { index, spam ->
                        if (index > 0) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        }

                        // Date
                        Text(
                            SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())
                                .format(Date(spam.date)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        // Full message body
                        Text(
                            spam.body,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        // Spam reasons if available
                        if (!spam.spamReasons.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    "Spam reason: ${spam.spamReasons}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        // Confidence score
                        Text(
                            "Confidence: ${(spam.spamConfidence * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val ids = messagesForConversation.map { it.messageId }
                            // Add to whitelist to prevent future detection
                            spamFilterService.addToWhitelist(conversation.address)
                            database.spamMessageDao().deleteByAddress(conversation.address)
                            if (com.phoneintegration.app.desktop.DesktopSyncService.hasPairedDevices(context)) {
                                ids.forEach { syncService.deleteSpamMessage(it) }
                            }
                            Toast.makeText(context, "Marked as not spam", Toast.LENGTH_SHORT).show()
                        }
                        selectedSpamConversation = null
                    }
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Not Spam")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedSpamConversation = null }) {
                    Text("Close")
                }
            }
        )
    }

    // Clear All Dialog
    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("Clear All Spam") },
            text = { Text("This will permanently delete all ${spamMessages.size} spam messages. This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            database.spamMessageDao().clearAll()
                            // Only sync to cloud if devices are paired
                            if (com.phoneintegration.app.desktop.DesktopSyncService.hasPairedDevices(context)) {
                                syncService.clearAllSpamMessages()
                            }
                            Toast.makeText(context, "All spam cleared", Toast.LENGTH_SHORT).show()
                        }
                        showClearAllDialog = false
                    }
                ) {
                    Text("Delete All", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private data class SpamConversation(
    val address: String,
    val contactName: String,
    val latestMessage: SpamMessage?,
    val messageCount: Int
)

@Composable
private fun SpamConversationItem(
    conversation: SpamConversation,
    onClick: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                conversation.contactName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Column {
                Text(
                    conversation.latestMessage?.body ?: "",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "${conversation.messageCount} message${if (conversation.messageCount > 1) "s" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        leadingContent = {
            SyncFlowAvatar(
                name = conversation.contactName,
                size = AvatarSize.Medium
            )
        },
        trailingContent = {
            Row {
                IconButton(onClick = onRestore) {
                    Icon(
                        Icons.Default.Restore,
                        "Not spam",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        "Delete",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        modifier = Modifier.clickable { onClick() }
    )
    HorizontalDivider()
}
