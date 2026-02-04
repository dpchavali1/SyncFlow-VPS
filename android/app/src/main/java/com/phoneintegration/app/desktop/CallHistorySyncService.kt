package com.phoneintegration.app.desktop

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

/**
 * Handles syncing call history to Firebase for desktop access
 */
class CallHistorySyncService(private val context: Context) {

    private val syncService = DesktopSyncService(context)

    companion object {
        private const val TAG = "CallHistorySyncService"
        private const val MAX_CALLS_TO_SYNC = 100
    }

    /**
     * Data class for call log entry
     */
    data class CallLogEntry(
        val id: String,
        val phoneNumber: String,
        val contactName: String?,
        val callType: CallType,
        val callDate: Long,
        val duration: Long, // in seconds
        val simId: Int?
    )

    enum class CallType(val value: Int) {
        INCOMING(CallLog.Calls.INCOMING_TYPE),
        OUTGOING(CallLog.Calls.OUTGOING_TYPE),
        MISSED(CallLog.Calls.MISSED_TYPE),
        REJECTED(CallLog.Calls.REJECTED_TYPE),
        BLOCKED(CallLog.Calls.BLOCKED_TYPE),
        VOICEMAIL(CallLog.Calls.VOICEMAIL_TYPE);

        companion object {
            fun fromInt(value: Int): CallType {
                return values().find { it.value == value } ?: MISSED
            }
        }

        fun toDisplayString(): String {
            return when (this) {
                INCOMING -> "Incoming"
                OUTGOING -> "Outgoing"
                MISSED -> "Missed"
                REJECTED -> "Rejected"
                BLOCKED -> "Blocked"
                VOICEMAIL -> "Voicemail"
            }
        }
    }

    /**
     * Get call history from device
     */
    suspend fun getCallHistory(limit: Int = MAX_CALLS_TO_SYNC): List<CallLogEntry> = withContext(Dispatchers.IO) {
        val callLogs = mutableListOf<CallLogEntry>()

        // Permission guard
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_CALL_LOG not granted; skipping call history sync")
            return@withContext emptyList()
        }

