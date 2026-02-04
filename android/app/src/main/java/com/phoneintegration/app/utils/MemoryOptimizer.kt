package com.phoneintegration.app.utils

import android.content.Context
import android.os.Debug
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/**
 * Memory optimization utilities for SyncFlow
 * Provides memory monitoring, leak prevention, and optimization recommendations
 */
class MemoryOptimizer private constructor(private val context: Context) {

    companion object {
        private const val TAG = "MemoryOptimizer"
        private const val MEMORY_WARNING_THRESHOLD = 0.8f // 80% of available memory
        private const val MEMORY_CRITICAL_THRESHOLD = 0.9f // 90% of available memory

        @Volatile
        private var instance: MemoryOptimizer? = null

        fun getInstance(context: Context): MemoryOptimizer {
            return instance ?: synchronized(this) {
                instance ?: MemoryOptimizer(context.applicationContext).also { instance = it }
            }
        }
    }

    private var memoryWarningShown = false
    private var memoryCriticalShown = false

    // Weak references to prevent memory leaks
    private val weakReferences = mutableListOf<WeakReference<Any>>()

    init {
        setupLifecycleObserver()
        Log.i(TAG, "MemoryOptimizer initialized")
    }

    /**
     * Setup lifecycle observer to monitor app state
     */
    private fun setupLifecycleObserver() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // App came to foreground - clear some caches if memory is low
                checkMemoryPressure()
            }

            override fun onStop(owner: LifecycleOwner) {
                // App went to background - cleanup resources
                cleanupWeakReferences()
            }
        })
    }

    /**
     * Check current memory usage and take action if needed
     */
    fun checkMemoryPressure(): MemoryPressure {
        val runtime = Runtime.getRuntime()
        val usedMemInMB = (runtime.totalMemory() - runtime.freeMemory()) / 1048576L
        val maxMemInMB = runtime.maxMemory() / 1048576L
        val usedPercentage = usedMemInMB.toFloat() / maxMemInMB.toFloat()

        val pressure = when {
            usedPercentage >= MEMORY_CRITICAL_THRESHOLD -> {
                if (!memoryCriticalShown) {
                    Log.w(TAG, "CRITICAL memory usage: ${usedMemInMB}MB / ${maxMemInMB}MB (${(usedPercentage * 100).roundToInt()}%)")
                    memoryCriticalShown = true
                    memoryWarningShown = true
                }
                MemoryPressure.CRITICAL
            }
            usedPercentage >= MEMORY_WARNING_THRESHOLD -> {
                if (!memoryWarningShown) {
                    Log.w(TAG, "HIGH memory usage: ${usedMemInMB}MB / ${maxMemInMB}MB (${(usedPercentage * 100).roundToInt()}%)")
                    memoryWarningShown = true
                }
                MemoryPressure.HIGH
            }
            else -> {
                if (memoryWarningShown) {
                    Log.i(TAG, "Memory usage normalized: ${usedMemInMB}MB / ${maxMemInMB}MB (${(usedPercentage * 100).roundToInt()}%)")
                    memoryWarningShown = false
                    memoryCriticalShown = false
                }
                MemoryPressure.NORMAL
            }
        }

        // Take action based on memory pressure
        when (pressure) {
            MemoryPressure.CRITICAL -> performCriticalCleanup()
            MemoryPressure.HIGH -> performHighCleanup()
            MemoryPressure.NORMAL -> {} // No action needed
        }

        return pressure
    }

    /**
     * Perform critical memory cleanup
     */
    private fun performCriticalCleanup() {
        Log.w(TAG, "Performing critical memory cleanup")

        // Force garbage collection
        System.gc()
        System.runFinalization()
        System.gc()

        // Clear all weak references
        cleanupWeakReferences()

        // Additional cleanup could be added here
        // - Clear image caches
        // - Clear database caches
        // - Reduce loaded data
    }

    /**
     * Perform high memory cleanup
     */
    private fun performHighCleanup() {
        Log.w(TAG, "Performing high memory cleanup")

        // Clear weak references
        cleanupWeakReferences()

        // Request garbage collection
        System.gc()
    }

    /**
     * Add a weak reference to track potential memory leaks
     */
    fun addWeakReference(obj: Any) {
        synchronized(weakReferences) {
            weakReferences.add(WeakReference(obj))
        }
    }

    /**
     * Clean up weak references that have been garbage collected
     */
    private fun cleanupWeakReferences() {
        synchronized(weakReferences) {
            val iterator = weakReferences.iterator()
            var cleaned = 0
            while (iterator.hasNext()) {
                val ref = iterator.next()
                if (ref.get() == null) {
                    iterator.remove()
                    cleaned++
                }
            }
            if (cleaned > 0) {
                Log.d(TAG, "Cleaned up $cleaned weak references")
            }
        }
    }

    /**
     * Get memory optimization recommendations
     */
    fun getOptimizationRecommendations(): List<String> {
        val recommendations = mutableListOf<String>()
        val pressure = checkMemoryPressure()

        when (pressure) {
            MemoryPressure.CRITICAL -> {
                recommendations.addAll(listOf(
                    "Close other apps to free up memory",
                    "Restart the app to clear memory",
                    "Avoid loading large message histories",
                    "Disable photo sync temporarily"
                ))
            }
            MemoryPressure.HIGH -> {
                recommendations.addAll(listOf(
                    "Consider closing unused conversations",
                    "Disable background services temporarily",
                    "Clear app cache if available"
                ))
            }
            MemoryPressure.NORMAL -> {
                recommendations.add("Memory usage is normal")
            }
        }

        // General recommendations
        recommendations.addAll(listOf(
            "Use pagination for large message lists",
            "Enable conversation list hiding to reduce memory",
            "Regularly clear old message caches"
        ))

        return recommendations
    }

    /**
     * Get current memory statistics
     */
    fun getMemoryStats(): MemoryStats {
        val runtime = Runtime.getRuntime()
        return MemoryStats(
            usedMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / 1048576L,
            totalMemoryMB = runtime.totalMemory() / 1048576L,
            maxMemoryMB = runtime.maxMemory() / 1048576L,
            pressure = checkMemoryPressure()
        )
    }

    /**
     * Memory leak detection - log if too many weak references are still alive
     */
    fun checkForMemoryLeaks() {
        synchronized(weakReferences) {
            val aliveRefs = weakReferences.count { it.get() != null }
            if (aliveRefs > 100) { // Arbitrary threshold
                Log.w(TAG, "Potential memory leak: $aliveRefs objects still referenced")
            }
        }
    }
}

/**
 * Memory pressure levels
 */
enum class MemoryPressure {
    NORMAL,
    HIGH,
    CRITICAL
}

/**
 * Memory statistics data class
 */
data class MemoryStats(
    val usedMemoryMB: Long,
    val totalMemoryMB: Long,
    val maxMemoryMB: Long,
    val pressure: MemoryPressure
) {
    val usedPercentage: Float
        get() = if (maxMemoryMB > 0) usedMemoryMB.toFloat() / maxMemoryMB.toFloat() else 0f
}