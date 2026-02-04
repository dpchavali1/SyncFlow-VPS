package com.phoneintegration.app.desktop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

/**
 * Service that monitors and syncs phone status (battery, signal, WiFi) to Firebase
 * for display on macOS/desktop clients.
 */
class PhoneStatusService(context: Context) {
    private val context: Context = context.applicationContext
    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()

    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    private var batteryReceiver: BroadcastReceiver? = null
    private var signalStrengthListener: Any? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Debounce sync to avoid flooding Firebase
    private var syncJob: Job? = null
    private var lastSyncedStatus: PhoneStatus? = null

    // Current status values
    private var currentBatteryLevel: Int = -1
    private var currentIsCharging: Boolean = false
    private var currentSignalStrength: Int = -1 // 0-4 bars
    private var currentNetworkType: String = "unknown"
    private var currentWifiConnected: Boolean = false
    private var currentWifiStrength: Int = -1 // 0-4 bars
    private var currentWifiSsid: String? = null
    private var currentCellularConnected: Boolean = false

    companion object {
        private const val TAG = "PhoneStatusService"
        private const val STATUS_PATH = "phone_status"
        private const val USERS_PATH = "users"
        private const val SYNC_DEBOUNCE_MS = 2000L // Debounce sync by 2 seconds
    }

    /**
     * Data class representing phone status
     */
    data class PhoneStatus(
        val batteryLevel: Int,
        val isCharging: Boolean,
        val signalStrength: Int, // 0-4 bars
        val networkType: String, // "5G", "LTE", "3G", "2G", "unknown"
        val wifiConnected: Boolean,
        val wifiStrength: Int, // 0-4 bars
        val wifiSsid: String?,
        val cellularConnected: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Start monitoring phone status
     */
    fun startMonitoring() {
        Log.d(TAG, "Starting phone status monitoring")
        database.goOnline()
        registerBatteryReceiver()
        registerSignalStrengthListener()
        registerNetworkCallback()

        // Initial sync
        scope.launch {
            updateAndSyncStatus()
        }
    }

    /**
     * Stop monitoring phone status
     */
    fun stopMonitoring() {
        Log.d(TAG, "Stopping phone status monitoring")
        unregisterBatteryReceiver()
        unregisterSignalStrengthListener()
        unregisterNetworkCallback()
        scope.cancel()
    }

    /**
     * Get current phone status
     */
    fun getCurrentStatus(): PhoneStatus {
        return PhoneStatus(
            batteryLevel = currentBatteryLevel,
            isCharging = currentIsCharging,
            signalStrength = currentSignalStrength,
            networkType = currentNetworkType,
            wifiConnected = currentWifiConnected,
            wifiStrength = currentWifiStrength,
            wifiSsid = currentWifiSsid,
            cellularConnected = currentCellularConnected
        )
    }

    /**
     * Register battery change receiver
     */
    private fun registerBatteryReceiver() {
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.let { updateBatteryStatus(it) }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }

        context.registerReceiver(batteryReceiver, filter)

        // Get initial battery status
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryIntent?.let { updateBatteryStatus(it) }
    }