        val uri = CallLog.Calls.CONTENT_URI

        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                CallLog.Calls.PHONE_ACCOUNT_ID
            } else {
                CallLog.Calls._ID // Fallback for older versions
            }
        )

        try {
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                var count = 0
                val idIndex = cursor.getColumnIndex(CallLog.Calls._ID)
                val numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIndex = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeIndex = cursor.getColumnIndex(CallLog.Calls.TYPE)
                val dateIndex = cursor.getColumnIndex(CallLog.Calls.DATE)
                val durationIndex = cursor.getColumnIndex(CallLog.Calls.DURATION)

                while (cursor.moveToNext() && count < limit) {
                    val id = cursor.getString(idIndex) ?: continue
                    count++
                    val number = cursor.getString(numberIndex) ?: "Unknown"
                    val name = cursor.getString(nameIndex)
                    val type = cursor.getInt(typeIndex)
                    val date = cursor.getLong(dateIndex)
                    val duration = cursor.getLong(durationIndex)

                    // Try to get SIM ID (available on Android N+)
                    val simId: Int? = try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                            val simIndex = cursor.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)
                            if (simIndex >= 0) {
                                val accountId = cursor.getString(simIndex)
                                // Extract SIM ID from account ID (format varies by manufacturer)
                                accountId?.hashCode()
                            } else null
                        } else null
                    } catch (e: Exception) {
                        null
                    }

                    callLogs.add(
                        CallLogEntry(
                            id = id,
                            phoneNumber = number,
                            contactName = name,
                            callType = CallType.fromInt(type),
                            callDate = date,
                            duration = duration,
                            simId = simId
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading call logs", e)
        }

        Log.d(TAG, "Retrieved ${callLogs.size} call log entries")
        return@withContext callLogs
    }

    /**
     * Sync call history to Firebase
     */
    suspend fun syncCallHistory() {
        val userId = syncService.getCurrentUserId()
        syncCallHistoryForUser(userId)
    }

    /**
     * Sync call history to Firebase for a specific user ID via Cloud Function
     * Uses Cloud Function to avoid OOM from Firebase WebSocket sync
     */
    suspend fun syncCallHistoryForUser(userId: String) {
        try {
            val callLogs = getCallHistory()

            if (callLogs.isEmpty()) {
                Log.d(TAG, "No call logs to sync")
                return
            }

            Log.d(TAG, "Syncing ${callLogs.size} call logs via Cloud Function...")

            // Convert call logs to list of maps for Cloud Function
            val callLogsList = callLogs.map { call ->
                mapOf(
                    "id" to "${call.phoneNumber}_${call.callDate}",
                    "phoneNumber" to call.phoneNumber,
                    "contactName" to (call.contactName ?: ""),
                    "callType" to call.callType.toDisplayString(),
                    "callDate" to call.callDate,
                    "duration" to call.duration,
                    "formattedDuration" to formatDuration(call.duration),
                    "formattedDate" to formatDate(call.callDate),
                    "simId" to (call.simId ?: 0)
                )
            }

            // Call Cloud Function to sync (avoids OOM from Firebase WebSocket)
            val functions = com.google.firebase.functions.FirebaseFunctions.getInstance()
            val result = functions
                .getHttpsCallable("syncCallHistory")
                .call(mapOf("userId" to userId, "callLogs" to callLogsList))
                .await()

            val data = result.data as? Map<*, *>
            val success = data?.get("success") as? Boolean ?: false
            val count = data?.get("count") as? Int ?: 0

            if (success) {
                Log.d(TAG, "Successfully synced $count call logs via Cloud Function")
            } else {
                Log.e(TAG, "Cloud Function sync failed: ${data?.get("error")}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing call logs", e)
            throw e
        }
    }

    /**
     * Sync a single call entry (for real-time sync)
     */
    suspend fun syncCallEntry(call: CallLogEntry) {
        try {
            val userId = syncService.getCurrentUserId()

            val callHistoryRef = com.google.firebase.database.FirebaseDatabase.getInstance().reference
                .child("users")
                .child(userId)
                .child("call_history")
                .child("${call.phoneNumber}_${call.callDate}")

            val callData = mapOf(
                "id" to call.id,
                "phoneNumber" to call.phoneNumber,
                "contactName" to (call.contactName ?: ""),
                "callType" to call.callType.toDisplayString(),
                "callTypeInt" to call.callType.value,
                "callDate" to call.callDate,
                "duration" to call.duration,
                "formattedDuration" to formatDuration(call.duration),
                "formattedDate" to formatDate(call.callDate),
                "simId" to (call.simId ?: 0),
                "syncedAt" to ServerValue.TIMESTAMP
            )

            callHistoryRef.setValue(callData).await()
            Log.d(TAG, "Synced call entry: ${call.phoneNumber} at ${call.callDate}")
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing call entry", e)
        }
    }

    /**
     * Format duration in seconds to human-readable string
     */
    private fun formatDuration(seconds: Long): String {
        if (seconds == 0L) return "Not answered"

        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60

        return when {
            hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, secs)
            minutes > 0 -> String.format("%d:%02d", minutes, secs)
            else -> String.format("0:%02d", secs)
        }
    }

    /**
     * Format date to human-readable string
     */
    private fun formatDate(timestamp: Long): String {
        val date = Date(timestamp)
        val now = Date()
        val calendar = Calendar.getInstance()

        calendar.time = now
        val todayStart = calendar.apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        calendar.time = now
        calendar.add(Calendar.DAY_OF_YEAR, -1)
        val yesterdayStart = calendar.apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        return when {
            timestamp >= todayStart -> {
                val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
                "Today at ${timeFormat.format(date)}"
            }
            timestamp >= yesterdayStart -> {
                val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
                "Yesterday at ${timeFormat.format(date)}"
            }
            else -> {
                val dateFormat = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
                dateFormat.format(date)
            }
        }
    }

    /**
     * Get call statistics
     */
    suspend fun getCallStatistics(): CallStatistics {
        val calls = getCallHistory()

        val totalCalls = calls.size
        val incomingCalls = calls.count { it.callType == CallType.INCOMING }
        val outgoingCalls = calls.count { it.callType == CallType.OUTGOING }
        val missedCalls = calls.count { it.callType == CallType.MISSED }
        val totalDuration = calls.filter { it.duration > 0 }.sumOf { it.duration }

        return CallStatistics(
            totalCalls = totalCalls,
            incomingCalls = incomingCalls,
            outgoingCalls = outgoingCalls,
            missedCalls = missedCalls,
            totalDuration = totalDuration
        )
    }

    data class CallStatistics(
        val totalCalls: Int,
        val incomingCalls: Int,
        val outgoingCalls: Int,
        val missedCalls: Int,
        val totalDuration: Long
    )
}
