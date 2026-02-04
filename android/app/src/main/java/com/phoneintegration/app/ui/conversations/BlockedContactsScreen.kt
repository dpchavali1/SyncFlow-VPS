package com.phoneintegration.app.ui.conversations

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoneintegration.app.data.database.AppDatabase
import com.phoneintegration.app.data.database.BlockedContact
import com.phoneintegration.app.ui.components.SyncFlowAvatar
import com.phoneintegration.app.ui.components.AvatarSize
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedContactsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val database = remember { AppDatabase.getInstance(context) }

    val blockedContacts by database.blockedContactDao().getAllBlocked().collectAsState(initial = emptyList())
    var contactToUnblock by remember { mutableStateOf<BlockedContact?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Blocked Contacts")
                        if (blockedContacts.isNotEmpty()) {
                            Text(
                                "${blockedContacts.size} blocked",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
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
        if (blockedContacts.isEmpty()) {
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
                    Icon(
                        Icons.Default.PersonOff,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No blocked contacts",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Contacts you block will appear here",
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
                items(blockedContacts, key = { it.phoneNumber }) { contact ->
                    BlockedContactItem(
                        contact = contact,
                        onUnblock = { contactToUnblock = contact }
                    )
                }
            }
        }
    }

    // Unblock Confirmation Dialog
    contactToUnblock?.let { contact ->
        AlertDialog(
            onDismissRequest = { contactToUnblock = null },
            title = { Text("Unblock Contact") },
            text = {
                Text("Unblock ${contact.displayName ?: contact.phoneNumber}? You will start receiving messages from this contact again.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            database.blockedContactDao().unblock(contact.phoneNumber)
                            Toast.makeText(
                                context,
                                "${contact.displayName ?: contact.phoneNumber} unblocked",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        contactToUnblock = null
                    }
                ) {
                    Text("Unblock")
                }
            },
            dismissButton = {
                TextButton(onClick = { contactToUnblock = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun BlockedContactItem(
    contact: BlockedContact,
    onUnblock: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()) }

    ListItem(
        headlineContent = {
            Text(
                contact.displayName ?: contact.phoneNumber,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Column {
                if (contact.displayName != null) {
                    Text(
                        contact.phoneNumber,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    "Blocked ${dateFormat.format(Date(contact.blockedAt))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                if (contact.reason != null) {
                    Text(
                        "Reason: ${contact.reason}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        leadingContent = {
            SyncFlowAvatar(
                name = contact.displayName ?: contact.phoneNumber,
                size = AvatarSize.Medium
            )
        },
        trailingContent = {
            TextButton(onClick = onUnblock) {
                Text("Unblock")
            }
        }
    )
    HorizontalDivider()
}
