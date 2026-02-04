package com.phoneintegration.app.spam

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.regex.Pattern

/**
 * Main spam detection service that coordinates all spam checking components
 */
class SpamFilterService(private val context: Context) {

    companion object {
        private const val TAG = "SpamFilterService"

        @Volatile
        private var INSTANCE: SpamFilterService? = null

        fun getInstance(context: Context): SpamFilterService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SpamFilterService(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private var patternMatcher: SpamPatternMatcher? = null
    private var urlChecker: UrlBlocklistManager? = null
    private var filterUpdateManager: FilterUpdateManager? = null
    private var mlClassifier: SpamMLClassifier? = null
    private var isInitialized = false

    // User whitelist - addresses marked as "not spam" by user
    private val userWhitelistPrefs by lazy {
        context.getSharedPreferences("spam_user_whitelist", Context.MODE_PRIVATE)
    }
    private val userWhitelist = mutableSetOf<String>()

    // Blocked sender management (user-marked spam) - must be declared before init block
    private val blockedSendersPrefs by lazy {
        context.getSharedPreferences("spam_blocked_senders", Context.MODE_PRIVATE)
    }
    private val blockedSenders = mutableSetOf<String>()

    private val _scanProgress = MutableStateFlow<ScanProgress?>(null)
    val scanProgress: StateFlow<ScanProgress?> = _scanProgress

    private val _filterStats = MutableStateFlow<FilterStats?>(null)
    val filterStats: StateFlow<FilterStats?> = _filterStats

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Desktop sync service for cloud sync
    private val syncService by lazy {
        com.phoneintegration.app.desktop.DesktopSyncService(context)
    }

    init {
        Log.w(TAG, "SPAM_INIT: SpamFilterService created")
        // Load user whitelist and blocklist from preferences
        loadUserWhitelist()
        loadBlockedSenders()

        // Listen for cloud changes (from macOS)
        startCloudSync()

        // Initialize components lazily in background to avoid blocking UI
        scope.launch {
            Log.w(TAG, "SPAM_INIT: Starting component initialization")
            initializeComponents()
        }
    }

    private fun loadUserWhitelist() {
        val saved = userWhitelistPrefs.getStringSet("whitelisted_addresses", emptySet()) ?: emptySet()
        userWhitelist.clear()
        userWhitelist.addAll(saved.map { normalizeAddress(it) })
    }

    /**
     * Add an address to the user whitelist (marked as "not spam")
     * This address will be excluded from future spam scans
     */
    fun addToWhitelist(address: String) {
        val normalized = normalizeAddress(address)
        userWhitelist.add(normalized)
        // Also remove from blocklist if present
        if (blockedSenders.remove(normalized)) {
            saveBlockedSenders()
        }
        saveUserWhitelist()
    }

    /**
     * Remove an address from the user whitelist
     */
    fun removeFromWhitelist(address: String) {
        val normalized = normalizeAddress(address)
        userWhitelist.remove(normalized)
        saveUserWhitelist()
    }

    /**
     * Check if an address is in the user whitelist
     */
    fun isWhitelisted(address: String): Boolean {
        return userWhitelist.contains(normalizeAddress(address))
    }

    /**
     * Get all whitelisted addresses
     */
    fun getWhitelistedAddresses(): Set<String> {
        return userWhitelist.toSet()
    }

    private fun saveUserWhitelist() {
        userWhitelistPrefs.edit()
            .putStringSet("whitelisted_addresses", userWhitelist.toSet())
            .apply()

        // Sync to cloud for cross-device sync
        if (com.phoneintegration.app.desktop.DesktopSyncService.hasPairedDevices(context)) {
            scope.launch {
                try {
                    syncService.syncWhitelist(userWhitelist.toSet())
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to sync whitelist to cloud", e)
                }
            }
        }
    }

    private fun normalizeAddress(address: String): String {
        // Normalize phone numbers by removing non-digits, keeping + prefix
        return if (address.any { it.isDigit() }) {
            address.filter { it.isDigit() || it == '+' }.takeLast(10)  // Last 10 digits for comparison
        } else {
            address.lowercase().trim()
        }
    }

    /**
     * Start listening for whitelist/blocklist changes from cloud (macOS sync)
     */
    private fun startCloudSync() {
        // Only start if user has paired devices
        if (!com.phoneintegration.app.desktop.DesktopSyncService.hasPairedDevices(context)) {
            return
        }

        // Listen for whitelist changes from cloud
        scope.launch {
            try {
                syncService.listenForWhitelist().collect { cloudWhitelist ->
                    // Merge cloud whitelist with local (cloud takes precedence for new entries)
                    val newAddresses = cloudWhitelist.filter { !userWhitelist.contains(it) }
                    if (newAddresses.isNotEmpty()) {
                        userWhitelist.addAll(newAddresses)
                        // Remove from blocklist if present
                        newAddresses.forEach { blockedSenders.remove(it) }
                        // Save locally (but don't re-sync to avoid loop)
                        userWhitelistPrefs.edit()
                            .putStringSet("whitelisted_addresses", userWhitelist.toSet())
                            .apply()
                        blockedSendersPrefs.edit()
                            .putStringSet("blocked_addresses", blockedSenders.toSet())
                            .apply()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error listening for whitelist changes", e)
            }
        }

        // Listen for blocklist changes from cloud
        scope.launch {
            try {
                syncService.listenForBlocklist().collect { cloudBlocklist ->
                    // Merge cloud blocklist with local
                    val newAddresses = cloudBlocklist.filter { !blockedSenders.contains(it) }
                    if (newAddresses.isNotEmpty()) {
                        blockedSenders.addAll(newAddresses)
                        // Remove from whitelist if present
                        newAddresses.forEach { userWhitelist.remove(it) }
                        // Save locally (but don't re-sync to avoid loop)
                        blockedSendersPrefs.edit()
                            .putStringSet("blocked_addresses", blockedSenders.toSet())
                            .apply()
                        userWhitelistPrefs.edit()
                            .putStringSet("whitelisted_addresses", userWhitelist.toSet())
                            .apply()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error listening for blocklist changes", e)
            }
        }
    }

    private fun initializeComponents() {
        if (isInitialized) return

        try {
            patternMatcher = SpamPatternMatcher()
            // Load patterns from assets
            try {
                val patternsJson = context.assets.open("spam_patterns.json").bufferedReader().use { it.readText() }
                patternMatcher?.loadPatternsFromJson(patternsJson)
                Log.w(TAG, "SPAM_INIT: Loaded ${patternMatcher?.getPatternCount()} patterns from assets")
            } catch (e: Exception) {
                Log.e(TAG, "SPAM_INIT: Failed to load patterns from assets", e)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Pattern matcher failed: ${e.message}")
        }

        try {
            urlChecker = UrlBlocklistManager(context)
        } catch (e: Exception) {
            Log.w(TAG, "URL checker failed: ${e.message}")
        }

        try {
            filterUpdateManager = FilterUpdateManager(context)
        } catch (e: Exception) {
            Log.w(TAG, "Filter update manager failed: ${e.message}")
        }

        // ML classifier is optional
        try {
            mlClassifier = SpamMLClassifier(context)
        } catch (e: Exception) {
            Log.w(TAG, "ML classifier not available: ${e.message}")
        }

        isInitialized = true
        updateFilterStats()
    }

    private fun ensureInitialized() {
        if (!isInitialized) {
            initializeComponents()
        }
    }

    /**
     * Check a single message for spam
     * @param address The sender address/phone number
     * @param body The message body text
     * @param isRead Whether the message has been read (unread from unknown senders is more suspicious)
     * @param messageAgeHours How old the message is in hours (old unread = more suspicious)
     * @param isFromContact Whether the sender is in the user's contacts
     */
    fun checkMessage(
        address: String,
        body: String,
        isRead: Boolean = true,
        messageAgeHours: Long = 0,
        isFromContact: Boolean = false
    ): SpamCheckResult {
        // CONTACTS CHECK FIRST - Messages from contacts are NEVER spam
        if (isFromContact) {
            return SpamCheckResult(
                isSpam = false,
                confidence = 0f,
                reasons = emptyList(),
                threatTypes = emptySet()
            )
        }

        // USER WHITELIST CHECK - User marked as "not spam"
        if (isWhitelisted(address)) {
            return SpamCheckResult(
                isSpam = false,
                confidence = 0f,
                reasons = emptyList(),
                threatTypes = emptySet()
            )
        }

        // TRUSTED SENDERS CHECK - Banks and verified senders are NEVER spam
        if (isTrustedBankSender(address)) {
            return SpamCheckResult(
                isSpam = false,
                confidence = 0f,
                reasons = emptyList(),
                threatTypes = emptySet()
            )
        }

        ensureInitialized()

        val reasons = mutableListOf<SpamReason>()
        val threatTypes = mutableSetOf<ThreatType>()
        var maxConfidence = 0f

        // 1. Check sender blocklist
        if (isBlockedSender(address)) {
            reasons.add(SpamReason(
                type = ReasonType.BLOCKED_SENDER,
                description = "Sender is in blocklist"
            ))
            threatTypes.add(ThreatType.UNKNOWN)
            maxConfidence = maxOf(maxConfidence, 0.95f)
        }

        // 2. Check spam patterns (if available)
        patternMatcher?.let { matcher ->
            val patternResults = matcher.checkPatterns(body)
            for (result in patternResults) {
                reasons.add(SpamReason(
                    type = ReasonType.SPAM_PATTERN,
                    description = result.description,
                    matchedPattern = result.matchedPattern
                ))
                threatTypes.add(result.threatType)
                maxConfidence = maxOf(maxConfidence, result.confidence)
            }
        }

        // 3. Extract and check URLs (if available)
        val urls = extractUrls(body)
        urlChecker?.let { checker ->
            for (url in urls) {
                val urlResult = checker.checkUrl(url)
                if (urlResult != null) {
                    reasons.add(SpamReason(
                        type = ReasonType.MALICIOUS_URL,
                        description = "Malicious URL detected: ${urlResult.threatType}",
                        matchedPattern = url
                    ))
                    threatTypes.add(urlResult.threatType)
                    maxConfidence = maxOf(maxConfidence, 0.98f)
                }
            }
        }

        // Check for suspicious short URLs
        for (url in urls) {
            if (isShortUrl(url)) {
                reasons.add(SpamReason(
                    type = ReasonType.SHORT_URL,
                    description = "Suspicious shortened URL",
                    matchedPattern = url
                ))
                maxConfidence = maxOf(maxConfidence, 0.6f)
            }
        }

        // 4. ML classification (if available)
        mlClassifier?.let { classifier ->
            val mlResult = classifier.classify(body)
            if (mlResult.isSpam && mlResult.confidence > 0.7f) {
                reasons.add(SpamReason(
                    type = ReasonType.ML_CLASSIFICATION,
                    description = "AI detected spam with ${(mlResult.confidence * 100).toInt()}% confidence"
                ))
                maxConfidence = maxOf(maxConfidence, mlResult.confidence)
            }
        }

        // 5. Check for suspicious keywords
        val keywordResult = checkSuspiciousKeywords(body)
        if (keywordResult != null) {
            reasons.add(keywordResult)
            maxConfidence = maxOf(maxConfidence, 0.7f)
        }

        // 6. Check unread message heuristic
        // Persistently unread messages from non-contacts are suspicious
        // Spam/bulk SMS often remain unread because users recognize them as spam
        if (!isRead && !isFromContact) {
            if (messageAgeHours > 72) {
                // Very old unread messages (>3 days) are highly suspicious
                reasons.add(SpamReason(
                    type = ReasonType.SUSPICIOUS_KEYWORDS,
                    description = "Very old unread message from unknown sender (${messageAgeHours}h)"
                ))
                // High confidence boost - if user hasn't read it in 3+ days, likely spam
                maxConfidence = maxOf(maxConfidence, 0.6f + (reasons.size * 0.05f).coerceAtMost(0.2f))
            } else if (messageAgeHours > 24) {
                // Old unread messages (>24 hours) are more suspicious
                reasons.add(SpamReason(
                    type = ReasonType.SUSPICIOUS_KEYWORDS,
                    description = "Old unread message from unknown sender (${messageAgeHours}h)"
                ))
                // Moderate boost - combine with other signals
                maxConfidence = maxOf(maxConfidence, 0.5f + (reasons.size * 0.05f).coerceAtMost(0.15f))
            } else if (messageAgeHours > 6) {
                // Messages unread for 6+ hours from unknown senders
                reasons.add(SpamReason(
                    type = ReasonType.SUSPICIOUS_KEYWORDS,
                    description = "Unread message from unknown sender (${messageAgeHours}h)"
                ))
                maxConfidence = maxOf(maxConfidence, 0.35f + (reasons.size * 0.05f).coerceAtMost(0.1f))
            }
        }

        val isSpam = maxConfidence >= 0.6f || reasons.isNotEmpty()

        return SpamCheckResult(
            isSpam = isSpam,
            confidence = maxConfidence,
            reasons = reasons,
            threatTypes = threatTypes
        )
    }

    /**
     * Scan all existing messages
     */
    suspend fun scanAllMessages(
        messages: List<MessageToScan>,
        onProgress: ((ScanProgress) -> Unit)? = null
    ): ScanResult = withContext(Dispatchers.Default) {
        val flaggedMessages = mutableListOf<FlaggedMessage>()
        var phishingCount = 0
        var scamCount = 0

        messages.forEachIndexed { index, message ->
            val result = checkMessage(message.address, message.body)

            if (result.isSpam) {
                flaggedMessages.add(FlaggedMessage(
                    messageId = message.id,
                    address = message.address,
                    body = message.body,
                    date = message.date,
                    result = result
                ))

                if (result.threatTypes.contains(ThreatType.PHISHING)) phishingCount++
                if (result.threatTypes.contains(ThreatType.SCAM)) scamCount++
            }

            val progress = ScanProgress(
                totalMessages = messages.size,
                scannedMessages = index + 1,
                spamFound = flaggedMessages.size,
                isComplete = index == messages.lastIndex
            )
            _scanProgress.value = progress
            onProgress?.invoke(progress)

            // Yield to prevent blocking
            if (index % 50 == 0) yield()
        }

        _scanProgress.value = null

        ScanResult(
            totalScanned = messages.size,
            spamCount = flaggedMessages.size,
            phishingCount = phishingCount,
            scamCount = scamCount,
            flaggedMessages = flaggedMessages
        )
    }

    /**
     * Update all filters from remote
     */
    suspend fun updateFilters(onProgress: ((String, Int) -> Unit)? = null): Boolean {
        ensureInitialized()

        return try {
            filterUpdateManager?.updateAllFilters { component, progress ->
                onProgress?.invoke(component, progress)
            }

            // Reload components
            urlChecker?.reloadBlocklist()
            patternMatcher?.reloadPatterns(context)

            // Try to reload ML model if updated
            try {
                mlClassifier = SpamMLClassifier(context)
            } catch (_: Exception) { }

            updateFilterStats()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update filters", e)
            false
        }
    }

    /**
     * Check for filter updates without downloading
     */
    suspend fun checkForUpdates(): FilterUpdateInfo {
        ensureInitialized()
        return filterUpdateManager?.checkForUpdates() ?: FilterUpdateInfo(
            hasUpdates = false,
            components = emptyList(),
            totalSize = 0
        )
    }

    /**
     * Get current filter statistics
     */
    fun getFilterStats(): FilterStats {
        return FilterStats(
            urlBlocklistCount = urlChecker?.getBlocklistSize() ?: 0,
            patternCount = patternMatcher?.getPatternCount() ?: 0,
            blockedSenderCount = getBlockedSenderCount(),
            modelVersion = mlClassifier?.getModelVersion(),
            lastUpdated = filterUpdateManager?.getLastUpdateTime() ?: 0L,
            messagesScanned = getMessagesScannedCount(),
            spamBlocked = getSpamBlockedCount()
        )
    }

    private fun updateFilterStats() {
        _filterStats.value = getFilterStats()
    }

    // URL extraction
    private val urlPattern = Pattern.compile(
        "(https?://[\\w\\-._~:/?#\\[\\]@!\$&'()*+,;=%]+)" +
        "|(www\\.[\\w\\-._~:/?#\\[\\]@!\$&'()*+,;=%]+)" +
        "|([\\w\\-]+\\.(com|org|net|io|co|in|uk|ly|me|info|biz|xyz|top|click|link|gq|ml|ga|cf|tk)[/\\w\\-._~:/?#\\[\\]@!\$&'()*+,;=%]*)",
        Pattern.CASE_INSENSITIVE
    )

    private fun extractUrls(text: String): List<String> {
        val urls = mutableListOf<String>()
        val matcher = urlPattern.matcher(text)
        while (matcher.find()) {
            matcher.group()?.let { urls.add(it) }
        }
        return urls
    }

    // Short URL domains
    private val shortUrlDomains = setOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "is.gd",
        "buff.ly", "adf.ly", "j.mp", "tr.im", "cli.gs", "short.to",
        "budurl.com", "ping.fm", "post.ly", "Just.as", "bkite.com",
        "snipr.com", "fic.kr", "loopt.us", "doiop.com", "twitthis.com",
        "htxt.it", "AltURL.com", "RedirX.com", "DigBig.com", "short.ie",
        "cutt.ly", "rb.gy", "shorturl.at", "tiny.cc"
    )

    private fun isShortUrl(url: String): Boolean {
        val lowerUrl = url.lowercase()
        return shortUrlDomains.any { lowerUrl.contains(it) }
    }

    // Suspicious keywords check
    private fun checkSuspiciousKeywords(body: String): SpamReason? {
        val lowerBody = body.lowercase()

        // Lottery/Prize scams
        val lotteryKeywords = listOf(
            "you have won", "winner", "lottery", "prize", "claim your",
            "congratulations", "lucky winner", "selected for", "jackpot"
        )
        if (lotteryKeywords.any { lowerBody.contains(it) }) {
            return SpamReason(
                type = ReasonType.SUSPICIOUS_KEYWORDS,
                description = "Lottery/Prize scam indicators detected"
            )
        }

        // Banking fraud
        val bankingKeywords = listOf(
            "account blocked", "verify immediately", "kyc expired", "pan card",
            "aadhar update", "account suspended", "click here to verify",
            "bank account will be", "update your kyc", "link aadhar"
        )
        if (bankingKeywords.any { lowerBody.contains(it) }) {
            return SpamReason(
                type = ReasonType.SUSPICIOUS_KEYWORDS,
                description = "Banking fraud indicators detected"
            )
        }

        // Urgency indicators combined with action requests
        val urgencyWords = listOf("urgent", "immediately", "expire", "last chance", "act now", "limited time")
        val actionWords = listOf("click", "call", "verify", "confirm", "update")

        val hasUrgency = urgencyWords.any { lowerBody.contains(it) }
        val hasAction = actionWords.any { lowerBody.contains(it) }

        if (hasUrgency && hasAction) {
            return SpamReason(
                type = ReasonType.SUSPICIOUS_KEYWORDS,
                description = "Urgency + action request pattern detected"
            )
        }

        return null
    }

    // ==========================================
    // TRUSTED SENDERS WHITELIST - Never spam
    // ==========================================
    private val trustedBankSenders = setOf(
        // Indian Banks
        "HDFCBK", "HDFC", "HDFCBANK", "ICICIB", "ICICI", "ICICIBANK",
        "SBIINB", "SBIOTP", "SBIBNK", "SBI", "AXISBK", "AXIS", "AXISBANK",
        "KOTAKB", "KOTAK", "KOTAKBANK", "PNBSMS", "PNB", "BOIIND", "BOI",
        "IDBIBK", "IDBI", "CANBNK", "CANARA", "UNIONB", "UNION",
        "BOBTXN", "BOB", "BOBSMS", "YESBK", "YESBANK", "RBLBNK", "RBL",
        "FEDERL", "FEDERAL", "SCBANK", "SCBIND", "CITIBK", "CITI",
        "HSBCBK", "HSBC", "AMEXIN", "AMEX",
        // Payment Services
        "PYTMPR", "PAYTM", "PYTM", "GPAY", "PHONPE", "PHONEPE",
        "AMAZNP", "AMAZON", "AMZN", "FLIPKT", "FLIPKART", "BHARPE",
        // Credit Cards
        "HDFCCC", "ICICCC", "SBICC",
        // Insurance & Government
        "LICIND", "LIC", "UIDAI", "EPFIND", "EPFO", "ITREFUND"
    )

    private fun isTrustedBankSender(address: String): Boolean {
        val upperSender = address.uppercase().trim()
        // Direct match
        if (trustedBankSenders.any { upperSender.contains(it) }) {
            return true
        }
        // Check XX-BANKNAME format
        val prefixPattern = Regex("""^[A-Z]{2}-(.+)$""")
        val match = prefixPattern.find(upperSender)
        if (match != null) {
            val code = match.groupValues[1]
            if (trustedBankSenders.any { code.contains(it) || it.contains(code) }) {
                return true
            }
        }
        return false
    }

    private fun loadBlockedSenders() {
        val saved = blockedSendersPrefs.getStringSet("blocked_addresses", emptySet()) ?: emptySet()
        blockedSenders.clear()
        blockedSenders.addAll(saved.map { normalizeAddress(it) })
    }

    private fun saveBlockedSenders() {
        blockedSendersPrefs.edit()
            .putStringSet("blocked_addresses", blockedSenders.toSet())
            .apply()

        // Sync to cloud for cross-device sync
        if (com.phoneintegration.app.desktop.DesktopSyncService.hasPairedDevices(context)) {
            scope.launch {
                try {
                    syncService.syncBlocklist(blockedSenders.toSet())
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to sync blocklist to cloud", e)
                }
            }
        }
    }

    private fun isBlockedSender(address: String): Boolean {
        return blockedSenders.contains(normalizeAddress(address))
    }

    /**
     * Add a sender to the blocklist (user marked as spam)
     * This sender will always be flagged as spam in future scans
     */
    fun addBlockedSender(address: String) {
        val normalized = normalizeAddress(address)
        blockedSenders.add(normalized)
        // Also remove from whitelist if present
        userWhitelist.remove(normalized)
        saveUserWhitelist()
        saveBlockedSenders()
    }

    /**
     * Remove a sender from the blocklist
     */
    fun removeBlockedSender(address: String) {
        val normalized = normalizeAddress(address)
        blockedSenders.remove(normalized)
        saveBlockedSenders()
    }

    /**
     * Check if a sender is blocked
     */
    fun isSenderBlocked(address: String): Boolean {
        return blockedSenders.contains(normalizeAddress(address))
    }

    /**
     * Get all blocked sender addresses
     */
    fun getBlockedSenders(): Set<String> {
        return blockedSenders.toSet()
    }

    private fun getBlockedSenderCount(): Int = blockedSenders.size

    // Stats tracking (would be persisted in SharedPreferences in real implementation)
    private var messagesScanned = 0
    private var spamBlocked = 0

    private fun getMessagesScannedCount(): Int = messagesScanned
    private fun getSpamBlockedCount(): Int = spamBlocked

    fun incrementStats(scanned: Int, blocked: Int) {
        messagesScanned += scanned
        spamBlocked += blocked
        updateFilterStats()
    }
}

/**
 * Message data for scanning
 */
data class MessageToScan(
    val id: String,
    val address: String,
    val body: String,
    val date: Long
)

/**
 * Filter update information
 */
data class FilterUpdateInfo(
    val hasUpdates: Boolean,
    val components: List<UpdateableComponent>,
    val totalSize: Long
)

data class UpdateableComponent(
    val name: String,
    val currentVersion: String,
    val newVersion: String,
    val size: Long
)
