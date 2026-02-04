package com.phoneintegration.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.ActivityCompat

/**
 * Manages detection and information about multiple SIM cards/eSIMs
 */
class SimManager(private val context: Context) {

    companion object {
        private const val TAG = "SimManager"
    }

    private val subscriptionManager: SubscriptionManager? by lazy {
        context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
    }

    private val telephonyManager: TelephonyManager by lazy {
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    }

    /**
     * Data class representing a SIM card
     */
    data class SimInfo(
        val subscriptionId: Int,
        val slotIndex: Int,
        val displayName: String,
        val carrierName: String,
        val phoneNumber: String?,
        val iccId: String?,
        val isEmbedded: Boolean,
        val isActive: Boolean
    ) {
        fun toMap(): Map<String, Any?> {
            return mapOf(
                "subscriptionId" to subscriptionId,
                "slotIndex" to slotIndex,
                "displayName" to displayName,
                "carrierName" to carrierName,
                "phoneNumber" to phoneNumber,
                "iccId" to iccId,
                "isEmbedded" to isEmbedded,
                "isActive" to isActive
            )
        }
    }

    /**
     * Get all active SIM cards (physical + eSIM)
     */
    fun getActiveSims(): List<SimInfo> {
        if (!hasRequiredPermissions()) {
            Log.e(TAG, "Missing permissions to read SIM info")
            return emptyList()
        }

        val sims = mutableListOf<SimInfo>()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                // On Android 11+, accessing activeSubscriptionInfoList may require READ_PHONE_NUMBERS
                // Wrap in try-catch to handle permission issues gracefully
                val subscriptions = try {
                    subscriptionManager?.activeSubscriptionInfoList
                } catch (e: SecurityException) {
                    Log.w(TAG, "SecurityException getting subscription list, falling back to default", e)
                    null
                }

                if (subscriptions.isNullOrEmpty()) {
                    Log.w(TAG, "No active subscriptions found")
                    // Try to get default SIM info as fallback
                    getDefaultSimInfo()?.let { sims.add(it) }
                    return sims
                }

                subscriptions.forEach { subscription ->
                    sims.add(
                        SimInfo(
                            subscriptionId = subscription.subscriptionId,
                            slotIndex = subscription.simSlotIndex,
                            displayName = subscription.displayName?.toString() ?: "SIM ${subscription.simSlotIndex + 1}",
                            carrierName = subscription.carrierName?.toString() ?: "Unknown",
                            phoneNumber = getPhoneNumber(subscription),
                            iccId = subscription.iccId,
                            isEmbedded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                subscription.isEmbedded
                            } else {
                                false
                            },
                            isActive = true
                        )
                    )
                }

                Log.d(TAG, "Found ${sims.size} active SIM(s)")
            } else {
                // Fallback for older Android versions
                getDefaultSimInfo()?.let { sims.add(it) }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException getting SIM info", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting SIM info", e)
        }

        return sims
    }

    /**
     * Get phone number for a subscription
     */
    private fun getPhoneNumber(subscription: SubscriptionInfo): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                subscription.number?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting phone number for subscription ${subscription.subscriptionId}", e)
            null
        }
    }

    /**
     * Get default SIM info as fallback
     */
    private fun getDefaultSimInfo(): SimInfo? {
        return try {
            val phoneNumber = if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_PHONE_NUMBERS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    telephonyManager.getLine1Number()
                } else {
                    @Suppress("DEPRECATION")
                    telephonyManager.line1Number
                }
            } else {
                null
            }

            val networkOperator = telephonyManager.networkOperatorName

            SimInfo(
                subscriptionId = SubscriptionManager.getDefaultSubscriptionId(),
                slotIndex = 0,
                displayName = "Default SIM",
                carrierName = networkOperator.ifBlank { "Unknown" },
                phoneNumber = phoneNumber,
                iccId = null,
                isEmbedded = false,
                isActive = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting default SIM info", e)
            null
        }
    }

    /**
     * Get SIM info by subscription ID
     */
    fun getSimBySubscriptionId(subscriptionId: Int): SimInfo? {
        return getActiveSims().find { it.subscriptionId == subscriptionId }
    }

    /**
     * Get the default SIM for calls
     */
    fun getDefaultCallSim(): SimInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val defaultSubId = SubscriptionManager.getDefaultVoiceSubscriptionId()
                getSimBySubscriptionId(defaultSubId)
            } else {
                getActiveSims().firstOrNull()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting default call SIM", e)
            getActiveSims().firstOrNull()
        }
    }

    /**
     * Check if device has multiple SIMs
     */
    fun hasMultipleSims(): Boolean {
        return getActiveSims().size > 1
    }

    /**
     * Get SIM count
     */
    fun getSimCount(): Int {
        return getActiveSims().size
    }

    /**
     * Check if we have required permissions
     */
    private fun hasRequiredPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Get user-friendly SIM display name
     */
    fun getSimDisplayName(sim: SimInfo): String {
        return buildString {
            append(sim.displayName)
            if (sim.phoneNumber != null) {
                append(" (${sim.phoneNumber})")
            } else {
                append(" - ${sim.carrierName}")
            }
            if (sim.isEmbedded) {
                append(" [eSIM]")
            }
        }
    }

    /**
     * Sync SIM information to Firebase
     */
    suspend fun syncSimsToFirebase(syncService: com.phoneintegration.app.desktop.DesktopSyncService) {
        try {
            val sims = getActiveSims()
            val simsData = sims.map { it.toMap() }

            val userId = syncService.getCurrentUserId()
            val database = com.google.firebase.database.FirebaseDatabase.getInstance()
            val simsRef = database.reference
                .child("users")
                .child(userId)
                .child("sims")

            com.google.android.gms.tasks.Tasks.await(simsRef.setValue(simsData))
            Log.d(TAG, "Synced ${sims.size} SIM(s) to Firebase")

            // Also register phone numbers in phone_to_uid for video calling lookup
            registerPhoneNumbersForVideoCalling(userId, sims, database)
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing SIMs to Firebase", e)
        }
    }

    /**
     * Register phone numbers in phone_to_uid for video call lookup
     */
    private fun registerPhoneNumbersForVideoCalling(
        userId: String,
        sims: List<SimInfo>,
        database: com.google.firebase.database.FirebaseDatabase
    ) {
        for (sim in sims) {
            val phoneNumber = sim.phoneNumber
            if (!phoneNumber.isNullOrEmpty() && phoneNumber != "Unknown") {
                // Normalize phone number - remove +, spaces, dashes, etc.
                val normalizedPhone = phoneNumber.replace(Regex("[^0-9]"), "")
                if (normalizedPhone.isNotEmpty()) {
                    val variants = mutableSetOf(normalizedPhone)
                    if (normalizedPhone.length == 10) {
                        variants.add("1$normalizedPhone")
                    }
                    for (variant in variants) {
                        database.reference
                            .child("phone_to_uid")
                            .child(variant)
                            .setValue(userId)
                            .addOnSuccessListener {
                                Log.d(TAG, "Registered phone $variant -> $userId in phone_to_uid")
                            }
                            .addOnFailureListener { e ->
                                Log.e(TAG, "Failed to register phone in phone_to_uid: ${e.message}")
                            }
                    }
                }
            }
        }
    }
}
