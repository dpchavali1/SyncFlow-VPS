package com.phoneintegration.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phoneintegration.app.data.PreferencesManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageSettingsScreen(
    prefsManager: PreferencesManager,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Message Settings") },
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Input Settings
            Text(
                "Input",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            SwitchCard(
                title = "Send on Enter",
                subtitle = "Press Enter to send (Shift+Enter for new line)",
                checked = prefsManager.sendOnEnter.value,
                onCheckedChange = { prefsManager.setSendOnEnter(it) }
            )
            
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            
            // Display Settings
            Text(
                "Display",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            SwitchCard(
                title = "Show Timestamps",
                subtitle = "Display time on each message",
                checked = prefsManager.showTimestamps.value,
                onCheckedChange = { prefsManager.setShowTimestamps(it) }
            )
            
            SwitchCard(
                title = "Group by Date",
                subtitle = "Show date headers in conversations",
                checked = prefsManager.groupMessagesByDate.value,
                onCheckedChange = { prefsManager.setGroupByDate(it) }
            )
            
            // Gesture Settings
            Text(
                "Gestures",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            SwitchCard(
                title = "Enable swipe gestures",
                subtitle = "Swipe a conversation to archive or pin it",
                checked = prefsManager.swipeGesturesEnabled.value,
                onCheckedChange = { prefsManager.setSwipeGesturesEnabled(it) }
            )
            
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            
            // Auto-Delete
            Text(
                "Auto-Delete Old Messages",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            SwitchCard(
                title = "Auto-Delete",
                subtitle = "Automatically delete old messages",
                checked = prefsManager.autoDeleteOld.value,
                onCheckedChange = { prefsManager.setAutoDeleteOld(it) }
            )
            
            if (prefsManager.autoDeleteOld.value) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Delete messages after",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = prefsManager.deleteAfterDays.value == 30,
                                onClick = { prefsManager.setDeleteAfterDays(30) },
                                label = { Text("30 days") }
                            )
                            FilterChip(
                                selected = prefsManager.deleteAfterDays.value == 90,
                                onClick = { prefsManager.setDeleteAfterDays(90) },
                                label = { Text("90 days") }
                            )
                            FilterChip(
                                selected = prefsManager.deleteAfterDays.value == 180,
                                onClick = { prefsManager.setDeleteAfterDays(180) },
                                label = { Text("180 days") }
                            )
                        }
                    }
                }
            }
            
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            
            // Message Signature
            Text(
                "Message Signature",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            SwitchCard(
                title = "Add Signature",
                subtitle = "Automatically append signature to messages",
                checked = prefsManager.addSignature.value,
                onCheckedChange = { prefsManager.setAddSignature(it) }
            )
            
            if (prefsManager.addSignature.value) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = prefsManager.messageSignature.value,
                            onValueChange = { prefsManager.setMessageSignature(it) },
                            label = { Text("Signature") },
                            placeholder = { Text("e.g., Sent from SyncFlow") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 2
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Preview: Your message\n\n${prefsManager.messageSignature.value}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}
