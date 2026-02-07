package com.phoneintegration.app.webrtc

import android.content.Context
import android.util.Log
import com.phoneintegration.app.models.SyncFlowCall
import com.phoneintegration.app.vps.VPSClient
import org.webrtc.EglBase
import org.webrtc.VideoTrack
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Manages SyncFlow-to-SyncFlow audio/video calls.
 * Handles call state and VPS signaling.
 *
 * Note: WebRTC implementation removed - uses VPS for call signaling only.
 */
class SyncFlowCallManager(context: Context) {
    private val context: Context = context.applicationContext
    private val vpsClient = VPSClient.getInstance(context)

    companion object {
        private const val TAG = "SyncFlowCallManager"
    }

    // State
    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _currentCall = MutableStateFlow<SyncFlowCall?>(null)
    val currentCall: StateFlow<SyncFlowCall?> = _currentCall.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isVideoEnabled = MutableStateFlow(true)
    val isVideoEnabled: StateFlow<Boolean> = _isVideoEnabled.asStateFlow()

    private val _localVideoTrackFlow = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrackFlow: StateFlow<VideoTrack?> = _localVideoTrackFlow.asStateFlow()

    private val _remoteVideoTrackFlow = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrackFlow: StateFlow<VideoTrack?> = _remoteVideoTrackFlow.asStateFlow()

    private val _videoEffect = MutableStateFlow(VideoEffect.NONE)
    val videoEffect: StateFlow<VideoEffect> = _videoEffect.asStateFlow()

    // Coroutine scope
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    sealed class CallState {
        object Idle : CallState()
        object Initializing : CallState()
        object Ringing : CallState()
        object Connecting : CallState()
        object Connected : CallState()
        data class Failed(val error: String) : CallState()
        object Ended : CallState()
    }

    /**
     * Initialize call manager
     */
    fun initialize() {
        Log.d(TAG, "SyncFlowCallManager initialized (VPS mode)")
    }

    /**
     * Start an outgoing call
     */
    suspend fun startCall(
        calleeDeviceId: String,
        calleeName: String,
        isVideo: Boolean
    ): Result<String> {
        Log.d(TAG, "Starting call to $calleeName (device: $calleeDeviceId, video: $isVideo)")

        _callState.value = CallState.Initializing

        return try {
            // Request call via VPS
            val response = vpsClient.requestCall(calleeDeviceId)
            val callId = response["callId"] ?: throw Exception("No call ID returned")

            _callState.value = CallState.Ringing

            // Create call object
            val call = SyncFlowCall(
                id = callId,
                callerId = vpsClient.userId ?: "",
                callerName = "Me",
                callerPlatform = "android",
                calleeId = calleeDeviceId,
                calleeName = calleeName,
                calleePlatform = "unknown",
                callType = if (isVideo) SyncFlowCall.CallType.VIDEO else SyncFlowCall.CallType.AUDIO,
                status = SyncFlowCall.CallStatus.RINGING,
                startedAt = System.currentTimeMillis()
            )
            _currentCall.value = call

            Result.success(callId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start call", e)
            _callState.value = CallState.Failed(e.message ?: "Unknown error")
            Result.failure(e)
        }
    }

    /**
     * Start a user-to-user call (cross-account)
     */
    suspend fun startUserCall(
        recipientPhone: String,
        recipientName: String,
        isVideo: Boolean
    ): Result<String> {
        Log.d(TAG, "Starting user call to $recipientName ($recipientPhone)")
        _callState.value = CallState.Failed("User calls not implemented in VPS mode")
        return Result.failure(Exception("User calls not implemented in VPS mode"))
    }

    suspend fun startCallToUser(
        recipientPhone: String,
        recipientName: String,
        isVideo: Boolean
    ): Result<String> {
        return startUserCall(recipientPhone, recipientName, isVideo)
    }

    /**
     * Answer an incoming device-to-device call
     */
    suspend fun answerCall(userId: String, callId: String, withVideo: Boolean): Result<Unit> {
        Log.d(TAG, "Answering call $callId with video: $withVideo")

        _callState.value = CallState.Connecting

        return try {
            // Update call status via VPS
            vpsClient.updateCallRequestStatus(callId, "answered")
            _callState.value = CallState.Connected
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to answer call", e)
            _callState.value = CallState.Failed(e.message ?: "Unknown error")
            Result.failure(e)
        }
    }

    /**
     * Answer a user-to-user call
     */
    suspend fun answerUserCall(callId: String, withVideo: Boolean): Result<Unit> {
        Log.d(TAG, "Answering user call $callId")
        return answerCall(vpsClient.userId ?: "", callId, withVideo)
    }

    /**
     * Reject an incoming device-to-device call
     */
    suspend fun rejectCall(userId: String, callId: String): Result<Unit> {
        Log.d(TAG, "Rejecting call $callId")

        return try {
            vpsClient.updateCallRequestStatus(callId, "rejected")
            _callState.value = CallState.Ended
            _currentCall.value = null
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reject call", e)
            Result.failure(e)
        }
    }

    /**
     * Reject a user-to-user call
     */
    suspend fun rejectUserCall(callId: String): Result<Unit> {
        return rejectCall(vpsClient.userId ?: "", callId)
    }

    /**
     * End the current call
     */
    suspend fun endCall(): Result<Unit> {
        Log.d(TAG, "Ending call")

        val call = _currentCall.value

        return try {
            if (call != null) {
                vpsClient.updateCallRequestStatus(call.id, "ended")
            }

            _callState.value = CallState.Ended
            _currentCall.value = null

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to end call", e)
            // Still clean up locally
            _callState.value = CallState.Ended
            _currentCall.value = null
            Result.failure(e)
        }
    }

    /**
     * Toggle microphone mute
     */
    fun toggleMute(): Boolean {
        val newMuted = !_isMuted.value
        _isMuted.value = newMuted
        Log.d(TAG, "Mute toggled: $newMuted")
        return newMuted
    }

    /**
     * Toggle video on/off
     */
    fun toggleVideo(): Boolean {
        val newEnabled = !_isVideoEnabled.value
        _isVideoEnabled.value = newEnabled
        Log.d(TAG, "Video toggled: $newEnabled")
        return newEnabled
    }

    fun toggleFaceFocus(): VideoEffect {
        _videoEffect.value = if (_videoEffect.value == VideoEffect.FACE_FOCUS) {
            VideoEffect.NONE
        } else {
            VideoEffect.FACE_FOCUS
        }
        return _videoEffect.value
    }

    fun toggleBackgroundBlur(): VideoEffect {
        _videoEffect.value = if (_videoEffect.value == VideoEffect.BACKGROUND_BLUR) {
            VideoEffect.NONE
        } else {
            VideoEffect.BACKGROUND_BLUR
        }
        return _videoEffect.value
    }

    fun switchCamera() {
        Log.d(TAG, "switchCamera() ignored in VPS mode (no WebRTC)")
    }

    fun refreshVideoTrack() {
        Log.d(TAG, "refreshVideoTrack() ignored in VPS mode (no WebRTC)")
    }

    fun getEglBaseContext(): EglBase.Context? {
        return null
    }

    /**
     * Release resources
     */
    fun release() {
        Log.d(TAG, "Releasing SyncFlowCallManager resources")
        scope.cancel()
        _callState.value = CallState.Idle
        _currentCall.value = null
        _localVideoTrackFlow.value = null
        _remoteVideoTrackFlow.value = null
        _videoEffect.value = VideoEffect.NONE
    }

    private fun getAndroidDeviceId(): String {
        return android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
    }

    private fun getAndroidDeviceName(): String {
        return android.os.Build.MODEL
    }
}
