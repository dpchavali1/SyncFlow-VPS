package com.phoneintegration.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val syncState by SyncManager.syncState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var selectedDays by remember { mutableStateOf(30) }
    var showEstimateDialog by remember { mutableStateOf(false) }
    var estimateLoading by remember { mutableStateOf(false) }
    var estimateError by remember { mutableStateOf<String?>(null) }
    var estimateResult by remember { mutableStateOf<SyncManager.SyncEstimate?>(null) }

    // BANDWIDTH OPTIMIZATION: Free users limited to 30 days to stay within Firebase free tier
    // Pro users get extended sync options
    val isProUser = false // TODO: Implement pro subscription check

    val dayOptions = listOf(
        30 to "Last 30 days" to false, // Always available
        60 to "Last 60 days" to true,  // Pro only
        90 to "Last 90 days" to true,  // Pro only
        180 to "Last 6 months" to true, // Pro only
        365 to "Last year" to true,     // Pro only
        -1 to "All messages" to true    // Pro only
    )

    val selectedRangeLabel = dayOptions.firstOrNull { it.first.first == selectedDays }?.first?.second ?: "Selected range"

    fun requestEstimate() {
        if (syncState.isSyncing) return
        showEstimateDialog = true
        estimateLoading = true
        estimateError = null
        estimateResult = null
        scope.launch {
            try {
                estimateResult = SyncManager.estimateSync(context, selectedDays)
            } catch (e: Exception) {
                estimateError = e.message ?: "Failed to estimate sync size"
            } finally {
                estimateLoading = false
            }
        }
    }

    // Show toast when sync completes or fails
    LaunchedEffect(syncState.isComplete, syncState.error) {
        if (syncState.isComplete && syncState.error == null && syncState.totalCount > 0) {
            Toast.makeText(
                context,
                "Successfully synced ${syncState.syncedCount} messages",
                Toast.LENGTH_LONG
            ).show()
        } else if (syncState.error != null) {
            Toast.makeText(
                context,
                "Sync failed: ${syncState.error}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Info Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Filled.Sync, contentDescription = null)
                        Column {
                            Text(
                                "Sync Message History",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "Load older messages to Mac and Web",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    // Last sync info
                    if (syncState.lastSyncCompleted != null) {
                        HorizontalDivider()
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Last successful sync:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "Time:",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    syncState.lastSyncCompleted?.let { formatTimestamp(it) } ?: "Unknown",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "Range:",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    formatSyncRange(syncState.lastSyncDays),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "Messages:",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "${syncState.lastSyncMessageCount ?: 0} synced",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
            }

            // Description
            Text(
                "By default, SyncFlow syncs the last 30 days of messages. " +
                "Use this option to sync older messages to your Mac app or access them via sfweb.app in any browser.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Pro user info card
            if (!isProUser) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Free Tier Limit",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                text = "Free users can sync up to 30 days of message history. Upgrade to Pro to sync 60 days, 90 days, or your entire message history.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
            }

            // MMS note
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = "MMS messages will sync text only. Images and attachments are not included in history sync to improve speed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            // Day Selection
            Text(
                "Select time range:",
                style = MaterialTheme.typography.titleSmall
            )

            dayOptions.forEach { (dayData, isProOnly) ->
                val (days, label) = dayData
                val isEnabled = !syncState.isSyncing && (!isProOnly || isProUser)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        RadioButton(
                            selected = selectedDays == days,
                            onClick = {
                                if (isEnabled) {
                                    selectedDays = days
                                } else if (isProOnly && !isProUser) {
                                    Toast.makeText(
                                        context,
                                        "Upgrade to Pro to sync more than 30 days",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            enabled = isEnabled
                        )
                        Text(
                            text = label,
                            modifier = Modifier.padding(start = 8.dp),
                            color = if (isEnabled)
                                MaterialTheme.colorScheme.onSurface
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }

                    // Pro badge
                    if (isProOnly && !isProUser) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                        ) {
                            Text(
                                text = "PRO",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Sync Button
            Button(
                onClick = { requestEstimate() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !syncState.isSyncing
            ) {
                if (syncState.isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Icon(Icons.Filled.CloudDownload, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (syncState.isSyncing) "Syncing..." else "Sync Message History")
            }

            if (showEstimateDialog) {
                AlertDialog(
                    onDismissRequest = {
                        if (!estimateLoading) {
                            showEstimateDialog = false
                        }
                    },
                    title = { Text("Confirm message sync") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Range: $selectedRangeLabel")
                            if (estimateLoading) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Text(
                                    "Calculating message count and data size...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else if (estimateError != null) {
                                Text(
                                    "Unable to estimate: $estimateError",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            } else if (estimateResult != null) {
                                val estimate = estimateResult!!
                                Text("Messages: ${estimate.totalMessages}")
                                Text("Estimated data: ${formatBytes(estimate.estimatedBytes)}")
                                Text("Estimated time: ${formatDurationRange(estimate.minMinutes, estimate.maxMinutes)}")
                                Text(
                                    "MMS attachments are excluded to keep the sync fast.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (estimate.totalMessages == 0) {
                                    Text(
                                        "No messages found for this range.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        val canStart = estimateResult != null &&
                            estimateResult!!.totalMessages > 0 &&
                            !estimateLoading &&
                            estimateError == null
                        TextButton(
                            onClick = {
                                showEstimateDialog = false
                                SyncManager.startSync(context, selectedDays)
                            },
                            enabled = canStart
                        ) {
                            Text("Start sync")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { showEstimateDialog = false },
                            enabled = !estimateLoading
                        ) {
                            Text("Cancel")
                        }
                    }
                )
            }

            // Progress
            if (syncState.isSyncing || syncState.status.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (syncState.error != null)
                            MaterialTheme.colorScheme.errorContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            syncState.status,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (syncState.error != null)
                                MaterialTheme.colorScheme.onErrorContainer
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (syncState.totalCount > 0) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { syncState.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${syncState.syncedCount} / ${syncState.totalCount} messages",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Sync again button (after completion)
            if (syncState.isComplete) {
                OutlinedButton(
                    onClick = { SyncManager.resetState() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Sync Again")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Note
            Text(
                "Note: Sync continues in background even if you leave this screen. " +
                "Keep the app open until sync completes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
        bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
        bytes >= kb -> String.format(Locale.US, "%.0f KB", bytes / kb)
        else -> "$bytes B"
    }
}

private fun formatDurationRange(minMinutes: Int, maxMinutes: Int): String {
    if (minMinutes <= 0 && maxMinutes <= 0) return "Under 1 min"
    if (minMinutes == maxMinutes) return formatMinutes(minMinutes)
    return "${formatMinutes(minMinutes)} - ${formatMinutes(maxMinutes)}"
}

private fun formatMinutes(minutes: Int): String {
    if (minutes <= 0) return "Under 1 min"
    if (minutes < 60) return "$minutes min"
    val hours = minutes / 60
    val rem = minutes % 60
    return if (rem == 0) "$hours hr" else "$hours hr $rem min"
}

private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24

    return when {
        days > 7 -> {
            val date = java.text.SimpleDateFormat("MMM d, yyyy h:mm a", Locale.US).format(java.util.Date(timestamp))
            date
        }
        days > 0 -> "$days day${if (days > 1) "s" else ""} ago"
        hours > 0 -> "$hours hour${if (hours > 1) "s" else ""} ago"
        minutes > 0 -> "$minutes min ago"
        else -> "Just now"
    }
}

private fun formatSyncRange(days: Int?): String {
    return when (days) {
        null -> "Unknown"
        -1, 3650 -> "All messages"
        30 -> "Last 30 days"
        60 -> "Last 60 days"
        90 -> "Last 90 days"
        180 -> "Last 6 months"
        365 -> "Last year"
        else -> "Last $days days"
    }
}
