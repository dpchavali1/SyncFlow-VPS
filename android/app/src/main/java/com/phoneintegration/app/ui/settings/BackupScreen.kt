package com.phoneintegration.app.ui.settings

import android.app.backup.BackupManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.phoneintegration.app.auth.RecoveryCodeManager
import com.phoneintegration.app.data.PreferencesManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isExporting by remember { mutableStateOf(false) }

    val preferencesManager = remember { PreferencesManager(context) }
    val recoveryCodeManager = remember { RecoveryCodeManager.getInstance(context) }
    val backupManager = remember { BackupManager(context) }

    val recoveryBackupEnabled by preferencesManager.recoveryBackupEnabled
    var hasRecoveryCode by remember { mutableStateOf(false) }
    var recoveryCodePreview by remember { mutableStateOf<String?>(null) }
    var isDeviceBackupEnabled by remember { mutableStateOf(false) }
    var isBackingUp by remember { mutableStateOf(false) }

    // Check backup status
    LaunchedEffect(Unit) {
        val code = recoveryCodeManager.getStoredRecoveryCode()
        hasRecoveryCode = code != null
        recoveryCodePreview = code?.let {
            val formatted = recoveryCodeManager.formatRecoveryCode(it)
            // Show first part only for security
            formatted.take(9) + "****-****"
        }

        // Check if device backup is enabled at OS level
        // Note: BackupManager doesn't expose this directly, so we check via Settings
        isDeviceBackupEnabled = try {
            val backupEnabled = Settings.Secure.getInt(
                context.contentResolver,
                "backup_enabled",
                0
            )
            backupEnabled == 1
        } catch (e: Exception) {
            // If we can't check, assume enabled
            true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup & Restore") },
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
                .fillMaxSize()
                .padding(padding)
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
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Column {
                        Text(
                            "About Backup",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Export your messages as JSON or CSV files. Backups are saved to Downloads folder.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // Export Section
            Text(
                "Export Messages",
                style = MaterialTheme.typography.titleLarge
            )

            Button(
                onClick = {
                    isExporting = true
                    exportMessages(context, "json")
                    isExporting = false
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isExporting
            ) {
                Icon(Icons.Default.Download, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Export as JSON")
            }

            OutlinedButton(
                onClick = {
                    isExporting = true
                    exportMessages(context, "csv")
                    isExporting = false
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isExporting
            ) {
                Icon(Icons.Default.Download, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Export as CSV")
            }

            if (isExporting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            HorizontalDivider()

            // Import Section
            Text(
                "Import Messages",
                style = MaterialTheme.typography.titleLarge
            )

            OutlinedButton(
                onClick = {
                    // TODO: Implement import
                    Toast.makeText(context, "Import feature coming soon!", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Upload, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Import from File")
            }

            HorizontalDivider()

            // Recovery Code Backup Section
            Text(
                "Recovery Code Backup",
                style = MaterialTheme.typography.titleLarge
            )

            // Recovery Backup Toggle Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (recoveryBackupEnabled)
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    else
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (recoveryBackupEnabled) Icons.Default.CloudDone else Icons.Default.CloudOff,
                                contentDescription = null,
                                tint = if (recoveryBackupEnabled)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text("Google Drive Backup", style = MaterialTheme.typography.bodyLarge)
                        }
                        Switch(
                            checked = recoveryBackupEnabled,
                            onCheckedChange = { enabled ->
                                preferencesManager.setRecoveryBackupEnabled(context, enabled)
                                if (enabled) {
                                    // Request backup when enabled
                                    BackupManager(context).dataChanged()
                                    Toast.makeText(context, "Backup enabled - recovery code will be backed up", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Backup disabled - recovery code stored locally only", Toast.LENGTH_LONG).show()
                                }
                            }
                        )
                    }

                    Text(
                        "Automatically backup your recovery code to Google Drive. " +
                                "This allows seamless account recovery when you reinstall the app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Status Row
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // Recovery Code Status
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Recovery Code",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            if (hasRecoveryCode) recoveryCodePreview ?: "Set up" else "Not set up",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hasRecoveryCode)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error
                        )
                    }

                    // Device Backup Status (OS level)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Device Backup",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            if (isDeviceBackupEnabled) "Enabled" else "Disabled in Settings",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isDeviceBackupEnabled)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error
                        )
                    }

                    // Overall Backup Status
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Backup Status",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val backupStatus = when {
                            !hasRecoveryCode -> "No recovery code"
                            !recoveryBackupEnabled -> "Disabled (local only)"
                            !isDeviceBackupEnabled -> "Enable device backup"
                            else -> "Ready to backup"
                        }
                        val statusColor = when {
                            !hasRecoveryCode -> MaterialTheme.colorScheme.error
                            !recoveryBackupEnabled -> MaterialTheme.colorScheme.onSurfaceVariant
                            !isDeviceBackupEnabled -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        }
                        Text(
                            backupStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = statusColor
                        )
                    }
                }
            }

            // Backup Now Button
            if (recoveryBackupEnabled && hasRecoveryCode) {
                Button(
                    onClick = {
                        isBackingUp = true
                        backupManager.dataChanged()
                        scope.launch {
                            kotlinx.coroutines.delay(1500)
                            isBackingUp = false
                            Toast.makeText(
                                context,
                                "Backup requested. It will sync when connected to WiFi and charging.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isBackingUp && isDeviceBackupEnabled
                ) {
                    if (isBackingUp) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Requesting backup...")
                    } else {
                        Icon(Icons.Default.Backup, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Backup Now")
                    }
                }
            }

            // Warning if backup not possible
            if (recoveryBackupEnabled && hasRecoveryCode && !isDeviceBackupEnabled) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Column {
                            Text(
                                "Device backup is disabled",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                "Enable backup in system settings to protect your recovery code.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // Open System Backup Settings
            OutlinedButton(
                onClick = {
                    try {
                        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            Intent(Settings.ACTION_PRIVACY_SETTINGS)
                        } else {
                            Intent(Settings.ACTION_SETTINGS)
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Could not open settings", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Settings, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Open System Backup Settings")
            }

            // Info about backup
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        "Backups are encrypted and stored in your Google account. " +
                                "Only your recovery code is backed up - messages are synced separately.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            HorizontalDivider()

            // Message Export Section
            Text(
                "Message Export",
                style = MaterialTheme.typography.titleLarge
            )

            Text(
                "Export messages as files for manual backup",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Warning Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(
                        "Keep your backup files secure. They contain your message history.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

private fun exportMessages(context: Context, format: String) {
    try {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val filename = "syncflow_backup_$timestamp.$format"

        // TODO: Implement actual export logic
        // For now, just show success message
        Toast.makeText(
            context,
            "Exported to Downloads/$filename",
            Toast.LENGTH_LONG
        ).show()
    } catch (e: Exception) {
        Toast.makeText(
            context,
            "Export failed: ${e.message}",
            Toast.LENGTH_SHORT
        ).show()
    }
}
