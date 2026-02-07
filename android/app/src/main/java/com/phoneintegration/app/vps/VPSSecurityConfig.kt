/**
 * VPS Security Configuration
 *
 * Handles security configuration for VPS backend connections.
 * Replaces FirebaseSecurityConfig for VPS-only deployments.
 */

package com.phoneintegration.app.vps

import android.content.Context
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Security configuration for VPS backend.
 * Provides certificate pinning and secure HTTP client configuration.
 */
object VPSSecurityConfig {

    private const val TAG = "VPSSecurityConfig"

    // VPS server hostname - update this when deploying to production with HTTPS
    // Connection timeouts
    private const val CONNECT_TIMEOUT_SECONDS = 30L
    private const val READ_TIMEOUT_SECONDS = 30L
    private const val WRITE_TIMEOUT_SECONDS = 30L

    /**
     * Initialize VPS security configuration.
     * This should be called in Application.onCreate() before any VPS usage.
     */
    fun initialize(context: Context) {
        try {
            val host = getVpsHost(context)
            android.util.Log.i(TAG, "VPS security config initialized")
            android.util.Log.i(TAG, "VPS server: $host")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to initialize VPS security config", e)
        }
    }

    /**
     * Create a secure OkHttp client for VPS connections.
     * When the VPS server supports HTTPS, certificate pinning can be added here.
     */
    fun createSecureOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // TODO: Add certificate pinning when VPS uses HTTPS
            // .certificatePinner(createCertificatePinner())
            .build()
    }

    /**
     * Create certificate pinner for VPS server (when HTTPS is enabled).
     * This should be updated with actual certificate pins in production.
     */
    private fun createCertificatePinner(): CertificatePinner {
        return CertificatePinner.Builder()
            // TODO: Add VPS server certificate pins when HTTPS is enabled
            // .add(VPS_HOST, "sha256/XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX=")
            .build()
    }

    /**
     * Validate VPS connectivity.
     */
    fun validateVpsConnectivity(): Boolean {
        return try {
            val client = createSecureOkHttpClient()
            val host = getVpsHost(null)
            val request = okhttp3.Request.Builder()
                .url("http://$host/health")
                .build()

            client.newCall(request).execute().use { response ->
                val isSuccess = response.isSuccessful
                android.util.Log.i(TAG, "VPS connectivity check: ${if (isSuccess) "OK" else "FAILED"}")
                isSuccess
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "VPS connectivity check failed", e)
            false
        }
    }

    private fun getVpsHost(context: Context?): String {
        val raw = context?.let { SyncBackendConfig.getInstance(it).vpsUrl.value } ?: SyncBackendConfig.DEFAULT_VPS_URL
        val trimmed = raw.trim().ifEmpty { SyncBackendConfig.DEFAULT_VPS_URL }
        val withoutScheme = trimmed.removePrefix("https://").removePrefix("http://")
        return withoutScheme.trimEnd('/')
    }

    /**
     * Get security recommendations for VPS deployment.
     */
    fun getSecurityRecommendations(): List<String> {
        return listOf(
            "Enable HTTPS on VPS server for encrypted connections",
            "Implement certificate pinning once HTTPS is enabled",
            "Use strong JWT secrets for token generation",
            "Implement rate limiting on all API endpoints",
            "Enable PostgreSQL SSL connections",
            "Regularly rotate authentication secrets",
            "Monitor server logs for suspicious activity"
        )
    }
}
