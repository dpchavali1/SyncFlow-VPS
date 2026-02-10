package com.phoneintegration.app.ui.settings

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.phoneintegration.app.vps.VPSAuthManager
import com.phoneintegration.app.vps.VPSClient
import com.phoneintegration.app.vps.VPSUsageData
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min

private const val TRIAL_DAYS = 7 // 7 day trial
private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

// Trial/Free tier: 500MB upload/month, 100MB storage
private const val TRIAL_MONTHLY_UPLOAD_BYTES = 500L * 1024L * 1024L
private const val TRIAL_STORAGE_BYTES = 100L * 1024L * 1024L

// Paid tier: 10GB upload/month, 2GB storage
private const val PAID_MONTHLY_UPLOAD_BYTES = 10L * 1024L * 1024L * 1024L
private const val PAID_STORAGE_BYTES = 2L * 1024L * 1024L * 1024L

// File size limits (no daily limits - R2 has free egress)
private const val MAX_FILE_SIZE_FREE = 50L * 1024L * 1024L     // 50MB per file
private const val MAX_FILE_SIZE_PRO = 1024L * 1024L * 1024L    // 1GB per file

private data class UsageSummary(
    val plan: String?,
    val planExpiresAt: Long?,
    val trialStartedAt: Long?,
    val storageBytes: Long,
    val monthlyUploadBytes: Long,
    val monthlyMmsBytes: Long,
    val monthlyFileBytes: Long,
    val monthlyPhotoBytes: Long,
    val lastUpdatedAt: Long?,
    val isPaid: Boolean
)

private sealed class UsageUiState {
    object Loading : UsageUiState()
    data class Loaded(val summary: UsageSummary) : UsageUiState()
    data class Error(val message: String) : UsageUiState()
}

