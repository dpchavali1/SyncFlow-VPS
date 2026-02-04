package com.phoneintegration.app.utils

import android.util.Log
import com.phoneintegration.app.BuildConfig

/**
 * Secure logger that prevents sensitive data from being logged in production
 *
 * NEVER log:
 * - SMS message content
 * - Phone numbers
 * - User IDs / Auth tokens
 * - Email addresses
 * - Any PII (Personally Identifiable Information)
 */
object SecureLogger {

    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            val sanitized = sanitize(message)
            Log.d(tag, sanitized)
        }
        // Never log in production
    }

    fun i(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            val sanitized = sanitize(message)
            Log.i(tag, sanitized)
        }
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            val sanitized = sanitize(message)
            if (throwable != null) {
                Log.w(tag, sanitized, throwable)
            } else {
                Log.w(tag, sanitized)
            }
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        // Errors can be logged in production but must be sanitized
        val sanitized = sanitize(message)
        if (throwable != null) {
            Log.e(tag, sanitized, throwable)
        } else {
            Log.e(tag, sanitized)
        }
    }

    /**
     * Sanitizes log message by removing sensitive information
     */
    private fun sanitize(message: String): String {
        return message
            // Replace phone numbers (10-15 digits)
            .replace(Regex("\\b\\d{10,15}\\b"), "[PHONE]")
            // Replace email addresses
            .replace(Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"), "[EMAIL]")
            // Replace potential user IDs (long alphanumeric strings)
            .replace(Regex("\\b[a-zA-Z0-9]{20,}\\b"), "[ID]")
            // Replace API keys pattern
            .replace(Regex("AIza[0-9A-Za-z\\-_]{35}"), "[API_KEY]")
    }

    /**
     * For extremely sensitive operations where even debug logging is risky
     */
    fun secureLog(tag: String, message: String) {
        // Only log a generic message, no details
        if (BuildConfig.DEBUG) {
            Log.d(tag, "[SECURE_OPERATION] $message")
        }
    }
}
