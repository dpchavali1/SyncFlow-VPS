package com.phoneintegration.app.desktop

import android.content.Context

/**
 * PhotoSyncService - REMOVED
 *
 * Photo sync feature has been removed from SyncFlow. This stub class is kept
 * to prevent compilation errors from any remaining references.
 */
class PhotoSyncService(private val context: Context) {

    companion object {
        private const val TAG = "PhotoSyncService"
    }

    fun startSync() { /* no-op */ }
    fun stopSync() { /* no-op */ }
    fun syncNow() { /* no-op */ }
    fun reduceSyncFrequency() { /* no-op */ }
    fun pauseSync() { /* no-op */ }
    fun resumeSync() { /* no-op */ }
}
