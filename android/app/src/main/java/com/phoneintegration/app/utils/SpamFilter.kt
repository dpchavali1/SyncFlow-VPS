/**
 * SpamFilter.kt
 *
 * Comprehensive spam detection utility that analyzes SMS messages using pattern matching,
 * keyword detection, and heuristics to identify spam, scams, and promotional messages.
 *
 * ## Architecture Overview
 *
 * The spam filter uses a scoring system where each spam indicator adds to a confidence score.
 * When the score exceeds a threshold (default 0.5), the message is classified as spam.
 *
 * ```
 * Message Input --> Whitelist Check (trusted senders bypass)
 *                        |
 *                        v
 *                 Keyword Matching (+0.15 per keyword)
 *                        |
 *                        v
 *                 URL Analysis (+0.35 for suspicious URLs)
 *                        |
 *                        v
 *                 Sender Pattern Check (+0.25 for suspicious senders)
 *                        |
 *                        v
 *                 Additional Heuristics (caps, special chars, etc.)
 *                        |
 *                        v
 *                 Final Score --> Spam Classification
 * ```
 *
 * ## Spam Categories Detected
 *
 * 1. **Financial Scams**: Fake loans, card blocked alerts, phishing
 * 2. **Toll/Traffic Scams**: Fake toll violation notices (very common)
 * 3. **Delivery Scams**: Fake package notifications (USPS, FedEx impersonation)
 * 4. **Dating/Romance Spam**: Unsolicited dating messages
 * 5. **Political Spam**: Campaign messages and fundraising
 * 6. **Promotional Spam**: Aggressive marketing, discount offers
 * 7. **Brand Impersonation**: Messages claiming to be from Amazon, Netflix, etc.
 *
 * ## Trusted Senders (Never Spam)
 *
 * Bank transaction alerts, payment confirmations, and government notices from
 * recognized senders are automatically whitelisted. Examples:
 * - Banks: HDFC, ICICI, SBI, AXIS, etc.
 * - Payments: Paytm, PhonePe, GPay
 * - Government: UIDAI, EPFO
 *
 * ## Score Weights
 *
 * | Indicator                    | Score Weight |
 * |------------------------------|--------------|
 * | High confidence scam phrase  | +0.60        |
 * | Brand impersonation          | +0.50        |
 * | Dating spam phrase           | +0.50        |
 * | Old unread message (>72h)    | +0.55        |
 * | Suspicious URL               | +0.35        |
 * | Promotional sender prefix    | +0.30        |
 * | Spam keyword (each, max 4)   | +0.15        |
 * | Saved contact                | -0.15        |
 *
 * ## Usage
 *
 * ```kotlin
 * val result = SpamFilter.checkMessage(
 *     body = message.body,
 *     senderAddress = message.address,
 *     isFromContact = isKnownContact,
 *     threshold = 0.5f
 * )
 *
 * if (result.isSpam) {
 *     // Handle spam message
 *     Log.d("Spam", "Confidence: ${result.confidence}, Reasons: ${result.reasons}")
 * }
 * ```
 *
 * @see checkMessage for the main spam detection method
 * @see SpamCheckResult for the result data structure
 */
package com.phoneintegration.app.utils

import android.content.Context
import com.phoneintegration.app.data.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Spam filter utility for detecting and managing spam messages.
 * Uses pattern matching and heuristics to identify spam.
 *
 * This is an object (singleton) so the pattern lists are loaded once and reused.
 */
object SpamFilter {

    // ==========================================
    // REGION: Trusted Senders Whitelist
    // ==========================================

