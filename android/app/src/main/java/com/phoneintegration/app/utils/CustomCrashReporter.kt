package com.phoneintegration.app.utils

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.phoneintegration.app.BuildConfig
import com.phoneintegration.app.vps.SyncBackendConfig
import com.phoneintegration.app.vps.VPSAuthManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Custom crash reporter that sends crashes to VPS backend.
 *
 * This is a lightweight crash reporting solution that:
 * - Sends crashes to VPS server at /api/crashes endpoint
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
    private const val CRASHES_ENDPOINT = "/api/crashes"
    private const val MAX_STACK_TRACE_LENGTH = 10000 // Limit to avoid size limits

    private lateinit var context: Context
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var originalHandler: Thread.UncaughtExceptionHandler? = null
    private var isInitialized = false

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

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
            // Log the crash to VPS
            logCrash(throwable, thread.name, isFatal = true)

            // Call original handler (will terminate the app)
            originalHandler?.uncaughtException(thread, throwable)
        }

        isInitialized = true
        Log.i(TAG, "Custom crash reporter initialized (VPS backend)")
    }

    /**
     * Log a non-fatal exception to VPS.
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
     * Internal method to log crashes to VPS.
     */
    private fun logCrash(throwable: Throwable, context: String, isFatal: Boolean) {
        scope.launch {
            try {
                // Get user ID (may be null if not logged in)
                val userId = try {
                    VPSAuthManager.getInstance(this@CustomCrashReporter.context)
                        .getCurrentUserId()
                } catch (e: Exception) {
                    null
                }

                // Generate crash ID
                val crashId = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"

                // Get stack trace
                val stackTrace = getStackTraceString(throwable)

                // Build crash report
                val crashData = buildCrashReport(throwable, context, isFatal, stackTrace, userId, crashId)

                // Send to VPS
                sendToVps(crashData)

                Log.i(TAG, "Crash logged successfully: $crashId (fatal: $isFatal)")

            } catch (e: Exception) {
                Log.e(TAG, "Error in crash reporter", e)
            }
        }
    }

    /**
     * Send crash report to VPS server.
     */
    private fun sendToVps(crashData: Map<String, Any?>) {
        try {
            val baseUrl = SyncBackendConfig.getInstance(context).vpsUrl.value.trim()
                .ifEmpty { SyncBackendConfig.DEFAULT_VPS_URL }
                .let { if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it" }
                .trimEnd('/')
            val jsonBody = gson.toJson(crashData)
            val request = Request.Builder()
                .url("$baseUrl$CRASHES_ENDPOINT")
                .post(jsonBody.toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Failed to send crash to VPS: ${response.code}")
                }
            }
        } catch (e: Exception) {
            // Silently fail - we don't want crash reporting to cause more issues
            Log.w(TAG, "Could not send crash to VPS: ${e.message}")
        }
    }

    /**
     * Build the crash report data structure.
     */
    private fun buildCrashReport(
        throwable: Throwable,
        context: String,
        isFatal: Boolean,
        stackTrace: String,
        userId: String?,
        crashId: String
    ): Map<String, Any?> {
        return mapOf(
            // Crash info
            "id" to crashId,
            "userId" to userId,
            "isFatal" to isFatal,
            "context" to context,
            "timestamp" to System.currentTimeMillis(),
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
