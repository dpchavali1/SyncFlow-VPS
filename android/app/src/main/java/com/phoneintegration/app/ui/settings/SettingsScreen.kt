/**
 * SettingsScreen.kt
 *
 * This file implements the main settings screen for the SyncFlow app. It provides
 * access to all app configuration options organized into logical sections.
 *
 * Key Sections:
 * - Desktop & Web Access: Pairing, web access, sync, file transfer
 * - App Settings: Theme, notifications, appearance, privacy, usage
 * - Messages: Message settings, quick reply templates, backup, blocked numbers, spam filter
 * - Help & Support: AI support chat
 * - Account: Delete account (for paired users only)
 * - About: App version info
 *
 * Architecture:
 * - Pure navigation hub with minimal business logic
 * - Uses PreferencesManager for settings values
 * - All sub-screens accessed via hoisted callbacks
 * - Conditional sections based on app state (default SMS, paired devices)
 *
 * Navigation:
 * - Entry point from main screen via overflow menu
 * - Navigates to numerous sub-settings screens
 *
 * @see PreferencesManager for settings storage
 * @see DesktopSyncService for pairing state
 */
package com.phoneintegration.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.phoneintegration.app.BuildConfig
import com.phoneintegration.app.MainActivity
import com.phoneintegration.app.data.PreferencesManager
import com.phoneintegration.app.desktop.DesktopSyncService
import com.phoneintegration.app.utils.DefaultSmsHelper

// =============================================================================
// region MAIN SETTINGS SCREEN
// =============================================================================

