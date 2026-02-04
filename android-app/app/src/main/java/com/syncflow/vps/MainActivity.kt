package com.syncflow.vps

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.Divider
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.syncflow.vps.api.*
import com.syncflow.vps.services.SyncFlowService
import com.syncflow.vps.services.SyncFlowState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SyncFlowVPSApp

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SyncFlowApp(app.syncFlowService)
                }
            }
        }
    }
}

// ==================== ViewModel ====================

class SyncFlowViewModel(private val service: SyncFlowService) : ViewModel() {
    val state: StateFlow<SyncFlowState> = service.state

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        viewModelScope.launch {
            service.init()
        }
    }

    fun loadMessages() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                service.loadMessages()
                _error.value = null
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadContacts() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                service.loadContacts()
                _error.value = null
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadCallHistory() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                service.loadCallHistory()
                _error.value = null
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadDevices() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                service.loadDevices()
                _error.value = null
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
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

    fun clearError() {
        _error.value = null
    }
}

class SyncFlowViewModelFactory(
    private val service: SyncFlowService
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return SyncFlowViewModel(service) as T
    }
}

// ==================== Main App ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncFlowApp(service: SyncFlowService) {
    val viewModel: SyncFlowViewModel = viewModel(factory = SyncFlowViewModelFactory(service))
    val state by viewModel.state.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (state.isAuthenticated) {
                NavigationBar {
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Message, "Messages") },
                        label = { Text("Messages") },
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Contacts, "Contacts") },
                        label = { Text("Contacts") },
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Call, "Calls") },
                        label = { Text("Calls") },
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Devices, "Devices") },
                        label = { Text("Devices") },
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (!state.isAuthenticated) {
                SetupScreen(service)
            } else {
                when (selectedTab) {
                    0 -> MessagesScreen(viewModel, state)
                    1 -> ContactsScreen(viewModel, state)
                    2 -> CallsScreen(viewModel, state)
                    3 -> DevicesScreen(viewModel, state)
                }
            }
        }
    }
}

// ==================== Setup Screen (Android is primary device) ====================

@Composable
fun SetupScreen(service: SyncFlowService) {
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Auto-authenticate on first launch
    LaunchedEffect(Unit) {
        try {
            service.authenticateAnonymous()
        } catch (e: Exception) {
            error = e.message ?: "Failed to initialize"
        } finally {
            isLoading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Sync,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            "SyncFlow VPS",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "Setting up your device...",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(48.dp))

        if (isLoading) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Connecting to server...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        error?.let {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {
                scope.launch {
                    isLoading = true
                    error = null
                    try {
                        service.authenticateAnonymous()
                    } catch (e: Exception) {
                        error = e.message ?: "Failed to initialize"
                    } finally {
                        isLoading = false
                    }
                }
            }) {
                Text("Retry")
            }
        }
    }
}

