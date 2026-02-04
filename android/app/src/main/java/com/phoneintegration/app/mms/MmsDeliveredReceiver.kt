package com.phoneintegration.app.mms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

class MmsDeliveredReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("MmsDeliveredReceiver", "MMS_DELIVERED broadcast received")

        val result = resultCode

        when (result) {
            android.app.Activity.RESULT_OK -> {
                Toast.makeText(context, "MMS delivered!", Toast.LENGTH_SHORT).show()
                Log.d("MmsDeliveredReceiver", "MMS delivered to recipient")
            }
            else -> {
                Toast.makeText(context, "Delivery failed!", Toast.LENGTH_LONG).show()
                Log.e("MmsDeliveredReceiver", "MMS delivery failed: result=$result")
            }
        }
    }
}
