/**
 * SyncFlow Android App - Main Navigation and Screens
 *
 * Complete Jetpack Compose app using VPS backend only.
 */

package com.syncflow.vps.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.syncflow.api.*
import com.syncflow.services.SyncFlowService
import com.syncflow.services.SyncFlowState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// ==================== View Model ====================

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

    fun sendMessage(address: String, body: String) {
        viewModelScope.launch {
            try {
                service.sendMessage(address, body)
                _error.value = null
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun markAsRead(messageId: String) {
        viewModelScope.launch {
            try {
                service.markAsRead(messageId)
            } catch (e: Exception) {
                // Silently ignore
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

// ==================== Main App ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncFlowApp(
    service: SyncFlowService,
    viewModel: SyncFlowViewModel = viewModel { SyncFlowViewModel(service) }
) {
    val navController = rememberNavController()
    val state by viewModel.state.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    // Show error snackbar
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
                        selected = true,
                        onClick = { navController.navigate("messages") }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Contacts, "Contacts") },
                        label = { Text("Contacts") },
                        selected = false,
                        onClick = { navController.navigate("contacts") }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Call, "Calls") },
                        label = { Text("Calls") },
                        selected = false,
                        onClick = { navController.navigate("calls") }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Devices, "Devices") },
                        label = { Text("Devices") },
                        selected = false,
                        onClick = { navController.navigate("devices") }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            NavHost(
                navController = navController,
                startDestination = if (state.isAuthenticated) "messages" else "auth"
            ) {
                composable("auth") {
                    AuthScreen(service, navController)
                }
                composable("messages") {
                    MessagesScreen(viewModel)
                }
                composable("contacts") {
                    ContactsScreen(viewModel)
                }
                composable("calls") {
                    CallsScreen(viewModel)
                }
                composable("devices") {
                    DevicesScreen(viewModel, navController)
                }
            }
        }
    }
}

// ==================== Auth Screen ====================

@Composable
fun AuthScreen(service: SyncFlowService, navController: NavHostController) {
    var isLoading by remember { mutableStateOf(false) }
    var pairingToken by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "SyncFlow VPS",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "Connect your devices securely",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(48.dp))

        if (pairingToken != null) {
            // Show pairing QR code / token
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
                        "Enter this code on your other device:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        pairingToken!!,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else {
            Button(
                onClick = {
                    isLoading = true
                    // Launch pairing flow
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
fun MessagesScreen(viewModel: SyncFlowViewModel) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadMessages()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Messages") },
            actions = {
                if (state.isConnected) {
                    Icon(
                        Icons.Default.Cloud,
                        contentDescription = "Connected",
                        tint = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        Icons.Default.CloudOff,
                        contentDescription = "Disconnected",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
            }
        )

        if (state.messages.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No messages yet")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.messages) { message ->
                    MessageCard(message) {
                        viewModel.markAsRead(message.id)
                    }
                }
            }
        }
    }
}

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
fun ContactsScreen(viewModel: SyncFlowViewModel) {
    val state by viewModel.state.collectAsState()
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
                Text("No contacts found")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                items(filteredContacts) { contact ->
                    ContactItem(contact)
                }
            }
        }
    }
}

@Composable
fun ContactItem(contact: Contact) {
    ListItem(
        headlineContent = { Text(contact.displayName) },
        supportingContent = {
            contact.phoneNumbers.firstOrNull()?.let { Text(it) }
        },
        leadingContent = {
            Icon(Icons.Default.Person, contentDescription = null)
        }
    )
    HorizontalDivider()
}

// ==================== Calls Screen ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallsScreen(viewModel: SyncFlowViewModel) {
    val state by viewModel.state.collectAsState()
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
                Text("No call history")
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
                            Text(
                                formatDuration(call.duration),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
}

// ==================== Devices Screen ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(viewModel: SyncFlowViewModel, navController: NavHostController) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadDevices()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Devices") },
            actions = {
                IconButton(onClick = { viewModel.logout(); navController.navigate("auth") }) {
                    Icon(Icons.Default.Logout, "Logout")
                }
            }
        )

        if (state.devices.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No paired devices")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.devices) { device ->
                    DeviceCard(device, isCurrentDevice = device.id == state.deviceId)
                }
            }
        }
    }
}

@Composable
fun DeviceCard(device: Device, isCurrentDevice: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentDevice)
                MaterialTheme.colorScheme.primaryContainer
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
                    "ios", "macos" -> Icons.Default.Apple
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
                    device.deviceType,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isCurrentDevice) {
                Text(
                    "This device",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
