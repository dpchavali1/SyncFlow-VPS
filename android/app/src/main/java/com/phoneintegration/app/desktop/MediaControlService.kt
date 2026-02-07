package com.phoneintegration.app.desktop

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.util.Log
import android.view.KeyEvent
import com.phoneintegration.app.vps.VPSClient
import kotlinx.coroutines.*

/**
 * Service to control media playback on Android from macOS.
 * Requires Notification Listener permission to access MediaSessions.
 *
 * VPS Backend Only - Uses VPS API instead of Firebase.
 */
class MediaControlService(context: Context) {
    private val context: Context = context.applicationContext
    private val vpsClient = VPSClient.getInstance(context)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var commandPollingJob: Job? = null
    private var sessionListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var lastSyncedState: MediaState? = null
    private var syncJob: Job? = null
    private var statusPollingJob: Job? = null
    private var devicesPollingJob: Job? = null
    private var hasActiveDesktop: Boolean = false

    companion object {
        private const val TAG = "MediaControlService"
        private const val SYNC_DEBOUNCE_MS = 1000L
        private const val STATUS_POLL_INTERVAL_MS = 5000L
        private const val COMMAND_POLL_INTERVAL_MS = 2000L
        private const val DEVICES_POLL_INTERVAL_MS = 30000L
        private const val DESKTOP_ACTIVE_WINDOW_MS = 2 * 60 * 1000L
    }

    data class MediaState(
        val isPlaying: Boolean,
        val title: String?,
        val artist: String?,
        val album: String?,
        val appName: String?,
        val packageName: String?,
        val volume: Int,
        val maxVolume: Int
    )

    /**
     * Start media control service
     */
    fun startListening() {
        // Recreate scope if it was cancelled (from a previous stopListening call)
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }

