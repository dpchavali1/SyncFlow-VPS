package com.phoneintegration.app.utils

import kotlinx.coroutines.withTimeout
import android.util.Log

/**
 * Firebase utilities for timeout and error handling
 *
 * Prevents ANR (Application Not Responding) errors by adding timeouts
 * to all Firebase operations.
 */
object FirebaseUtils {
    private const val TAG = "FirebaseUtils"

    // Default timeout for Firebase operations (5 seconds)
    private const val DEFAULT_TIMEOUT_MS = 5000L

    // Timeout for Cloud Functions (10 seconds - they may take longer)
    private const val CLOUD_FUNCTION_TIMEOUT_MS = 10000L

    /**
     * Execute a Firebase operation with timeout
     *
     * @param timeoutMillis Timeout in milliseconds (default: 5 seconds)
     * @param operationName Name of the operation for logging
     * @param block The Firebase operation to execute
     * @return Result of the operation
     * @throws kotlinx.coroutines.TimeoutCancellationException if timeout is reached
     *
     * @example
     * ```kotlin
     * val data = firebaseWithTimeout(operationName = "getUserData") {
     *     database.reference.child("users").child(userId).get().await()
     * }
     * ```
     */
    suspend fun <T> firebaseWithTimeout(
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
        operationName: String = "Firebase operation",
        block: suspend () -> T
    ): T {
        return try {
            withTimeout(timeoutMillis) {
                Log.d(TAG, "Starting: $operationName")
                val result = block()
                Log.d(TAG, "Completed: $operationName")
                result
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.e(TAG, "Timeout: $operationName exceeded ${timeoutMillis}ms")
            throw e
        }
    }

    /**
     * Execute a Cloud Function call with timeout
     *
     * Cloud Functions may take longer than database operations,
     * so this uses a 10-second timeout by default.
     *
     * @param functionName Name of the Cloud Function
     * @param block The Cloud Function call to execute
     * @return Result of the function call
     */
    suspend fun <T> cloudFunctionWithTimeout(
        functionName: String,
        timeoutMillis: Long = CLOUD_FUNCTION_TIMEOUT_MS,
        block: suspend () -> T
    ): T {
        return firebaseWithTimeout(
            timeoutMillis = timeoutMillis,
            operationName = "Cloud Function: $functionName",
            block = block
        )
    }

    /**
     * Execute a Firebase operation with timeout and fallback
     *
     * If the operation times out, returns the fallback value instead of throwing.
     *
     * @param timeoutMillis Timeout in milliseconds
     * @param operationName Name of the operation for logging
     * @param fallback Value to return if timeout occurs
     * @param block The Firebase operation to execute
     * @return Result of the operation or fallback if timeout
     */
    suspend fun <T> firebaseWithTimeoutOrFallback(
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
        operationName: String = "Firebase operation",
        fallback: T,
        block: suspend () -> T
    ): T {
        return try {
            firebaseWithTimeout(timeoutMillis, operationName, block)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w(TAG, "Using fallback for: $operationName")
            fallback
        }
    }
}
