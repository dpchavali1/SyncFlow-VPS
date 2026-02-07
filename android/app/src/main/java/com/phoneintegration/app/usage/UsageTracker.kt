package com.phoneintegration.app.usage

import android.content.Context
import android.util.Log
import com.phoneintegration.app.vps.VPSClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class UsageCheck(val allowed: Boolean, val reason: String? = null)

data class UsageStats(
    val monthlyUploadBytes: Long = 0L,
    val monthlyLimitBytes: Long = 0L,
    val storageBytes: Long = 0L,
    val storageLimitBytes: Long = 0L,
    val isPaid: Boolean = false,
    val isTrialExpired: Boolean = false,
    val trialDaysRemaining: Int = 0,
    val periodKey: String = ""
) {
    val monthlyUsagePercent: Float
        get() = if (monthlyLimitBytes > 0) (monthlyUploadBytes.toFloat() / monthlyLimitBytes * 100f) else 0f

    val storageUsagePercent: Float
        get() = if (storageLimitBytes > 0) (storageBytes.toFloat() / storageLimitBytes * 100f) else 0f

    val isMonthlyLimitExceeded: Boolean
        get() = monthlyUploadBytes >= monthlyLimitBytes

    val isMonthlyLimitNear: Boolean
        get() = monthlyUsagePercent >= 80f && !isMonthlyLimitExceeded

    val isStorageLimitExceeded: Boolean
        get() = storageBytes >= storageLimitBytes

    val formattedMonthlyUsage: String
        get() = "${formatBytes(monthlyUploadBytes)} / ${formatBytes(monthlyLimitBytes)}"

    val formattedStorageUsage: String
        get() = "${formatBytes(storageBytes)} / ${formatBytes(storageLimitBytes)}"

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024L * 1024L -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}

enum class UsageCategory {
    MMS,
    FILE
}

/**
 * Usage Tracker - VPS Backend Only
 *
 * Tracks upload and storage usage via VPS API instead of Firebase.
 */
class UsageTracker(private val context: Context) {
    companion object {
        private const val TAG = "UsageTracker"

        const val REASON_TRIAL_EXPIRED = "trial_expired"
        const val REASON_MONTHLY_LIMIT = "monthly_quota"
        const val REASON_STORAGE_LIMIT = "storage_quota"

        private const val TRIAL_DAYS = 7 // 7 day trial
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

        // Trial/Free tier: 500MB upload/month, 100MB storage
        private const val TRIAL_MONTHLY_UPLOAD_BYTES = 500L * 1024L * 1024L
        private const val TRIAL_STORAGE_BYTES = 100L * 1024L * 1024L

        // Paid tier: 10GB upload/month, 2GB storage
        private const val PAID_MONTHLY_UPLOAD_BYTES = 10L * 1024L * 1024L * 1024L
        private const val PAID_STORAGE_BYTES = 2L * 1024L * 1024L * 1024L
    }

    private val vpsClient = VPSClient.getInstance(context)

