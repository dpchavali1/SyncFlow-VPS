package com.phoneintegration.app.ui.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.phoneintegration.app.SmsReceiver
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Info
import com.phoneintegration.app.webrtc.SyncFlowCallManager
import com.phoneintegration.app.SyncFlowCallService
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.phoneintegration.app.R
import com.phoneintegration.app.SmsMessage
import com.phoneintegration.app.SmsViewModel
import com.phoneintegration.app.continuity.ContinuityService
import com.phoneintegration.app.data.DraftRepository
import com.phoneintegration.app.data.ScheduledMessageRepository
import com.phoneintegration.app.realtime.TypingIndicatorManager
import com.phoneintegration.app.PhoneNumberUtils
import kotlinx.coroutines.launch
import java.io.File
import com.phoneintegration.app.data.database.AppDatabase
import com.phoneintegration.app.data.database.BlockedContact
import com.phoneintegration.app.data.database.SpamMessage
import com.phoneintegration.app.utils.SpamFilter
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Warning


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationDetailScreen(
    threadId: Long,  // Thread ID for loading messages
    address: String,
    contactName: String,
    viewModel: SmsViewModel,
    onBack: () -> Unit,
    groupMembers: List<String>? = null,  // Optional list of group member names
    groupId: Long? = null,  // Optional group ID for deletion
    onDeleteGroup: ((Long) -> Unit)? = null  // Callback for group deletion
) {
    val context = LocalContext.current
    val messages by viewModel.conversationMessages.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()
    val smart by viewModel.smartReplies.collectAsState()
    val reactions by viewModel.messageReactions.collectAsState()
    // Temporarily provide empty map for read receipts
    val readReceipts by remember { mutableStateOf<Map<String, Any>>(emptyMap()) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showContactInfo by remember { mutableStateOf(false) }

    // Block and spam state
    val database = remember { AppDatabase.getInstance(context) }
    var isBlocked by remember { mutableStateOf(false) }
    var showBlockConfirmDialog by remember { mutableStateOf(false) }
    var showReportSpamDialog by remember { mutableStateOf(false) }
    var showDeleteMessageDialog by remember { mutableStateOf(false) }
    var messageToDelete by remember { mutableStateOf<SmsMessage?>(null) }
    val relatedAddresses by viewModel.relatedAddresses.collectAsState()
    val preferredSendAddress by viewModel.preferredSendAddress.collectAsState()
    val effectiveSendAddress = preferredSendAddress ?: address
    val displayRelatedAddresses = if (relatedAddresses.isNotEmpty()) relatedAddresses else listOf(address)

    var replyToMessage by remember { mutableStateOf<SmsMessage?>(null) }

    // Check if contact is blocked
    LaunchedEffect(address) {
        isBlocked = database.blockedContactDao().isBlocked(address)
        replyToMessage = null
    }

    // Draft and Scheduled message repositories
    val draftRepository = remember { DraftRepository(context) }
    val scheduledRepository = remember { ScheduledMessageRepository(context) }
    val typingManager = remember { TypingIndicatorManager(context) }
    val continuityService = remember { ContinuityService(context.applicationContext) }

    // Load draft on first load
    var draftLoaded by remember { mutableStateOf(false) }

    // SyncFlow video call manager for user-to-user calls (use the service's manager)
    val callManager = remember { SyncFlowCallService.getCallManager() }

    // Permission launcher for video call
    val videoCallPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true
        val micGranted = permissions[Manifest.permission.RECORD_AUDIO] == true

        if (cameraGranted && micGranted) {
            // Permissions granted, start the video call
            // Start the call service first
            SyncFlowCallService.startService(context)

            scope.launch {
                // Wait a moment for service to initialize
                kotlinx.coroutines.delay(500)

                val manager = SyncFlowCallService.getCallManager()
                if (manager == null) {
                    Toast.makeText(context, "Call service not ready. Please try again.", Toast.LENGTH_LONG).show()
                    return@launch
                }
                val result = manager.startCallToUser(
                    recipientPhoneNumber = effectiveSendAddress,
                    recipientName = contactName,
                    isVideo = true
                )
                result.onSuccess {
                    Toast.makeText(context, "Calling $contactName...", Toast.LENGTH_SHORT).show()
                }
                result.onFailure { e ->
                    Toast.makeText(context, e.message ?: "Failed to start call", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            Toast.makeText(context, "Camera and microphone permissions are required for video calls", Toast.LENGTH_LONG).show()
        }
    }

    var input by remember { mutableStateOf("") }
    var selectedMessage by remember { mutableStateOf<SmsMessage?>(null) }
    var showActions by remember { mutableStateOf(false) }
    var showForwardDialog by remember { mutableStateOf(false) }
    var showMediaGallery by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }

    // Starred messages
    val starredMessageIds by viewModel.starredMessageIds.collectAsState()
    val conversations by viewModel.conversations.collectAsState()

    // Load starred message IDs when entering the screen
    LaunchedEffect(Unit) {
        viewModel.loadStarredMessageIds()
    }

    // Filter messages based on search
    val filteredMessages = remember(messages, searchText) {
        if (searchText.isBlank()) {
            messages
        } else {
            messages.filter { it.body.contains(searchText, ignoreCase = true) }
        }
    }

    // Mark thread as read when conversation is opened
    LaunchedEffect(threadId) {
        if (threadId > 0) {
            viewModel.markThreadRead(threadId)
        }
    }

    // Mark messages as read for Firebase sync
    LaunchedEffect(messages, effectiveSendAddress) {
        viewModel.markConversationMessagesRead(effectiveSendAddress, messages)
    }

    // FAB only for SyncFlow Deals fake conversation
    val isDealsConversation = address == "syncflow_ads"

    // Attachment state
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var selectedAttachmentUri by remember { mutableStateOf<Uri?>(null) }
    var mmsMessageText by remember { mutableStateOf("") }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    val replyPreview = replyToMessage?.let { buildReplyPreview(it) }

    // 🚀 Gallery picker
    val galleryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            selectedAttachmentUri = uri
            showAttachmentSheet = false
        }
    }

    // 🚀 Camera capture
    val cameraCapture = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            pendingCameraUri?.let { uri ->
                selectedAttachmentUri = uri
                showAttachmentSheet = false
            }
        } else {
            pendingCameraUri = null
        }
    }

    val startCameraCapture = {
        val file = File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
        pendingCameraUri = uri
        cameraCapture.launch(uri)
    }

    LaunchedEffect(selectedAttachmentUri) {
        if (selectedAttachmentUri != null) {
            mmsMessageText = input
        } else {
            mmsMessageText = ""
        }
    }

    // Camera permission launcher
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCameraCapture()
        } else {
            Toast.makeText(context, "Camera permission is required to take photos", Toast.LENGTH_SHORT).show()
        }
    }

    // First time load
    LaunchedEffect(threadId) {
        // Always load by thread ID
        viewModel.loadConversationByThreadId(threadId, contactName)
    }

    // Load draft on first load
    LaunchedEffect(address) {
        if (!draftLoaded && address.isNotBlank() && address != "syncflow_ads") {
            val draft = draftRepository.getDraft(address)
            if (draft != null && draft.body.isNotBlank()) {
                input = draft.body
            }
            draftLoaded = true
            continuityService.updateConversationState(address, contactName, threadId, input)
        }
    }

    // Auto-save draft when input changes (with debouncing in repository)
    LaunchedEffect(input) {
        if (draftLoaded && address.isNotBlank() && address != "syncflow_ads") {
            draftRepository.saveDraftDebounced(address, input, null, contactName)
            // Update typing indicator
            if (input.isNotBlank()) {
                typingManager.startTyping(address)
            } else {
                typingManager.stopTyping(address)
            }
            continuityService.updateConversationState(address, contactName, threadId, input)
        }
    }

    // Stop typing when leaving screen
    DisposableEffect(address) {
        onDispose {
            typingManager.stopTyping(address)
        }
    }

    // Scroll to bottom on new messages
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            scope.launch { listState.scrollToItem(0) }
        }
    }

    // Mark thread read locally so the conversation list stops showing unread badges
    LaunchedEffect(threadId, messages.size) {
        if (messages.isNotEmpty()) {
            viewModel.markThreadRead(threadId)
        }
    }

    // Listen for incoming SMS/MMS broadcasts to refresh this conversation
    DisposableEffect(threadId) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val receivedAddress = intent?.getStringExtra(SmsReceiver.EXTRA_ADDRESS)
                Log.d("ConversationDetailScreen", "SMS/MMS received from $receivedAddress - current address: $address")

                // Reload conversation by thread ID
                viewModel.loadConversationByThreadId(threadId, contactName)
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(contactName)
                        // Show group members if this is a group
                        if (!groupMembers.isNullOrEmpty()) {
                            Text(
                                text = groupMembers.joinToString(", "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                maxLines = 1
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    // Show delete button for groups
                    if (groupId != null && onDeleteGroup != null) {
                        var showDeleteDialog by remember { mutableStateOf(false) }

                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                painter = painterResource(id = android.R.drawable.ic_menu_delete),
                                contentDescription = "Delete Group",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }

                        if (showDeleteDialog) {
                            AlertDialog(
                                onDismissRequest = { showDeleteDialog = false },
                                title = { Text("Delete Group") },
                                text = { Text("Are you sure you want to delete this group? This will only remove the group from your list, not delete the messages.") },
                                confirmButton = {
                                    TextButton(onClick = {
                                        showDeleteDialog = false
                                        onDeleteGroup(groupId)
                                    }) {
                                        Text("Delete", color = MaterialTheme.colorScheme.error)
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showDeleteDialog = false }) {
                                        Text("Cancel")
                                    }
                                }
                            )
                        }
                    }

                    // Media gallery button
                    IconButton(onClick = { showMediaGallery = true }) {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = "Media Gallery"
                        )
                    }

                    // Search button
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(
                            imageVector = if (showSearch) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (showSearch) "Close search" else "Search"
                        )
                    }

                    IconButton(onClick = { showContactInfo = true }) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Contact info"
                        )
                    }

                    // Don't show call buttons for SyncFlow Deals conversation or groups
                    if (!isDealsConversation && groupMembers.isNullOrEmpty()) {
                        // SyncFlow Video Call button (user-to-user)
                        IconButton(
                            onClick = {
                                // Check if permissions are already granted
                                val cameraPermission = ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.CAMERA
                                )
                                val micPermission = ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.RECORD_AUDIO
                                )

                                Log.d("VideoCall", "Video call button tapped for $address")
                                if (cameraPermission == PackageManager.PERMISSION_GRANTED &&
                                    micPermission == PackageManager.PERMISSION_GRANTED) {
                                    // Permissions already granted, start call
                                    Log.d("VideoCall", "Permissions granted, starting call to $effectiveSendAddress")

                                    // Start the call service first
                                    SyncFlowCallService.startService(context)

                                    scope.launch {
                                        // Wait a moment for service to initialize
                                        kotlinx.coroutines.delay(500)

                                        try {
                                            val manager = SyncFlowCallService.getCallManager()
                                            if (manager == null) {
                                                Toast.makeText(context, "Call service not ready. Please try again.", Toast.LENGTH_LONG).show()
                                                return@launch
                                            }
                                            val result = manager.startCallToUser(
                                                recipientPhoneNumber = effectiveSendAddress,
                                                recipientName = contactName,
                                                isVideo = true
                                            )
                                            result.onSuccess {
                                                Log.d("VideoCall", "Call started successfully")
                                                Toast.makeText(context, "Calling $contactName...", Toast.LENGTH_SHORT).show()
                                            }
                                            result.onFailure { e ->
                                                Log.e("VideoCall", "Call failed: ${e.message}", e)
                                                Toast.makeText(context, e.message ?: "Failed to start call", Toast.LENGTH_LONG).show()
                                            }
                                        } catch (e: Exception) {
                                            Log.e("VideoCall", "Exception starting call", e)
                                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                } else {
                                    Log.d("VideoCall", "Requesting permissions")
                                    // Request permissions
                                    videoCallPermissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.CAMERA,
                                            Manifest.permission.RECORD_AUDIO
                                        )
                                    )
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Videocam,
                                contentDescription = "Video Call",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        // Regular phone call button
                        IconButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_CALL).apply {
                                data = Uri.parse("tel:$effectiveSendAddress")
                                }
                                context.startActivity(intent)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Phone,
                                contentDescription = "Call",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            )
        },

        bottomBar = {
            ModernMessageInput(
                value = input,
                onValueChange = { input = it },
                onSend = { message ->
                    // Clear draft on send
                    scope.launch {
                        draftRepository.deleteDraft(address)
                    }
                    val replyPrefix = replyToMessage?.let { buildReplyPrefix(it) }
                    val composedMessage = mergeReplyPrefix(replyPrefix, message)
                    viewModel.sendSms(effectiveSendAddress, composedMessage) { ok ->
                        if (!ok) {
                            Toast.makeText(context, "Failed to send", Toast.LENGTH_SHORT).show()
                        } else {
                            replyToMessage = null
                        }
                    }
                },
                onSchedule = { message, scheduledTime ->
                    scope.launch {
                        val replyPrefix = replyToMessage?.let { buildReplyPrefix(it) }
                        val composedMessage = mergeReplyPrefix(replyPrefix, message)
                        scheduledRepository.scheduleMessage(
                            address = effectiveSendAddress,
                            body = composedMessage,
                            scheduledTime = scheduledTime,
                            contactName = contactName
                        )
                        // Clear draft after scheduling
                        draftRepository.deleteDraft(address)
                        replyToMessage = null
                        Toast.makeText(context, "Message scheduled", Toast.LENGTH_SHORT).show()
                    }
                },
                onAttach = { showAttachmentSheet = true },
                onCamera = {
                    // Launch camera for quick photo
                    showAttachmentSheet = true
                },
                onGif = {
                    // Show GIF picker (placeholder - can be enhanced later)
                    Toast.makeText(context, "GIF picker coming soon", Toast.LENGTH_SHORT).show()
                },
                smartReplies = smart,
                replyPreview = replyPreview,
                onClearReply = { replyToMessage = null }
            )
        }
    ) { padding ->

        Column(Modifier.padding(padding).fillMaxSize()) {
            // Search bar
            if (showSearch) {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    placeholder = { Text("Search in conversation...") },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (searchText.isNotEmpty()) {
                            IconButton(onClick = { searchText = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                reverseLayout = true,
                state = listState
            ) {
                itemsIndexed(filteredMessages) { index, sms ->
                // Temporarily disabled read receipts functionality
                val readReceipt = null // readReceipts[viewModel.getMessageKey(sms)] as? ReadReceipt
                MessageBubble(
                    sms = sms,
                    reaction = reactions[sms.id],
                    readReceipt = readReceipt,
                    onLongPress = {
                        selectedMessage = sms
                        showActions = true
                    },
                    onQuickReact = {
                        viewModel.toggleQuickReaction(sms.id)
                    },
                    onRetryMms = { failedSms ->
                        viewModel.retryMms(failedSms)
                    }
                )

                if (index == messages.lastIndex && hasMore && !isLoadingMore) {
                    viewModel.loadMore()
                }
            }

            if (isLoadingMore) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
        }
    }

    // Contact Info & Actions Bottom Sheet
    if (showContactInfo) {
        ModalBottomSheet(
            onDismissRequest = { showContactInfo = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = contactName,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = address,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                if (displayRelatedAddresses.size > 1 && groupMembers.isNullOrEmpty()) {
                    val selectedSend = preferredSendAddress ?: address
                    Text(
                        text = "Send using",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    displayRelatedAddresses.forEach { number ->
                        val isSelected = number == selectedSend
                        ListItem(
                            headlineContent = {
                                Text(PhoneNumberUtils.formatForDisplay(number))
                            },
                            supportingContent = {
                                if (PhoneNumberUtils.formatForDisplay(number) != number) {
                                    Text(number)
                                }
                            },
                            trailingContent = {
                                if (isSelected) {
                                    Icon(Icons.Default.CheckCircle, null)
                                }
                            },
                            modifier = Modifier.clickable {
                                viewModel.setPreferredSendAddress(number)
                                Toast.makeText(context, "Using $number", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }

                    HorizontalDivider()
                }

                if (!groupMembers.isNullOrEmpty()) {
                    Text(
                        text = "Members: ${groupMembers.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }

                HorizontalDivider()

                // Copy Number
                ListItem(
                    headlineContent = { Text("Copy Number") },
                    leadingContent = { Icon(Icons.Filled.ContentCopy, null) },
                    modifier = Modifier.clickable {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Phone number", effectiveSendAddress))
                        Toast.makeText(context, "Number copied", Toast.LENGTH_SHORT).show()
                        showContactInfo = false
                    }
                )

                HorizontalDivider()

                // Block/Unblock Contact
                ListItem(
                    headlineContent = {
                        Text(if (isBlocked) "Unblock Contact" else "Block Contact")
                    },
                    supportingContent = {
                        Text(
                            if (isBlocked) "Allow messages from this contact"
                            else "Stop receiving messages from this contact"
                        )
                    },
                    leadingContent = {
                        Icon(
                            if (isBlocked) Icons.Default.CheckCircle else Icons.Default.Block,
                            null,
                            tint = if (isBlocked) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.error
                        )
                    },
                    modifier = Modifier.clickable {
                        showContactInfo = false
                        showBlockConfirmDialog = true
                    }
                )

                // Report as Spam
                ListItem(
                    headlineContent = { Text("Report as Spam") },
                    supportingContent = { Text("Move this conversation to spam folder") },
                    leadingContent = {
                        Icon(
                            Icons.Default.Report,
                            null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    modifier = Modifier.clickable {
                        showContactInfo = false
                        showReportSpamDialog = true
                    }
                )

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // Block Confirmation Dialog
    if (showBlockConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showBlockConfirmDialog = false },
            title = { Text(if (isBlocked) "Unblock Contact" else "Block Contact") },
            text = {
                Text(
                    if (isBlocked)
                        "You will start receiving messages from $contactName again."
                    else
                        "You will no longer receive messages from $contactName. They won't be notified that you've blocked them."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            if (isBlocked) {
                                database.blockedContactDao().unblock(address)
                                Toast.makeText(context, "$contactName unblocked", Toast.LENGTH_SHORT).show()
                            } else {
                                database.blockedContactDao().block(
                                    BlockedContact(
                                        phoneNumber = address,
                                        displayName = contactName,
                                        blockSms = true,
                                        blockCalls = true
                                    )
                                )
                                Toast.makeText(context, "$contactName blocked", Toast.LENGTH_SHORT).show()
                            }
                            isBlocked = !isBlocked
                        }
                        showBlockConfirmDialog = false
                    }
                ) {
                    Text(
                        if (isBlocked) "Unblock" else "Block",
                        color = if (isBlocked) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showBlockConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Report Spam Dialog
    if (showReportSpamDialog) {
        AlertDialog(
            onDismissRequest = { showReportSpamDialog = false },
            title = { Text("Report as Spam") },
            text = {
                Text("This conversation will be moved to the spam folder. You can also block this contact to stop receiving future messages.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val syncService = com.phoneintegration.app.desktop.DesktopSyncService(context.applicationContext)
                            // Move all messages from this conversation to spam
                            messages.forEach { msg ->
                                val spamMessage = SpamMessage(
                                    messageId = msg.id,
                                    address = msg.address,
                                    body = msg.body,
                                    date = msg.date,
                                    contactName = contactName,
                                    spamConfidence = 1.0f,
                                    isUserMarked = true
                                )
                                database.spamMessageDao().insert(spamMessage)
                                syncService.syncSpamMessage(spamMessage)
                            }
                            Toast.makeText(context, "Conversation moved to spam", Toast.LENGTH_SHORT).show()
                        }
                        showReportSpamDialog = false
                        onBack()
                    }
                ) {
                    Text("Report Spam", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val syncService = com.phoneintegration.app.desktop.DesktopSyncService(context.applicationContext)
                            // Report spam AND block
                            messages.forEach { msg ->
                                val spamMessage = SpamMessage(
                                    messageId = msg.id,
                                    address = msg.address,
                                    body = msg.body,
                                    date = msg.date,
                                    contactName = contactName,
                                    spamConfidence = 1.0f,
                                    isUserMarked = true
                                )
                                database.spamMessageDao().insert(spamMessage)
                                syncService.syncSpamMessage(spamMessage)
                            }
                            database.blockedContactDao().block(
                                BlockedContact(
                                    phoneNumber = address,
                                    displayName = contactName,
                                    blockSms = true,
                                    blockCalls = true,
                                    reason = "Reported as spam"
                                )
                            )
                            Toast.makeText(context, "Reported as spam and blocked", Toast.LENGTH_SHORT).show()
                        }
                        showReportSpamDialog = false
                        onBack()
                    }
                ) {
                    Text("Report & Block", color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }

    // 📎 Bottom sheet for attachment options
    if (showAttachmentSheet) {
        ModalBottomSheet(onDismissRequest = { showAttachmentSheet = false }) {
            Column(Modifier.padding(16.dp)) {
                Text("Attach Media", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = { galleryPicker.launch("image/*") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Pick from Gallery")
                }

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = {
                        // Check camera permission before launching
                        when {
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED -> {
                                startCameraCapture()
                            }
                            else -> {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Capture Photo")
                }
            }
        }
    }

    // 📸 MMS preview dialog
    if (selectedAttachmentUri != null) {
        AlertDialog(
            onDismissRequest = { selectedAttachmentUri = null },
            title = { Text("Send MMS") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AsyncImage(
                        model = selectedAttachmentUri,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(250.dp)
                            .clip(RoundedCornerShape(16.dp))
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = mmsMessageText,
                        onValueChange = { mmsMessageText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Message (optional)") },
                        maxLines = 3
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val replyPrefix = replyToMessage?.let { buildReplyPrefix(it) }
                    val composedMessage = mergeReplyPrefix(replyPrefix, mmsMessageText).ifBlank { null }
                    Log.d("ConversationDetailScreen", "[LocalSend] Sending MMS to $effectiveSendAddress with attachment: $selectedAttachmentUri, message: ${composedMessage?.length ?: 0} chars")
                    viewModel.sendMms(effectiveSendAddress, selectedAttachmentUri!!, composedMessage)
                    selectedAttachmentUri = null
                    mmsMessageText = ""
                    input = ""
                    replyToMessage = null
                }) {
                    Text("Send")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedAttachmentUri = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete message confirmation dialog
    if (showDeleteMessageDialog && messageToDelete != null) {
        AlertDialog(
            onDismissRequest = {
                showDeleteMessageDialog = false
                messageToDelete = null
            },
            icon = {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Delete Message?") },
            text = {
                Text("This message will be permanently deleted and cannot be recovered.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        val msgId = messageToDelete!!.id
                        showDeleteMessageDialog = false
                        messageToDelete = null
                        viewModel.deleteMessage(msgId) { ok ->
                            Toast.makeText(
                                context,
                                if (ok) "Message deleted" else "Failed to delete",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteMessageDialog = false
                        messageToDelete = null
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // Long-press actions
    if (showActions) {
        selectedMessage?.let { msg ->
            MessageActionsSheet(
                message = msg,
                currentReaction = reactions[msg.id],
                isStarred = starredMessageIds.contains(msg.id),
                onSetReaction = { reaction ->
                    viewModel.setMessageReaction(msg.id, reaction)
                },
                onReply = {
                    replyToMessage = msg
                },
                onForward = {
                    showForwardDialog = true
                },
                onStar = {
                    viewModel.toggleStar(msg)
                    val action = if (starredMessageIds.contains(msg.id)) "unstarred" else "starred"
                    Toast.makeText(context, "Message $action", Toast.LENGTH_SHORT).show()
                },
                onDismiss = { showActions = false },
                onDelete = {
                    messageToDelete = msg
                    showDeleteMessageDialog = true
                    showActions = false
                }
            )
        }
    }

    // Forward message dialog
    if (showForwardDialog) {
        selectedMessage?.let { msg ->
            ForwardMessageDialog(
                message = msg,
                conversations = conversations,
                onForward = { targetAddress, messageBody ->
                    viewModel.forwardMessage(targetAddress, messageBody) { success ->
                        if (!success) {
                            Toast.makeText(context, "Failed to forward message", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onDismiss = {
                    showForwardDialog = false
                    showActions = false
                }
            )
        }
    }

    // Media gallery screen
    if (showMediaGallery) {
        Dialog(
            onDismissRequest = { showMediaGallery = false },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            MediaGalleryScreen(
                contactName = contactName,
                messages = messages,
                onBack = { showMediaGallery = false }
            )
        }
    }
}

private fun buildReplyPreview(message: SmsMessage): ReplyPreview {
    return ReplyPreview(
        sender = replySenderName(message),
        snippet = buildReplySnippet(message)
    )
}

private fun buildReplyPrefix(message: SmsMessage): String {
    val sender = replySenderName(message)
    val snippet = buildReplySnippet(message)
    return "> $sender: $snippet"
}

private fun mergeReplyPrefix(prefix: String?, body: String): String {
    if (prefix.isNullOrBlank()) {
        return body
    }

    val trimmedBody = body.trim()
    return if (trimmedBody.isBlank()) {
        prefix
    } else {
        "$prefix\n$trimmedBody"
    }
}

private fun replySenderName(message: SmsMessage): String {
    return if (message.type == 2) {
        "You"
    } else {
        message.contactName ?: message.address
    }
}

private fun buildReplySnippet(message: SmsMessage): String {
    val body = message.body.trim().replace(Regex("\\s+"), " ")
    if (body.isNotBlank()) {
        return body.take(80)
    }

    val attachment = message.mmsAttachments.firstOrNull()
    if (attachment != null) {
        return when {
            attachment.isImage() -> "Photo"
            attachment.isVideo() -> "Video"
            attachment.isAudio() -> "Audio"
            attachment.isVCard() -> "Contact"
            else -> "Attachment"
        }
    }

    return "Message"
}
