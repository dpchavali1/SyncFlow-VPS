package com.phoneintegration.app.continuity

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener

class ContinuityService(context: Context) {

    data class ContinuityState(
        val deviceId: String,
        val deviceName: String,
        val platform: String,
        val type: String,
        val address: String,
        val contactName: String?,
        val threadId: Long?,
        val draft: String?,
        val timestamp: Long
    )

    private val appContext = context.applicationContext
    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()

    private val deviceId = Settings.Secure.getString(
        appContext.contentResolver,
        Settings.Secure.ANDROID_ID
    ) ?: "android_unknown"
    private val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Android" }

    private var listener: ValueEventListener? = null
    private var continuityRef: DatabaseReference? = null
    private var lastSeenDeviceId: String? = null
    private var lastSeenTimestamp: Long = 0L
    private var lastPublishAt: Long = 0L
    private var lastPayloadHash: Int = 0

    fun updateConversationState(
        address: String,
        contactName: String?,
        threadId: Long,
        draft: String?
    ) {
        val userId = auth.currentUser?.uid ?: return
        val now = System.currentTimeMillis()
        val payloadHash = listOf(address, contactName ?: "", threadId.toString(), draft ?: "").hashCode()

        if (now - lastPublishAt < 800 && payloadHash == lastPayloadHash) {
            return
        }

        lastPublishAt = now
        lastPayloadHash = payloadHash

        database.goOnline()

        val ref = database.reference
            .child("users")
            .child(userId)
            .child("continuity_state")
            .child(deviceId)

        val trimmedDraft = draft?.take(1000)

        val data = mapOf(
            "deviceId" to deviceId,
            "deviceName" to deviceName,
            "platform" to "android",
            "type" to "conversation",
            "address" to address,
            "contactName" to (contactName ?: ""),
            "threadId" to threadId,
            "draft" to (trimmedDraft ?: ""),
            "timestamp" to ServerValue.TIMESTAMP
        )

        ref.setValue(data).addOnFailureListener { e ->
            Log.e("ContinuityService", "Failed to update continuity state", e)
        }
    }

    fun startListening(onUpdate: (ContinuityState?) -> Unit) {
        val userId = auth.currentUser?.uid ?: return

        database.goOnline()

        val ref = database.reference
            .child("users")
            .child(userId)
            .child("continuity_state")

        continuityRef = ref

        val valueListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val latest = snapshot.children
                    .mapNotNull { parseState(it) }
                    .filter { it.deviceId != deviceId }
                    .filter { it.timestamp > 0 }
                    .maxByOrNull { it.timestamp }

                if (latest == null) {
                    onUpdate(null)
                    return
                }

                if (latest.deviceId == lastSeenDeviceId && latest.timestamp <= lastSeenTimestamp) {
                    return
                }

                if (System.currentTimeMillis() - latest.timestamp > 5 * 60 * 1000) {
                    return
                }

                lastSeenDeviceId = latest.deviceId
                lastSeenTimestamp = latest.timestamp
                onUpdate(latest)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("ContinuityService", "Continuity listener cancelled: ${error.message}")
            }
        }

        listener = valueListener
        ref.addValueEventListener(valueListener)
    }

    fun stopListening() {
        val ref = continuityRef ?: return
        val valueListener = listener ?: return
        ref.removeEventListener(valueListener)
        listener = null
        continuityRef = null
    }

    private fun parseState(snapshot: DataSnapshot): ContinuityState? {
        val deviceId = snapshot.key ?: return null
        val data = snapshot.value as? Map<*, *> ?: return null
        val timestamp = parseTimestamp(data["timestamp"])
        val address = data["address"] as? String ?: return null

        return ContinuityState(
            deviceId = deviceId,
            deviceName = data["deviceName"] as? String ?: "Device",
            platform = data["platform"] as? String ?: "unknown",
            type = data["type"] as? String ?: "conversation",
            address = address,
            contactName = (data["contactName"] as? String)?.ifBlank { null },
            threadId = parseLong(data["threadId"]),
            draft = (data["draft"] as? String)?.ifBlank { null },
            timestamp = timestamp
        )
    }

    private fun parseTimestamp(value: Any?): Long {
        return when (value) {
            is Long -> value
            is Double -> value.toLong()
            is Int -> value.toLong()
            else -> 0L
        }
    }

    private fun parseLong(value: Any?): Long? {
        return when (value) {
            is Long -> value
            is Double -> value.toLong()
            is Int -> value.toLong()
            else -> null
        }
    }
}
