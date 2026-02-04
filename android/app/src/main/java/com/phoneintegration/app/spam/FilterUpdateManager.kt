package com.phoneintegration.app.spam

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Manages downloading and updating spam filter components
 */
class FilterUpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "FilterUpdateManager"
        private const val PREFS_NAME = "spam_filter_prefs"
        private const val KEY_LAST_UPDATE = "last_update_time"
        private const val KEY_MANIFEST_VERSION = "manifest_version"

        // Filter manifest URL - host this on your Firebase Storage or GitHub
        private const val MANIFEST_URL = "https://raw.githubusercontent.com/user/syncflow-filters/main/manifest.json"

        // Alternative: Firebase Storage URLs
        // private const val MANIFEST_URL = "https://firebasestorage.googleapis.com/v0/b/your-project/o/filters%2Fmanifest.json?alt=media"

        // URLhaus direct feed (free, no API key needed)
        private const val URLHAUS_RECENT_URL = "https://urlhaus.abuse.ch/downloads/json_recent/"

        // PhishTank feed (requires free API key)
        private const val PHISHTANK_URL = "http://data.phishtank.com/data/online-valid.json"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Check if updates are available
     */
    suspend fun checkForUpdates(): FilterUpdateInfo = withContext(Dispatchers.IO) {
        try {
            val manifest = fetchManifest()
            if (manifest == null) {
                return@withContext FilterUpdateInfo(false, emptyList(), 0)
            }

            val updates = mutableListOf<UpdateableComponent>()
            var totalSize = 0L

            for ((name, component) in manifest.components) {
                val currentVersion = getLocalVersion(name)
                if (currentVersion == null || isNewerVersion(component.version, currentVersion)) {
                    updates.add(UpdateableComponent(
                        name = name,
                        currentVersion = currentVersion ?: "none",
                        newVersion = component.version,
                        size = component.size
                    ))
                    totalSize += component.size
                }
            }

            FilterUpdateInfo(
                hasUpdates = updates.isNotEmpty(),
                components = updates,
                totalSize = totalSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for updates", e)
            FilterUpdateInfo(false, emptyList(), 0)
        }
    }

    /**
     * Update all filters
     */
    suspend fun updateAllFilters(
        onProgress: ((String, Int) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            var success = true

            // 1. Update URL blocklist from URLhaus
            onProgress?.invoke("URL Blocklist", 0)
            if (!updateUrlBlocklist()) {
                Log.w(TAG, "Failed to update URL blocklist")
                success = false
            }
            onProgress?.invoke("URL Blocklist", 100)

            // 2. Update spam patterns
            onProgress?.invoke("Spam Patterns", 0)
            if (!updateSpamPatterns()) {
                Log.w(TAG, "Failed to update spam patterns")
                // Don't fail completely - patterns have good defaults
            }
            onProgress?.invoke("Spam Patterns", 100)

            // 3. Update domain blocklist
            onProgress?.invoke("Domain Blocklist", 0)
            if (!updateDomainBlocklist()) {
                Log.w(TAG, "Failed to update domain blocklist")
            }
            onProgress?.invoke("Domain Blocklist", 100)

            // 4. Update ML model (optional, larger download)
            onProgress?.invoke("ML Model", 0)
            updateMlModel()
            onProgress?.invoke("ML Model", 100)

            // Save update time
            prefs.edit().putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()

            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update filters", e)
            false
        }
    }

    /**
     * Update URL blocklist from URLhaus
     */
    private suspend fun updateUrlBlocklist(): Boolean = withContext(Dispatchers.IO) {
        try {
            val connection = URL(URLHAUS_RECENT_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.setRequestProperty("Accept", "application/json")

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                val processedList = processUrlhausResponse(response)

                // Save to file
                val file = File(context.filesDir, "url_blocklist.json")
                file.writeText(processedList)

                // Save version
                saveLocalVersion("url_blocklist", getCurrentDateVersion())
                true
            } else {
                Log.e(TAG, "URLhaus returned ${connection.responseCode}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch URLhaus blocklist", e)
            false
        }
    }

    /**
     * Process URLhaus JSON response
     */
    private fun processUrlhausResponse(response: String): String {
        try {
            val json = JSONObject(response)
            val urls = json.optJSONArray("urls") ?: return "[]"

            val processed = JSONArray()
            for (i in 0 until minOf(urls.length(), 50000)) { // Limit to 50k URLs
                val urlObj = urls.getJSONObject(i)
                val entry = JSONObject().apply {
                    put("url", urlObj.optString("url", ""))
                    put("threat", urlObj.optString("threat", "malware"))
                    put("date", urlObj.optString("date_added", ""))
                }
                processed.put(entry)
            }

            return processed.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process URLhaus response", e)
            return "[]"
        }
    }

    /**
     * Update spam patterns from server or use enhanced defaults
     */
    private suspend fun updateSpamPatterns(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Try to fetch from server first
            val manifest = fetchManifest()
            val patternComponent = manifest?.components?.get("spam_patterns")

            if (patternComponent != null) {
                val response = fetchUrl(patternComponent.url)
                if (response != null) {
                    // Verify checksum
                    if (verifyChecksum(response, patternComponent.checksum)) {
                        File(context.filesDir, "spam_patterns.json").writeText(response)
                        saveLocalVersion("spam_patterns", patternComponent.version)
                        return@withContext true
                    }
                }
            }

            // Fall back to bundled patterns (already loaded by SpamPatternMatcher)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Update domain blocklist
     */
    private suspend fun updateDomainBlocklist(): Boolean = withContext(Dispatchers.IO) {
        try {
            val manifest = fetchManifest()
            val component = manifest?.components?.get("domain_blocklist")

            if (component != null) {
                val response = fetchUrl(component.url)
                if (response != null && verifyChecksum(response, component.checksum)) {
                    File(context.filesDir, "domain_blocklist.txt").writeText(response)
                    saveLocalVersion("domain_blocklist", component.version)
                    return@withContext true
                }
            }

            // Create default if not exists
            val file = File(context.filesDir, "domain_blocklist.txt")
            if (!file.exists()) {
                file.writeText(getDefaultDomainBlocklist())
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update domain blocklist", e)
            false
        }
    }

    /**
     * Update ML model file
     */
    private suspend fun updateMlModel(): Boolean = withContext(Dispatchers.IO) {
        try {
            val manifest = fetchManifest()
            val component = manifest?.components?.get("ml_model")

            if (component != null) {
                val currentVersion = getLocalVersion("ml_model")
                if (currentVersion != null && !isNewerVersion(component.version, currentVersion)) {
                    return@withContext true
                }

                // Download model file
                val modelBytes = fetchBinaryUrl(component.url)
                if (modelBytes != null) {
                    val modelFile = File(context.filesDir, "spam_model.tflite")
                    FileOutputStream(modelFile).use { it.write(modelBytes) }
                    saveLocalVersion("ml_model", component.version)
                    return@withContext true
                }
            }

            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update ML model", e)
            false
        }
    }

    /**
     * Fetch manifest from server
     */
    private suspend fun fetchManifest(): FilterManifest? = withContext(Dispatchers.IO) {
        try {
            val response = fetchUrl(MANIFEST_URL) ?: return@withContext null
            parseManifest(response)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch manifest", e)
            null
        }
    }

    private fun parseManifest(json: String): FilterManifest {
        val obj = JSONObject(json)
        val componentsObj = obj.getJSONObject("components")
        val components = mutableMapOf<String, FilterComponent>()

        for (key in componentsObj.keys()) {
            val compObj = componentsObj.getJSONObject(key)
            components[key] = FilterComponent(
                version = compObj.getString("version"),
                url = compObj.getString("url"),
                size = compObj.optLong("size", 0),
                checksum = compObj.optString("checksum", ""),
                minAppVersion = compObj.optString("minAppVersion", null)
            )
        }

        return FilterManifest(
            version = obj.getString("version"),
            lastUpdated = obj.optLong("lastUpdated", 0),
            components = components
        )
    }

    // Network utilities
    private fun fetchUrl(urlString: String): String? {
        return try {
            val connection = URL(urlString).openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            if (connection.responseCode == 200) {
                connection.inputStream.bufferedReader().readText()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch URL: $urlString", e)
            null
        }
    }

    private fun fetchBinaryUrl(urlString: String): ByteArray? {
        return try {
            val connection = URL(urlString).openConnection() as HttpURLConnection
            connection.connectTimeout = 60000
            connection.readTimeout = 60000

            if (connection.responseCode == 200) {
                connection.inputStream.readBytes()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch binary URL: $urlString", e)
            null
        }
    }

    // Version management
    private fun getLocalVersion(component: String): String? {
        return prefs.getString("version_$component", null)
    }

    private fun saveLocalVersion(component: String, version: String) {
        prefs.edit().putString("version_$component", version).apply()
    }

    private fun isNewerVersion(newVersion: String, currentVersion: String): Boolean {
        // Simple string comparison - works for date-based versions (2024.01.27)
        // and semver (1.2.3) if compared correctly
        return newVersion > currentVersion
    }

    private fun getCurrentDateVersion(): String {
        val sdf = java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.US)
        return sdf.format(java.util.Date())
    }

    // Checksum verification
    private fun verifyChecksum(content: String, expectedChecksum: String): Boolean {
        if (expectedChecksum.isEmpty()) return true // No checksum to verify

        val actualChecksum = calculateSha256(content)
        return actualChecksum.equals(expectedChecksum.removePrefix("sha256:"), ignoreCase = true)
    }

    private fun calculateSha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(content.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    fun getLastUpdateTime(): Long = prefs.getLong(KEY_LAST_UPDATE, 0)

    /**
     * Default domain blocklist
     */
    private fun getDefaultDomainBlocklist(): String {
        return """
            # Known phishing/scam domains
            # Updated: ${getCurrentDateVersion()}

            # Fake banking domains
            hdfc-secure-login.com
            icici-netbanking-verify.com
            sbi-kyc-update.in
            axis-account-verify.com

            # Fake payment domains
            paytm-cashback-offer.com
            phonepe-kyc-update.com
            gpay-verify-account.com

            # Lottery/Prize scams
            amazon-lucky-winner.com
            flipkart-prize-winner.com
            google-lottery-winner.com

            # Job scams
            google-hiring-now.com
            amazon-work-from-home.com

            # Loan scams
            instant-loan-approved.com
            pre-approved-loan-offer.com
        """.trimIndent()
    }
}
