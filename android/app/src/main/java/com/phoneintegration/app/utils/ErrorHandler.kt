package com.phoneintegration.app.utils

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.phoneintegration.app.BuildConfig
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Global error handling utility for SyncFlow
 * Provides centralized error management, logging, and user-friendly error messages
 */
object ErrorHandler {

    private const val TAG = "SyncFlowError"

    // Flow for emitting errors to UI
    private val _errorFlow = MutableSharedFlow<AppError>(replay = 0)
    val errorFlow = _errorFlow.asSharedFlow()

    // Original exception handler (for chaining)
    private var originalHandler: Thread.UncaughtExceptionHandler? = null

    /**
     * Initialize global error handler
     * Should be called in Application.onCreate()
     *
     * Note: Errors are automatically sent to CustomCrashReporter (Firebase Realtime Database)
     * when ENABLE_CRASH_REPORTING is true in release builds.
     */
    fun init(context: Context) {
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)

            // Log crash details
            logError(throwable, "CRASH", mapOf("thread" to thread.name))

            // Let the original handler deal with the crash
            originalHandler?.uncaughtException(thread, throwable)
        }

        Log.d(TAG, "Global error handler initialized")
    }

    /**
     * Create a CoroutineExceptionHandler for ViewModels and background work
     */
    fun createCoroutineHandler(
        scope: CoroutineScope,
        context: String = "Background"
    ): CoroutineExceptionHandler {
        return CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "Coroutine exception in $context", throwable)
            logError(throwable, context)

            // Emit error to UI
            scope.launch {
                _errorFlow.emit(throwable.toAppError(context))
            }
        }
    }

    /**
     * Handle an error with logging and optional user notification
     */
    fun handle(
        error: Throwable,
        context: String = "Unknown",
        showToast: Boolean = false,
        appContext: Context? = null
    ) {
        Log.e(TAG, "Error in $context: ${error.message}", error)
        logError(error, context)

        if (showToast && appContext != null) {
            val message = error.toUserFriendlyMessage()
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Handle error and emit to UI flow
     */
    suspend fun handleAndEmit(
        error: Throwable,
        context: String = "Unknown"
    ) {
        Log.e(TAG, "Error in $context: ${error.message}", error)
        logError(error, context)
        _errorFlow.emit(error.toAppError(context))
    }

    /**
     * Log error with additional context
     */
    private fun logError(
        error: Throwable,
        context: String,
        extras: Map<String, Any?> = emptyMap()
    ) {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))

        val logMessage = buildString {
            appendLine("=== SyncFlow Error Report ===")
            appendLine("Context: $context")
            appendLine("Error Type: ${error.javaClass.simpleName}")
            appendLine("Message: ${error.message}")
            extras.forEach { (key, value) ->
                appendLine("$key: $value")
            }
            appendLine("Stack Trace:")
            appendLine(sw.toString().take(2000)) // Limit stack trace length
        }

        Log.e(TAG, logMessage)

        // Send to custom crash reporter (free, no Gradle plugin needed)
        if (BuildConfig.ENABLE_CRASH_REPORTING) {
            try {
                val contextWithExtras = if (extras.isNotEmpty()) {
                    "$context - ${extras.entries.joinToString { "${it.key}=${it.value}" }}"
                } else {
                    context
                }
                CustomCrashReporter.logException(error, contextWithExtras)
                Log.d(TAG, "Error sent to custom crash reporter: $context")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send error to crash reporter", e)
            }
        }
    }

    /**
     * Execute a block safely with error handling
     */
    inline fun <T> runSafely(
        context: String = "Unknown",
        default: T,
        block: () -> T
    ): T {
        return try {
            block()
        } catch (e: Exception) {
            handle(e, context)
            default
        }
    }

    /**
     * Execute a suspend block safely with error handling
     */
    suspend inline fun <T> runSafelySuspend(
        context: String = "Unknown",
        default: T,
        block: suspend () -> T
    ): T {
        return try {
            block()
        } catch (e: Exception) {
            handle(e, context)
            default
        }
    }
}

/**
 * Represents an app-level error with user-friendly messaging
 */
data class AppError(
    val message: String,
    val userMessage: String,
    val context: String,
    val isRecoverable: Boolean = true,
    val cause: Throwable? = null
)

/**
 * Extension to convert Throwable to AppError
 */
fun Throwable.toAppError(context: String = "Unknown"): AppError {
    return AppError(
        message = this.message ?: "Unknown error",
        userMessage = this.toUserFriendlyMessage(),
        context = context,
        isRecoverable = this.isRecoverable(),
        cause = this
    )
}

/**
 * Convert exception to user-friendly message
 */
fun Throwable.toUserFriendlyMessage(): String {
    return when (this) {
        is java.net.UnknownHostException -> "No internet connection. Please check your network."
        is java.net.SocketTimeoutException -> "Connection timed out. Please try again."
        is java.net.ConnectException -> "Unable to connect. Please check your network."
        is java.io.IOException -> "A network error occurred. Please try again."
        is SecurityException -> "Permission denied. Please check app permissions."
        is IllegalStateException -> "Something went wrong. Please restart the app."
        is IllegalArgumentException -> "Invalid input. Please check your data."
        is NoNetworkException -> "No internet connection available."
        is OutOfMemoryError -> "The app ran out of memory. Please restart."
        else -> message?.take(100) ?: "An unexpected error occurred."
    }
}

/**
 * Check if error is recoverable
 */
fun Throwable.isRecoverable(): Boolean {
    return when (this) {
        is java.net.UnknownHostException,
        is java.net.SocketTimeoutException,
        is java.net.ConnectException,
        is java.io.IOException,
        is NoNetworkException -> true // Network errors are usually recoverable
        is SecurityException,
        is OutOfMemoryError -> false // These need user action
        else -> true // Most errors are recoverable with retry
    }
}

/**
 * Extension for Result handling with error logging
 */
inline fun <T> Result<T>.onFailureLogged(
    context: String = "Unknown",
    action: (Throwable) -> Unit = {}
): Result<T> {
    return this.onFailure { error ->
        ErrorHandler.handle(error, context)
        action(error)
    }
}
