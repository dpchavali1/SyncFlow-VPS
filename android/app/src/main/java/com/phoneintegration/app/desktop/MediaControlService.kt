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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

/**
 * Service to control media playback on Android from macOS.
 * Requires Notification Listener permission to access MediaSessions.
 */
class MediaControlService(context: Context) {
    private val context: Context = context.applicationContext
    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var commandListener: ValueEventListener? = null
    private var sessionListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var lastSyncedState: MediaState? = null
    private var syncJob: Job? = null
    private var statusPollingJob: Job? = null
    private var devicesListener: ValueEventListener? = null
    private var hasActiveDesktop: Boolean = false

    companion object {
        private const val TAG = "MediaControlService"
        private const val MEDIA_STATUS_PATH = "media_status"
        private const val MEDIA_COMMAND_PATH = "media_command"
        private const val USERS_PATH = "users"
        private const val SYNC_DEBOUNCE_MS = 1000L
        private const val STATUS_POLL_INTERVAL_MS = 5000L
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

        // Force Firebase online connection
        database.goOnline()

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
     * Listen for media commands from macOS
     */
    private fun startListeningForCommands() {
        scope.launch {
            try {
                val currentUser = auth.currentUser ?: return@launch
                val userId = currentUser.uid

                val commandRef = database.reference
                    .child(USERS_PATH)
                    .child(userId)
                    .child(MEDIA_COMMAND_PATH)

                val listener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val action = snapshot.child("action").value as? String ?: return
                        val timestamp = snapshot.child("timestamp").value as? Long ?: return

                        // Only process recent commands
                        if (System.currentTimeMillis() - timestamp > 10000) return

                        when (action) {
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
                                val volume = snapshot.child("volume").value as? Long
                                if (volume != null) {
                                    setVolume(volume.toInt())
                                }
                            }
                        }

                        snapshot.ref.removeValue()
                        debouncedSync()
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Log.e(TAG, "Command listener cancelled: ${error.message}")
                    }
                }

                commandListener = listener
                commandRef.addValueEventListener(listener)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting command listener", e)
            }
        }
    }

    private fun stopListeningForCommands() {
        commandListener?.let { listener ->
            scope.launch {
                try {
                    val userId = auth.currentUser?.uid ?: return@launch
                    database.reference
                        .child(USERS_PATH)
                        .child(userId)
                        .child(MEDIA_COMMAND_PATH)
                        .removeEventListener(listener)
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing command listener", e)
                }
            }
        }
        commandListener = null
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
     * Sync current media status to Firebase
     */
    private fun syncMediaStatus() {
        scope.launch { syncMediaStatusOnce(forceSync = false) }
    }

    private suspend fun syncMediaStatusOnce(forceSync: Boolean = false) {
        try {
            if (!forceSync && !shouldSyncNow()) return

            val currentUser = auth.currentUser ?: return
            val userId = currentUser.uid
            val currentState = getCurrentMediaState()

            // Skip if no change (unless forcing)
            if (!forceSync && currentState == lastSyncedState) return
            lastSyncedState = currentState

            val statusRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(MEDIA_STATUS_PATH)

            val statusData = mutableMapOf<String, Any?>(
                "isPlaying" to currentState.isPlaying,
                "title" to currentState.title,
                "artist" to currentState.artist,
                "album" to currentState.album,
                "appName" to currentState.appName,
                "packageName" to currentState.packageName,
                "volume" to currentState.volume,
                "maxVolume" to currentState.maxVolume,
                "hasPermission" to hasNotificationListenerPermission(),
                "timestamp" to ServerValue.TIMESTAMP
            )

            statusRef.setValue(statusData)
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
        scope.launch {
            try {
                val currentUser = auth.currentUser ?: return@launch
                val userId = currentUser.uid

                val devicesRef = database.reference
                    .child(USERS_PATH)
                    .child(userId)
                    .child("devices")

                val listener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val now = System.currentTimeMillis()
                        var active = false

                        snapshot.children.forEach { child ->
                            val platformRaw = child.child("platform").getValue(String::class.java)
                                ?: child.child("type").getValue(String::class.java)
                            val platform = platformRaw?.lowercase()?.trim()

                            if (platform != "macos") return@forEach

                            val online = (child.child("online").value as? Boolean) ?: false
                            val lastSeenValue = child.child("lastSeen").value
                            val lastSeen = when (lastSeenValue) {
                                is Long -> lastSeenValue
                                is Double -> lastSeenValue.toLong()
                                is Int -> lastSeenValue.toLong()
                                is String -> lastSeenValue.toLongOrNull() ?: 0L
                                else -> 0L
                            }
                            val recent = lastSeen > 0 && now - lastSeen <= DESKTOP_ACTIVE_WINDOW_MS

                            if (online || recent) {
                                active = true
                                return@forEach
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
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Log.e(TAG, "Device listener cancelled: ${error.message}")
                    }
                }

                devicesListener = listener
                devicesRef.addValueEventListener(listener)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting device listener", e)
            }
        }
    }

    private fun stopListeningForDevices() {
        val listener = devicesListener ?: return
        scope.launch {
            try {
                val userId = auth.currentUser?.uid ?: return@launch
                database.reference
                    .child(USERS_PATH)
                    .child(userId)
                    .child("devices")
                    .removeEventListener(listener)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing device listener", e)
            }
        }
        devicesListener = null
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
