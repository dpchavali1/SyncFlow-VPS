package com.phoneintegration.app.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.*
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.phoneintegration.app.SmsViewModel
import com.phoneintegration.app.data.PreferencesManager
import com.phoneintegration.app.ui.conversations.ConversationListScreen
import com.phoneintegration.app.ui.conversations.NewConversationScreen
import com.phoneintegration.app.ui.conversations.NewMessageComposeScreen
import com.phoneintegration.app.ui.conversations.CreateGroupNameScreen
import com.phoneintegration.app.ui.conversations.ContactInfo
import com.phoneintegration.app.ui.chat.ConversationDetailScreen
import com.phoneintegration.app.ui.stats.MessageStatsScreen
import com.phoneintegration.app.ui.settings.*
import com.phoneintegration.app.ui.conversations.AdConversationScreen
import com.phoneintegration.app.ui.desktop.DesktopIntegrationScreen
import com.phoneintegration.app.ui.ai.AIAssistantScreen
import com.phoneintegration.app.ui.scheduled.ScheduledMessagesScreen
import com.phoneintegration.app.ui.archive.ArchivedConversationsScreen
import com.phoneintegration.app.ui.downloads.SyncFlowDownloadsScreen
import com.phoneintegration.app.ui.conversations.SpamFolderScreen
import com.phoneintegration.app.ui.conversations.BlockedContactsScreen
import com.phoneintegration.app.spam.SpamFilterSettingsScreen
import com.phoneintegration.app.ui.support.SupportChatScreen
import com.phoneintegration.app.ui.filetransfer.FileTransferScreen
import com.phoneintegration.app.share.SharePayload
import com.phoneintegration.app.data.GroupRepository
import com.phoneintegration.app.SmsMessage
import com.phoneintegration.app.SmsRepository
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import android.util.Log
import java.net.URLEncoder
import java.net.URLDecoder
import com.phoneintegration.app.ui.components.PhoneNumberRegistrationDialog
import com.phoneintegration.app.ui.components.isPhoneNumberRegistered