    /**
     * Known trusted senders that should NEVER be marked as spam.
     *
     * These include:
     * - Indian banks (HDFC, ICICI, SBI, etc.)
     * - Payment services (Paytm, PhonePe, GPay)
     * - Credit card companies
     * - Insurance and investment firms
     * - Government agencies
     *
     * The sender ID is checked against this set (case-insensitive contains match).
     */
    private val TRUSTED_BANK_SENDERS = setOf(
        // Indian Banks - Transaction alerts
        "HDFCBK", "HDFC", "HDFCBANK",
        "ICICIB", "ICICI", "ICICIBANK",
        "SBIINB", "SBIOTP", "SBIBNK", "SBI",
        "AXISBK", "AXIS", "AXISBANK",
        "KOTAKB", "KOTAK", "KOTAKBANK",
        "PNBSMS", "PNB",
        "BOIIND", "BOI",
        "ABORIG", "ABFL",
        "IABORIG", "IDBI", "IDBIBK",
        "CANBNK", "CANARA",
        "UNIONB", "UNION",
        "INDBNK", "INDIAN",
        "BOBTXN", "BOB", "BOBSMS",
        "YESBK", "YESBANK",
        "RBLBNK", "RBL",
        "FEDERL", "FEDERAL",
        "SCBANK", "SCBIND", // Standard Chartered
        "CITIBK", "CITI",
        "HSBCBK", "HSBC",
        "DLOAMT", // Loan EMI alerts

        // Payment Services
        "PYTMPR", "PAYTM", "PYTM",
        "GPAY", "GOOGLEPAY",
        "PHONPE", "PHONEPE",
        "AMAZNP", "AMAZON", "AMZN",
        "FLIPKT", "FLIPKART",
        "BHARPE", "BHRTPE",

        // Credit Cards
        "AMEXIN", "AMEX",
        "HDFCCC", "ICICCC", "SBICC",

        // Insurance & Investments
        "LICIND", "LIC",
        "SBIMF", "HDFCMF", "ICICMF",

        // Government
        "UIDAI", "AADHAR", "EPFIND", "EPFO",
        "INCOME", "ITREFUND"
    )

    /**
     * Regex patterns for trusted sender formats.
     * Matches the Indian bank SMS format: XX-BANKNAME (e.g., "AD-HDFCBANK")
     */
    private val TRUSTED_SENDER_PATTERNS = listOf(
        Regex("""^[A-Z]{2}-[A-Z]*HDFC[A-Z]*$""", RegexOption.IGNORE_CASE),
        Regex("""^[A-Z]{2}-[A-Z]*ICICI[A-Z]*$""", RegexOption.IGNORE_CASE),
        Regex("""^[A-Z]{2}-[A-Z]*SBI[A-Z]*$""", RegexOption.IGNORE_CASE),
        Regex("""^[A-Z]{2}-[A-Z]*AXIS[A-Z]*$""", RegexOption.IGNORE_CASE),
        Regex("""^[A-Z]{2}-[A-Z]*KOTAK[A-Z]*$""", RegexOption.IGNORE_CASE),
        Regex("""^[A-Z]{2}-[A-Z]*PAYTM[A-Z]*$""", RegexOption.IGNORE_CASE)
    )

    // ==========================================
    // REGION: Dating/Romance Spam Detection
    // ==========================================

    /**
     * Phrases commonly used in dating/romance spam messages.
     * These are high-confidence spam indicators.
     */
    private val DATING_SPAM_PHRASES = listOf(
        "hey babe",
        "hey handsome",
        "hey sexy",
        "hey cutie",
        "hi babe",
        "hi handsome",
        "hi sexy",
        "miss you babe",
        "i miss you",
        "are you there",
        "why no reply",
        "did you get my",
        "check my profile",
        "my profile pic",
        "see my photos",
        "want to meet",
        "let's meet",
        "come meet me",
        "i'm lonely",
        "i'm single",
        "looking for fun",
        "no strings",
        "casual fun",
        "just for fun",
        "adult fun"
    )

    /** Domain keywords that indicate a dating/adult spam URL */
    private val DATING_SPAM_DOMAINS = listOf(
        "intimate", "dating", "hookup", "singles", "meet", "love",
        "affair", "flirt", "chat", "sexy", "hot", "naughty", "adult",
        "match", "date", "romance", "lonely", "finder"
    )

    // ==========================================
    // REGION: Spam Keywords Database
    // ==========================================

