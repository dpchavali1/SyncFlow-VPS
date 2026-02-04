package com.phoneintegration.app.usage

import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.tasks.await
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

class UsageTracker(private val database: FirebaseDatabase) {
    companion object {
        const val REASON_TRIAL_EXPIRED = "trial_expired"
        const val REASON_MONTHLY_LIMIT = "monthly_quota"
        const val REASON_STORAGE_LIMIT = "storage_quota"

        private const val USERS_PATH = "users"
        private const val USAGE_PATH = "usage"

        private const val TRIAL_DAYS = 7 // 7 day trial
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

        // Trial/Free tier: 500MB upload/month, 100MB storage
        private const val TRIAL_MONTHLY_UPLOAD_BYTES = 500L * 1024L * 1024L
        private const val TRIAL_STORAGE_BYTES = 100L * 1024L * 1024L

        // Paid tier: 10GB upload/month, 2GB storage
        private const val PAID_MONTHLY_UPLOAD_BYTES = 10L * 1024L * 1024L * 1024L
        private const val PAID_STORAGE_BYTES = 2L * 1024L * 1024L * 1024L
    }

    suspend fun isUploadAllowed(
        userId: String,
        bytes: Long,
        countsTowardStorage: Boolean
    ): UsageCheck {
        if (bytes <= 0L) return UsageCheck(true)

        val usageRef = database.reference
            .child(USERS_PATH)
            .child(userId)
            .child(USAGE_PATH)

        val snapshot = usageRef.get().await()
        val planRaw = snapshot.child("plan").getValue(String::class.java)
        val planExpiresAt = snapshot.child("planExpiresAt").getValue(Long::class.java)
        val now = System.currentTimeMillis()

        val isPaid = isPaidPlan(planRaw, planExpiresAt, now)

        if (!isPaid) {
            val trialStart = snapshot.child("trialStartedAt").getValue(Long::class.java)
                ?: run {
                    usageRef.child("trialStartedAt").setValue(ServerValue.TIMESTAMP).await()
                    now
                }
            if (now - trialStart > TRIAL_DAYS * MILLIS_PER_DAY) {
                return UsageCheck(false, REASON_TRIAL_EXPIRED)
            }
        }

        val monthlyLimit = if (isPaid) PAID_MONTHLY_UPLOAD_BYTES else TRIAL_MONTHLY_UPLOAD_BYTES
        val storageLimit = if (isPaid) PAID_STORAGE_BYTES else TRIAL_STORAGE_BYTES

        val periodKey = currentPeriodKey()
        val periodSnapshot = snapshot.child("monthly").child(periodKey)
        val currentUpload = periodSnapshot.child("uploadBytes").getValue(Long::class.java) ?: 0L
        if (currentUpload + bytes > monthlyLimit) {
            return UsageCheck(false, REASON_MONTHLY_LIMIT)
        }

        if (countsTowardStorage) {
            val storageBytes = snapshot.child("storageBytes").getValue(Long::class.java) ?: 0L
            if (storageBytes + bytes > storageLimit) {
                return UsageCheck(false, REASON_STORAGE_LIMIT)
            }
        }

        return UsageCheck(true)
    }

    suspend fun recordUpload(
        userId: String,
        bytes: Long,
        category: UsageCategory,
        countsTowardStorage: Boolean
    ) {
        if (bytes <= 0L) return

        val periodKey = currentPeriodKey()
        val updates = mutableMapOf<String, Any>(
            "$USAGE_PATH/monthly/$periodKey/uploadBytes" to ServerValue.increment(bytes),
            "$USAGE_PATH/lastUpdatedAt" to ServerValue.TIMESTAMP
        )

        when (category) {
            UsageCategory.MMS -> {
                updates["$USAGE_PATH/monthly/$periodKey/mmsBytes"] = ServerValue.increment(bytes)
            }
            UsageCategory.FILE -> {
                updates["$USAGE_PATH/monthly/$periodKey/fileBytes"] = ServerValue.increment(bytes)
            }
        }

        if (countsTowardStorage) {
            updates["$USAGE_PATH/storageBytes"] = ServerValue.increment(bytes)
        }

        database.reference
            .child(USERS_PATH)
            .child(userId)
            .updateChildren(updates)
            .await()
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
    suspend fun getUsageStats(userId: String): UsageStats {
        val usageRef = database.reference
            .child(USERS_PATH)
            .child(userId)
            .child(USAGE_PATH)

        val snapshot = usageRef.get().await()
        val planRaw = snapshot.child("plan").getValue(String::class.java)
        val planExpiresAt = snapshot.child("planExpiresAt").getValue(Long::class.java)
        val now = System.currentTimeMillis()

        val isPaid = isPaidPlan(planRaw, planExpiresAt, now)
        val trialStart = snapshot.child("trialStartedAt").getValue(Long::class.java) ?: now

        val trialElapsedDays = ((now - trialStart) / MILLIS_PER_DAY).toInt()
        val trialDaysRemaining = maxOf(0, TRIAL_DAYS - trialElapsedDays)
        val isTrialExpired = !isPaid && trialElapsedDays > TRIAL_DAYS

        val monthlyLimit = if (isPaid) PAID_MONTHLY_UPLOAD_BYTES else TRIAL_MONTHLY_UPLOAD_BYTES
        val storageLimit = if (isPaid) PAID_STORAGE_BYTES else TRIAL_STORAGE_BYTES

        val periodKey = currentPeriodKey()
        val periodSnapshot = snapshot.child("monthly").child(periodKey)
        val currentUpload = periodSnapshot.child("uploadBytes").getValue(Long::class.java) ?: 0L
        val storageBytes = snapshot.child("storageBytes").getValue(Long::class.java) ?: 0L

        return UsageStats(
            monthlyUploadBytes = currentUpload,
            monthlyLimitBytes = monthlyLimit,
            storageBytes = storageBytes,
            storageLimitBytes = storageLimit,
            isPaid = isPaid,
            isTrialExpired = isTrialExpired,
            trialDaysRemaining = trialDaysRemaining,
            periodKey = periodKey
        )
    }

    /**
     * Clear all synced messages from Firebase (for resync)
     */
    suspend fun clearSyncedMessages(userId: String) {
        val messagesRef = database.reference
            .child(USERS_PATH)
            .child(userId)
            .child("messages")

        messagesRef.removeValue().await()
    }

    /**
     * Clear storage usage counter (after deleting attachments)
     */
    suspend fun resetStorageUsage(userId: String) {
        val usageRef = database.reference
            .child(USERS_PATH)
            .child(userId)
            .child(USAGE_PATH)

        usageRef.child("storageBytes").setValue(0L).await()
    }

    /**
     * Reset monthly upload counter
     */
    suspend fun resetMonthlyUsage(userId: String) {
        val periodKey = currentPeriodKey()
        val usageRef = database.reference
            .child(USERS_PATH)
            .child(userId)
            .child(USAGE_PATH)
            .child("monthly")
            .child(periodKey)

        usageRef.removeValue().await()
    }
}