@Composable
fun MainNavigation(
    viewModel: SmsViewModel,
    preferencesManager: PreferencesManager,
    pendingShare: SharePayload?,
    onShareHandled: () -> Unit,
    pendingConversation: com.phoneintegration.app.MainActivity.ConversationLaunch?,
    onConversationHandled: () -> Unit,
    pendingOpenSpam: Boolean = false,
    onSpamHandled: () -> Unit = {}
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val groupRepository = remember { GroupRepository(context) }
    val scope = rememberCoroutineScope()

    var selectedContacts by remember { mutableStateOf<List<ContactInfo>>(emptyList()) }
    var activeShare by remember { mutableStateOf<SharePayload?>(null) }

    // Phone number registration dialog state
    var showPhoneRegistrationDialog by remember { mutableStateOf(false) }

    // Check if phone number is registered on first launch
    LaunchedEffect(Unit) {
        // Small delay to let the UI render first
        kotlinx.coroutines.delay(2000)
        // Check if user is authenticated and phone not registered
        val vpsClient = com.phoneintegration.app.vps.VPSClient.getInstance(context)
        val userId = vpsClient.userId
        if (userId != null && !isPhoneNumberRegistered(context)) {
            showPhoneRegistrationDialog = true
        }
    }

    // Show phone number registration dialog
    if (showPhoneRegistrationDialog) {
        PhoneNumberRegistrationDialog(
            onDismiss = {
                showPhoneRegistrationDialog = false
            },
            onRegistered = {
                showPhoneRegistrationDialog = false
                Toast.makeText(context, "Phone number registered for video calling", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // Handle pending share intent
    LaunchedEffect(pendingShare) {
        if (pendingShare != null) {
            activeShare = pendingShare
            selectedContacts = emptyList()
            navController.navigate("newConversation") {
                launchSingleTop = true
            }
            onShareHandled()
        }
    }

    // Handle pending conversation launch
    LaunchedEffect(pendingConversation) {
        if (pendingConversation != null) {
            val encodedAddress = URLEncoder.encode(pendingConversation.address, "UTF-8")
            val encodedName = URLEncoder.encode(pendingConversation.name, "UTF-8")
            val threadId = pendingConversation.threadId
            navController.navigate("chat/$threadId/$encodedAddress/$encodedName") {
                launchSingleTop = true
            }
            onConversationHandled()
        }
    }

    // Handle pending spam folder open from notification
    LaunchedEffect(pendingOpenSpam) {
        if (pendingOpenSpam) {
            navController.navigate("spam") {
                launchSingleTop = true
            }
            onSpamHandled()
        }
    }

    // Navigation with NO animations - cleanest, fastest experience
    NavHost(
        navController = navController,
        startDestination = "list",
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None }
    ) {

            composable("list") {
                // Collect conversations to check if clicked item is a group
                val conversations by viewModel.conversations.collectAsState()

                ConversationListScreen(
                    viewModel = viewModel,
                    prefsManager = preferencesManager,
                    onOpen = { address: String, name: String ->
                        // Find the conversation to get threadId
                        val conversation = conversations.find {
                            it.address == address
                        }

                        when {
                            address == "syncflow_ads" -> {
                                // Open Ads screen
                                navController.navigate("ads")
                            }
                            conversation?.isGroupConversation == true && conversation.groupId != null -> {
                                // Open saved group chat
                                navController.navigate("groupChat/${conversation.groupId}")
                            }
                            conversation != null -> {
                                // Open chat using threadId (URL encode address and name for safe navigation)
                                val encodedAddress = URLEncoder.encode(address, "UTF-8")
                                val encodedName = URLEncoder.encode(name, "UTF-8")
                                navController.navigate("chat/${conversation.threadId}/$encodedAddress/$encodedName")
                            }
                            else -> {
                                // Fallback: Open normal SMS chat (URL encode address and name)
                                val encodedAddress = URLEncoder.encode(address, "UTF-8")
                                val encodedName = URLEncoder.encode(name, "UTF-8")
                                navController.navigate("chat/0/$encodedAddress/$encodedName")
                            }
                        }
                    },
                    onOpenStats = {
                        navController.navigate("stats")
                    },
                    onOpenSettings = {
                        navController.navigate("settings")
                    },
                    onNewMessage = {
                        navController.navigate("newConversation")
                    },
                    onOpenAI = {
                        navController.navigate("ai")
                    },
                    onOpenScheduled = {
                        navController.navigate("scheduled")
                    },
                    onOpenArchived = {
                        navController.navigate("archived")
                    },
                    onOpenDownloads = {
                        navController.navigate("downloads")
                    },
                    onOpenSpam = {
                        navController.navigate("spam")
                    },
                    onOpenBlocked = {
                        navController.navigate("blocked")
                    }
                )
            }

            composable("chat/{threadId}/{address}/{name}") { backStackEntry ->
                val threadIdStr = backStackEntry.arguments?.getString("threadId") ?: "0"
                val threadId = threadIdStr.toLongOrNull() ?: 0L
                // URL decode the address and name to handle special characters
                val rawAddress = backStackEntry.arguments?.getString("address") ?: ""
                val rawName = backStackEntry.arguments?.getString("name") ?: rawAddress
                val address = try { URLDecoder.decode(rawAddress, "UTF-8") } catch (e: Exception) { rawAddress }
                val name = try { URLDecoder.decode(rawName, "UTF-8") } catch (e: Exception) { rawName }

                ConversationDetailScreen(
                    threadId = threadId,
                    address = address,
                    contactName = name,
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            
            composable("stats") {
                MessageStatsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }

            composable("ai") {
                var allMessages by remember { mutableStateOf<List<SmsMessage>>(emptyList()) }

                LaunchedEffect(Unit) {
                    withContext(Dispatchers.IO) {
                        val repo = SmsRepository(context)
                        val messages = repo.getAllMessages(limit = 500)
                        withContext(Dispatchers.Main) {
                            allMessages = messages
                        }
                    }
                }

                AIAssistantScreen(
                    messages = allMessages,
                    onDismiss = { navController.popBackStack() }
                )
            }
            
            // Settings Navigation
            composable("settings") {
                SettingsScreen(
                    prefsManager = preferencesManager,
                    onBack = { navController.popBackStack() },
                    onNavigateToTheme = { navController.navigate("settings/theme") },
                    onNavigateToNotifications = { navController.navigate("settings/notifications") },
                    onNavigateToAppearance = { navController.navigate("settings/appearance") },
                    onNavigateToPrivacy = { navController.navigate("settings/privacy") },
                    onNavigateToMessages = { navController.navigate("settings/messages") },
                    onNavigateToTemplates = { navController.navigate("settings/templates") },
                    onNavigateToBackup = { navController.navigate("settings/backup") },
                    onNavigateToDesktop = { navController.navigate("settings/desktop") },
                    onNavigateToUsage = { navController.navigate("settings/usage") },
                    onNavigateToSync = { navController.navigate("settings/sync") },
                    onNavigateToSpamFilter = { navController.navigate("settings/spam-filter") },
                    onNavigateToSupport = { navController.navigate("support") },
                    onNavigateToFileTransfer = { navController.navigate("filetransfer") },
                    onNavigateToDeleteAccount = { navController.navigate("settings/delete-account") }
                )
            }
            
            composable("settings/theme") {
                ThemeSettingsScreen(
                    prefsManager = preferencesManager,
                    onBack = { navController.popBackStack() }
                )
            }
            
            composable("settings/notifications") {
                NotificationSettingsScreen(
                    prefsManager = preferencesManager,
                    onBack = { navController.popBackStack() }
                )
            }
            
            composable("settings/appearance") {
                AppearanceSettingsScreen(
                    prefsManager = preferencesManager,
                    onBack = { navController.popBackStack() }
                )
            }
            
            composable("settings/privacy") {
                PrivacySettingsScreen(
                    prefsManager = preferencesManager,
                    onBack = { navController.popBackStack() }
                )
            }
            
            composable("settings/messages") {
                MessageSettingsScreen(
                    prefsManager = preferencesManager,
                    onBack = { navController.popBackStack() }
                )
            }
            
            composable("settings/templates") {
                QuickReplyTemplatesScreen(
                    prefsManager = preferencesManager,
                    onBack = { navController.popBackStack() }
                )
            }
            
            composable("settings/backup") {
                BackupScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable("settings/desktop") {
                DesktopIntegrationScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable("settings/usage") {
                UsageSettingsScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable("settings/sync") {
                SyncSettingsScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable("settings/spam-filter") {
                SpamFilterSettingsScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable("settings/delete-account") {
                DeleteAccountScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            // Support Chat Screen
            composable("support") {
                SupportChatScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            // File Transfer Screen (Android → Mac)
            composable("filetransfer") {
                FileTransferScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            // Scheduled Messages Screen
            composable("scheduled") {
                ScheduledMessagesScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            // Archived Conversations Screen
            composable("archived") {
                val conversations by viewModel.conversations.collectAsState()

                ArchivedConversationsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenConversation = { conversation ->
                        // Navigate to the conversation (URL encode address and name)
                        val encodedAddress = URLEncoder.encode(conversation.address, "UTF-8")
                        val encodedName = URLEncoder.encode(conversation.contactName ?: conversation.address, "UTF-8")
                        navController.navigate("chat/${conversation.threadId}/$encodedAddress/$encodedName")
                    }
                )
            }

            // SyncFlow Downloads Screen
            composable("downloads") {
                SyncFlowDownloadsScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            // Spam Folder Screen
            composable("spam") {
                SpamFolderScreen(
                    onBack = { navController.popBackStack() },
                    viewModel = viewModel
                )
            }

            // Blocked Contacts Screen
            composable("blocked") {
                BlockedContactsScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            // Ads Conversation Screen
            composable("ads") {
                AdConversationScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            // New Conversation - Contact Picker
            composable("newConversation") {
                NewConversationScreen(
                    initialNumber = activeShare?.recipient,
                    onBack = {
                        if (activeShare != null) {
                            activeShare = null
                            onShareHandled()
                        }
                        navController.popBackStack()
                    },
                    onContactsSelected = { contacts ->
                        selectedContacts = contacts
                        navController.navigate("newMessageCompose")
                    },
                    onCreateGroup = { contacts ->
                        selectedContacts = contacts
                        navController.navigate("createGroupName")
                    }
                )
            }

            // Create Group - Name Input
            composable("createGroupName") {
                if (selectedContacts.isNotEmpty()) {
                    CreateGroupNameScreen(
                        selectedContacts = selectedContacts,
                        onBack = { navController.popBackStack() },
                        onCreateGroup = { groupName ->
                            // Create the group in database
                            scope.launch {
                                try {
                                    val groupId = withContext(Dispatchers.IO) {
                                        groupRepository.createGroup(
                                            name = groupName,
                                            members = selectedContacts
                                        )
                                    }

                                    // Navigate to group chat (on Main thread)
                                    navController.navigate("groupChat/$groupId") {
                                        popUpTo("list") { inclusive = false }
                                    }
                                } catch (e: Exception) {
                                    // Show toast on Main thread
                                    Toast.makeText(
                                        context,
                                        "Failed to create group: ${e.message}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    )
                }
            }

            // New Message Compose
            composable("newMessageCompose") {
                if (selectedContacts.isNotEmpty()) {
                    NewMessageComposeScreen(
                        contacts = selectedContacts,
                        viewModel = viewModel,
                        onBack = {
                            if (activeShare != null) {
                                activeShare = null
                                onShareHandled()
                            }
                            navController.popBackStack()
                        },
                        onMessageSent = {
                            // Go back to conversation list
                            if (activeShare != null) {
                                activeShare = null
                                onShareHandled()
                            }
                            navController.popBackStack("list", inclusive = false)
                        },
                        initialMessage = activeShare?.text,
                        initialAttachmentUris = activeShare?.uris ?: emptyList()
                    )
                }
            }

            // Group Chat
            composable("groupChat/{groupId}") { backStackEntry ->
                val groupId = backStackEntry.arguments?.getString("groupId")?.toLongOrNull() ?: 0L

                var groupWithMembers by remember { mutableStateOf<com.phoneintegration.app.data.database.GroupWithMembers?>(null) }

                LaunchedEffect(groupId) {
                    Log.d("MainNavigation", "=== LOADING GROUP CHAT ===")
                    Log.d("MainNavigation", "Group ID: $groupId")
                    groupWithMembers = groupRepository.getGroupWithMembers(groupId)
                    Log.d("MainNavigation", "Group loaded: ${groupWithMembers?.group?.name}")
                    Log.d("MainNavigation", "Group thread ID: ${groupWithMembers?.group?.threadId}")
                    Log.d("MainNavigation", "Group members: ${groupWithMembers?.members?.size}")
                }

                groupWithMembers?.let { group ->
                    val contacts = group.members.map {
                        ContactInfo(
                            name = it.contactName ?: it.phoneNumber,
                            phoneNumber = it.phoneNumber
                        )
                    }

                    // If group has a thread ID, it means messages have been sent
                    // Use ConversationDetailScreen to show message history
                    if (group.group.threadId != null) {
                        // Load by thread ID directly
                        val memberNames = contacts.map { it.name }
                        ConversationDetailScreen(
                            address = group.group.name,
                            contactName = group.group.name,
                            viewModel = viewModel,
                            onBack = {
                                navController.popBackStack("list", inclusive = false)
                            },
                            threadId = group.group.threadId,
                            groupMembers = memberNames,
                            groupId = groupId,
                            onDeleteGroup = { id ->
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        groupRepository.deleteGroup(id)
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "Group deleted", Toast.LENGTH_SHORT).show()
                                            navController.popBackStack("list", inclusive = false)
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(
                                                context,
                                                "Failed to delete group: ${e.message}",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                            }
                        )
                    } else {
                        // New group without messages - use compose screen
                        NewMessageComposeScreen(
                            contacts = contacts,
                            viewModel = viewModel,
                            onBack = {
                                if (activeShare != null) {
                                    activeShare = null
                                    onShareHandled()
                                }
                                navController.popBackStack("list", inclusive = false)
                            },
                            onMessageSent = {
                                // Update group's last message timestamp
                                scope.launch(Dispatchers.IO) {
                                    groupRepository.updateLastMessage(groupId)
                                }
                                if (activeShare != null) {
                                    activeShare = null
                                    onShareHandled()
                                }
                                navController.popBackStack("list", inclusive = false)
                            },
                            groupName = group.group.name,
                            groupId = groupId,
                            initialMessage = activeShare?.text,
                            initialAttachmentUris = activeShare?.uris ?: emptyList()
                        )
                    }
                }
            }
        }
    }
