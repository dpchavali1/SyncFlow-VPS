package com.phoneintegration.app.ui.settings

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeleteAccountScreen(
    onBack: () -> Unit,
    onAccountDeleted: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var isLoading by remember { mutableStateOf(true) }
    var isScheduledForDeletion by remember { mutableStateOf(false) }
    var scheduledDeletionAt by remember { mutableStateOf<Long?>(null) }
    var daysRemaining by remember { mutableStateOf(0) }

    var showConfirmDialog by remember { mutableStateOf(false) }
    var showCancelDialog by remember { mutableStateOf(false) }
    var selectedReason by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }

    val deletionReasons = listOf(
        "I don't use the app anymore",
        "I'm switching to another app",
        "Privacy concerns",
        "Too many bugs or issues",
        "Missing features I need",
        "Other"
    )

    // Check deletion status on load
    LaunchedEffect(Unit) {
        try {
            val functions = FirebaseFunctions.getInstance()
            val result = functions
                .getHttpsCallable("getAccountDeletionStatus")
                .call()
                .await()

            val data = result.data as? Map<*, *>
            isScheduledForDeletion = data?.get("isScheduledForDeletion") as? Boolean ?: false
            scheduledDeletionAt = (data?.get("scheduledDeletionAt") as? Number)?.toLong()
            daysRemaining = (data?.get("daysRemaining") as? Number)?.toInt() ?: 0
        } catch (e: Exception) {
            // Not scheduled or error
            isScheduledForDeletion = false
        } finally {
            isLoading = false
        }
    }

    // Request deletion
    fun requestDeletion() {
        scope.launch {
            isProcessing = true
            try {
                val functions = FirebaseFunctions.getInstance()
                val result = functions
                    .getHttpsCallable("requestAccountDeletion")
                    .call(mapOf("reason" to selectedReason))
                    .await()

                val data = result.data as? Map<*, *>
                val success = data?.get("success") as? Boolean ?: false

                if (success) {
                    scheduledDeletionAt = (data?.get("scheduledDeletionAt") as? Number)?.toLong()
                    daysRemaining = 30
                    isScheduledForDeletion = true
                    showConfirmDialog = false
                    snackbarHostState.showSnackbar("Account scheduled for deletion in 30 days")
                }
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Failed to schedule deletion: ${e.message}")
            } finally {
                isProcessing = false
            }
        }
    }

    // Cancel deletion
    fun cancelDeletion() {
        scope.launch {
            isProcessing = true
            try {
                val functions = FirebaseFunctions.getInstance()
                val result = functions
                    .getHttpsCallable("cancelAccountDeletion")
                    .call()
                    .await()

                val data = result.data as? Map<*, *>
                val success = data?.get("success") as? Boolean ?: false

                if (success) {
                    isScheduledForDeletion = false
                    scheduledDeletionAt = null
                    showCancelDialog = false
                    snackbarHostState.showSnackbar("Account deletion cancelled")
                }
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Failed to cancel deletion: ${e.message}")
            } finally {
                isProcessing = false
            }
        }
    }

    // Confirm deletion dialog
    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { if (!isProcessing) showConfirmDialog = false },
            icon = {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Delete Account?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        "Your account will be permanently deleted in 30 days. " +
                        "You can cancel anytime before then."
                    )

                    Text(
                        "Why are you leaving?",
                        style = MaterialTheme.typography.labelLarge
                    )

                    Column {
                        deletionReasons.forEach { reason ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedReason == reason,
                                    onClick = { selectedReason = reason }
                                )
                                Text(
                                    text = reason,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { requestDeletion() },
                    enabled = selectedReason.isNotEmpty() && !isProcessing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onError,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Delete Account")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showConfirmDialog = false },
                    enabled = !isProcessing
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // Cancel deletion dialog
    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { if (!isProcessing) showCancelDialog = false },
            icon = {
                Icon(
                    Icons.Filled.Restore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text("Keep Your Account?") },
            text = {
                Text("Your account will be restored and all your data will remain intact.")
            },
            confirmButton = {
                Button(
                    onClick = { cancelDeletion() },
                    enabled = !isProcessing
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Keep Account")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCancelDialog = false },
                    enabled = !isProcessing
                ) {
                    Text("Go Back")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Delete Account") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Status card
                if (isScheduledForDeletion) {
                    // Account is scheduled for deletion
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Schedule,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    "Account Scheduled for Deletion",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }

                            Text(
                                "Your account will be permanently deleted in $daysRemaining days.",
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )

                            scheduledDeletionAt?.let { timestamp ->
                                val dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
                                Text(
                                    "Deletion date: ${dateFormat.format(Date(timestamp))}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                                )
                            }

                            Button(
                                onClick = { showCancelDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(Icons.Filled.Restore, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Cancel Deletion & Keep Account")
                            }
                        }
                    }
                } else {
                    // Normal state - account not scheduled for deletion
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Filled.PersonOff,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.error
                            )

                            Text(
                                "Delete Your Account",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                "We're sorry to see you go. Before you delete your account, " +
                                "please review what will happen:",
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // What gets deleted
                Card {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "What will be deleted:",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )

                        DeletionItem(
                            icon = Icons.Filled.Message,
                            text = "All synced messages"
                        )
                        DeletionItem(
                            icon = Icons.Filled.Devices,
                            text = "All connected devices"
                        )
                        DeletionItem(
                            icon = Icons.Filled.Key,
                            text = "Your recovery code"
                        )
                        DeletionItem(
                            icon = Icons.Filled.CreditCard,
                            text = "Subscription data"
                        )
                        DeletionItem(
                            icon = Icons.Filled.Person,
                            text = "All personal information"
                        )
                    }
                }

                // 30-day grace period info
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                "30-Day Grace Period",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Your account won't be deleted immediately. You have 30 days to change your mind and cancel the deletion.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                // Warning about recovery code
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Column {
                            Text(
                                "Recovery Code Disabled",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Once scheduled for deletion, your recovery code will stop working immediately. You won't be able to recover your account on new devices.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                // Delete button (only show if not already scheduled)
                if (!isScheduledForDeletion) {
                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { showConfirmDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Filled.DeleteForever, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Delete My Account")
                    }

                    Text(
                        "This will schedule your account for deletion in 30 days.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun DeletionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
