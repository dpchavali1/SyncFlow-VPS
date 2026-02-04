/**
 * VPS Settings Screen
 *
 * This screen allows users to configure the VPS backend settings,
 * including switching between Firebase and VPS backends during migration.
 */
package com.phoneintegration.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.phoneintegration.app.vps.SyncBackend
import com.phoneintegration.app.vps.SyncBackendConfig
import com.phoneintegration.app.vps.VPSAuthManager
import com.phoneintegration.app.vps.VPSAuthState
import com.phoneintegration.app.vps.VPSSyncService
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpsSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backendConfig = remember { SyncBackendConfig.getInstance(context) }
    val vpsAuthManager = remember { VPSAuthManager.getInstance(context) }
    val vpsSyncService = remember { VPSSyncService.getInstance(context) }

    val currentBackend by backendConfig.currentBackend.collectAsState()
    val vpsUrl by backendConfig.vpsUrl.collectAsState()
    val authState by vpsAuthManager.authState.collectAsState()
    val connectionState by vpsSyncService.connectionState.collectAsState()

    var showBackendDialog by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var customUrl by remember { mutableStateOf(vpsUrl) }
    var isConnecting by remember { mutableStateOf(false) }
    var connectionError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VPS Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // Backend Selection
            Text(
                text = "Sync Backend",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            ListItem(
                headlineContent = { Text("Current Backend") },
                supportingContent = {
                    Text(
                        when (currentBackend) {
                            SyncBackend.FIREBASE -> "Firebase (Original)"
                            SyncBackend.VPS -> "VPS Server (PostgreSQL)"
                            SyncBackend.HYBRID -> "Hybrid (Both)"
                        }
                    )
                },
                leadingContent = {
                    Icon(
                        when (currentBackend) {
                            SyncBackend.FIREBASE -> Icons.Filled.Cloud
                            SyncBackend.VPS -> Icons.Filled.Storage
                            SyncBackend.HYBRID -> Icons.Filled.SyncAlt
                        },
                        contentDescription = null
                    )
                },
                trailingContent = {
                    Icon(Icons.Filled.ChevronRight, contentDescription = null)
                },
                modifier = Modifier.clickable { showBackendDialog = true }
            )

            Divider()

            // VPS Connection Status
            Text(
                text = "VPS Connection",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            ListItem(
                headlineContent = { Text("Server URL") },
                supportingContent = { Text(vpsUrl) },
                leadingContent = {
                    Icon(Icons.Filled.Link, contentDescription = null)
                },
                trailingContent = {
                    Icon(Icons.Filled.Edit, contentDescription = null)
                },
                modifier = Modifier.clickable { showUrlDialog = true }
            )

            ListItem(
                headlineContent = { Text("Connection Status") },
                supportingContent = {
                    Text(
                        if (connectionState) "Connected" else "Disconnected"
                    )
                },
                leadingContent = {
                    Icon(
                        if (connectionState) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                        contentDescription = null,
                        tint = if (connectionState) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
            )

            ListItem(
                headlineContent = { Text("Authentication") },
                supportingContent = {
                    Text(
                        when (authState) {
                            is VPSAuthState.Authenticated -> "Signed in as ${(authState as VPSAuthState.Authenticated).user.userId.take(8)}..."
                            is VPSAuthState.Error -> "Error: ${(authState as VPSAuthState.Error).message}"
                            VPSAuthState.Unauthenticated -> "Not signed in"
                        }
                    )
                },
                leadingContent = {
                    Icon(
                        when (authState) {
                            is VPSAuthState.Authenticated -> Icons.Filled.Person
                            is VPSAuthState.Error -> Icons.Filled.Error
                            VPSAuthState.Unauthenticated -> Icons.Filled.PersonOff
                        },
                        contentDescription = null,
                        tint = when (authState) {
                            is VPSAuthState.Authenticated -> MaterialTheme.colorScheme.primary
                            is VPSAuthState.Error -> MaterialTheme.colorScheme.error
                            VPSAuthState.Unauthenticated -> MaterialTheme.colorScheme.outline
                        }
                    )
                }
            )

            connectionError?.let { error ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            Divider()

            // Actions
            Text(
                text = "Actions",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Test Connection Button
            ListItem(
                headlineContent = { Text("Test VPS Connection") },
                supportingContent = { Text("Verify connectivity to VPS server") },
                leadingContent = {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Filled.NetworkCheck, contentDescription = null)
                    }
                },
                modifier = Modifier.clickable(enabled = !isConnecting) {
                    scope.launch {
                        isConnecting = true
                        connectionError = null
                        try {
                            val success = vpsSyncService.initialize()
                            if (success) {
                                connectionError = null
                            } else {
                                connectionError = "Failed to connect to VPS server"
                            }
                        } catch (e: Exception) {
                            connectionError = "Connection failed: ${e.message}"
                        } finally {
                            isConnecting = false
                        }
                    }
                }
            )

            // Sign in anonymously (if not authenticated and using VPS)
            if (authState == VPSAuthState.Unauthenticated &&
                (currentBackend == SyncBackend.VPS || currentBackend == SyncBackend.HYBRID)
            ) {
                ListItem(
                    headlineContent = { Text("Sign In to VPS") },
                    supportingContent = { Text("Create anonymous VPS account") },
                    leadingContent = {
                        Icon(Icons.Filled.Login, contentDescription = null)
                    },
                    modifier = Modifier.clickable {
                        scope.launch {
                            isConnecting = true
                            connectionError = null
                            try {
                                val result = vpsAuthManager.signInAnonymously()
                                if (result.isFailure) {
                                    connectionError = "Sign in failed: ${result.exceptionOrNull()?.message}"
                                }
                            } catch (e: Exception) {
                                connectionError = "Sign in failed: ${e.message}"
                            } finally {
                                isConnecting = false
                            }
                        }
                    }
                )
            }

            // Logout (if authenticated)
            if (authState is VPSAuthState.Authenticated) {
                ListItem(
                    headlineContent = { Text("Sign Out from VPS") },
                    supportingContent = { Text("Clear VPS authentication") },
                    leadingContent = {
                        Icon(
                            Icons.Filled.Logout,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    modifier = Modifier.clickable {
                        vpsAuthManager.logout()
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Info card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Migration Info",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "The VPS backend is being developed as a replacement for Firebase. " +
                                "During migration, you can use:\n\n" +
                                "• Firebase (Original): Continue using Firebase as before\n" +
                                "• VPS Server: Use only the VPS backend\n" +
                                "• Hybrid: Sync to both (for testing)\n\n" +
                                "The VPS backend stores data on a self-hosted PostgreSQL server " +
                                "and uses WebSockets for real-time updates.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }

    // Backend Selection Dialog
    if (showBackendDialog) {
        AlertDialog(
            onDismissRequest = { showBackendDialog = false },
            icon = { Icon(Icons.Filled.Storage, contentDescription = null) },
            title = { Text("Select Sync Backend") },
            text = {
                Column(modifier = Modifier.selectableGroup()) {
                    SyncBackend.values().forEach { backend ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = currentBackend == backend,
                                    onClick = {
                                        backendConfig.setBackend(backend)
                                        showBackendDialog = false
                                    },
                                    role = Role.RadioButton
                                )
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentBackend == backend,
                                onClick = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    when (backend) {
                                        SyncBackend.FIREBASE -> "Firebase (Original)"
                                        SyncBackend.VPS -> "VPS Server"
                                        SyncBackend.HYBRID -> "Hybrid Mode"
                                    },
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    when (backend) {
                                        SyncBackend.FIREBASE -> "Use Firebase Realtime Database"
                                        SyncBackend.VPS -> "Use VPS PostgreSQL + WebSocket"
                                        SyncBackend.HYBRID -> "Sync to both backends (testing)"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBackendDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // URL Edit Dialog
    if (showUrlDialog) {
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            icon = { Icon(Icons.Filled.Link, contentDescription = null) },
            title = { Text("VPS Server URL") },
            text = {
                OutlinedTextField(
                    value = customUrl,
                    onValueChange = { customUrl = it },
                    label = { Text("Server URL") },
                    placeholder = { Text("http://5.78.188.206") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        backendConfig.setVpsUrl(customUrl)
                        showUrlDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUrlDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// Clickable extension for ListItem
private fun Modifier.clickable(
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier = this.then(
    if (enabled) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }
)