    /**
     * Comprehensive list of spam keywords and phrases.
     *
     * Organized by category:
     * - Lottery/Prize scams
     * - Toll/Traffic scams
     * - Financial scams
     * - Banking/Card scams
     * - Phishing attempts
     * - IRS/Tax scams
     * - Delivery scams
     * - Job scams
     * - Dating/Adult spam
     * - Political campaigns
     * - Promotional spam
     *
     * Each match adds +0.15 to the spam score (capped at 4 matches = +0.60)
     */
    private val SPAM_KEYWORDS = listOf(
        // Lottery/Prize scams
        "congratulations you have won",
        "you have been selected",
        "claim your prize",
        "lottery winner",
        "you won",
        "winner selected",
        "cash prize",
        "gift card winner",

        // Toll/Traffic scams (very common)
        "missed toll",
        "unpaid toll",
        "toll fee",
        "toll violation",
        "toll balance",
        "toll due",
        "pay toll",
        "ezpass",
        "e-zpass",
        "fastrak",
        "sunpass",
        "peach pass",
        "tollway",
        "outstanding toll",

        // Financial scams
        "urgent loan",
        "instant loan",
        "loan approved",
        "pre-approved loan",
        "credit card offer",
        "debt relief",
        "make money fast",
        "earn from home",
        "investment opportunity",
        "crypto profit",
        "bitcoin profit",
        "forex trading",
        "cash advance",
        "payday loan",
        "guaranteed approval",

        // Banking/Card scams
        "card blocked",
        "card suspended",
        "suspicious transaction",
        "unauthorized transaction",
        "bank alert",
        "account locked",
        "verify transaction",
        "confirm payment",
        "payment declined",
        "update payment",

        // Phishing
        "verify your account",
        "account suspended",
        "click here to verify",
        "update your details",
        "confirm your identity",
        "unusual activity",
        "security alert",
        "login attempt",
        "password expired",
        "immediate action required",
        "action required",
        "verify now",
        "confirm now",

        // IRS/Tax scams
        "irs notice",
        "tax refund",
        "tax debt",
        "irs audit",
        "stimulus check",
        "unclaimed refund",

        // Delivery scams
        "delivery failed",
        "package waiting",
        "customs fee required",
        "shipping fee",
        "redelivery fee",
        "unable to deliver",
        "delivery attempt",
        "reschedule delivery",
        "tracking update",
        "package held",

        // Job scams
        "work from home",
        "easy money",
        "part time job",
        "hiring now",
        "earn daily",
        "no experience needed",
        "start today",
        "remote job opportunity",

        // Subscription scams
        "subscription expired",
        "renew now",
        "auto-renewal",
        "membership expiring",
        "account will be closed",
        "service interrupted",

        // Dating/Adult spam
        "adult content",
        "dating site",
        "singles in your area",
        "meet singles",
        "hot singles",
        "lonely housewives",
        "hookup",
        "looking for fun",
        "casual dating",
        "no strings attached",
        "discreet affair",
        "naughty",
        "sexy singles",
        "local singles",
        "match waiting",
        "someone likes you",
        "new match",
        "dating profile",
        "view profile",
        "women near you",
        "men near you",
        "chat now",
        "meet tonight",
        "looking for you",
        "wants to meet",

        // Event/Festival spam
        "festival tickets",
        "concert tickets",
        "event tickets",
        "whisky festival",
        "wine festival",
        "beer festival",
        "food festival",
        "music festival",
        "free tickets",
        "vip tickets",
        "exclusive tickets",
        "limited tickets",
        "get tickets",
        "book tickets",

        // Political campaigns - USA
        "election",  // Standalone keyword for any election-related spam
        "elections",
        "electoral",
        "vote for",
        "vote yes",
        "vote no",
        "election day",
        "polling location",
        "your vote",
        "cast your vote",
        "support our campaign",
        "political action",
        "donate now",
        "campaign contribution",
        "paid for by",
        "approved by",
        "candidate for",
        "running for",
        "re-elect",
        "reelect",
        "democrat",
        "republican",
        "conservative",
        "liberal",
        "trump",
        "biden",
        "desantis",
        "harris",
        "pence",
        "pelosi",
        "mcconnell",
        "aoc",
        "bernie",
        "sanders",
        "congress",
        "senate",
        "governor",
        "senator",
        "representative",
        "ballot",
        "proposition",
        "measure",
        "primary election",
        "general election",
        "early voting",
        "mail-in ballot",
        "absentee ballot",
        "gop",
        "dnc",
        "rnc",
        "maga",
        "super pac",
        "political committee",
        "swing state",
        "battleground",
        "midterm",
        "electoral college",
        "fundraising deadline",
        "match your donation",
        "triple match",
        "double your impact",

        // Political campaigns - India
        "bjp",
        "congress party",
        "aap",
        "tmc",
        "dmk",
        "shiv sena",
        "ncp",
        "modi ji",
        "rahul gandhi",
        "kejriwal",
        "yogi",
        "mamata",
        "election rally",
        "chunav",
        "vikas",

        // Emergency/Roadside scams
        "aaa membership",
        "roadside assistance",
        "emergency roadside",
        "towing service",
        "car warranty",
        "extended warranty",
        "warranty expiring",
        "vehicle warranty",
        "auto warranty",
        "insurance expired",
        "coverage expiring",

        // Fake delivery - specific carriers
        "usps delivery",
        "usps package",
        "usps notice",
        "fedex delivery",
        "fedex package",
        "ups delivery",
        "ups package",
        "dhl delivery",
        "dhl package",
        "your shipment",
        "delivery notification",
        "parcel waiting",
        "schedule your delivery",

        // Promotional spam / Ads
        "% off",
        "50% off",
        "70% off",
        "80% off",
        "90% off",
        "huge discount",
        "massive sale",
        "flash sale",
        "clearance sale",
        "promo code",
        "coupon code",
        "use code",
        "discount code",
        "special offer",
        "exclusive offer",
        "deal expires",
        "offer expires",
        "limited stock",
        "while supplies last",
        "order now",
        "shop now",
        "buy now",
        "don't miss out",
        "last chance",
        "final hours",
        "ending soon",
        "today only",
        "one day only",
        "free shipping",
        "no minimum",

        // General spam triggers
        "act now",
        "limited time offer",
        "exclusive deal",
        "free gift",
        "risk free",
        "no obligation",
        "call now",
        "text back",
        "reply stop to",
        "unsubscribe",
        "opt out",
        "text stop",
        "click below",
        "tap here",
        "click link",
        "visit link",
        "urgent",
        "important notice",
        "dear customer",
        "dear user",
        "valued customer"
    )

