/**
 * ConversationListScreen.kt
 *
 * This file implements the main conversation list screen, which is the primary
 * entry point of the SyncFlow messaging app. It displays all SMS conversations
 * in a scrollable list with search, filtering, and quick action capabilities.
 *
 * Key Features:
 * - Conversation list with contact avatars and message previews
 * - Search functionality across contacts and message content
 * - SIM card filtering for dual-SIM devices
 * - Unread message filtering
 * - Swipe gestures for archive/pin actions
 * - Long-press context menu for additional actions
 * - Continuity banner for cross-device handoff
 * - SyncFlow Deals promotional card
 * - Floating action buttons for AI, stats, and new message
 *
 * Architecture:
 * - Follows MVVM pattern with SmsViewModel
 * - Uses state hoisting for all callback actions
 * - Broadcasts received via LocalBroadcastManager for real-time updates
 * - Swipe-to-dismiss for gesture-based interactions
 *
 * @see SmsViewModel for data management
 * @see ConversationInfo for conversation data model
 */
@file:OptIn(ExperimentalFoundationApi::class)

package com.phoneintegration.app.ui.conversations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Report
import androidx.compose.foundation.ExperimentalFoundationApi
import com.phoneintegration.app.continuity.ContinuityService
import com.phoneintegration.app.data.DraftRepository
import com.phoneintegration.app.data.PreferencesManager
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import android.widget.Toast
import com.phoneintegration.app.data.ArchiveRepository
import kotlinx.coroutines.launch
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import com.phoneintegration.app.SmsViewModel
import com.phoneintegration.app.ConversationInfo
import com.phoneintegration.app.SmsReceiver
import com.phoneintegration.app.ui.theme.Spacing
import com.phoneintegration.app.ui.theme.SyncFlowTypography
import com.phoneintegration.app.ui.theme.SyncFlowBlue
import com.phoneintegration.app.ui.theme.Blue100
import com.phoneintegration.app.ui.theme.Blue700
import com.phoneintegration.app.ui.components.AdBanner
import com.phoneintegration.app.ui.components.SyncFlowAvatar
import com.phoneintegration.app.ui.components.AvatarSize
import com.phoneintegration.app.ui.components.SyncFlowBadge
import com.phoneintegration.app.ui.components.SyncFlowEmptyState
import com.phoneintegration.app.ui.components.EmptyStateType
import com.phoneintegration.app.PhoneNumberUtils
import coil.compose.AsyncImage
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

// =============================================================================
// region MAIN SCREEN COMPOSABLE
// =============================================================================

