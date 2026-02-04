package com.phoneintegration.app.spam

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Manages URL blocklist for malicious/phishing URL detection
 */
class UrlBlocklistManager(private val context: Context) {

    companion object {
        private const val TAG = "UrlBlocklistManager"
        private const val BLOCKLIST_FILE = "url_blocklist.json"
        private const val DOMAIN_BLOCKLIST_FILE = "domain_blocklist.txt"
    }

    data class UrlCheckResult(
        val threatType: ThreatType,
        val source: String,
        val confidence: Float
    )

    // In-memory blocklists for fast lookup
    private val blockedUrlHashes = mutableSetOf<String>()
    private val blockedDomains = mutableSetOf<String>()
    private val blockedUrlPatterns = mutableListOf<Regex>()

    // Known malicious domain patterns
    private val suspiciousDomainPatterns = listOf(
        // Typosquatting patterns
        Regex("amaz[o0]n[.-]", RegexOption.IGNORE_CASE),
        Regex("g[o0][o0]gle[.-]", RegexOption.IGNORE_CASE),
        Regex("faceb[o0][o0]k[.-]", RegexOption.IGNORE_CASE),
        Regex("paypa[l1][.-]", RegexOption.IGNORE_CASE),
        Regex("netf[l1]ix[.-]", RegexOption.IGNORE_CASE),
        Regex("app[l1]e[.-](?!com)", RegexOption.IGNORE_CASE),
        Regex("micr[o0]s[o0]ft[.-]", RegexOption.IGNORE_CASE),

        // Indian bank typosquatting
        Regex("hdfc[.-](?!bank\\.com)", RegexOption.IGNORE_CASE),
        Regex("icici[.-](?!bank\\.com)", RegexOption.IGNORE_CASE),
        Regex("sbi[.-](?!co\\.in)", RegexOption.IGNORE_CASE),
        Regex("axis[.-](?!bank\\.com)", RegexOption.IGNORE_CASE),

        // Common scam patterns
        Regex("secure[.-]?login", RegexOption.IGNORE_CASE),
        Regex("verify[.-]?account", RegexOption.IGNORE_CASE),
        Regex("update[.-]?kyc", RegexOption.IGNORE_CASE),
        Regex("link[.-]?aadhar", RegexOption.IGNORE_CASE),
        Regex("claim[.-]?prize", RegexOption.IGNORE_CASE),
    )

    // Suspicious TLDs commonly used in scams
    private val suspiciousTlds = setOf(
        ".tk", ".ml", ".ga", ".cf", ".gq",  // Free TLDs
        ".xyz", ".top", ".click", ".link", ".buzz",
        ".work", ".date", ".faith", ".loan", ".racing",
        ".win", ".download", ".stream", ".gdn", ".men",
        ".party", ".science", ".trade", ".webcam"
    )

    init {
        loadBlocklists()
    }

    /**
     * Check if a URL is malicious
     */
    fun checkUrl(url: String): UrlCheckResult? {
        val normalizedUrl = normalizeUrl(url)
        val domain = extractDomain(normalizedUrl)
        val urlHash = hashUrl(normalizedUrl)

        // 1. Check URL hash in blocklist
        if (blockedUrlHashes.contains(urlHash)) {
            return UrlCheckResult(ThreatType.MALWARE, "URLhaus", 0.99f)
        }

        // 2. Check domain blocklist
        if (blockedDomains.contains(domain)) {
            return UrlCheckResult(ThreatType.PHISHING, "Domain Blocklist", 0.95f)
        }

        // 3. Check against URL patterns
        for (pattern in blockedUrlPatterns) {
            if (pattern.containsMatchIn(normalizedUrl)) {
                return UrlCheckResult(ThreatType.PHISHING, "Pattern Match", 0.9f)
            }
        }

        // 4. Check suspicious domain patterns (typosquatting)
        for (pattern in suspiciousDomainPatterns) {
            if (pattern.containsMatchIn(domain)) {
                return UrlCheckResult(ThreatType.PHISHING, "Typosquatting", 0.85f)
            }
        }

        // 5. Check suspicious TLD
        val tld = extractTld(domain)
        if (tld != null && suspiciousTlds.contains(tld.lowercase())) {
            // Only flag if combined with other suspicious indicators
            if (hasSuspiciousUrlCharacteristics(normalizedUrl)) {
                return UrlCheckResult(ThreatType.SCAM, "Suspicious TLD", 0.7f)
            }
        }

        return null
    }

    /**
     * Reload blocklists from files
     */
    fun reloadBlocklist() {
        loadBlocklists()
    }

