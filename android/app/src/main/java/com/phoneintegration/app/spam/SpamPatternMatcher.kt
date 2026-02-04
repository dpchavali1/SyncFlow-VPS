package com.phoneintegration.app.spam

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.regex.Pattern

/**
 * Pattern-based spam detection using regex and keyword matching
 */
class SpamPatternMatcher {

    companion object {
        private const val TAG = "SpamPatternMatcher"
        private const val PATTERNS_FILE = "spam_patterns.json"
    }

    data class PatternResult(
        val threatType: ThreatType,
        val confidence: Float,
        val description: String,
        val matchedPattern: String
    )

    private val patterns = mutableListOf<CompiledPattern>()

    private data class CompiledPattern(
        val id: String,
        val pattern: Pattern?,
        val keywords: List<String>,
        val isRegex: Boolean,
        val threatType: ThreatType,
        val confidence: Float,
        val description: String
    )

    init {
        // Load default patterns
        loadDefaultPatterns()
    }

    /**
     * Check message body against all patterns
     */
    fun checkPatterns(body: String): List<PatternResult> {
        val results = mutableListOf<PatternResult>()
        val lowerBody = body.lowercase()

        for (compiled in patterns) {
            val matched = if (compiled.isRegex && compiled.pattern != null) {
                compiled.pattern.matcher(body).find()
            } else {
                compiled.keywords.any { lowerBody.contains(it.lowercase()) }
            }

            if (matched) {
                results.add(PatternResult(
                    threatType = compiled.threatType,
                    confidence = compiled.confidence,
                    description = compiled.description,
                    matchedPattern = compiled.id
                ))
            }
        }

        return results
    }

    /**
     * Load patterns from file or use defaults
     */
    fun reloadPatterns(context: Context) {
        val patternsFile = File(context.filesDir, PATTERNS_FILE)
        if (patternsFile.exists()) {
            try {
                val json = patternsFile.readText()
                loadPatternsFromJson(json)
                return
            } catch (_: Exception) { }
        }
        loadDefaultPatterns()
    }