    // ==========================================
    // REGION: High Confidence Spam Phrases
    // ==========================================

    /**
     * High-confidence spam phrases that indicate definite spam/scam.
     * These add +0.60 to the spam score for immediate flagging.
     *
     * Includes:
     * - Toll scam phrases ("your toll", "pay your toll")
     * - Threat phrases ("legal action", "arrest warrant")
     * - Delivery scam phrases ("package could not be delivered")
     * - Dating scam patterns
     * - Brand impersonation indicators
     */
    private val HIGH_CONFIDENCE_PHRASES = listOf(
        // Toll scams
        "your toll",
        "pay your toll",
        "toll invoice",
        "settle your balance",
        "settle your toll",
        "toll amount due",

        // Urgency/Threat scams
        "avoid penalties",
        "avoid late fees",
        "collection action",
        "legal action",
        "arrest warrant",
        "court appearance",
        "final warning",
        "account will be suspended",
        "immediate suspension",

        // Delivery scams
        "verify your identity immediately",
        "your package could not be delivered",
        "click to reschedule",
        "confirm delivery address",
        "usps: your package",
        "fedex: your package",
        "ups: your package",
        "delivery fee required",
        "pay delivery fee",

        // Dating scam patterns
        "girl wants to meet",
        "woman wants to meet",
        "sent you a message",
        "waiting for your reply",
        "check your matches",

        // Brand impersonation indicators
        "from amazon",
        "amazon order",
        "amazon delivery",
        "your amazon",
        "netflix account",
        "paypal account",
        "apple id",
        "google account"
    )

    // ==========================================
    // REGION: Brand Impersonation Detection
    // ==========================================

    /**
     * Mapping of brand names to their legitimate domains.
     *
     * Used to detect brand impersonation: if a message mentions "Amazon"
     * but links to a non-Amazon domain, it's likely a scam.
     */
    private val BRAND_DOMAINS = mapOf(
        "amazon" to listOf("amazon.com", "amazon.in", "amazon.co.uk", "amzn.com", "amzn.to"),
        "usps" to listOf("usps.com"),
        "fedex" to listOf("fedex.com"),
        "ups" to listOf("ups.com"),
        "netflix" to listOf("netflix.com"),
        "paypal" to listOf("paypal.com"),
        "apple" to listOf("apple.com", "icloud.com"),
        "google" to listOf("google.com", "gmail.com"),
        "aaa" to listOf("aaa.com"),
        "walmart" to listOf("walmart.com"),
        "costco" to listOf("costco.com"),
        "target" to listOf("target.com")
    )

    // ==========================================
    // REGION: Suspicious URL Patterns
    // ==========================================