/**
 * Main conversation list screen composable.
 *
 * This is the primary screen of the app, displaying all SMS conversations
 * in a searchable, filterable list. Supports dual-SIM filtering, swipe gestures,
 * and provides quick access to AI assistant and statistics.
 *
 * State Hoisting Pattern:
 * - ViewModel is passed in for state management
 * - All navigation callbacks are hoisted to the parent
 * - Local UI state (search query, menus) managed internally
 *
 * Side Effects:
 * - DisposableEffect for broadcast receiver registration
 * - DisposableEffect for continuity service lifecycle
 * - LaunchedEffect for loading SIMs and conversations on mount
 * - LaunchedEffect for periodic SMS permission checks
 *
 * @param viewModel The SmsViewModel for conversation data and actions
 * @param prefsManager Preferences manager for swipe gesture settings
 * @param onOpen Callback when a conversation is tapped (address, name)
 * @param onOpenStats Callback to navigate to statistics screen
 * @param onOpenSettings Callback to navigate to settings screen
 * @param onNewMessage Callback to start a new message
 * @param onOpenAI Callback to open AI assistant
 * @param onOpenScheduled Callback to view scheduled messages
 * @param onOpenArchived Callback to view archived conversations
 * @param onOpenDownloads Callback to view SyncFlow downloads
 * @param onOpenSpam Callback to view spam folder
 * @param onOpenBlocked Callback to view blocked contacts
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConversationListScreen(
    viewModel: SmsViewModel,
    prefsManager: PreferencesManager,
    onOpen: (address: String, name: String) -> Unit,
    onOpenStats: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onNewMessage: () -> Unit = {},
    onOpenAI: () -> Unit = {},
    onOpenScheduled: () -> Unit = {},
    onOpenArchived: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onOpenSpam: () -> Unit = {},
    onOpenBlocked: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val archiveRepository = remember { ArchiveRepository(context) }
    val continuityService = remember { ContinuityService(context.applicationContext) }
    val draftRepository = remember { DraftRepository(context.applicationContext) }

    val conversations by viewModel.conversations.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val availableSims by viewModel.availableSims.collectAsState()
    val selectedSimFilter by viewModel.selectedSimFilter.collectAsState()
    val spamAddresses by viewModel.spamAddresses.collectAsState()
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var continuityState by remember { mutableStateOf<ContinuityService.ContinuityState?>(null) }
    var dismissedContinuity by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var showUnreadOnly by remember { mutableStateOf(false) }

    // Track dismissed items for animation
    var dismissedThreadId by remember { mutableStateOf<Long?>(null) }

    // Delete confirmation dialog state
    var conversationToDelete by remember { mutableStateOf<ConversationInfo?>(null) }

    val swipeEnabled by remember { derivedStateOf { prefsManager.swipeGesturesEnabled.value } }

    DisposableEffect(Unit) {
        continuityService.startListening { state ->
            if (state == null) {
                continuityState = null
                return@startListening
            }

            val dismissed = dismissedContinuity
            if (dismissed != null && dismissed.first == state.deviceId && dismissed.second >= state.timestamp) {
                return@startListening
            }

            continuityState = state
        }

        onDispose {
            continuityService.stopListening()
        }
    }

    // Check SMS permission state - reload when permission is granted
    val hasSmsPermission = remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        )
    }

    // Periodically check for permission changes (handles cases where user grants permission via settings)
    LaunchedEffect(Unit) {
        while (true) {
            val currentPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
            if (currentPermission != hasSmsPermission.value) {
                hasSmsPermission.value = currentPermission
                if (currentPermission) {
                    Log.d("ConversationListScreen", "SMS permission granted - reloading conversations")
                    viewModel.loadConversations(forceReload = true)
                }
            }
            kotlinx.coroutines.delay(1000) // Check every second
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadAvailableSims()
        viewModel.loadConversations()
    }

    // Reload conversations when screen becomes visible (user navigates back from conversation detail)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        // Invalidate cache first to ensure fresh data loads
        viewModel.invalidateConversationCache()
        viewModel.loadConversations(forceReload = true)
    }

    // Listen for incoming SMS/MMS broadcasts to refresh conversation list
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.d("ConversationListScreen", "SMS/MMS received broadcast - refreshing conversations")
                viewModel.loadConversations()
            }
        }

        val filter = IntentFilter().apply {
            addAction(SmsReceiver.SMS_RECEIVED_ACTION)
            addAction("com.phoneintegration.app.MMS_RECEIVED")
        }
        LocalBroadcastManager.getInstance(context).registerReceiver(receiver, filter)

        onDispose {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(receiver)
        }
    }

    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("SyncFlow") },
                actions = {
                    // Overflow menu
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, "More options")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Scheduled Messages") },
                                onClick = {
                                    showMenu = false
                                    onOpenScheduled()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Schedule, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Archived") },
                                onClick = {
                                    showMenu = false
                                    onOpenArchived()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Archive, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Spam") },
                                onClick = {
                                    showMenu = false
                                    onOpenSpam()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Report, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Blocked Contacts") },
                                onClick = {
                                    showMenu = false
                                    onOpenBlocked()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Block, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("SyncFlow Downloads") },
                                onClick = {
                                    showMenu = false
                                    onOpenDownloads()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                onClick = {
                                    showMenu = false
                                    onOpenSettings()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Settings, contentDescription = null)
                                }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // AI Assistant FAB
                SmallFloatingActionButton(
                    onClick = onOpenAI,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Text(
                        text = "🧠",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                // Statistics FAB
                SmallFloatingActionButton(
                    onClick = onOpenStats,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Icon(
                        imageVector = Icons.Default.Analytics,
                        contentDescription = "Statistics"
                    )
                }

                // New Message FAB
                FloatingActionButton(
                    onClick = onNewMessage,
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "New Message"
                    )
                }
            }
        }
    ) { padding ->

        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search…") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp)
                )

                var expandedSimMenu by remember { mutableStateOf(false) }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp)
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (availableSims.size > 1) {
                        Text(
                            text = "SIM:",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(end = 8.dp)
                        )

                        Box {
                            AssistChip(
                                onClick = { expandedSimMenu = true },
                                label = {
                                    Text(
                                        when (selectedSimFilter) {
                                            null -> "All SIMs (${availableSims.size})"
                                            else -> {
                                                val sim = availableSims.find { it.subscriptionId == selectedSimFilter }
                                                sim?.let { "${it.displayName} (${it.carrierName})" } ?: "Unknown SIM"
                                            }
                                        }
                                    )
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            )

                            DropdownMenu(
                                expanded = expandedSimMenu,
                                onDismissRequest = { expandedSimMenu = false }
                            ) {
                                // "All SIMs" option
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("All SIMs (${availableSims.size})")
                                            if (selectedSimFilter == null) {
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text("✓", color = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    },
                                    onClick = {
                                        viewModel.setSimFilter(null)
                                        expandedSimMenu = false
                                    }
                                )

                                Divider()

                                // Individual SIM options
                                availableSims.forEach { sim ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Column {
                                                    Text(
                                                        text = sim.displayName,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Text(
                                                        text = "${sim.carrierName}${sim.phoneNumber?.let { " • $it" } ?: ""}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                if (selectedSimFilter == sim.subscriptionId) {
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text("✓", color = MaterialTheme.colorScheme.primary)
                                                }
                                            }
                                        },
                                        onClick = {
                                            viewModel.setSimFilter(sim.subscriptionId)
                                            expandedSimMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = !showUnreadOnly,
                            onClick = { showUnreadOnly = false },
                            label = { Text("All") },
                            modifier = Modifier
                        )
                        FilterChip(
                            selected = showUnreadOnly,
                            onClick = { showUnreadOnly = true },
                            label = { Text("Unread") },
                            modifier = Modifier
                        )
                    }
                }

                val filtered = remember(conversations, spamAddresses, query.text, showUnreadOnly) {
                    fun isSpamConversation(conversation: ConversationInfo): Boolean {
                        val addresses = if (conversation.relatedAddresses.isNotEmpty()) {
                            conversation.relatedAddresses
                        } else {
                            listOf(conversation.address)
                        }
                        return addresses.any { address ->
                            val normalized = PhoneNumberUtils.normalizeForConversation(address)
                            normalized.isNotBlank() && spamAddresses.contains(normalized)
                        }
                    }
                    val visibleConversations = conversations.filter { convo ->
                        convo.isAdConversation || !isSpamConversation(convo)
                    }
                    val q = query.text.lowercase()
                    val queryDigits = q.filter { it.isDigit() }
                    fun matchesNumber(address: String): Boolean {
                        if (queryDigits.isBlank()) return false
                        val addressDigits = address.filter { it.isDigit() }
                        val normalized = PhoneNumberUtils.normalizeForConversation(address)
                        return addressDigits.contains(queryDigits) ||
                            queryDigits.contains(addressDigits) ||
                            (normalized.isNotBlank() && (
                                normalized.contains(queryDigits) || queryDigits.contains(normalized)
                            ))
                    }
                    val searched = if (q.isBlank()) visibleConversations
                    else visibleConversations.filter {
                        it.address.lowercase().contains(q) ||
                                (it.contactName?.lowercase()?.contains(q) == true) ||
                                it.lastMessage.lowercase().contains(q) ||
                                matchesNumber(it.address)
                    }

                    if (showUnreadOnly) searched.filter { it.unreadCount > 0 } else searched
                }

                // NEVER show loading spinner - conversations load instantly from cache
                if (filtered.isEmpty()) {
                    SyncFlowEmptyState(
                        type = if (query.text.isNotBlank()) EmptyStateType.NoSearchResults else EmptyStateType.NoConversations,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        // Ad banner for free/trial users (at top of list)
                        item {
                            AdBanner(
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        if (continuityState != null) {
                            item {
                                val state = continuityState!!
                                val resolvedConversation = resolveContinuityConversation(state, conversations)
                                val targetAddress = resolvedConversation?.address ?: state.address
                                val targetName = resolvedConversation?.contactName ?: state.contactName ?: state.address
                                val targetThreadId = resolvedConversation?.threadId ?: state.threadId ?: 0L
                                ContinuityBanner(
                                    state = state,
                                    onOpen = {
                                        scope.launch {
                                            val draft = state.draft
                                            if (!draft.isNullOrBlank()) {
                                                draftRepository.saveDraftImmediate(
                                                    address = targetAddress,
                                                    body = draft,
                                                    threadId = targetThreadId,
                                                    contactName = targetName
                                                )
                                            }
                                            onOpen(targetAddress, targetName)
                                            continuityState = null
                                            dismissedContinuity = null
                                        }
                                    },
                                    onDismiss = {
                                        dismissedContinuity = state.deviceId to state.timestamp
                                        continuityState = null
                                    }
                                )
                            }
                        }
                        itemsIndexed(
                            items = filtered,
                            key = { _, convo -> convo.threadId }
                        ) { index, convo ->
                            val handleOpen = {
                                val name = convo.contactName ?: convo.address
                                onOpen(convo.address, name)
                            }
                            val handleArchive = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                scope.launch {
                                    archiveRepository.archiveConversation(convo)
                                    viewModel.loadConversations()
                                    Toast.makeText(context, "Conversation archived", Toast.LENGTH_SHORT).show()
                                }
                                Unit
                            }
                            val handlePin = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.togglePin(convo.threadId, convo.isPinned)
                                val msg = if (convo.isPinned) "Unpinned" else "Pinned"
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                Unit
                            }
                            val handleMute = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.toggleMute(convo.threadId, convo.isMuted)
                                val msg = if (convo.isMuted) "Notifications unmuted" else "Notifications muted"
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                Unit
                            }
                            val handleBlock = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.blockContact(convo.address, convo.contactName)
                                Toast.makeText(context, "Contact blocked", Toast.LENGTH_SHORT).show()
                                Unit
                            }
                            val handleMarkSpam = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.markConversationAsSpam(convo)
                                Toast.makeText(context, "Marked as spam", Toast.LENGTH_SHORT).show()
                                Unit
                            }
                            val handleDelete = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                conversationToDelete = convo
                                Unit
                            }
                            if (convo.isAdConversation) {
                                // Use custom SyncFlow Deals card (no swipe)
                                SyncFlowDealsCard {
                                    onOpen("syncflow_ads", "SyncFlow Deals")
                                }
                            } else {
                                if (swipeEnabled) {
                                    // Swipeable conversation item
                                    SwipeableConversationItem(
                                        conversation = convo,
                                        onOpen = handleOpen,
                                        onArchive = handleArchive,
                                        onPin = handlePin,
                                        onMute = handleMute,
                                        onBlock = handleBlock,
                                        onMarkSpam = handleMarkSpam,
                                        onDelete = handleDelete
                                    )
                                } else {
                                    NonSwipeConversationItem(
                                        conversation = convo,
                                        onOpen = handleOpen,
                                        onArchive = handleArchive,
                                        onPin = handlePin,
                                        onMute = handleMute,
                                        onBlock = handleBlock,
                                        onMarkSpam = handleMarkSpam,
                                        onDelete = handleDelete
                                    )
                                }
                            }

                            if (index < filtered.lastIndex) {
                                ConversationListSeparator()
                            }
                        }
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    conversationToDelete?.let { convo ->
        AlertDialog(
            onDismissRequest = { conversationToDelete = null },
            icon = {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Delete Conversation?") },
            text = {
                Column {
                    Text("All messages in this conversation with ${convo.contactName ?: convo.address} will be permanently deleted.")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "This action cannot be undone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteConversation(convo.threadId) { success ->
                            if (success) {
                                Toast.makeText(context, "Conversation deleted", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Failed to delete conversation", Toast.LENGTH_SHORT).show()
                            }
                        }
                        conversationToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete Conversation")
                }
            },
            dismissButton = {
                TextButton(onClick = { conversationToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// =============================================================================
// endregion
// =============================================================================

// =============================================================================
// region PROMOTIONAL COMPONENTS
// =============================================================================

/**
 * Promotional card for SyncFlow Deals.
 *
 * Displays an eye-catching gradient card with gift icon and "HOT" badge
 * to promote exclusive offers. Appears at the top of the conversation list.
 *
 * @param onClick Callback when the card is tapped
 */
