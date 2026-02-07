package com.phoneintegration.app.models

/**
 * Represents a SyncFlow-to-SyncFlow audio/video call between devices.
 * This is separate from cellular phone calls - it's app-to-app VoIP.
 *
 * VPS Backend Only - Firebase annotations removed.
 */
data class SyncFlowCall(
    val id: String = "",
    val callerId: String = "",
    val callerName: String = "",
    val callerPlatform: String = "",
    val calleeId: String = "",
    val calleeName: String = "",
    val calleePlatform: String = "",
    val callType: CallType = CallType.AUDIO,
    val status: CallStatus = CallStatus.RINGING,
    val startedAt: Long = 0,
    val answeredAt: Long? = null,
    val endedAt: Long? = null,
    val offer: SDPData? = null,
    val answer: SDPData? = null,
    // New fields for user-to-user calling
    val isUserCall: Boolean = false,
    val callerPhone: String = ""
) {
    enum class CallType {
        AUDIO,
        VIDEO;

        companion object {
            fun fromString(value: String): CallType {
                return when (value.lowercase()) {
                    "video" -> VIDEO
                    else -> AUDIO
                }
            }
        }

        override fun toString(): String = name.lowercase()
    }

    enum class CallStatus {
        RINGING,    // Call initiated, waiting for answer
        ACTIVE,     // Call in progress
        ENDED,      // Call ended normally
        REJECTED,   // Callee rejected the call
        MISSED,     // Call timed out without answer
        FAILED;     // Call failed to connect

        companion object {
            fun fromString(value: String): CallStatus {
                return when (value.lowercase()) {
                    "ringing" -> RINGING
                    "active" -> ACTIVE
                    "ended" -> ENDED
                    "rejected" -> REJECTED
                    "missed" -> MISSED
                    "failed" -> FAILED
                    else -> RINGING
                }
            }
        }

        override fun toString(): String = name.lowercase()
    }

    /**
     * SDP (Session Description Protocol) data for WebRTC signaling
     */
    data class SDPData(
        val sdp: String = "",
        val type: String = ""
    )

    /**
     * ICE (Interactive Connectivity Establishment) candidate for WebRTC
     */
    data class IceCandidate(
        val candidate: String = "",
        val sdpMid: String? = null,
        val sdpMLineIndex: Int = 0
    )

    /**
     * Convert to map for API serialization
     */
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "callerId" to callerId,
        "callerName" to callerName,
        "callerPlatform" to callerPlatform,
        "calleeId" to calleeId,
        "calleeName" to calleeName,
        "calleePlatform" to calleePlatform,
        "callType" to callType.toString(),
        "status" to status.toString(),
        "startedAt" to startedAt,
        "answeredAt" to answeredAt,
        "endedAt" to endedAt,
        "offer" to offer?.let { mapOf("sdp" to it.sdp, "type" to it.type) },
        "answer" to answer?.let { mapOf("sdp" to it.sdp, "type" to it.type) },
        "isUserCall" to isUserCall,
        "callerPhone" to callerPhone
    )

    val isIncoming: Boolean
        get() = callerPlatform != "android"

    val isOutgoing: Boolean
        get() = callerPlatform == "android"

    val isVideo: Boolean
        get() = callType == CallType.VIDEO

    val isActive: Boolean
        get() = status == CallStatus.ACTIVE

    val isRinging: Boolean
        get() = status == CallStatus.RINGING

    val displayName: String
        get() = if (isIncoming) callerName else calleeName

    val duration: Long
        get() {
            val start = answeredAt ?: return 0
            val end = endedAt ?: System.currentTimeMillis()
            return end - start
        }

    companion object {
        const val CALL_TIMEOUT_MS = 60_000L // 60 seconds

        /**
         * Create a new outgoing call from this device
         */
        fun createOutgoing(
            callId: String,
            callerId: String,
            callerName: String,
            calleeId: String,
            calleeName: String,
            isVideo: Boolean
        ): SyncFlowCall = SyncFlowCall(
            id = callId,
            callerId = callerId,
            callerName = callerName,
            callerPlatform = "android",
            calleeId = calleeId,
            calleeName = calleeName,
            calleePlatform = "macos",
            callType = if (isVideo) CallType.VIDEO else CallType.AUDIO,
            status = CallStatus.RINGING,
            startedAt = System.currentTimeMillis()
        )

        /**
         * Parse from map data
         */
        fun fromMap(id: String, map: Map<String, Any?>): SyncFlowCall {
            val offerMap = map["offer"] as? Map<*, *>
            val answerMap = map["answer"] as? Map<*, *>

            return SyncFlowCall(
                id = id,
                callerId = map["callerId"] as? String ?: map["callerUid"] as? String ?: "",
                callerName = map["callerName"] as? String ?: "",
                callerPlatform = map["callerPlatform"] as? String ?: "",
                calleeId = map["calleeId"] as? String ?: "",
                calleeName = map["calleeName"] as? String ?: "",
                calleePlatform = map["calleePlatform"] as? String ?: "",
                callType = CallType.fromString(map["callType"] as? String ?: "audio"),
                status = CallStatus.fromString(map["status"] as? String ?: "ringing"),
                startedAt = (map["startedAt"] as? Number)?.toLong() ?: 0,
                answeredAt = (map["answeredAt"] as? Number)?.toLong(),
                endedAt = (map["endedAt"] as? Number)?.toLong(),
                offer = offerMap?.let {
                    SDPData(
                        sdp = it["sdp"] as? String ?: "",
                        type = it["type"] as? String ?: ""
                    )
                },
                answer = answerMap?.let {
                    SDPData(
                        sdp = it["sdp"] as? String ?: "",
                        type = it["type"] as? String ?: ""
                    )
                },
                isUserCall = map["isUserCall"] as? Boolean ?: false,
                callerPhone = map["callerPhone"] as? String ?: ""
            )
        }
    }
}

/**
 * Device information for SyncFlow calling
 */
data class SyncFlowDevice(
    val id: String = "",
    val name: String = "",
    val platform: String = "",
    val online: Boolean = false,
    val lastSeen: Long = 0
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "platform" to platform,
        "online" to online,
        "lastSeen" to lastSeen
    )

    val isMacOS: Boolean
        get() = platform == "macos"

    val isAndroid: Boolean
        get() = platform == "android"

    companion object {
        fun fromMap(id: String, map: Map<String, Any?>): SyncFlowDevice = SyncFlowDevice(
            id = id,
            name = map["name"] as? String ?: "",
            platform = map["platform"] as? String ?: "",
            online = map["online"] as? Boolean ?: false,
            lastSeen = (map["lastSeen"] as? Number)?.toLong() ?: 0
        )
    }
}
