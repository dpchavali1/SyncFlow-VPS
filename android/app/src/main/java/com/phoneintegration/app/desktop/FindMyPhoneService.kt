package com.phoneintegration.app.desktop

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.phoneintegration.app.vps.VPSClient
import kotlinx.coroutines.*

/**
 * Service that allows finding the phone by playing a loud ringtone
 * when triggered from macOS/desktop.
 *
 * VPS Backend Only - Uses VPS API instead of Firebase.
 */
class FindMyPhoneService(context: Context) {
    private val context: Context = context.applicationContext
    private val vpsClient = VPSClient.getInstance(context)

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var findRequestPollingJob: Job? = null
    private var isRinging = false
    private val processedRequests = mutableSetOf<String>()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "FindMyPhoneService"
        private const val RING_DURATION_MS = 30000L // Ring for 30 seconds max
        private const val POLL_INTERVAL_MS = 2000L
    }

    /**
     * Start listening for find phone requests
     */
    fun startListening() {
        Log.d(TAG, "Starting Find My Phone service")
        listenForFindRequests()
    }

    /**
     * Stop listening for find phone requests
     */
    fun stopListening() {
        Log.d(TAG, "Stopping Find My Phone service")
        stopRinging()
        findRequestPollingJob?.cancel()
        findRequestPollingJob = null
        scope.cancel()
    }

    /**
     * Listen for find phone requests from other devices via polling
     */
    private fun listenForFindRequests() {
        findRequestPollingJob?.cancel()
        findRequestPollingJob = scope.launch {
            while (isActive) {
                try {
                    if (!vpsClient.isAuthenticated) {
                        delay(POLL_INTERVAL_MS)
                        continue
                    }

                    val requests = vpsClient.getFindPhoneRequests()
                    for (request in requests) {
                        // Skip if already processed locally
                        if (processedRequests.contains(request.id)) continue

                        // Check if request is recent (within last 30 seconds)
                        val now = System.currentTimeMillis()
                        if (now - request.timestamp > 30000) {
                            Log.d(TAG, "Ignoring old find request: ${request.id}")
                            processedRequests.add(request.id)
                            continue
                        }

                        // Check if already processed on server
                        if (request.status == "ringing" || request.status == "stopped") {
                            processedRequests.add(request.id)
                            continue
                        }

                        Log.d(TAG, "Received find phone request: action=${request.action}, id=${request.id}")

                        when (request.action) {
                            "ring" -> {
                                startRinging()
                                updateRequestStatus(request.id, "ringing")
                            }
                            "stop" -> {
                                stopRinging()
                                updateRequestStatus(request.id, "stopped")
                            }
                        }
                        processedRequests.add(request.id)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error polling find phone requests", e)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
        Log.d(TAG, "Find phone polling started")
    }

    /**
     * Start ringing the phone loudly
     */
    private fun startRinging() {
        if (isRinging) {
            Log.d(TAG, "Already ringing")
            return
        }

        try {
            isRinging = true

            // Get ringtone URI
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            // Set up media player with maximum volume
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, ringtoneUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setLegacyStreamType(AudioManager.STREAM_ALARM)
                        .build()
                )
                isLooping = true
                prepare()

                // Set volume to maximum
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)

                start()
            }

            // Start vibration
            startVibration()

            Log.d(TAG, "Started ringing for Find My Phone")

            // Auto-stop after duration
            scope.launch {
                delay(RING_DURATION_MS)
                stopRinging()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error starting ring", e)
            isRinging = false
        }
    }

    /**
     * Stop ringing
     */
    fun stopRinging() {
        if (!isRinging) return

        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
            mediaPlayer = null

            stopVibration()

            isRinging = false
            Log.d(TAG, "Stopped ringing")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping ring", e)
        }
    }

    /**
     * Start vibration pattern
     */
    private fun startVibration() {
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            // Vibrate in a pattern: 500ms on, 500ms off, repeat
            val pattern = longArrayOf(0, 500, 500, 500, 500, 500)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(pattern, 0) // 0 = repeat from index 0
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting vibration", e)
        }
    }

    /**
     * Stop vibration
     */
    private fun stopVibration() {
        try {
            vibrator?.cancel()
            vibrator = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping vibration", e)
        }
    }

    /**
     * Update the request status in VPS
     */
    private fun updateRequestStatus(requestId: String, status: String) {
        scope.launch {
            try {
                if (!vpsClient.isAuthenticated) return@launch

                vpsClient.updateFindPhoneRequestStatus(requestId, status)
                Log.d(TAG, "Updated request $requestId status to $status")
            } catch (e: Exception) {
                Log.e(TAG, "Error updating request status", e)
            }
        }
    }

    /**
     * Check if phone is currently ringing
     */
    fun isCurrentlyRinging(): Boolean = isRinging
}
