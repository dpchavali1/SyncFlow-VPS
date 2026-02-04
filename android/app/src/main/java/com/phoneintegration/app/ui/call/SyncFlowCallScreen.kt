package com.phoneintegration.app.ui.call

import android.app.Activity
import android.content.Context
import android.os.PowerManager
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.phoneintegration.app.SyncFlowCallService
import com.phoneintegration.app.webrtc.SyncFlowCallManager
import com.phoneintegration.app.webrtc.VideoEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

@Composable
fun SyncFlowCallScreen(
    callManager: SyncFlowCallManager,
    onCallEnded: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val callState by callManager.callState.collectAsState()
    val currentCall by callManager.currentCall.collectAsState()
    val isMuted by callManager.isMuted.collectAsState()
    val isVideoEnabled by callManager.isVideoEnabled.collectAsState()
    val localVideoTrack by callManager.localVideoTrackFlow.collectAsState()
    val remoteVideoTrack by callManager.remoteVideoTrackFlow.collectAsState()
    val videoEffect by callManager.videoEffect.collectAsState()

    // Keep controls visible until video is working, then auto-hide after 8 seconds
    var showControls by remember { mutableStateOf(true) }
    var elapsedSeconds by remember { mutableStateOf(0) }
    val coroutineScope = rememberCoroutineScope()

    // Keep screen on during video call using wake lock and window flags
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "SyncFlow:VideoCallWakeLock"
        )

        // Acquire wake lock
        try {
            wakeLock.acquire(60 * 60 * 1000L) // 1 hour max
            android.util.Log.d("SyncFlowCallScreen", "Wake lock acquired")
        } catch (e: Exception) {
            android.util.Log.e("SyncFlowCallScreen", "Failed to acquire wake lock", e)
        }

        // Keep screen on via window flags
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        android.util.Log.d("SyncFlowCallScreen", "Screen keep-on flag set")

        onDispose {
            // Release wake lock
            if (wakeLock.isHeld) {
                wakeLock.release()
                android.util.Log.d("SyncFlowCallScreen", "Wake lock released")
            }
            // Remove keep screen on flag
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            android.util.Log.d("SyncFlowCallScreen", "Screen keep-on flag cleared")
        }
    }

    // Handle lifecycle events to prevent issues when app goes to background
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    android.util.Log.d("SyncFlowCallScreen", "Lifecycle: ON_PAUSE - keeping call active")
                }
                Lifecycle.Event.ON_RESUME -> {
                    android.util.Log.d("SyncFlowCallScreen", "Lifecycle: ON_RESUME - refreshing video")
                    // Re-enable video if it was enabled before
                    if (currentCall?.isVideo == true && isVideoEnabled) {
                        callManager.refreshVideoTrack()
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    android.util.Log.d("SyncFlowCallScreen", "Lifecycle: ON_STOP")
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Log state for debugging
    LaunchedEffect(callState, currentCall, localVideoTrack, remoteVideoTrack) {
        android.util.Log.d("SyncFlowCallScreen", "CallState: $callState")
        android.util.Log.d("SyncFlowCallScreen", "CurrentCall: ${currentCall?.displayName}, isVideo: ${currentCall?.isVideo}")
        android.util.Log.d("SyncFlowCallScreen", "LocalVideoTrack: $localVideoTrack")
        android.util.Log.d("SyncFlowCallScreen", "RemoteVideoTrack: $remoteVideoTrack")
    }

    // Hide controls after 8 seconds (only if there's remote video)
    LaunchedEffect(showControls, remoteVideoTrack) {
        if (showControls && remoteVideoTrack != null) {
            delay(8000)
            showControls = false
        }
    }

    // Duration timer
    LaunchedEffect(callState) {
        if (callState == SyncFlowCallManager.CallState.Connected) {
            while (true) {
                delay(1000)
                elapsedSeconds++
            }
        }
    }

    // Handle call ended
    LaunchedEffect(callState) {
        if (callState == SyncFlowCallManager.CallState.Ended ||
            callState is SyncFlowCallManager.CallState.Failed) {
            delay(1000)
            onCallEnded()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                showControls = !showControls
            }
    ) {
        // Remote video (full screen)
        remoteVideoTrack?.let { track ->
            VideoRenderer(
                videoTrack = track,
                modifier = Modifier.fillMaxSize(),
                eglContext = callManager.getEglBaseContext()
            )
        } ?: run {
            // Placeholder when no remote video
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Icon(
                        imageVector = if (currentCall?.isVideo == true) Icons.Filled.Videocam else Icons.Filled.Call,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = Color.White.copy(alpha = 0.5f)
                    )

                    Text(
                        text = currentCall?.displayName ?: "Calling...",
                        style = MaterialTheme.typography.headlineLarge,
                        color = Color.White
                    )

                    Text(
                        text = getStatusText(callState),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
        }

        // Local video preview (PiP)
        if (localVideoTrack != null && isVideoEnabled) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(width = 120.dp, height = 160.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                VideoRenderer(
                    videoTrack = localVideoTrack!!,
                    modifier = Modifier.fillMaxSize(),
                    eglContext = callManager.getEglBaseContext(),
                    mirror = true
                )
            }
        }

        // Controls overlay
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Top bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.7f),
                                    Color.Transparent
                                )
                            )
                        )
                        .padding(16.dp)
                ) {
                    Column {
                        Text(
                            text = currentCall?.displayName ?: "",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )

                        if (callState == SyncFlowCallManager.CallState.Connected) {
                            Text(
                                text = formatDuration(elapsedSeconds),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }

                    // Connection status
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .background(
                                Color.Black.copy(alpha = 0.5f),
                                RoundedCornerShape(16.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    getStatusColor(callState),
                                    CircleShape
                                )
                        )
                        Text(
                            text = getStatusText(callState),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Bottom control bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.7f)
                                )
                            )
                        )
                        .padding(vertical = 30.dp, horizontal = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Mute button
                        CallControlButton(
                            icon = if (isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            label = if (isMuted) "Unmute" else "Mute",
                            isActive = isMuted,
                            onClick = { callManager.toggleMute() }
                        )

                        // Video toggle
                        if (currentCall?.isVideo == true) {
                            CallControlButton(
                                icon = if (isVideoEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                                label = if (isVideoEnabled) "Stop Video" else "Start Video",
                                isActive = !isVideoEnabled,
                                onClick = { callManager.toggleVideo() }
                            )

                            CallControlButton(
                                icon = Icons.Filled.CenterFocusStrong,
                                label = "Face Focus",
                                isActive = videoEffect == VideoEffect.FACE_FOCUS,
                                onClick = { callManager.toggleFaceFocus() }
                            )

                            CallControlButton(
                                icon = Icons.Filled.BlurOn,
                                label = "Background",
                                isActive = videoEffect == VideoEffect.BACKGROUND_BLUR,
                                onClick = { callManager.toggleBackgroundBlur() }
                            )

                            // Switch camera
                            CallControlButton(
                                icon = Icons.Filled.FlipCameraAndroid,
                                label = "Switch",
                                isActive = false,
                                onClick = { callManager.switchCamera() }
                            )
                        }

                        // End call button
                        IconButton(
                            onClick = {
                                coroutineScope.launch {
                                    callManager.endCall()
                                }
                            },
                            modifier = Modifier
                                .size(70.dp)
                                .background(Color.Red, CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CallEnd,
                                contentDescription = "End Call",
                                modifier = Modifier.size(32.dp),
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CallControlButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(60.dp)
                .background(
                    if (isActive) Color.White.copy(alpha = 0.3f)
                    else Color.Black.copy(alpha = 0.5f),
                    CircleShape
                )
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(24.dp),
                tint = Color.White
            )
        }

        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.8f)
        )
    }
}

