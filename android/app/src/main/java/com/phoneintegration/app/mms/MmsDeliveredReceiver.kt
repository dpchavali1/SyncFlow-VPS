package com.phoneintegration.app.mms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.phoneintegration.app.vps.VPSClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MmsDeliveredReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MmsDeliveredReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "MMS_DELIVERED broadcast received")

        val outgoingId = intent.getStringExtra("outgoing_id")
        val result = resultCode

        when (result) {
            Activity.RESULT_OK -> {
                Toast.makeText(context, "MMS delivered!", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "MMS delivered to recipient")
                syncDeliveryStatus(context, outgoingId, "delivered")
            }
            else -> {
                Log.w(TAG, "MMS delivery report: result=$result (carrier may not support delivery reports)")
                // Don't update to failed — MMS delivery reports are unreliable
            }
        }
    }

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

                    // Clean up mapping
                    prefs.edit().remove(outgoingId).apply()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync MMS delivery status: ${e.message}")
            }
        }
    }
}