    private fun unregisterBatteryReceiver() {
        batteryReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering battery receiver", e)
            }
        }
        batteryReceiver = null
    }

    /**
     * Update battery status from intent
     */
    private fun updateBatteryStatus(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

        currentBatteryLevel = if (scale > 0) (level * 100 / scale) else level
        currentIsCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                           status == BatteryManager.BATTERY_STATUS_FULL

        debouncedSync()
    }

    /**
     * Register signal strength listener
     */
    @Suppress("DEPRECATION")
    private fun registerSignalStrengthListener() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+
                registerTelephonyCallback()
            } else {
                // Older Android versions
                registerPhoneStateListener()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering signal strength listener", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerTelephonyCallback() {
        val callback = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                updateSignalStrength(signalStrength)
            }
        }
        telephonyManager.registerTelephonyCallback(context.mainExecutor, callback)
        signalStrengthListener = callback
    }

    @Suppress("DEPRECATION")
    private fun registerPhoneStateListener() {
        val listener = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                signalStrength?.let { updateSignalStrength(it) }
            }
        }
        telephonyManager.listen(listener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
        signalStrengthListener = listener
    }

    @Suppress("DEPRECATION")
    private fun unregisterSignalStrengthListener() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (signalStrengthListener as? TelephonyCallback)?.let {
                    telephonyManager.unregisterTelephonyCallback(it)
                }
            } else {
                (signalStrengthListener as? PhoneStateListener)?.let {
                    telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering signal strength listener", e)
        }
        signalStrengthListener = null
    }

    /**
     * Update signal strength
     */
    private fun updateSignalStrength(signalStrength: SignalStrength) {
        // Get signal level (0-4)
        currentSignalStrength = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            signalStrength.level
        } else {
            // Estimate from ASU value for older devices
            @Suppress("DEPRECATION")
            val asu = signalStrength.gsmSignalStrength
            when {
                asu >= 12 -> 4
                asu >= 8 -> 3
                asu >= 5 -> 2
                asu >= 0 -> 1
                else -> 0
            }
        }

        // Get network type
        currentNetworkType = getNetworkTypeName()

        debouncedSync()
    }

    /**
     * Get network type name
     */
    @Suppress("DEPRECATION")
    private fun getNetworkTypeName(): String {
        return try {
            when (telephonyManager.dataNetworkType) {
                TelephonyManager.NETWORK_TYPE_NR -> "5G"
                TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                TelephonyManager.NETWORK_TYPE_HSPAP,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA -> "3G+"
                TelephonyManager.NETWORK_TYPE_UMTS,
                TelephonyManager.NETWORK_TYPE_EVDO_0,
                TelephonyManager.NETWORK_TYPE_EVDO_A,
                TelephonyManager.NETWORK_TYPE_EVDO_B -> "3G"
                TelephonyManager.NETWORK_TYPE_EDGE,
                TelephonyManager.NETWORK_TYPE_GPRS,
                TelephonyManager.NETWORK_TYPE_CDMA,
                TelephonyManager.NETWORK_TYPE_1xRTT -> "2G"
                else -> "unknown"
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "No permission to read network type", e)
            "unknown"
        }
    }

    /**
     * Register network callback for WiFi/cellular changes
     */
    private fun registerNetworkCallback() {
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateNetworkStatus()
            }

            override fun onLost(network: Network) {
                updateNetworkStatus()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                updateNetworkStatus()
            }
        }

        connectivityManager.registerDefaultNetworkCallback(networkCallback!!)

        // Initial update
        updateNetworkStatus()
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering network callback", e)
            }
        }
        networkCallback = null
    }

    /**
     * Update network (WiFi/cellular) status
     */
    @Suppress("DEPRECATION")
    private fun updateNetworkStatus() {
        try {
            val activeNetwork = connectivityManager.activeNetwork
            val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }

            currentWifiConnected = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            currentCellularConnected = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

            if (currentWifiConnected) {
                // Get WiFi details - wrapped in try-catch for permission issues
                try {
                    val wifiInfo = wifiManager.connectionInfo
                    currentWifiSsid = wifiInfo?.ssid?.replace("\"", "") ?: "Unknown"
                    if (currentWifiSsid == "<unknown ssid>") {
                        currentWifiSsid = "WiFi"
                    }

                    // Calculate WiFi strength (0-4 bars)
                    val rssi = wifiInfo?.rssi ?: -100
                    currentWifiStrength = WifiManager.calculateSignalLevel(rssi, 5)
                } catch (e: SecurityException) {
                    Log.w(TAG, "No permission to access WiFi info", e)
                    currentWifiSsid = "WiFi"
                    currentWifiStrength = 2 // Default to medium strength
                }
            } else {
                currentWifiSsid = null
                currentWifiStrength = -1
            }

            debouncedSync()
        } catch (e: Exception) {
            Log.e(TAG, "Error updating network status", e)
        }
    }

    /**
     * Update all status values and sync
     */
    private suspend fun updateAndSyncStatus() {
        // Battery is updated via receiver, just sync current values
        syncStatus()
    }

    /**
     * Debounced sync - prevents flooding Firebase with updates
     */
    private fun debouncedSync() {
        syncJob?.cancel()
        syncJob = scope.launch {
            delay(SYNC_DEBOUNCE_MS)
            syncStatus()
        }
    }

    /**
     * Sync current status to Firebase
     */
    private suspend fun syncStatus() {
        try {
            val currentUser = auth.currentUser ?: return
            val userId = currentUser.uid

            val status = getCurrentStatus()

            // Only sync if status actually changed
            lastSyncedStatus?.let { last ->
                if (last.batteryLevel == status.batteryLevel &&
                    last.isCharging == status.isCharging &&
                    last.signalStrength == status.signalStrength &&
                    last.networkType == status.networkType &&
                    last.wifiConnected == status.wifiConnected &&
                    last.cellularConnected == status.cellularConnected) {
                    return // No change, skip sync
                }
            }

            val statusRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(STATUS_PATH)

            val statusData = mapOf(
                "batteryLevel" to status.batteryLevel,
                "isCharging" to status.isCharging,
                "signalStrength" to status.signalStrength,
                "networkType" to status.networkType,
                "wifiConnected" to status.wifiConnected,
                "wifiStrength" to status.wifiStrength,
                "wifiSsid" to (status.wifiSsid ?: ""),
                "cellularConnected" to status.cellularConnected,
                "timestamp" to ServerValue.TIMESTAMP,
                "deviceModel" to Build.MODEL,
                "deviceName" to (Build.MODEL ?: "Android Device")
            )

            // Use NonCancellable to ensure Firebase operation completes even if scope is cancelled
            withContext(NonCancellable) {
                statusRef.setValue(statusData).await()
            }
            lastSyncedStatus = status
            Log.d(TAG, "Phone status synced to Firebase")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Ignore cancellation - this is expected during shutdown
            Log.d(TAG, "Phone status sync cancelled (expected during shutdown)")
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing phone status", e)
        }
    }

    /**
     * Force sync status (for manual refresh)
     */
    suspend fun forceSync() {
        updateNetworkStatus()
        syncStatus()
    }
}
