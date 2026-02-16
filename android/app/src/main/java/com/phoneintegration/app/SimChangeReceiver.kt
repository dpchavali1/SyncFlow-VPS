package com.phoneintegration.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.phoneintegration.app.ui.components.clearPhoneRegistration
import com.phoneintegration.app.ui.components.getRegisteredPhoneNumber
import com.phoneintegration.app.vps.VPSClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SimChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.telephony.action.SIM_CARD_STATE_CHANGED" ||
            intent.action == "android.intent.action.SIM_STATE_CHANGED") {

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val vpsClient = VPSClient.getInstance(context)
                    if (!vpsClient.isAuthenticated) return@launch

                    // Only re-register if the SIM number actually changed
                    val simManager = SimManager(context)
                    val primarySim = simManager.getActiveSims().minByOrNull { it.slotIndex }
                    val simPhone = primarySim?.phoneNumber
                    if (simPhone.isNullOrEmpty() || simPhone == "Unknown") return@launch

                    val normalizedSim = PhoneNumberUtils.toE164(simPhone)
                    val currentRegistered = getRegisteredPhoneNumber(context)

                    if (currentRegistered != null && currentRegistered == normalizedSim) {
                        Log.d("SimChangeReceiver", "SIM state changed but number unchanged, skipping")
                        return@launch
                    }

                    Log.d("SimChangeReceiver", "SIM changed: $currentRegistered → $normalizedSim, re-registering")
                    clearPhoneRegistration(context)
                    vpsClient.autoRegisterPhoneNumbers()
                } catch (e: Exception) {
                    Log.w("SimChangeReceiver", "Failed to handle SIM change: ${e.message}")
                }
            }
        }
    }
}
