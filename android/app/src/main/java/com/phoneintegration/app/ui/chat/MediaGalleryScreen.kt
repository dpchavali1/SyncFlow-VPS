package com.phoneintegration.app.ui.chat

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.phoneintegration.app.SmsMessage
import com.phoneintegration.app.SmsViewModel
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MediaItem(
    val uri: String,
    val type: String,  // "image" or "video"
    val timestamp: Long,
    val messageId: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaGalleryScreen(
    contactName: String,
    messages: List<SmsMessage>,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var selectedMedia by remember { mutableStateOf<MediaItem?>(null) }

    // Extract all media from messages
    val mediaItems = remember(messages) {
        messages.flatMap { message ->
            message.mmsAttachments.mapNotNull { attachment ->
                val filePath = attachment.filePath ?: return@mapNotNull null
                val type = when {
                    attachment.contentType.startsWith("image/") -> "image"
                    attachment.contentType.startsWith("video/") -> "video"
                    else -> null
                }
                type?.let {
                    MediaItem(
                        uri = filePath,
                        type = it,
                        timestamp = message.date,
                        messageId = message.id
                    )
                }
            }
        }.sortedByDescending { it.timestamp }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Media")
                        Text(
                            text = "${mediaItems.size} items",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (mediaItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No media shared yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(mediaItems) { media ->
                    MediaThumbnail(
                        media = media,
                        onClick = { selectedMedia = media }
                    )
                }
            }
        }
    }

    // Full-screen media viewer
    selectedMedia?.let { media ->
        MediaViewerDialog(
            media = media,
            onDismiss = { selectedMedia = null },
            onShare = {
                try {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = if (media.type == "image") "image/*" else "video/*"
                        putExtra(Intent.EXTRA_STREAM, Uri.parse(media.uri))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share media"))
                } catch (e: Exception) {
                    Toast.makeText(context, "Failed to share", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}

@Composable
private fun MediaThumbnail(
    media: MediaItem,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = media.uri,
            contentDescription = "Media",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Video indicator overlay
        if (media.type == "video") {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Video",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

@Composable
private fun MediaViewerDialog(
    media: MediaItem,
    onDismiss: () -> Unit,
    onShare: () -> Unit
) {
    val dateFormatter = remember { SimpleDateFormat("MMM dd, yyyy 'at' h:mm a", Locale.getDefault()) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Media content
                AsyncImage(
                    model = media.uri,
                    contentDescription = "Full size media",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 56.dp, bottom = 80.dp),
                    contentScale = ContentScale.Fit
                )

                // Top bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White
                        )
                    }

                    Text(
                        text = dateFormatter.format(Date(media.timestamp)),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )

                    IconButton(onClick = onShare) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                            tint = Color.White
                        )
                    }
                }

                // Bottom bar with info
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = if (media.type == "image") "Photo" else "Video",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }
    }
}