    /**
     * Load patterns from JSON string
     */
    fun loadPatternsFromJson(json: String) {
        try {
            val jsonArray = JSONArray(json)
            patterns.clear()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val pattern = parsePatternObject(obj)
                if (pattern != null) {
                    patterns.add(pattern)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse patterns JSON", e)
        }
    }

    private fun parsePatternObject(obj: JSONObject): CompiledPattern? {
        return try {
            val id = obj.getString("id")
            val patternStr = obj.optString("pattern", "")
            val isRegex = obj.optBoolean("isRegex", false)
            val threatTypeStr = obj.optString("threatType", "UNKNOWN")
            val confidence = obj.optDouble("confidence", 0.8).toFloat()
            val description = obj.optString("description", "")

            val keywords = if (!isRegex && obj.has("keywords")) {
                val keywordsArray = obj.getJSONArray("keywords")
                (0 until keywordsArray.length()).map { keywordsArray.getString(it) }
            } else if (!isRegex) {
                listOf(patternStr)
            } else {
                emptyList()
            }

            val compiledPattern = if (isRegex && patternStr.isNotEmpty()) {
                try {
                    Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE)
                } catch (e: Exception) {
                    Log.w(TAG, "Invalid regex pattern: $patternStr")
                    null
                }
            } else null

            CompiledPattern(
                id = id,
                pattern = compiledPattern,
                keywords = keywords,
                isRegex = isRegex,
                threatType = try { ThreatType.valueOf(threatTypeStr) } catch (e: Exception) { ThreatType.UNKNOWN },
                confidence = confidence,
                description = description
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse pattern object", e)
            null
        }
    }

    fun getPatternCount(): Int = patterns.size

    /**
     * Default spam patterns - used when no downloaded patterns available
     */
    private fun loadDefaultPatterns() {
        patterns.clear()
        patterns.addAll(listOf(
            // Lottery/Prize Scams
            CompiledPattern(
                id = "lottery_winner",
                pattern = Pattern.compile("(you\\s*(have\\s*)?won|winner|lottery|prize|jackpot|lucky\\s*draw)", Pattern.CASE_INSENSITIVE),
                keywords = emptyList(),
                isRegex = true,
                threatType = ThreatType.LOTTERY_SCAM,
                confidence = 0.85f,
                description = "Lottery/Prize scam detected"
            ),
            CompiledPattern(
                id = "claim_prize",
                pattern = Pattern.compile("claim\\s*(your)?\\s*(prize|reward|gift|money|cash)", Pattern.CASE_INSENSITIVE),
                keywords = emptyList(),
                isRegex = true,
                threatType = ThreatType.LOTTERY_SCAM,
                confidence = 0.85f,
                description = "Prize claim scam detected"
            ),

            // Banking Fraud
            CompiledPattern(
                id = "kyc_scam",
                pattern = Pattern.compile("(kyc|pan|aadhar|aadhaar)\\s*(update|verify|expire|link|mandatory)", Pattern.CASE_INSENSITIVE),
                keywords = emptyList(),
                isRegex = true,
                threatType = ThreatType.BANKING_FRAUD,
                confidence = 0.9f,
                description = "KYC/Banking fraud detected"
            ),
            CompiledPattern(
                id = "account_blocked",
                pattern = Pattern.compile("(account|card|bank).{0,20}(block|suspend|freeze|deactivate|close)", Pattern.CASE_INSENSITIVE),
                keywords = emptyList(),
                isRegex = true,
                threatType = ThreatType.BANKING_FRAUD,
                confidence = 0.85f,
                description = "Account suspension scam detected"
            ),
            CompiledPattern(
                id = "verify_immediately",
                pattern = Pattern.compile("verify\\s*(immediately|now|urgent|within)", Pattern.CASE_INSENSITIVE),
                keywords = emptyList(),
                isRegex = true,
                threatType = ThreatType.PHISHING,
                confidence = 0.8f,
                description = "Urgent verification phishing detected"
            ),

            // OTP Theft
            CompiledPattern(
                id = "otp_share",
                pattern = Pattern.compile("(share|send|give|tell)\\s*(your)?\\s*(otp|pin|password|code)", Pattern.CASE_INSENSITIVE),
                keywords = emptyList(),
                isRegex = true,
                threatType = ThreatType.OTP_THEFT,
                confidence = 0.95f,
                description = "OTP theft attempt detected"
            ),

            // Loan/Credit Scams
            CompiledPattern(
                id = "instant_loan",
                pattern = Pattern.compile("(instant|quick|fast|easy)\\s*(loan|credit|cash|money).{0,30}(approv|sanction|disburse)", Pattern.CASE_INSENSITIVE),
                keywords = emptyList(),
                isRegex = true,
                threatType = ThreatType.SCAM,
                confidence = 0.75f,
                description = "Loan scam detected"
            ),
            CompiledPattern(
                id = "pre_approved_loan",
                pattern = Pattern.compile("pre.?approved\\s*(loan|credit|offer)", Pattern.CASE_INSENSITIVE),
                keywords = emptyList(),
                isRegex = true,
                threatType = ThreatType.SCAM,
                confidence = 0.7f,
                description = "Pre-approved loan scam detected"
            ),

            // Job Scams
            CompiledPattern(
                id = "work_from_home",
                pattern = Pattern.compile("(work\\s*from\\s*home|earn\\s*from\\s*home).{0,30}(\\d+k|\\$|₹|rs)", Pattern.CASE_INSENSITIVE),
                keywords = emptyList(),
                isRegex = true,
                threatType = ThreatType.SCAM,
                confidence = 0.8f,
                description = "Work from home scam detected"
            ),
            CompiledPattern(
                id = "earn_daily",
                pattern = Pattern.compile("earn\\s*(daily|weekly|monthly)\\s*(\\d+|\\$|₹|rs)", Pattern.CASE_INSENSITIVE),
                keywords = emptyList(),
                isRegex = true,
                threatType = ThreatType.SCAM,
                confidence = 0.8f,
                description = "Earnings scam detected"
            ),

            // Investment Scams
            CompiledPattern(
                id = "crypto_scam",
                pattern = Pattern.compile("(bitcoin|crypto|trading).{0,30}(profit|earn|double|guaranteed)", Pattern.CASE_INSENSITIVE),
                keywords = emptyList(),
                isRegex = true,
                threatType = ThreatType.SCAM,
                confidence = 0.85f,
                description = "Crypto/Investment scam detected"
            ),
            CompiledPattern(
                id = "guaranteed_returns",
                pattern = Pattern.compile("(guaranteed|assured|fixed)\\s*(return|profit|income|earning)", Pattern.CASE_INSENSITIVE),
                keywords = emptyList(),
                isRegex = true,
                threatType = ThreatType.SCAM,
                confidence = 0.8f,
                description = "Guaranteed returns scam detected"
            ),

            // Phishing Indicators
            CompiledPattern(
                id = "click_link_urgent",
                pattern = Pattern.compile("(click|tap|visit|open)\\s*(the)?\\s*(link|url|below).{0,30}(urgent|immediate|expire)", Pattern.CASE_INSENSITIVE),
                keywords = emptyList(),
                isRegex = true,
                threatType = ThreatType.PHISHING,
                confidence = 0.85f,
                description = "Urgent link phishing detected"
            ),
            CompiledPattern(
                id = "dear_customer_generic",
                pattern = Pattern.compile("dear\\s*(customer|user|member|valued\\s*customer)", Pattern.CASE_INSENSITIVE),
                keywords = emptyList(),
                isRegex = true,
                threatType = ThreatType.PHISHING,
                confidence = 0.5f,
                description = "Generic greeting (possible phishing)"
            ),

            // Promotional Spam
            CompiledPattern(
                id = "unsubscribe_spam",
                pattern = null,
                keywords = listOf("unsubscribe", "opt-out", "stop receiving"),
                isRegex = false,
                threatType = ThreatType.PROMOTIONAL_SPAM,
                confidence = 0.4f,
                description = "Promotional message"
            ),
            CompiledPattern(
                id = "limited_offer",
                pattern = Pattern.compile("(limited\\s*(time)?\\s*offer|offer\\s*valid\\s*(till|until)|last\\s*day)", Pattern.CASE_INSENSITIVE),
                keywords = emptyList(),
                isRegex = true,
                threatType = ThreatType.PROMOTIONAL_SPAM,
                confidence = 0.5f,
                description = "Limited time offer spam"
            ),

            // Dating/Romance Scams
            CompiledPattern(
                id = "dating_scam",
                pattern = Pattern.compile("(lonely|single|girl|lady|woman|meet\\s*me).{0,30}(whatsapp|telegram|chat|call\\s*me)", Pattern.CASE_INSENSITIVE),
                keywords = emptyList(),
                isRegex = true,
                threatType = ThreatType.SCAM,
                confidence = 0.85f,
                description = "Dating/Romance scam detected"
            ),

            // Fake Delivery
            CompiledPattern(
                id = "fake_delivery",
                pattern = Pattern.compile("(package|parcel|delivery).{0,30}(fail|unable|reschedule|update\\s*address).{0,30}(click|link|http)", Pattern.CASE_INSENSITIVE),
                keywords = emptyList(),
                isRegex = true,
                threatType = ThreatType.PHISHING,
                confidence = 0.85f,
                description = "Fake delivery notification phishing"
            ),

            // Political Spam
            CompiledPattern(
                id = "political_campaign",
                pattern = Pattern.compile("(vote\\s*for|support|elect|campaign|rally|bjp|congress|aap|party).{0,30}(2024|2025|2026|election)", Pattern.CASE_INSENSITIVE),
                keywords = emptyList(),
                isRegex = true,
                threatType = ThreatType.PROMOTIONAL_SPAM,
                confidence = 0.6f,
                description = "Political campaign spam"
            ),

            // Malicious Domains (common in SMS scams)
            CompiledPattern(
                id = "suspicious_tld",
                pattern = Pattern.compile("https?://[\\w.-]+\\.(tk|ml|ga|cf|gq|xyz|top|click|link|buzz|work)/", Pattern.CASE_INSENSITIVE),
                keywords = emptyList(),
                isRegex = true,
                threatType = ThreatType.PHISHING,
                confidence = 0.75f,
                description = "Suspicious domain TLD detected"
            )
        ))
    }
}