    /**
     * Get current blocklist size
     */
    fun getBlocklistSize(): Int = blockedUrlHashes.size + blockedDomains.size

    /**
     * Add URL to local blocklist
     */
    fun addToBlocklist(url: String, threatType: ThreatType) {
        val hash = hashUrl(normalizeUrl(url))
        blockedUrlHashes.add(hash)
    }

    /**
     * Load blocklists from files
     */
    private fun loadBlocklists() {
        loadUrlBlocklist()
        loadDomainBlocklist()
        loadDefaultBlockedDomains()
    }

    private fun loadUrlBlocklist() {
        val file = File(context.filesDir, BLOCKLIST_FILE)
        if (!file.exists()) return

        try {
            val json = file.readText()
            val jsonArray = JSONArray(json)

            blockedUrlHashes.clear()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val url = obj.optString("url", "")
                if (url.isNotEmpty()) {
                    blockedUrlHashes.add(hashUrl(normalizeUrl(url)))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load URL blocklist", e)
        }
    }

    private fun loadDomainBlocklist() {
        val file = File(context.filesDir, DOMAIN_BLOCKLIST_FILE)
        if (!file.exists()) return

        try {
            val lines = file.readLines()
            blockedDomains.clear()
            lines.filter { it.isNotBlank() && !it.startsWith("#") }
                .forEach { blockedDomains.add(it.trim().lowercase()) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load domain blocklist", e)
        }
    }

    /**
     * Default blocked domains - known scam/phishing domains
     */
    private fun loadDefaultBlockedDomains() {
        val defaults = listOf(
            // Add commonly known scam domains
            // These are examples - real implementation would have more
            "secure-login-verify.com",
            "account-update-required.com",
            "prize-winner-claim.com",
            "lottery-winner-2024.com",
            "kyc-update-sbi.com",
            "hdfc-netbanking-secure.com",
            "icici-verify-account.com",
            "amazon-prize-winner.com",
            "paytm-kyc-update.in",
        )
        defaults.forEach { blockedDomains.add(it.lowercase()) }
    }

    // URL utility functions
    private fun normalizeUrl(url: String): String {
        var normalized = url.lowercase().trim()

        // Remove protocol variations
        normalized = normalized.removePrefix("http://")
        normalized = normalized.removePrefix("https://")
        normalized = normalized.removePrefix("www.")

        // Remove trailing slash
        normalized = normalized.trimEnd('/')

        // Remove common tracking parameters
        val queryIndex = normalized.indexOf('?')
        if (queryIndex > 0) {
            val basePath = normalized.substring(0, queryIndex)
            val query = normalized.substring(queryIndex + 1)
            val filteredParams = query.split('&')
                .filter { !it.startsWith("utm_") && !it.startsWith("ref=") }
                .joinToString("&")
            normalized = if (filteredParams.isEmpty()) basePath else "$basePath?$filteredParams"
        }

        return normalized
    }

    private fun extractDomain(url: String): String {
        val withoutProtocol = url.removePrefix("http://").removePrefix("https://").removePrefix("www.")
        val pathStart = withoutProtocol.indexOf('/')
        return if (pathStart > 0) {
            withoutProtocol.substring(0, pathStart)
        } else {
            withoutProtocol.split('?')[0]
        }.lowercase()
    }

    private fun extractTld(domain: String): String? {
        val parts = domain.split('.')
        return if (parts.size >= 2) ".${parts.last()}" else null
    }

    private fun hashUrl(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(url.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun hasSuspiciousUrlCharacteristics(url: String): Boolean {
        // Check for suspicious characteristics
        val suspicious = listOf(
            url.contains("login"),
            url.contains("verify"),
            url.contains("secure"),
            url.contains("account"),
            url.contains("update"),
            url.contains("confirm"),
            url.contains("bank"),
            url.contains("pay"),
            url.length > 50, // Unusually long URL
            url.count { it == '-' } > 3, // Many hyphens
            url.matches(Regex(".*\\d{5,}.*")), // Long number sequences
        )
        return suspicious.count { it } >= 2
    }

    /**
     * Fetch fresh blocklist from URLhaus (called during update)
     */
    suspend fun fetchUrlhausBlocklist(): Boolean = withContext(Dispatchers.IO) {
        try {
            // URLhaus provides free JSON/CSV feeds
            // In production, this would download from:
            // https://urlhaus.abuse.ch/downloads/json_recent/
            // https://urlhaus.abuse.ch/downloads/csv_recent/

            // For now, return true - actual download handled by FilterUpdateManager
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch URLhaus blocklist", e)
            false
        }
    }
}
