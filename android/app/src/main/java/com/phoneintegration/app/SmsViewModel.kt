package com.phoneintegration.app

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.delay
import android.util.Log
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Job
import com.phoneintegration.app.deals.DealsRepository
import com.phoneintegration.app.data.GroupRepository
import com.phoneintegration.app.desktop.DesktopSyncService
import com.phoneintegration.app.utils.MessageDeduplicator
import com.phoneintegration.app.data.database.SyncFlowDatabase
import com.phoneintegration.app.data.database.PinnedConversation
import com.phoneintegration.app.data.database.MutedConversation
import com.phoneintegration.app.data.database.StarredMessage
import com.phoneintegration.app.data.database.BlockedContact
import com.phoneintegration.app.data.database.NotificationSettings
import com.phoneintegration.app.data.database.CachedConversation
import com.phoneintegration.app.data.database.SpamMessage
import com.phoneintegration.app.utils.MemoryOptimizer
import com.phoneintegration.app.ai.AIService
import com.phoneintegration.app.data.PreferencesManager
import com.phoneintegration.app.realtime.ReadReceiptManager
import com.phoneintegration.app.realtime.ReadReceipt
import com.phoneintegration.app.realtime.ReadReceiptPayload

class SmsViewModel(app: Application) : AndroidViewModel(app) {

    private val aiService = AIService(app)

    private var currentThreadId: Long? = null
    private var currentRelatedThreadIds: List<Long> = emptyList()

    private val repo = SmsRepository(app.applicationContext)
    private val groupRepository = GroupRepository(app.applicationContext)
    private val syncService = DesktopSyncService(app.applicationContext)
    private val memoryOptimizer = MemoryOptimizer.getInstance(app.applicationContext)
    private val preferencesManager = PreferencesManager(app.applicationContext)
    private val readReceiptManager = ReadReceiptManager(app.applicationContext)
    private val database = SyncFlowDatabase.getInstance(app.applicationContext)
    private val pinnedDao = database.pinnedConversationDao()
    private val mutedDao = database.mutedConversationDao()
    private val starredDao = database.starredMessageDao()
    private val blockedDao = database.blockedContactDao()
    private val notificationSettingsDao = database.notificationSettingsDao()
    private val cachedConversationDao = database.cachedConversationDao()
    private val spamMessageDao = database.spamMessageDao()
    private val mmsOverrides = mutableMapOf<Long, SmsMessage>()
    private val mmsCache = MmsAttachmentCache(app.applicationContext)
    private var currentConversationKey: String? = null

    private val _relatedAddresses = MutableStateFlow<List<String>>(emptyList())
    val relatedAddresses: StateFlow<List<String>> = _relatedAddresses.asStateFlow()

    private val _preferredSendAddress = MutableStateFlow<String?>(null)
    val preferredSendAddress: StateFlow<String?> = _preferredSendAddress.asStateFlow()

    private val _spamAddresses = MutableStateFlow<Set<String>>(emptySet())
    val spamAddresses: StateFlow<Set<String>> = _spamAddresses.asStateFlow()

    // Memory cache for instant loading
    private var cachedConversations: List<ConversationInfo>? = null
    private var lastLoadTime: Long = 0
    private val CACHE_VALIDITY_MS = 100L // Cache valid for 100ms only - always load fresh data

    /**
     * Invalidate the conversation list cache to force fresh data on next load
     */
    fun invalidateConversationCache() {
        lastLoadTime = 0
        Log.d("SmsViewModel", "Conversation cache invalidated")
    }

    // Track if initial load is done
    private var initialLoadComplete = false

    // Track if persistent cache was loaded
    private var persistentCacheLoaded = false

    // Debounce mechanism for ContentObserver
    private var debounceJob: Job? = null

    // Flag to suppress ContentObserver during send operations
    private var suppressObserverUntil: Long = 0

    private fun conversationKey(address: String): String {
        val normalized = PhoneNumberUtils.normalizeForConversation(address)
        return if (normalized.isNotBlank()) normalized else "addr_${address.hashCode()}"
    }

    private suspend fun resolveThreadIdForAddress(address: String): Long? {
        return withContext(Dispatchers.IO) {
            try {
                Telephony.Threads.getOrCreateThreadId(getApplication(), setOf(address))
            } catch (e: Exception) {
                Log.e("SmsViewModel", "Failed to resolve thread ID for $address", e)
                null
            }
        }
    }

    private fun ensureRelatedThreadId(threadId: Long?) {
        if (threadId == null) return
        if (!currentRelatedThreadIds.contains(threadId)) {
            currentRelatedThreadIds = currentRelatedThreadIds + threadId
        }
    }

    private fun ensureRelatedAddress(address: String) {
        val current = _relatedAddresses.value
        if (!current.contains(address)) {
            _relatedAddresses.value = current + address
        }
    }

    private fun updateConversationContext(primaryAddress: String, related: List<String>) {
        val key = conversationKey(primaryAddress)
        currentConversationKey = key
        val uniqueAddresses = if (related.isNotEmpty()) related.distinct() else listOf(primaryAddress)
        _relatedAddresses.value = uniqueAddresses

        val preferred = preferencesManager.getPreferredSendAddress(key)
        _preferredSendAddress.value = if (preferred != null && uniqueAddresses.contains(preferred)) {
            preferred
        } else {
            null
        }
    }

    fun setPreferredSendAddress(address: String?) {
        val key = currentConversationKey ?: return
        preferencesManager.setPreferredSendAddress(key, address)
        _preferredSendAddress.value = address
    }
    private val DEBOUNCE_DELAY_MS = 500L // Wait 500ms after last change before reloading
    private var pendingChanges = 0

    // ContentObserver with debouncing to reduce CPU usage
    private val smsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            // Check if we should suppress this callback (during send operations)
            if (System.currentTimeMillis() < suppressObserverUntil) {
                Log.d("SmsViewModel", "SMS database changed - SUPPRESSED (send in progress)")
                return
            }

            pendingChanges++
            Log.d("SmsViewModel", "SMS database changed (pending: $pendingChanges)")

            // Cancel any existing debounce job
            debounceJob?.cancel()

