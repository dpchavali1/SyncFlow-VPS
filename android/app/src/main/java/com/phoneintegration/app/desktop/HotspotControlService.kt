package com.phoneintegration.app.desktop

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.phoneintegration.app.vps.VPSClient
import kotlinx.coroutines.*
import java.lang.reflect.Method

/**
 * Service to control mobile hotspot from macOS.
 * Due to Android security restrictions (Android 10+), we can only:
 * - Check hotspot status
 * - Open hotspot settings for user to toggle manually
 * - On older devices, toggle programmatically
 *
 * VPS Backend Only - Uses VPS API instead of Firebase.
 */
class HotspotControlService(context: Context) {
    private val context: Context = context.applicationContext
    private val vpsClient = VPSClient.getInstance(context)
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var commandPollingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "HotspotControlService"
        private const val COMMAND_POLL_INTERVAL_MS = 2000L
    }

    /**
     * Start listening for hotspot commands from macOS via polling
     */
    fun startListening() {
        commandPollingJob?.cancel()
        commandPollingJob = scope.launch {
            // Sync initial status
            syncHotspotStatus()

            while (isActive) {
                try {
                    if (!vpsClient.isAuthenticated) {
                        delay(COMMAND_POLL_INTERVAL_MS)
                        continue
                    }

                    val commands = vpsClient.getHotspotCommands()
                    for (command in commands) {
                        // Only process recent commands (within 10 seconds)
                        if (System.currentTimeMillis() - command.timestamp > 10000) continue

                        Log.d(TAG, "Received hotspot command: ${command.action}")

                        when (command.action) {
                            "toggle" -> toggleHotspot()
                            "enable" -> enableHotspot()
                            "disable" -> disableHotspot()
                            "status" -> syncHotspotStatus()
                            "open_settings" -> openHotspotSettings()
                        }

                        // Mark command as processed
                        vpsClient.markHotspotCommandProcessed(command.id)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error polling hotspot commands", e)
                }
                delay(COMMAND_POLL_INTERVAL_MS)
            }
        }
        Log.d(TAG, "HotspotControlService started listening")
    }

    /**
     * Stop listening for commands
     */
    fun stopListening() {
        commandPollingJob?.cancel()
        commandPollingJob = null
        scope.cancel()
        Log.d(TAG, "HotspotControlService stopped")
    }

    /**
     * Check if hotspot is currently enabled
     */
    fun isHotspotEnabled(): Boolean {
        return try {
            val method: Method = wifiManager.javaClass.getDeclaredMethod("isWifiApEnabled")
            method.isAccessible = true
            method.invoke(wifiManager) as Boolean
        } catch (e: Exception) {
            Log.e(TAG, "Error checking hotspot status", e)
            false
        }
    }

    /**
     * Toggle hotspot state
     */
    private fun toggleHotspot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 8+: Open settings for user to toggle
            openHotspotSettings()
            sendStatusUpdate(isHotspotEnabled(), "Please toggle hotspot in Settings")
        } else {
            // Older Android: Try to toggle programmatically
            val currentState = isHotspotEnabled()
            if (currentState) {
                disableHotspot()
            } else {
                enableHotspot()
            }
        }
    }

    /**
     * Enable hotspot (works on Android < 8.0)
     */
    @Suppress("DEPRECATION")
    private fun enableHotspot() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                openHotspotSettings()
                sendStatusUpdate(isHotspotEnabled(), "Please enable hotspot in Settings")
                return
            }

            val method = wifiManager.javaClass.getMethod(
                "setWifiApEnabled",
                android.net.wifi.WifiConfiguration::class.java,
                Boolean::class.javaPrimitiveType
            )

            // Disable WiFi first
            wifiManager.isWifiEnabled = false

            method.invoke(wifiManager, null, true)
            Log.d(TAG, "Hotspot enabled")

            // Sync status after a delay
            mainHandler.postDelayed({ syncHotspotStatus() }, 2000)
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling hotspot", e)
            sendStatusUpdate(false, "Failed to enable hotspot: ${e.message}")
        }
    }

    /**
     * Disable hotspot (works on Android < 8.0)
     */
    @Suppress("DEPRECATION")
    private fun disableHotspot() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                openHotspotSettings()
                sendStatusUpdate(isHotspotEnabled(), "Please disable hotspot in Settings")
                return
            }

            val method = wifiManager.javaClass.getMethod(
                "setWifiApEnabled",
                android.net.wifi.WifiConfiguration::class.java,
                Boolean::class.javaPrimitiveType
            )

            method.invoke(wifiManager, null, false)
            Log.d(TAG, "Hotspot disabled")

            // Sync status after a delay
            mainHandler.postDelayed({ syncHotspotStatus() }, 2000)
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling hotspot", e)
            sendStatusUpdate(false, "Failed to disable hotspot: ${e.message}")
        }
    }

    /**
     * Open hotspot settings screen
     */
    private fun openHotspotSettings() {
        try {
            val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Try to open tethering settings directly
            try {
                val tetheringIntent = Intent().apply {
                    setClassName("com.android.settings", "com.android.settings.TetherSettings")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(tetheringIntent)
            } catch (e: Exception) {
                // Fallback to wireless settings
                context.startActivity(intent)
            }

            Log.d(TAG, "Opened hotspot settings")
            sendStatusUpdate(isHotspotEnabled(), "Settings opened - please toggle hotspot")
        } catch (e: Exception) {
            Log.e(TAG, "Error opening settings", e)
        }
    }

    /**
     * Sync current hotspot status to VPS
     */
    private fun syncHotspotStatus() {
        scope.launch {
            try {
                if (!vpsClient.isAuthenticated) return@launch

                val isEnabled = isHotspotEnabled()
                val ssid = getHotspotSSID()
                val connectedDevices = getConnectedDevicesCount()

                val statusData = mapOf(
                    "enabled" to isEnabled,
                    "ssid" to (ssid ?: ""),
                    "connectedDevices" to connectedDevices,
                    "canToggleProgrammatically" to (Build.VERSION.SDK_INT < Build.VERSION_CODES.O),
                    "timestamp" to System.currentTimeMillis()
                )

                vpsClient.syncHotspotStatus(statusData)
                Log.d(TAG, "Hotspot status synced: enabled=$isEnabled")
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing hotspot status", e)
            }
        }
    }

    /**
     * Send status update with optional message
     */
    private fun sendStatusUpdate(enabled: Boolean, message: String? = null) {
        scope.launch {
            try {
                if (!vpsClient.isAuthenticated) return@launch

                val statusData = mutableMapOf<String, Any>(
                    "enabled" to enabled,
                    "canToggleProgrammatically" to (Build.VERSION.SDK_INT < Build.VERSION_CODES.O),
                    "timestamp" to System.currentTimeMillis()
                )

                message?.let { statusData["message"] = it }

                vpsClient.syncHotspotStatus(statusData)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending status update", e)
            }
        }
    }

    /**
     * Get hotspot SSID (network name)
     */
    @Suppress("DEPRECATION")
    private fun getHotspotSSID(): String? {
        return try {
            val method = wifiManager.javaClass.getDeclaredMethod("getWifiApConfiguration")
            method.isAccessible = true
            val config = method.invoke(wifiManager) as? android.net.wifi.WifiConfiguration
            config?.SSID
        } catch (e: Exception) {
            Log.e(TAG, "Error getting hotspot SSID", e)
            null
        }
    }

    /**
     * Get count of devices connected to hotspot
     */
    private fun getConnectedDevicesCount(): Int {
        // This is a rough estimate - proper implementation would read /proc/net/arp
        return try {
            val process = Runtime.getRuntime().exec("cat /proc/net/arp")
            val reader = process.inputStream.bufferedReader()
            var count = 0
            reader.forEachLine { line ->
                if (!line.contains("IP address") && line.isNotBlank()) {
                    count++
                }
            }
            reader.close()
            // Subtract 1 for the header line if present
            maxOf(0, count - 1)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting connected devices count", e)
            0
        }
    }
}
