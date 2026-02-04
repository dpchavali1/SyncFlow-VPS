package com.phoneintegration.app.utils

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.phoneintegration.app.BuildConfig
import com.phoneintegration.app.auth.UnifiedIdentityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * Custom crash reporter that sends crashes to Firebase Realtime Database.
 *
 * This is a lightweight, free alternative to Firebase Crashlytics that:
 * - Stores crashes in Firebase Realtime Database under /crashes/{userId}/{crashId}
 * - Works without any Gradle plugins
 * - Provides full crash details with stack traces
 * - Supports both caught exceptions and uncaught crashes
 * - Automatically includes device and app metadata
 *
 * Usage:
 * ```
 * // Initialize in Application.onCreate()
 * CustomCrashReporter.init(context)
 *
 * // Log non-fatal exceptions
 * CustomCrashReporter.logException(exception, "Payment failed")
 * ```
 */
object CustomCrashReporter {
    private const val TAG = "CustomCrashReporter"
    private const val CRASHES_PATH = "crashes"
    private const val MAX_STACK_TRACE_LENGTH = 10000 // Limit to avoid Firebase size limits

    private lateinit var context: Context
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var originalHandler: Thread.UncaughtExceptionHandler? = null
    private var isInitialized = false

    /**
     * Initialize the crash reporter.
     * Call this in Application.onCreate() before any other initialization.
     */
    fun init(appContext: Context) {
        if (isInitialized) return

        context = appContext.applicationContext

        // Set up global exception handler for uncaught crashes
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Log the crash to Firebase
            logCrash(throwable, thread.name, isFatal = true)

            // Call original handler (will terminate the app)
            originalHandler?.uncaughtException(thread, throwable)
        }

        isInitialized = true
        Log.i(TAG, "Custom crash reporter initialized")
    }

    /**
     * Log a non-fatal exception to Firebase.
     * Use this for caught exceptions that you want to track.
     *
     * @param exception The exception to log
     * @param context Additional context about where/why the exception occurred
     */
    fun logException(exception: Throwable, context: String = "Unknown") {
        if (!isInitialized) {
            Log.w(TAG, "Crash reporter not initialized, skipping exception log")
            return
        }

        logCrash(exception, context, isFatal = false)
    }

    /**
     * Internal method to log crashes to Firebase.
     */
    private fun logCrash(throwable: Throwable, context: String, isFatal: Boolean) {
        scope.launch {
            try {
                // Get user ID (may be null if not logged in)
                val userId = try {
                    UnifiedIdentityManager.getInstance(this@CustomCrashReporter.context)
                        .getUnifiedUserIdSync()
                } catch (e: Exception) {
                    null
                }

                // Generate crash ID
                val crashId = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"

                // Get stack trace
                val stackTrace = getStackTraceString(throwable)

                // Build crash report
                val crashData = buildCrashReport(throwable, context, isFatal, stackTrace)

                // Save to Firebase
                val database = FirebaseDatabase.getInstance()
                val crashPath = if (userId != null) {
                    "$CRASHES_PATH/$userId/$crashId"
                } else {
                    "$CRASHES_PATH/anonymous/$crashId"
                }

                database.reference.child(crashPath).setValue(crashData)
                    .addOnSuccessListener {
                        Log.i(TAG, "Crash logged successfully: $crashId (fatal: $isFatal)")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed to log crash to Firebase", e)
                    }

            } catch (e: Exception) {
                Log.e(TAG, "Error in crash reporter", e)
            }
        }
    }

    /**
     * Build the crash report data structure.
     */
    private fun buildCrashReport(
        throwable: Throwable,
        context: String,
        isFatal: Boolean,
        stackTrace: String
    ): Map<String, Any?> {
        return mapOf(
            // Crash info
            "isFatal" to isFatal,
            "context" to context,
            "timestamp" to ServerValue.TIMESTAMP,
            "timestampFormatted" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(Date()),

            // Exception details
            "exceptionType" to throwable.javaClass.simpleName,
            "exceptionMessage" to (throwable.message ?: "No message"),
            "stackTrace" to stackTrace.take(MAX_STACK_TRACE_LENGTH),
            "cause" to throwable.cause?.let {
                mapOf(
                    "type" to it.javaClass.simpleName,
                    "message" to (it.message ?: "No message")
                )
            },

            // App info
            "appVersion" to BuildConfig.VERSION_NAME,
            "appVersionCode" to BuildConfig.VERSION_CODE,
            "buildType" to if (BuildConfig.DEBUG) "debug" else "release",

            // Device info
            "deviceManufacturer" to Build.MANUFACTURER,
            "deviceModel" to Build.MODEL,
            "deviceBrand" to Build.BRAND,
            "androidVersion" to Build.VERSION.RELEASE,
            "androidSdkInt" to Build.VERSION.SDK_INT,
            "deviceId" to "${Build.MANUFACTURER}_${Build.MODEL}_${Build.DEVICE}".take(100)
        )
    }

    /**
     * Get stack trace as a string.
     */
    private fun getStackTraceString(throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        return sw.toString()
    }
}
