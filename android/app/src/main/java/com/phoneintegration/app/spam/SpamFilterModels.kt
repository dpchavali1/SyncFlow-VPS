package com.phoneintegration.app.spam

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Spam detection result for a message
 */
data class SpamCheckResult(
    val isSpam: Boolean,
    val confidence: Float,
    val reasons: List<SpamReason>,
    val threatTypes: Set<ThreatType>
)

data class SpamReason(
    val type: ReasonType,
    val description: String,
    val matchedPattern: String? = null
)

enum class ReasonType {
    MALICIOUS_URL,
    SPAM_PATTERN,
    BLOCKED_SENDER,
    ML_CLASSIFICATION,
    SUSPICIOUS_KEYWORDS,
    SHORT_URL,
    KNOWN_SCAM
}

enum class ThreatType {
    PHISHING,
    MALWARE,
    SCAM,
    FRAUD,
    PROMOTIONAL_SPAM,
    LOTTERY_SCAM,
    BANKING_FRAUD,
    OTP_THEFT,
    UNKNOWN
}

/**
 * Filter manifest for updates
 */
data class FilterManifest(
    val version: String,
    val lastUpdated: Long,
    val components: Map<String, FilterComponent>
)

data class FilterComponent(
    val version: String,
    val url: String,
    val size: Long,
    val checksum: String,
    val minAppVersion: String? = null
)

/**
 * URL blocklist entry
 */
data class BlockedUrl(
    val url: String,
    val threatType: ThreatType,
    val source: String,
    val dateAdded: Long
)

/**
 * Spam pattern rule
 */
data class SpamPattern(
    val id: String,
    val pattern: String,
    val isRegex: Boolean,
    val threatType: ThreatType,
    val confidence: Float,
    val description: String
)

/**
 * Blocked sender entry
 */
data class BlockedSender(
    val address: String,
    val reason: String,
    val reportCount: Int,
    val dateAdded: Long
)

/**
 * Room entity for caching filter data
 */
@Entity(tableName = "url_blocklist")
data class UrlBlocklistEntity(
    @PrimaryKey val urlHash: String,
    val url: String,
    val threatType: String,
    val source: String,
    val dateAdded: Long
)

@Entity(tableName = "spam_patterns")
data class SpamPatternEntity(
    @PrimaryKey val id: String,
    val pattern: String,
    val isRegex: Boolean,
    val threatType: String,
    val confidence: Float,
    val description: String
)

@Entity(tableName = "blocked_senders")
data class BlockedSenderEntity(
    @PrimaryKey val address: String,
    val reason: String,
    val reportCount: Int,
    val dateAdded: Long
)

@Entity(tableName = "filter_metadata")
data class FilterMetadataEntity(
    @PrimaryKey val componentName: String,
    val version: String,
    val lastUpdated: Long,
    val checksum: String
)

/**
 * Scan progress and results
 */
data class ScanProgress(
    val totalMessages: Int,
    val scannedMessages: Int,
    val spamFound: Int,
    val isComplete: Boolean
)

data class ScanResult(
    val totalScanned: Int,
    val spamCount: Int,
    val phishingCount: Int,
    val scamCount: Int,
    val flaggedMessages: List<FlaggedMessage>
)

data class FlaggedMessage(
    val messageId: String,
    val address: String,
    val body: String,
    val date: Long,
    val result: SpamCheckResult
)

/**
 * Filter statistics
 */
data class FilterStats(
    val urlBlocklistCount: Int,
    val patternCount: Int,
    val blockedSenderCount: Int,
    val modelVersion: String?,
    val lastUpdated: Long,
    val messagesScanned: Int,
    val spamBlocked: Int
)
