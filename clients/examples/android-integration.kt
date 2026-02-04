/**
 * Example: Integrating SyncFlow VPS into an Android App
 *
 * This shows how to replace Firebase with VPS backend.
 */

package com.syncflow.example

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.*
import com.syncflow.api.Message
import com.syncflow.services.SyncFlowService
import com.syncflow.services.SyncFlowState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

// ==================== Application Class ====================

class SyncFlowApplication : Application() {
    val syncFlowService: SyncFlowService by lazy {
        SyncFlowService.getInstance(this)
    }

    override fun onCreate() {
        super.onCreate()
        // Schedule background sync
        scheduleSyncWorker()
    }

    private fun scheduleSyncWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<MessageSyncWorker>(
            15, TimeUnit.MINUTES,
            5, TimeUnit.MINUTES // Flex interval
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "syncflow_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
    }
}

// ==================== Background Sync Worker ====================

class MessageSyncWorker(
    context: android.content.Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val service = (applicationContext as SyncFlowApplication).syncFlowService

        return try {
            // Initialize if not already
            if (!service.state.value.isAuthenticated) {
                service.init()
            }

            if (service.state.value.isAuthenticated) {
                // Load latest messages
                service.loadMessages(limit = 50)
            }

            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}

// ==================== Main Activity ====================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as SyncFlowApplication

        setContent {
            MaterialTheme {
                SyncFlowApp(app.syncFlowService)
            }
        }
    }
}

// ==================== ViewModel ====================

class MainViewModel(private val service: SyncFlowService) : ViewModel() {
    val state: StateFlow<SyncFlowState> = service.state

    init {
        viewModelScope.launch {
            service.init()
        }
    }

    fun loadMessages() {
        viewModelScope.launch {
            try {
                service.loadMessages()
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    fun sendMessage(address: String, body: String) {
        viewModelScope.launch {
            try {
                service.sendMessage(address, body)
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    fun markAsRead(messageId: String) {
        viewModelScope.launch {
            try {
                service.markAsRead(messageId)
            } catch (e: Exception) {
                // Silently fail
            }
        }
    }

    fun logout() {
        service.logout()
    }
}

class MainViewModelFactory(
    private val service: SyncFlowService
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return MainViewModel(service) as T
    }
}

// ==================== Main App Composable ====================

@Composable
fun SyncFlowApp(service: SyncFlowService) {
    val viewModel: MainViewModel = viewModel(
        factory = MainViewModelFactory(service)
    )
    val state by viewModel.state.collectAsState()

    when {
        !state.isAuthenticated -> PairingScreen(service)
        else -> MessagesScreen(viewModel, state)
    }
}

// ==================== Pairing Screen ====================

@Composable
fun PairingScreen(service: SyncFlowService) {
    var pairingToken by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "SyncFlow",
            style = MaterialTheme.typography.headlineLarge
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "Connect your devices",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(48.dp))

        if (pairingToken != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Enter this code on your other device:")
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        pairingToken!!,
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Waiting for approval...",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        try {
                            val token = service.initiatePairing("My Android")
                            pairingToken = token
                            // Start polling
                            while (true) {
                                kotlinx.coroutines.delay(2000)
                                val approved = service.checkPairingStatus(token)
                                if (approved) {
                                    service.redeemPairing(token)
                                    break
                                }
                            }
                        } catch (e: Exception) {
                            error = e.message
                        } finally {
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Start Pairing")
                }
            }
        }

        error?.let {
            Spacer(modifier = Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}

// ==================== Messages Screen ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(viewModel: MainViewModel, state: SyncFlowState) {
    LaunchedEffect(Unit) {
        viewModel.loadMessages()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Messages") },
                actions = {
                    // Connection indicator
                    if (state.isConnected) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.primary
                        ) { Text("Connected") }
                    } else {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.error
                        ) { Text("Offline") }
                    }
                }
            )
        }
    ) { padding ->
        if (state.messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("No messages yet")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.messages) { message ->
                    MessageCard(
                        message = message,
                        onClick = { viewModel.markAsRead(message.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun MessageCard(message: Message, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (message.read)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    message.contactName ?: message.address,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    formatDate(message.date),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                message.body,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2
            )
        }
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}