            // Start new debounce timer
            debounceJob = viewModelScope.launch {
                delay(DEBOUNCE_DELAY_MS)

                // Check again after delay in case send started during debounce
                if (System.currentTimeMillis() < suppressObserverUntil) {
                    Log.d("SmsViewModel", "Observer debounce completed - SUPPRESSED (send in progress)")
                    pendingChanges = 0
                    return@launch
                }

                // Only process if we still have pending changes
                if (pendingChanges > 0) {
                    Log.d("SmsViewModel", "Processing $pendingChanges batched changes")
                    pendingChanges = 0

                    // Reload conversation list
                    loadConversations()

                    // Reload current conversation if viewing one
                    currentThreadId?.let { threadId ->
                        Log.d("SmsViewModel", "Reloading current conversation thread: $threadId (related: ${currentRelatedThreadIds.size} threads)")
                        val firstPage = if (currentRelatedThreadIds.size > 1) {
                            repo.getMessagesByThreadIds(currentRelatedThreadIds, pageSize, 0)
                        } else {
                            repo.getMessagesByThreadId(threadId, pageSize, 0)
                        }

                        // Preserve any temp messages (negative IDs) that aren't in the provider yet
                        val currentMessages = _conversationMessages.value
                        val tempMessages = currentMessages.filter { it.id < 0 }

                        val combined = if (tempMessages.isNotEmpty()) {
                            Log.d("SmsViewModel", "Preserving ${tempMessages.size} temp messages during reload")
                            tempMessages + firstPage
                        } else {
                            firstPage
                        }

                        // Deduplicate messages to prevent duplicates from rapid updates
                        _conversationMessages.value = MessageDeduplicator.deduplicateMessages(combined)
                        Log.d("SmsViewModel", "Reloaded ${firstPage.size} messages for thread $threadId (total: ${_conversationMessages.value.size})")
                    }
                }
            }
        }
    }

    init {
        // INSTANT: Load from persistent cache FIRST (before anything else)
        viewModelScope.launch(Dispatchers.IO) {
            loadFromPersistentCacheInstantly()
        }

        // Register ContentObserver to watch for SMS/MMS changes
        app.contentResolver.registerContentObserver(
            Telephony.Sms.CONTENT_URI,
            true,
            smsObserver
        )
        app.contentResolver.registerContentObserver(
            Uri.parse("content://mms"),
            true,
            smsObserver
        )
        Log.d("SmsViewModel", "ContentObserver registered for SMS/MMS changes (debounced)")

        viewModelScope.launch {
            try {
                syncService.listenForMessageReactions().collect { reactions ->
                    _messageReactions.value = reactions
                }
            } catch (e: Exception) {
                Log.e("SmsViewModel", "Error listening for reactions", e)
            }
        }

        // Temporarily commented out read receipts functionality
        /*
        viewModelScope.launch {
            try {
                readReceiptManager.observeReadReceipts().collect { receipts ->
                    _readReceipts.value = receipts
                }
            } catch (e: Exception) {
                Log.e("SmsViewModel", "Error listening for read receipts", e)
            }
        }
        */

        viewModelScope.launch(Dispatchers.IO) {
            spamMessageDao.getSpamAddressesFlow().collect { addresses ->
                val normalized = addresses.mapNotNull { address ->
                    val normalizedAddress = PhoneNumberUtils.normalizeForConversation(address)
                    if (normalizedAddress.isNotBlank()) normalizedAddress else null
                }.toSet()
                _spamAddresses.value = normalized
            }
        }

        // Initial one-time reconciliation on app start
        viewModelScope.launch(Dispatchers.IO) {
            reconcileSpamWithCloud()
        }
        // Note: Real-time spam sync is started on-demand via startSpamSync()
        // when user opens spam folder, to save battery
    }

    /**
     * Real-time listener for spam updates from other devices (Mac/Web).
     * When a user marks a message as spam on any device, all devices sync.
     */
    private suspend fun listenForRemoteSpamUpdates() {
        try {
            syncService.listenForSpamMessages().collect { remoteSpam ->
                Log.d("SmsViewModel", "Received ${remoteSpam.size} spam messages from Firebase")

                if (remoteSpam.isEmpty()) return@collect

                // Get current local spam
                val localSpam = spamMessageDao.getSpamMessages()
                val localIds = localSpam.map { it.messageId }.toSet()
                val remoteIds = remoteSpam.map { it.messageId }.toSet()

                // Insert new spam from remote (Mac/Web added these)
                val newFromRemote = remoteSpam.filter { it.messageId !in localIds }
                if (newFromRemote.isNotEmpty()) {
                    Log.d("SmsViewModel", "Adding ${newFromRemote.size} new spam messages from remote")
                    spamMessageDao.insertAll(newFromRemote)
                    NotificationHelper(getApplication()).showSpamMovedSummaryNotification(newFromRemote)
                }

                // Update existing spam (e.g., isRead status changed)
                val existingUpdated = remoteSpam.filter { remote ->
                    val local = localSpam.find { it.messageId == remote.messageId }
                    local != null && (local.isRead != remote.isRead || local.isUserMarked != remote.isUserMarked)
                }
                if (existingUpdated.isNotEmpty()) {
                    Log.d("SmsViewModel", "Updating ${existingUpdated.size} existing spam messages")
                    spamMessageDao.insertAll(existingUpdated)
                }

                // Delete spam that was removed from remote (another device unspammed it)
                val deletedFromRemote = localSpam.filter { it.messageId !in remoteIds }
                if (deletedFromRemote.isNotEmpty()) {
                    Log.d("SmsViewModel", "Removing ${deletedFromRemote.size} spam messages deleted from remote")
                    deletedFromRemote.forEach { spamMessageDao.delete(it.messageId) }
                }
            }
        } catch (e: Exception) {
            Log.e("SmsViewModel", "Error listening for remote spam updates", e)
        }
    }

    /**
     * Refresh spam from cloud. Call when user opens spam folder.
     * This fetches latest spam from Firebase without keeping a persistent connection.
     */
    fun refreshSpamFromCloud() {
        viewModelScope.launch(Dispatchers.IO) {
            reconcileSpamWithCloud()
        }
    }

    private suspend fun reconcileSpamWithCloud() {
        // Skip cloud sync if no devices are paired (saves battery for Android-only users)
        if (!DesktopSyncService.hasPairedDevices(getApplication())) {
            Log.d("SmsViewModel", "Skipping spam cloud sync - no paired devices")
            return
        }

        try {
            // First, sync ALL local spam to cloud (ensures nothing is lost)
            val localSpam = spamMessageDao.getSpamMessages()
            if (localSpam.isNotEmpty()) {
                syncService.syncSpamMessages(localSpam)
            }

            // Then fetch from cloud and merge any we don't have locally
            val remoteSpam = syncService.fetchSpamMessages()
            val localIds = localSpam.map { it.messageId }.toSet()

            val missingLocal = remoteSpam.filter { it.messageId !in localIds }
            if (missingLocal.isNotEmpty()) {
                spamMessageDao.insertAll(missingLocal)
                NotificationHelper(getApplication()).showSpamMovedSummaryNotification(missingLocal)
            }
        } catch (e: Exception) {
            Log.e("SmsViewModel", "Failed to reconcile spam with cloud", e)
        }
    }

    /**
     * Load conversations from persistent Room cache INSTANTLY on app startup.
     * This ensures conversations appear immediately without any loading state.
     */
    private suspend fun loadFromPersistentCacheInstantly() {
        val startTime = System.currentTimeMillis()
        try {
            val cached = cachedConversationDao.getAll()
            if (cached.isNotEmpty()) {
                Log.d("SmsViewModel", "INSTANT: Loaded ${cached.size} conversations from persistent cache in ${System.currentTimeMillis() - startTime}ms")

                // Ads conversation (always at top)
                val adsConversation = ConversationInfo(
                    threadId = -1L,
                    address = "syncflow_ads",
                    contactName = "SyncFlow Deals",
                    lastMessage = "Tap here to explore today's best offers!",
                    timestamp = System.currentTimeMillis(),
                    unreadCount = 0,
                    photoUri = null,
                    isAdConversation = true
                )

                val conversationList = cached.map { it.toConversationInfo() }
                cachedConversations = conversationList
                // Mark cache as expired so it triggers a refresh on first loadConversations() call
                lastLoadTime = 0L
                persistentCacheLoaded = true

                withContext(Dispatchers.Main) {
                    _conversations.value = listOf(adsConversation) + conversationList
                    _isLoading.value = false // Never show loading
                }
                Log.d("SmsViewModel", "INSTANT: UI updated with cached data (marked as expired for refresh)")
            } else {
                Log.d("SmsViewModel", "INSTANT: No persistent cache found, will load fresh")
            }
        } catch (e: Exception) {
            Log.e("SmsViewModel", "Error loading persistent cache", e)
        }
    }

    /**
     * Save conversations to persistent cache for instant startup next time.
     */
    private suspend fun saveToPersistentCache(conversations: List<ConversationInfo>) {
        try {
            val cached = conversations
                .filter { !it.isAdConversation }
                .map { it.toCachedConversation() }
            cachedConversationDao.replaceAll(cached)
            Log.d("SmsViewModel", "Saved ${cached.size} conversations to persistent cache")
        } catch (e: Exception) {
            Log.e("SmsViewModel", "Error saving to persistent cache", e)
        }
    }

    // Extension to convert CachedConversation to ConversationInfo
    private fun CachedConversation.toConversationInfo(): ConversationInfo {
        return ConversationInfo(
            threadId = threadId,
            address = address,
            contactName = contactName,
            lastMessage = lastMessage,
            timestamp = timestamp,
            unreadCount = unreadCount,
            photoUri = photoUri,
            isPinned = isPinned,
            isMuted = isMuted,
            isGroupConversation = isGroupConversation,
            recipientCount = recipientCount,
            groupId = groupId,
            isAdConversation = false
        )
    }

    // Extension to convert ConversationInfo to CachedConversation
    private fun ConversationInfo.toCachedConversation(): CachedConversation {
        return CachedConversation(
            threadId = threadId,
            address = address,
            contactName = contactName,
            lastMessage = lastMessage,
            timestamp = timestamp,
            unreadCount = unreadCount,
            photoUri = photoUri,
            isPinned = isPinned,
            isMuted = isMuted,
            isGroupConversation = isGroupConversation,
            recipientCount = recipientCount,
            groupId = groupId
        )
    }

    override fun onCleared() {
        super.onCleared()
        // Unregister ContentObserver
        getApplication<Application>().contentResolver.unregisterContentObserver(smsObserver)
        Log.d("SmsViewModel", "ContentObserver unregistered")
    }

    // Tracks whether SyncFlow is the default SMS app
    private val _isDefaultSmsApp = MutableStateFlow(false)
    val isDefaultSmsApp = _isDefaultSmsApp.asStateFlow()
    /**
     * Called when the system default SMS role changes.
     * - Called from MainActivity.onResume()
     * - Called after user accepts the RoleManager popup
     */
    fun onDefaultSmsAppChanged(isDefault: Boolean) {
        _isDefaultSmsApp.value = isDefault

        if (isDefault) {
            // Reload everything because now we have full SMS/MMS access
            loadConversations()
        }
    }

    private val _conversations = MutableStateFlow<List<ConversationInfo>>(emptyList())
    val conversations = _conversations.asStateFlow()

    private val _conversationMessages = MutableStateFlow<List<SmsMessage>>(emptyList())
    val conversationMessages = _conversationMessages.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore = _isLoadingMore.asStateFlow()

    private val _hasMore = MutableStateFlow(true)
    val hasMore = _hasMore.asStateFlow()

    private val _smartReplies = MutableStateFlow<List<String>>(emptyList())
    val smartReplies = _smartReplies.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _messageReactions = MutableStateFlow<Map<Long, String>>(emptyMap())
    val messageReactions = _messageReactions.asStateFlow()

    // private val _readReceipts = MutableStateFlow<Map<String, ReadReceipt>>(emptyMap())
    // val readReceipts = _readReceipts.asStateFlow()

    // SIM filtering support
    private val _selectedSimFilter = MutableStateFlow<Int?>(null)
    val selectedSimFilter = _selectedSimFilter.asStateFlow()

    private val _availableSims = MutableStateFlow<List<SimManager.SimInfo>>(emptyList())
    val availableSims = _availableSims.asStateFlow()

    // Search support
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SmsMessage>>(emptyList())
    val searchResults = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching = _isSearching.asStateFlow()

    private val pageSize = 50
    private var offset = 0
    private var currentAddress = ""

    private fun resolveNameForMessages(address: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolved = repo.resolveContactName(address)
            if (resolved != null) {
                withContext(Dispatchers.Main) {
                    _conversationMessages.value =
                        _conversationMessages.value.map { msg ->
                            msg.copy(contactName = resolved)
                        }
                }
            }
        }
    }

    fun getMessageKey(message: SmsMessage): String {
        return if (message.isMms) {
            "mms_${message.id}"
        } else {
            message.id.toString()
        }
    }

    fun markConversationMessagesRead(conversationAddress: String, messages: List<SmsMessage>) {
        if (messages.isEmpty()) return

        val normalizedAddress = PhoneNumberUtils.normalizeForConversation(conversationAddress)
        // val existingReceipts = _readReceipts.value // Temporarily commented out

        val payloads = messages.asSequence()
            .filter { it.type == 1 && it.id > 0 }
            // Temporarily commented out for compilation
            /*
            .map { message ->
                ReadReceiptPayload(
                    messageKey = getMessageKey(message),
                    sourceId = message.id,
                    sourceType = if (message.isMms) "mms" else "sms"
                )
            }
            .filter { payload -> !existingReceipts.containsKey(payload.messageKey) }
            .distinctBy { it.messageKey }
            */
            .toList()

        if (payloads.isEmpty()) return

        // Temporarily commented out read receipts functionality
        /*
        viewModelScope.launch(Dispatchers.IO) {
            readReceiptManager.markMultipleAsRead(payloads, normalizedAddress)
        }
        */
    }

    fun markThreadRead(threadId: Long) {
        if (threadId <= 0) return

        val relatedThreadIds = if (currentRelatedThreadIds.isNotEmpty()) {
            currentRelatedThreadIds
        } else {
            listOf(threadId)
        }

        // Immediately update local state for instant UI feedback
        _conversations.value = _conversations.value.map { conv ->
            if (conv.threadId == threadId || conv.relatedThreadIds.any { relatedThreadIds.contains(it) }) {
                conv.copy(unreadCount = 0)
            } else {
                conv
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            relatedThreadIds.forEach { id ->
                repo.markThreadRead(id)
            }
            // Also reload to ensure consistency with system data
            withContext(Dispatchers.Main) {
                loadConversations(forceReload = true)
            }
        }
    }

    // ---------------------------------------------------------
    // SIM FILTER MANAGEMENT
    // ---------------------------------------------------------
    fun loadAvailableSims() {
        viewModelScope.launch(Dispatchers.IO) {
            val simManager = SimManager(getApplication())
            val sims = simManager.getActiveSims()
            _availableSims.value = sims
            Log.d("SmsViewModel", "Loaded ${sims.size} active SIM(s)")
            try {
                simManager.syncSimsToVps(syncService)
            } catch (e: Exception) {
                Log.e("SmsViewModel", "Failed to sync SIMs to VPS", e)
            }
        }
    }

    fun setSimFilter(subId: Int?) {
        _selectedSimFilter.value = subId
        loadConversations()  // Reload with new filter
        Log.d("SmsViewModel", "SIM filter set to: ${subId ?: "All SIMs"}")
    }

    // ---------------------------------------------------------
    // SEARCH FUNCTIONS
    // ---------------------------------------------------------

    /**
     * Search all messages by query
     */
    fun searchMessages(query: String) {
        _searchQuery.value = query

        if (query.isBlank() || query.length < 2) {
            _searchResults.value = emptyList()
            _isSearching.value = false
            return
        }

        viewModelScope.launch {
            _isSearching.value = true
            try {
                val results = repo.searchMessages(query)
                _searchResults.value = results
                Log.d("SmsViewModel", "Search found ${results.size} messages for '$query'")
            } catch (e: Exception) {
                Log.e("SmsViewModel", "Search error", e)
                _searchResults.value = emptyList()
            } finally {
                _isSearching.value = false
            }
        }
    }

    /**
     * Search within a specific conversation
     */
    fun searchInCurrentConversation(query: String) {
        val threadId = currentThreadId ?: return

        if (query.isBlank() || query.length < 2) {
            _searchResults.value = emptyList()
            return
        }

        viewModelScope.launch {
            _isSearching.value = true
            try {
                val results = repo.searchInConversation(threadId, query)
                _searchResults.value = results
            } catch (e: Exception) {
                Log.e("SmsViewModel", "Search in conversation error", e)
            } finally {
                _isSearching.value = false
            }
        }
    }

    /**
     * Filter conversations by query (name, number, or last message)
     */
    fun filterConversations(query: String): List<ConversationInfo> {
        if (query.isBlank()) return _conversations.value

        val lowerQuery = query.lowercase().trim()
        return _conversations.value.filter { conv ->
            conv.contactName?.lowercase()?.contains(lowerQuery) == true ||
            conv.address.contains(lowerQuery) ||
            conv.lastMessage.lowercase().contains(lowerQuery)
        }
    }

    /**
     * Get messages with links/URLs
     */
    fun getMessagesWithLinks() {
        viewModelScope.launch {
            _isSearching.value = true
            try {
                val results = repo.getMessagesWithLinks()
                _searchResults.value = results
            } catch (e: Exception) {
                Log.e("SmsViewModel", "Get links error", e)
            } finally {
                _isSearching.value = false
            }
        }
    }

    /**
     * Clear search results
     */
    fun clearSearch() {
        _searchQuery.value = ""
        _searchResults.value = emptyList()
        _isSearching.value = false
    }

    fun setMessageReaction(messageId: Long, reaction: String?) {
        val current = _messageReactions.value.toMutableMap()
        if (reaction.isNullOrBlank()) {
            current.remove(messageId)
        } else {
            current[messageId] = reaction
        }
        _messageReactions.value = current

        viewModelScope.launch {
            try {
                syncService.setMessageReaction(messageId, reaction)
            } catch (e: Exception) {
                Log.e("SmsViewModel", "Error setting reaction", e)
            }
        }
    }

    fun toggleQuickReaction(messageId: Long) {
        val current = _messageReactions.value[messageId]
        if (current == "👍") {
            setMessageReaction(messageId, null)
        } else {
            setMessageReaction(messageId, "👍")
        }
    }

    // ---------------------------------------------------------
    // LOAD CONVERSATIONS (BLAZING FAST - NO LOADING SPINNER EVER)
    // ---------------------------------------------------------
    fun loadConversations(forceReload: Boolean = false) {
        viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            Log.d("SmsViewModel", "=== loadConversations() started ===")

            // NEVER show loading spinner - we always have cached data or show empty
            _isLoading.value = false

            // Ads conversation (always at top)
            val adsConversation = ConversationInfo(
                threadId = -1L,
                address = "syncflow_ads",
                contactName = "SyncFlow Deals",
                lastMessage = "Tap here to explore today's best offers!",
                timestamp = System.currentTimeMillis(),
                unreadCount = 0,
                photoUri = null,
                isAdConversation = true
            )

            // INSTANT: Show cached data if available AND fresh
            val cached = cachedConversations
            val cacheAge = System.currentTimeMillis() - lastLoadTime
            if (!forceReload && cached != null && cached.isNotEmpty() && cacheAge < CACHE_VALIDITY_MS) {
                Log.d("SmsViewModel", "Using cached conversations (${cached.size} items, ${cacheAge}ms old)")
                _conversations.value = listOf(adsConversation) + cached

                // Refresh in background to get latest data
                refreshConversationsInBackground(adsConversation)
                return@launch
            }

            // Cache is stale or missing - log and load fresh
            if (cached != null && cacheAge >= CACHE_VALIDITY_MS) {
                Log.d("SmsViewModel", "Cache expired (${cacheAge}ms > ${CACHE_VALIDITY_MS}ms), loading fresh data")
            }

            // No cache yet - still don't show loading, just load data

            // FAST: Preload all contacts FIRST (single batch query)
            withContext(Dispatchers.IO) {
                repo.preloadContactCache()
            }

            // FAST: Fetch conversations on IO thread (contacts already cached)
            val smsList = withContext(Dispatchers.IO) {
                try {
                    val conversations = repo.getConversations()

                    // Load pinned and muted thread IDs (safely)
                    val pinnedIds = try {
                        pinnedDao.getPinnedIds().toSet()
                    } catch (e: Exception) {
                        Log.e("SmsViewModel", "Error loading pinned IDs", e)
                        emptySet<Long>()
                    }

                    val mutedIds = try {
                        mutedDao.getMutedIds().toSet()
                    } catch (e: Exception) {
                        Log.e("SmsViewModel", "Error loading muted IDs", e)
                        emptySet<Long>()
                    }

                    // Mark pinned/muted conversations and sort (pinned first, then by timestamp)
                    conversations.map { conv ->
                        try {
                            conv.copy(
                                isPinned = conv.threadId in pinnedIds,
                                isMuted = conv.threadId in mutedIds
                            )
                        } catch (e: Exception) {
                            Log.e("SmsViewModel", "Error processing conversation ${conv.threadId}", e)
                            conv // Return original if copy fails
                        }
                    }.sortedWith(
                        compareByDescending<ConversationInfo> { it.isPinned }
                            .thenByDescending { it.timestamp }
                    )
                } catch (e: Exception) {
                    Log.e("SmsViewModel", "Error loading conversations", e)
                    emptyList<ConversationInfo>()
                }
            }

            Log.d("SmsViewModel", "Loaded ${smsList.size} conversations in ${System.currentTimeMillis() - startTime}ms")

            // INSTANT: Update UI immediately (safely)
            try {
                cachedConversations = smsList
                lastLoadTime = System.currentTimeMillis()
                _conversations.value = listOf(adsConversation) + smsList
                _isLoading.value = false
                initialLoadComplete = true
            } catch (e: Exception) {
                Log.e("SmsViewModel", "Error updating UI with conversations", e)
                _isLoading.value = false
            }

            Log.d("SmsViewModel", "UI updated in ${System.currentTimeMillis() - startTime}ms")

            // Save to persistent cache for instant startup next time
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    saveToPersistentCache(smsList)
                } catch (e: Exception) {
                    Log.e("SmsViewModel", "Error saving to persistent cache", e)
                }
            }

            // DEFERRED: Everything below runs in background without blocking UI
            // Delay to ensure UI is fully rendered first
            delay(100)

            // Background: Resolve any remaining contact names not in preload cache
            // (This is now just a fallback - most names are already resolved)
            viewModelScope.launch(Dispatchers.IO) {
                resolveContactNamesBatch(smsList)
            }

            // Background: Resolve contact photos (low priority, delayed)
            viewModelScope.launch(Dispatchers.IO) {
                delay(300) // Names are already loaded, photos can start sooner
                resolveContactPhotos(smsList)
            }

            // Background: Load groups (lowest priority, delayed)
            viewModelScope.launch(Dispatchers.IO) {
                delay(1000) // Wait 1 second before loading groups
                loadGroupsInBackground(smsList, adsConversation)
            }

            // Background: Sync MMS to Firebase (very low priority, delayed)
            viewModelScope.launch(Dispatchers.IO) {
                delay(5000) // Wait 5 seconds before starting MMS sync
                syncMmsInBackground(smsList.take(5)) // Reduce to 5 for speed
            }
        }
    }

    // Refresh conversations in background without blocking
    private fun refreshConversationsInBackground(adsConversation: ConversationInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val conversations = repo.getConversations()

                // Load pinned and muted thread IDs (same as loadConversations)
                val pinnedIds = pinnedDao.getPinnedIds().toSet()
                val mutedIds = mutedDao.getMutedIds().toSet()

                // Mark pinned/muted conversations and sort (pinned first, then by timestamp)
                val smsList = conversations.map { conv ->
                    conv.copy(
                        isPinned = conv.threadId in pinnedIds,
                        isMuted = conv.threadId in mutedIds
                    )
                }.sortedWith(
                    compareByDescending<ConversationInfo> { it.isPinned }
                        .thenByDescending { it.timestamp }
                )

                cachedConversations = smsList
                lastLoadTime = System.currentTimeMillis()

                withContext(Dispatchers.Main) {
                    _conversations.value = listOf(adsConversation) + smsList
                }

                // Save to persistent cache
                saveToPersistentCache(smsList)

                // Also resolve names in background
                resolveContactNamesBatch(smsList)
            } catch (e: Exception) {
                Log.e("SmsViewModel", "Background refresh failed", e)
            }
        }
    }

    // Load groups in background
    private suspend fun loadGroupsInBackground(smsList: List<ConversationInfo>, adsConversation: ConversationInfo) {
        try {
            val groups = groupRepository.getAllGroupsWithMembers().first()

            if (groups.isNotEmpty()) {
                val groupConversations = groups.map { groupWithMembers ->
                    val group = groupWithMembers.group
                    val members = groupWithMembers.members

                    ConversationInfo(
                        threadId = group.threadId ?: -(group.id + 1000),
                        address = members.joinToString(", ") { it.phoneNumber },
                        contactName = group.name,
                        lastMessage = if (group.threadId != null) "Group conversation" else "Tap to start chatting",
                        timestamp = group.lastMessageAt,
                        unreadCount = 0,
                        photoUri = null,
                        isAdConversation = false,
                        isGroupConversation = true,
                        recipientCount = members.size,
                        groupId = group.id
                    )
                }

                val groupThreadIds = groups.mapNotNull { it.group.threadId }.toSet()
                val filteredSmsList = smsList.filterNot { groupThreadIds.contains(it.threadId) }

                val allConversations = (filteredSmsList + groupConversations)
                    .sortedByDescending { it.timestamp }

                // Update cache
                cachedConversations = allConversations
                lastLoadTime = System.currentTimeMillis()

                withContext(Dispatchers.Main) {
                    _conversations.value = listOf(adsConversation) + allConversations
                }

                // Sync groups to Firebase (low priority)
                delay(2000)
                syncService.syncGroups(groups.map { it.group })
            }
        } catch (e: Exception) {
            Log.e("SmsViewModel", "Error loading groups", e)
        }
    }

    // Batch resolve contact names (much faster than one-by-one)
    private fun resolveContactNamesBatch(list: List<ConversationInfo>) {
        viewModelScope.launch(Dispatchers.IO) {
            val uniqueAddresses = list.map { it.address }.distinct()
            val resolvedNames = mutableMapOf<String, String>()

            // Resolve all at once
            for (address in uniqueAddresses) {
                val name = repo.resolveContactName(address)
                if (name != null) {
                    resolvedNames[address] = name
                }
            }

            // Apply all resolved names in one update
            if (resolvedNames.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    _conversations.value = _conversations.value.map { c ->
                        val resolved = resolvedNames[c.address]
                        if (resolved != null && resolved != c.contactName) {
                            c.copy(contactName = resolved)
                        } else c
                    }
                }
            }
        }
    }

    // Background MMS sync (non-blocking)
    private suspend fun syncMmsInBackground(conversations: List<ConversationInfo>) {
        try {
            var totalMmsSynced = 0
            for (convo in conversations) {
                // Use related thread IDs if available for merged conversations
                val messages = if (convo.relatedThreadIds.size > 1) {
                    repo.getMessagesByThreadIds(convo.relatedThreadIds, limit = 5, offset = 0)
                } else {
                    repo.getMessagesByThreadId(convo.threadId, limit = 5, offset = 0)
                }
                val mmsMessages = messages.filter { it.isMms }
                for (message in mmsMessages) {
                    syncService.syncMessage(message)
                    totalMmsSynced++
                }
            }
            if (totalMmsSynced > 0) {
                Log.d("SmsViewModel", "Background: Synced $totalMmsSynced MMS messages")
            }
        } catch (e: Exception) {
            Log.e("SmsViewModel", "Background MMS sync failed", e)
        }
    }

    fun refreshConversations() {
        viewModelScope.launch {
            val conversations = repo.getConversations()

            // Load pinned and muted thread IDs
            val pinnedIds = withContext(Dispatchers.IO) { pinnedDao.getPinnedIds().toSet() }
            val mutedIds = withContext(Dispatchers.IO) { mutedDao.getMutedIds().toSet() }

            // Ads conversation (always at top)
            val adsConversation = ConversationInfo(
                threadId = -1L,
                address = "syncflow_ads",
                contactName = "SyncFlow Deals",
                lastMessage = "Tap here to explore today's best offers!",
                timestamp = System.currentTimeMillis(),
                unreadCount = 0,
                photoUri = null,
                isAdConversation = true
            )

            // Mark pinned/muted and sort
            val list = conversations.map { conv ->
                conv.copy(
                    isPinned = conv.threadId in pinnedIds,
                    isMuted = conv.threadId in mutedIds
                )
            }.sortedWith(
                compareByDescending<ConversationInfo> { it.isPinned }
                    .thenByDescending { it.timestamp }
            )

            cachedConversations = list
            lastLoadTime = System.currentTimeMillis()
            _conversations.value = listOf(adsConversation) + list

            resolveContactNamesBatch(list)
            resolveContactPhotos(list)
        }
    }

    // ---------------------------------------------------------
    // LOAD SINGLE CONVERSATION
    // ---------------------------------------------------------
    fun loadConversation(address: String) {
        viewModelScope.launch {
            // Clear old messages immediately to prevent showing wrong conversation
            _conversationMessages.value = emptyList()

            currentAddress = address
            offset = 0

            // Do all heavy work on IO dispatcher for speed
            val result = withContext(Dispatchers.IO) {
                // Resolve threadId for fastest load
                val threadId = repo.getThreadIdForAddress(address)

                val normalized = PhoneNumberUtils.normalizeForConversation(address)
                val mergedConversation = _conversations.value.firstOrNull {
                    PhoneNumberUtils.normalizeForConversation(it.address) == normalized
                }
                val primaryAddress = mergedConversation?.address ?: address
                val related = mergedConversation?.relatedAddresses ?: listOf(address)
                updateConversationContext(primaryAddress, related)

                currentThreadId = threadId
                val mergedRelatedIds = mergedConversation?.relatedThreadIds?.filter { it > 0 } ?: emptyList()
                currentRelatedThreadIds = when {
                    mergedRelatedIds.isNotEmpty() -> {
                        if (threadId != null && !mergedRelatedIds.contains(threadId)) {
                            mergedRelatedIds + threadId
                        } else {
                            mergedRelatedIds
                        }
                    }
                    threadId != null -> listOf(threadId)
                    else -> emptyList()
                }

                if (threadId == null) {
                    return@withContext null
                }

                val firstPage = if (currentRelatedThreadIds.size > 1) {
                    repo.getMessagesByThreadIds(currentRelatedThreadIds, pageSize, 0)
                } else {
                    repo.getMessagesByThreadId(threadId, pageSize, 0)
                }

                // Deduplicate messages (SMS + MMS may overlap)
                val deduped = MessageDeduplicator.deduplicateMessages(firstPage)
                // Skip disk cache on initial load for speed - only use memory cache
                val quickMessages = applyMmsOverrides(deduped, loadFromDisk = false)
                Triple(quickMessages, firstPage.size == pageSize, deduped)
            }

            // Update UI on main thread
            if (result == null) {
                _conversationMessages.value = emptyList()
                _hasMore.value = false
                return@launch
            }

            val (messages, hasMore, deduped) = result
            _conversationMessages.value = messages
            _hasMore.value = hasMore

            // Load MMS details from disk in background and update UI
            viewModelScope.launch(Dispatchers.IO) {
                val withDiskCache = applyMmsOverrides(deduped, loadFromDisk = true)
                if (withDiskCache != messages) {
                    withContext(Dispatchers.Main) {
                        _conversationMessages.value = withDiskCache
                    }
                }
            }

            // Background tasks
            resolveNameForMessages(address)

            if (deduped.isNotEmpty()) {
                val last = deduped.first()
                if (last.type == 1) {
                    _smartReplies.value = generateSmartReplies(last.body)
                }
            }
        }
    }

    // Load conversation by thread ID directly (for groups)
    fun loadConversationByThreadId(threadId: Long, displayName: String, relatedThreadIds: List<Long> = emptyList()) {
        viewModelScope.launch {
            // Clear old messages immediately to prevent showing wrong conversation
            _conversationMessages.value = emptyList()

            Log.d("SmsViewModel", "=== LOADING CONVERSATION BY THREAD ID ===")
            Log.d("SmsViewModel", "Thread ID: $threadId")
            Log.d("SmsViewModel", "Display Name: $displayName")
            Log.d("SmsViewModel", "Related Thread IDs (passed): $relatedThreadIds")

            currentAddress = displayName
            offset = 0
            currentThreadId = threadId

            // Do all heavy work on IO dispatcher for speed
            val result = withContext(Dispatchers.IO) {
                val conv = _conversations.value.find { it.threadId == threadId }
                if (conv != null) {
                    currentAddress = conv.address
                    val relatedAddresses = if (conv.relatedAddresses.isNotEmpty()) {
                        conv.relatedAddresses
                    } else {
                        listOf(conv.address)
                    }
                    updateConversationContext(conv.address, relatedAddresses)
                } else {
                    updateConversationContext(displayName, listOf(displayName))
                }

                // Look up related thread IDs from conversation list if not provided
                val effectiveRelatedThreadIds = if (relatedThreadIds.isNotEmpty()) {
                    relatedThreadIds
                } else {
                    // Find conversation in list to get its relatedThreadIds
                    if (conv != null && conv.relatedThreadIds.isNotEmpty()) {
                        Log.d("SmsViewModel", "Found related threads from conversation: ${conv.relatedThreadIds}")
                        conv.relatedThreadIds
                    } else {
                        listOf(threadId)
                    }
                }
                currentRelatedThreadIds = effectiveRelatedThreadIds
                Log.d("SmsViewModel", "Effective Related Thread IDs: $currentRelatedThreadIds")

                val firstPage = if (currentRelatedThreadIds.size > 1) {
                    repo.getMessagesByThreadIds(currentRelatedThreadIds, pageSize, 0)
                } else {
                    repo.getMessagesByThreadId(threadId, pageSize, 0)
                }

                Log.d("SmsViewModel", "Loaded ${firstPage.size} messages for thread $threadId")
                firstPage.forEach { msg ->
                    Log.d("SmsViewModel", "  - Message ${msg.id}: ${msg.body.take(50)}... (type=${msg.type}, date=${msg.date})")
                }

                // Deduplicate messages (SMS + MMS may overlap)
                val deduped = MessageDeduplicator.deduplicateMessages(firstPage)
                // Skip disk cache on initial load for speed - only use memory cache
                Triple(applyMmsOverrides(deduped, loadFromDisk = false), firstPage, deduped)
            }

            // Update UI on main thread
            val (messages, firstPage, deduped) = result
            _conversationMessages.value = messages
            _hasMore.value = firstPage.size == pageSize

            // Load MMS details from disk in background and update UI
            viewModelScope.launch(Dispatchers.IO) {
                val withDiskCache = applyMmsOverrides(deduped, loadFromDisk = true)
                if (withDiskCache != messages) {
                    withContext(Dispatchers.Main) {
                        _conversationMessages.value = withDiskCache
                    }
                }
            }

            if (firstPage.isNotEmpty()) {
                val last = firstPage.first()
                if (last.type == 1) {
                    _smartReplies.value = generateSmartReplies(last.body)
                }
            } else {
                Log.d("SmsViewModel", "No messages found for thread ID $threadId")
            }
        }
    }

    // ---------------------------------------------------------
    // LOAD MORE PAGINATION
    // ---------------------------------------------------------
    fun loadMore() {
        if (_isLoadingMore.value || !_hasMore.value) return
        val threadId = currentThreadId ?: return

        viewModelScope.launch {
            _isLoadingMore.value = true

            offset += pageSize

            // Use thread IDs based method for merged conversations
            val new = if (currentRelatedThreadIds.size > 1) {
                repo.getMessagesByThreadIds(currentRelatedThreadIds, pageSize, offset)
            } else {
                repo.getMessagesByThreadId(threadId, pageSize, offset)
            }

            // Use append with deduplication to prevent overlap issues
            val patchedNew = applyMmsOverrides(new)
            _conversationMessages.value = MessageDeduplicator.appendMessages(
                _conversationMessages.value,
                patchedNew
            )
            _hasMore.value = new.size == pageSize

            _isLoadingMore.value = false
        }
    }

    // ---------------------------------------------------------
    // SEND SMS
    // ---------------------------------------------------------
    fun sendSms(address: String, body: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            Log.d("SmsViewModel", "sendSms() started: address=$address")

            // Validate inputs for security
            val phoneValidation = com.phoneintegration.app.utils.InputValidation.validatePhoneNumber(address)
            val messageValidation = com.phoneintegration.app.utils.InputValidation.validateMessage(body)

            if (!phoneValidation.isValid || !messageValidation.isValid) {
                Log.w("SmsViewModel", "Input validation failed: phone=${phoneValidation.errorMessage}, message=${messageValidation.errorMessage}")
                onResult(false)
                return@launch
            }

            // Use sanitized values
            val sanitizedAddress = phoneValidation.sanitizedValue ?: address
            val sanitizedBody = messageValidation.sanitizedValue ?: body

            // Suppress ContentObserver for 3 seconds to prevent race conditions
            suppressObserverUntil = System.currentTimeMillis() + 3000
            Log.d("SmsViewModel", "ContentObserver suppressed for 3 seconds")

            val temp = SmsMessage(
                id = -System.currentTimeMillis(),
                address = sanitizedAddress,
                body = sanitizedBody,
                date = System.currentTimeMillis(),
                type = 2,
                contactName = null
            )

            _conversationMessages.value = listOf(temp) + _conversationMessages.value
            Log.d("SmsViewModel", "Temp message added, total messages: ${_conversationMessages.value.size}")

            val ok = repo.sendSms(sanitizedAddress, sanitizedBody)
            Log.d("SmsViewModel", "sendSms result: $ok")

            if (ok) {
                // Wait for SMS to be written to provider
                kotlinx.coroutines.delay(1000)

                val resolvedThreadId = resolveThreadIdForAddress(address)
                Log.d("SmsViewModel", "resolvedThreadId=$resolvedThreadId, currentThreadId=$currentThreadId")
                if (currentThreadId == null && resolvedThreadId != null) {
                    currentThreadId = resolvedThreadId
                }
                ensureRelatedThreadId(resolvedThreadId)
                ensureRelatedAddress(address)

                val threadId = currentThreadId
                Log.d("SmsViewModel", "Using threadId=$threadId, relatedThreadIds=$currentRelatedThreadIds")

                val updated = when {
                    currentRelatedThreadIds.isNotEmpty() -> {
                        Log.d("SmsViewModel", "Loading by thread IDs: $currentRelatedThreadIds")
                        repo.getMessagesByThreadIds(currentRelatedThreadIds, pageSize, 0)
                    }
                    threadId != null -> {
                        Log.d("SmsViewModel", "Loading by thread ID: $threadId")
                        repo.getMessagesByThreadId(threadId, pageSize, 0)
                    }
                    else -> {
                        Log.d("SmsViewModel", "Loading by address: $address")
                        repo.getMessages(address, pageSize, 0)
                    }
                }
                Log.d("SmsViewModel", "Loaded ${updated.size} messages after send")

                // Check if our sent message is in the list
                var sentFound = updated.any { it.type == 2 && it.body == body }
                Log.d("SmsViewModel", "Sent message found in updated list: $sentFound")

                // If not found, wait and retry (SMS provider might still be processing)
                var finalUpdated = updated
                if (!sentFound) {
                    Log.d("SmsViewModel", "Sent message not found, waiting and retrying...")
                    kotlinx.coroutines.delay(500)
                    finalUpdated = when {
                        currentRelatedThreadIds.isNotEmpty() -> {
                            repo.getMessagesByThreadIds(currentRelatedThreadIds, pageSize, 0)
                        }
                        threadId != null -> {
                            repo.getMessagesByThreadId(threadId, pageSize, 0)
                        }
                        else -> {
                            repo.getMessages(address, pageSize, 0)
                        }
                    }
                    sentFound = finalUpdated.any { it.type == 2 && it.body == body }
                    Log.d("SmsViewModel", "After retry: found=${sentFound}, messages=${finalUpdated.size}")
                }

                // Check if the sent message was found in the provider
                val sentMessageInProvider = finalUpdated.any { it.id > 0 && it.type == 2 && it.body == body }
                Log.d("SmsViewModel", "Sent message found in provider: $sentMessageInProvider")

                if (threadId == null || currentThreadId == threadId) {
                    if (sentMessageInProvider) {
                        // Real message found - remove temp and use provider data
                        val deduped = MessageDeduplicator.removeTempIfRealExists(finalUpdated, body, address)
                        val final = MessageDeduplicator.deduplicateMessages(deduped)
                        Log.d("SmsViewModel", "Using provider data: ${final.size} messages")
                        _conversationMessages.value = final
                    } else {
                        // Real message NOT found in provider (write permission issue?)
                        // Keep the temp message so user can see they sent something
                        Log.w("SmsViewModel", "Sent message not in provider - keeping temp message in UI")

                        // Merge: keep temp message, add provider messages that aren't duplicates
                        val currentMessages = _conversationMessages.value
                        val tempMessage = currentMessages.find { it.id == temp.id }

                        if (tempMessage != null) {
                            // Combine temp with provider data
                            val combined = listOf(tempMessage) + finalUpdated
                            val final = MessageDeduplicator.deduplicateMessages(combined)
                            Log.d("SmsViewModel", "Merged temp with provider: ${final.size} messages")
                            _conversationMessages.value = final
                        } else {
                            // Temp message somehow got lost - just use provider data
                            val final = MessageDeduplicator.deduplicateMessages(finalUpdated)
                            _conversationMessages.value = final
                        }
                    }
                } else {
                    Log.w("SmsViewModel", "Thread ID mismatch: threadId=$threadId, currentThreadId=$currentThreadId - NOT updating UI")
                }

                refreshConversations()

                // Immediately sync the sent message to Firebase (in background, don't block UI)
                if (updated.isNotEmpty()) {
                    val sentMessage = updated.firstOrNull { it.type == 2 && it.body == body }
                    sentMessage?.let { msg ->
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                syncService.syncMessage(msg)
                                Log.d("SmsViewModel", "Sent message synced to Firebase: ${msg.id}")
                            } catch (e: Exception) {
                                Log.e("SmsViewModel", "Failed to sync sent message to Firebase", e)
                            }
                        }
                    }
                }
            } else {
                _conversationMessages.value =
                    _conversationMessages.value.filterNot { it.id == temp.id }
            }

            // Clear suppress flag - let ContentObserver work again
            suppressObserverUntil = 0
            Log.d("SmsViewModel", "ContentObserver suppress cleared")

            onResult(ok)
        }
    }

    // ---------------------------------------------------------
    // DELETE SMS
    // ---------------------------------------------------------
    fun deleteMessage(id: Long, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = repo.deleteMessage(id)
            if (ok) {
                _conversationMessages.value =
                    _conversationMessages.value.filterNot { it.id == id }
            }
            onResult(ok)
        }
    }

    /**
     * Delete an entire conversation (all messages in thread).
     */
    fun deleteConversation(threadId: Long, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = repo.deleteThread(threadId)
            if (ok) {
                // Remove from conversations list
                _conversations.value = _conversations.value.filterNot { it.threadId == threadId }
                // Clear current conversation messages
                _conversationMessages.value = emptyList()
            }
            onResult(ok)
        }
    }

    /**
     * Reconcile deletions from Firebase when app resumes.
     *
     * DISABLED: This function was incorrectly deleting messages that were never
     * synced to Firebase in the first place (like bank messages). The logic
     * assumed all local messages exist in Firebase, which is not true.
     *
     * To properly implement this, we would need to track which messages have
     * been successfully synced before we can safely delete based on cloud state.
     */
    fun reconcileDeletedMessages() {
        // DISABLED - This was causing data loss by deleting unsynced messages
        // See: https://github.com/issues/xxx - ICICI messages being deleted
        Log.d("SmsViewModel", "reconcileDeletedMessages: DISABLED to prevent data loss")
    }

    // ---------------------------------------------------------
    // PIN/UNPIN CONVERSATIONS
    // ---------------------------------------------------------
    fun pinConversation(threadId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            pinnedDao.pin(PinnedConversation(threadId))
            withContext(Dispatchers.Main) {
                // Update the cached list to reflect pinned status
                _conversations.value = _conversations.value.map { conv ->
                    if (conv.threadId == threadId) conv.copy(isPinned = true) else conv
                }.sortedWith(
                    compareByDescending<ConversationInfo> { it.isPinned }
                        .thenByDescending { if (it.isAdConversation) Long.MAX_VALUE else it.timestamp }
                )
            }
        }
    }

    fun unpinConversation(threadId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            pinnedDao.unpin(threadId)
            withContext(Dispatchers.Main) {
                // Update the cached list to reflect unpinned status
                _conversations.value = _conversations.value.map { conv ->
                    if (conv.threadId == threadId) conv.copy(isPinned = false) else conv
                }.sortedWith(
                    compareByDescending<ConversationInfo> { it.isPinned }
                        .thenByDescending { if (it.isAdConversation) Long.MAX_VALUE else it.timestamp }
                )
            }
        }
    }

    fun togglePin(threadId: Long, currentlyPinned: Boolean) {
        if (currentlyPinned) {
            unpinConversation(threadId)
        } else {
            pinConversation(threadId)
        }
    }

    // ---------------------------------------------------------
    // MUTE/UNMUTE CONVERSATIONS
    // ---------------------------------------------------------
    fun muteConversation(threadId: Long, durationMs: Long? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val mutedUntil = durationMs?.let { System.currentTimeMillis() + it }
            mutedDao.mute(MutedConversation(threadId, mutedUntil = mutedUntil))
            withContext(Dispatchers.Main) {
                _conversations.value = _conversations.value.map { conv ->
                    if (conv.threadId == threadId) conv.copy(isMuted = true) else conv
                }
            }
        }
    }

    fun unmuteConversation(threadId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            mutedDao.unmute(threadId)
            withContext(Dispatchers.Main) {
                _conversations.value = _conversations.value.map { conv ->
                    if (conv.threadId == threadId) conv.copy(isMuted = false) else conv
                }
            }
        }
    }

    fun toggleMute(threadId: Long, currentlyMuted: Boolean) {
        if (currentlyMuted) {
            unmuteConversation(threadId)
        } else {
            muteConversation(threadId)
        }
    }

    fun markConversationAsSpam(conversation: ConversationInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            val threadIds = if (conversation.relatedThreadIds.isNotEmpty()) {
                conversation.relatedThreadIds
            } else {
                listOf(conversation.threadId)
            }
            val messages = if (threadIds.size > 1) {
                repo.getMessagesByThreadIds(threadIds, limit = 1, offset = 0)
            } else {
                repo.getMessagesByThreadId(conversation.threadId, limit = 1, offset = 0)
            }
            val latest = messages.firstOrNull() ?: return@launch
            val address = conversation.address.ifBlank { latest.address }

            // Add to blocked senders list for future scans
            val spamFilterService = com.phoneintegration.app.spam.SpamFilterService.getInstance(getApplication())
            spamFilterService.addBlockedSender(address)

            val spamMessage = SpamMessage(
                messageId = latest.id,
                address = address,
                body = latest.body,
                date = latest.date,
                contactName = conversation.contactName ?: latest.contactName,
                spamConfidence = 1.0f,
                spamReasons = "Marked by user",
                detectedAt = System.currentTimeMillis(),
                isUserMarked = true,
                isRead = true
            )
            spamMessageDao.insert(spamMessage)
            // Only sync to desktop if devices are paired
            if (DesktopSyncService.hasPairedDevices(getApplication())) {
                syncService.syncSpamMessage(spamMessage)
            }
        }
    }

    /**
     * Check if a conversation is muted (used by notification service)
     */
    suspend fun isConversationMuted(threadId: Long): Boolean {
        return mutedDao.isMuted(threadId)
    }

    // ---------------------------------------------------------
    // STARRED MESSAGES
    // ---------------------------------------------------------
    private val _starredMessageIds = MutableStateFlow<Set<Long>>(emptySet())
    val starredMessageIds = _starredMessageIds.asStateFlow()

    fun loadStarredMessageIds() {
        viewModelScope.launch(Dispatchers.IO) {
            val ids = starredDao.getAllStarredIds().toSet()
            _starredMessageIds.value = ids
        }
    }

    fun starMessage(message: SmsMessage) {
        viewModelScope.launch(Dispatchers.IO) {
            val threadId = currentThreadId ?: return@launch
            val starred = StarredMessage(
                messageId = message.id,
                threadId = threadId,
                address = message.address,
                body = message.body,
                timestamp = message.date,
                isMms = message.isMms
            )
            starredDao.star(starred)
            _starredMessageIds.value = _starredMessageIds.value + message.id
            Log.d("SmsViewModel", "Message ${message.id} starred")
        }
    }

    fun unstarMessage(messageId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            starredDao.unstar(messageId)
            _starredMessageIds.value = _starredMessageIds.value - messageId
            Log.d("SmsViewModel", "Message $messageId unstarred")
        }
    }

    fun toggleStar(message: SmsMessage) {
        if (_starredMessageIds.value.contains(message.id)) {
            unstarMessage(message.id)
        } else {
            starMessage(message)
        }
    }

    fun isMessageStarred(messageId: Long): Boolean {
        return _starredMessageIds.value.contains(messageId)
    }

    // ---------------------------------------------------------
    // MESSAGE FORWARDING
    // ---------------------------------------------------------
    fun forwardMessage(targetAddress: String, messageBody: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            Log.d("SmsViewModel", "Forwarding message to $targetAddress")
            val ok = repo.sendSms(targetAddress, messageBody)
            if (ok) {
                Log.d("SmsViewModel", "Message forwarded successfully")
            } else {
                Log.e("SmsViewModel", "Failed to forward message")
            }
            withContext(Dispatchers.Main) {
                onResult(ok)
            }
        }
    }

    // ---------------------------------------------------------
    // CONTACT BLOCKING
    // ---------------------------------------------------------
    private val _blockedNumbers = MutableStateFlow<Set<String>>(emptySet())
    val blockedNumbers = _blockedNumbers.asStateFlow()

    fun loadBlockedNumbers() {
        viewModelScope.launch(Dispatchers.IO) {
            val numbers = blockedDao.getAllBlockedNumbers().toSet()
            _blockedNumbers.value = numbers
        }
    }

    fun blockContact(phoneNumber: String, displayName: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val normalizedNumber = normalizePhoneNumber(phoneNumber)
            blockedDao.block(BlockedContact(
                phoneNumber = normalizedNumber,
                displayName = displayName
            ))
            _blockedNumbers.value = _blockedNumbers.value + normalizedNumber
            Log.d("SmsViewModel", "Contact blocked: $normalizedNumber")

            // Reload conversations to hide blocked contact
            withContext(Dispatchers.Main) {
                loadConversations()
            }
        }
    }

    fun unblockContact(phoneNumber: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val normalizedNumber = normalizePhoneNumber(phoneNumber)
            blockedDao.unblock(normalizedNumber)
            _blockedNumbers.value = _blockedNumbers.value - normalizedNumber
            Log.d("SmsViewModel", "Contact unblocked: $normalizedNumber")

            // Reload conversations to show unblocked contact
            withContext(Dispatchers.Main) {
                loadConversations()
            }
        }
    }

    fun isContactBlocked(phoneNumber: String): Boolean {
        val normalized = normalizePhoneNumber(phoneNumber)
        return _blockedNumbers.value.contains(normalized)
    }

    suspend fun isContactBlockedAsync(phoneNumber: String): Boolean {
        val normalized = normalizePhoneNumber(phoneNumber)
        return blockedDao.isBlocked(normalized)
    }

    private fun normalizePhoneNumber(phoneNumber: String): String {
        // Remove all non-digit characters for comparison
        return phoneNumber.replace(Regex("[^0-9+]"), "")
    }

    // ---------------------------------------------------------
    // NOTIFICATION SETTINGS
    // ---------------------------------------------------------
    fun setCustomNotificationSound(threadId: Long, soundUri: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = notificationSettingsDao.get(threadId)
            if (existing != null) {
                notificationSettingsDao.updateSoundUri(threadId, soundUri, System.currentTimeMillis())
            } else {
                notificationSettingsDao.save(NotificationSettings(
                    threadId = threadId,
                    customSoundUri = soundUri
                ))
            }
            Log.d("SmsViewModel", "Custom notification sound set for thread $threadId: $soundUri")
        }
    }

    fun setNotificationVibration(threadId: Long, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = notificationSettingsDao.get(threadId)
            if (existing != null) {
                notificationSettingsDao.updateVibration(threadId, enabled, System.currentTimeMillis())
            } else {
                notificationSettingsDao.save(NotificationSettings(
                    threadId = threadId,
                    vibrationEnabled = enabled
                ))
            }
        }
    }

    suspend fun getNotificationSettings(threadId: Long): NotificationSettings? {
        return notificationSettingsDao.get(threadId)
    }

    fun clearNotificationSettings(threadId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            notificationSettingsDao.delete(threadId)
        }
    }

    // ---------------------------------------------------------
    // SMART REPLIES
    // ---------------------------------------------------------
    private fun generateSmartReplies(message: String): List<String> {
        // Use enhanced local AI suggestions first
        val messages = conversationMessages.value.takeLast(10) // Last 10 messages for context
        if (messages.isNotEmpty()) {
            viewModelScope.launch {
                try {
                    val aiSuggestions = aiService.generateMessageSuggestions(messages, count = 3)
                    if (aiSuggestions.isNotEmpty()) {
                        _smartReplies.value = aiSuggestions
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.w("SmsViewModel", "AI suggestions failed, using ML Kit", e)
                }
            }
        }

        // Fallback to ML Kit smart replies
        viewModelScope.launch {
            try {
                val helper = SmartReplyHelper()
                val mlKitReplies = helper.generateReplies(messages)
                if (mlKitReplies.isNotEmpty()) {
                    _smartReplies.value = mlKitReplies
                    return@launch
                }
            } catch (e: Exception) {
                Log.w("SmsViewModel", "ML Kit failed, using patterns", e)
            }
        }

        // Final fallback to pattern-based replies
        val lower = message.lowercase()

        return when {
            "how are you" in lower || "how r u" in lower ->
                listOf("I'm good!", "Doing well!", "Great!")
            "thanks" in lower || "thank you" in lower ->
                listOf("You're welcome!", "Anytime!", "No problem!")
            "sorry" in lower ->
                listOf("It's okay!", "No worries!", "Don't worry about it")
            "?" in lower ->
                listOf("Yes", "No", "Let me check")
            else ->
                listOf("OK", "Sure", "Got it")
        }
    }

    private fun resolveContactPhotos(list: List<ConversationInfo>) {
        if (list.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                list.forEach { info ->
                    try {
                        // Skip if address is null or empty
                        if (info.address.isNullOrEmpty()) return@forEach

                        val photo = repo.resolveContactPhoto(info.address)

                        // Only update if we got a valid photo and it's different from current
                        if (!photo.isNullOrEmpty() && photo != info.photoUri) {
                            // Validate the photo URI by trying to access it
                            if (isValidPhotoUri(photo)) {
                                withContext(Dispatchers.Main) {
                                    try {
                                        _conversations.value = _conversations.value.map { c ->
                                            if (c.threadId == info.threadId)
                                                c.copy(photoUri = photo)
                                            else c
                                        }
                                    } catch (e: Exception) {
                                        Log.e("SmsViewModel", "Error updating conversations list", e)
                                    }
                                }

                                // Persist the updated conversation to cache (safely)
                                try {
                                    val updated = _conversations.value.firstOrNull { it.threadId == info.threadId }
                                    if (updated != null) {
                                        cachedConversationDao.insert(updated.toCachedConversation())
                                    }
                                } catch (e: Exception) {
                                    Log.e("SmsViewModel", "Error saving to cache for ${info.address}", e)
                                }

                                Log.d("SmsViewModel", "Updated photo for ${info.address}: $photo")
                            } else {
                                Log.w("SmsViewModel", "Invalid photo URI for ${info.address}: $photo")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("SmsViewModel", "Error resolving photo for ${info.address}", e)
                        // Clear invalid photo URI
                        try {
                            withContext(Dispatchers.Main) {
                                _conversations.value = _conversations.value.map { c ->
                                    if (c.threadId == info.threadId)
                                        c.copy(photoUri = null)
                                    else c
                                }
                            }
                        } catch (updateException: Exception) {
                            Log.e("SmsViewModel", "Error clearing photo URI for ${info.address}", updateException)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("SmsViewModel", "Fatal error in resolveContactPhotos", e)
            }
        }
    }

    private fun isValidPhotoUri(photoUri: String): Boolean {
        return try {
            // Try to parse the URI to ensure it's valid
            val uri = android.net.Uri.parse(photoUri)
            uri != null && (uri.scheme == "content" || uri.scheme == "file")
        } catch (e: Exception) {
            false
        }
    }
    fun sendMms(address: String, uri: Uri, messageText: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            Log.d("SmsViewModel", "[LocalMmsSend] sendMms() called - address: $address, uri: $uri, messageText length: ${messageText?.length ?: 0}")

            // Suppress ContentObserver for 5 seconds (MMS takes longer)
            suppressObserverUntil = System.currentTimeMillis() + 5000
            Log.d("SmsViewModel", "ContentObserver suppressed for 5 seconds (MMS)")

            val ok = MmsHelper.sendMms(getApplication(), address, uri, messageText)
            Log.d("SmsViewModel", "[LocalMmsSend] MmsHelper.sendMms returned: $ok")

            if (ok) {
                Log.d("SmsViewModel", "MMS send request succeeded, waiting DB insert…")

                delay(1800)  // Telephony inserts MMS asynchronously

                var threadId = currentThreadId
                val resolvedThreadId = resolveThreadIdForAddress(address)
                if (resolvedThreadId != null) {
                    if (threadId == null) {
                        threadId = resolvedThreadId
                        currentThreadId = resolvedThreadId
                    }
                    ensureRelatedThreadId(resolvedThreadId)
                    ensureRelatedAddress(address)
                } else if (threadId == null) {
                    Log.e("SmsViewModel", "Failed to resolve thread ID for MMS")
                }

                fun updateUiIfActive(list: List<SmsMessage>) {
                    if (threadId != null && currentRelatedThreadIds.contains(threadId)) {
                        viewModelScope.launch(Dispatchers.Main) {
                            _conversationMessages.value = MessageDeduplicator.deduplicateMessages(list)
                        }
                    }
                }

                var sentMms: SmsMessage? = null
                if (threadId != null) {
                    val updated = if (currentRelatedThreadIds.size > 1) {
                        repo.getMessagesByThreadIds(currentRelatedThreadIds, pageSize, 0)
                    } else {
                        repo.getMessagesByThreadId(threadId, pageSize, 0)
                    }
                    sentMms = updated.firstOrNull { it.type == 2 && it.isMms }
                    val patchedSent = ensureMmsAttachment(sentMms, uri, messageText)
                    if (patchedSent != null) {
                        sentMms = patchedSent
                    }

                    val patchedList = if (patchedSent != null) {
                        updated.map { message ->
                            if (message.id == patchedSent.id) patchedSent else message
                        }
                    } else {
                        updated
                    }

                    updateUiIfActive(patchedList)
                }

                if (sentMms == null || shouldPersistMmsParts(sentMms, messageText)) {
                    val persistedId = MmsHelper.persistSentMmsIfMissing(
                        getApplication(),
                        address,
                        messageText,
                        uri
                    )
                    if (persistedId != null && threadId != null) {
                        delay(500)
                        val refreshed = if (currentRelatedThreadIds.size > 1) {
                            repo.getMessagesByThreadIds(currentRelatedThreadIds, pageSize, 0)
                        } else {
                            repo.getMessagesByThreadId(threadId, pageSize, 0)
                        }
                        sentMms = refreshed.firstOrNull { it.id == persistedId }
                            ?: refreshed.firstOrNull { it.type == 2 && it.isMms }
                        val patchedSent = ensureMmsAttachment(sentMms, uri, messageText)
                        if (patchedSent != null) {
                            sentMms = patchedSent
                        }

                        val patchedList = if (patchedSent != null) {
                            refreshed.map { message ->
                                if (message.id == patchedSent.id) patchedSent else message
                            }
                        } else {
                            refreshed
                        }

                        updateUiIfActive(patchedList)
                    }
                }

                sentMms?.let { mms ->
                    cacheMmsOverride(mms)
                    // Sync in background to avoid blocking UI (only if devices are paired)
                    if (DesktopSyncService.hasPairedDevices(getApplication())) {
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                val syncService = com.phoneintegration.app.desktop.DesktopSyncService(getApplication())
                                syncService.syncMessage(mms)
                                Log.d("SmsViewModel", "Sent MMS synced to Firebase: ${mms.id}")
                            } catch (e: Exception) {
                                Log.e("SmsViewModel", "Failed to sync sent MMS to Firebase", e)
                            }
                        }
                    }
                }

                loadConversations()
            } else {
                // Insert a temporary failed message bubble
                val failedMsg = SmsMessage(
                    id = -System.currentTimeMillis(),
                    address = address,
                    body = "[MMS image]",
                    date = System.currentTimeMillis(),
                    type = 2,
                    isMms = true,
                    mmsAttachments = emptyList(),
                    category = null,
                    otpInfo = null
                )

                withContext(Dispatchers.Main) {
                    // Prepend failed message into conversation
                    _conversationMessages.value = listOf(failedMsg) + _conversationMessages.value
                }
            }

            // Clear suppress flag
            suppressObserverUntil = 0
            Log.d("SmsViewModel", "ContentObserver suppress cleared (MMS)")
        }
    }

    fun retryMms(sms: SmsMessage) {
        viewModelScope.launch {
            val uri = sms.mmsAttachments.firstOrNull()?.filePath ?: return@launch

            sendMms(sms.address, Uri.parse(uri), sms.body.ifBlank { null })
        }
    }

    private fun ensureMmsAttachment(
        sentMms: SmsMessage?,
        sourceUri: Uri,
        messageText: String?
    ): SmsMessage? {
        if (sentMms == null) return null
        if (sentMms.mmsAttachments.isNotEmpty()) return sentMms

        val providerAttachments = loadMmsAttachmentsFromProvider(sentMms.id)
        if (providerAttachments.isNotEmpty()) {
            val patchedBody = if (sentMms.body.isBlank() && !messageText.isNullOrBlank()) {
                messageText
            } else {
                sentMms.body
            }
            return sentMms.copy(
                body = patchedBody,
                mmsAttachments = providerAttachments,
                isMms = true
            )
        }

        val attachment = buildAttachmentFromUri(sourceUri) ?: return sentMms
        val patchedBody = if (sentMms.body.isBlank() && !messageText.isNullOrBlank()) {
            messageText
        } else {
            sentMms.body
        }

        return sentMms.copy(
            body = patchedBody,
            mmsAttachments = listOf(attachment),
            isMms = true
        )
    }

    private fun buildAttachmentFromUri(uri: Uri): MmsAttachment? {
        val resolver = getApplication<Application>().contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            ?: uri.lastPathSegment

        val extension = name?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotBlank() }
        val mimeType = resolver.getType(uri)
            ?: extension?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: "application/octet-stream"

        return MmsAttachment(
            id = System.currentTimeMillis(),
            contentType = mimeType,
            filePath = uri.toString(),
            data = null,
            fileName = name
        )
    }

    private fun shouldPersistMmsParts(message: SmsMessage?, messageText: String?): Boolean {
        if (message == null) return true
        if (!message.isMms) return false
        val hasMissingText = !messageText.isNullOrBlank() && message.body.isBlank()
        val hasMissingAttachments = message.mmsAttachments.isEmpty() || message.mmsAttachments.any { attachment ->
            val path = attachment.filePath ?: return@any true
            !path.startsWith("content://mms/part/")
        }

        return hasMissingText || hasMissingAttachments
    }

    private fun loadMmsAttachmentsFromProvider(mmsId: Long): List<MmsAttachment> {
        if (mmsId <= 0) return emptyList()

        val resolver = getApplication<Application>().contentResolver
        val attachments = mutableListOf<MmsAttachment>()
        val cursor = resolver.query(
            Uri.parse("content://mms/part"),
            arrayOf("_id", "ct", "name", "fn"),
            "mid = ?",
            arrayOf(mmsId.toString()),
            null
        ) ?: return emptyList()

        cursor.use {
            while (it.moveToNext()) {
                val partId = it.getLong(0)
                val contentType = it.getString(1) ?: ""
                val fileName = it.getString(2) ?: it.getString(3)

                if (contentType == "text/plain" || contentType == "application/smil") continue

                val partUri = "content://mms/part/$partId"
                attachments.add(
                    MmsAttachment(
                        id = partId,
                        contentType = contentType,
                        filePath = partUri,
                        data = null,
                        fileName = fileName
                    )
                )
            }
        }

        return attachments
    }

    private fun cacheMmsOverride(message: SmsMessage) {
        if (!message.isMms) return
        if (message.id <= 0) return
        if (message.mmsAttachments.isEmpty() && message.body.isBlank()) return

        mmsOverrides[message.id] = message
        viewModelScope.launch(Dispatchers.IO) {
            mmsCache.cacheMessage(message)
        }
    }

    private fun applyMmsOverrides(messages: List<SmsMessage>, loadFromDisk: Boolean = false): List<SmsMessage> {
        return messages.map { message ->
            if (!message.isMms || message.id <= 0) {
                return@map message
            }

            var body = message.body
            var attachments = message.mmsAttachments

            // Check memory cache first (fast)
            val override = mmsOverrides[message.id]
            if (override != null) {
                val needsBody = body.isBlank() && override.body.isNotBlank()
                val needsAttachments = attachments.isEmpty() && override.mmsAttachments.isNotEmpty()

                if (needsBody) {
                    body = override.body
                }
                if (needsAttachments) {
                    attachments = override.mmsAttachments
                }

                if (!needsBody && !needsAttachments) {
                    if (message.body.isNotBlank() || message.mmsAttachments.isNotEmpty()) {
                        mmsOverrides.remove(message.id)
                    }
                }
            }

            // Only check disk cache if explicitly requested (slow, do in background)
            if (loadFromDisk) {
                if (body.isBlank()) {
                    mmsCache.loadBody(message.id)?.let { cachedBody ->
                        body = cachedBody
                    }
                }
                if (attachments.isEmpty()) {
                    val cachedAttachments = mmsCache.loadAttachments(message.id)
                    if (cachedAttachments.isNotEmpty()) {
                        attachments = cachedAttachments
                    }
                }
            }

            if (body == message.body && attachments == message.mmsAttachments) {
                message
            } else {
                message.copy(
                    body = body,
                    mmsAttachments = attachments,
                    isMms = true
                )
            }
        }
    }

    fun refreshDeals(onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = DealsRepository(getApplication()).refreshDeals()
            onDone(ok)
            // Force reload conversations, including SyncFlow Deals
            loadConversations()
        }
    }


}