    /**
     * Regex patterns for suspicious URLs commonly used in spam.
     *
     * Detects:
     * - URL shorteners (bit.ly, tinyurl.com, etc.)
     * - Suspicious TLDs (.xyz, .top, .click, .icu, etc.)
     * - Fake toll/government domains
     * - Fake carrier domains (usps/fedex impersonation)
     */
    private val SUSPICIOUS_URL_PATTERNS = listOf(
        // URL shorteners (commonly used in spam)
        Regex("""bit\.ly/\w+""", RegexOption.IGNORE_CASE),
        Regex("""tinyurl\.com/\w+""", RegexOption.IGNORE_CASE),
        Regex("""goo\.gl/\w+""", RegexOption.IGNORE_CASE),
        Regex("""t\.co/\w+""", RegexOption.IGNORE_CASE),
        Regex("""rb\.gy/\w+""", RegexOption.IGNORE_CASE),
        Regex("""is\.gd/\w+""", RegexOption.IGNORE_CASE),
        Regex("""cutt\.ly/\w+""", RegexOption.IGNORE_CASE),
        Regex("""ow\.ly/\w+""", RegexOption.IGNORE_CASE),
        Regex("""buff\.ly/\w+""", RegexOption.IGNORE_CASE),
        Regex("""shorturl\.\w+/\w+""", RegexOption.IGNORE_CASE),
        Regex("""tiny\.cc/\w+""", RegexOption.IGNORE_CASE),
        Regex("""v\.gd/\w+""", RegexOption.IGNORE_CASE),

        // Suspicious TLDs (no trailing slash required, supports hyphens in domain)
        Regex("""[-\w]+\.xyz($|/|\s|[^a-z])""", RegexOption.IGNORE_CASE),
        Regex("""[-\w]+\.top($|/|\s|[^a-z])""", RegexOption.IGNORE_CASE),
        Regex("""[-\w]+\.click($|/|\s|[^a-z])""", RegexOption.IGNORE_CASE),
        Regex("""[-\w]+\.link($|/|\s|[^a-z])""", RegexOption.IGNORE_CASE),
        Regex("""[-\w]+\.buzz($|/|\s|[^a-z])""", RegexOption.IGNORE_CASE),
        Regex("""[-\w]+\.site($|/|\s|[^a-z])""", RegexOption.IGNORE_CASE),
        Regex("""[-\w]+\.online($|/|\s|[^a-z])""", RegexOption.IGNORE_CASE),
        Regex("""[-\w]+\.fun($|/|\s|[^a-z])""", RegexOption.IGNORE_CASE),
        Regex("""[-\w]+\.icu($|/|\s|[^a-z])""", RegexOption.IGNORE_CASE),
        Regex("""[-\w]+\.vip($|/|\s|[^a-z])""", RegexOption.IGNORE_CASE),
        Regex("""[-\w]+\.club($|/|\s|[^a-z])""", RegexOption.IGNORE_CASE),
        Regex("""[-\w]+\.store($|/|\s|[^a-z])""", RegexOption.IGNORE_CASE),
        Regex("""[-\w]+\.shop($|/|\s|[^a-z])""", RegexOption.IGNORE_CASE),
        Regex("""\w+\.work/""", RegexOption.IGNORE_CASE),
        Regex("""\w+\.rest/""", RegexOption.IGNORE_CASE),
        Regex("""\w+\.fit/""", RegexOption.IGNORE_CASE),
        Regex("""\w+\.life/""", RegexOption.IGNORE_CASE),
        Regex("""\w+\.club/""", RegexOption.IGNORE_CASE),
        Regex("""\w+\.online/""", RegexOption.IGNORE_CASE),
        Regex("""\w+\.site/""", RegexOption.IGNORE_CASE),
        Regex("""\w+\.store/""", RegexOption.IGNORE_CASE),
        Regex("""\w+\.shop/""", RegexOption.IGNORE_CASE),
        Regex("""\w+\.icu/""", RegexOption.IGNORE_CASE),
        Regex("""\w+\.vip/""", RegexOption.IGNORE_CASE),
        Regex("""\w+\.win/""", RegexOption.IGNORE_CASE),
        Regex("""\w+\.space/""", RegexOption.IGNORE_CASE),
        Regex("""\w+\.fun/""", RegexOption.IGNORE_CASE),
        Regex("""\w+\.monster/""", RegexOption.IGNORE_CASE),

        // Fake toll/government domains
        Regex("""toll.*\.com""", RegexOption.IGNORE_CASE),
        Regex("""ezpass.*\.com""", RegexOption.IGNORE_CASE),
        Regex("""pay.*toll""", RegexOption.IGNORE_CASE),
        Regex("""usps.*\..*(?!usps\.com)""", RegexOption.IGNORE_CASE),
        Regex("""fedex.*\..*(?!fedex\.com)""", RegexOption.IGNORE_CASE)
    )

    // ==========================================
    // REGION: Suspicious Sender Patterns
    // ==========================================