@Composable
fun SyncFlowDealsCard(
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .shadow(8.dp, RoundedCornerShape(16.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.secondaryContainer,
                            MaterialTheme.colorScheme.tertiaryContainer
                        )
                    )
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Gift icon with background
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFFFFD700), // Gold
                                        Color(0xFFFFA500)  // Orange
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CardGiftcard,
                            contentDescription = "Deals",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    // Text content
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "SyncFlow Deals",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            // Hot badge
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFFFF4444),
                                modifier = Modifier.padding(start = 4.dp)
                            ) {
                                Text(
                                    text = "HOT",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "Exclusive offers just for you!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }

                // Arrow/Chevron indicator
                Icon(
                    imageVector = Icons.Default.LocalOffer,
                    contentDescription = "View deals",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .size(28.dp)
                        .padding(start = 8.dp)
                )
            }
        }
    }
}

// =============================================================================
// endregion
// =============================================================================

// =============================================================================
// region CONTINUITY FEATURE
// =============================================================================

/**
 * Resolves a continuity state to a matching conversation.
 *
 * Attempts to find the conversation matching the continuity handoff by:
 * 1. Exact address match
 * 2. Normalized phone number match
 * 3. Contact name match (case-insensitive)
 *
 * @param state The continuity state from another device
 * @param conversations List of local conversations to search
 * @return Matching conversation, or null if not found
 */
