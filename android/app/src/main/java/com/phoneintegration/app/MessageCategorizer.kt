package com.phoneintegration.app

import java.util.regex.Pattern

enum class MessageCategory(val title: String, val emoji: String) {
    OTP("OTP / Verification", "🔑"),
    TRANSACTION("Transactions", "💰"),
    PERSONAL("Personal", "👥"),
    PROMOTION("Promotions", "📢"),
    ALERT("Alerts", "⚠️"),
    GENERAL("General", "💬")
}

data class OtpInfo(
    val code: String,
    val body: String
)

object MessageCategorizer {

    // -----------------------
    // OTP Detection
    // -----------------------

    private val otpRegexList = listOf(
        Pattern.compile("""\b(\d{4,8})\b"""),                          // Any 4–8 digit code
        Pattern.compile("""(?i)(otp|code|pin|password)[:\s]*([0-9]{3,8})"""),
        Pattern.compile("""(?i)verification\s*code[:\s]*([0-9]{3,8})"""),
        Pattern.compile("""\b([0-9]{3}-[0-9]{3,4})\b""")
    )

    private val otpKeywords = listOf(
        "otp", "one time", "verification", "authenticate", "login code", "passcode"
    )

    // -----------------------
    // Transaction Keywords
    // -----------------------

    private val transactionKeywords = listOf(
        "credited", "debited", "txn", "transaction", "amount", "rs", "inr", "usd",
        "balance", "account", "upi", "paid", "payment", "withdrawn", "deposit",
        "transfer", "purchase", "refund", "card", "invoice", "receipt", "charge"
    )

    // -----------------------
    // Promotion Keywords
    // -----------------------
    private val promoKeywords = listOf(
        "sale", "discount", "offer", "cashback", "reward", "deal",
        "coupon", "promo", "buy now", "shop now", "% off", "unsubscribe"
    )

    // -----------------------
    // Alert Keywords
    // -----------------------
    private val alertKeywords = listOf(
        "alert", "urgent", "warning", "action required", "expires", "blocked",
        "verify", "suspended", "important update", "due", "overdue", "reminder"
    )

    // -----------------------
    // Detect Automated Sender
    // -----------------------
    private fun isAutomatedSender(address: String): Boolean {
        return when {
            address.matches(Regex("""^\d{5,6}$""")) -> true               // Shortcodes
            address.matches(Regex("^[A-Z]{2,10}-[A-Z0-9]+$")) -> true     // VM-BANK
            address.any { it.isLetter() } -> true                          // Alerts, OTP senders
            else -> false
        }
    }

    // -----------------------
    // Extract OTP
    // -----------------------
    fun extractOtp(message: String): OtpInfo? {
        for (regex in otpRegexList) {
            val matcher = regex.matcher(message)
            if (matcher.find()) {
                val raw = matcher.group(1) ?: matcher.group(0)
                val cleaned = raw.replace(Regex("[^0-9]"), "")
                if (cleaned.length in 4..8) {
                    return OtpInfo(cleaned, message)
                }
            }
        }
        return null
    }

    // -----------------------
    // Scoring System
    // -----------------------
    private fun scoreKeywords(text: String, keywords: List<String>): Int {
        var score = 0
        for (keyword in keywords) {
            if (text.contains(keyword)) score++
        }
        return score
    }

    // -----------------------
    // Main Categorization
    // -----------------------
    fun categorizeMessage(sms: SmsMessage): MessageCategory {
        val text = sms.body.lowercase()
        val otpMatch = extractOtp(sms.body)

        // 1. High-priority: OTP
        if (otpMatch != null && otpKeywords.any { text.contains(it) }) {
            sms.otpInfo = otpMatch
            sms.category = MessageCategory.OTP
            return MessageCategory.OTP
        }

        // 2. Transaction scoring
        val txnScore = scoreKeywords(text, transactionKeywords)

        // 3. Alert scoring
        val alertScore = scoreKeywords(text, alertKeywords)

        // 4. Promotional scoring
        val promoScore = scoreKeywords(text, promoKeywords)

        // --------------------------
        // Category Decision Tree
        // --------------------------

        // Promotion (only if sender is automated)
        if (promoScore >= 2 && isAutomatedSender(sms.address)) {
            sms.category = MessageCategory.PROMOTION
            return MessageCategory.PROMOTION
        }

        // Alerts (e.g. bank alerts, hospital alerts, app notifications)
        if (alertScore >= 2) {
            sms.category = MessageCategory.ALERT
            return MessageCategory.ALERT
        }

        // Transactions (UPI, bank, debit/credit)
        if (txnScore >= 2) {
            sms.category = MessageCategory.TRANSACTION
            return MessageCategory.TRANSACTION
        }

        // Personal
        if (!isAutomatedSender(sms.address)) {
            sms.category = MessageCategory.PERSONAL
            return MessageCategory.PERSONAL
        }

        // Fallback
        sms.category = MessageCategory.GENERAL
        return MessageCategory.GENERAL
    }

    // -----------------------
    // Statistics (Optional UI usage)
    // -----------------------
    fun getStats(messages: List<SmsMessage>): Map<MessageCategory, Int> {
        return messages.groupBy { categorizeMessage(it) }
            .mapValues { it.value.size }
    }
}
