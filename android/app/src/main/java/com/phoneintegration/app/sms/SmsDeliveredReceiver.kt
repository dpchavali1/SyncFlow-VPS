package com.phoneintegration.app.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.phoneintegration.app.vps.VPSClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver for SMS delivery confirmations.
 *
 * When an SMS is sent with a delivery PendingIntent, the carrier sends back a delivery report.
 * This receiver handles that report and updates the VPS server so Mac/Web can show double checkmarks.
 *
 * Extras passed via PendingIntent:
 * - "outgoing_id": The VPS outgoing message ID (e.g., "out_17...")
 *
 * The synced message ID is looked up from SharedPreferences ("delivery_mappings")
 * which is stored by OutgoingMessageService after the message is synced.
 */
class SmsDeliveredReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsDeliveredReceiver"
        const val ACTION_SMS_DELIVERED = "com.phoneintegration.app.SMS_DELIVERED"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val outgoingId = intent.getStringExtra("outgoing_id")

        // Look up the synced message ID from the mapping stored by OutgoingMessageService
        val syncedMessageId = if (!outgoingId.isNullOrEmpty()) {
            val prefs = context.getSharedPreferences("delivery_mappings", Context.MODE_PRIVATE)
            prefs.getString(outgoingId, null)
        } else null

        Log.d(TAG, "SMS delivery report: resultCode=$resultCode, outgoingId=$outgoingId, syncedId=$syncedMessageId")

        when (resultCode) {
            Activity.RESULT_OK -> {
                Log.d(TAG, "SMS delivered successfully")
                updateDeliveryStatus(context, outgoingId, syncedMessageId, "delivered")
            }
            else -> {
                Log.w(TAG, "SMS delivery failed or unknown: resultCode=$resultCode")
                // Don't update to failed — the SMS was sent successfully, delivery report just wasn't confirmed
            }
        }
    }

    private fun updateDeliveryStatus(
        context: Context,
        outgoingId: String?,
        syncedMessageId: String?,
        status: String
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val vpsClient = VPSClient.getInstance(context)

                // Update the outgoing message status
                if (!outgoingId.isNullOrEmpty()) {
                    try {
                        vpsClient.updateOutgoingStatus(outgoingId, status)
                        Log.d(TAG, "Updated outgoing message $outgoingId to $status")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to update outgoing status: ${e.message}")
                    }
                }

                // Update the synced message delivery status
                if (!syncedMessageId.isNullOrEmpty()) {
                    try {
                        vpsClient.updateMessageDeliveryStatus(syncedMessageId, status)
                        Log.d(TAG, "Updated synced message $syncedMessageId delivery to $status")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to update synced message delivery status: ${e.message}")
                    }
                }

                // Clean up the mapping
                if (!outgoingId.isNullOrEmpty()) {
                    try {
                        val prefs = context.getSharedPreferences("delivery_mappings", Context.MODE_PRIVATE)
                        prefs.edit().remove(outgoingId).apply()
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating delivery status", e)
            }
        }
    }
}
