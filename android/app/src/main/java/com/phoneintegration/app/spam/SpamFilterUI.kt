package com.phoneintegration.app.spam

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import androidx.work.WorkManager
import androidx.work.WorkInfo

/**
 * Spam Filter Settings Screen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpamFilterSettingsScreen(
    onBack: () -> Unit,
    onScanMessages: () -> Unit = {}
) {
    val context = LocalContext.current
    val spamFilterService = remember {
        try {
            SpamFilterService.getInstance(context)
        } catch (e: Exception) {
            null
        }
    }
    val filterStats by spamFilterService?.filterStats?.collectAsState() ?: remember { mutableStateOf(null) }
    val scope = rememberCoroutineScope()

    var isUpdating by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableStateOf("") }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<FilterUpdateInfo?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Spam Filter") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                spamFilterService?.let {
                                    updateInfo = it.checkForUpdates()
                                    showUpdateDialog = true
                                }
                            }
                        },
                        enabled = spamFilterService != null
                    ) {
                        Icon(Icons.Default.Refresh, "Check Updates")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Auto-Protection Status Card
            item {
                AutoProtectionStatusCard()
            }

            // Filter Status Card
            item {
                FilterStatusCard(
                    stats = filterStats,
                    isUpdating = isUpdating
                )
            }

            // Manual Actions Section
            item {
                Text(
                    "Manual Actions",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // Scan Messages Button
            item {
                OutlinedButton(
                    onClick = {
                        // Trigger manual scan via WorkManager
                        SpamFilterWorker.scanNow(context)
                        // Show toast
                        android.widget.Toast.makeText(
                            context,
                            "Scanning messages in background...",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Search, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Scan Messages Now")
                }
            }

            // Update Filters Button
            item {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            isUpdating = true
                            spamFilterService?.updateFilters { component, progress ->
                                updateProgress = "$component: $progress%"
                            }
                            isUpdating = false
                            updateProgress = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isUpdating && spamFilterService != null
                ) {
                    if (isUpdating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(updateProgress.ifEmpty { "Updating..." })
                    } else {
                        Icon(Icons.Default.Download, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Update Filters Now")
                    }
                }
            }

            // Protection Features Section
            item {
                Text(
                    "Protection Features",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            item {
                ProtectionFeaturesList()
            }

            // Statistics Section
            item {
                Text(
                    "Statistics",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            item {
                filterStats?.let { stats ->
                    StatisticsCard(stats = stats)
                }
            }

            // About Section
            item {
                AboutSection()
            }
        }
    }

    // Update Available Dialog
    if (showUpdateDialog && updateInfo != null) {
        UpdateAvailableDialog(
            updateInfo = updateInfo!!,
            onDismiss = { showUpdateDialog = false },
            onUpdate = {
                showUpdateDialog = false
                scope.launch {
                    isUpdating = true
                    spamFilterService?.updateFilters { component, progress ->
                        updateProgress = "$component: $progress%"
                    }
                    isUpdating = false
                    updateProgress = ""
                }
            }
        )
    }
}

@Composable
fun FilterStatusCard(
    stats: FilterStats?,
    isUpdating: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Protection Active",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    stats?.let {
                        val lastUpdate = if (it.lastUpdated > 0) {
                            val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.US)
                            "Updated: ${sdf.format(Date(it.lastUpdated))}"
                        } else {
                            "Never updated"
                        }
                        Text(
                            lastUpdate,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }

                if (isUpdating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Quick Stats Row
            stats?.let {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    QuickStat(
                        icon = Icons.Outlined.Link,
                        value = "${it.urlBlocklistCount}",
                        label = "URLs"
                    )
                    QuickStat(
                        icon = Icons.Outlined.TextFields,
                        value = "${it.patternCount}",
                        label = "Patterns"
                    )
                    QuickStat(
                        icon = Icons.Outlined.Block,
                        value = "${it.spamBlocked}",
                        label = "Blocked"
                    )
                }
            }
        }
    }
}

@Composable
fun QuickStat(
    icon: ImageVector,
    value: String,
    label: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
        )
    }
}

@Composable
fun ProtectionFeaturesList() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            ProtectionFeatureItem(
                icon = Icons.Outlined.LinkOff,
                title = "Malicious URL Detection",
                description = "Blocks phishing and malware links from URLhaus database",
                enabled = true
            )
            Divider()
            ProtectionFeatureItem(
                icon = Icons.Outlined.TextFields,
                title = "Spam Pattern Matching",
                description = "Detects lottery scams, banking fraud, job scams",
                enabled = true
            )
            Divider()
            ProtectionFeatureItem(
                icon = Icons.Outlined.Psychology,
                title = "AI Classification",
                description = "Machine learning based spam detection",
                enabled = true
            )
            Divider()
            ProtectionFeatureItem(
                icon = Icons.Outlined.Block,
                title = "Sender Blocklist",
                description = "Block specific numbers manually",
                enabled = true
            )
        }
    }
}

@Composable
fun ProtectionFeatureItem(
    icon: ImageVector,
    title: String,
    description: String,
    enabled: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        )

        Spacer(Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }

        Icon(
            if (enabled) Icons.Default.CheckCircle else Icons.Outlined.Circle,
            contentDescription = null,
            tint = if (enabled) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
fun StatisticsCard(stats: FilterStats) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            StatRow("Messages Scanned", "${stats.messagesScanned}")
            StatRow("Spam Blocked", "${stats.spamBlocked}")
            StatRow("URL Database Size", "${stats.urlBlocklistCount} URLs")
            StatRow("Pattern Rules", "${stats.patternCount} patterns")
            StatRow("Blocked Senders", "${stats.blockedSenderCount}")
            stats.modelVersion?.let {
                StatRow("ML Model Version", it)
            }
        }
    }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun AboutSection() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "About Spam Protection",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "SyncFlow's spam filter runs entirely on your device for maximum privacy. " +
                "No message content is ever sent to external servers.\n\n" +
                "Protection is fully automatic:\n" +
                "• Incoming messages are scanned in real-time\n" +
                "• Filters update daily when on WiFi\n" +
                "• Existing messages are scanned on first launch\n\n" +
                "Filter sources:\n" +
                "• URLhaus (abuse.ch) - Malicious URL database\n" +
                "• Community patterns - Spam detection rules",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
fun UpdateAvailableDialog(
    updateInfo: FilterUpdateInfo,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Download, null) },
        title = {
            Text(if (updateInfo.hasUpdates) "Updates Available" else "Filters Up to Date")
        },
        text = {
            if (updateInfo.hasUpdates) {
                Column {
                    Text("The following updates are available:")
                    Spacer(Modifier.height(8.dp))
                    updateInfo.components.forEach { component ->
                        Text(
                            "• ${component.name}: ${component.currentVersion} → ${component.newVersion}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Total download: ${formatBytes(updateInfo.totalSize)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            } else {
                Text("All spam filters are up to date.")
            }
        },
        confirmButton = {
            if (updateInfo.hasUpdates) {
                Button(onClick = onUpdate) {
                    Text("Update Now")
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("OK")
                }
            }
        },
        dismissButton = {
            if (updateInfo.hasUpdates) {
                TextButton(onClick = onDismiss) {
                    Text("Later")
                }
            }
        }
    )
}

/**
 * Message Scan Dialog
 */
