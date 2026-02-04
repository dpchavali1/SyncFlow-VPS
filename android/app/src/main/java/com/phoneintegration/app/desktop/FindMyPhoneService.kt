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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

/**
 * Service that allows finding the phone by playing a loud ringtone
 * when triggered from macOS/desktop.
 */
class FindMyPhoneService(context: Context) {
    private val context: Context = context.applicationContext
    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var findRequestHandle: ChildEventListener? = null
    private var isRinging = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "FindMyPhoneService"
        private const val FIND_PHONE_PATH = "find_my_phone"
        private const val USERS_PATH = "users"
        private const val RING_DURATION_MS = 30000L // Ring for 30 seconds max
    }

    /**
     * Start listening for find phone requests
     */
    fun startListening() {
        Log.d(TAG, "Starting Find My Phone service")
        database.goOnline()
        listenForFindRequests()
    }

    /**
     * Stop listening for find phone requests
     */
    fun stopListening() {
        Log.d(TAG, "Stopping Find My Phone service")
        stopRinging()
        removeFindRequestListener()
        scope.cancel()
    }

    /**
     * Listen for find phone requests from other devices
     */
    private fun listenForFindRequests() {
        scope.launch {
            try {
                val currentUser = auth.currentUser ?: return@launch
                val userId = currentUser.uid

                val findRef = database.reference
                    .child(USERS_PATH)
                    .child(userId)
                    .child(FIND_PHONE_PATH)

                findRequestHandle = object : ChildEventListener {
                    override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                        handleFindRequest(snapshot)
                    }

                    override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                        handleFindRequest(snapshot)
                    }

                    override fun onChildRemoved(snapshot: DataSnapshot) {}
                    override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                    override fun onCancelled(error: DatabaseError) {
                        Log.e(TAG, "Find phone listener cancelled: ${error.message}")
                    }
                }

                findRef.addChildEventListener(findRequestHandle!!)
                Log.d(TAG, "Find phone listener registered")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting find phone listener", e)
            }
        }
    }

    private fun removeFindRequestListener() {
        scope.launch {
            try {
                val userId = auth.currentUser?.uid ?: return@launch
                findRequestHandle?.let { listener ->
                    database.reference
                        .child(USERS_PATH)
                        .child(userId)
                        .child(FIND_PHONE_PATH)
                        .removeEventListener(listener)
                }
                findRequestHandle = null
            } catch (e: Exception) {
                Log.e(TAG, "Error removing find phone listener", e)
            }
        }
    }

    /**
     * Handle incoming find phone request
     */
    private fun handleFindRequest(snapshot: DataSnapshot) {
        val requestId = snapshot.key ?: return
        val action = snapshot.child("action").value as? String ?: return
        val timestamp = snapshot.child("timestamp").value as? Long ?: 0
        val status = snapshot.child("status").value as? String

        // Check if request is recent (within last 30 seconds)
        val now = System.currentTimeMillis()
        if (now - timestamp > 30000) {
            Log.d(TAG, "Ignoring old find request: $requestId")
            return
        }

        // Check if already processed
        if (status == "ringing" || status == "stopped") {
            return
        }

        Log.d(TAG, "Received find phone request: action=$action, id=$requestId")

        when (action) {
            "ring" -> {
                startRinging()
                updateRequestStatus(requestId, "ringing")
            }
            "stop" -> {
                stopRinging()
                updateRequestStatus(requestId, "stopped")
            }
        }
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
     * Update the request status in Firebase
     */
    private fun updateRequestStatus(requestId: String, status: String) {
        scope.launch {
            try {
                val userId = auth.currentUser?.uid ?: return@launch

                database.reference
                    .child(USERS_PATH)
                    .child(userId)
                    .child(FIND_PHONE_PATH)
                    .child(requestId)
                    .child("status")
                    .setValue(status)
                    .await()

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
