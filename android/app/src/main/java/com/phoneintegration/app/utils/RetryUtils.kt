package com.phoneintegration.app.utils

import android.util.Log
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.pow

/**
 * Utility for retry logic with exponential backoff
 * Ideal for Firebase operations and network requests
 */
object RetryUtils {

    private const val TAG = "RetryUtils"

    /**
     * Default configuration for Firebase operations
     */
    object FirebaseConfig {
        const val MAX_RETRIES = 3
        const val INITIAL_DELAY_MS = 1000L
        const val MAX_DELAY_MS = 30000L
        const val MULTIPLIER = 2.0
    }

    /**
     * Execute a suspend function with exponential backoff retry
     *
     * @param maxRetries Maximum number of retry attempts
     * @param initialDelayMs Initial delay before first retry
     * @param maxDelayMs Maximum delay between retries
     * @param multiplier Multiplier for exponential backoff
     * @param retryIf Predicate to determine if should retry based on exception
     * @param onRetry Callback called before each retry with attempt number
     * @param block The suspend function to execute
     * @return Result of the block or throws the last exception
     */
    suspend fun <T> withRetry(
        maxRetries: Int = FirebaseConfig.MAX_RETRIES,
        initialDelayMs: Long = FirebaseConfig.INITIAL_DELAY_MS,
        maxDelayMs: Long = FirebaseConfig.MAX_DELAY_MS,
        multiplier: Double = FirebaseConfig.MULTIPLIER,
        retryIf: (Throwable) -> Boolean = { it.isRetryable() },
        onRetry: suspend (attempt: Int, exception: Throwable, delayMs: Long) -> Unit = { _, _, _ -> },
        block: suspend () -> T
    ): T {
        var currentDelay = initialDelayMs
        var lastException: Throwable? = null

        repeat(maxRetries + 1) { attempt ->
            try {
                return block()
            } catch (e: Throwable) {
                lastException = e

                if (attempt >= maxRetries || !retryIf(e)) {
                    Log.e(TAG, "Failed after ${attempt + 1} attempts, giving up", e)
                    throw e
                }

                Log.w(TAG, "Attempt ${attempt + 1} failed: ${e.message}, retrying in ${currentDelay}ms")
                onRetry(attempt + 1, e, currentDelay)

                delay(currentDelay)
                currentDelay = min(
                    (currentDelay * multiplier).toLong(),
                    maxDelayMs
                )
            }
        }

        throw lastException ?: IllegalStateException("Retry failed with no exception")
    }

    /**
     * Execute with retry and return Result instead of throwing
     */
    suspend fun <T> withRetryResult(
        maxRetries: Int = FirebaseConfig.MAX_RETRIES,
        initialDelayMs: Long = FirebaseConfig.INITIAL_DELAY_MS,
        maxDelayMs: Long = FirebaseConfig.MAX_DELAY_MS,
        multiplier: Double = FirebaseConfig.MULTIPLIER,
        retryIf: (Throwable) -> Boolean = { it.isRetryable() },
        onRetry: suspend (attempt: Int, exception: Throwable, delayMs: Long) -> Unit = { _, _, _ -> },
        block: suspend () -> T
    ): Result<T> {
        return try {
            Result.success(
                withRetry(
                    maxRetries = maxRetries,
                    initialDelayMs = initialDelayMs,
                    maxDelayMs = maxDelayMs,
                    multiplier = multiplier,
                    retryIf = retryIf,
                    onRetry = onRetry,
                    block = block
                )
            )
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /**
     * Firebase-specific retry configuration
     */
    suspend fun <T> withFirebaseRetry(
        operation: String = "Firebase operation",
        maxRetries: Int = FirebaseConfig.MAX_RETRIES,
        block: suspend () -> T
    ): T {
        return withRetry(
            maxRetries = maxRetries,
            retryIf = { it.isFirebaseRetryable() },
            onRetry = { attempt, exception, delayMs ->
                Log.d(TAG, "$operation: Retry $attempt after ${delayMs}ms - ${exception.message}")
            },
            block = block
        )
    }

    /**
     * Firebase retry returning Result
     */
    suspend fun <T> withFirebaseRetryResult(
        operation: String = "Firebase operation",
        maxRetries: Int = FirebaseConfig.MAX_RETRIES,
        block: suspend () -> T
    ): Result<T> {
        return try {
            Result.success(withFirebaseRetry(operation, maxRetries, block))
        } catch (e: Throwable) {
            Log.e(TAG, "$operation failed after retries", e)
            Result.failure(e)
        }
    }
}

/**
 * Check if an exception is retryable (transient error)
 */
fun Throwable.isRetryable(): Boolean {
    return when (this) {
        // Network errors
        is java.net.UnknownHostException,
        is java.net.SocketTimeoutException,
        is java.net.ConnectException,
        is java.net.SocketException,
        is java.io.InterruptedIOException -> true

        // General IO errors
        is java.io.IOException -> {
            // Retry most IO exceptions except security-related
            message?.contains("permission", ignoreCase = true) != true
        }

        // Rate limiting / server errors
        else -> {
            message?.contains("rate limit", ignoreCase = true) == true ||
            message?.contains("503", ignoreCase = true) == true ||
            message?.contains("temporarily unavailable", ignoreCase = true) == true ||
            message?.contains("network", ignoreCase = true) == true ||
            message?.contains("timeout", ignoreCase = true) == true ||
            message?.contains("unavailable", ignoreCase = true) == true
        }
    }
}

/**
 * Firebase-specific retry check
 */
fun Throwable.isFirebaseRetryable(): Boolean {
    return this.isRetryable() || (this.message?.contains("firebase", ignoreCase = true) == true)
}

/**
 * Network-specific retry check
 */
fun Throwable.isNetworkRetryable(): Boolean {
    // First check general retryable conditions
    if (this.isRetryable()) return true

    // Network-specific checks
    val message = this.message?.lowercase() ?: return false

    return message.contains("network") ||
           message.contains("timeout") ||
           message.contains("unavailable") ||
           message.contains("disconnect") ||
           message.contains("connection") ||
           message.contains("UNAVAILABLE")
}

/**
 * Extension for easy retry on any suspend block
 */
suspend fun <T> retryOnFailure(
    maxRetries: Int = 3,
    initialDelayMs: Long = 1000L,
    block: suspend () -> T
): T = RetryUtils.withRetry(
    maxRetries = maxRetries,
    initialDelayMs = initialDelayMs,
    block = block
)
