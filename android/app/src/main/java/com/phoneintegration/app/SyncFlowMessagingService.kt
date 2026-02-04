/**
 * SyncFlowMessagingService.kt
 *
 * Firebase Cloud Messaging (FCM) service that handles push notifications for the SyncFlow app.
 * This service enables real-time communication between the server and the Android app without
 * requiring persistent connections or polling.
 *
 * ## Architecture Overview
 *
 * ```
 * Firebase Cloud Functions
 *         |
 *         v (FCM Push)
 * SyncFlowMessagingService
 *         |
 *         +-- handleIncomingCall() --> SyncFlowCallService
 *         +-- handleOutgoingMessage() --> OutgoingMessageService
 *         +-- handleE2EEMessage() --> SMS ContentProvider
 *         +-- handleMakePhoneCall() --> CallMonitorService
 * ```
 *
 * ## Key Responsibilities
 *
 * 1. **Incoming Calls**: Receives call notifications from desktop, starts ringtone/vibration
 * 2. **Outgoing Messages**: Wakes up OutgoingMessageService to send queued SMS/MMS
 * 3. **E2EE Messages**: Decrypts incoming encrypted messages and stores in SMS inbox
 * 4. **Call Control**: Handles call cancelled/ended/status changed notifications
 * 5. **Phone Calls**: Initiates phone calls requested from desktop
 *
 * ## FCM Message Types
 *
 * | Type                  | Description                                    |
 * |-----------------------|------------------------------------------------|
 * | `e2ee_message`        | Encrypted incoming message                     |
 * | `incoming_call`       | SyncFlow call notification                     |
 * | `call_cancelled`      | Call was cancelled before answering            |
 * | `call_status_changed` | Call state transition (ringing -> answered)    |
 * | `call_ended_by_remote`| Remote party ended the call                    |
 * | `outgoing_message`    | Desktop queued a message to send               |
 * | `make_phone_call`     | Desktop requested a phone call                 |
 *
 * ## Lifecycle
 *
 * - Created by the Android system when an FCM message arrives
 * - Uses SupervisorJob + Dispatchers.IO for async operations
 * - Automatically destroyed when message processing completes
 * - Scope is cancelled in onDestroy() to prevent leaks
 *
 * ## Notification Channels
 *
 * - `syncflow_calls`: High-priority channel for incoming call notifications
 *
 * @see SyncFlowCallService for call handling logic
 * @see OutgoingMessageService for SMS/MMS sending
 * @see SignalProtocolManager for E2EE decryption
 */