@Composable
fun VideoRenderer(
    videoTrack: VideoTrack,
    modifier: Modifier = Modifier,
    eglContext: org.webrtc.EglBase.Context?,
    mirror: Boolean = false
) {
    var rendererRef by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var localEglBase by remember { mutableStateOf<org.webrtc.EglBase?>(null) }
    var isInitialized by remember { mutableStateOf(false) }
    var currentTrackId by remember { mutableStateOf<String?>(null) }

    // Track the video track to handle changes
    val trackId = remember(videoTrack) { videoTrack.id() }

    // Handle video track changes - re-add sink when track changes
    DisposableEffect(videoTrack, trackId) {
        val renderer = rendererRef
        if (renderer != null && isInitialized) {
            try {
                // Remove old track sink if different track
                if (currentTrackId != null && currentTrackId != trackId) {
                    android.util.Log.d("VideoRenderer", "Track changed from $currentTrackId to $trackId")
                }
                videoTrack.addSink(renderer)
                currentTrackId = trackId
                android.util.Log.d("VideoRenderer", "Added sink for track: $trackId, mirror: $mirror")
            } catch (e: Exception) {
                android.util.Log.e("VideoRenderer", "Error adding sink", e)
            }
        }

        onDispose {
            renderer?.let { r ->
                try {
                    videoTrack.removeSink(r)
                    android.util.Log.d("VideoRenderer", "Removed sink for track: $trackId")
                } catch (e: Exception) {
                    android.util.Log.e("VideoRenderer", "Error removing sink", e)
                }
            }
        }
    }

    // Ensure renderer stays active during lifecycle
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, rendererRef) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    // Re-add sink on resume to ensure video continues
                    rendererRef?.let { renderer ->
                        try {
                            if (isInitialized) {
                                videoTrack.addSink(renderer)
                                android.util.Log.d("VideoRenderer", "Re-added sink on resume")
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("VideoRenderer", "Error re-adding sink on resume", e)
                        }
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    AndroidView(
        factory = { context ->
            SurfaceViewRenderer(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                try {
                    val eglToUse = eglContext ?: run {
                        val created = org.webrtc.EglBase.create()
                        localEglBase = created
                        created.eglBaseContext
                    }
                    init(eglToUse, null)
                    setMirror(mirror)
                    setEnableHardwareScaler(true)
                    setZOrderMediaOverlay(mirror) // Local video should be on top
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    android.util.Log.d("VideoRenderer", "SurfaceViewRenderer initialized, mirror: $mirror")
                    rendererRef = this
                    isInitialized = true
                    // Add sink after initialization
                    videoTrack.addSink(this)
                    currentTrackId = trackId
                    android.util.Log.d("VideoRenderer", "Initial sink added for track: $trackId")
                } catch (e: Exception) {
                    android.util.Log.e("VideoRenderer", "Error initializing renderer", e)
                }
            }
        },
        modifier = modifier,
        update = { renderer ->
            // Handle updates - ensure sink is attached
            try {
                if (isInitialized && currentTrackId != trackId) {
                    videoTrack.addSink(renderer)
                    currentTrackId = trackId
                    android.util.Log.d("VideoRenderer", "Updated sink for new track: $trackId")
                }
            } catch (e: Exception) {
                android.util.Log.e("VideoRenderer", "Error in update", e)
            }
        },
        onRelease = { renderer ->
            try {
                videoTrack.removeSink(renderer)
                renderer.clearImage()
                renderer.release()
                localEglBase?.release()
                localEglBase = null
                rendererRef = null
                isInitialized = false
                currentTrackId = null
                android.util.Log.d("VideoRenderer", "Renderer released")
            } catch (e: Exception) {
                android.util.Log.e("VideoRenderer", "Error releasing renderer", e)
            }
        }
    )
}

// MARK: - Incoming Call Screen

@Composable
fun IncomingSyncFlowCallScreen(
    callerName: String,
    isVideo: Boolean,
    onAcceptVideo: () -> Unit,
    onAcceptAudio: () -> Unit,
    onDecline: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ring")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.weight(1f))

            // Animated ring effect
            Box(contentAlignment = Alignment.Center) {
                repeat(3) { index ->
                    val scale by infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = 1.2f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1500, easing = LinearOutSlowInEasing),
                            repeatMode = RepeatMode.Restart,
                            initialStartOffset = StartOffset(index * 300)
                        ),
                        label = "ring_scale_$index"
                    )

                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.3f - index * 0.1f,
                        targetValue = 0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1500, easing = LinearOutSlowInEasing),
                            repeatMode = RepeatMode.Restart,
                            initialStartOffset = StartOffset(index * 300)
                        ),
                        label = "ring_alpha_$index"
                    )

                    Box(
                        modifier = Modifier
                            .size((120 + index * 40).dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            }
                            .background(
                                Color.Green.copy(alpha = alpha),
                                CircleShape
                            )
                    )
                }

                // Caller avatar
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color.Green, Color.Green.copy(alpha = 0.7f))
                            ),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isVideo) Icons.Filled.Videocam else Icons.Filled.Call,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            // Caller info
            Text(
                text = callerName,
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "SyncFlow ${if (isVideo) "Video" else "Audio"} Call",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.weight(1f))

            // Action buttons
            Row(
                modifier = Modifier.padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(60.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Decline
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    IconButton(
                        onClick = onDecline,
                        modifier = Modifier
                            .size(70.dp)
                            .background(Color.Red, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CallEnd,
                            contentDescription = "Decline",
                            modifier = Modifier.size(32.dp),
                            tint = Color.White
                        )
                    }
                    Text(
                        text = "Decline",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }

                if (isVideo) {
                    // Accept audio only
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        IconButton(
                            onClick = onAcceptAudio,
                            modifier = Modifier
                                .size(70.dp)
                                .background(Color.Blue, CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Call,
                                contentDescription = "Audio Only",
                                modifier = Modifier.size(32.dp),
                                tint = Color.White
                            )
                        }
                        Text(
                            text = "Audio",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }

                // Accept
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    IconButton(
                        onClick = if (isVideo) onAcceptVideo else onAcceptAudio,
                        modifier = Modifier
                            .size(70.dp)
                            .background(Color.Green, CircleShape)
                    ) {
                        Icon(
                            imageVector = if (isVideo) Icons.Filled.Videocam else Icons.Filled.Call,
                            contentDescription = "Accept",
                            modifier = Modifier.size(32.dp),
                            tint = Color.White
                        )
                    }
                    Text(
                        text = if (isVideo) "Video" else "Accept",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(60.dp))
        }
    }
}

// Helper functions

private fun getStatusText(state: SyncFlowCallManager.CallState): String {
    return when (state) {
        SyncFlowCallManager.CallState.Ringing -> "Ringing..."
        SyncFlowCallManager.CallState.Connecting -> "Connecting..."
        SyncFlowCallManager.CallState.Connected -> "Connected"
        is SyncFlowCallManager.CallState.Failed -> "Failed"
        SyncFlowCallManager.CallState.Ended -> "Ended"
        else -> ""
    }
}

private fun getStatusColor(state: SyncFlowCallManager.CallState): Color {
    return when (state) {
        SyncFlowCallManager.CallState.Connected -> Color.Green
        SyncFlowCallManager.CallState.Connecting,
        SyncFlowCallManager.CallState.Ringing -> Color.Yellow
        is SyncFlowCallManager.CallState.Failed -> Color.Red
        else -> Color.Gray
    }
}

private fun formatDuration(seconds: Int): String {
    val minutes = seconds / 60
    val secs = seconds % 60
    return String.format("%02d:%02d", minutes, secs)
}
