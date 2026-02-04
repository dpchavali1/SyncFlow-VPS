package com.phoneintegration.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.phoneintegration.app.services.BatteryAwareServiceManager
import com.phoneintegration.app.utils.MemoryOptimizer
import org.junit.Test
import org.junit.Assert.*

/**
 * Simple test to verify performance optimization classes compile and work
 */
class PerformanceOptimizationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun testMemoryOptimizerCreation() {
        val optimizer = MemoryOptimizer.getInstance(context)
        assertNotNull("MemoryOptimizer should be created", optimizer)
    }

    @Test
    fun testBatteryAwareServiceManagerCreation() {
        val manager = BatteryAwareServiceManager.getInstance(context)
        assertNotNull("BatteryAwareServiceManager should be created", manager)
    }

    @Test
    fun testMemoryStats() {
        val optimizer = MemoryOptimizer.getInstance(context)
        val stats = optimizer.getMemoryStats()

        assertTrue("Used memory should be >= 0", stats.usedMemoryMB >= 0)
        assertTrue("Total memory should be > 0", stats.totalMemoryMB > 0)
        assertTrue("Max memory should be > 0", stats.maxMemoryMB > 0)
    }

    @Test
    fun testMemoryPressureDetection() {
        val optimizer = MemoryOptimizer.getInstance(context)
        val pressure = optimizer.checkMemoryPressure()

        // Pressure should be one of the enum values
        assertTrue("Pressure should be valid",
            pressure == com.phoneintegration.app.utils.MemoryPressure.NORMAL ||
            pressure == com.phoneintegration.app.utils.MemoryPressure.HIGH ||
            pressure == com.phoneintegration.app.utils.MemoryPressure.CRITICAL
        )
    }
}