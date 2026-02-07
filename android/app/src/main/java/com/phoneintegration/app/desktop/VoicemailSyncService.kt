package com.phoneintegration.app.desktop

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.VoicemailContract
import android.util.Base64
import android.util.Log
import com.phoneintegration.app.vps.VPSClient
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

/**
 * Service to sync voicemails from Android to macOS.
 * Reads voicemails from ContentProvider and syncs metadata to VPS.
 *
 * VPS Backend Only - Uses VPS API instead of Firebase.
 */
class VoicemailSyncService(context: Context) {
    private val context: Context = context.applicationContext
    private val vpsClient = VPSClient.getInstance(context)

    private var voicemailObserver: ContentObserver? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private var lastSyncedVoicemailIds = mutableSetOf<String>()

    companion object {
        private const val TAG = "VoicemailSyncService"
        private const val MAX_VOICEMAIL_SIZE = 5 * 1024 * 1024 // 5MB max
    }

    data class Voicemail(
        val id: String,
        val number: String,
        val duration: Int,
        val date: Long,
        val isRead: Boolean,
        val transcription: String?,
        val hasAudio: Boolean
    )

    /**
     * Start syncing voicemails
     */
    fun startSync() {
        Log.d(TAG, "Starting voicemail sync")
        registerVoicemailObserver()
        syncVoicemails()
    }

    /**
     * Stop syncing
     */
    fun stopSync() {
        Log.d(TAG, "Stopping voicemail sync")
        unregisterVoicemailObserver()
        scope.cancel()
    }

    /**
     * Register observer for voicemail changes
     */
    private fun registerVoicemailObserver() {
        voicemailObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                Log.d(TAG, "Voicemail content changed")
                syncVoicemails()
            }
        }

        try {
            context.contentResolver.registerContentObserver(
                VoicemailContract.Voicemails.CONTENT_URI,
                true,
                voicemailObserver!!
            )
            Log.d(TAG, "Voicemail observer registered")
        } catch (e: SecurityException) {
            Log.e(TAG, "No permission to observe voicemails", e)
        }
    }

    private fun unregisterVoicemailObserver() {
        voicemailObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
        }
        voicemailObserver = null
    }

    /**
     * Sync all voicemails to VPS
     */
    private fun syncVoicemails() {
        scope.launch {
            try {
                if (!vpsClient.isAuthenticated) return@launch

                val voicemails = readVoicemails()
                Log.d(TAG, "Found ${voicemails.size} voicemails")

                for (voicemail in voicemails) {
                    // Skip if already synced
                    if (lastSyncedVoicemailIds.contains(voicemail.id)) continue

                    syncVoicemail(voicemail)
                    lastSyncedVoicemailIds.add(voicemail.id)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing voicemails", e)
            }
        }
    }

    /**
     * Read voicemails from ContentProvider
     */
    private fun readVoicemails(): List<Voicemail> {
        val voicemails = mutableListOf<Voicemail>()

        try {
            val projection = arrayOf(
                VoicemailContract.Voicemails._ID,
                VoicemailContract.Voicemails.NUMBER,
                VoicemailContract.Voicemails.DURATION,
                VoicemailContract.Voicemails.DATE,
                VoicemailContract.Voicemails.IS_READ,
                VoicemailContract.Voicemails.TRANSCRIPTION,
                VoicemailContract.Voicemails.HAS_CONTENT
            )

            val cursor = context.contentResolver.query(
                VoicemailContract.Voicemails.CONTENT_URI,
                projection,
                null,
                null,
                "${VoicemailContract.Voicemails.DATE} DESC"
            )

            cursor?.use {
                val idIndex = it.getColumnIndex(VoicemailContract.Voicemails._ID)
                val numberIndex = it.getColumnIndex(VoicemailContract.Voicemails.NUMBER)
                val durationIndex = it.getColumnIndex(VoicemailContract.Voicemails.DURATION)
                val dateIndex = it.getColumnIndex(VoicemailContract.Voicemails.DATE)
                val isReadIndex = it.getColumnIndex(VoicemailContract.Voicemails.IS_READ)
                val transcriptionIndex = it.getColumnIndex(VoicemailContract.Voicemails.TRANSCRIPTION)
                val hasContentIndex = it.getColumnIndex(VoicemailContract.Voicemails.HAS_CONTENT)

                while (it.moveToNext()) {
                    val id = it.getLong(idIndex).toString()
                    val number = it.getString(numberIndex) ?: "Unknown"
                    val duration = it.getInt(durationIndex)
                    val date = it.getLong(dateIndex)
                    val isRead = it.getInt(isReadIndex) == 1
                    val transcription = if (transcriptionIndex >= 0) it.getString(transcriptionIndex) else null
                    val hasContent = if (hasContentIndex >= 0) it.getInt(hasContentIndex) == 1 else false

                    voicemails.add(Voicemail(
                        id = id,
                        number = number,
                        duration = duration,
                        date = date,
                        isRead = isRead,
                        transcription = transcription,
                        hasAudio = hasContent
                    ))
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "No permission to read voicemails", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading voicemails", e)
        }

        return voicemails
    }

    /**
     * Sync a single voicemail to VPS
     */
    private suspend fun syncVoicemail(voicemail: Voicemail) {
        try {
            // Get contact name if available
            val contactName = getContactName(voicemail.number)

            val voicemailData = mutableMapOf<String, Any?>(
                "id" to voicemail.id,
                "number" to voicemail.number,
                "contactName" to contactName,
                "duration" to voicemail.duration,
                "date" to voicemail.date,
                "isRead" to voicemail.isRead,
                "hasAudio" to voicemail.hasAudio,
                "syncedAt" to System.currentTimeMillis()
            )

            // Add transcription if available
            voicemail.transcription?.let {
                voicemailData["transcription"] = it
            }

            vpsClient.syncVoicemail(voicemailData)
            Log.d(TAG, "Synced voicemail: ${voicemail.id} from ${voicemail.number}")
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing voicemail ${voicemail.id}", e)
        }
    }

    /**
     * Get contact name for a phone number
     */
    private fun getContactName(phoneNumber: String): String? {
        return try {
            val uri = Uri.withAppendedPath(
                android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    it.getString(0)
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting contact name", e)
            null
        }
    }

    /**
     * Mark a voicemail as read
     */
    fun markAsRead(voicemailId: String) {
        scope.launch {
            try {
                if (!vpsClient.isAuthenticated) return@launch

                vpsClient.markVoicemailAsRead(voicemailId)
                Log.d(TAG, "Marked voicemail $voicemailId as read")
            } catch (e: Exception) {
                Log.e(TAG, "Error marking voicemail as read", e)
            }
        }
    }

    /**
     * Delete a voicemail
     */
    fun deleteVoicemail(voicemailId: String) {
        scope.launch {
            try {
                if (!vpsClient.isAuthenticated) return@launch

                vpsClient.deleteVoicemail(voicemailId)
                lastSyncedVoicemailIds.remove(voicemailId)
                Log.d(TAG, "Deleted voicemail $voicemailId")
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting voicemail", e)
            }
        }
    }
}
