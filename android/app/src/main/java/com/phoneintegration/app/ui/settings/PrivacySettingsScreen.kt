package com.phoneintegration.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.phoneintegration.app.data.PreferencesManager
import com.phoneintegration.app.e2ee.SignalProtocolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacySettingsScreen(
    prefsManager: PreferencesManager,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy & Security") },
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
            Text(
                "Security",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            SwitchCard(
                title = "End-to-end encryption",
                subtitle = "Encrypt messages and attachments between paired devices",
                checked = prefsManager.e2eeEnabled.value,
                onCheckedChange = { enabled ->
                    prefsManager.setE2eeEnabled(enabled)
                    if (enabled) {
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    SignalProtocolManager(context).initializeKeys()
                                }
                                Toast.makeText(
                                    context,
                                    "End-to-end encryption enabled",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } catch (e: Exception) {
                                Toast.makeText(
                                    context,
                                    "Failed to enable encryption",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    } else {
                        Toast.makeText(
                            context,
                            "End-to-end encryption disabled",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )

            SwitchCard(
                title = "Require Fingerprint (Coming Soon)",
                subtitle = "Lock app with fingerprint authentication",
                checked = prefsManager.requireFingerprint.value,
                onCheckedChange = {
                    Toast.makeText(context,
                        "Fingerprint authentication coming in next update!",
                        Toast.LENGTH_LONG).show()
                    prefsManager.setRequireFingerprint(it)
                }
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                "Privacy",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            SwitchCard(
                title = "Hide Message Preview",
                subtitle = "Don't show message content in recents",
                checked = prefsManager.hideMessagePreview.value,
                onCheckedChange = {
                    prefsManager.setHideMessagePreview(it)
                    Toast.makeText(context,
                        if (it) "Message previews hidden" else "Message previews shown",
                        Toast.LENGTH_SHORT).show()
                }
            )

            SwitchCard(
                title = "Incognito Mode",
                subtitle = "Don't save conversation history",
                checked = prefsManager.incognitoMode.value,
                onCheckedChange = {
                    prefsManager.setIncognitoMode(it)
                    Toast.makeText(context,
                        if (it) "Incognito mode enabled" else "Incognito mode disabled",
                        Toast.LENGTH_SHORT).show()
                }
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                "AI Features",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Smart AI Assistant",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Intelligent message suggestions, conversation summaries, and pattern analysis - all processed locally on your device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "✓ 100% Free - No API keys or subscriptions\n✓ Completely private - works offline\n✓ Advanced pattern recognition\n✓ Smart reply suggestions\n✓ Intelligent summaries",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        "⚠️ Incognito Mode",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "When enabled, messages won't be saved to your device. SMS messages will still be in your phone's native SMS database.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
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