package com.phoneintegration.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.phoneintegration.app.desktop.OutgoingMessageService
import com.phoneintegration.app.e2ee.SignalProtocolManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class SyncFlowMessagingService : FirebaseMessagingService() {

    // ==========================================
    // REGION: Constants
    // ==========================================

    companion object {
        private const val TAG = "SyncFlowMessaging"
        private const val CALL_CHANNEL_ID = "syncflow_calls"
        private const val CALL_NOTIFICATION_ID = 3001
    }

    // ==========================================
    // REGION: Instance Variables
    // ==========================================

    /** Handles E2EE decryption for incoming encrypted messages */
    private lateinit var signalProtocolManager: SignalProtocolManager

    /** Firebase Realtime Database for looking up user phone numbers */
    private val firebaseDatabase = FirebaseDatabase.getInstance()

    /** Firebase Auth for getting current user */
    private val auth = FirebaseAuth.getInstance()

    /**
     * Coroutine scope for async operations within this service.
     * Uses SupervisorJob so one failure doesn't cancel other operations.
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ==========================================
    // REGION: Lifecycle Methods
    // ==========================================

    override fun onCreate() {
        super.onCreate()
        // Initialize E2EE manager for decrypting incoming messages
        signalProtocolManager = SignalProtocolManager(applicationContext)
        createNotificationChannel()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cancel all pending coroutines to prevent leaks
        serviceScope.cancel()
    }

    /**
     * Creates the notification channel for incoming SyncFlow calls.
     *
     * This channel is configured for high priority with:
     * - Ringtone sound
     * - Vibration pattern
     * - Lock screen visibility
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ringtoneUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val channel = NotificationChannel(
                CALL_CHANNEL_ID,
                "SyncFlow Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming SyncFlow video/audio calls"
                setShowBadge(true)
                setSound(ringtoneUri, audioAttributes)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 500, 1000, 500, 1000)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    // ==========================================
    // REGION: FCM Message Handling
    // ==========================================

    /**
     * Main entry point for all FCM messages.
     *
     * Routes messages to appropriate handlers based on the "type" field in the data payload.
     * All FCM messages from SyncFlow use data-only payloads (no notification payload)
     * to ensure the app can process them even when in the background.
     *
     * @param remoteMessage The FCM message containing the data payload
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "FCM message received: ${remoteMessage.data}")

        val data = remoteMessage.data

        // Route to appropriate handler based on message type
        when (data["type"]) {
            "e2ee_message" -> handleE2EEMessage(data)
            "incoming_call" -> handleIncomingCall(data)
            "call_cancelled" -> handleCallCancelled(data)
            "call_status_changed" -> handleCallStatusChanged(data)
            "call_ended_by_remote" -> handleCallEndedByRemote(data)
            "outgoing_message" -> handleOutgoingMessage(data)
            "make_phone_call" -> handleMakePhoneCall(data)
            else -> Log.d(TAG, "Unknown message type: ${data["type"]}")
        }
    }

    /**
     * Handles end-to-end encrypted messages received via FCM.
     *
     * Decrypts the message body using the SignalProtocolManager and inserts
     * it into the SMS inbox so it appears in the conversation list.
     *
     * @param data FCM data containing senderId and encryptedBody
     */
    private fun handleE2EEMessage(data: Map<String, String>) {
        val senderId = data["senderId"]
        val encryptedBody = data["encryptedBody"]

        if (senderId != null && encryptedBody != null) {
            try {
                // Decrypt using the Signal Protocol
                val decryptedBody = signalProtocolManager.decryptMessage(encryptedBody)
                if (decryptedBody != null) {
                    Log.d(TAG, "Decrypted message: $decryptedBody")
                    // Insert into SMS inbox so it shows in conversations
                    insertSms(senderId, decryptedBody)
                } else {
                    Log.e(TAG, "Failed to decrypt message")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error decrypting message", e)
            }
        }
    }

    // ==========================================
    // REGION: Call Handling
    // ==========================================

    /**
     * Handles incoming SyncFlow call notifications.
     *
     * When a user initiates a call from the desktop/web client, this notification
     * is sent to wake up the Android device and display the incoming call UI.
     *
     * ## Call Flow
     *
     * 1. FCM message arrives with call details
     * 2. Start SyncFlowCallService as foreground service
     * 3. Service shows full-screen incoming call notification
     * 4. Service plays ringtone and vibrates
     * 5. User answers or declines
     *
     * @param data FCM data containing callId, callerName, callerPhone, isVideo
     */
    private fun handleIncomingCall(data: Map<String, String>) {
        val callId = data["callId"] ?: return
        val callerName = data["callerName"] ?: "Unknown"
        val callerPhone = data["callerPhone"] ?: ""
        val isVideo = data["isVideo"]?.toBoolean() ?: false

        Log.d(TAG, "Incoming call from $callerName ($callerPhone), isVideo: $isVideo, callId: $callId")

        // Start the SyncFlowCallService with the incoming call data
        // Pass the call data directly so it doesn't rely on Firebase listeners (which are offline)
        // The service will handle showing the notification with proper looping ringtone
        val serviceIntent = Intent(this, SyncFlowCallService::class.java).apply {
            action = SyncFlowCallService.ACTION_INCOMING_USER_CALL
            putExtra(SyncFlowCallService.EXTRA_CALL_ID, callId)
            putExtra(SyncFlowCallService.EXTRA_CALLER_NAME, callerName)
            putExtra(SyncFlowCallService.EXTRA_CALLER_PHONE, callerPhone)
            putExtra(SyncFlowCallService.EXTRA_IS_VIDEO, isVideo)
        }

        // Use startForegroundService on Android O+ for reliability
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Note: SyncFlowCallService.showIncomingCallNotification() handles:
        // - Showing the notification with proper call UI
        // - Playing looping ringtone via MediaPlayer
        // - Vibration
        // - Launching incoming call activity
        // We don't show a separate notification here to avoid duplicate sounds
    }

    /**
     * Handles call cancellation (caller hung up before answering).
     *
     * @param data FCM data containing the callId
     */
    private fun handleCallCancelled(data: Map<String, String>) {
        val callId = data["callId"] ?: return
        Log.d(TAG, "Call cancelled: $callId")

        // Dismiss the incoming call notification
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.cancel(CALL_NOTIFICATION_ID)
    }

    /**
     * Handles call status changes (ringing -> answered, etc.).
     *
     * When a call transitions from "ringing" to any other state, we need to:
     * 1. Cancel all call-related notifications
     * 2. Stop the ringtone
     * 3. Update the UI state
     *
     * @param data FCM data containing callId and status
     */
    private fun handleCallStatusChanged(data: Map<String, String>) {
        val callId = data["callId"] ?: return
        val status = data["status"] ?: return
        Log.d(TAG, "📞 Call status changed via FCM: callId=$callId, status=$status")

        // If the call is no longer ringing (answered, ended, rejected, cancelled, etc.),
        // dismiss the notification immediately
        if (status != "ringing") {
            Log.d(TAG, "📞 Call no longer ringing, dismissing notifications")

            // Cancel all call-related notifications immediately
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.cancel(CALL_NOTIFICATION_ID)

            // Also cancel the service's incoming call notification (ID 2002)
            notificationManager.cancel(2002)

            // Also cancel the FCM call notification (ID 3001)
            notificationManager.cancel(3001)

            // IMPORTANT: Directly update the static flow so MainActivity can observe the change
            SyncFlowCallService.dismissIncomingCall()
            Log.d(TAG, "📞 Called SyncFlowCallService.dismissIncomingCall()")

            // Send broadcast as backup
            val dismissIntent = Intent("com.phoneintegration.app.DISMISS_INCOMING_CALL").apply {
                putExtra("call_id", callId)
                putExtra("reason", status)
            }
            sendBroadcast(dismissIntent)

            // Try to tell the service to stop ringing (if it's running)
            try {
                val serviceIntent = Intent(this, SyncFlowCallService::class.java).apply {
                    action = SyncFlowCallService.ACTION_DISMISS_CALL_NOTIFICATION
                    putExtra(SyncFlowCallService.EXTRA_CALL_ID, callId)
                }
                startService(serviceIntent)
                Log.d(TAG, "📞 Sent dismiss intent to SyncFlowCallService")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send dismiss to service (may not be running)", e)
            }
        }
    }

    /**
     * Handles call ended by the remote party.
     *
     * This is sent when the other party ends the call. We need to clean up
     * the call UI and notify the call service to release resources.
     *
     * @param data FCM data containing the callId
     */
    private fun handleCallEndedByRemote(data: Map<String, String>) {
        val callId = data["callId"] ?: return
        Log.d(TAG, "📞 Call ended by remote: callId=$callId")

        // IMPORTANT: Directly update the static flow so MainActivity can observe the change
        SyncFlowCallService.dismissIncomingCall()
        Log.d(TAG, "📞 Called SyncFlowCallService.dismissIncomingCall()")

        // Send broadcast as backup
        val endCallIntent = Intent("com.phoneintegration.app.CALL_ENDED_BY_REMOTE").apply {
            putExtra("call_id", callId)
        }
        sendBroadcast(endCallIntent)

        // Tell the call service to end the call
        try {
            val serviceIntent = Intent(this, SyncFlowCallService::class.java).apply {
                action = SyncFlowCallService.ACTION_END_CALL
                putExtra(SyncFlowCallService.EXTRA_CALL_ID, callId)
            }
            startService(serviceIntent)
            Log.d(TAG, "📞 Sent end call intent to SyncFlowCallService")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send end call to service", e)
        }

        // Also try to directly end via the call manager
        serviceScope.launch {
            try {
                SyncFlowCallService.getCallManager()?.let { callManager ->
                    if (callManager.currentCall.value?.id == callId) {
                        Log.d(TAG, "📞 Ending call via call manager")
                        callManager.endUserCall()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error ending call via call manager", e)
            }
        }
    }

    // ==========================================
    // REGION: Message Handling
    // ==========================================

    /**
     * Handles outgoing message notifications from desktop/web.
     *
     * When a user sends a message from the desktop client, this notification
     * wakes up the OutgoingMessageService to process the pending message queue.
     *
     * @param data FCM data containing messageId and address (for logging)
     */
    private fun handleOutgoingMessage(data: Map<String, String>) {
        val messageId = data["messageId"] ?: return
        val address = data["address"] ?: ""

        Log.d(TAG, "Outgoing message notification: $messageId to $address")

        // Start OutgoingMessageService to process the message
        // The service will process pending messages and stop itself when done
        OutgoingMessageService.start(this)
    }

    // ==========================================
    // REGION: Phone Call Initiation
    // ==========================================

    /**
     * Handles phone call initiation request from Mac/Web via FCM.
     *
     * This allows users to make phone calls from the desktop without keeping
     * CallMonitorService running constantly. The FCM notification wakes up
     * the service to process the call request.
     *
     * @param data FCM data containing phoneNumber and requestId
     */
    private fun handleMakePhoneCall(data: Map<String, String>) {
        val phoneNumber = data["phoneNumber"] ?: return
        val requestId = data["requestId"] ?: return

        Log.d(TAG, "📞 FCM: Make phone call request - phoneNumber: $phoneNumber, requestId: $requestId")

        // Start CallMonitorService to handle the call
        // It will process the call_request from Firebase and make the call
        CallMonitorService.start(this)

        Log.d(TAG, "📞 FCM: Started CallMonitorService for outgoing call")
    }

    // ==========================================
    // REGION: SMS Insertion
    // ==========================================

    /**
     * Inserts a received SMS into the inbox ContentProvider.
     *
     * After decrypting an E2EE message, we insert it into the SMS inbox
     * so it appears in the conversation list alongside regular SMS messages.
     *
     * @param senderId The Firebase UID of the sender
     * @param body The decrypted message body
     */
    private fun insertSms(senderId: String, body: String) {
        serviceScope.launch {
            val address = getAddressForUid(senderId)
            if (address != null) {
                val values = ContentValues().apply {
                    put(Telephony.Sms.Inbox.ADDRESS, address)
                    put(Telephony.Sms.Inbox.BODY, body)
                    put(Telephony.Sms.Inbox.DATE, System.currentTimeMillis())
                    put(Telephony.Sms.Inbox.READ, 0)
                }
                contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
            }
        }
    }

    /**
     * Resolves a Firebase UID to a phone number.
     *
     * Looks up the user's registered phone number in Firebase to use as
     * the "from" address when inserting the SMS.
     *
     * @param uid Firebase UID of the sender
     * @return The sender's phone number, or null if not found
     */
    private suspend fun getAddressForUid(uid: String): String? {
        return try {
            val dataSnapshot = firebaseDatabase.getReference("users").child(uid).child("phoneNumber").get().await()
            dataSnapshot.getValue(String::class.java)
        } catch (e: Exception) {
            android.util.Log.e("SyncFlowMessaging", "Error getting address for UID: ${e.message}")
            null
        }
    }


    // ==========================================
    // REGION: FCM Token Management
    // ==========================================

    /**
     * Called when FCM token is refreshed.
     *
     * FCM tokens can change when:
     * - App is restored to a new device
     * - User uninstalls/reinstalls the app
     * - User clears app data
     * - Token is periodically refreshed by FCM
     *
     * @param token The new FCM token
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        sendTokenToServer(token)
    }

    /**
     * Sends the FCM token to Firebase for server-side push notifications.
     *
     * The token is stored at fcm_tokens/{userId} so Cloud Functions can
     * look it up when sending push notifications to this device.
     *
     * @param token The FCM token to register
     */
    private fun sendTokenToServer(token: String) {
        val uid = auth.currentUser?.uid
        if (uid != null) {
            firebaseDatabase.getReference("fcm_tokens").child(uid).setValue(token)
        }
    }
}