private fun resolveContinuityConversation(
    state: ContinuityService.ContinuityState,
    conversations: List<com.phoneintegration.app.ConversationInfo>
): com.phoneintegration.app.ConversationInfo? {
    val exact = conversations.firstOrNull { it.address == state.address }
    if (exact != null) {
        return exact
    }

    val normalizedTarget = normalizeAddress(state.address)
    if (normalizedTarget.isNotBlank()) {
        val normalizedMatch = conversations.firstOrNull {
            normalizeAddress(it.address) == normalizedTarget
        }
        if (normalizedMatch != null) {
            return normalizedMatch
        }
    }

    val name = state.contactName?.lowercase()?.trim()
    if (!name.isNullOrBlank()) {
        val nameMatch = conversations.firstOrNull {
            it.contactName?.lowercase()?.trim() == name
        }
        if (nameMatch != null) {
            return nameMatch
        }
    }

    return null
}

/**
 * Normalizes a phone address by extracting only digits.
 *
 * @param value The raw phone number or address string
 * @return String containing only digit characters
 */
private fun normalizeAddress(value: String): String {
    return value.filter { it.isDigit() }
}

/**
 * Banner displayed when a conversation handoff is available from another device.
 *
 * Shows device name, contact info, and provides Open/Dismiss actions.
 * Part of the cross-device continuity feature.
 *
 * @param state The continuity state containing handoff information
 * @param onOpen Callback to accept the handoff and open the conversation
 * @param onDismiss Callback to dismiss the handoff banner
 */