    /**
     * Regex patterns for suspicious sender addresses.
     * Note: These need to be balanced - some legitimate senders use similar formats.
     */
    private val SPAM_SENDER_PATTERNS = listOf(
        Regex("""^[A-Z]{2}-\w+"""),  // Shortcodes like "AD-SPAM"
        Regex("""^\d{5,6}$"""),       // 5-6 digit shortcodes
        Regex("""^[A-Z]{6,}$""")      // All caps sender names
    )

    // ==========================================
    // REGION: Data Classes
    // ==========================================

    /**
     * Result of a spam check operation.
     *
     * @property isSpam True if the message is classified as spam
     * @property confidence Score from 0.0 to 1.0 indicating spam likelihood
     * @property reasons List of human-readable reasons for the classification
     */
    data class SpamCheckResult(
        val isSpam: Boolean,
        val confidence: Float,  // 0.0 to 1.0
        val reasons: List<String>
    )

    // ==========================================
    // REGION: Public API
    // ==========================================

    /**
     * Checks if a message is likely spam using multi-factor analysis.
     *
     * ## Algorithm Overview
     *
     * 1. **Whitelist Check**: Trusted senders (banks, etc.) return immediately as not spam
     * 2. **High Confidence Phrases**: Check for definite scam phrases (+0.60)
     * 3. **Keyword Matching**: Count spam keywords (up to +0.60)
     * 4. **URL Analysis**: Check for suspicious URLs (+0.35)
     * 5. **Sender Analysis**: Check sender format patterns
     * 6. **Content Heuristics**: Caps ratio, special chars, length
     * 7. **Unread Heuristic**: Old unread messages from unknowns are suspicious
     * 8. **Brand Impersonation**: Detect fake brand links
     * 9. **Dating Spam**: Check dating-specific patterns
     *
     * ## Threshold Guidelines
     *
     * - 0.3: Very aggressive (catches more spam, more false positives)
     * - 0.5: Balanced (default, recommended)
     * - 0.7: Conservative (fewer false positives, might miss some spam)
     *
     * @param body The message body text
     * @param senderAddress The sender's phone number or shortcode
     * @param isFromContact True if sender is in contacts (reduces spam score)
     * @param threshold Confidence threshold for spam classification (default 0.5)
     * @param isRead True if message has been read (unread messages more suspicious)
     * @param messageAgeHours Age of the message in hours (old unread = suspicious)
     * @return SpamCheckResult with classification and reasons
     */
    fun checkMessage(
        body: String,
        senderAddress: String,
        isFromContact: Boolean = false,
        threshold: Float = 0.5f,
        isRead: Boolean = true,  // Default to true to not penalize when unknown
        messageAgeHours: Long = 0  // How old the message is
    ): SpamCheckResult {
        val reasons = mutableListOf<String>()
        var score = 0f

        val lowerBody = body.lowercase()
        val upperSender = senderAddress.uppercase()

        // STEP 1: Whitelist check - trusted senders are NEVER spam
        // This is the first check because it's fast and definitive
        if (isTrustedSender(senderAddress)) {
            return SpamCheckResult(isSpam = false, confidence = 0f, reasons = listOf("Trusted sender"))
        }

        // STEP 2: Contact bonus
        // Messages from saved contacts are less likely to be spam
        // But we limit the bonus since businesses can be saved as contacts
        if (isFromContact) {
            score -= 0.15f
        }

        // STEP 3: High confidence phrase detection (scam patterns)
        // These are definite spam indicators that add significant score
        val matchedHighConfidence = HIGH_CONFIDENCE_PHRASES.filter { phrase ->
            lowerBody.contains(phrase)
        }
        if (matchedHighConfidence.isNotEmpty()) {
            score += 0.6f  // High score for scam phrases
            reasons.add("Scam phrase detected: ${matchedHighConfidence.first()}")
        }

        // STEP 4: Keyword matching
        // Check against comprehensive spam keyword database
        val matchedKeywords = SPAM_KEYWORDS.filter { keyword ->
            lowerBody.contains(keyword)
        }
        if (matchedKeywords.isNotEmpty()) {
            score += 0.15f * matchedKeywords.size.coerceAtMost(4)
            reasons.add("Contains spam keywords: ${matchedKeywords.take(3).joinToString(", ")}")
        }

        // STEP 5: URL analysis
        // Check for URL shorteners and suspicious TLDs
        val hasShortUrl = SUSPICIOUS_URL_PATTERNS.any { it.containsMatchIn(body) }
        if (hasShortUrl) {
            score += 0.35f
            reasons.add("Contains shortened/suspicious URL")
        }

        // STEP 6: Sender pattern analysis
        // Check for suspicious sender formats (shortcodes, all caps, etc.)
        val isSuspiciousSender = SPAM_SENDER_PATTERNS.any { it.matches(senderAddress) }
        if (isSuspiciousSender) {
            score += 0.25f
            reasons.add("Suspicious sender format")
        }

        // STEP 7: Promotional sender prefix check
        // Common prefixes used by marketing/spam senders
        if (upperSender.startsWith("AD-") || upperSender.startsWith("BZ-") ||
            upperSender.startsWith("DM-") || upperSender.startsWith("VM-") ||
            upperSender.startsWith("HP-") || upperSender.startsWith("JD-") ||
            upperSender.startsWith("AM-") || upperSender.startsWith("BN-") ||
            upperSender.startsWith("TD-") || upperSender.startsWith("TM-")) {
            score += 0.3f
            reasons.add("Promotional sender prefix")
        }

        // Check if sender is a brand/business shortcode (all letters, 5-11 chars)
        if (senderAddress.matches(Regex("^[A-Za-z]{5,11}$"))) {
            score += 0.2f
            reasons.add("Business shortcode sender")
        }

        // Check for any URL in message from non-contact
        val hasAnyUrl = body.contains("http://") || body.contains("https://") ||
                        body.contains(".com") || body.contains(".in") || body.contains(".co")
        if (hasAnyUrl && !isFromContact) {
            score += 0.15f
            reasons.add("Contains URL from unknown sender")
        }

        // Check for excessive caps
        val capsRatio = body.count { it.isUpperCase() }.toFloat() / body.length.coerceAtLeast(1)
        if (capsRatio > 0.5f && body.length > 20) {
            score += 0.15f
            reasons.add("Excessive capital letters")
        }

        // Check for excessive special characters (common in spam/ads)
        val specialCharRatio = body.count { !it.isLetterOrDigit() && !it.isWhitespace() }.toFloat() / body.length.coerceAtLeast(1)
        if (specialCharRatio > 0.15f) {
            score += 0.1f
            reasons.add("Excessive special characters")
        }

        // Check for phone numbers in body (common in spam)
        val hasPhoneInBody = Regex("""(\+\d{10,}|\d{10,})""").containsMatchIn(body)
        if (hasPhoneInBody && !isFromContact) {
            score += 0.1f
            reasons.add("Contains phone number in message")
        }

        // Check message length (very short with link = suspicious)
        if (body.length < 15 && hasAnyUrl) {
            score += 0.2f
            reasons.add("Very short message with link")
        }

        // Check for common ad/promotional patterns
        if (lowerBody.contains("t&c apply") || lowerBody.contains("t&c") ||
            lowerBody.contains("terms apply") || lowerBody.contains("conditions apply")) {
            score += 0.2f
            reasons.add("Contains terms/conditions disclaimer")
        }

        // Check for unknown sender pattern
        val lowerSender = senderAddress.lowercase()
        if (lowerSender.contains("unknown") || lowerSender == "unknown_sender" ||
            lowerSender == "unknown sender" || lowerSender.contains("private") ||
            lowerSender.contains("blocked") || lowerSender.contains("no caller id")) {
            score += 0.4f
            reasons.add("Unknown/hidden sender")
        }

        // STEP 8: Unread message heuristic
        // Messages that remain unread for a long time from unknown senders
        // are often spam that users instinctively ignore
        if (!isRead && !isFromContact) {
            if (messageAgeHours > 72) {
                // Very old unread messages (>3 days) are highly suspicious
                score += 0.55f
                reasons.add("Very old unread message from unknown sender (${messageAgeHours}h)")
            } else if (messageAgeHours > 24) {
                // Old unread messages (>24 hours) are more suspicious
                score += 0.45f
                reasons.add("Old unread message from unknown sender (${messageAgeHours}h)")
            } else if (messageAgeHours > 6) {
                // Messages unread for 6+ hours from unknown senders
                score += 0.3f
                reasons.add("Unread message from unknown sender (${messageAgeHours}h)")
            }
        }

        // STEP 9: Brand impersonation detection
        // If message mentions a brand but links to a different domain, it's likely a scam
        for ((brand, validDomains) in BRAND_DOMAINS) {
            if (lowerBody.contains(brand)) {
                // Extract URLs from body
                val urlPattern = Regex("""https?://[^\s]+|www\.[^\s]+|\w+\.\w+/[^\s]*""", RegexOption.IGNORE_CASE)
                val urlsInBody = urlPattern.findAll(body).map { it.value.lowercase() }.toList()

                if (urlsInBody.isNotEmpty()) {
                    // Check if any URL matches the valid brand domains
                    val hasValidBrandUrl = urlsInBody.any { url ->
                        validDomains.any { domain -> url.contains(domain) }
                    }

                    if (!hasValidBrandUrl) {
                        score += 0.5f  // High score for brand impersonation
                        reasons.add("Possible $brand impersonation - suspicious link")
                    }
                }
            }
        }

        // STEP 10: Dating/romance spam detection
        // Check for phrases and domains commonly used in dating spam
        val hasDatingPhrase = DATING_SPAM_PHRASES.any { lowerBody.contains(it) }
        if (hasDatingPhrase) {
            score += 0.5f
            reasons.add("Dating/romance spam phrase detected")
        }

        // Check for dating spam domains in URLs
        val urlPattern = Regex("""https?://[^\s]+|www\.[^\s]+|[a-zA-Z0-9][-a-zA-Z0-9]*\.(com|net|org|xyz|top|site|online|link|click)[^\s]*""", RegexOption.IGNORE_CASE)
        val urlsInMessage = urlPattern.findAll(body).map { it.value.lowercase() }.toList()
        for (url in urlsInMessage) {
            if (DATING_SPAM_DOMAINS.any { url.contains(it) }) {
                score += 0.6f  // High score for dating spam domains
                reasons.add("Suspicious dating/adult domain: $url")
                break
            }
        }

        // Combination: casual greeting + URL from unknown sender = high spam likelihood
        val casualGreetings = listOf("hey", "hi there", "hello there", "hii", "heyy", "yo ")
        val hasCasualGreeting = casualGreetings.any { lowerBody.startsWith(it) }
        if (hasCasualGreeting && urlsInMessage.isNotEmpty() && !isFromContact) {
            score += 0.4f
            reasons.add("Casual greeting + link from unknown sender")
        }

        // FINAL: Normalize score and return result
        // Clamp to 0-1 range and compare against threshold
        val confidence = score.coerceIn(0f, 1f)
        val isSpam = confidence >= threshold

        return SpamCheckResult(
            isSpam = isSpam,
            confidence = confidence,
            reasons = reasons
        )
    }