private const val TAG = "UsageSettingsScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authManager = remember { VPSAuthManager.getInstance(context) }
    val vpsClient = remember { VPSClient.getInstance(context) }
    val currentUserId = vpsClient.userId

    var state by remember { mutableStateOf<UsageUiState>(UsageUiState.Loading) }
    var showClearDialog by remember { mutableStateOf(false) }
    var isClearing by remember { mutableStateOf(false) }
    var clearResult by remember { mutableStateOf<String?>(null) }

    val loadUsage: () -> Unit = {
        scope.launch {
            val userId = vpsClient.userId

            if (userId.isNullOrBlank()) {
                state = UsageUiState.Error("Not signed in")
                return@launch
            }

            state = UsageUiState.Loading
            try {
                val result = vpsClient.getUserUsage()
                if (result.success && result.usage != null) {
                    state = UsageUiState.Loaded(parseUsageFromVPS(result.usage))
                } else {
                    // No data - show default trial state
                    state = UsageUiState.Loaded(defaultUsageSummary())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading usage: ${e.message}", e)
                state = UsageUiState.Error(e.message ?: "Failed to load usage")
            }
        }
    }

    LaunchedEffect(Unit) {
        loadUsage()
    }

    // Clear MMS Data function
    val clearMmsData: () -> Unit = {
        scope.launch {
            val userId = vpsClient.userId ?: return@launch
            isClearing = true
            clearResult = null

            try {
                val result = vpsClient.clearMmsData()
                if (result.success) {
                    val deletedFiles = result.deletedFiles
                    val freedBytes = result.freedBytes
                    val freedMB = freedBytes / (1024.0 * 1024.0)
                    clearResult = "Cleared $deletedFiles files (${String.format(Locale.US, "%.1f", freedMB)} MB freed)"
                    loadUsage() // Refresh usage stats
                } else {
                    clearResult = "Cleared successfully"
                    loadUsage()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing MMS data: ${e.message}", e)
                clearResult = "Error: ${e.message}"
            }

            isClearing = false
        }
    }

    // Clear confirmation dialog
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear MMS & File Data?") },
            text = {
                Text("This will delete all synced MMS images, videos, and file transfers from cloud storage. Your storage quota will be reset to 0. Message text is not affected.\n\nThis action cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearDialog = false
                        clearMmsData()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Usage & Limits") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = loadUsage) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (val current = state) {
                UsageUiState.Loading -> {
                    Spacer(modifier = Modifier.height(24.dp))
                    CircularProgressIndicator()
                }
                is UsageUiState.Error -> {
                    Text(
                        text = current.message,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                is UsageUiState.Loaded -> {
                    val summary = current.summary
                    val now = System.currentTimeMillis()
                    val planLabel = planLabel(summary.plan, summary.isPaid)
                    val trialDays = trialDaysRemaining(summary.trialStartedAt, now)
                    val monthlyLimit = if (summary.isPaid) PAID_MONTHLY_UPLOAD_BYTES else TRIAL_MONTHLY_UPLOAD_BYTES
                    val storageLimit = if (summary.isPaid) PAID_STORAGE_BYTES else TRIAL_STORAGE_BYTES

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("User ID", style = MaterialTheme.typography.titleMedium)
                                val clipboardManager = LocalClipboardManager.current
                                val coroutineScope = rememberCoroutineScope()

                                IconButton(
                                    onClick = {
                                        currentUserId?.let { userId ->
                                            clipboardManager.setText(AnnotatedString(userId))
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar("User ID copied to clipboard")
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        Icons.Filled.ContentCopy,
                                        contentDescription = "Copy User ID",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Text(
                                text = currentUserId ?: "Unknown",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Plan", style = MaterialTheme.typography.titleMedium)
                            Text(planLabel, style = MaterialTheme.typography.bodyLarge)
                            if (!summary.isPaid) {
                                Text(
                                    text = if (summary.trialStartedAt == null) {
                                        "Trial not started (starts on first upload)"
                                    } else if (trialDays > 0) {
                                        "$trialDays days remaining in trial"
                                    } else {
                                        "Trial expired"
                                    },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            } else if (summary.planExpiresAt != null) {
                                Text(
                                    text = "Renews on ${formatDate(summary.planExpiresAt)}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("Monthly uploads", style = MaterialTheme.typography.titleMedium)
                            UsageBar(
                                label = "${formatBytes(summary.monthlyUploadBytes)} / ${formatBytes(monthlyLimit)}",
                                progress = ratio(summary.monthlyUploadBytes, monthlyLimit)
                            )
                            Text(
                                text = "MMS: ${formatBytes(summary.monthlyMmsBytes)} • Photos: ${formatBytes(summary.monthlyPhotoBytes)} • Files: ${formatBytes(summary.monthlyFileBytes)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("Storage", style = MaterialTheme.typography.titleMedium)
                            UsageBar(
                                label = "${formatBytes(summary.storageBytes)} / ${formatBytes(storageLimit)}",
                                progress = ratio(summary.storageBytes, storageLimit)
                            )
                        }
                    }

                    // File Transfer section
                    val maxFileSize = if (summary.isPaid) MAX_FILE_SIZE_PRO else MAX_FILE_SIZE_FREE

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("File Transfer", style = MaterialTheme.typography.titleMedium)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Max file size", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    formatBytes(maxFileSize),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (!summary.isPaid) {
                                Text(
                                    text = "Upgrade to Pro for 1GB file transfers",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    // Clear MMS Data section
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("Storage Management", style = MaterialTheme.typography.titleMedium)

                            OutlinedButton(
                                onClick = { showClearDialog = true },
                                enabled = !isClearing,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (isClearing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.padding(horizontal = 8.dp))
                                    Text("Clearing...")
                                } else {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                                    Text("Clear MMS & File Data")
                                }
                            }

                            Text(
                                text = "Deletes synced MMS attachments and file transfers from cloud storage. This frees up your storage quota. Message text is not affected.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            clearResult?.let { result ->
                                Text(
                                    text = result,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (result.startsWith("Error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    if (summary.lastUpdatedAt != null) {
                        Text(
                            text = "Last updated ${formatDateTime(summary.lastUpdatedAt)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageBar(label: String, progress: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
    }
}

private fun parseUsageFromVPS(data: VPSUsageData): UsageSummary {
    val plan = data.plan
    val planExpiresAt = data.planExpiresAt
    val trialStartedAt = data.trialStartedAt
    val storageBytes = data.storageBytes
    val lastUpdatedAt = data.lastUpdatedAt
    val monthlyUploadBytes = data.monthlyUploadBytes
    val monthlyMmsBytes = data.monthlyMmsBytes
    val monthlyFileBytes = data.monthlyFileBytes

    val now = System.currentTimeMillis()
    val isPaid = isPaidPlan(plan, planExpiresAt, now)

    return UsageSummary(
        plan = plan,
        planExpiresAt = planExpiresAt,
        trialStartedAt = trialStartedAt,
        storageBytes = storageBytes,
        monthlyUploadBytes = monthlyUploadBytes,
        monthlyMmsBytes = monthlyMmsBytes,
        monthlyFileBytes = monthlyFileBytes,
        monthlyPhotoBytes = data.monthlyPhotoBytes,
        lastUpdatedAt = lastUpdatedAt,
        isPaid = isPaid
    )
}

private fun defaultUsageSummary(): UsageSummary {
    return UsageSummary(
        plan = null,
        planExpiresAt = null,
        trialStartedAt = null,
        storageBytes = 0L,
        monthlyUploadBytes = 0L,
        monthlyMmsBytes = 0L,
        monthlyFileBytes = 0L,
        monthlyPhotoBytes = 0L,
        lastUpdatedAt = null,
        isPaid = false
    )
}

private fun planLabel(plan: String?, isPaid: Boolean): String {
    if (!isPaid) return "Trial"
    return when (plan?.lowercase(Locale.US)) {
        "lifetime", "3year" -> "3-Year"
        "yearly" -> "Yearly"
        "monthly" -> "Monthly"
        "paid" -> "Paid"
        else -> "Paid"
    }
}

private fun trialDaysRemaining(trialStartedAt: Long?, now: Long): Int {
    if (trialStartedAt == null) return TRIAL_DAYS
    val end = trialStartedAt + TRIAL_DAYS * MILLIS_PER_DAY
    val remaining = end - now
    return max(0, (remaining / MILLIS_PER_DAY).toInt())
}

private fun currentPeriodKey(): String {
    val formatter = SimpleDateFormat("yyyyMM", Locale.US)
    formatter.timeZone = TimeZone.getTimeZone("UTC")
    return formatter.format(Date())
}

private fun isPaidPlan(plan: String?, planExpiresAt: Long?, now: Long): Boolean {
    val normalized = plan?.lowercase(Locale.US) ?: return false
    if (normalized == "lifetime" || normalized == "3year") {
        return true
    }
    if (normalized == "monthly" || normalized == "yearly" || normalized == "paid") {
        return planExpiresAt?.let { it > now } ?: true
    }
    return false
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.1f GB", gb)
}

private fun ratio(used: Long, limit: Long): Float {
    if (limit <= 0L) return 0f
    return min(1f, used.toFloat() / limit.toFloat())
}

private fun formatDate(timestamp: Long): String {
    val formatter = SimpleDateFormat("MMM d, yyyy", Locale.US)
    return formatter.format(Date(timestamp))
}

private fun formatDateTime(timestamp: Long): String {
    val formatter = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.US)
    return formatter.format(Date(timestamp))
}
