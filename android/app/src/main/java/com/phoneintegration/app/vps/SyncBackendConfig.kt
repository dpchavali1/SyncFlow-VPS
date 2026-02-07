/**
 * Sync Backend Configuration
 *
 * This configuration manager allows switching between Firebase and VPS backends
 * for a gradual migration. During the transition period, both backends can coexist.
 */

package com.phoneintegration.app.vps

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Supported sync backends
 */
enum class SyncBackend {
    FIREBASE,  // Original Firebase Realtime Database
    VPS,       // New VPS server (PostgreSQL + WebSocket)
    HYBRID     // Use VPS for sync, Firebase for auth (transition mode)
}

/**
 * Backend configuration manager for controlling which sync backend to use.
 * This allows for a gradual migration from Firebase to VPS.
 */
class SyncBackendConfig private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SyncBackendConfig"
        private const val PREFS_NAME = "syncflow_backend_config"
        private const val KEY_BACKEND = "sync_backend"
        private const val KEY_VPS_URL = "vps_url"
        private const val KEY_MIGRATION_COMPLETE = "migration_complete"

        // Default VPS server URL
        const val DEFAULT_VPS_URL = "https://api.sfweb.app"

        @Volatile
        private var instance: SyncBackendConfig? = null

        fun getInstance(context: Context): SyncBackendConfig {
            return instance ?: synchronized(this) {
                instance ?: SyncBackendConfig(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _currentBackend = MutableStateFlow(getSavedBackend())
    val currentBackend: StateFlow<SyncBackend> = _currentBackend.asStateFlow()

    private val _vpsUrl = MutableStateFlow(getSavedVpsUrl())
    val vpsUrl: StateFlow<String> = _vpsUrl.asStateFlow()

    /**
     * Get the saved backend configuration
     * DEFAULT: VPS for this version (SyncFlow-VPS)
     */
    private fun getSavedBackend(): SyncBackend {
        val backendName = prefs.getString(KEY_BACKEND, SyncBackend.VPS.name)
        return try {
            SyncBackend.valueOf(backendName ?: SyncBackend.VPS.name)
        } catch (e: Exception) {
            SyncBackend.VPS
        }
    }

    /**
     * Get the saved VPS URL
     */
    private fun getSavedVpsUrl(): String {
        return prefs.getString(KEY_VPS_URL, DEFAULT_VPS_URL) ?: DEFAULT_VPS_URL
    }

    /**
     * Set the sync backend
     */
    fun setBackend(backend: SyncBackend) {
        prefs.edit().putString(KEY_BACKEND, backend.name).apply()
        _currentBackend.value = backend
        Log.i(TAG, "Sync backend set to: $backend")
    }

    /**
     * Set the VPS server URL
     */
    fun setVpsUrl(url: String) {
        prefs.edit().putString(KEY_VPS_URL, url).apply()
        _vpsUrl.value = url
        Log.i(TAG, "VPS URL set to: $url")
    }

    /**
     * Check if using VPS backend
     */
    fun isUsingVps(): Boolean {
        return _currentBackend.value == SyncBackend.VPS || _currentBackend.value == SyncBackend.HYBRID
    }

    /**
     * Check if using Firebase backend
     */
    fun isUsingFirebase(): Boolean {
        return _currentBackend.value == SyncBackend.FIREBASE || _currentBackend.value == SyncBackend.HYBRID
    }

    /**
     * Mark migration as complete
     */
    fun markMigrationComplete() {
        prefs.edit().putBoolean(KEY_MIGRATION_COMPLETE, true).apply()
        Log.i(TAG, "Migration marked as complete")
    }

    /**
     * Check if migration is complete
     */
    fun isMigrationComplete(): Boolean {
        return prefs.getBoolean(KEY_MIGRATION_COMPLETE, false)
    }

    /**
     * Reset to default Firebase backend
     */
    fun resetToFirebase() {
        setBackend(SyncBackend.FIREBASE)
        Log.i(TAG, "Reset to Firebase backend")
    }

    /**
     * Switch to VPS backend
     */
    fun switchToVps() {
        setBackend(SyncBackend.VPS)
        Log.i(TAG, "Switched to VPS backend")
    }

    /**
     * Use hybrid mode (VPS for sync, Firebase for auth)
     */
    fun useHybridMode() {
        setBackend(SyncBackend.HYBRID)
        Log.i(TAG, "Using hybrid mode")
    }
}
