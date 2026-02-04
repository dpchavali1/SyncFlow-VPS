package com.phoneintegration.app.security

import android.content.Context
import android.util.Log
import com.phoneintegration.app.auth.AuthManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Security monitoring and alerting system for SyncFlow.
 * Monitors authentication events, suspicious activities, and potential threats.
 */
class SecurityMonitor private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SecurityMonitor"
        private const val MAX_EVENTS_PER_MINUTE = 10
        private const val MAX_FAILED_AUTH_ATTEMPTS = 5
        private const val ALERT_COOLDOWN_MINUTES = 15

        @Volatile
        private var instance: SecurityMonitor? = null

        fun getInstance(context: Context): SecurityMonitor {
            return instance ?: synchronized(this) {
                instance ?: SecurityMonitor(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Security event flow for real-time monitoring
    private val _securityEvents = MutableSharedFlow<SecurityEvent>()
    val securityEvents: SharedFlow<SecurityEvent> = _securityEvents.asSharedFlow()

    // Security metrics tracking
    private val eventCounts = ConcurrentHashMap<SecurityEventType, AtomicInteger>()
    private val lastAlertTimes = ConcurrentHashMap<AlertType, Long>()
    private val failedAuthAttempts = ConcurrentHashMap<String, AtomicInteger>()

    // Alert handlers
    private val alertHandlers = mutableListOf<(SecurityAlert) -> Unit>()

    init {
        startEventProcessing()
    }

    /**
     * Log a security event
     */
    fun logEvent(event: SecurityEvent) {
        scope.launch {
            try {
                // Rate limiting
                if (isRateLimited(event.type)) {
                    Log.w(TAG, "Rate limited: ${event.type}")
                    return@launch
                }

                // Track event counts
                eventCounts.getOrPut(event.type) { AtomicInteger(0) }.incrementAndGet()

                // Special handling for authentication events
                handleAuthenticationEvent(event)

                // Emit event
                _securityEvents.emit(event)

                // Check for alerts
                checkForAlerts(event)

                // Log to system
                Log.i(TAG, "Security event: ${event.type} - ${event.message}")

            } catch (e: Exception) {
                Log.e(TAG, "Error processing security event", e)
            }
        }
    }

    /**
     * Handle authentication-specific events
     */
    private fun handleAuthenticationEvent(event: SecurityEvent) {
        when (event.type) {
            SecurityEventType.AUTH_FAILED -> {
                val identifier = event.metadata["identifier"] as? String ?: "unknown"
                val attempts = failedAuthAttempts.getOrPut(identifier) { AtomicInteger(0) }
                val newCount = attempts.incrementAndGet()

                if (newCount >= MAX_FAILED_AUTH_ATTEMPTS) {
                    // Trigger brute force alert
                    triggerAlert(SecurityAlert(
                        type = AlertType.BRUTE_FORCE_ATTEMPT,
                        severity = AlertSeverity.HIGH,
                        message = "Multiple failed authentication attempts for: $identifier",
                        metadata = mapOf(
                            "identifier" to identifier,
                            "attempts" to newCount.toString(),
                            "ip" to (event.metadata["ip"] ?: "unknown")
                        )
                    ))
                }
            }
            SecurityEventType.AUTH_SUCCESS -> {
                // Reset failed attempts on successful auth
                val identifier = event.metadata["identifier"] as? String
                if (identifier != null) {
                    failedAuthAttempts.remove(identifier)
                }
            }
            else -> {} // No special handling needed
        }
    }

    /**
     * Check if event should trigger an alert
     */
    private fun checkForAlerts(event: SecurityEvent) {
        when (event.type) {
            SecurityEventType.AUTH_FAILED -> {
                // Already handled in handleAuthenticationEvent
            }
            SecurityEventType.CERTIFICATE_PINNING_BYPASSED -> {
                triggerAlert(SecurityAlert(
                    type = AlertType.CERTIFICATE_PINNING_VIOLATION,
                    severity = AlertSeverity.CRITICAL,
                    message = "Certificate pinning bypassed - potential MITM attack",
                    metadata = event.metadata
                ))
            }
            SecurityEventType.UNAUTHORIZED_ACCESS -> {
                triggerAlert(SecurityAlert(
                    type = AlertType.UNAUTHORIZED_ACCESS_ATTEMPT,
                    severity = AlertSeverity.HIGH,
                    message = "Unauthorized access attempt detected",
                    metadata = event.metadata
                ))
            }
            SecurityEventType.DATA_TAMPERING -> {
                triggerAlert(SecurityAlert(
                    type = AlertType.DATA_INTEGRITY_VIOLATION,
                    severity = AlertSeverity.HIGH,
                    message = "Data tampering detected",
                    metadata = event.metadata
                ))
            }
            SecurityEventType.SESSION_TIMEOUT -> {
                triggerAlert(SecurityAlert(
                    type = AlertType.SESSION_SECURITY_ISSUE,
                    severity = AlertSeverity.MEDIUM,
                    message = "Session timeout due to inactivity",
                    metadata = event.metadata
                ))
            }
            SecurityEventType.INPUT_VALIDATION_FAILED -> {
                val recentCount = getEventCountInLastMinute(SecurityEventType.INPUT_VALIDATION_FAILED)
                if (recentCount > 3) {
                    triggerAlert(SecurityAlert(
                        type = AlertType.SUSPICIOUS_INPUT_PATTERN,
                        severity = AlertSeverity.MEDIUM,
                        message = "Multiple input validation failures detected",
                        metadata = mapOf("count" to recentCount.toString()) + event.metadata
                    ))
                }
            }
            else -> {} // No alert needed
        }
    }

    /**
     * Trigger a security alert
     */
    private fun triggerAlert(alert: SecurityAlert) {
        // Check cooldown
        val lastAlert = lastAlertTimes[alert.type]
        val now = System.currentTimeMillis()
        if (lastAlert != null &&
            (now - lastAlert) < (ALERT_COOLDOWN_MINUTES * 60 * 1000)) {
            Log.d(TAG, "Alert throttled: ${alert.type}")
            return
        }

        lastAlertTimes[alert.type] = now

        // Notify all handlers
        alertHandlers.forEach { handler ->
            try {
                handler(alert)
            } catch (e: Exception) {
                Log.e(TAG, "Error in alert handler", e)
            }
        }

        // Log critical alerts
        when (alert.severity) {
            AlertSeverity.CRITICAL -> Log.e(TAG, "CRITICAL ALERT: ${alert.message}")
            AlertSeverity.HIGH -> Log.w(TAG, "HIGH ALERT: ${alert.message}")
            else -> Log.i(TAG, "ALERT: ${alert.message}")
        }
    }

    /**
     * Add an alert handler
     */
    fun addAlertHandler(handler: (SecurityAlert) -> Unit) {
        alertHandlers.add(handler)
    }

    /**
     * Remove an alert handler
     */
    fun removeAlertHandler(handler: (SecurityAlert) -> Unit) {
        alertHandlers.remove(handler)
    }

    /**
     * Check if event is rate limited
     */
    private fun isRateLimited(eventType: SecurityEventType): Boolean {
        val count = getEventCountInLastMinute(eventType)
        return count >= MAX_EVENTS_PER_MINUTE
    }

    /**
     * Get event count in the last minute for a specific type
     */
    private fun getEventCountInLastMinute(eventType: SecurityEventType): Int {
        // This is a simplified implementation
        // In a real system, you'd track timestamps for each event
        return eventCounts[eventType]?.get() ?: 0
    }

    /**
     * Start background event processing
     */
    private fun startEventProcessing() {
        scope.launch {
            securityEvents.collect { event ->
                // Process events (could send to remote monitoring, etc.)
                Log.d(TAG, "Processed security event: ${event.type}")
            }
        }
    }

    /**
     * Get security metrics
     */
    fun getSecurityMetrics(): SecurityMetrics {
        return SecurityMetrics(
            totalEvents = eventCounts.values.sumOf { it.get() },
            eventsByType = eventCounts.mapValues { it.value.get() },
            activeFailedAuthAttempts = failedAuthAttempts.size,
            lastAlertTimes = lastAlertTimes.toMap()
        )
    }

    /**
     * Reset security metrics (for testing)
     */
    fun resetMetrics() {
        eventCounts.clear()
        failedAuthAttempts.clear()
        lastAlertTimes.clear()
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        scope.cancel()
        alertHandlers.clear()
    }
}

/**
 * Security event types
 */
enum class SecurityEventType {
    AUTH_SUCCESS,
    AUTH_FAILED,
    SESSION_STARTED,
    SESSION_TIMEOUT,
    SESSION_FORCED_LOGOUT,
    CERTIFICATE_PINNING_BYPASSED,
    UNAUTHORIZED_ACCESS,
    DATA_TAMPERING,
    INPUT_VALIDATION_FAILED,
    SUSPICIOUS_ACTIVITY,
    NETWORK_ANOMALY
}

/**
 * Security event data class
 */
data class SecurityEvent(
    val type: SecurityEventType,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: Map<String, Any> = emptyMap()
)

/**
 * Alert types
 */
enum class AlertType {
    BRUTE_FORCE_ATTEMPT,
    CERTIFICATE_PINNING_VIOLATION,
    UNAUTHORIZED_ACCESS_ATTEMPT,
    DATA_INTEGRITY_VIOLATION,
    SESSION_SECURITY_ISSUE,
    SUSPICIOUS_INPUT_PATTERN,
    NETWORK_SECURITY_THREAT
}

/**
 * Alert severity levels
 */
enum class AlertSeverity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

/**
 * Security alert data class
 */
data class SecurityAlert(
    val type: AlertType,
    val severity: AlertSeverity,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: Map<String, Any> = emptyMap()
)

/**
 * Security metrics data class
 */
data class SecurityMetrics(
    val totalEvents: Int,
    val eventsByType: Map<SecurityEventType, Int>,
    val activeFailedAuthAttempts: Int,
    val lastAlertTimes: Map<AlertType, Long>
)