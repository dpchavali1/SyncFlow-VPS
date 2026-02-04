package com.phoneintegration.app.desktop

import android.content.Context

data class SyncDiagnostics(
    val lastStart: Long,
    val lastEnd: Long,
    val lastTotal: Int,
    val lastDone: Int,
    val lastStatus: String?,
    val lastError: String?
)

class SyncDiagnosticsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): SyncDiagnostics {
        return SyncDiagnostics(
            lastStart = prefs.getLong(KEY_LAST_START, 0L),
            lastEnd = prefs.getLong(KEY_LAST_END, 0L),
            lastTotal = prefs.getInt(KEY_LAST_TOTAL, 0),
            lastDone = prefs.getInt(KEY_LAST_DONE, 0),
            lastStatus = prefs.getString(KEY_LAST_STATUS, null),
            lastError = prefs.getString(KEY_LAST_ERROR, null)
        )
    }

    fun markStart() {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putLong(KEY_LAST_START, now)
            .putLong(KEY_LAST_END, 0L)
            .putInt(KEY_LAST_TOTAL, 0)
            .putInt(KEY_LAST_DONE, 0)
            .putString(KEY_LAST_STATUS, "Starting sync")
            .putString(KEY_LAST_ERROR, null)
            .apply()
    }

    fun updateProgress(done: Int, total: Int, status: String) {
        prefs.edit()
            .putInt(KEY_LAST_TOTAL, total)
            .putInt(KEY_LAST_DONE, done)
            .putString(KEY_LAST_STATUS, status)
            .apply()
    }

    fun markComplete(done: Int, total: Int) {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putLong(KEY_LAST_END, now)
            .putInt(KEY_LAST_TOTAL, total)
            .putInt(KEY_LAST_DONE, done)
            .putString(KEY_LAST_STATUS, "Sync complete")
            .apply()
    }

    fun markFailed(error: String?) {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putLong(KEY_LAST_END, now)
            .putString(KEY_LAST_STATUS, "Sync failed")
            .putString(KEY_LAST_ERROR, error ?: "Unknown error")
            .apply()
    }

    fun markCancelled(done: Int, total: Int) {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putLong(KEY_LAST_END, now)
            .putInt(KEY_LAST_TOTAL, total)
            .putInt(KEY_LAST_DONE, done)
            .putString(KEY_LAST_STATUS, "Sync cancelled")
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "syncflow_sync_diagnostics"
        private const val KEY_LAST_START = "last_start"
        private const val KEY_LAST_END = "last_end"
        private const val KEY_LAST_TOTAL = "last_total"
        private const val KEY_LAST_DONE = "last_done"
        private const val KEY_LAST_STATUS = "last_status"
        private const val KEY_LAST_ERROR = "last_error"
    }
}
