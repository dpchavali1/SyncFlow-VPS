package com.phoneintegration.app.mms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import com.phoneintegration.app.vps.VPSClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MmsSentReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MmsSentReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "=== MMS_SENT broadcast received ===")
        Log.d(TAG, "Intent action: ${intent.action}")
        Log.d(TAG, "Intent extras: ${intent.extras?.keySet()?.joinToString()}")

        var mmsId = intent.getLongExtra("mms_id", -1)
        val threadId = intent.getLongExtra("thread_id", -1)
        val contentUriRaw = intent.getStringExtra("content_uri")
        val outgoingId = intent.getStringExtra("outgoing_id")
        if (mmsId <= 0 && !contentUriRaw.isNullOrBlank()) {
            mmsId = runCatching { ContentUris.parseId(Uri.parse(contentUriRaw)) }
                .getOrDefault(-1)
        }
        Log.d(TAG, "MMS ID: $mmsId, Thread ID: $threadId, content_uri=$contentUriRaw, outgoingId=$outgoingId")

        val result = resultCode
        Log.d(TAG, "Result code: $result")

        val resultMessage = when (result) {
            Activity.RESULT_OK -> "SUCCESS (RESULT_OK)"
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "GENERIC_FAILURE"
            SmsManager.RESULT_ERROR_NO_SERVICE -> "NO_SERVICE"
            SmsManager.RESULT_ERROR_NULL_PDU -> "NULL_PDU"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "RADIO_OFF"
            SmsManager.MMS_ERROR_UNSPECIFIED -> "MMS_ERROR_UNSPECIFIED"
            SmsManager.MMS_ERROR_INVALID_APN -> "MMS_ERROR_INVALID_APN"
            SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS -> "MMS_ERROR_UNABLE_CONNECT_MMS"
            SmsManager.MMS_ERROR_HTTP_FAILURE -> "MMS_ERROR_HTTP_FAILURE"
            SmsManager.MMS_ERROR_IO_ERROR -> "MMS_ERROR_IO_ERROR"
            SmsManager.MMS_ERROR_RETRY -> "MMS_ERROR_RETRY"
            SmsManager.MMS_ERROR_CONFIGURATION_ERROR -> "MMS_ERROR_CONFIGURATION_ERROR"
            SmsManager.MMS_ERROR_NO_DATA_NETWORK -> "MMS_ERROR_NO_DATA_NETWORK"
            else -> "UNKNOWN_ERROR (code: $result)"
        }

        Log.d(TAG, "MMS send result: $resultMessage")

        when (result) {
            Activity.RESULT_OK -> {
                Toast.makeText(context, "MMS sent successfully!", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "MMS successfully sent")

                // Move MMS from outbox to sent folder
                if (mmsId > 0) {
                    moveMmsToSent(context, mmsId)
                }

                // Sync sent status to VPS for the synced message
                syncDeliveryStatus(context, outgoingId, "sent")
            }
            else -> {
                Toast.makeText(context, "MMS failed: $resultMessage", Toast.LENGTH_LONG).show()
                Log.e(TAG, "MMS send failed: $resultMessage")

                // Move to failed folder or delete from outbox
                if (mmsId > 0) {
                    moveMmsToFailed(context, mmsId)
                }

                // Sync failed status to VPS
                syncDeliveryStatus(context, outgoingId, "failed")
            }
        }
    }

    /**
     * Move MMS from outbox to sent folder
     */
    private fun moveMmsToSent(context: Context, mmsId: Long) {
        try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put("msg_box", 2)  // MESSAGE_BOX_SENT = 2
                put("date_sent", System.currentTimeMillis() / 1000)
            }
            val updated = resolver.update(
                ContentUris.withAppendedId(Uri.parse("content://mms"), mmsId),
                values,
                null,
                null
            )
            Log.d(TAG, "Moved MMS $mmsId to sent folder: $updated rows updated")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to move MMS to sent: ${e.message}")
        }
    }

    /**
     * Move MMS to failed folder (msg_box = 5)
     */
    private fun moveMmsToFailed(context: Context, mmsId: Long) {
        try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put("msg_box", 5)  // MESSAGE_BOX_FAILED = 5
            }
            val updated = resolver.update(
                ContentUris.withAppendedId(Uri.parse("content://mms"), mmsId),
                values,
                null,
                null
            )
            Log.d(TAG, "Moved MMS $mmsId to failed folder: $updated rows updated")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to move MMS to failed: ${e.message}")
        }
    }

    /**
     * Sync delivery status to VPS server so Mac/Web can show status indicators.
     */
    private fun syncDeliveryStatus(context: Context, outgoingId: String?, status: String) {
        if (outgoingId.isNullOrEmpty()) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val vpsClient = VPSClient.getInstance(context)

                // Look up synced message ID from mapping
                val prefs = context.getSharedPreferences("delivery_mappings", Context.MODE_PRIVATE)
                val syncedMessageId = prefs.getString(outgoingId, null)

                if (!syncedMessageId.isNullOrEmpty()) {
                    vpsClient.updateMessageDeliveryStatus(syncedMessageId, status)
                    Log.d(TAG, "Synced MMS delivery status: $syncedMessageId -> $status")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync MMS delivery status: ${e.message}")
            }
        }
    }
}
