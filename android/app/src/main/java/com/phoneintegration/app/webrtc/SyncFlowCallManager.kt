package com.phoneintegration.app.webrtc

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.phoneintegration.app.SyncFlowCallService
import com.phoneintegration.app.models.SyncFlowCall
import com.phoneintegration.app.vps.VPSClient
import com.phoneintegration.app.vps.WebRTCSignalListener
import org.webrtc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Manages SyncFlow-to-SyncFlow audio/video calls using WebRTC.
 * Handles PeerConnection lifecycle, ICE candidates, and VPS signaling.
 */
class SyncFlowCallManager(context: Context) {
    private val context: Context = context.applicationContext
    private val vpsClient = VPSClient.getInstance(context)

    companion object {
        private const val TAG = "SyncFlowCallManager"
    }

    // WebRTC
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var eglBase: EglBase? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrackInternal: VideoTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var pendingIceCandidates = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false
    private var currentCallId: String? = null
    private var currentToDevice: String? = null
    private var isFrontCamera = true
    private var disconnectTimeoutJob: Job? = null
    private var ringingTimeoutJob: Job? = null
    private var answerPollingJob: Job? = null
    private var icePollingJob: Job? = null
    private val processedIceCandidates = mutableSetOf<String>()
    private var isCallInitiator = false
    private var iceRestartAttempted = false

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
     * Initialize call manager - set up PeerConnectionFactory and register signal listener
     */
    fun initialize() {
        Log.d(TAG, "Initializing SyncFlowCallManager with WebRTC")

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        eglBase = EglBase.create()

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase!!.eglBaseContext, true, true
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()

        // Register for WebRTC signals from VPS WebSocket
        vpsClient.webrtcSignalListener = object : WebRTCSignalListener {
            override fun onWebRTCSignal(callId: String, signalType: String, signalData: Any, fromDevice: String) {
                scope.launch { handleSignal(callId, signalType, signalData, fromDevice) }
            }

            override fun onIncomingSyncFlowCall(callId: String, callerId: String, callerName: String, callType: String) {
                Log.d(TAG, "Incoming SyncFlow call via WebSocket: callId=$callId, caller=$callerName")
                val intent = Intent(context, SyncFlowCallService::class.java).apply {
                    action = SyncFlowCallService.ACTION_INCOMING_USER_CALL
                    putExtra(SyncFlowCallService.EXTRA_CALL_ID, callId)
                    putExtra(SyncFlowCallService.EXTRA_CALLER_NAME, callerName)
                    putExtra(SyncFlowCallService.EXTRA_CALLER_PHONE, callerId)
                    putExtra(SyncFlowCallService.EXTRA_IS_VIDEO, callType == "video")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }

            override fun onSyncFlowCallStatus(callId: String, status: String) {
                Log.d(TAG, "Call status update: callId=$callId, status=$status, currentCallId=$currentCallId")
                if (callId == currentCallId && status in listOf("ended", "rejected", "missed", "failed")) {
                    Log.d(TAG, "Ending call due to remote status: $status")
                    // Clear currentCallId BEFORE endCall() so it doesn't re-send "ended" to server
                    // (the other side already sent the status, no need to echo it back)
                    currentCallId = null
                    scope.launch { endCall() }
                }
            }
        }

        Log.d(TAG, "SyncFlowCallManager initialized with WebRTC")
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
        _isVideoEnabled.value = isVideo
        _isMuted.value = false
        isCallInitiator = true
        iceRestartAttempted = false
        processedIceCandidates.clear()
        icePollingJob?.cancel()
        icePollingJob = null

        return try {
            // Create call via VPS
            val response = vpsClient.createSyncFlowCall(
                calleeId = calleeDeviceId,
                calleeName = calleeName,
                callType = if (isVideo) "video" else "audio"
            )
            val callId = response["callId"]?.toString() ?: throw Exception("No call ID returned")

            // Log server diagnostics for debugging call delivery
            val debug = response["_debug"]
            if (debug != null) {
                Log.i(TAG, "Call delivery diagnostics: $debug")
            }

            currentCallId = callId
            currentToDevice = calleeDeviceId

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

            // Get TURN credentials and create PeerConnection
            val turnCreds = vpsClient.getTurnCredentials()
            val iceServers = parseIceServers(turnCreds)
            createPeerConnection(iceServers, isVideo)

            // Create and send offer
            val offer = createOfferSuspend()
            setLocalDescriptionSuspend(offer)

            vpsClient.sendSignal(
                callId = callId,
                signalType = "offer",
                signalData = mapOf("sdp" to offer.description, "type" to offer.type.canonicalForm()),
                toDevice = calleeDeviceId
            )

            _callState.value = CallState.Ringing
            // Start 60-second ringing timeout
            startRingingTimeout()
            // Poll for answer as fallback in case WebSocket misses it
            startAnswerPolling(callId)
            Result.success(callId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start call", e)
            _callState.value = CallState.Failed(e.message ?: "Unknown error")
            cleanupWebRTC()
            Result.failure(e)
        }
    }

    private fun startRingingTimeout() {
        ringingTimeoutJob?.cancel()
        ringingTimeoutJob = scope.launch {
            delay(60_000)
            if (_callState.value == CallState.Ringing) {
                Log.w(TAG, "Call timed out after 60s - no answer")
                _callState.value = CallState.Failed("No answer")
                endCall()
            }
        }
    }

    /**
     * Poll for answer signal as fallback if WebSocket misses it.
     * Polls every 1 second until an answer is received or the call ends.
     */
    private fun startAnswerPolling(callId: String) {
        answerPollingJob?.cancel()
        answerPollingJob = scope.launch(Dispatchers.IO) {
            repeat(120) { // Poll for up to 60 seconds (every 500ms)
                if (remoteDescriptionSet || currentCallId != callId) return@launch
                try {
                    val signals = vpsClient.getSignals(callId)
                    val answerSignal = signals.find { (it["signalType"] as? String) == "answer" }
                    if (answerSignal != null && !remoteDescriptionSet) {
                        Log.d(TAG, "Answer received via polling fallback")
                        withContext(Dispatchers.Main) {
                            handleAnswerSignal(answerSignal["signalData"])
                        }
                        // Also process any ICE candidates we might have missed
                        for (signal in signals) {
                            if ((signal["signalType"] as? String) == "ice-candidate") {
                                withContext(Dispatchers.Main) {
                                    handleIceCandidateSignal(signal["signalData"])
                                }
                            }
                        }
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Answer polling error: ${e.message}")
                }
                delay(500)
            }
        }
    }

    /**
     * Poll for ICE candidates as fallback if WebSocket misses trickle ICE.
     * Polls every 500ms for up to 60 seconds or until connected/ended.
     */
    private fun startIcePolling(callId: String) {
        icePollingJob?.cancel()
        icePollingJob = scope.launch(Dispatchers.IO) {
            repeat(120) {
                val state = _callState.value
                if (currentCallId != callId ||
                    state is CallState.Connected ||
                    state is CallState.Ended ||
                    state is CallState.Failed
                ) {
                    return@launch
                }
                try {
                    val signals = vpsClient.getSignals(callId)
                    for (signal in signals) {
                        if ((signal["signalType"] as? String) == "ice-candidate") {
                            withContext(Dispatchers.Main) {
                                handleIceCandidateSignal(signal["signalData"])
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "ICE polling error: ${e.message}")
                }
                delay(500)
            }
        }
    }

    /**
     * Start a user-to-user call
     */
    suspend fun startUserCall(
        recipientPhone: String,
        recipientName: String,
        isVideo: Boolean
    ): Result<String> {
        Log.d(TAG, "Starting user call to $recipientName ($recipientPhone)")
        return startCall(recipientPhone, recipientName, isVideo)
    }

    suspend fun startCallToUser(
        recipientPhone: String,
        recipientName: String,
        isVideo: Boolean
    ): Result<String> {
        return startUserCall(recipientPhone, recipientName, isVideo)
    }

    /**
     * Answer an incoming call
     */
    suspend fun answerCall(userId: String, callId: String, withVideo: Boolean): Result<Unit> {
        Log.d(TAG, "Answering call $callId with video: $withVideo")

        _callState.value = CallState.Connecting
        _isVideoEnabled.value = withVideo
        _isMuted.value = false
        isCallInitiator = false
        iceRestartAttempted = false
        currentCallId = callId
        processedIceCandidates.clear()
        icePollingJob?.cancel()
        icePollingJob = null

        // Set currentCall so the UI knows this is a video call and shows controls
        _currentCall.value = SyncFlowCall(
            id = callId,
            callerId = "",
            callerName = "Caller",
            callerPlatform = "unknown",
            calleeId = userId,
            calleeName = "Me",
            calleePlatform = "android",
            callType = if (withVideo) SyncFlowCall.CallType.VIDEO else SyncFlowCall.CallType.AUDIO,
            status = SyncFlowCall.CallStatus.ACTIVE,
            startedAt = System.currentTimeMillis()
        )

        return try {
            vpsClient.updateSyncFlowCallStatus(callId, "active")

            // Get TURN credentials and create PeerConnection
            val turnCreds = vpsClient.getTurnCredentials()
            val iceServers = parseIceServers(turnCreds)
            createPeerConnection(iceServers, withVideo)

            // Process all pending signals (offer + ICE candidates)
            val signals = vpsClient.getSignals(callId)
            Log.d(TAG, "Fetched ${signals.size} pending signals for call $callId")

            // Process offer first, then ICE candidates
            val offerSignal = signals.find { (it["signalType"] as? String) == "offer" }
            if (offerSignal != null) {
                handleOfferSignal(callId, offerSignal["signalData"])
            }
            // Now process all ICE candidates
            for (signal in signals) {
                val signalType = signal["signalType"] as? String ?: continue
                if (signalType == "ice-candidate") {
                    handleIceCandidateSignal(signal["signalData"])
                }
            }

            // Continue polling ICE as fallback (in case WebSocket drops)
            startIcePolling(callId)

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to answer call", e)
            _callState.value = CallState.Failed(e.message ?: "Unknown error")
            cleanupWebRTC()
            Result.failure(e)
        }
    }

    suspend fun answerUserCall(callId: String, withVideo: Boolean): Result<Unit> {
        Log.d(TAG, "Answering user call $callId")
        return answerCall(vpsClient.userId ?: "", callId, withVideo)
    }

    suspend fun rejectCall(userId: String, callId: String): Result<Unit> {
        Log.d(TAG, "Rejecting call $callId")

        return try {
            vpsClient.updateSyncFlowCallStatus(callId, "rejected")
            _callState.value = CallState.Ended
            _currentCall.value = null
            currentCallId = null
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reject call", e)
            Result.failure(e)
        }
    }

    suspend fun rejectUserCall(callId: String): Result<Unit> {
        return rejectCall(vpsClient.userId ?: "", callId)
    }

    suspend fun endCall(): Result<Unit> {
        Log.d(TAG, "Ending call")

        // Capture callId before clearing - use currentCallId (always set) not _currentCall (may be null for callee)
        val endingCallId = currentCallId ?: _currentCall.value?.id
        currentCallId = null
        currentToDevice = null
        ringingTimeoutJob?.cancel()
        ringingTimeoutJob = null
        disconnectTimeoutJob?.cancel()
        disconnectTimeoutJob = null
        answerPollingJob?.cancel()
        answerPollingJob = null

        return try {
            if (endingCallId != null) {
                Log.d(TAG, "Sending ended status for call $endingCallId")
                vpsClient.updateSyncFlowCallStatus(endingCallId, "ended")
                try { vpsClient.deleteSignals(endingCallId) } catch (_: Exception) {}
            }

            cleanupWebRTC()
            _callState.value = CallState.Ended
            _currentCall.value = null
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to end call", e)
            cleanupWebRTC()
            _callState.value = CallState.Ended
            _currentCall.value = null
            Result.failure(e)
        }
    }

    // ==================== ICE Restart ====================

    /**
     * Attempt ICE restart by creating a new offer with iceRestart flag.
     * Only the call initiator (caller) should initiate ICE restart to avoid glare.
     */
    private suspend fun attemptIceRestart() {
        val callId = currentCallId ?: return
        val pc = peerConnection ?: return

        Log.d(TAG, "Attempting ICE restart for call $callId")
        iceRestartAttempted = true

        try {
            // Mark ICE transport for restart - next offer will include iceRestart
            pc.restartIce()

            // Clear processed ICE candidates so new ones can be accepted
            processedIceCandidates.clear()

            // Create new offer (automatically includes iceRestart after restartIce())
            val offer = createOfferSuspend()
            setLocalDescriptionSuspend(offer)

            vpsClient.sendSignal(
                callId = callId,
                signalType = "offer",
                signalData = mapOf("sdp" to offer.description, "type" to offer.type.canonicalForm()),
                toDevice = currentToDevice
            )
            Log.d(TAG, "ICE restart offer sent for call $callId")
        } catch (e: Exception) {
            Log.e(TAG, "ICE restart failed: ${e.message}", e)
        }
    }

    // ==================== Signal Handling ====================

    private suspend fun handleSignal(callId: String, signalType: String, signalData: Any, fromDevice: String) {
        if (callId != currentCallId) {
            Log.d(TAG, "Ignoring signal for different call: $callId (current: $currentCallId)")
            return
        }
        currentToDevice = fromDevice

        when (signalType) {
            "offer" -> handleOfferSignal(callId, signalData)
            "answer" -> handleAnswerSignal(signalData)
            "ice-candidate" -> handleIceCandidateSignal(signalData)
        }
    }

    private suspend fun handleOfferSignal(callId: String, signalData: Any?) {
        val data = signalData as? Map<*, *> ?: return
        val sdp = data["sdp"] as? String ?: return
        val type = data["type"] as? String ?: "offer"

        // Allow renegotiation offers (ICE restart) when already connected
        val isRenegotiation = remoteDescriptionSet
        if (isRenegotiation) {
            Log.d(TAG, "Processing renegotiation offer (ICE restart) for call $callId")
            processedIceCandidates.clear()
        } else {
            Log.d(TAG, "Handling initial offer for call $callId")
        }

        val sessionDescription = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(type), sdp
        )

        // MUST wait for setRemoteDescription to complete before creating answer
        try {
            setRemoteDescriptionSuspend(sessionDescription)
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring setRemoteDescription failure for offer: ${e.message}")
            return
        }
        remoteDescriptionSet = true

        if (!isRenegotiation) {
            drainPendingIceCandidates()
        }

        // Create answer (remote description must be set first)
        val answer = createAnswerSuspend()
        setLocalDescriptionSuspend(answer)

        Log.d(TAG, "Sending answer for call $callId")
        vpsClient.sendSignal(
            callId = callId,
            signalType = "answer",
            signalData = mapOf("sdp" to answer.description, "type" to answer.type.canonicalForm()),
            toDevice = currentToDevice
        )

        if (!isRenegotiation) {
            // Start ICE polling fallback (callee side)
            startIcePolling(callId)
        }
    }

    private suspend fun handleAnswerSignal(signalData: Any?) {
        val data = signalData as? Map<*, *> ?: return
        val sdp = data["sdp"] as? String ?: return
        val type = data["type"] as? String ?: "answer"

        // Allow renegotiation answers (response to ICE restart offer)
        val isRenegotiation = remoteDescriptionSet
        if (isRenegotiation) {
            Log.d(TAG, "Processing renegotiation answer (ICE restart response)")
        } else {
            Log.d(TAG, "Handling initial answer")
            // Cancel answer polling since we got the initial answer
            answerPollingJob?.cancel()
            answerPollingJob = null
        }

        // Guard against duplicate answers when already stable
        val signalingState = peerConnection?.signalingState()
        if (signalingState == PeerConnection.SignalingState.STABLE) {
            Log.w(TAG, "Ignoring answer in STABLE signaling state (duplicate or stale)")
            return
        }

        val sessionDescription = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(type), sdp
        )

        // MUST wait for setRemoteDescription to complete before adding ICE candidates
        try {
            setRemoteDescriptionSuspend(sessionDescription)
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring setRemoteDescription failure for answer: ${e.message}")
            return
        }
        remoteDescriptionSet = true

        if (!isRenegotiation) {
            drainPendingIceCandidates()

            // Start ICE polling fallback (caller side)
            val callId = currentCallId ?: _currentCall.value?.id
            if (callId != null) {
                startIcePolling(callId)
            }
        }
    }

    private fun handleIceCandidateSignal(signalData: Any?) {
        val data = signalData as? Map<*, *> ?: return
        val candidate = data["candidate"] as? String ?: return
        val sdpMid = data["sdpMid"] as? String ?: ""
        val sdpMLineIndex = (data["sdpMLineIndex"] as? Number)?.toInt() ?: 0

        val candidateKey = "$candidate|$sdpMid|$sdpMLineIndex"
        if (processedIceCandidates.contains(candidateKey)) {
            return
        }
        processedIceCandidates.add(candidateKey)

        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)

        if (remoteDescriptionSet) {
            peerConnection?.addIceCandidate(iceCandidate)
        } else {
            pendingIceCandidates.add(iceCandidate)
        }
    }

    private fun drainPendingIceCandidates() {
        for (candidate in pendingIceCandidates) {
            peerConnection?.addIceCandidate(candidate)
        }
        pendingIceCandidates.clear()
    }

    // ==================== WebRTC Setup ====================

    private fun createPeerConnection(iceServers: List<PeerConnection.IceServer>, isVideo: Boolean) {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "ICE candidate gathered: ${candidate.sdpMid}")
                val callId = currentCallId ?: return
                scope.launch(Dispatchers.IO) {
                    try {
                        vpsClient.sendSignal(
                            callId = callId,
                            signalType = "ice-candidate",
                            signalData = mapOf(
                                "candidate" to candidate.sdp,
                                "sdpMid" to (candidate.sdpMid ?: ""),
                                "sdpMLineIndex" to candidate.sdpMLineIndex
                            ),
                            toDevice = currentToDevice
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send ICE candidate", e)
                    }
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                Log.d(TAG, "PeerConnection state: $newState")
                scope.launch {
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            disconnectTimeoutJob?.cancel()
                            ringingTimeoutJob?.cancel()
                            icePollingJob?.cancel()
                            icePollingJob = null
                            iceRestartAttempted = false // Reset so we can restart again if needed
                            _callState.value = CallState.Connected
                        }
                        PeerConnection.PeerConnectionState.FAILED -> {
                            disconnectTimeoutJob?.cancel()
                            _callState.value = CallState.Failed("Connection lost")
                            endCall()
                        }
                        PeerConnection.PeerConnectionState.DISCONNECTED -> {
                            Log.w(TAG, "PeerConnection disconnected, attempting recovery")
                            disconnectTimeoutJob?.cancel()

                            // Attempt ICE restart if we're the caller and haven't tried yet
                            if (isCallInitiator && !iceRestartAttempted) {
                                scope.launch {
                                    try {
                                        attemptIceRestart()
                                    } catch (e: Exception) {
                                        Log.e(TAG, "ICE restart failed: ${e.message}")
                                    }
                                }
                            }

                            // Allow 15 seconds for ICE restart / natural recovery
                            disconnectTimeoutJob = scope.launch {
                                delay(15000)
                                Log.w(TAG, "PeerConnection still disconnected after 15s, ending call")
                                _callState.value = CallState.Failed("Connection lost")
                                endCall()
                            }
                        }
                        PeerConnection.PeerConnectionState.CLOSED -> {
                            disconnectTimeoutJob?.cancel()
                            _callState.value = CallState.Ended
                        }
                        else -> {}
                    }
                }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}

            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(dataChannel: DataChannel) {}
            override fun onRenegotiationNeeded() {}

            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                // Modern Unified Plan callback for receiving remote tracks
                val track = receiver.track()
                Log.d(TAG, "onAddTrack: kind=${track?.kind()}, id=${track?.id()}, enabled=${track?.enabled()}")
                if (track is VideoTrack) {
                    track.setEnabled(true)
                    scope.launch { _remoteVideoTrackFlow.value = track }
                } else if (track is AudioTrack) {
                    track.setEnabled(true)
                }
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                // Primary Unified Plan callback (preferred over onAddTrack)
                transceiver?.receiver?.track()?.let { track ->
                    Log.d(TAG, "onTrack: kind=${track.kind()}, id=${track.id()}, enabled=${track.enabled()}")
                    if (track is VideoTrack) {
                        track.setEnabled(true)
                        scope.launch { _remoteVideoTrackFlow.value = track }
                    } else if (track is AudioTrack) {
                        track.setEnabled(true)
                    }
                }
            }
        })

        // Add local audio track with echo cancellation, noise suppression, etc.
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }
        val audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory?.createAudioTrack("audio0", audioSource)
        localAudioTrack?.let { peerConnection?.addTrack(it, listOf("stream0")) }

        // Add local video track if video call
        if (isVideo) {
            setupVideoTrack()
        }
    }

    private fun setupVideoTrack() {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames

        val frontCamera = deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
        val backCamera = deviceNames.firstOrNull { enumerator.isBackFacing(it) }
        val cameraName = frontCamera ?: backCamera ?: return

        videoCapturer = enumerator.createCapturer(cameraName, null)

        localVideoSource = peerConnectionFactory?.createVideoSource(videoCapturer!!.isScreencast)
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext)

        val effectObserver = VideoEffectCapturerObserver(
            localVideoSource!!.capturerObserver,
            { _videoEffect.value }
        )
        videoCapturer?.initialize(surfaceTextureHelper, context, effectObserver)
        videoCapturer?.startCapture(1280, 720, 30)

        localVideoTrackInternal = peerConnectionFactory?.createVideoTrack("video0", localVideoSource)
        localVideoTrackInternal?.setEnabled(true)
        localVideoTrackInternal?.let {
            peerConnection?.addTrack(it, listOf("stream0"))
            _localVideoTrackFlow.value = it
        }
    }

    private suspend fun setRemoteDescriptionSuspend(sdp: SessionDescription): Unit = suspendCancellableCoroutine { cont ->
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "Remote description set successfully")
                cont.resume(Unit) {}
            }
            override fun onSetFailure(error: String) {
                val signalingState = peerConnection?.signalingState()
                if (sdp.type == SessionDescription.Type.ANSWER &&
                    signalingState == PeerConnection.SignalingState.STABLE &&
                    error.contains("Called in wrong state", ignoreCase = true)
                ) {
                    Log.w(TAG, "Ignoring setRemoteDescription failure in STABLE state: $error")
                    cont.resume(Unit) {}
                    return
                }
                Log.e(TAG, "Failed to set remote description: $error")
                cont.resumeWith(Result.failure(Exception("setRemoteDescription failed: $error")))
            }
            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onCreateFailure(error: String) {}
        }, sdp) ?: cont.resume(Unit) {}
    }

    private suspend fun setLocalDescriptionSuspend(sdp: SessionDescription): Unit = suspendCancellableCoroutine { cont ->
        peerConnection?.setLocalDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "Local description set successfully")
                cont.resume(Unit) {}
            }
            override fun onSetFailure(error: String) {
                Log.e(TAG, "Failed to set local description: $error")
                cont.resumeWith(Result.failure(Exception("setLocalDescription failed: $error")))
            }
            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onCreateFailure(error: String) {}
        }, sdp) ?: cont.resume(Unit) {}
    }

    private suspend fun createOfferSuspend(): SessionDescription = suspendCancellableCoroutine { cont ->
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) { cont.resume(sdp) {} }
            override fun onCreateFailure(error: String) { cont.resumeWith(Result.failure(Exception(error))) }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String) {}
        }, constraints)
    }

    private suspend fun createAnswerSuspend(): SessionDescription = suspendCancellableCoroutine { cont ->
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) { cont.resume(sdp) {} }
            override fun onCreateFailure(error: String) { cont.resumeWith(Result.failure(Exception(error))) }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String) {}
        }, constraints)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseIceServers(turnCreds: Map<String, Any>): List<PeerConnection.IceServer> {
        val servers = mutableListOf<PeerConnection.IceServer>()
        val iceServersList = turnCreds["iceServers"] as? List<Map<String, Any>>
        if (iceServersList == null) {
            // Fallback to public STUN if TURN credentials unavailable
            Log.w(TAG, "No TURN credentials, falling back to public STUN")
            servers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
            return servers
        }

        for (server in iceServersList) {
            val urls = when (val urlsRaw = server["urls"]) {
                is List<*> -> urlsRaw.filterIsInstance<String>()
                is String -> listOf(urlsRaw)
                else -> continue
            }
            val username = server["username"] as? String
            val credential = server["credential"] as? String

            val builder = PeerConnection.IceServer.builder(urls)
            username?.let { builder.setUsername(it) }
            credential?.let { builder.setPassword(it) }
            servers.add(builder.createIceServer())
        }
        return servers
    }

    private fun cleanupWebRTC() {
        // Phase 1: Stop capture immediately
        try { videoCapturer?.stopCapture() } catch (_: Exception) {}

        // Phase 2: Detach UI from tracks BEFORE disposing native resources.
        // This allows SurfaceViewRenderers to call removeSink() on still-valid tracks.
        _localVideoTrackFlow.value = null
        _remoteVideoTrackFlow.value = null

        // Phase 3: Capture references for background disposal
        val capturer = videoCapturer
        val localTrack = localVideoTrackInternal
        val audioTrack = localAudioTrack
        val videoSource = localVideoSource
        val textureHelper = surfaceTextureHelper
        val pc = peerConnection

        // Phase 4: Clear instance references immediately
        videoCapturer = null
        localVideoTrackInternal = null
        localAudioTrack = null
        localVideoSource = null
        surfaceTextureHelper = null
        peerConnection = null

        // Phase 5: Reset state
        _isVideoEnabled.value = true
        _isMuted.value = false
        remoteDescriptionSet = false
        pendingIceCandidates.clear()
        processedIceCandidates.clear()
        icePollingJob?.cancel()
        icePollingJob = null

        // Phase 6: Dispose native resources on background thread.
        // Delay briefly to allow UI renderers to detach sinks via recomposition.
        // peerConnection.close() is especially heavy and MUST NOT run on the main thread.
        Thread({
            try { Thread.sleep(300) } catch (_: InterruptedException) {}
            try { capturer?.dispose() } catch (e: Exception) { Log.w(TAG, "capturer dispose: ${e.message}") }
            try { localTrack?.dispose() } catch (e: Exception) { Log.w(TAG, "localTrack dispose: ${e.message}") }
            try { audioTrack?.dispose() } catch (e: Exception) { Log.w(TAG, "audioTrack dispose: ${e.message}") }
            try { videoSource?.dispose() } catch (e: Exception) { Log.w(TAG, "videoSource dispose: ${e.message}") }
            try { textureHelper?.dispose() } catch (e: Exception) { Log.w(TAG, "textureHelper dispose: ${e.message}") }
            try { pc?.close() } catch (e: Exception) { Log.w(TAG, "pc close: ${e.message}") }
        }, "WebRTC-Cleanup").start()
    }

    // ==================== Controls ====================

    fun toggleMute(): Boolean {
        val newMuted = !_isMuted.value
        _isMuted.value = newMuted
        localAudioTrack?.setEnabled(!newMuted)
        return newMuted
    }

    fun toggleVideo(): Boolean {
        val newEnabled = !_isVideoEnabled.value
        _isVideoEnabled.value = newEnabled
        localVideoTrackInternal?.setEnabled(newEnabled)
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
        videoCapturer?.switchCamera(null)
        isFrontCamera = !isFrontCamera
    }

    fun refreshVideoTrack() {
        localVideoTrackInternal?.setEnabled(_isVideoEnabled.value)
    }

    fun getEglBaseContext(): EglBase.Context? {
        return eglBase?.eglBaseContext
    }

    fun release() {
        Log.d(TAG, "Releasing SyncFlowCallManager resources")
        cleanupWebRTC()
        scope.cancel()

        // Defer factory/EGL disposal to allow cleanup thread to finish first
        val factory = peerConnectionFactory
        val egl = eglBase
        peerConnectionFactory = null
        eglBase = null
        Thread({
            try { Thread.sleep(600) } catch (_: InterruptedException) {}
            try { factory?.dispose() } catch (e: Exception) { Log.w(TAG, "factory dispose: ${e.message}") }
            try { egl?.release() } catch (e: Exception) { Log.w(TAG, "eglBase release: ${e.message}") }
        }, "WebRTC-Release").start()

        _callState.value = CallState.Idle
        _currentCall.value = null
        _videoEffect.value = VideoEffect.NONE
        vpsClient.webrtcSignalListener = null
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

private class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) {
        Log.e("SdpObserver", "Create failure: $error")
    }
    override fun onSetFailure(error: String) {
        Log.e("SdpObserver", "Set failure: $error")
    }
}