/**
 * Main settings screen composable.
 *
 * Displays a scrollable list of settings options organized into sections.
 * Includes a card prompting users to set the app as default SMS app when needed.
 * Shows conditional sections based on paired device status.
 *
 * State Hoisting Pattern:
 * - PreferencesManager passed in for current settings values
 * - All navigation callbacks hoisted to parent
 * - Local UI state for dialogs and checks
 *
 * Side Effects:
 * - LaunchedEffect to check default SMS app status and paired devices on mount
 *
 * @param prefsManager The PreferencesManager instance for reading settings
 * @param onBack Callback to navigate back
 * @param onNavigateToTheme Callback to navigate to theme settings
 * @param onNavigateToNotifications Callback to navigate to notification settings
 * @param onNavigateToAppearance Callback to navigate to appearance settings
 * @param onNavigateToPrivacy Callback to navigate to privacy settings
 * @param onNavigateToMessages Callback to navigate to message settings
 * @param onNavigateToTemplates Callback to navigate to quick reply templates
 * @param onNavigateToBackup Callback to navigate to backup/restore
 * @param onNavigateToDesktop Callback to navigate to desktop pairing
 * @param onNavigateToUsage Callback to navigate to usage/limits
 * @param onNavigateToSync Callback to navigate to message history sync
 * @param onNavigateToSpamFilter Callback to navigate to spam filter settings
 * @param onNavigateToSupport Callback to navigate to AI support chat
 * @param onNavigateToFileTransfer Callback to navigate to file transfer
 * @param onNavigateToDeleteAccount Callback to navigate to delete account
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    prefsManager: PreferencesManager,
    onBack: () -> Unit,
    onNavigateToTheme: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    onNavigateToAppearance: () -> Unit,
    onNavigateToPrivacy: () -> Unit,
    onNavigateToMessages: () -> Unit,
    onNavigateToTemplates: () -> Unit,
    onNavigateToBackup: () -> Unit,
    onNavigateToDesktop: () -> Unit = {},
    onNavigateToUsage: () -> Unit = {},
    onNavigateToSync: () -> Unit = {},
    onNavigateToSpamFilter: () -> Unit = {},
    onNavigateToSupport: () -> Unit = {},
    onNavigateToFileTransfer: () -> Unit = {},
    onNavigateToDeleteAccount: () -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    var isDefaultSmsApp by remember { mutableStateOf(false) }
    var showWebAccessDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var isPaired by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isDefaultSmsApp = DefaultSmsHelper.isDefaultSmsApp(context)
        isPaired = DesktopSyncService.hasPairedDevices(context)
    }

    // Web Access Instructions Dialog
    if (showWebAccessDialog) {
        AlertDialog(
            onDismissRequest = { showWebAccessDialog = false },
            icon = { Icon(Icons.Filled.Language, contentDescription = null) },
            title = { Text("Web Access & Subscriptions") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Web Access",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text("Access your messages from any browser:")

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("1. Open ")
                        Text(
                            "https://sfweb.app",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                            )
                        )
                    }
                    Text("2. A QR code will be displayed on the website")
                    Text("3. In this app, go to Settings → Pair Device")
                    Text("4. Tap \"Scan QR Code\" and scan the code from your browser")

                    Divider(Modifier.padding(vertical = 8.dp))

                    Text(
                        "Subscriptions",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text("Manage your subscription at sfweb.app:")
                    Text("• View current plan and usage")
                    Text("• Upgrade or cancel subscription")
                    Text("• Update payment method")
                    Text("• Download invoices")

                    Text(
                        "Note: Subscriptions are managed through the web to avoid app store fees.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showWebAccessDialog = false
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://sfweb.app"))
                        context.startActivity(intent)
                    }
                ) {
                    Text("Open sfweb.app")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWebAccessDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // About Dialog
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            icon = { Icon(Icons.Filled.Info, contentDescription = null) },
            title = { Text("About SyncFlow") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Version ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Divider(Modifier.padding(vertical = 4.dp))

                    Text(
                        "SyncFlow lets you send and receive SMS/MMS from your Mac, PC, or any browser.",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Text(
                        "Features:",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text("• Sync messages across all devices")
                    Text("• Video & audio calling")
                    Text("• Photo sync & file transfer")
                    Text("• AI-powered spam filtering")
                    Text("• End-to-end secure pairing")

                    Divider(Modifier.padding(vertical = 4.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Web: ")
                        Text(
                            "https://sfweb.app",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                            )
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PRIVACY_POLICY_URL))
                                context.startActivity(intent)
                            }
                        ) {
                            Text("Privacy", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("•", modifier = Modifier.align(Alignment.CenterVertically))
                        TextButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.TERMS_OF_SERVICE_URL))
                                context.startActivity(intent)
                            }
                        ) {
                            Text("Terms", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Text(
                        "© 2026 SyncFlow. All rights reserved.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAboutDialog = false
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://sfweb.app"))
                        context.startActivity(intent)
                    }
                ) {
                    Text("Visit Website")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
        ) {

            // ----------------------------------------------
            // DEFAULT SMS REQUEST CARD
            // ----------------------------------------------
            if (!isDefaultSmsApp) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Filled.Message, contentDescription = null)
                            Column(Modifier.weight(1f)) {
                                Text("Set as Default SMS App")
                                Text("Required to send/receive SMS & MMS fully")
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                android.util.Log.d("SettingsScreen", "Set as Default button clicked")
                                android.util.Log.d("SettingsScreen", "Activity: $activity")
                                activity?.requestDefaultSmsAppViaRole()
                            }
                        ) {
                            Text("Set as Default")
                        }
                    }
                }
            }

            // -------------------------
            // Desktop Integration (at the top for visibility)
            // -------------------------
            SettingsSection("Desktop & Web Access")

            SettingsItem(
                icon = Icons.Filled.Computer,
                title = "Pair Device",
                subtitle = "Connect Mac app or Web browser",
                onClick = onNavigateToDesktop
            )

            SettingsItem(
                icon = Icons.Filled.Language,
                title = "Web Access & Subscriptions",
                subtitle = "Access messages and manage subscription",
                onClick = { showWebAccessDialog = true }
            )

            if (isPaired) {
                SettingsItem(
                    icon = Icons.Filled.Sync,
                    title = "Sync Message History",
                    subtitle = "Load older messages to Mac and Web",
                    onClick = onNavigateToSync
                )

                SettingsItem(
                    icon = Icons.Filled.CloudUpload,
                    title = "Send Files to Mac",
                    subtitle = "Share photos, videos, and files",
                    onClick = onNavigateToFileTransfer
                )
            }

            Divider(Modifier.padding(vertical = 8.dp))

            // -------------------------
            // App Settings
            // -------------------------
            SettingsSection("App Settings")

            SettingsItem(
                icon = Icons.Filled.Palette,
                title = "Theme",
                subtitle = if (prefsManager.isAutoTheme.value) "Auto"
                else if (prefsManager.isDarkMode.value) "Dark" else "Light",
                onClick = onNavigateToTheme
            )

            SettingsItem(
                icon = Icons.Filled.Notifications,
                title = "Notifications",
                subtitle = if (prefsManager.notificationsEnabled.value) "Enabled" else "Disabled",
                onClick = onNavigateToNotifications
            )

            SettingsItem(
                icon = Icons.Filled.Wallpaper,
                title = "Appearance",
                subtitle = "Customize chat appearance",
                onClick = onNavigateToAppearance
            )

            SettingsItem(
                icon = Icons.Filled.Lock,
                title = "Privacy & Security",
                subtitle = if (prefsManager.requireFingerprint.value) "Fingerprint Enabled" else "Not Secured",
                onClick = onNavigateToPrivacy
            )

            SettingsItem(
                icon = Icons.Filled.DataUsage,
                title = "Usage & Limits",
                subtitle = "View plan and current usage",
                onClick = onNavigateToUsage
            )

            Divider(Modifier.padding(vertical = 8.dp))

            SettingsSection("Messages")

            SettingsItem(
                icon = Icons.Filled.Settings,
                title = "Message Settings",
                subtitle = "Delivery, timestamps, and more",
                onClick = onNavigateToMessages
            )

            SettingsItem(
                icon = Icons.Filled.Chat,
                title = "Quick Reply Templates",
                subtitle = "${prefsManager.getQuickReplyTemplates().size} templates",
                onClick = onNavigateToTemplates
            )

            SettingsItem(
                icon = Icons.Filled.Archive,
                title = "Backup & Restore",
                subtitle = "Export and import messages",
                onClick = onNavigateToBackup
            )

            SettingsItem(
                icon = Icons.Filled.Block,
                title = "Blocked Numbers",
                subtitle = "Manage blocked numbers",
                onClick = { /* TODO */ }
            )

            SettingsItem(
                icon = Icons.Filled.Shield,
                title = "Spam Filter",
                subtitle = "AI-powered spam detection",
                onClick = onNavigateToSpamFilter
            )

            Divider(Modifier.padding(vertical = 8.dp))

            SettingsSection("Help & Support")

            SettingsItem(
                icon = Icons.Filled.AutoAwesome,
                title = "AI Support Chat",
                subtitle = "Get help from our AI assistant",
                onClick = onNavigateToSupport
            )

            // Only show Account section if user has paired (has an account)
            if (isPaired) {
                Divider(Modifier.padding(vertical = 8.dp))

                SettingsSection("Account")

                SettingsItem(
                    icon = Icons.Filled.DeleteForever,
                    title = "Delete Account",
                    subtitle = "Schedule account for deletion",
                    onClick = onNavigateToDeleteAccount
                )
            }

            Divider(Modifier.padding(vertical = 8.dp))

            SettingsSection("About")

            SettingsItem(
                icon = Icons.Filled.Info,
                title = "About SyncFlow",
                subtitle = "Version ${BuildConfig.VERSION_NAME}",
                onClick = { showAboutDialog = true }
            )

            // Debug section (only visible in debug builds)
            if (BuildConfig.DEBUG) {
                Divider(Modifier.padding(vertical = 8.dp))

                SettingsSection("Debug")

                SettingsItem(
                    icon = Icons.Filled.BugReport,
                    title = "Test Crash Reporter",
                    subtitle = "Force a crash to test crash reporting",
                    onClick = {
                        // Force a test crash
                        throw RuntimeException("Test crash from Settings - CustomCrashReporter integration test")
                    }
                )
            }
        }
    }
}

// =============================================================================
// endregion
// =============================================================================

// =============================================================================
// region HELPER COMPONENTS
// =============================================================================

/**
 * Section header for grouping related settings items.
 *
 * Displays a title with consistent styling and spacing.
 *
 * @param title The section title text
 */
@Composable
private fun SettingsSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(16.dp)
    )
}

/**
 * Individual settings item with icon, title, subtitle, and navigation arrow.
 *
 * Displays a clickable list item that navigates to a sub-settings screen.
 * Uses Material 3 ListItem component for consistent styling.
 *
 * @param icon Leading icon to display
 * @param title Primary text for the setting
 * @param subtitle Secondary text describing current value or setting purpose
 * @param onClick Callback when the item is tapped
 */
@Composable
private fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, null) },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

// =============================================================================
// endregion
// =============================================================================