    // ==========================================
    // REGION: Helper Methods
    // ==========================================

    /**
     * Checks if a sender is in the trusted whitelist.
     *
     * Trusted senders include banks, payment services, and government agencies.
     * These should NEVER be marked as spam as they send important transaction alerts.
     *
     * @param senderAddress The sender's phone number or shortcode
     * @return True if sender is trusted, false otherwise
     */
    private fun isTrustedSender(senderAddress: String): Boolean {
        val upperSender = senderAddress.uppercase().trim()

        // Direct match in trusted list
        if (TRUSTED_BANK_SENDERS.any { upperSender.contains(it) }) {
            return true
        }

        // Check sender patterns (like XX-HDFCBANK)
        if (TRUSTED_SENDER_PATTERNS.any { it.matches(upperSender) }) {
            return true
        }

        // Check for format: XX-BANKNAME (common Indian bank SMS format)
        val bankPrefixPattern = Regex("""^[A-Z]{2}-([A-Z]+)$""")
        val match = bankPrefixPattern.find(upperSender)
        if (match != null) {
            val bankCode = match.groupValues[1]
            // Check if the extracted code matches any trusted sender
            if (TRUSTED_BANK_SENDERS.any { bankCode.contains(it) || it.contains(bankCode) }) {
                return true
            }
        }

        return false
    }

    /**
     * Simple boolean check for spam classification.
     *
     * Convenience method that wraps checkMessage() for quick filtering.
     * Uses default threshold of 0.5.
     *
     * @param body The message body text
     * @param senderAddress The sender's phone number or shortcode
     * @param isFromContact True if sender is in contacts
     * @return True if message is classified as spam
     */
    fun isSpam(body: String, senderAddress: String, isFromContact: Boolean = false): Boolean {
        return checkMessage(body, senderAddress, isFromContact).isSpam
    }

    /**
     * Gets a human-readable spam classification label for UI display.
     *
     * @param confidence Spam confidence score from 0.0 to 1.0
     * @return User-friendly label: "High confidence spam", "Likely spam", etc.
     */
    fun getSpamLabel(confidence: Float): String {
        return when {
            confidence >= 0.8f -> "High confidence spam"
            confidence >= 0.6f -> "Likely spam"
            confidence >= 0.5f -> "Possible spam"
            else -> "Not spam"
        }
    }
}