        registerSessionListener()
        startListeningForCommands()
        startListeningForDevices()
        // Force an initial sync to update status immediately
        scope.launch {
            delay(2000)
            syncMediaStatusOnce(forceSync = true)
        }
    }

    /**
     * Stop media control service
     */
    fun stopListening() {
        unregisterSessionListener()
        stopListeningForCommands()
        stopStatusPolling()
        stopListeningForDevices()
        scope.cancel()
    }

    /**
     * Check if notification listener permission is granted
     */
    fun hasNotificationListenerPermission(): Boolean {
        val componentName = ComponentName(context, NotificationMirrorService::class.java)
        val flattenedName = componentName.flattenToString()
        val enabledListeners = android.provider.Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: ""
        return enabledListeners.contains(flattenedName)
    }

    /**
     * Register listener for active media sessions
     */
    private fun registerSessionListener() {
        try {
            if (!hasNotificationListenerPermission()) return

            val mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val componentName = ComponentName(context, NotificationMirrorService::class.java)

            sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { _ ->
                debouncedSync()
            }

            mediaSessionManager.addOnActiveSessionsChangedListener(sessionListener!!, componentName)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering session listener", e)
        }
    }

    private fun unregisterSessionListener() {
        sessionListener?.let { listener ->
            try {
                val mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                mediaSessionManager.removeOnActiveSessionsChangedListener(listener)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering session listener", e)
            }
        }
        sessionListener = null
    }

    /**
     * Listen for media commands from macOS via polling
     */
    private fun startListeningForCommands() {
        commandPollingJob?.cancel()
        commandPollingJob = scope.launch {
            while (isActive) {
                try {
                    if (!vpsClient.isAuthenticated) {
                        delay(COMMAND_POLL_INTERVAL_MS)
                        continue
                    }

                    val commands = vpsClient.getMediaCommands()
                    for (command in commands) {
                        // Only process recent commands
                        if (System.currentTimeMillis() - command.timestamp > 10000) continue

                        when (command.action) {
                            "play" -> sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY)
                            "pause" -> sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PAUSE)
                            "play_pause" -> sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                            "next" -> sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_NEXT)
                            "previous" -> sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                            "stop" -> sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_STOP)
                            "volume_up" -> adjustVolume(AudioManager.ADJUST_RAISE)
                            "volume_down" -> adjustVolume(AudioManager.ADJUST_LOWER)
                            "volume_mute" -> adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE)
                            "set_volume" -> {
                                command.volume?.let { setVolume(it) }
                            }
                        }

                        // Mark command as processed
                        vpsClient.markMediaCommandProcessed(command.id)
                        debouncedSync()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error polling media commands", e)
                }
                delay(COMMAND_POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopListeningForCommands() {
        commandPollingJob?.cancel()
        commandPollingJob = null
    }

    /**
     * Send media key event
     */
    private fun sendMediaKeyEvent(keyCode: Int) {
        try {
            val controller = getActiveMediaController()
            if (controller != null) {
                val controls = controller.transportControls
                when (keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY -> controls.play()
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> controls.pause()
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        val isPlaying = controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
                        if (isPlaying) controls.pause() else controls.play()
                    }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> controls.skipToNext()
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> controls.skipToPrevious()
                    KeyEvent.KEYCODE_MEDIA_STOP -> controls.stop()
                }
            } else {
                val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
                val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
                audioManager.dispatchMediaKeyEvent(downEvent)
                audioManager.dispatchMediaKeyEvent(upEvent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending media key event", e)
        }
    }

    private fun adjustVolume(direction: Int) {
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        } catch (e: Exception) {
            Log.e(TAG, "Error adjusting volume", e)
        }
    }

    private fun setVolume(volume: Int) {
        try {
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                volume.coerceIn(0, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)),
                AudioManager.FLAG_SHOW_UI
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error setting volume", e)
        }
    }

    /**
     * Get active media controller
     */
    private fun getActiveMediaController(): MediaController? {
        return try {
            if (!hasNotificationListenerPermission()) return null

            val mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val componentName = ComponentName(context, NotificationMirrorService::class.java)
            val controllers = mediaSessionManager.getActiveSessions(componentName)

            // Prefer playing controller, then one with metadata, then first available
            controllers?.firstOrNull {
                it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
            } ?: controllers?.firstOrNull { it.metadata != null } ?: controllers?.firstOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting active media controller", e)
            null
        }
    }

    /**
     * Debounced sync to avoid rapid updates
     */
    private fun debouncedSync() {
        syncJob?.cancel()
        syncJob = scope.launch {
            delay(SYNC_DEBOUNCE_MS)
            if (shouldSyncNow()) {
                syncMediaStatusOnce()
            }
        }
    }

    /**
     * Sync current media status to VPS
     */
    private fun syncMediaStatus() {
        scope.launch { syncMediaStatusOnce(forceSync = false) }
    }

    private suspend fun syncMediaStatusOnce(forceSync: Boolean = false) {
        try {
            if (!forceSync && !shouldSyncNow()) return
            if (!vpsClient.isAuthenticated) return

            val currentState = getCurrentMediaState()

            // Skip if no change (unless forcing)
            if (!forceSync && currentState == lastSyncedState) return
            lastSyncedState = currentState

            val statusData = mapOf(
                "isPlaying" to currentState.isPlaying,
                "title" to (currentState.title ?: ""),
                "artist" to (currentState.artist ?: ""),
                "album" to (currentState.album ?: ""),
                "appName" to (currentState.appName ?: ""),
                "packageName" to (currentState.packageName ?: ""),
                "volume" to currentState.volume,
                "maxVolume" to currentState.maxVolume,
                "hasPermission" to hasNotificationListenerPermission(),
                "timestamp" to System.currentTimeMillis()
            )

            vpsClient.syncMediaStatus(statusData)
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing media status", e)
        }
    }

    private fun startStatusPolling() {
        statusPollingJob?.cancel()
        statusPollingJob = scope.launch {
            while (isActive) {
                syncMediaStatusOnce()
                delay(STATUS_POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopStatusPolling() {
        statusPollingJob?.cancel()
        statusPollingJob = null
    }

    private fun startListeningForDevices() {
        devicesPollingJob?.cancel()
        devicesPollingJob = scope.launch {
            while (isActive) {
                try {
                    if (!vpsClient.isAuthenticated) {
                        delay(DEVICES_POLL_INTERVAL_MS)
                        continue
                    }

                    val devicesResponse = vpsClient.getDevices()
                    val now = System.currentTimeMillis()
                    var active = false

                    for (device in devicesResponse.devices) {
                        val platform = device.deviceType.lowercase().trim()
                        if (platform != "macos") continue

                        // Check if device was seen recently
                        val lastSeen = device.lastSeen?.let {
                            try { java.time.Instant.parse(it).toEpochMilli() } catch (e: Exception) { 0L }
                        } ?: 0L
                        val recent = lastSeen > 0 && now - lastSeen <= DESKTOP_ACTIVE_WINDOW_MS

                        if (recent) {
                            active = true
                            break
                        }
                    }

                    if (active != hasActiveDesktop) {
                        hasActiveDesktop = active
                        if (active) {
                            startStatusPolling()
                            syncMediaStatus()
                        } else {
                            stopStatusPolling()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error polling devices", e)
                }
                delay(DEVICES_POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopListeningForDevices() {
        devicesPollingJob?.cancel()
        devicesPollingJob = null
        hasActiveDesktop = false
    }

    private fun shouldSyncNow(): Boolean {
        val prefs = context.getSharedPreferences("syncflow_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("notification_mirror_enabled", false)
    }

    /**
     * Get current media state
     */
    private fun getCurrentMediaState(): MediaState {
        val controller = getActiveMediaController()
        val metadata = controller?.metadata
        val playbackState = controller?.playbackState

        val sessionPlaying = playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
        val isPlaying = sessionPlaying || audioManager.isMusicActive

        val description = metadata?.description
        val sessionTitle = firstNonBlank(
            metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE),
            metadata?.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            description?.title?.toString()
        )
        val sessionArtist = firstNonBlank(
            metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST),
            metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            metadata?.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            description?.subtitle?.toString()
        )
        val sessionAlbum = firstNonBlank(
            metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM),
            metadata?.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION),
            description?.description?.toString()
        )
        val sessionPackage = controller?.packageName
        val sessionAppName = getAppName(sessionPackage)

        // Get fallback from notification mirror service
        val fallback = NotificationMirrorService.getLastMediaInfo()

        // Try to get app name from any active session if we don't have one
        var finalPackageName = firstNonBlank(sessionPackage, fallback?.packageName)
        var finalAppName = firstNonBlank(sessionAppName, fallback?.appName)

        // If still no app name but music is playing, try to find any active controller
        if (finalAppName == null && isPlaying) {
            val anyController = tryGetAnyActiveController()
            if (anyController != null) {
                finalPackageName = anyController.packageName
                finalAppName = getAppName(finalPackageName)
            }
        }

        val title = firstNonBlank(sessionTitle, fallback?.title)
        val artist = firstNonBlank(sessionArtist, fallback?.artist)
        val album = firstNonBlank(sessionAlbum, fallback?.album)

        val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        return MediaState(
            isPlaying = isPlaying,
            title = title,
            artist = artist,
            album = album,
            appName = finalAppName,
            packageName = finalPackageName,
            volume = volume,
            maxVolume = maxVolume
        )
    }

    private fun tryGetAnyActiveController(): MediaController? {
        return try {
            if (!hasNotificationListenerPermission()) return null
            val mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val componentName = ComponentName(context, NotificationMirrorService::class.java)
            mediaSessionManager.getActiveSessions(componentName)?.firstOrNull()
        } catch (e: Exception) {
            null
        }
    }

    private fun firstNonBlank(vararg values: String?): String? {
        for (value in values) {
            if (!value.isNullOrBlank()) {
                return value
            }
        }
        return null
    }

    private fun getAppName(packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        return try {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(packageName, 0)
            )?.toString()
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * NotificationListenerService required for MediaSession access
 */
class MediaNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d("MediaNotificationListener", "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d("MediaNotificationListener", "Notification listener disconnected")
    }
}
