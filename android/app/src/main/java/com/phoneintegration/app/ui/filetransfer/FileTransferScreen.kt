package com.phoneintegration.app.ui.filetransfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoneintegration.app.desktop.DesktopSyncService
import com.phoneintegration.app.desktop.FileTransferService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DecimalFormat

data class FileTransferItem(
    val id: String,
    val fileName: String,
    val fileSize: Long,
    val status: FileTransferStatus,
    val progress: Float = 0f,
    val error: String? = null
)

enum class FileTransferStatus {
    PENDING, UPLOADING, SENT, FAILED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileTransferScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isPaired by remember { mutableStateOf(false) }
    var transfers by remember { mutableStateOf<List<FileTransferItem>>(emptyList()) }
    var isUploading by remember { mutableStateOf(false) }
    var limits by remember { mutableStateOf<FileTransferService.TransferLimits?>(null) }

    val fileTransferService = remember { FileTransferService(context) }

    // Check if paired and load limits
    LaunchedEffect(Unit) {
        isPaired = DesktopSyncService.hasPairedDevices(context)
        limits = fileTransferService.getTransferLimits()
    }

    // Refresh limits after each transfer
    fun refreshLimits() {
        scope.launch {
            limits = fileTransferService.getTransferLimits()
        }
    }

    // File picker
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                uploadFile(context, fileTransferService, uri) { item ->
                    transfers = listOf(item) + transfers.filter { it.id != item.id }
                    if (item.status == FileTransferStatus.SENT || item.status == FileTransferStatus.FAILED) {
                        refreshLimits()
                    }
                }
            }
        }
    }

    // Multiple file picker
    val multiFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        uris.forEach { uri ->
            scope.launch {
                uploadFile(context, fileTransferService, uri) { item ->
                    transfers = listOf(item) + transfers.filter { it.id != item.id }
                    if (item.status == FileTransferStatus.SENT || item.status == FileTransferStatus.FAILED) {
                        refreshLimits()
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Send Files to Mac") },
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
        ) {
            if (!isPaired) {
                // Not paired message
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            Icons.Default.LinkOff,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "No Mac Paired",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            "Pair your Mac to send files. Go to Settings → Pair Device and scan the QR code from your Mac.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // File transfer UI
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Limits info card
                    limits?.let { l ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (l.isPro)
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                else
                                    MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        if (l.isPro) "Pro Plan" else "Free Plan",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (l.isPro)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        "Max file: ${l.maxFileSize / (1024 * 1024)}MB",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                // Upgrade hint for free users
                                if (!l.isPro) {
                                    Text(
                                        "Upgrade to Pro for 1GB file transfers",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }

                    // Action buttons
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                "Share files with your Mac",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "Files will be saved to Downloads/SyncFlow on your Mac",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = { filePicker.launch("*/*") },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.AttachFile, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Select File")
                                }

                                OutlinedButton(
                                    onClick = { multiFilePicker.launch("*/*") },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Folder, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Multiple")
                                }
                            }
                        }
                    }

                    // Quick share buttons
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AssistChip(
                            onClick = { filePicker.launch("image/*") },
                            label = { Text("Photos") },
                            leadingIcon = { Icon(Icons.Default.Image, null, Modifier.size(18.dp)) }
                        )
                        AssistChip(
                            onClick = { filePicker.launch("video/*") },
                            label = { Text("Videos") },
                            leadingIcon = { Icon(Icons.Default.VideoFile, null, Modifier.size(18.dp)) }
                        )
                        AssistChip(
                            onClick = { filePicker.launch("application/pdf") },
                            label = { Text("PDFs") },
                            leadingIcon = { Icon(Icons.Default.PictureAsPdf, null, Modifier.size(18.dp)) }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Transfer history
                    if (transfers.isNotEmpty()) {
                        Text(
                            "Recent Transfers",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )

                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp)
                        ) {
                            items(transfers) { transfer ->
                                FileTransferItemRow(transfer)
                            }
                        }
                    } else {
                        // Empty state
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.CloudUpload,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Text(
                                    "No files sent yet",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Info footer
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        tonalElevation = 1.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Info,
                                null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            limits?.let { l ->
                                Text(
                                    "Max file size: ${l.maxFileSize / (1024 * 1024)}MB${if (l.isPro) " (Pro)" else ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } ?: Text(
                                "Loading limits...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileTransferItemRow(transfer: FileTransferItem) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Icon
            Icon(
                when (transfer.status) {
                    FileTransferStatus.PENDING, FileTransferStatus.UPLOADING -> Icons.Default.CloudUpload
                    FileTransferStatus.SENT -> Icons.Default.CheckCircle
                    FileTransferStatus.FAILED -> Icons.Default.Error
                },
                contentDescription = null,
                tint = when (transfer.status) {
                    FileTransferStatus.PENDING, FileTransferStatus.UPLOADING -> MaterialTheme.colorScheme.primary
                    FileTransferStatus.SENT -> MaterialTheme.colorScheme.primary
                    FileTransferStatus.FAILED -> MaterialTheme.colorScheme.error
                }
            )

            // File info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    transfer.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    formatFileSize(transfer.fileSize),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Progress bar for uploading
                if (transfer.status == FileTransferStatus.UPLOADING) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { transfer.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Error message
                transfer.error?.let { error ->
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // Status text
            Text(
                when (transfer.status) {
                    FileTransferStatus.PENDING -> "Pending"
                    FileTransferStatus.UPLOADING -> "${(transfer.progress * 100).toInt()}%"
                    FileTransferStatus.SENT -> "Sent"
                    FileTransferStatus.FAILED -> "Failed"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private suspend fun uploadFile(
    context: Context,
    service: FileTransferService,
    uri: Uri,
    onUpdate: (FileTransferItem) -> Unit
) {
    withContext(Dispatchers.IO) {
        val fileId = System.currentTimeMillis().toString()

        // Get file info
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        var fileName = "file"
        var fileSize = 0L

        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) fileName = it.getString(nameIndex)
                if (sizeIndex >= 0) fileSize = it.getLong(sizeIndex)
            }
        }

        // Get content type
        val contentType = context.contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                fileName.substringAfterLast(".", "")
            )
            ?: "application/octet-stream"

        // Update UI - pending
        withContext(Dispatchers.Main) {
            onUpdate(FileTransferItem(
                id = fileId,
                fileName = fileName,
                fileSize = fileSize,
                status = FileTransferStatus.UPLOADING,
                progress = 0f
            ))
        }

        try {
            // Copy to temp file
            val tempFile = File(context.cacheDir, "upload_$fileName")
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // Upload
            val success = service.uploadFile(tempFile, fileName, contentType)

            // Update UI
            withContext(Dispatchers.Main) {
                onUpdate(FileTransferItem(
                    id = fileId,
                    fileName = fileName,
                    fileSize = fileSize,
                    status = if (success) FileTransferStatus.SENT else FileTransferStatus.FAILED,
                    progress = if (success) 1f else 0f,
                    error = if (!success) "Upload failed" else null
                ))
            }

            // Clean up temp file
            tempFile.delete()
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onUpdate(FileTransferItem(
                    id = fileId,
                    fileName = fileName,
                    fileSize = fileSize,
                    status = FileTransferStatus.FAILED,
                    error = e.message
                ))
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    val format = DecimalFormat("#,##0.#")
    return "${format.format(bytes / Math.pow(1024.0, digitGroups.toDouble()))} ${units[digitGroups]}"
}
