package com.phoneintegration.app.webrtc

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.phoneintegration.app.models.SyncFlowCall
import com.phoneintegration.app.models.SyncFlowDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resume
import org.webrtc.*
import java.net.URL
import java.util.UUID

/**
 * Manages SyncFlow-to-SyncFlow audio/video calls using WebRTC.
 * Handles peer connection, media tracks, and Firebase signaling.
 * Note: Always uses applicationContext internally to prevent memory leaks.
 */
class SyncFlowCallManager(context: Context) {
    // Always use applicationContext to prevent Activity memory leaks
    private val context: Context = context.applicationContext

    companion object {
        private const val TAG = "SyncFlowCallManager"

        // ICE servers for NAT traversal
        private val ICE_SERVERS = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer()
        )

        // Audio constraints
        private val AUDIO_CONSTRAINTS = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }
    }

    // Firebase
    private val database = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()

    // WebRTC components
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioSource: AudioSource? = null
    private var localVideoSource: VideoSource? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    // EGL context for video rendering
    private var eglBase: EglBase? = null
    private var isInitialized = false

    // Audio manager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Ringtone for outgoing call feedback
    private var ringbackPlayer: MediaPlayer? = null

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

    // Network monitoring
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val _networkQuality = MutableStateFlow<NetworkQuality>(NetworkQuality.Unknown)
    val networkQuality: StateFlow<NetworkQuality> = _networkQuality.asStateFlow()

    private val _connectionQuality = MutableStateFlow<ConnectionQuality>(ConnectionQuality.Unknown)
    val connectionQuality: StateFlow<ConnectionQuality> = _connectionQuality.asStateFlow()

    // Firebase listeners (for device-to-device calls only - user calls use REST polling)
    private var callListener: ValueEventListener? = null
    private var answerListener: ValueEventListener? = null
    private var iceCandidatesListener: ChildEventListener? = null
    private var callStatusListener: ValueEventListener? = null  // Listen for remote party ending call
    private var callStatusRef: DatabaseReference? = null
    private var currentCallRef: DatabaseReference? = null
    private var disconnectJob: Job? = null

    // REST API polling jobs (for user-to-user calls to avoid OOM)
    private var answerPollingJob: Job? = null
    private var icePollingJob: Job? = null
    private var statusPollingJob: Job? = null
    private val processedIceCandidates = mutableSetOf<String>()

    // ICE server caching
    private var cachedIceServers: List<PeerConnection.IceServer>? = null
    private var iceServersLastFetched: Long = 0
    private val iceServersCacheDuration = 12 * 60 * 60 * 1000L // 12 hours in milliseconds

    // Connection quality monitoring
    private var statsMonitoringJob: Job? = null
    private var lastStatsTimestamp = 0L
    private var lastBytesSent = 0L
    private var lastBytesReceived = 0L
    private var lastPacketsSent = 0L
    private var lastPacketsReceived = 0L

    // Connection retry logic
    private var connectionRetryCount = 0
    private val maxRetryAttempts = 3
    private var retryJob: Job? = null
    private var hasVideoFailed = false
    private var isAudioOnlyFallback = false

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

    sealed class NetworkQuality {
        object Unknown : NetworkQuality()
        object Excellent : NetworkQuality()  // WiFi or strong cellular
        object Good : NetworkQuality()       // Moderate cellular
        object Poor : NetworkQuality()       // Weak cellular
        object VeryPoor : NetworkQuality()   // Very weak or no connection
    }

    sealed class ConnectionQuality {
        object Unknown : ConnectionQuality()
        data class Excellent(val rtt: Int, val packetLoss: Double) : ConnectionQuality()  // RTT < 100ms, loss < 1%
        data class Good(val rtt: Int, val packetLoss: Double) : ConnectionQuality()       // RTT < 200ms, loss < 3%
        data class Fair(val rtt: Int, val packetLoss: Double) : ConnectionQuality()       // RTT < 400ms, loss < 5%
        data class Poor(val rtt: Int, val packetLoss: Double) : ConnectionQuality()       // RTT > 400ms or loss > 5%
    }

    /**
     * Initialize WebRTC components
     */
    fun initialize() {
        Log.d(TAG, "Initializing WebRTC")

        // Initialize EGL context
        eglBase = EglBase.create()

        // Initialize PeerConnectionFactory
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase!!.eglBaseContext,
            true,
            true
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()

        Log.d(TAG, "WebRTC initialized successfully")
        isInitialized = true
    }

    private fun ensureInitialized() {
        if (!isInitialized || peerConnectionFactory == null || eglBase == null) {
            Log.d(TAG, "WebRTC not initialized, initializing now")
            initialize()
        }
    }

    /**
     * Get EGL base context for video rendering
     */
    fun getEglBaseContext(): EglBase.Context? = eglBase?.eglBaseContext

    /**
     * Start monitoring network connectivity.
     * Automatically triggers ICE restart on network changes during active calls.
     */
    fun startNetworkMonitoring() {
        Log.d(TAG, "Starting network monitoring")

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available: $network")
                updateNetworkQuality()

                // If call is active, trigger ICE restart to use new network
                if (_callState.value == CallState.Connected || _callState.value == CallState.Connecting) {
                    Log.d(TAG, "Network changed during call - triggering ICE restart")
                    scope.launch {
                        restartIce()
                    }
                }
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost: $network")
                _networkQuality.value = NetworkQuality.VeryPoor
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                updateNetworkQuality(capabilities)
            }
        }

        connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
        updateNetworkQuality()
    }

    /**
     * Stop monitoring network connectivity.
     */
    fun stopNetworkMonitoring() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
                Log.d(TAG, "Network monitoring stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering network callback", e)
            }
        }
        networkCallback = null
    }

    /**
     * Update network quality based on current connectivity.
     */
    private fun updateNetworkQuality(capabilities: NetworkCapabilities? = null) {
        val caps = capabilities ?: connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)

        val quality = when {
            caps == null -> NetworkQuality.VeryPoor
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val linkDownstreamBandwidth = caps.linkDownstreamBandwidthKbps
                when {
                    linkDownstreamBandwidth > 10000 -> NetworkQuality.Excellent  // > 10 Mbps
                    linkDownstreamBandwidth > 5000 -> NetworkQuality.Good        // > 5 Mbps
                    else -> NetworkQuality.Poor                                  // < 5 Mbps WiFi
                }
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                val linkDownstreamBandwidth = caps.linkDownstreamBandwidthKbps
                when {
                    linkDownstreamBandwidth > 5000 -> NetworkQuality.Good    // > 5 Mbps (likely 4G/5G)
                    linkDownstreamBandwidth > 2000 -> NetworkQuality.Good    // > 2 Mbps (moderate)
                    linkDownstreamBandwidth > 500 -> NetworkQuality.Poor     // > 500 Kbps
                    else -> NetworkQuality.VeryPoor                          // < 500 Kbps
                }
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkQuality.Excellent
            else -> NetworkQuality.Unknown
        }

        _networkQuality.value = quality
        Log.d(TAG, "Network quality updated: $quality")
    }

    /**
     * Restart ICE connection to handle network changes.
     */
    private suspend fun restartIce() {
        try {
            Log.d(TAG, "Restarting ICE connection")

            // Refresh ICE servers
            val iceServers = getIceServers()

            // Trigger ICE restart by creating a new offer with iceRestart flag
            peerConnection?.let { pc ->
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
                }

                val offer = withContext(Dispatchers.Default) {
                    suspendCancellableCoroutine<SessionDescription?> { continuation ->
                        pc.createOffer(object : SdpObserver {
                            override fun onCreateSuccess(sdp: SessionDescription?) {
                                if (continuation.isActive) {
                                    continuation.resume(sdp) {}
                                }
                            }
                            override fun onCreateFailure(error: String?) {
                                Log.e(TAG, "ICE restart offer creation failed: $error")
                                if (continuation.isActive) {
                                    continuation.resume(null) {}
                                }
                            }
                            override fun onSetSuccess() {}
                            override fun onSetFailure(error: String?) {}
                        }, constraints)
                    }
                }

                if (offer != null) {
                    Log.d(TAG, "ICE restart offer created, setting local description")
                    // Set local description and send to remote peer
                    // This would require signaling the offer to the remote peer
                    // Implementation depends on your existing offer/answer flow
                } else {
                    Log.e(TAG, "Failed to create ICE restart offer")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error restarting ICE", e)
        }
    }

    /**
     * Handle ICE connection failure with retry logic and fallback strategies.
     */
    private fun handleConnectionFailure() {
        Log.w(TAG, "Connection failed (attempt ${connectionRetryCount + 1}/$maxRetryAttempts)")

        if (connectionRetryCount < maxRetryAttempts) {
            // Calculate exponential backoff: 2^attempt seconds (2s, 4s, 8s)
            val retryDelay = (1L shl connectionRetryCount) * 2000L

            Log.d(TAG, "Scheduling retry in ${retryDelay}ms")
            connectionRetryCount++

            retryJob?.cancel()
            retryJob = scope.launch {
                delay(retryDelay)

                Log.d(TAG, "Attempting to restart ICE connection (retry $connectionRetryCount)")
                try {
                    restartIce()
                } catch (e: Exception) {
                    Log.e(TAG, "Retry failed", e)
                    handleConnectionFailure() // Recursive retry
                }
            }
        } else {
            // Max retries exceeded
            if (!isAudioOnlyFallback && _currentCall.value?.isVideo == true && !hasVideoFailed) {
                Log.w(TAG, "Video call failing - attempting audio-only fallback")
                attemptAudioOnlyFallback()
            } else {
                Log.e(TAG, "All retry attempts exhausted - ending call")
                val errorMessage = when {
                    _networkQuality.value is NetworkQuality.VeryPoor ->
                        "Connection failed: Poor network quality"
                    _networkQuality.value is NetworkQuality.Poor ->
                        "Connection failed: Weak network connection"
                    connectionRetryCount >= maxRetryAttempts ->
                        "Connection failed: Unable to establish connection after $maxRetryAttempts attempts"
                    else ->
                        "Connection failed: Please check your network and try again"
                }
                _callState.value = CallState.Failed(errorMessage)
                scope.launch {
                    if (_currentCall.value?.isUserCall == true) {
                        endUserCall()
                    } else {
                        endCall()
                    }
                }
            }
        }
    }

    /**
     * Attempt to recover the call by falling back to audio-only mode.
     */
    private fun attemptAudioOnlyFallback() {
        Log.d(TAG, "Attempting audio-only fallback")
        hasVideoFailed = true
        isAudioOnlyFallback = true

        try {
            // Disable video track
            localVideoTrack?.setEnabled(false)
            _isVideoEnabled.value = false
            _localVideoTrackFlow.value = null

            // Notify user
            scope.launch(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    "Switching to audio-only due to poor video quality",
                    Toast.LENGTH_LONG
                ).show()
            }

            // Reset retry counter and try again with audio only
            connectionRetryCount = 0
            scope.launch {
                delay(1000)
                restartIce()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio-only fallback failed", e)
            _callState.value = CallState.Failed("Unable to maintain call quality")
            scope.launch {
                if (_currentCall.value?.isUserCall == true) {
                    endUserCall()
                } else {
                    endCall()
                }
            }
        }
    }

    /**
     * Reset retry state when connection succeeds.
     */
    private fun resetRetryState() {
        connectionRetryCount = 0
        retryJob?.cancel()
        retryJob = null
    }

    /**
     * Start monitoring WebRTC connection quality using statistics.
     * Collects stats every 2 seconds and updates connection quality.
     */
    private fun startStatsMonitoring() {
        Log.d(TAG, "Starting stats monitoring")
        statsMonitoringJob?.cancel()

        statsMonitoringJob = scope.launch {
            while (isActive && _callState.value == CallState.Connected) {
                try {
                    collectAndAnalyzeStats()
                    delay(2000) // Collect stats every 2 seconds
                } catch (e: Exception) {
                    Log.e(TAG, "Error collecting stats", e)
                }
            }
        }
    }

    /**
     * Stop monitoring WebRTC statistics.
     */
    private fun stopStatsMonitoring() {
        statsMonitoringJob?.cancel()
        statsMonitoringJob = null
        _connectionQuality.value = ConnectionQuality.Unknown
    }

    /**
     * Collect and analyze WebRTC statistics to determine connection quality.
     */
    private suspend fun collectAndAnalyzeStats() {
        val pc = peerConnection ?: return

        withContext(Dispatchers.Default) {
            try {
                val reports = suspendCoroutine<RTCStatsReport?> { continuation ->
                    pc.getStats { report ->
                        continuation.resume(report)
                    }
                }

                if (reports == null) {
                    Log.w(TAG, "No stats report available")
                    return@withContext
                }

                var rtt = 0
                var packetsLost = 0L
                var packetsReceived = 0L
                var jitter = 0.0
                var bytesSent = 0L
                var bytesReceived = 0L

                // Parse stats report
                for (stat in reports.statsMap.values) {
                    when (stat.type) {
                        "candidate-pair" -> {
                            // Get RTT from candidate pair (current round-trip time)
                            val currentRtt = stat.members["currentRoundTripTime"] as? Double
                            if (currentRtt != null) {
                                rtt = (currentRtt * 1000).toInt() // Convert to ms
                            }
                        }
                        "inbound-rtp" -> {
                            // Get packet loss and jitter for received media
                            val lost = stat.members["packetsLost"] as? Number
                            val received = stat.members["packetsReceived"] as? Number
                            val jitterValue = stat.members["jitter"] as? Double

                            if (lost != null) packetsLost += lost.toLong()
                            if (received != null) packetsReceived += received.toLong()
                            if (jitterValue != null) jitter = jitterValue

                            val bytes = stat.members["bytesReceived"] as? Number
                            if (bytes != null) bytesReceived += bytes.toLong()
                        }
                        "outbound-rtp" -> {
                            // Get sent bytes
                            val bytes = stat.members["bytesSent"] as? Number
                            if (bytes != null) bytesSent += bytes.toLong()
                        }
                    }
                }

                // Calculate packet loss percentage
                val totalPackets = packetsLost + packetsReceived
                val packetLossPercent = if (totalPackets > 0) {
                    (packetsLost.toDouble() / totalPackets.toDouble()) * 100.0
                } else {
                    0.0
                }

                // Calculate bandwidth (bytes per second)
                val currentTime = System.currentTimeMillis()
                val timeDiff = (currentTime - lastStatsTimestamp) / 1000.0

                if (lastStatsTimestamp > 0 && timeDiff > 0) {
                    val sendBandwidth = ((bytesSent - lastBytesSent) * 8 / timeDiff / 1000).toInt() // Kbps
                    val receiveBandwidth = ((bytesReceived - lastBytesReceived) * 8 / timeDiff / 1000).toInt() // Kbps

                    Log.d(TAG, "Stats - RTT: ${rtt}ms, Loss: ${"%.2f".format(packetLossPercent)}%, " +
                            "Jitter: ${"%.2f".format(jitter * 1000)}ms, " +
                            "Send: ${sendBandwidth}kbps, Receive: ${receiveBandwidth}kbps")
                }

                lastStatsTimestamp = currentTime
                lastBytesSent = bytesSent
                lastBytesReceived = bytesReceived
                lastPacketsSent = packetsReceived
                lastPacketsReceived = packetsLost

                // Update connection quality based on metrics
                val quality = when {
                    rtt < 100 && packetLossPercent < 1.0 ->
                        ConnectionQuality.Excellent(rtt, packetLossPercent)
                    rtt < 200 && packetLossPercent < 3.0 ->
                        ConnectionQuality.Good(rtt, packetLossPercent)
                    rtt < 400 && packetLossPercent < 5.0 ->
                        ConnectionQuality.Fair(rtt, packetLossPercent)
                    else ->
                        ConnectionQuality.Poor(rtt, packetLossPercent)
                }

                withContext(Dispatchers.Main) {
                    _connectionQuality.value = quality
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error analyzing stats", e)
            }
        }
    }

    /**
     * Fetches ICE servers dynamically from Cloud Function with Cloudflare TURN credentials.
     * Results are cached for 12 hours to reduce API calls.
     *
     * @return List of ICE servers including STUN and TURN servers
     */
    private suspend fun getIceServers(): List<PeerConnection.IceServer> {
        // Check cache validity
        val currentTime = System.currentTimeMillis()
        if (cachedIceServers != null && (currentTime - iceServersLastFetched) < iceServersCacheDuration) {
            Log.d(TAG, "Using cached ICE servers")
            return cachedIceServers!!
        }

        Log.d(TAG, "Fetching fresh ICE servers from Cloud Function")

        return try {
            // Call the Cloud Function to get TURN credentials
            val functions = com.google.firebase.functions.FirebaseFunctions.getInstance()
            val callable = functions.getHttpsCallable("getTurnCredentials")
            val result = callable.call().await()

            @Suppress("UNCHECKED_CAST")
            val data = result.data as? Map<String, Any> ?: throw Exception("Invalid response format")
            @Suppress("UNCHECKED_CAST")
            val iceServersData = data["iceServers"] as? List<Map<String, Any>>
                ?: throw Exception("No iceServers in response")

            // Parse ICE servers from response
            val servers = mutableListOf<PeerConnection.IceServer>()

            for (serverData in iceServersData) {
                val urls = when (val urlData = serverData["urls"]) {
                    is String -> listOf(urlData)
                    is List<*> -> urlData.filterIsInstance<String>()
                    else -> continue
                }

                val username = serverData["username"] as? String
                val credential = serverData["credential"] as? String

                for (url in urls) {
                    val builder = PeerConnection.IceServer.builder(url)
                    if (username != null && credential != null) {
                        builder.setUsername(username)
                        builder.setPassword(credential)
                    }
                    servers.add(builder.createIceServer())
                }
            }

            // Cache the fetched servers
            cachedIceServers = servers
            iceServersLastFetched = currentTime

            Log.d(TAG, "✅ Cloudflare TURN credentials fetched successfully (${servers.size} servers)")
            servers

        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch ICE servers from Cloud Function: ${e.message}", e)
            Log.d(TAG, "Falling back to public STUN servers")

            // Fallback to public STUN servers
            val fallbackServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
            )

            cachedIceServers = fallbackServers
            iceServersLastFetched = currentTime

            fallbackServers
        }
    }

    /**
     * Start an outgoing call to a user by their phone number.
     * Looks up the recipient's UID and creates call signaling in their Firebase path.
     */
    suspend fun startCallToUser(
        recipientPhoneNumber: String,
        recipientName: String,
        isVideo: Boolean
    ): Result<String> = withContext(Dispatchers.Main) {
        try {
            ensureInitialized()
            startNetworkMonitoring()
            _callState.value = CallState.Initializing

            val myUserId = auth.currentUser?.uid
                ?: return@withContext Result.failure(Exception("Not authenticated"))

            // Normalize phone number (remove spaces, dashes, etc.)
            val normalizedPhone = recipientPhoneNumber.replace(Regex("[^0-9+]"), "")
            val phoneKey = normalizedPhone.replace("+", "")

            Log.d(TAG, "Looking up phone_to_uid for: $phoneKey")

            // Look up recipient's UID using REST API to avoid OOM from goOnline()
            val recipientUid = withContext(Dispatchers.IO) {
                lookupPhoneToUid(phoneKey)
            }

            if (recipientUid == null) {
                Log.e(TAG, "User not found in phone_to_uid for: $phoneKey")
                return@withContext Result.failure(Exception("User not found on SyncFlow. They need to install SyncFlow to receive video calls."))
            }
            Log.d(TAG, "Found recipient UID: $recipientUid")

            val deviceId = getDeviceId()
            val deviceName = getDeviceName()
            val callId = UUID.randomUUID().toString()

            // Get my phone number for caller info (from Firebase or TelephonyManager)
            val myPhoneNumber = getMyPhoneNumber()

            // Create call object - mark as user call since we're calling a user by phone number
            val call = SyncFlowCall.createOutgoing(
                callId = callId,
                callerId = myUserId,  // Use UID instead of device ID
                callerName = deviceName,
                calleeId = recipientUid,
                calleeName = recipientName,
                isVideo = isVideo
            ).copy(isUserCall = true)

            _currentCall.value = call
            Log.d(TAG, "Created outgoing user call: callId=$callId, isUserCall=${call.isUserCall}")

            // Prepare call data
            val callData = call.toMap().toMutableMap()
            callData["callerUid"] = myUserId
            callData["callerPhone"] = myPhoneNumber

            // Write call to RECIPIENT's Firebase path using REST API (to avoid OOM)
            val callPath = "users/$recipientUid/incoming_syncflow_calls/$callId"
            val writeSuccess = withContext(Dispatchers.IO) {
                writeToFirebaseRest(callPath, callData)
            }

            if (!writeSuccess) {
                Log.e(TAG, "Failed to write call to Firebase")
                _callState.value = CallState.Failed("Failed to initiate call")
                return@withContext Result.failure(Exception("Failed to initiate call"))
            }

            Log.d(TAG, "Call data written to recipient's path via REST API")

            // Clear any stale currentCallRef from previous calls
            currentCallRef = null

            // Also write to my path for tracking (fire and forget via REST)
            withContext(Dispatchers.IO) {
                writeToFirebaseRest("users/$myUserId/outgoing_syncflow_calls/$callId", callData)
            }

            // Send FCM push notification to wake up recipient's device
            sendCallNotificationToUser(recipientUid, callId, deviceName, myPhoneNumber, isVideo)

            // IMPORTANT: We do NOT call database.goOnline() to avoid OOM crash
            // All signaling is done via REST API polling instead of Firebase SDK listeners
            Log.d(TAG, "Setting up WebRTC (using REST API for signaling)")

            // Setup peer connection for user call - ICE candidates sent via REST API
            setupPeerConnectionForUserCallRest(recipientUid, callId, isOutgoing = true)

            // Create media tracks
            createMediaTracks(isVideo)

            // Create and send offer to recipient's incoming_syncflow_calls path via REST
            createAndSendOfferForUserCallRest(recipientUid, callId)

            // Poll for answer and ICE candidates via REST API (instead of Firebase listeners)
            startAnswerPollingRest(recipientUid, callId)
            startIceCandidatesPollingRest(recipientUid, callId, isOutgoing = true)

            // Setup audio
            setupAudio()

            _callState.value = CallState.Ringing

            // Start ringback tone so caller hears feedback
            startRingbackTone()

            // Poll for call status changes via REST (if remote party ends the call)
            startCallStatusPollingRest(recipientUid, callId)

            // Start timeout timer for user call
            startCallTimeoutForUserCall(recipientUid, callId)

            Log.d(TAG, "Started call to $recipientName ($recipientPhoneNumber)")
            Result.success(callId)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting call", e)
            _callState.value = CallState.Failed(e.message ?: "Unknown error")
            cleanup()
            Result.failure(e)
        }
    }

    /**
     * Start an outgoing call to a device (for device-to-device calls)
     */
    suspend fun startCall(
        calleeDeviceId: String,
        calleeName: String,
        isVideo: Boolean
    ): Result<String> = withContext(Dispatchers.Main) {
        try {
            ensureInitialized()
            startNetworkMonitoring()
            _callState.value = CallState.Initializing

            val userId = auth.currentUser?.uid
                ?: return@withContext Result.failure(Exception("Not authenticated"))

            val deviceId = getDeviceId()
            val deviceName = getDeviceName()
            val callId = UUID.randomUUID().toString()

            // Create call object
            val call = SyncFlowCall.createOutgoing(
                callId = callId,
                callerId = deviceId,
                callerName = deviceName,
                calleeId = calleeDeviceId,
                calleeName = calleeName,
                isVideo = isVideo
            )

            _currentCall.value = call

            // Write call to Firebase
            val callRef = database.reference
                .child("users")
                .child(userId)
                .child("syncflow_calls")
                .child(callId)

            callRef.setValue(call.toMap()).await()
            currentCallRef = callRef

            // Setup peer connection
            setupPeerConnection(userId, callId, isOutgoing = true)

            // Create media tracks
            createMediaTracks(isVideo)

            // Create and send offer
            createAndSendOffer(userId, callId)

            // Listen for answer and ICE candidates
            listenForAnswer(userId, callId)
            listenForIceCandidates(userId, callId, isOutgoing = true)

            // Setup audio
            setupAudio()

            _callState.value = CallState.Ringing

            // Start timeout timer
            startCallTimeout(userId, callId)

            // Listen for call status changes (if remote party ends the call)
            listenForCallStatusChanges()

            Result.success(callId)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting call", e)
            _callState.value = CallState.Failed(e.message ?: "Unknown error")
            Result.failure(e)
        }
    }

    private fun getMyPhoneNumberLocal(): String? {
        return try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            telephonyManager?.line1Number
        } catch (e: SecurityException) {
            Log.d(TAG, "No permission to read phone number from TelephonyManager")
            null
        }
    }

    /**
     * Get my phone number from Firebase SIMs data (preferred) or local TelephonyManager (fallback)
     */
    private suspend fun getMyPhoneNumber(): String {
        val userId = auth.currentUser?.uid ?: return "Unknown"

        return try {
            // First try to get from Firebase SIMs data
            val simsSnapshot = database.reference
                .child("users")
                .child(userId)
                .child("sims")
                .get()
                .await()

            val simsData = simsSnapshot.value as? Map<String, Map<String, Any?>>
            if (simsData != null) {
                for ((_, sim) in simsData) {
                    val phoneNumber = sim["phoneNumber"] as? String
                    if (!phoneNumber.isNullOrEmpty() && phoneNumber != "Unknown") {
                        Log.d(TAG, "Got phone number from Firebase SIMs")
                        return phoneNumber
                    }
                }
            }

            // Fallback to local TelephonyManager
            val localNumber = getMyPhoneNumberLocal()
            if (!localNumber.isNullOrEmpty()) {
                Log.d(TAG, "Got phone number from TelephonyManager")
                return localNumber
            }

            "Unknown"
        } catch (e: Exception) {
            Log.w(TAG, "Error getting phone number from Firebase: ${e.message}")
            getMyPhoneNumberLocal() ?: "Unknown"
        }
    }

    /**
     * Answer an incoming call
     */
    suspend fun answerCall(userId: String, callId: String, withVideo: Boolean): Result<Unit> =
        withContext(Dispatchers.Main) {
            try {
                ensureInitialized()
                startNetworkMonitoring()
                _callState.value = CallState.Connecting

                val callRef = database.reference
                    .child("users")
                    .child(userId)
                    .child("syncflow_calls")
                    .child(callId)

                currentCallRef = callRef

                // Get call data
                val snapshot = callRef.get().await()
                val callData = snapshot.value as? Map<String, Any?>
                    ?: return@withContext Result.failure(Exception("Call not found"))

                val call = SyncFlowCall.fromMap(callId, callData)
                _currentCall.value = call

                // Update call status
                callRef.child("status").setValue("active").await()
                callRef.child("answeredAt").setValue(ServerValue.TIMESTAMP).await()

                // Listen for call status changes (if caller ends the call)
                listenForCallStatusChanges()

                // Setup peer connection
                setupPeerConnection(userId, callId, isOutgoing = false)

                // Create media tracks
                createMediaTracks(withVideo)

                // Get the offer and set as remote description
                val offer = call.offer
                    ?: return@withContext Result.failure(Exception("No offer in call"))

                val offerSdp = SessionDescription(
                    SessionDescription.Type.OFFER,
                    offer.sdp
                )

                // CRITICAL: Must await setRemoteDescription completion before creating answer
                val setRemoteResult = setRemoteDescriptionAsync(offerSdp)
                if (setRemoteResult.isFailure) {
                    val error = setRemoteResult.exceptionOrNull()?.message ?: "Unknown error"
                    Log.e(TAG, "Failed to set remote description: $error")
                    return@withContext Result.failure(Exception("Failed to set remote description: $error"))
                }
                Log.d(TAG, "Remote description set from offer successfully")

                // Create and send answer
                createAndSendAnswer(userId, callId)

                // Listen for ICE candidates
                listenForIceCandidates(userId, callId, isOutgoing = false)

                // Setup audio
                setupAudio()

                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Error answering call", e)
                _callState.value = CallState.Failed(e.message ?: "Unknown error")
                Result.failure(e)
            }
        }

    /**
     * Reject an incoming call
     */
    suspend fun rejectCall(userId: String, callId: String): Result<Unit> =
        withContext(Dispatchers.Main) {
            try {
                val callRef = database.reference
                    .child("users")
                    .child(userId)
                    .child("syncflow_calls")
                    .child(callId)

                callRef.child("status").setValue("rejected").await()
                callRef.child("endedAt").setValue(ServerValue.TIMESTAMP).await()

                _callState.value = CallState.Ended
                cleanup()

                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Error rejecting call", e)
                Result.failure(e)
            }
        }

    /**
     * End the current call (handles both user-to-user and device-to-device calls)
     */
    suspend fun endCall(): Result<Unit> = withContext(Dispatchers.Main) {
        try {
            Log.d(TAG, "endCall() called")

            // Always stop ringback/ringtone first to prevent audio issues
            stopRingbackTone()

            val currentCallValue = _currentCall.value
            val callId = currentCallValue?.id

            Log.d(TAG, "endCall: callId=$callId, isUserCall=${currentCallValue?.isUserCall}")

            // For user-to-user calls, use REST API (delegate to endUserCall)
            if (currentCallValue?.isUserCall == true && callId != null) {
                Log.d(TAG, "Delegating to endUserCall for user call")
                return@withContext endUserCall()
            }

            // Use the stored call reference if available (this is set during call setup
            // and points to the correct Firebase path regardless of caller/callee)
            val callRef = currentCallRef
            if (callRef != null) {
                try {
                    callRef.child("status").setValue("ended").await()
                    callRef.child("endedAt").setValue(ServerValue.TIMESTAMP).await()
                    Log.d(TAG, "Call status updated to ended in Firebase via currentCallRef")
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating call status in Firebase, continuing with cleanup", e)
                }
            } else if (callId != null) {
                // Fallback: try to determine the path manually
                val userId = auth.currentUser?.uid
                if (userId != null) {
                    val isUserCall = currentCallValue?.isUserCall ?: false
                    val callPath = if (isUserCall) "incoming_syncflow_calls" else "syncflow_calls"

                    Log.d(TAG, "Ending call at fallback path: users/$userId/$callPath/$callId")

                    val fallbackRef = database.reference
                        .child("users")
                        .child(userId)
                        .child(callPath)
                        .child(callId)

                    try {
                        fallbackRef.child("status").setValue("ended").await()
                        fallbackRef.child("endedAt").setValue(ServerValue.TIMESTAMP).await()
                        Log.d(TAG, "Call status updated to ended via fallback path")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating call status via fallback, continuing with cleanup", e)
                    }
                }
            } else {
                Log.w(TAG, "No call reference or call ID, just cleaning up")
            }

            _callState.value = CallState.Ended
            cleanup()

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error ending call", e)
            // Always clean up even on error
            _callState.value = CallState.Ended
            cleanup()
            Result.failure(e)
        }
    }

    /**
     * Toggle microphone mute
     */
    fun toggleMute() {
        val newMuteState = !_isMuted.value
        _isMuted.value = newMuteState
        localAudioTrack?.setEnabled(!newMuteState)
        Log.d(TAG, "Mute toggled: $newMuteState")
    }

    /**
     * Toggle video
     */
    fun toggleVideo() {
        val newVideoState = !_isVideoEnabled.value
        _isVideoEnabled.value = newVideoState
        localVideoTrack?.setEnabled(newVideoState)
        Log.d(TAG, "Video toggled: $newVideoState")
    }

    /**
     * Refresh video track when returning from background.
     * This ensures video is re-enabled and the track is re-emitted to trigger UI updates.
     * Helps fix issues where video disappears after app goes to background and returns.
     */
    fun refreshVideoTrack() {
        Log.d(TAG, "Refreshing video track...")

        // Re-enable the video track if it exists
        localVideoTrack?.let { track ->
            if (_isVideoEnabled.value) {
                track.setEnabled(true)
                Log.d(TAG, "Local video track re-enabled")
            }
        }

        // Re-emit the local video track to trigger UI updates
        val currentLocalTrack = localVideoTrack
        if (currentLocalTrack != null) {
            _localVideoTrackFlow.value = null
            _localVideoTrackFlow.value = currentLocalTrack
            Log.d(TAG, "Local video track flow re-emitted: $currentLocalTrack")
        }

        // Re-emit remote video track as well
        val currentRemoteTrack = _remoteVideoTrackFlow.value
        if (currentRemoteTrack != null) {
            _remoteVideoTrackFlow.value = null
            _remoteVideoTrackFlow.value = currentRemoteTrack
            Log.d(TAG, "Remote video track flow re-emitted: $currentRemoteTrack")
        }

        // Ensure video capturer is still running
        try {
            if (videoCapturer != null && _isVideoEnabled.value) {
                // The capturer should still be running, but log for debugging
                Log.d(TAG, "Video capturer status: active")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking video capturer", e)
        }
    }

    fun toggleFaceFocus() {
        if (_videoEffect.value == VideoEffect.FACE_FOCUS) {
            _videoEffect.value = VideoEffect.NONE
            Log.d(TAG, "Face focus toggled off")
            return
        }

        Toast.makeText(
            context,
            "Face focus is temporarily disabled on Android",
            Toast.LENGTH_SHORT
        ).show()
        _videoEffect.value = VideoEffect.NONE
        Log.w(TAG, "Face focus toggle blocked (stability safeguard)")
    }

    fun toggleBackgroundBlur() {
        _videoEffect.value = if (_videoEffect.value == VideoEffect.BACKGROUND_BLUR) {
            VideoEffect.NONE
        } else {
            VideoEffect.BACKGROUND_BLUR
        }
        Log.d(TAG, "Background blur toggled: ${_videoEffect.value}")
    }

    /**
     * Switch camera (front/back)
     */
    fun switchCamera() {
        videoCapturer?.switchCamera(null)
        Log.d(TAG, "Camera switched")
    }

    /**
     * Set video surface views for rendering
     */
    fun setLocalVideoSink(sink: VideoSink) {
        localVideoTrack?.addSink(sink)
    }

    fun setRemoteVideoSink(sink: VideoSink) {
        _remoteVideoTrackFlow.value?.addSink(sink)
    }

    /**
     * Listen for incoming calls (device-to-device)
     */
    fun listenForIncomingCalls(userId: String): Flow<SyncFlowCall> = callbackFlow {
        val callsRef = database.reference
            .child("users")
            .child(userId)
            .child("syncflow_calls")

        val listener = callsRef.orderByChild("status").equalTo("ringing")
            .addChildEventListener(object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    val callData = snapshot.value as? Map<String, Any?> ?: return
                    val call = SyncFlowCall.fromMap(snapshot.key ?: "", callData)

                    // Only notify for incoming calls (from macOS)
                    if (call.calleePlatform == "android" && call.isRinging) {
                        trySend(call)
                    }
                }

                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onChildRemoved(snapshot: DataSnapshot) {}
                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Incoming calls listener cancelled: ${error.message}")
                }
            })

        awaitClose {
            callsRef.removeEventListener(listener)
        }
    }

    /**
     * Listen for incoming user-to-user SyncFlow calls
     * These are calls from other SyncFlow users (identified by phone number)
     */
    fun listenForIncomingUserCalls(userId: String): Flow<IncomingUserCall> = callbackFlow {
        val callsRef = database.reference
            .child("users")
            .child(userId)
            .child("incoming_syncflow_calls")

        val listener = callsRef.orderByChild("status").equalTo("ringing")
            .addChildEventListener(object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    val callData = snapshot.value as? Map<String, Any?> ?: return
                    val callId = snapshot.key ?: return

                    val callerUid = callData["callerUid"] as? String ?: return
                    val callerPhone = callData["callerPhone"] as? String ?: "Unknown"
                    val callerName = callData["callerName"] as? String ?: "Unknown"
                    val callerPlatform = callData["callerPlatform"] as? String ?: "unknown"
                    val callType = callData["callType"] as? String ?: "audio"
                    val isVideo = callType == "video"

                    Log.d(TAG, "Incoming user call from $callerName ($callerPhone)")

                    val incomingCall = IncomingUserCall(
                        callId = callId,
                        callerUid = callerUid,
                        callerPhone = callerPhone,
                        callerName = callerName,
                        callerPlatform = callerPlatform,
                        isVideo = isVideo
                    )

                    trySend(incomingCall)
                }

                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                    // Check if call was cancelled or ended
                    val callData = snapshot.value as? Map<String, Any?> ?: return
                    val status = callData["status"] as? String ?: return

                    if (status != "ringing") {
                        Log.d(TAG, "Incoming call status changed to: $status")
                    }
                }

                override fun onChildRemoved(snapshot: DataSnapshot) {
                    Log.d(TAG, "Incoming call removed")
                }

                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Incoming user calls listener cancelled: ${error.message}")
                }
            })

        awaitClose {
            callsRef.removeEventListener(listener)
        }
    }

    /**
     * Answer an incoming user-to-user call (uses REST API to avoid OOM)
     */
    suspend fun answerUserCall(callId: String, withVideo: Boolean): Result<Unit> =
        withContext(Dispatchers.Main) {
            try {
                ensureInitialized()
                startNetworkMonitoring()
                val myUserId = auth.currentUser?.uid
                    ?: return@withContext Result.failure(Exception("Not authenticated"))

                Log.d(TAG, "Answering user call: $callId, withVideo: $withVideo (using REST API)")
                _callState.value = CallState.Connecting

                // Get call data via REST API (avoid goOnline OOM)
                val callPath = "users/$myUserId/incoming_syncflow_calls/$callId"
                val callData = withContext(Dispatchers.IO) {
                    readFromFirebaseRest(callPath)
                } ?: return@withContext Result.failure(Exception("Call not found"))

                val callerUid = callData["callerUid"] as? String
                    ?: return@withContext Result.failure(Exception("Caller UID not found"))

                // Parse and set the current call - explicitly mark as user call
                val call = SyncFlowCall.fromMap(callId, callData).copy(isUserCall = true)
                _currentCall.value = call
                Log.d(TAG, "Set current call: ${call.callerName}, isVideo: ${call.isVideo}, isUserCall: ${call.isUserCall}")

                // Update call status via REST API
                withContext(Dispatchers.IO) {
                    writeToFirebaseRestValue("$callPath/status", "active")
                    writeToFirebaseRestValue("$callPath/answeredAt", System.currentTimeMillis())
                }

                // Setup peer connection using REST for ICE candidates
                setupPeerConnectionForUserCallRest(myUserId, callId, isOutgoing = false)

                // Get the offer and set as remote description FIRST
                @Suppress("UNCHECKED_CAST")
                val offerData = callData["offer"] as? Map<String, Any?>
                    ?: return@withContext Result.failure(Exception("No offer in call"))
                val offerSdp = offerData["sdp"] as? String
                    ?: return@withContext Result.failure(Exception("No SDP in offer"))
                val offerType = offerData["type"] as? String ?: "offer"

                val offer = SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(offerType),
                    offerSdp
                )

                // CRITICAL: Must await setRemoteDescription completion before creating answer
                val setRemoteResult = setRemoteDescriptionAsync(offer)
                if (setRemoteResult.isFailure) {
                    val error = setRemoteResult.exceptionOrNull()?.message ?: "Unknown error"
                    Log.e(TAG, "Failed to set remote description: $error")
                    return@withContext Result.failure(Exception("Failed to set remote description: $error"))
                }
                Log.d(TAG, "Remote description set from offer successfully")

                // Create media tracks AFTER setting remote description
                createMediaTracks(withVideo)
                Log.d(TAG, "Media tracks created, withVideo: $withVideo")

                // Create and send answer via REST API
                createAndSendAnswerForUserCallRest(myUserId, callId)

                // Poll for ICE candidates from caller via REST
                startIceCandidatesPollingRest(myUserId, callId, isOutgoing = false)

                // Poll for call status changes via REST
                startCallStatusPollingRest(myUserId, callId)

                // Setup audio
                setupAudio()

                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Error answering user call", e)
                _callState.value = CallState.Failed(e.message ?: "Unknown error")
                Result.failure(e)
            }
        }

    /**
     * Reject an incoming user-to-user call (uses REST API to avoid OOM)
     */
    suspend fun rejectUserCall(callId: String): Result<Unit> =
        withContext(Dispatchers.Main) {
            try {
                val myUserId = auth.currentUser?.uid
                    ?: return@withContext Result.failure(Exception("Not authenticated"))

                val callPath = "users/$myUserId/incoming_syncflow_calls/$callId"

                // Update call status via REST API
                withContext(Dispatchers.IO) {
                    writeToFirebaseRestValue("$callPath/status", "rejected")
                    writeToFirebaseRestValue("$callPath/endedAt", System.currentTimeMillis())
                }

                _callState.value = CallState.Ended
                cleanup()

                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Error rejecting user call", e)
                Result.failure(e)
            }
        }

    private suspend fun setupPeerConnectionForUserCall(userId: String, callId: String, isOutgoing: Boolean) {
        val iceServers = getIceServers()
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let {
                        Log.d(TAG, "ICE candidate generated (user call)")
                        scope.launch {
                            sendIceCandidateForUserCall(userId, callId, it, isOutgoing)
                        }
                    }
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    Log.d(TAG, "ICE connection state (user call): $state")
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED -> {
                            cancelDisconnectTimeout()
                            stopRingbackTone()
                            _callState.value = CallState.Connected
                            resetRetryState()
                            startStatsMonitoring()
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            cancelDisconnectTimeout()
                            stopRingbackTone()
                            handleConnectionFailure()
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            scheduleDisconnectTimeout()
                        }
                        PeerConnection.IceConnectionState.CLOSED -> {
                            cancelDisconnectTimeout()
                            stopRingbackTone()
                            if (_callState.value != CallState.Ended && _callState.value != CallState.Idle) {
                                scope.launch { endUserCall() }
                            }
                        }
                        else -> {}
                    }
                }

                override fun onTrack(transceiver: RtpTransceiver?) {
                    Log.d(TAG, "onTrack called (user call), transceiver: $transceiver")
                    transceiver?.receiver?.track()?.let { track ->
                        Log.d(TAG, "Remote track received (user call): kind=${track.kind()}, id=${track.id()}, enabled=${track.enabled()}")
                        if (track is VideoTrack) {
                            Log.d(TAG, "Setting remote video track (user call)")
                            _remoteVideoTrackFlow.value = track
                            track.setEnabled(true)
                        } else if (track is AudioTrack) {
                            Log.d(TAG, "Remote audio track received (user call)")
                            track.setEnabled(true)
                        }
                    }
                }

                override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                    Log.d(TAG, "Signaling state (user call): $state")
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                    Log.d(TAG, "Peer connection state (user call): $newState")
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            Log.d(TAG, "WebRTC peer connection CONNECTED!")
                        }
                        PeerConnection.PeerConnectionState.FAILED -> {
                            Log.e(TAG, "WebRTC peer connection FAILED!")
                        }
                        else -> {}
                    }
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) {
                    Log.d(TAG, "ICE connection receiving change (user call): $receiving")
                }

                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                    Log.d(TAG, "ICE gathering state (user call): $state")
                }
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(channel: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            }
        )
    }

    private suspend fun createAndSendOfferForUserCall(recipientUid: String, callId: String) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        suspendCancellableCoroutine<Unit> { continuation ->
            peerConnection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    sdp?.let {
                        peerConnection?.setLocalDescription(SdpObserverAdapter(), it)

                        // Send offer to recipient's incoming_syncflow_calls path
                        scope.launch {
                            try {
                                val offerData = mapOf(
                                    "sdp" to it.description,
                                    "type" to it.type.canonicalForm()
                                )
                                database.reference
                                    .child("users")
                                    .child(recipientUid)
                                    .child("incoming_syncflow_calls")
                                    .child(callId)
                                    .child("offer")
                                    .setValue(offerData)
                                    .await()

                                Log.d(TAG, "Offer sent for user call")
                                continuation.resume(Unit) {}
                            } catch (e: Exception) {
                                Log.e(TAG, "Error sending offer for user call", e)
                                continuation.cancel(e)
                            }
                        }
                    }
                }

                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) {
                    Log.e(TAG, "Create offer failed (user call): $error")
                    continuation.cancel(Exception(error))
                }
                override fun onSetFailure(error: String?) {}
            }, constraints)
        }
    }

    private fun listenForAnswerForUserCall(recipientUid: String, callId: String) {
        val answerRef = database.reference
            .child("users")
            .child(recipientUid)
            .child("incoming_syncflow_calls")
            .child(callId)
            .child("answer")

        answerListener = answerRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val answerData = snapshot.value as? Map<String, Any?> ?: return
                val sdp = answerData["sdp"] as? String ?: return
                val type = answerData["type"] as? String ?: return

                Log.d(TAG, "Answer received for user call")

                val answer = SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(type),
                    sdp
                )
                peerConnection?.setRemoteDescription(SdpObserverAdapter(), answer)
                _callState.value = CallState.Connecting
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Answer listener cancelled (user call): ${error.message}")
            }
        })
    }

    private suspend fun createAndSendAnswerForUserCall(userId: String, callId: String) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        suspendCancellableCoroutine<Unit> { continuation ->
            peerConnection?.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    sdp?.let {
                        peerConnection?.setLocalDescription(SdpObserverAdapter(), it)

                        // Send answer to my incoming_syncflow_calls path
                        scope.launch {
                            try {
                                val answerData = mapOf(
                                    "sdp" to it.description,
                                    "type" to it.type.canonicalForm()
                                )
                                database.reference
                                    .child("users")
                                    .child(userId)
                                    .child("incoming_syncflow_calls")
                                    .child(callId)
                                    .child("answer")
                                    .setValue(answerData)
                                    .await()

                                Log.d(TAG, "Answer sent for user call")
                                continuation.resume(Unit) {}
                            } catch (e: Exception) {
                                Log.e(TAG, "Error sending answer for user call", e)
                                continuation.cancel(e)
                            }
                        }
                    }
                }

                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) {
                    Log.e(TAG, "Create answer failed (user call): $error")
                    continuation.cancel(Exception(error))
                }
                override fun onSetFailure(error: String?) {}
            }, constraints)
        }
    }

    private fun listenForIceCandidatesForUserCall(userId: String, callId: String, isOutgoing: Boolean) {
        val icePath = if (isOutgoing) "ice_callee" else "ice_caller"
        val iceRef = database.reference
            .child("users")
            .child(userId)
            .child("incoming_syncflow_calls")
            .child(callId)
            .child(icePath)

        iceCandidatesListener = iceRef.addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val candidateData = snapshot.value as? Map<String, Any?> ?: return
                val candidate = candidateData["candidate"] as? String ?: return
                val sdpMid = candidateData["sdpMid"] as? String
                val sdpMLineIndex = (candidateData["sdpMLineIndex"] as? Number)?.toInt() ?: 0

                Log.d(TAG, "Remote ICE candidate received (user call)")

                val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
                peerConnection?.addIceCandidate(iceCandidate)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "ICE candidates listener cancelled (user call): ${error.message}")
            }
        })
    }

    private suspend fun sendIceCandidateForUserCall(
        userId: String,
        callId: String,
        candidate: IceCandidate,
        isOutgoing: Boolean
    ) {
        val icePath = if (isOutgoing) "ice_caller" else "ice_callee"

        try {
            val candidateData = mapOf(
                "candidate" to candidate.sdp,
                "sdpMid" to candidate.sdpMid,
                "sdpMLineIndex" to candidate.sdpMLineIndex
            )

            database.reference
                .child("users")
                .child(userId)
                .child("incoming_syncflow_calls")
                .child(callId)
                .child(icePath)
                .push()
                .setValue(candidateData)
                .await()

            Log.d(TAG, "ICE candidate sent (user call)")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending ICE candidate (user call)", e)
        }
    }

    /**
     * End the current user-to-user call
     */
    suspend fun endUserCall(): Result<Unit> = withContext(Dispatchers.Main) {
        try {
            val myUserId = auth.currentUser?.uid
                ?: return@withContext Result.failure(Exception("Not authenticated"))
            val callId = _currentCall.value?.id
                ?: return@withContext Result.failure(Exception("No active call"))
            val call = _currentCall.value

            // Use REST API to update call status (avoid Firebase SDK OOM)
            val timestamp = System.currentTimeMillis()

            if (call?.isOutgoing == true && !call.calleeId.isNullOrBlank()) {
                // Update recipient's incoming call
                val recipientPath = "users/${call.calleeId}/incoming_syncflow_calls/$callId"
                withContext(Dispatchers.IO) {
                    writeToFirebaseRestValue("$recipientPath/status", "ended")
                    writeToFirebaseRestValue("$recipientPath/endedAt", timestamp)
                }

                // Update my outgoing call
                val myCallPath = "users/$myUserId/outgoing_syncflow_calls/$callId"
                withContext(Dispatchers.IO) {
                    writeToFirebaseRestValue("$myCallPath/status", "ended")
                    writeToFirebaseRestValue("$myCallPath/endedAt", timestamp)
                }
            } else {
                // Update my incoming call path
                val callPath = "users/$myUserId/incoming_syncflow_calls/$callId"
                withContext(Dispatchers.IO) {
                    writeToFirebaseRestValue("$callPath/status", "ended")
                    writeToFirebaseRestValue("$callPath/endedAt", timestamp)
                }
            }

            _callState.value = CallState.Ended
            cleanup()

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error ending user call", e)
            // Fall back to regular cleanup
            cleanup()
            Result.failure(e)
        }
    }

    private fun startCallTimeoutForUserCall(recipientUid: String, callId: String) {
        scope.launch {
            delay(SyncFlowCall.CALL_TIMEOUT_MS)

            // Check if still ringing
            if (_callState.value == CallState.Ringing) {
                Log.d(TAG, "User call timed out")

                // Update status via REST API
                withContext(Dispatchers.IO) {
                    writeToFirebaseRestValue(
                        "users/$recipientUid/incoming_syncflow_calls/$callId/status",
                        "missed"
                    )
                }

                _callState.value = CallState.Failed("Call timed out - no answer")
                cleanup()
            }
        }
    }

    // ========== REST API-based signaling methods (to avoid OOM from goOnline()) ==========

    /**
     * Setup peer connection for user calls using REST API for ICE candidates.
     * This avoids the OOM crash caused by Firebase SDK's goOnline().
     */
    private suspend fun setupPeerConnectionForUserCallRest(userId: String, callId: String, isOutgoing: Boolean) {
        val iceServers = getIceServers()
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let {
                        Log.d(TAG, "ICE candidate generated (REST mode)")
                        scope.launch {
                            sendIceCandidateForUserCallRest(userId, callId, it, isOutgoing)
                        }
                    }
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    Log.d(TAG, "ICE connection state (REST mode): $state")
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED -> {
                            cancelDisconnectTimeout()
                            stopRingbackTone()
                            _callState.value = CallState.Connected
                            resetRetryState()
                            startStatsMonitoring()
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            cancelDisconnectTimeout()
                            stopRingbackTone()
                            handleConnectionFailure()
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            scheduleDisconnectTimeout()
                        }
                        PeerConnection.IceConnectionState.CLOSED -> {
                            cancelDisconnectTimeout()
                            stopRingbackTone()
                            if (_callState.value != CallState.Ended && _callState.value != CallState.Idle) {
                                scope.launch { endUserCall() }
                            }
                        }
                        else -> {}
                    }
                }

                override fun onTrack(transceiver: RtpTransceiver?) {
                    Log.d(TAG, "onTrack called (REST mode), transceiver: $transceiver")
                    transceiver?.receiver?.track()?.let { track ->
                        Log.d(TAG, "Remote track received (REST mode): kind=${track.kind()}, id=${track.id()}")
                        if (track is VideoTrack) {
                            Log.d(TAG, "Setting remote video track (REST mode)")
                            _remoteVideoTrackFlow.value = track
                            track.setEnabled(true)
                        } else if (track is AudioTrack) {
                            Log.d(TAG, "Remote audio track received (REST mode)")
                            track.setEnabled(true)
                        }
                    }
                }

                override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                    Log.d(TAG, "Signaling state (REST mode): $state")
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                    Log.d(TAG, "Peer connection state (REST mode): $newState")
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                    Log.d(TAG, "ICE gathering state (REST mode): $state")
                }
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(channel: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            }
        )
    }

    /**
     * Create and send offer via REST API.
     */
    private suspend fun createAndSendOfferForUserCallRest(recipientUid: String, callId: String) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        suspendCancellableCoroutine<Unit> { continuation ->
            peerConnection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    sdp?.let {
                        peerConnection?.setLocalDescription(SdpObserverAdapter(), it)

                        // Send offer via REST API
                        scope.launch {
                            try {
                                val offerData = mapOf(
                                    "sdp" to it.description,
                                    "type" to it.type.canonicalForm()
                                )
                                val success = withContext(Dispatchers.IO) {
                                    writeToFirebaseRest(
                                        "users/$recipientUid/incoming_syncflow_calls/$callId/offer",
                                        offerData
                                    )
                                }
                                if (success) {
                                    Log.d(TAG, "Offer sent via REST API")
                                    continuation.resume(Unit) {}
                                } else {
                                    Log.e(TAG, "Failed to send offer via REST API")
                                    continuation.cancel(Exception("Failed to send offer"))
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error sending offer via REST", e)
                                continuation.cancel(e)
                            }
                        }
                    }
                }

                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) {
                    Log.e(TAG, "Create offer failed (REST): $error")
                    continuation.cancel(Exception(error))
                }
                override fun onSetFailure(error: String?) {}
            }, constraints)
        }
    }

    /**
     * Poll for answer via REST API instead of using Firebase listener.
     */
    private fun startAnswerPollingRest(recipientUid: String, callId: String) {
        answerPollingJob?.cancel()
        answerPollingJob = scope.launch {
            val path = "users/$recipientUid/incoming_syncflow_calls/$callId/answer"
            Log.d(TAG, "Starting REST polling for answer at: $path")

            var pollCount = 0
            repeat(180) { // Poll for up to 90 seconds (500ms * 180) - extended for slow FCM
                if (!isActive || _callState.value == CallState.Connected ||
                    _callState.value == CallState.Ended || _callState.value == CallState.Idle) {
                    Log.d(TAG, "Answer polling stopped: state=${_callState.value}, isActive=$isActive")
                    return@launch
                }

                try {
                    val answerData = withContext(Dispatchers.IO) {
                        readFromFirebaseRest(path)
                    }

                    pollCount++
                    if (pollCount % 10 == 0) {
                        Log.d(TAG, "Answer polling attempt #$pollCount, data=${answerData?.keys}")
                    }

                    if (answerData != null && answerData.containsKey("sdp")) {
                        val sdp = answerData["sdp"] as? String
                        val type = answerData["type"] as? String

                        if (sdp != null && type != null) {
                            Log.d(TAG, "Answer received via REST polling! type=$type, sdpLength=${sdp.length}")
                            val answer = SessionDescription(
                                SessionDescription.Type.fromCanonicalForm(type),
                                sdp
                            )
                            peerConnection?.setRemoteDescription(SdpObserverAdapter(), answer)
                            _callState.value = CallState.Connecting
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error polling for answer: ${e.message}")
                }

                delay(500) // Poll every 500ms
            }

            Log.w(TAG, "Answer polling timed out after $pollCount attempts")
        }
    }

    /**
     * Poll for ICE candidates via REST API instead of using Firebase listener.
     */
    private fun startIceCandidatesPollingRest(userId: String, callId: String, isOutgoing: Boolean) {
        icePollingJob?.cancel()
        processedIceCandidates.clear()
        icePollingJob = scope.launch {
            val icePath = if (isOutgoing) "ice_callee" else "ice_caller"
            val path = "users/$userId/incoming_syncflow_calls/$callId/$icePath"
            Log.d(TAG, "Starting REST polling for ICE candidates at: $path")

            while (isActive && _callState.value != CallState.Ended && _callState.value != CallState.Idle) {
                try {
                    val iceCandidates = withContext(Dispatchers.IO) {
                        readFromFirebaseRest(path)
                    }

                    if (iceCandidates != null) {
                        @Suppress("UNCHECKED_CAST")
                        val candidates = iceCandidates as? Map<String, Map<String, Any?>>
                        candidates?.forEach { (key, candidateData) ->
                            if (key !in processedIceCandidates) {
                                val candidate = candidateData["candidate"] as? String
                                val sdpMid = candidateData["sdpMid"] as? String
                                val sdpMLineIndex = (candidateData["sdpMLineIndex"] as? Number)?.toInt() ?: 0

                                if (candidate != null) {
                                    Log.d(TAG, "Remote ICE candidate received via REST polling")
                                    val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
                                    peerConnection?.addIceCandidate(iceCandidate)
                                    processedIceCandidates.add(key)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error polling for ICE candidates: ${e.message}")
                }

                delay(300) // Poll every 300ms for ICE candidates (faster than answer)
            }
        }
    }

    /**
     * Poll for call status changes via REST API.
     */
    private fun startCallStatusPollingRest(recipientUid: String, callId: String) {
        statusPollingJob?.cancel()
        statusPollingJob = scope.launch {
            val path = "users/$recipientUid/incoming_syncflow_calls/$callId/status"
            Log.d(TAG, "Starting REST polling for call status...")

            while (isActive && _callState.value != CallState.Ended && _callState.value != CallState.Idle) {
                try {
                    val statusResponse = withContext(Dispatchers.IO) {
                        readFromFirebaseRestRaw(path)
                    }

                    if (statusResponse != null) {
                        val status = statusResponse.trim().removeSurrounding("\"")
                        when (status) {
                            "ended", "rejected", "missed", "failed" -> {
                                if (_callState.value != CallState.Ended && _callState.value != CallState.Idle) {
                                    Log.d(TAG, "Remote party ended the call (status: $status)")
                                    stopRingbackTone()
                                    _callState.value = CallState.Ended
                                    cleanup()
                                    return@launch
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error polling call status: ${e.message}")
                }

                delay(1000) // Poll every 1 second for status
            }
        }
    }

    /**
     * Create and send answer via REST API.
     */
    private suspend fun createAndSendAnswerForUserCallRest(userId: String, callId: String) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        suspendCancellableCoroutine<Unit> { continuation ->
            peerConnection?.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    sdp?.let {
                        peerConnection?.setLocalDescription(SdpObserverAdapter(), it)

                        // Send answer via REST API
                        scope.launch {
                            try {
                                val answerData = mapOf(
                                    "sdp" to it.description,
                                    "type" to it.type.canonicalForm()
                                )
                                val success = withContext(Dispatchers.IO) {
                                    writeToFirebaseRest(
                                        "users/$userId/incoming_syncflow_calls/$callId/answer",
                                        answerData
                                    )
                                }
                                if (success) {
                                    Log.d(TAG, "Answer sent via REST API")
                                    continuation.resume(Unit) {}
                                } else {
                                    Log.e(TAG, "Failed to send answer via REST API")
                                    continuation.cancel(Exception("Failed to send answer"))
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error sending answer via REST", e)
                                continuation.cancel(e)
                            }
                        }
                    }
                }

                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) {
                    Log.e(TAG, "Create answer failed (REST): $error")
                    continuation.cancel(Exception(error))
                }
                override fun onSetFailure(error: String?) {}
            }, constraints)
        }
    }

    /**
     * Send ICE candidate via REST API.
     */
    private suspend fun sendIceCandidateForUserCallRest(
        userId: String,
        callId: String,
        candidate: IceCandidate,
        isOutgoing: Boolean
    ) {
        val icePath = if (isOutgoing) "ice_caller" else "ice_callee"
        val candidateKey = "ice_${System.currentTimeMillis()}_${(0..999).random()}"

        try {
            val candidateData = mapOf(
                "candidate" to candidate.sdp,
                "sdpMid" to candidate.sdpMid,
                "sdpMLineIndex" to candidate.sdpMLineIndex
            )

            val success = withContext(Dispatchers.IO) {
                writeToFirebaseRest(
                    "users/$userId/incoming_syncflow_calls/$callId/$icePath/$candidateKey",
                    candidateData
                )
            }

            if (success) {
                Log.d(TAG, "ICE candidate sent via REST API")
            } else {
                Log.e(TAG, "Failed to send ICE candidate via REST")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending ICE candidate via REST", e)
        }
    }

    /**
     * Write a single value to Firebase using REST API.
     */
    private suspend fun writeToFirebaseRestValue(path: String, value: Any): Boolean {
        return try {
            val token = auth.currentUser?.getIdToken(false)?.await()?.token
                ?: return false

            val databaseUrl = "https://syncflow-6980e-default-rtdb.firebaseio.com"
            val url = URL("$databaseUrl/$path.json?auth=$token")

            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            // Write value as JSON
            val jsonValue = when (value) {
                is String -> "\"$value\""
                is Number -> value.toString()
                is Boolean -> value.toString()
                else -> org.json.JSONObject(mapOf("value" to value)).toString()
            }
            connection.outputStream.bufferedWriter().use { it.write(jsonValue) }

            val responseCode = connection.responseCode
            responseCode in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "Error writing value to Firebase via REST", e)
            false
        }
    }

    /**
     * Read data from Firebase using REST API.
     * Returns a Map of the data, or null if not found.
     */
    private suspend fun readFromFirebaseRest(path: String): Map<String, Any?>? {
        return try {
            val response = readFromFirebaseRestRaw(path)
            if (response == null || response == "null" || response.isBlank()) {
                null
            } else {
                // Parse JSON response
                val json = org.json.JSONObject(response)
                jsonToMap(json)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error reading from Firebase REST: ${e.message}")
            null
        }
    }

    /**
     * Read raw response from Firebase REST API.
     */
    private suspend fun readFromFirebaseRestRaw(path: String): String? {
        return try {
            val token = auth.currentUser?.getIdToken(false)?.await()?.token
                ?: return null

            val databaseUrl = "https://syncflow-6980e-default-rtdb.firebaseio.com"
            val url = URL("$databaseUrl/$path.json?auth=$token")

            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                connection.inputStream.bufferedReader().readText()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Convert JSONObject to Map recursively.
     */
    private fun jsonToMap(json: org.json.JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        json.keys().forEach { key ->
            val value = json.get(key)
            map[key] = when (value) {
                is org.json.JSONObject -> jsonToMap(value)
                is org.json.JSONArray -> jsonArrayToList(value)
                org.json.JSONObject.NULL -> null
                else -> value
            }
        }
        return map
    }

    /**
     * Convert JSONArray to List recursively.
     */
    private fun jsonArrayToList(array: org.json.JSONArray): List<Any?> {
        val list = mutableListOf<Any?>()
        for (i in 0 until array.length()) {
            val value = array.get(i)
            list.add(when (value) {
                is org.json.JSONObject -> jsonToMap(value)
                is org.json.JSONArray -> jsonArrayToList(value)
                org.json.JSONObject.NULL -> null
                else -> value
            })
        }
        return list
    }

    /**
     * Data class for incoming user-to-user calls
     */
    data class IncomingUserCall(
        val callId: String,
        val callerUid: String,
        val callerPhone: String,
        val callerName: String,
        val callerPlatform: String,
        val isVideo: Boolean
    )

    /**
     * Send FCM push notification to wake up recipient's device for incoming call.
     * This writes to a queue that Cloud Functions picks up to send the actual FCM.
     * Uses REST API to avoid OOM from Firebase SDK.
     */
    private fun sendCallNotificationToUser(
        recipientUid: String,
        callId: String,
        callerName: String,
        callerPhone: String,
        isVideo: Boolean
    ) {
        Log.d(TAG, "Sending FCM call notification to user: $recipientUid")

        val notificationData = mapOf(
            "type" to "incoming_call",
            "callId" to callId,
            "callerName" to callerName,
            "callerPhone" to callerPhone,
            "isVideo" to isVideo.toString(),
            "timestamp" to System.currentTimeMillis()
        )

        // Write to fcm_notifications queue via REST API - Cloud Functions will pick this up
        scope.launch {
            try {
                val success = withContext(Dispatchers.IO) {
                    writeToFirebaseRest("fcm_notifications/$recipientUid/$callId", notificationData)
                }
                if (success) {
                    Log.d(TAG, "FCM notification queued for $recipientUid")
                } else {
                    Log.e(TAG, "Failed to queue FCM notification")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error queueing FCM notification", e)
            }
        }
    }

    /**
     * Listen for call status changes - when remote party ends the call
     */
    private fun listenForCallStatusChanges() {
        val callRef = currentCallRef
        if (callRef == null) {
            Log.w(TAG, "No currentCallRef for call status listener")
            return
        }

        callStatusRef?.let { ref ->
            callStatusListener?.let { listener ->
                ref.removeEventListener(listener)
            }
        }

        callStatusRef = callRef

        Log.d(TAG, "Starting to listen for call status changes at: $callRef")

        callStatusListener = callRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    Log.d(TAG, "Call removed from Firebase, ending locally")
                    if (_callState.value != CallState.Ended && _callState.value != CallState.Idle) {
                        stopRingbackTone()
                        _callState.value = CallState.Ended
                        cleanup()
                    }
                    return
                }

                val status = snapshot.child("status").getValue(String::class.java) ?: return
                Log.d(TAG, "Call status changed to: $status")

                when (status) {
                    "ended", "rejected", "missed", "failed" -> {
                        // Only end if we're not already in ended state
                        if (_callState.value != CallState.Ended && _callState.value != CallState.Idle) {
                            Log.d(TAG, "Remote party ended the call (status: $status)")
                            stopRingbackTone()
                            _callState.value = CallState.Ended
                            cleanup()
                        }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Call status listener cancelled: ${error.message}")
            }
        })
    }

    // Private methods

    private suspend fun setupPeerConnection(userId: String, callId: String, isOutgoing: Boolean) {
        val iceServers = getIceServers()
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let {
                        Log.d(TAG, "ICE candidate generated")
                        scope.launch {
                            sendIceCandidate(userId, callId, it, isOutgoing)
                        }
                    }
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    Log.d(TAG, "ICE connection state: $state")
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED -> {
                            cancelDisconnectTimeout()
                            _callState.value = CallState.Connected
                            resetRetryState()
                            startStatsMonitoring()
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            cancelDisconnectTimeout()
                            handleConnectionFailure()
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            scheduleDisconnectTimeout()
                        }
                        PeerConnection.IceConnectionState.CLOSED -> {
                            cancelDisconnectTimeout()
                            if (_callState.value != CallState.Ended && _callState.value != CallState.Idle) {
                                scope.launch { endCall() }
                            }
                        }
                        else -> {}
                    }
                }

                override fun onTrack(transceiver: RtpTransceiver?) {
                    Log.d(TAG, "onTrack called, transceiver: $transceiver")
                    transceiver?.receiver?.track()?.let { track ->
                        Log.d(TAG, "Remote track received: kind=${track.kind()}, id=${track.id()}, enabled=${track.enabled()}")
                        if (track is VideoTrack) {
                            Log.d(TAG, "Setting remote video track")
                            _remoteVideoTrackFlow.value = track
                            track.setEnabled(true)
                        } else if (track is AudioTrack) {
                            Log.d(TAG, "Remote audio track received")
                            track.setEnabled(true)
                        }
                    }
                }

                override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                    Log.d(TAG, "Signaling state: $state")
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(channel: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            }
        )
    }

    private fun createMediaTracks(withVideo: Boolean) {
        Log.d(TAG, "Creating media tracks, withVideo: $withVideo")

        // Create audio track
        localAudioSource = peerConnectionFactory?.createAudioSource(AUDIO_CONSTRAINTS)
        localAudioTrack = peerConnectionFactory?.createAudioTrack("audio0", localAudioSource)
        localAudioTrack?.setEnabled(true)
        Log.d(TAG, "Audio track created: $localAudioTrack")

        peerConnection?.addTrack(localAudioTrack, listOf("stream0"))

        // Create video track if needed
        if (withVideo) {
            createVideoTrack()
        } else {
            Log.d(TAG, "Video not requested, skipping video track creation")
        }
    }

    private fun createVideoTrack() {
        Log.d(TAG, "Creating video track...")
        val cameraEnumerator = if (Camera2Enumerator.isSupported(context)) {
            Log.d(TAG, "Using Camera2 enumerator")
            Camera2Enumerator(context)
        } else {
            Log.d(TAG, "Camera2 not supported, using Camera1 enumerator")
            Camera1Enumerator(true)
        }
        val deviceNames = cameraEnumerator.deviceNames
        Log.d(TAG, "Available cameras: ${deviceNames.toList()}")

        // Try front camera first, then back
        val frontCamera = deviceNames.find { cameraEnumerator.isFrontFacing(it) }
        val backCamera = deviceNames.find { cameraEnumerator.isBackFacing(it) }
        val cameraName = frontCamera ?: backCamera
        Log.d(TAG, "Selected camera: $cameraName (front: $frontCamera, back: $backCamera)")

        if (cameraName != null) {
            try {
                videoCapturer = cameraEnumerator.createCapturer(cameraName, null)
                if (videoCapturer == null && cameraEnumerator is Camera2Enumerator) {
                    Log.w(TAG, "Camera2 capturer unavailable, retrying with Camera1")
                    val camera1 = Camera1Enumerator(true)
                    val fallbackName = camera1.deviceNames.firstOrNull()
                    if (fallbackName != null) {
                        videoCapturer = camera1.createCapturer(fallbackName, null)
                    }
                }
                Log.d(TAG, "Video capturer created: $videoCapturer")
                if (videoCapturer == null) {
                    Log.e(TAG, "Failed to create video capturer")
                    return
                }

                surfaceTextureHelper = SurfaceTextureHelper.create(
                    "CaptureThread",
                    eglBase?.eglBaseContext
                )
                Log.d(TAG, "Surface texture helper created: $surfaceTextureHelper")

                localVideoSource = peerConnectionFactory?.createVideoSource(false)
                Log.d(TAG, "Video source created: $localVideoSource")

                val downstreamObserver = localVideoSource?.capturerObserver
                val effectObserver = downstreamObserver?.let { observer ->
                    VideoEffectCapturerObserver(observer) { _videoEffect.value }
                }

                videoCapturer?.initialize(
                    surfaceTextureHelper,
                    context,
                    effectObserver ?: localVideoSource?.capturerObserver
                )
                try {
                    videoCapturer?.startCapture(1280, 720, 30)
                    Log.d(TAG, "Video capture started at 1280x720@30fps")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to start capture at 1280x720, retrying at 640x480", e)
                    videoCapturer?.startCapture(640, 480, 15)
                    Log.d(TAG, "Video capture started at 640x480@15fps")
                }

                localVideoTrack = peerConnectionFactory?.createVideoTrack("video0", localVideoSource)
                localVideoTrack?.setEnabled(true)
                _localVideoTrackFlow.value = localVideoTrack
                Log.d(TAG, "Local video track created and set: $localVideoTrack")

                peerConnection?.addTrack(localVideoTrack, listOf("stream0"))
                Log.d(TAG, "Video track added to peer connection")
            } catch (e: Exception) {
                Log.e(TAG, "Error creating video track", e)
            }
        } else {
            Log.w(TAG, "No camera found")
        }
    }

    private suspend fun createAndSendOffer(userId: String, callId: String) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        suspendCancellableCoroutine<Unit> { continuation ->
            peerConnection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    sdp?.let {
                        peerConnection?.setLocalDescription(SdpObserverAdapter(), it)

                        // Send offer to Firebase
                        scope.launch {
                            try {
                                val offerData = mapOf(
                                    "sdp" to it.description,
                                    "type" to it.type.canonicalForm()
                                )
                                database.reference
                                    .child("users")
                                    .child(userId)
                                    .child("syncflow_calls")
                                    .child(callId)
                                    .child("offer")
                                    .setValue(offerData)
                                    .await()

                                Log.d(TAG, "Offer sent")
                                continuation.resume(Unit) {}
                            } catch (e: Exception) {
                                Log.e(TAG, "Error sending offer", e)
                                continuation.cancel(e)
                            }
                        }
                    }
                }

                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) {
                    Log.e(TAG, "Create offer failed: $error")
                    continuation.cancel(Exception(error))
                }
                override fun onSetFailure(error: String?) {}
            }, constraints)
        }
    }

    private suspend fun createAndSendAnswer(userId: String, callId: String) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        suspendCancellableCoroutine<Unit> { continuation ->
            peerConnection?.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    sdp?.let {
                        peerConnection?.setLocalDescription(SdpObserverAdapter(), it)

                        // Send answer to Firebase
                        scope.launch {
                            try {
                                val answerData = mapOf(
                                    "sdp" to it.description,
                                    "type" to it.type.canonicalForm()
                                )
                                database.reference
                                    .child("users")
                                    .child(userId)
                                    .child("syncflow_calls")
                                    .child(callId)
                                    .child("answer")
                                    .setValue(answerData)
                                    .await()

                                Log.d(TAG, "Answer sent")
                                continuation.resume(Unit) {}
                            } catch (e: Exception) {
                                Log.e(TAG, "Error sending answer", e)
                                continuation.cancel(e)
                            }
                        }
                    }
                }

                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) {
                    Log.e(TAG, "Create answer failed: $error")
                    continuation.cancel(Exception(error))
                }
                override fun onSetFailure(error: String?) {}
            }, constraints)
        }
    }

    private fun listenForAnswer(userId: String, callId: String) {
        val answerRef = database.reference
            .child("users")
            .child(userId)
            .child("syncflow_calls")
            .child(callId)
            .child("answer")

        callListener = answerRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val answerData = snapshot.value as? Map<String, Any?> ?: return
                val sdp = answerData["sdp"] as? String ?: return
                val type = answerData["type"] as? String ?: return

                Log.d(TAG, "Answer received")

                val answerSdp = SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(type),
                    sdp
                )
                peerConnection?.setRemoteDescription(SdpObserverAdapter(), answerSdp)

                _callState.value = CallState.Connecting
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Answer listener cancelled: ${error.message}")
            }
        })
    }

    private fun listenForIceCandidates(userId: String, callId: String, isOutgoing: Boolean) {
        val icePath = if (isOutgoing) "ice_callee" else "ice_caller"
        val iceRef = database.reference
            .child("users")
            .child(userId)
            .child("syncflow_calls")
            .child(callId)
            .child(icePath)

        iceCandidatesListener = iceRef.addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val candidateData = snapshot.value as? Map<String, Any?> ?: return
                val candidate = candidateData["candidate"] as? String ?: return
                val sdpMid = candidateData["sdpMid"] as? String
                val sdpMLineIndex = (candidateData["sdpMLineIndex"] as? Number)?.toInt() ?: 0

                Log.d(TAG, "Remote ICE candidate received")

                val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
                peerConnection?.addIceCandidate(iceCandidate)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "ICE candidates listener cancelled: ${error.message}")
            }
        })
    }

    private suspend fun sendIceCandidate(
        userId: String,
        callId: String,
        candidate: IceCandidate,
        isOutgoing: Boolean
    ) {
        val icePath = if (isOutgoing) "ice_caller" else "ice_callee"

        try {
            val candidateData = mapOf(
                "candidate" to candidate.sdp,
                "sdpMid" to candidate.sdpMid,
                "sdpMLineIndex" to candidate.sdpMLineIndex
            )

            database.reference
                .child("users")
                .child(userId)
                .child("syncflow_calls")
                .child(callId)
                .child(icePath)
                .push()
                .setValue(candidateData)
                .await()

            Log.d(TAG, "ICE candidate sent")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending ICE candidate", e)
        }
    }

    private fun setupAudio() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true
    }

    /**
     * Start playing ringback tone (for caller while waiting for answer)
     */
    private fun startRingbackTone() {
        try {
            stopRingbackTone() // Stop any existing tone first

            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringbackPlayer = MediaPlayer().apply {
                setDataSource(context, ringtoneUri)
                isLooping = true
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                prepare()
                start()
            }
            Log.d(TAG, "Ringback tone started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting ringback tone", e)
        }
    }

    /**
     * Stop playing ringback tone - force stop with multiple fallbacks
     */
    private fun stopRingbackTone() {
        Log.d(TAG, "stopRingbackTone() called, ringbackPlayer=$ringbackPlayer")
        val player = ringbackPlayer
        ringbackPlayer = null  // Clear reference immediately to prevent double-stop

        if (player != null) {
            try {
                if (player.isPlaying) {
                    player.stop()
                    Log.d(TAG, "Ringback player stopped")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping ringback player (may already be stopped)", e)
            }

            try {
                player.reset()
                Log.d(TAG, "Ringback player reset")
            } catch (e: Exception) {
                Log.w(TAG, "Error resetting ringback player", e)
            }

            try {
                player.release()
                Log.d(TAG, "Ringback player released")
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing ringback player", e)
            }
        }
        Log.d(TAG, "Ringback tone cleanup complete")
    }

    private fun startCallTimeout(userId: String, callId: String) {
        scope.launch {
            delay(SyncFlowCall.CALL_TIMEOUT_MS)

            // Check if still ringing
            if (_callState.value == CallState.Ringing) {
                Log.d(TAG, "Call timed out")

                database.reference
                    .child("users")
                    .child(userId)
                    .child("syncflow_calls")
                    .child(callId)
                    .child("status")
                    .setValue("missed")

                _callState.value = CallState.Failed("Call timed out")
                cleanup()
            }
        }
    }

    private fun cleanup() {
        Log.d(TAG, "Cleaning up call resources")

        // Stop network monitoring
        stopNetworkMonitoring()

        // Stop stats monitoring
        stopStatsMonitoring()

        // Cancel retry attempts
        retryJob?.cancel()
        retryJob = null
        connectionRetryCount = 0
        hasVideoFailed = false
        isAudioOnlyFallback = false

        cancelDisconnectTimeout()

        // Stop ringback tone if playing
        stopRingbackTone()

        // Cancel REST polling jobs
        answerPollingJob?.cancel()
        answerPollingJob = null
        icePollingJob?.cancel()
        icePollingJob = null
        statusPollingJob?.cancel()
        statusPollingJob = null
        processedIceCandidates.clear()

        // Remove Firebase listeners (for device-to-device calls)
        callListener?.let { currentCallRef?.removeEventListener(it) }
        callListener = null

        answerListener?.let { currentCallRef?.child("answer")?.removeEventListener(it) }
        answerListener = null

        iceCandidatesListener?.let {
            currentCallRef?.child("ice_caller")?.removeEventListener(it)
            currentCallRef?.child("ice_callee")?.removeEventListener(it)
        }
        iceCandidatesListener = null

        // Remove call status listener
        callStatusListener?.let { listener ->
            callStatusRef?.removeEventListener(listener)
        }
        callStatusListener = null
        callStatusRef = null

        // Stop video capture
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        videoCapturer = null

        // Dispose video track
        localVideoTrack?.dispose()
        localVideoTrack = null
        _localVideoTrackFlow.value = null

        // Dispose audio track
        localAudioTrack?.dispose()
        localAudioTrack = null

        // Dispose sources
        localVideoSource?.dispose()
        localVideoSource = null
        localAudioSource?.dispose()
        localAudioSource = null

        // Dispose surface texture helper
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null

        // Close peer connection
        peerConnection?.close()
        peerConnection = null

        // Reset remote video
        _remoteVideoTrackFlow.value = null

        // Reset audio
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false

        // Reset state
        _currentCall.value = null
        _isMuted.value = false
        _isVideoEnabled.value = true
        _videoEffect.value = VideoEffect.NONE
        _callState.value = CallState.Idle
    }

    private fun scheduleDisconnectTimeout() {
        if (disconnectJob != null) return
        disconnectJob = scope.launch {
            delay(5000)
            if (_callState.value != CallState.Ended && _callState.value != CallState.Idle) {
                Log.d(TAG, "ICE disconnected too long, ending call")
                if (_currentCall.value?.isUserCall == true) {
                    endUserCall()
                } else {
                    endCall()
                }
            }
        }
    }

    private fun cancelDisconnectTimeout() {
        disconnectJob?.cancel()
        disconnectJob = null
    }

    /**
     * Release all resources
     */
    fun release() {
        cleanup()
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        eglBase?.release()
        eglBase = null
        scope.cancel()
    }

    /**
     * Write data to Firebase using REST API.
     * This avoids the OOM issues caused by goOnline() syncing all data.
     */
    private suspend fun writeToFirebaseRest(path: String, data: Map<String, Any?>): Boolean {
        return try {
            val token = auth.currentUser?.getIdToken(false)?.await()?.token
                ?: return false

            val databaseUrl = "https://syncflow-6980e-default-rtdb.firebaseio.com"
            val url = URL("$databaseUrl/$path.json?auth=$token")

            Log.d(TAG, "Writing to Firebase REST API: $path")

            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            // Convert data to JSON
            val jsonData = org.json.JSONObject(data).toString()
            connection.outputStream.bufferedWriter().use { it.write(jsonData) }

            val responseCode = connection.responseCode
            Log.d(TAG, "REST API write response: $responseCode")
            responseCode in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "Error writing to Firebase via REST", e)
            false
        }
    }

    /**
     * Look up user UID from phone number using Firebase REST API.
     * This avoids the OOM issues caused by goOnline() syncing all data.
     */
    private suspend fun lookupPhoneToUid(phoneKey: String): String? {
        return try {
            // Get auth token for authenticated REST request
            val token = auth.currentUser?.getIdToken(false)?.await()?.token
                ?: return null

            // Firebase REST API URL
            val databaseUrl = "https://syncflow-6980e-default-rtdb.firebaseio.com"
            val url = URL("$databaseUrl/phone_to_uid/$phoneKey.json?auth=$token")

            Log.d(TAG, "Looking up phone via REST API: $phoneKey")

            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                Log.d(TAG, "REST API response: $response")

                // Response is either "null" or "\"userId\""
                if (response == "null" || response.isBlank()) {
                    null
                } else {
                    // Remove surrounding quotes
                    response.trim().removeSurrounding("\"")
                }
            } else {
                Log.e(TAG, "REST API error: $responseCode")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error looking up phone_to_uid via REST", e)
            null
        }
    }

    private fun getDeviceId(): String {
        return android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
    }

    private fun getDeviceName(): String {
        return android.os.Build.MODEL
    }

    /**
     * Simple SDP observer adapter with logging
     */
    private class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {
            Log.d(TAG, "SDP created successfully: type=${sdp?.type}")
        }
        override fun onSetSuccess() {
            Log.d(TAG, "SDP set successfully")
        }
        override fun onCreateFailure(error: String?) {
            Log.e(TAG, "SDP create failed: $error")
        }
        override fun onSetFailure(error: String?) {
            Log.e(TAG, "SDP set failed: $error")
        }
    }

    /**
     * Suspendable wrapper for setRemoteDescription that properly awaits completion.
     * This is critical - we must wait for the remote description to be set before
     * creating an answer, otherwise WebRTC will fail silently.
     */
    private suspend fun setRemoteDescriptionAsync(sdp: SessionDescription): Result<Unit> =
        suspendCancellableCoroutine { continuation ->
            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    // Not used for setRemoteDescription
                }
                override fun onSetSuccess() {
                    Log.d(TAG, "Remote description set successfully: type=${sdp.type}")
                    if (continuation.isActive) {
                        continuation.resume(Result.success(Unit)) {}
                    }
                }
                override fun onCreateFailure(error: String?) {
                    // Not used for setRemoteDescription
                }
                override fun onSetFailure(error: String?) {
                    Log.e(TAG, "Failed to set remote description: $error")
                    if (continuation.isActive) {
                        continuation.resume(Result.failure(Exception("Failed to set remote description: $error"))) {}
                    }
                }
            }, sdp) ?: run {
                Log.e(TAG, "PeerConnection is null, cannot set remote description")
                if (continuation.isActive) {
                    continuation.resume(Result.failure(Exception("PeerConnection is null"))) {}
                }
            }
        }
}