    suspend fun isUploadAllowed(
        userId: String,
        bytes: Long,
        countsTowardStorage: Boolean
    ): UsageCheck = withContext(Dispatchers.IO) {
        if (bytes <= 0L) return@withContext UsageCheck(true)

        try {
            val usageResponse = vpsClient.getUserUsage()
            val usage = usageResponse.usage ?: return@withContext UsageCheck(true)

            val now = System.currentTimeMillis()
            val isPaid = isPaidPlan(usage.plan, usage.planExpiresAt, now)

            if (!isPaid) {
                val trialStart = usage.trialStartedAt ?: now
                if (now - trialStart > TRIAL_DAYS * MILLIS_PER_DAY) {
                    return@withContext UsageCheck(false, REASON_TRIAL_EXPIRED)
                }
            }

            val monthlyLimit = if (isPaid) PAID_MONTHLY_UPLOAD_BYTES else TRIAL_MONTHLY_UPLOAD_BYTES
            val storageLimit = if (isPaid) PAID_STORAGE_BYTES else TRIAL_STORAGE_BYTES

            if (usage.monthlyUploadBytes + bytes > monthlyLimit) {
                return@withContext UsageCheck(false, REASON_MONTHLY_LIMIT)
            }

            if (countsTowardStorage) {
                if (usage.storageBytes + bytes > storageLimit) {
                    return@withContext UsageCheck(false, REASON_STORAGE_LIMIT)
                }
            }

            UsageCheck(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking upload allowed: ${e.message}")
            // Allow upload if we can't check (fail open for better UX)
            UsageCheck(true)
        }
    }

    suspend fun recordUpload(
        userId: String,
        bytes: Long,
        category: UsageCategory,
        countsTowardStorage: Boolean
    ) = withContext(Dispatchers.IO) {
        if (bytes <= 0L) return@withContext

        try {
            vpsClient.recordUsage(
                bytes = bytes,
                category = category.name.lowercase(),
                countsTowardStorage = countsTowardStorage
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error recording upload: ${e.message}")
        }
    }

    private fun currentPeriodKey(): String {
        val formatter = SimpleDateFormat("yyyyMM", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date())
    }

    private fun isPaidPlan(planRaw: String?, planExpiresAt: Long?, now: Long): Boolean {
        val normalized = planRaw?.lowercase(Locale.US) ?: return false
        if (normalized == "lifetime" || normalized == "3year") {
            return true
        }
        if (normalized == "monthly" || normalized == "yearly" || normalized == "paid") {
            return planExpiresAt?.let { it > now } ?: true
        }
        return false
    }

    /**
     * Get current usage statistics for display in UI
     */
    suspend fun getUsageStats(userId: String): UsageStats = withContext(Dispatchers.IO) {
        try {
            val usageResponse = vpsClient.getUserUsage()
            val usage = usageResponse.usage ?: return@withContext UsageStats()

            val now = System.currentTimeMillis()
            val isPaid = isPaidPlan(usage.plan, usage.planExpiresAt, now)
            val trialStart = usage.trialStartedAt ?: now

            val trialElapsedDays = ((now - trialStart) / MILLIS_PER_DAY).toInt()
            val trialDaysRemaining = maxOf(0, TRIAL_DAYS - trialElapsedDays)
            val isTrialExpired = !isPaid && trialElapsedDays > TRIAL_DAYS

            val monthlyLimit = if (isPaid) PAID_MONTHLY_UPLOAD_BYTES else TRIAL_MONTHLY_UPLOAD_BYTES
            val storageLimit = if (isPaid) PAID_STORAGE_BYTES else TRIAL_STORAGE_BYTES

            UsageStats(
                monthlyUploadBytes = usage.monthlyUploadBytes,
                monthlyLimitBytes = monthlyLimit,
                storageBytes = usage.storageBytes,
                storageLimitBytes = storageLimit,
                isPaid = isPaid,
                isTrialExpired = isTrialExpired,
                trialDaysRemaining = trialDaysRemaining,
                periodKey = currentPeriodKey()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting usage stats: ${e.message}")
            UsageStats()
        }
    }

    /**
     * Clear all synced messages (for resync)
     * Note: In VPS mode, this is handled by the server
     */
    suspend fun clearSyncedMessages(userId: String) = withContext(Dispatchers.IO) {
        try {
            vpsClient.clearSyncedMessages()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing synced messages: ${e.message}")
        }
    }

    /**
     * Clear storage usage counter (after deleting attachments)
     */
    suspend fun resetStorageUsage(userId: String) = withContext(Dispatchers.IO) {
        try {
            vpsClient.resetStorageUsage()
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting storage usage: ${e.message}")
        }
    }

    /**
     * Reset monthly upload counter
     */
    suspend fun resetMonthlyUsage(userId: String) = withContext(Dispatchers.IO) {
        try {
            vpsClient.resetMonthlyUsage()
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting monthly usage: ${e.message}")
        }
    }
}
