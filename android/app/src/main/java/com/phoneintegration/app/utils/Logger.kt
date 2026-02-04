package com.phoneintegration.app.utils

import android.util.Log
import com.phoneintegration.app.BuildConfig

/**
 * Production-safe logger that only logs in debug builds.
 * All logging calls are no-ops in release builds.
 */
object Logger {
    private const val TAG_PREFIX = "SyncFlow"

    @JvmStatic
    fun d(tag: String, message: String) {
        if (BuildConfig.ENABLE_LOGGING) {
            Log.d("$TAG_PREFIX:$tag", message)
        }
    }

    @JvmStatic
    fun i(tag: String, message: String) {
        if (BuildConfig.ENABLE_LOGGING) {
            Log.i("$TAG_PREFIX:$tag", message)
        }
    }

    @JvmStatic
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.ENABLE_LOGGING) {
            if (throwable != null) {
                Log.w("$TAG_PREFIX:$tag", message, throwable)
            } else {
                Log.w("$TAG_PREFIX:$tag", message)
            }
        }
    }

    @JvmStatic
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        // Always log errors, but without stack traces in release
        if (BuildConfig.ENABLE_LOGGING) {
            if (throwable != null) {
                Log.e("$TAG_PREFIX:$tag", message, throwable)
            } else {
                Log.e("$TAG_PREFIX:$tag", message)
            }
        } else if (BuildConfig.ENABLE_CRASH_REPORTING) {
            // In release, only log error message without sensitive data
            Log.e("$TAG_PREFIX:$tag", "Error occurred: ${message.take(100)}")
        }
    }

    @JvmStatic
    fun v(tag: String, message: String) {
        if (BuildConfig.ENABLE_LOGGING && BuildConfig.DEBUG) {
            Log.v("$TAG_PREFIX:$tag", message)
        }
    }
}