@Composable
fun MessageScanDialog(
    isScanning: Boolean,
    progress: ScanProgress?,
    result: ScanResult?,
    onDismiss: () -> Unit,
    onViewResults: () -> Unit
) {
    Dialog(onDismissRequest = { if (!isScanning) onDismiss() }) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isScanning && progress != null) {
                    // Scanning in progress
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Scanning Messages...",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(8.dp))

                    val progressPercent = if (progress.totalMessages > 0) {
                        (progress.scannedMessages.toFloat() / progress.totalMessages * 100).toInt()
                    } else 0

                    LinearProgressIndicator(
                        progress = progress.scannedMessages.toFloat() / maxOf(progress.totalMessages, 1),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${progress.scannedMessages} / ${progress.totalMessages} messages",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "${progress.spamFound} spam found",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (progress.spamFound > 0) Color(0xFFE53935) else MaterialTheme.colorScheme.onSurface
                    )
                } else if (result != null) {
                    // Scan complete
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Scan Complete",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(16.dp))

                    // Results summary
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        ResultStat("Scanned", "${result.totalScanned}")
                        ResultStat("Spam", "${result.spamCount}", Color(0xFFE53935))
                        ResultStat("Phishing", "${result.phishingCount}", Color(0xFFFF9800))
                    }

                    Spacer(Modifier.height(24.dp))

                    if (result.spamCount > 0) {
                        Button(
                            onClick = onViewResults,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("View Flagged Messages")
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

@Composable
fun ResultStat(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
    }
}

/**
 * Flagged Messages List Screen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlaggedMessagesScreen(
    flaggedMessages: List<FlaggedMessage>,
    onNavigateBack: () -> Unit,
    onMessageClick: (FlaggedMessage) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Flagged Messages (${flaggedMessages.size})") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (flaggedMessages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("No spam detected!")
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(flaggedMessages) { message ->
                    FlaggedMessageCard(
                        message = message,
                        onClick = { onMessageClick(message) }
                    )
                }
            }
        }
    }
}

@Composable
fun FlaggedMessageCard(
    message: FlaggedMessage,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    message.address,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                // Threat badges
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    message.result.threatTypes.take(2).forEach { threat ->
                        ThreatBadge(threat)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                message.body,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(8.dp))

            // Reasons
            message.result.reasons.take(2).forEach { reason ->
                Text(
                    "• ${reason.description}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(4.dp))

            Text(
                "Confidence: ${(message.result.confidence * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
fun ThreatBadge(threat: ThreatType) {
    val (color, label) = when (threat) {
        ThreatType.PHISHING -> Color(0xFFE53935) to "Phishing"
        ThreatType.MALWARE -> Color(0xFF9C27B0) to "Malware"
        ThreatType.SCAM -> Color(0xFFFF9800) to "Scam"
        ThreatType.FRAUD -> Color(0xFFE53935) to "Fraud"
        ThreatType.LOTTERY_SCAM -> Color(0xFFFF9800) to "Lottery"
        ThreatType.BANKING_FRAUD -> Color(0xFFE53935) to "Banking"
        ThreatType.OTP_THEFT -> Color(0xFF9C27B0) to "OTP Theft"
        ThreatType.PROMOTIONAL_SPAM -> Color(0xFF607D8B) to "Promo"
        ThreatType.UNKNOWN -> Color(0xFF607D8B) to "Spam"
    }

    Surface(
        color = color.copy(alpha = 0.2f),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

// Utility functions
private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
    }
}

/**
 * Auto Protection Status Card - shows that protection runs automatically
 */
@Composable
fun AutoProtectionStatusCard() {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF4CAF50).copy(alpha = 0.1f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF4CAF50)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Automatic Protection Active",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2E7D32)
                )
                Text(
                    "Messages are scanned automatically. Filters update daily on WiFi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}
