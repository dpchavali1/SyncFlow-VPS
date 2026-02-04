package com.phoneintegration.app

import android.app.IntentService
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Service required for default SMS app functionality.
 * Handles "respond via message" intents from the system (e.g., from notification quick reply).
 */
class HeadlessSmsSendService : IntentService("HeadlessSmsSendService") {

    companion object {
        private const val TAG = "HeadlessSmsSend"
        private const val ACTION_RESPOND_VIA_MESSAGE = "android.intent.action.RESPOND_VIA_MESSAGE"
    }

    override fun onHandleIntent(intent: Intent?) {
        if (intent == null) return

        val action = intent.action
        Log.d(TAG, "onHandleIntent action: $action")

        if (ACTION_RESPOND_VIA_MESSAGE == action) {
            val extras = intent.extras ?: return

            // Get the message text
            val message = extras.getString(Intent.EXTRA_TEXT)
            if (message.isNullOrBlank()) {
                Log.w(TAG, "No message text provided")
                return
            }

            // Get the recipient from the data URI
            val uri: Uri? = intent.data
            if (uri == null) {
                Log.w(TAG, "No recipient URI provided")
                return
            }

            val recipients = getRecipients(uri)
            if (recipients.isEmpty()) {
                Log.w(TAG, "No recipients found in URI: $uri")
                return
            }

            Log.d(TAG, "Sending message to ${recipients.size} recipient(s)")

            // Send SMS to each recipient
            val smsManager = SmsManager.getDefault()
            for (recipient in recipients) {
                try {
                    val parts = smsManager.divideMessage(message)
                    if (parts.size > 1) {
                        smsManager.sendMultipartTextMessage(recipient, null, parts, null, null)
                    } else {
                        smsManager.sendTextMessage(recipient, null, message, null, null)
                    }
                    Log.d(TAG, "Message sent to $recipient")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send message to $recipient", e)
                }
            }
        }
    }

    private fun getRecipients(uri: Uri): List<String> {
        val recipients = mutableListOf<String>()

        // Try to get from scheme-specific part (smsto:+1234567890)
        val schemeSpecificPart = uri.schemeSpecificPart
        if (!schemeSpecificPart.isNullOrBlank()) {
            // Handle multiple recipients separated by comma or semicolon
            val parts = schemeSpecificPart.split(",", ";")
            for (part in parts) {
                val cleaned = part.trim().replace(Regex("[^0-9+]"), "")
                if (cleaned.isNotBlank()) {
                    recipients.add(cleaned)
                }
            }
        }

        return recipients
    }
}