// ==================== Pairing Approval Screen ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingApprovalScreen(service: SyncFlowService, pairingToken: String, onComplete: () -> Unit) {
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
        Icon(
            Icons.Default.DevicesOther,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            "Pairing Request",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "A device wants to connect to your account",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

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
                Text(
                    "Pairing Code:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    pairingToken,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                scope.launch {
                    isLoading = true
                    error = null
                    try {
                        service.completePairing(pairingToken)
                        onComplete()
                    } catch (e: Exception) {
                        error = e.message ?: "Failed to approve"
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
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Approve Pairing")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = onComplete,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Cancel")
        }

        error?.let {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

// ==================== Messages Screen ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(viewModel: SyncFlowViewModel, state: SyncFlowState) {
    LaunchedEffect(Unit) {
        viewModel.loadMessages()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Messages") },
            actions = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (state.isConnected) Icons.Default.Cloud else Icons.Default.CloudOff,
                        contentDescription = if (state.isConnected) "Connected" else "Disconnected",
                        tint = if (state.isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                }
            }
        )

        if (state.messages.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Message,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("No messages yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.messages) { message ->
                    MessageCard(message) { viewModel.markAsRead(message.id) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageCard(message: Message, onRead: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (message.read)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        ),
        onClick = onRead
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    message.contactName ?: message.address,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (!message.read) FontWeight.Bold else FontWeight.Normal
                )
                Text(
                    dateFormat.format(Date(message.date)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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

// ==================== Contacts Screen ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(viewModel: SyncFlowViewModel, state: SyncFlowState) {
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.loadContacts()
    }

    val filteredContacts = if (searchQuery.isBlank()) {
        state.contacts
    } else {
        state.contacts.filter {
            it.displayName.contains(searchQuery, ignoreCase = true) ||
            it.phoneNumbers.any { phone -> phone.contains(searchQuery) }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Contacts") })

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            placeholder = { Text("Search contacts") },
            leadingIcon = { Icon(Icons.Default.Search, "Search") },
            singleLine = true
        )

        if (filteredContacts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No contacts found", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                items(filteredContacts) { contact ->
                    ListItem(
                        headlineContent = { Text(contact.displayName) },
                        supportingContent = {
                            contact.phoneNumbers.firstOrNull()?.let { Text(it) }
                        },
                        leadingContent = {
                            Icon(Icons.Default.Person, contentDescription = null)
                        }
                    )
                    Divider()
                }
            }
        }
    }
}

// ==================== Calls Screen ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallsScreen(viewModel: SyncFlowViewModel, state: SyncFlowState) {
    val dateFormat = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }

    LaunchedEffect(Unit) {
        viewModel.loadCallHistory()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Call History") })

        if (state.calls.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No call history", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                items(state.calls) { call ->
                    ListItem(
                        headlineContent = { Text(call.contactName ?: call.phoneNumber) },
                        supportingContent = {
                            Text(dateFormat.format(Date(call.callDate)))
                        },
                        leadingContent = {
                            Icon(
                                when (call.callType) {
                                    1 -> Icons.Default.CallReceived
                                    2 -> Icons.Default.CallMade
                                    3 -> Icons.Default.CallMissed
                                    else -> Icons.Default.Call
                                },
                                contentDescription = null,
                                tint = when (call.callType) {
                                    3 -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.primary
                                }
                            )
                        },
                        trailingContent = {
                            val mins = call.duration / 60
                            val secs = call.duration % 60
                            Text(
                                if (mins > 0) "${mins}m ${secs}s" else "${secs}s",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    )
                    Divider()
                }
            }
        }
    }
}

// ==================== Devices Screen ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(viewModel: SyncFlowViewModel, state: SyncFlowState) {
    var showPairingDialog by remember { mutableStateOf(false) }
    var pairingCode by remember { mutableStateOf("") }
    var isPairing by remember { mutableStateOf(false) }
    var pairingError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val service = (LocalContext.current.applicationContext as SyncFlowVPSApp).syncFlowService

    LaunchedEffect(Unit) {
        viewModel.loadDevices()
    }

    // Pairing Dialog
    if (showPairingDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isPairing) {
                    showPairingDialog = false
                    pairingCode = ""
                    pairingError = null
                }
            },
            title = { Text("Pair New Device") },
            text = {
                Column {
                    Text(
                        "Enter the pairing code shown on your Mac or web browser:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = pairingCode,
                        onValueChange = { pairingCode = it.uppercase() },
                        label = { Text("Pairing Code") },
                        singleLine = true,
                        enabled = !isPairing,
                        modifier = Modifier.fillMaxWidth()
                    )
                    pairingError?.let {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            isPairing = true
                            pairingError = null
                            try {
                                service.completePairing(pairingCode.trim())
                                showPairingDialog = false
                                pairingCode = ""
                                viewModel.loadDevices()
                            } catch (e: Exception) {
                                pairingError = e.message ?: "Pairing failed"
                            } finally {
                                isPairing = false
                            }
                        }
                    },
                    enabled = pairingCode.isNotBlank() && !isPairing
                ) {
                    if (isPairing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text("Pair")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPairingDialog = false
                        pairingCode = ""
                        pairingError = null
                    },
                    enabled = !isPairing
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Devices") },
            actions = {
                IconButton(onClick = { viewModel.logout() }) {
                    Icon(Icons.Default.Logout, "Logout")
                }
            }
        )

        // Pair New Device Button
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            onClick = { showPairingDialog = true }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.AddCircle,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        "Pair New Device",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Connect Mac or web browser",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (state.devices.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No paired devices yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Text(
                "Paired Devices",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.devices) { device ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (device.id == state.deviceId)
                                MaterialTheme.colorScheme.secondaryContainer
                            else
                                MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                when (device.deviceType) {
                                    "android" -> Icons.Default.PhoneAndroid
                                    "macos" -> Icons.Default.Laptop
                                    "web" -> Icons.Default.Computer
                                    else -> Icons.Default.Devices
                                },
                                contentDescription = null,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    device.deviceName,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    device.deviceType.replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (device.id == state.deviceId) {
                                Text(
                                    "This device",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