@Composable
private fun ContinuityBanner(
    state: ContinuityService.ContinuityState,
    onOpen: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Devices,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Continue from ${state.deviceName}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = state.contactName ?: state.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
            TextButton(onClick = onOpen) {
                Text("Open")
            }
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    }
}

// =============================================================================
// endregion
// =============================================================================

// =============================================================================
// region CONVERSATION LIST ITEMS
// =============================================================================

/**
 * Individual conversation list item displaying contact info and message preview.
 *
 * Shows avatar, contact name, last message preview, timestamp, and badges
 * for unread count, pinned status, muted status, E2EE encryption, and
 * group conversations.
 *
 * @param info The ConversationInfo data to display
 */
@Composable
fun ConversationListItem(
    info: ConversationInfo
) {
    val context = LocalContext.current

    Surface(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.listItemHorizontal, vertical = Spacing.listItemVertical),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Avatar using new component
                SyncFlowAvatar(
                    name = info.contactName ?: info.address,
                    imageUrl = info.photoUri,
                    size = AvatarSize.Medium,
                    backgroundColor = Blue100,
                    textColor = Blue700
                )

                Spacer(modifier = Modifier.width(Spacing.listItemContentGap))

                // -------------------------------
                // NAME + LAST MESSAGE
                // -------------------------------
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    // Name with group indicator
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Pin indicator
                        if (info.isPinned) {
                            Icon(
                                imageVector = Icons.Default.PushPin,
                                contentDescription = "Pinned",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }

                        Text(
                            text = info.contactName ?: info.address,
                            style = SyncFlowTypography.conversationTitle
                        )

                        if (info.isE2ee) {
                            Spacer(modifier = Modifier.width(Spacing.xxs))
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "E2EE",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Mute indicator
                        if (info.isMuted) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.VolumeOff,
                                contentDescription = "Muted",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        // Group conversation indicator
                        if (info.isGroupConversation) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.Groups,
                                contentDescription = "Group conversation",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "(${info.recipientCount})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.xxxs))

                    Text(
                        text = info.lastMessage,
                        style = SyncFlowTypography.conversationPreview,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // -------------------------------
            // TIMESTAMP
            // -------------------------------
            Column(
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = formatConversationTime(info.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Show unread badge if there are unread messages
                if (info.unreadCount > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    SyncFlowBadge(
                        count = info.unreadCount
                    )
                }
            }
        }
    }
}

/**
 * Formats a timestamp for display in the conversation list.
 *
 * Uses smart formatting based on age:
 * - Same day: Time (e.g., "2:30 PM")
 * - Yesterday: "Yesterday"
 * - Same week: Day name (e.g., "Mon")
 * - Same year: Month and day (e.g., "Jan 15")
 * - Different year: Full date (e.g., "01/15/24")
 *
 * @param timestamp The message timestamp in milliseconds
 * @return Formatted time/date string, or empty string if timestamp is invalid
 */
private fun formatConversationTime(timestamp: Long): String {
    if (timestamp <= 0) return ""

    val messageDate = Date(timestamp)
    val now = Calendar.getInstance()
    val messageCalendar = Calendar.getInstance().apply { time = messageDate }

    return when {
        // Same day - show time
        now.get(Calendar.YEAR) == messageCalendar.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == messageCalendar.get(Calendar.DAY_OF_YEAR) -> {
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(messageDate)
        }
        // Yesterday
        now.get(Calendar.YEAR) == messageCalendar.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) - messageCalendar.get(Calendar.DAY_OF_YEAR) == 1 -> {
            "Yesterday"
        }
        // Within the same week - show day name
        now.get(Calendar.YEAR) == messageCalendar.get(Calendar.YEAR) &&
        now.get(Calendar.WEEK_OF_YEAR) == messageCalendar.get(Calendar.WEEK_OF_YEAR) -> {
            SimpleDateFormat("EEE", Locale.getDefault()).format(messageDate)
        }
        // Same year - show month and day
        now.get(Calendar.YEAR) == messageCalendar.get(Calendar.YEAR) -> {
            SimpleDateFormat("MMM d", Locale.getDefault()).format(messageDate)
        }
        // Different year - show full date
        else -> {
            SimpleDateFormat("MM/dd/yy", Locale.getDefault()).format(messageDate)
        }
    }
}

// =============================================================================
// region SWIPEABLE CONVERSATION ITEMS
// =============================================================================

/**
 * Swipeable conversation list item with gesture support.
 *
 * Supports swipe gestures:
 * - Swipe left to archive
 * - Swipe right to pin/unpin
 *
 * Also supports long-press for context menu with additional actions.
 *
 * @param conversation The ConversationInfo data to display
 * @param onOpen Callback when item is tapped
 * @param onArchive Callback when swiped left to archive
 * @param onPin Callback when swiped right to pin/unpin
 * @param onMute Callback from context menu to mute/unmute
 * @param onBlock Callback from context menu to block contact
 * @param onMarkSpam Callback from context menu to mark as spam
 * @param onDelete Callback from context menu to delete conversation
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SwipeableConversationItem(
    conversation: ConversationInfo,
    onOpen: () -> Unit,
    onArchive: () -> Unit,
    onPin: () -> Unit,
    onMute: () -> Unit,
    onBlock: () -> Unit,
    onMarkSpam: () -> Unit,
    onDelete: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { dismissValue ->
            when (dismissValue) {
                SwipeToDismissBoxValue.EndToStart -> {
                    // Swipe left to archive
                    onArchive()
                    false // Don't dismiss, we'll reload the list
                }
                SwipeToDismissBoxValue.StartToEnd -> {
                    // Swipe right to pin/unpin
                    onPin()
                    false // Don't dismiss
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
        positionalThreshold = { it * 0.4f }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val color by animateColorAsState(
                when (dismissState.targetValue) {
                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.primaryContainer
                    SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.tertiaryContainer
                    SwipeToDismissBoxValue.Settled -> Color.Transparent
                },
                label = "swipe_color"
            )
            val alignment = when (direction) {
                SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                else -> Alignment.Center
            }
            val icon = when (direction) {
                SwipeToDismissBoxValue.EndToStart -> Icons.Default.Archive
                SwipeToDismissBoxValue.StartToEnd -> Icons.Default.PushPin
                else -> Icons.Default.Archive
            }
            val iconTint = when (direction) {
                SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.onPrimaryContainer
                SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.onTertiaryContainer
                else -> MaterialTheme.colorScheme.onSurface
            }
            val text = when (direction) {
                SwipeToDismissBoxValue.EndToStart -> "Archive"
                SwipeToDismissBoxValue.StartToEnd -> if (conversation.isPinned) "Unpin" else "Pin"
                else -> ""
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color)
                    .padding(horizontal = 20.dp),
                contentAlignment = alignment
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (direction == SwipeToDismissBoxValue.StartToEnd)
                        Arrangement.Start else Arrangement.End
                ) {
                    if (direction == SwipeToDismissBoxValue.StartToEnd) {
                        Icon(
                            imageVector = icon,
                            contentDescription = text,
                            tint = iconTint
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = text,
                            color = iconTint,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Text(
                            text = text,
                            color = iconTint,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = icon,
                            contentDescription = text,
                            tint = iconTint
                        )
                    }
                }
            }
        },
        content = {
            ConversationItemContent(
                conversation = conversation,
                onOpen = onOpen,
                onArchive = onArchive,
                onPin = onPin,
                onMute = onMute,
                onBlock = onBlock,
                onMarkSpam = onMarkSpam,
                onDelete = onDelete,
                showPinArchiveInMenu = true
            ) {
                ConversationListItem(info = conversation)
            }
        }
    )
}

/**
 * Non-swipeable conversation list item.
 *
 * Used when swipe gestures are disabled in settings. Still supports
 * long-press for context menu with all actions.
 *
 * @param conversation The ConversationInfo data to display
 * @param onOpen Callback when item is tapped
 * @param onArchive Callback from context menu to archive
 * @param onPin Callback from context menu to pin/unpin
 * @param onMute Callback from context menu to mute/unmute
 * @param onBlock Callback from context menu to block contact
 * @param onMarkSpam Callback from context menu to mark as spam
 * @param onDelete Callback from context menu to delete conversation
 */
@Composable
fun NonSwipeConversationItem(
    conversation: ConversationInfo,
    onOpen: () -> Unit,
    onArchive: () -> Unit,
    onPin: () -> Unit,
    onMute: () -> Unit,
    onBlock: () -> Unit,
    onMarkSpam: () -> Unit,
    onDelete: () -> Unit
) {
    ConversationItemContent(
        conversation = conversation,
        onOpen = onOpen,
        onArchive = onArchive,
        onPin = onPin,
        onMute = onMute,
        onBlock = onBlock,
        onMarkSpam = onMarkSpam,
        onDelete = onDelete,
        showPinArchiveInMenu = false
    ) {
        ConversationListItem(info = conversation)
    }
}

// =============================================================================
// endregion
// =============================================================================

// =============================================================================
// region HELPER COMPONENTS
// =============================================================================

/**
 * Visual separator between conversation list items.
 *
 * Thin divider line with proper insets matching the avatar width.
 */
@Composable
private fun ConversationListSeparator() {
    Divider(
        color = MaterialTheme.colorScheme.outlineVariant,
        thickness = 1.dp,
        modifier = Modifier.padding(
            start = Spacing.dividerInsetStart,
            end = Spacing.listItemHorizontal
        )
    )
}

/**
 * Wrapper component providing click and long-press behavior for conversation items.
 *
 * Handles tap to open and long-press to show context menu. Used by both
 * SwipeableConversationItem and NonSwipeConversationItem.
 *
 * @param conversation The ConversationInfo for context menu state
 * @param onOpen Callback when item is tapped
 * @param onArchive Callback from context menu to archive
 * @param onPin Callback from context menu to pin/unpin
 * @param onMute Callback from context menu to mute/unmute
 * @param onBlock Callback from context menu to block contact
 * @param onMarkSpam Callback from context menu to mark as spam
 * @param onDelete Callback from context menu to delete conversation
 * @param showPinArchiveInMenu Whether to show pin/archive in menu (false for swipeable items)
 * @param content The content composable to wrap (typically ConversationListItem)
 */
@Composable
private fun ConversationItemContent(
    conversation: ConversationInfo,
    onOpen: () -> Unit,
    onArchive: () -> Unit,
    onPin: () -> Unit,
    onMute: () -> Unit,
    onBlock: () -> Unit,
    onMarkSpam: () -> Unit,
    onDelete: () -> Unit,
    showPinArchiveInMenu: Boolean,
    content: @Composable () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    var showContextMenu by remember { mutableStateOf(false) }

    Box {
        Box(
            modifier = Modifier.combinedClickable(
                onClick = onOpen,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    showContextMenu = true
                }
            )
        ) {
            content()
        }

        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false }
        ) {
            if (showPinArchiveInMenu) {
                DropdownMenuItem(
                    text = { Text(if (conversation.isPinned) "Unpin" else "Pin") },
                    onClick = {
                        showContextMenu = false
                        onPin()
                    },
                    leadingIcon = {
                        Icon(Icons.Default.PushPin, contentDescription = null)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Archive") },
                    onClick = {
                        showContextMenu = false
                        onArchive()
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Archive, contentDescription = null)
                    }
                )
            }

            DropdownMenuItem(
                text = { Text(if (conversation.isMuted) "Unmute" else "Mute") },
                onClick = {
                    showContextMenu = false
                    onMute()
                },
                leadingIcon = {
                    Icon(Icons.Default.VolumeOff, contentDescription = null)
                }
            )
            DropdownMenuItem(
                text = { Text("Mark as Spam") },
                onClick = {
                    showContextMenu = false
                    onMarkSpam()
                },
                leadingIcon = {
                    Icon(Icons.Default.Report, contentDescription = null)
                }
            )

            Divider()

            DropdownMenuItem(
                text = { Text("Block Contact") },
                onClick = {
                    showContextMenu = false
                    onBlock()
                },
                leadingIcon = {
                    Icon(Icons.Default.Block, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                }
            )
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    showContextMenu = false
                    onDelete()
                },
                leadingIcon = {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                }
            )
        }
    }
}

// =============================================================================
// endregion
// =============================================================================
