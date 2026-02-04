package com.phoneintegration.app.ui.downloads

import android.app.DownloadManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.phoneintegration.app.desktop.SyncFlowDownloadsRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncFlowDownloadsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val downloadsRepository = remember { SyncFlowDownloadsRepository(appContext) }

    var downloads by remember { mutableStateOf<List<SyncFlowDownloadsRepository.DownloadEntry>>(emptyList()) }
    var downloadsError by remember { mutableStateOf<String?>(null) }
    var isDownloadsLoading by remember { mutableStateOf(false) }
    var selectedDownloadIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showDeleteDownloadsDialog by remember { mutableStateOf(false) }

    val refreshDownloads: suspend () -> Unit = {
        isDownloadsLoading = true
        downloadsError = null
        downloads = try {
            downloadsRepository.loadDownloads()
        } catch (e: Exception) {
            downloadsError = e.message ?: "Failed to refresh"
            emptyList()
        }
        selectedDownloadIds = selectedDownloadIds.intersect(downloads.map { it.id }.toSet())
        isDownloadsLoading = false
    }

    LaunchedEffect(Unit) {
        refreshDownloads()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SyncFlow Downloads") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                try {
                                    val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Unable to open downloads", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.FolderOpen, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Open Downloads")
                        }

                        OutlinedButton(
                            onClick = { scope.launch { refreshDownloads() } },
                            enabled = !isDownloadsLoading
                        ) {
                            Icon(Icons.Default.Refresh, null)
                        }
                    }

                    OutlinedButton(
                        onClick = { showDeleteDownloadsDialog = true },
                        enabled = selectedDownloadIds.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Delete, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Delete Selected")
                    }
                }
            }

            when {
                downloadsError != null -> {
                    item {
                        Text(
                            text = downloadsError ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
                isDownloadsLoading -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
                downloads.isEmpty() -> {
                    item {
                        Text(
                            text = "No SyncFlow downloads yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
                else -> {
                    items(downloads) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selectedDownloadIds.contains(entry.id),
                                onCheckedChange = { checked ->
                                    selectedDownloadIds = if (checked) {
                                        selectedDownloadIds + entry.id
                                    } else {
                                        selectedDownloadIds - entry.id
                                    }
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.displayName,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "${formatFileSize(entry.sizeBytes)} • ${formatTimestamp(entry.modifiedSeconds)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showDeleteDownloadsDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDownloadsDialog = false },
            title = { Text("Delete downloads?") },
            text = { Text("This will delete the selected files from Downloads/SyncFlow.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDownloadsDialog = false
                    scope.launch {
                        val toDelete = downloads.filter { selectedDownloadIds.contains(it.id) }
                        val deletedCount = downloadsRepository.deleteDownloads(toDelete)
                        if (deletedCount > 0) {
                            Toast.makeText(context, "Deleted $deletedCount files", Toast.LENGTH_SHORT).show()
                        }
                        downloads = downloadsRepository.loadDownloads()
                        selectedDownloadIds = emptySet()
                    }
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDownloadsDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> String.format(Locale.US, "%.1f GB", gb)
        mb >= 1 -> String.format(Locale.US, "%.1f MB", mb)
        kb >= 1 -> String.format(Locale.US, "%.1f KB", kb)
        else -> "$bytes B"
    }
}

private fun formatTimestamp(seconds: Long): String {
    if (seconds <= 0) return "unknown"
    val date = Date(seconds * 1000)
    val formatter = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
    return formatter.format(date)
}
