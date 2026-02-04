/**
 * AIService.kt
 *
 * Local AI service for intelligent SMS analysis using enhanced pattern matching.
 * Provides spending analysis, transaction tracking, conversation insights, and
 * smart reply suggestions - all processed entirely on-device without external APIs.
 *
 * ## Architecture Overview
 *
 * ```
 * User Query --> AIService
 *                   |
 *                   +-- trySmsAnalysisQuery() --> Spending/Transaction/OTP queries
 *                   |
 *                   +-- generateGeneralResponse() --> Greetings, help, etc.
 *                   |
 *                   +-- parseTransactions() --> Financial data extraction
 * ```
 *
 * ## Key Features
 *
 * 1. **Spending Analysis**: Extracts transaction amounts, merchants, and spending patterns
 * 2. **Transaction Parsing**: Identifies debits vs credits using keyword analysis
 * 3. **OTP Detection**: Finds verification codes in messages
 * 4. **Conversation Summary**: Generates intelligent summaries with sentiment analysis
 * 5. **Smart Replies**: Suggests contextual reply options based on conversation
 *
 * ## Design Philosophy
 *
 * This service prioritizes:
 * - **Privacy**: All processing is local, no data leaves the device
 * - **Speed**: Pattern matching is fast, no network latency
 * - **Cost**: No API keys required, completely free to use
 * - **Reliability**: Works offline, no external dependencies
 *
 * ## Query Types Supported
 *
 * | Query Pattern           | Handler                    |
 * |-------------------------|----------------------------|
 * | "spent at Amazon"       | analyzeMerchantSpending()  |
 * | "how much did I spend"  | analyzeSpending()          |
 * | "show transactions"     | analyzeTransactions()      |
 * | "OTP codes"             | findOTPs()                 |
 * | "account balance"       | findBalanceInfo()          |
 * | "summarize messages"    | summarizeConversation()    |
 * | "money totals"          | analyzeCurrencyTotals()    |
 *
 * ## Currency Support
 *
 * Supports parsing amounts in multiple currencies:
 * - USD: $, USD (US Dollars)
 * - EUR: €, EUR (Euro)
 * - GBP: £, GBP (British Pound)
 * - JPY: ¥, JPY (Japanese Yen)
 * - INR: ₹, Rs., Rs, INR (Indian Rupees)
 * - CAD: CA$, CAD (Canadian Dollar)
 * - AUD: AU$, AUD (Australian Dollar)
 * - NZD: NZ$, NZD (New Zealand Dollar)
 * - CHF: CHF (Swiss Franc)
 * - SEK: SEK (Swedish Krona)
 * - NOK: NOK (Norwegian Krone)
 * - DKK: DKK (Danish Krone)
 *
 * @param context Android context for resources and preferences
 *
 * @see SpamFilter for spam detection logic
 * @see SmsMessage for message data structure
 */
package com.phoneintegration.app.ai

import android.content.Context
import android.util.Log
import com.phoneintegration.app.SmsMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

/**
 * Advanced AI Service for intelligent SMS analysis using enhanced pattern matching.
 * All processing is done locally - completely free and no API keys required.
 */
class AIService(private val context: Context) {

    // ==========================================
    // REGION: Constants and Configuration
    // ==========================================

    companion object {
        private const val TAG = "AIService"
    }

    /**
     * Known merchants with their common abbreviations and aliases.
     * Used to normalize merchant names when parsing transactions.
     */
    private val MERCHANT_ALIASES = mapOf(
        "amazon" to listOf("amazon", "amzn", "amazn", "amz"),
        "flipkart" to listOf("flipkart", "fkrt", "flip"),
        "walmart" to listOf("walmart", "wmt"),
        "uber" to listOf("uber"),
        "swiggy" to listOf("swiggy"),
        "zomato" to listOf("zomato"),
        "google" to listOf("google", "goog"),
        "apple" to listOf("apple", "itunes"),
        "netflix" to listOf("netflix"),
        "spotify" to listOf("spotify"),
        "doordash" to listOf("doordash"),
        "starbucks" to listOf("starbucks", "sbux"),
        "myntra" to listOf("myntra"),
        "bigbasket" to listOf("bigbasket", "bbsk"),
        "paytm" to listOf("paytm"),
        "phonepe" to listOf("phonepe"),
        "gpay" to listOf("gpay", "googlepay", "google pay"),
        "xfinity" to listOf("xfinity", "comcast"),
        "att" to listOf("at&t", "att"),
        "verizon" to listOf("verizon", "vzw"),
        "tmobile" to listOf("t-mobile", "tmobile"),
        "icici" to listOf("icici", "icicbank", "icicibank"),
        "hdfc" to listOf("hdfc", "hdfcbank"),
        "sbi" to listOf("sbi", "statebank", "state bank"),
        "axis" to listOf("axis", "axisbank"),
        "kotak" to listOf("kotak", "kotakbank"),
    )

    /**
     * Maps merchants/banks to their typical currency.
     * Used to correctly identify transaction currency based on merchant/sender.
     */
    private val MERCHANT_CURRENCY_MAP = mapOf(
        // Indian banks and services
        "icici" to "INR",
        "hdfc" to "INR",
        "sbi" to "INR",
        "axis" to "INR",
        "kotak" to "INR",
        "paytm" to "INR",
        "phonepe" to "INR",
        "gpay" to "INR",
        "googlepay" to "INR",
        "swiggy" to "INR",
        "zomato" to "INR",
        "flipkart" to "INR",
        "myntra" to "INR",
        "bigbasket" to "INR",

        // US services and banks
        "xfinity" to "USD",
        "comcast" to "USD",
        "att" to "USD",
        "verizon" to "USD",
        "tmobile" to "USD",
        "wellsfargo" to "USD",
        "wells fargo" to "USD",
        "chase" to "USD",
        "bofa" to "USD",
        "bank of america" to "USD",
        "citi" to "USD",
        "citibank" to "USD",
        "amex" to "USD",
        "american express" to "USD",
        "discover" to "USD",
        "capital one" to "USD",

        // International services (default USD)
        "amazon" to "USD",
        "uber" to "USD",
        "netflix" to "USD",
        "spotify" to "USD",
        "apple" to "USD",
        "google" to "USD",
        "walmart" to "USD",
        "doordash" to "USD",
        "starbucks" to "USD",
    )

    /**
     * Keywords that indicate a debit (money spent) transaction.
     * At least one of these must be present for a message to be considered a debit.
     */
    private val DEBIT_KEYWORDS = listOf(
        "debited", "spent", "paid", "charged", "purchase", "payment",
        "debit", "deducted", "txn", "transaction", "pos", "withdrawn"
    )

    /**
     * Keywords that indicate a credit (money received) transaction.
     * Messages containing these are excluded from spending analysis.
     */
    private val CREDIT_KEYWORDS = listOf(
        "credited", "received", "refund", "reversal", "cashback",
        "credit", "deposit", "deposited", "added", "bonus", "reward"
    )

    // ==========================================
    // REGION: Data Classes
    // ==========================================

    /**
     * Represents a parsed financial transaction from an SMS message.
     *
     * @property amount Transaction amount in the specified currency
     * @property currency Currency code (INR, USD)
     * @property merchant Extracted merchant name, if identified
     * @property date Timestamp of the transaction
     * @property messageBody Original message text for reference
     * @property isDebit True if money was spent, false if received
     */
    data class ParsedTransaction(
        val amount: Double,
        val currency: String,
        val merchant: String?,
        val date: Long,
        val messageBody: String,
        val isDebit: Boolean
    )

    /**
     * Context information extracted from recent messages in a conversation.
     * Used to generate more relevant reply suggestions.
     *
     * @property messagesSent Number of messages sent by the user
     * @property messagesReceived Number of messages received
     * @property topics Identified topics being discussed
     * @property sentiment Overall emotional tone of the conversation
     * @property urgency Detected urgency level
     */
    data class ConversationContext(
        val messagesSent: Int,
        val messagesReceived: Int,
        val topics: List<String>,
        val sentiment: Sentiment,
        val urgency: Urgency
    )

    /** Sentiment classification for conversation analysis */
    enum class Sentiment {
        POSITIVE,  // Happy, thankful, enthusiastic
        NEGATIVE,  // Upset, disappointed, concerned
        NEUTRAL    // Informational, neutral tone
    }

    /** Urgency level detected from message content */
    enum class Urgency {
        HIGH,   // Contains "urgent", "asap", "emergency", etc.
        MEDIUM, // Contains "today", "tomorrow", "soon"
        LOW     // No time pressure indicators
    }

    // ==========================================
    // REGION: Public API - Chat Interface
    // ==========================================

    /**
     * Main entry point for AI-powered chat queries.
     *
     * Analyzes the user's message and routes it to the appropriate handler:
     * - SMS analysis queries (spending, transactions, OTPs) go to specialized handlers
     * - General conversation (greetings, help) gets contextual responses
     *
     * @param userMessage The user's query or question
     * @param messages List of SMS messages to analyze
     * @param conversationHistory Previous chat exchanges for context
     * @return AI-generated response string
     */
    suspend fun chatWithAI(
        userMessage: String,
        messages: List<SmsMessage>,
        conversationHistory: List<Pair<String, String>> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Processing general AI query: $userMessage")

            val lowerMessage = userMessage.lowercase().trim()

            // First check if it's an SMS analysis query
            val smsAnalysisResponse = trySmsAnalysisQuery(userMessage, messages)
            if (smsAnalysisResponse != null) {
                return@withContext smsAnalysisResponse
            }

            // Handle general conversation
            return@withContext generateGeneralResponse(userMessage, messages, conversationHistory)
        } catch (e: Exception) {
            Log.e(TAG, "Error in AI chat", e)
            "⚠️ I'm having trouble processing that. Try asking about your SMS messages or spending analysis!"
        }
    }

    // ==========================================
    // REGION: Query Routing
    // ==========================================

    /**
     * Attempts to route the query to an SMS analysis handler.
     *
     * Checks for keywords that indicate the user wants to analyze their messages
     * (spending, transactions, OTPs, etc.) and routes to the appropriate handler.
     *
     * @param question The user's query
     * @param messages SMS messages to analyze
     * @return Response string if this is an SMS query, null otherwise
     */
    private fun trySmsAnalysisQuery(question: String, messages: List<SmsMessage>): String? {
        val lowerQuestion = question.lowercase()

        // Extract merchant if mentioned in the query
        val queryMerchant = extractMerchantFromQuery(lowerQuestion)

        return when {
            // Merchant-specific spending query (e.g., "Amazon spending", "spent at Amazon")
            queryMerchant != null && (lowerQuestion.contains("spend") || lowerQuestion.contains("spent") ||
                    lowerQuestion.contains("transaction") || lowerQuestion.contains("purchase")) -> {
                analyzeMerchantSpending(messages, queryMerchant, lowerQuestion)
            }
            // General spending query
            lowerQuestion.contains("spend") || lowerQuestion.contains("spent") -> {
                analyzeSpending(messages, lowerQuestion)
            }
            // Transaction listing
            lowerQuestion.contains("transaction") -> {
                analyzeTransactions(messages, lowerQuestion)
            }
            // OTP queries
            lowerQuestion.contains("otp") || lowerQuestion.contains("code") -> {
                findOTPs(messages, lowerQuestion)
            }
            // Balance queries
            lowerQuestion.contains("balance") || lowerQuestion.contains("account") -> {
                findBalanceInfo(messages)
            }
            // Shopping queries (without spending context)
            queryMerchant != null -> {
                findMerchantMessages(messages, queryMerchant)
            }
            lowerQuestion.contains("shop") || lowerQuestion.contains("order") ||
            lowerQuestion.contains("deliver") -> {
                findShoppingMessages(messages, lowerQuestion)
            }
            // Banking queries
            lowerQuestion.contains("bank") || lowerQuestion.contains("payment") -> {
                findBankingMessages(messages, lowerQuestion)
            }
            // Summary requests
            lowerQuestion.contains("summary") || lowerQuestion.contains("summarize") -> {
                runBlocking { summarizeConversation(messages) }
            }
            // Currency totals
            lowerQuestion.contains("money total") || lowerQuestion.contains("currency total") ||
            lowerQuestion.contains("money summary") || lowerQuestion.contains("financial summary") ||
            lowerQuestion.contains("show money") || lowerQuestion.contains("currency breakdown") -> {
                analyzeCurrencyTotals(messages)
            }
            else -> null // Not an SMS analysis query
        }
    }

    // ==========================================
    // REGION: Conversation Responses
    // ==========================================

    /**
     * Generates responses for general conversation queries (greetings, help, etc.).
     *
     * Handles common conversational patterns like:
     * - Greetings: "hi", "hello", "good morning"
     * - Status queries: "how are you"
     * - Thanks: "thank you", "thanks"
     * - Help requests: "what can you do"
     *
     * @param userMessage The user's message
     * @param messages Available SMS messages (for context)
     * @param conversationHistory Previous chat exchanges
     * @return Friendly, helpful response string
     */
    private fun generateGeneralResponse(
        userMessage: String,
        messages: List<SmsMessage>,
        conversationHistory: List<Pair<String, String>>
    ): String {
        val lowerMessage = userMessage.lowercase().trim()

        // Greetings
        if (lowerMessage.matches(Regex("(hi|hello|hey|good morning|good afternoon|good evening).*"))) {
            return when {
                lowerMessage.contains("morning") -> "Good morning! ☀️ How can I help you today?"
                lowerMessage.contains("afternoon") -> "Good afternoon! 🌤️ What can I assist you with?"
                lowerMessage.contains("evening") -> "Good evening! 🌙 How may I help you?"
                else -> "Hello! 👋 I'm your AI assistant. I can help you analyze your SMS messages, provide spending insights, or just chat. What would you like to know?"
            }
        }

        // How are you / status queries
        if (lowerMessage.contains("how are you") || lowerMessage.contains("how do you do")) {
            return "I'm doing great, thank you for asking! 🤖 I'm here to help you make sense of your SMS messages and provide useful insights. What would you like to explore?"
        }

        // Thanks
        if (lowerMessage.matches(Regex(".*(thank|thanks|thx|ty).*"))) {
            return "You're very welcome! 😊 Is there anything else I can help you with?"
        }

        // About queries
        if (lowerMessage.contains("what can you do") || lowerMessage.contains("help") || lowerMessage.contains("what do you do")) {
            return "I'm your smart SMS assistant! I can help you:\n\n" +
                    "💰 **Spending Analysis**\n" +
                    "• Track your expenses by merchant\n" +
                    "• Show spending patterns and trends\n" +
                    "• Analyze transactions and payments\n" +
                    "• Show currency totals across all messages\n\n" +
                    "📱 **SMS Insights**\n" +
                    "• Find OTP codes and verification messages\n" +
                    "• Summarize conversations\n" +
                    "• Extract banking and payment information\n\n" +
                    "💬 **Smart Chat**\n" +
                    "• Answer questions about your messages\n" +
                    "• Provide contextual suggestions\n" +
                    "• Help organize your SMS data\n\n" +
                    "Try asking: \"How much did I spend this month?\" or \"Show money totals\" or \"Summarize my messages\""
        }

        // Questions about capabilities
        if (lowerMessage.contains("can you") || lowerMessage.contains("do you")) {
            return "Yes! I'm designed to help you understand and analyze your SMS messages. I can:\n\n" +
                    "✅ Analyze spending patterns and transactions\n" +
                    "✅ Find OTP codes and security messages\n" +
                    "✅ Summarize conversations and extract key information\n" +
                    "✅ Identify merchants and categorize payments\n" +
                    "✅ Provide insights about your messaging habits\n\n" +
                    "What specific question do you have about your messages?"
        }

        // Default helpful response
        return "I'm here to help you analyze your SMS messages! 📱\n\n" +
                "Try asking me questions like:\n" +
                "• \"How much did I spend this month?\"\n" +
                "• \"Show my recent transactions\"\n" +
                "• \"Show money totals\" or \"Currency totals\"\n" +
                "• \"What OTPs did I receive?\"\n" +
                "• \"Summarize my Amazon orders\"\n" +
                "• \"What's my account balance?\"\n\n" +
                "Or just tell me what you're looking for in your messages! 💬"
    }

    /**
     * Legacy method for SMS analysis - kept for compatibility
     */
    suspend fun askWithContext(
        question: String,
        messages: List<SmsMessage>,
        conversationHistory: List<Pair<String, String>> = emptyList()
    ): String = chatWithAI(question, messages, conversationHistory)

    /**
     * Extract merchant name from query
     */
    private fun extractMerchantFromQuery(query: String): String? {
        for ((merchant, aliases) in MERCHANT_ALIASES) {
            if (aliases.any { query.contains(it) }) {
                return merchant
            }
        }
        return null
    }

    // ==========================================
    // REGION: Transaction Parsing
    // ==========================================

    /**
     * Detects the currency for a transaction based on merchant, message content, and sender.
     *
     * ## Detection Strategy (in order of priority):
     * 1. Check if merchant is in MERCHANT_CURRENCY_MAP
     * 2. Look for explicit currency symbols/codes in message ($, ₹, INR, USD)
     * 3. Check sender phone number pattern (Indian numbers start with +91 or are 10 digits)
     * 4. Default to USD for unrecognized patterns
     *
     * @param messageBody The SMS message text
     * @param merchant Extracted merchant name (if any)
     * @param senderAddress The phone number/short code of the sender
     * @return Currency code (INR, USD, etc.)
     */
    private fun detectCurrency(messageBody: String, merchant: String?, senderAddress: String): String {
        val bodyLower = messageBody.lowercase()

        // 1. Check merchant-specific currency mapping
        if (merchant != null) {
            val merchantLower = merchant.lowercase()
            val currency = MERCHANT_CURRENCY_MAP[merchantLower]
            if (currency != null) return currency

            // Also check if any key in the map is contained in merchant name
            for ((key, curr) in MERCHANT_CURRENCY_MAP) {
                if (merchantLower.contains(key) || bodyLower.contains(key)) {
                    return curr
                }
            }
        }

        // 2. Check for explicit currency indicators in message
        when {
            bodyLower.contains("₹") || bodyLower.contains("inr") ||
            bodyLower.contains("rs.") || bodyLower.contains("rs ") -> return "INR"
            bodyLower.contains("$") || bodyLower.contains("usd") -> return "USD"
            bodyLower.contains("€") || bodyLower.contains("eur") -> return "EUR"
            bodyLower.contains("£") || bodyLower.contains("gbp") -> return "GBP"
            bodyLower.contains("¥") || bodyLower.contains("jpy") -> return "JPY"
        }

        // 3. Check sender pattern for Indian numbers
        val cleanedSender = senderAddress.replace(Regex("[^0-9+]"), "")
        when {
            cleanedSender.startsWith("+91") -> return "INR"
            cleanedSender.startsWith("91") && cleanedSender.length > 10 -> return "INR"
            cleanedSender.length == 10 && !cleanedSender.startsWith("1") -> return "INR" // Likely Indian
            cleanedSender.startsWith("+1") || cleanedSender.startsWith("1") -> return "USD"
        }

        // 4. Check for Indian bank keywords
        if (bodyLower.contains("bank") && (bodyLower.contains("india") ||
            bodyLower.contains("mumbai") || bodyLower.contains("delhi"))) {
            return "INR"
        }

        // Default to USD for short codes and unknown patterns
        return "USD"
    }

    /**
     * Parses financial transactions from SMS messages using pattern matching.
     *
     * ## Parsing Strategy
     *
     * 1. Filter out credit/refund messages (we only want spending)
     * 2. Require at least one debit keyword for validation
     * 3. Extract amount using regex patterns for INR/USD
     * 4. Filter out invalid amounts (too small, too large, or reference numbers)
     * 5. Extract merchant name from common patterns
     *
     * ## Amount Validation
     *
     * - Must be > 0
     * - Must be < 10,000,000 (filters out reference numbers)
     * - Handles comma-separated thousands (e.g., 1,000.00)
     *
     * @param messages List of SMS messages to parse
     * @return List of parsed transactions, sorted by date descending
     */
    private fun parseTransactions(messages: List<SmsMessage>): List<ParsedTransaction> {
        val transactions = mutableListOf<ParsedTransaction>()

        // Amount patterns - must be preceded or followed by transaction context
        val amountPatterns = listOf(
            // INR patterns
            Regex("""(?:rs\.?|₹|inr)\s*([0-9,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE),
            // USD patterns
            Regex("""(?:\$|usd)\s*([0-9,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE),
            // Amount followed by INR
            Regex("""([0-9,]+(?:\.\d{1,2})?)\s*(?:rs\.?|₹|inr)""", RegexOption.IGNORE_CASE),
        )

        for (msg in messages) {
            val bodyLower = msg.body.lowercase()

            // Skip if it's a credit/refund message
            if (CREDIT_KEYWORDS.any { bodyLower.contains(it) }) {
                continue
            }

            // Must have at least one debit keyword
            val hasDebitKeyword = DEBIT_KEYWORDS.any { bodyLower.contains(it) }
            if (!hasDebitKeyword) {
                continue
            }

            // Extract merchant first (needed for currency detection)
            val merchant = extractMerchantFromMessage(msg.body)

            // Extract amount
            var amount: Double? = null

            for (pattern in amountPatterns) {
                val match = pattern.find(msg.body)
                if (match != null) {
                    val amountStr = match.groupValues[1].replace(",", "")
                    amount = amountStr.toDoubleOrNull()
                    if (amount != null) {
                        break
                    }
                }
            }

            // Skip if no valid amount found or amount is unreasonably large (likely a reference number)
            if (amount == null || amount <= 0 || amount > 10000000) {
                continue
            }

            // Detect currency based on merchant, message content, and sender
            val currency = detectCurrency(msg.body, merchant, msg.address)

            transactions.add(
                ParsedTransaction(
                    amount = amount,
                    currency = currency,
                    merchant = merchant,
                    date = msg.date,
                    messageBody = msg.body,
                    isDebit = true
                )
            )
        }

        return transactions.sortedByDescending { it.date }
    }

    /**
     * Extracts merchant name from a transaction message body.
     *
     * Tries multiple strategies:
     * 1. Check against known merchant aliases (Amazon, Uber, etc.)
     * 2. Parse common patterns like "at [MERCHANT]", "to [MERCHANT]"
     * 3. Filter out false positives (common words like "your", "the")
     *
     * @param body The message body text
     * @return Extracted merchant name with proper capitalization, or null
     */
    private fun extractMerchantFromMessage(body: String): String? {
        val bodyLower = body.lowercase()

        // Check for known merchants
        for ((merchant, aliases) in MERCHANT_ALIASES) {
            if (aliases.any { bodyLower.contains(it) }) {
                return merchant.replaceFirstChar { it.uppercase() }
            }
        }

        // Try to extract from common patterns
        val merchantPatterns = listOf(
            Regex("""(?:at|to|from)\s+([A-Za-z][A-Za-z0-9\s&'./-]{2,25})(?:\s+(?:on|for|ref|card)|$)""", RegexOption.IGNORE_CASE),
            Regex("""(?:txn|transaction|purchase)\s+(?:at|on|to)\s+([A-Za-z][A-Za-z0-9\s&'./-]{2,25})""", RegexOption.IGNORE_CASE),
        )

        for (pattern in merchantPatterns) {
            val match = pattern.find(body)
            if (match != null) {
                val extracted = match.groupValues[1].trim()
                // Filter out common false positives
                val skipWords = listOf("your", "the", "a", "an", "card", "account", "bank", "ending")
                if (extracted.length in 2..25 && !skipWords.any { extracted.lowercase().startsWith(it) }) {
                    return extracted.replaceFirstChar { it.uppercase() }
                }
            }
        }

        return null
    }

    // ==========================================
    // REGION: Spending Analysis
    // ==========================================

    /**
     * Analyzes spending for a specific merchant.
     *
     * Filters transactions to those matching the specified merchant,
     * applies time filters if present in the query, and generates
     * a formatted spending report.
     *
     * @param messages SMS messages to analyze
     * @param merchant Merchant name to filter by
     * @param query Original query (for time filter extraction)
     * @return Formatted spending report for the merchant
     */
    private fun analyzeMerchantSpending(messages: List<SmsMessage>, merchant: String, query: String): String {
        val allTransactions = parseTransactions(messages)

        // Filter by merchant
        val merchantTransactions = allTransactions.filter { txn ->
            txn.merchant?.lowercase()?.contains(merchant.lowercase()) == true ||
            txn.messageBody.lowercase().contains(merchant.lowercase())
        }

        if (merchantTransactions.isEmpty()) {
            return "📊 No spending transactions found for ${merchant.replaceFirstChar { it.uppercase() }}.\n\n" +
                    "This could mean:\n" +
                    "• No ${merchant} purchases in your SMS history\n" +
                    "• Purchases were made via a different payment method\n" +
                    "• SMS notifications were not enabled"
        }

        // Apply time filter if specified
        val filteredTransactions = applyTimeFilter(merchantTransactions, query)

        val total = filteredTransactions.sumOf { it.amount }
        val currency = filteredTransactions.firstOrNull()?.currency ?: "INR"
        val currencySymbol = if (currency == "USD") "$" else "₹"

        val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)
        val periodLabel = getTimePeriodLabel(query)

        return buildString {
            append("💳 ${merchant.replaceFirstChar { it.uppercase() }} Spending")
            if (periodLabel.isNotEmpty()) {
                append(" ($periodLabel)")
            }
            append("\n\n")
            append("Total: $currencySymbol${String.format("%,.2f", total)}\n")
            append("Transactions: ${filteredTransactions.size}\n\n")

            if (filteredTransactions.isNotEmpty()) {
                append("📝 Details:\n")
                filteredTransactions.take(10).forEachIndexed { index, txn ->
                    append("${index + 1}. $currencySymbol${String.format("%,.2f", txn.amount)} — ${dateFormat.format(Date(txn.date))}\n")
                    append("   ${txn.messageBody.take(70).replace("\n", " ")}...\n\n")
                }
            }
        }
    }

    /**
     * Analyzes overall spending patterns across all merchants.
     *
     * Generates a comprehensive spending report including:
     * - Total amount spent
     * - Transaction count
     * - Average transaction amount
     * - Top spending categories/merchants
     * - Recent transactions list
     *
     * @param messages SMS messages to analyze
     * @param query Original query (for time filter extraction)
     * @return Formatted spending analysis report
     */
    private fun analyzeSpending(messages: List<SmsMessage>, query: String): String {
        val allTransactions = parseTransactions(messages)

        if (allTransactions.isEmpty()) {
            return "📊 No spending transactions found in your messages.\n\n" +
                    "Make sure you have SMS notifications enabled for your bank/payment apps."
        }

        // Apply time filter if specified
        val filteredTransactions = applyTimeFilter(allTransactions, query)

        val total = filteredTransactions.sumOf { it.amount }
        val currency = filteredTransactions.firstOrNull()?.currency ?: "INR"
        val currencySymbol = if (currency == "USD") "$" else "₹"
        val average = if (filteredTransactions.isNotEmpty()) total / filteredTransactions.size else 0.0

        val dateFormat = SimpleDateFormat("MMM dd", Locale.US)
        val periodLabel = getTimePeriodLabel(query)

        // Group by merchant for top spending
        val merchantTotals = filteredTransactions
            .groupBy { it.merchant ?: "Unknown" }
            .mapValues { it.value.sumOf { txn -> txn.amount } }
            .entries
            .sortedByDescending { it.value }
            .take(5)

        return buildString {
            append("💰 Spending Analysis")
            if (periodLabel.isNotEmpty()) {
                append(" ($periodLabel)")
            }
            append("\n\n")
            append("Total Spent: $currencySymbol${String.format("%,.2f", total)}\n")
            append("Transactions: ${filteredTransactions.size}\n")
            append("Average: $currencySymbol${String.format("%,.2f", average)}\n\n")

            if (merchantTotals.isNotEmpty()) {
                append("🏪 Top Merchants:\n")
                merchantTotals.forEachIndexed { index, (merchant, amount) ->
                    append("${index + 1}. $merchant: $currencySymbol${String.format("%,.2f", amount)}\n")
                }
                append("\n")
            }

            append("📝 Recent Transactions:\n")
            filteredTransactions.take(5).forEach { txn ->
                append("• ${dateFormat.format(Date(txn.date))}: $currencySymbol${String.format("%,.2f", txn.amount)}")
                txn.merchant?.let { append(" at $it") }
                append("\n")
            }
        }
    }

    // ==========================================
    // REGION: Time Filtering
    // ==========================================

    /**
     * Applies a time-based filter to transactions based on query keywords.
     *
     * Supports the following time ranges:
     * - "today": Current calendar day
     * - "week" / "7 days": Last 7 days
     * - "month" / "30 days": Current calendar month
     * - "year": Current calendar year
     *
     * @param transactions List of transactions to filter
     * @param query User query containing time keywords
     * @return Filtered transactions within the specified time range
     */
    private fun applyTimeFilter(transactions: List<ParsedTransaction>, query: String): List<ParsedTransaction> {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()

        return when {
            query.contains("today") -> {
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                transactions.filter { it.date >= cal.timeInMillis }
            }
            query.contains("week") || query.contains("7 day") -> {
                transactions.filter { it.date >= now - 7L * 24 * 3600 * 1000 }
            }
            query.contains("month") || query.contains("30 day") -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                transactions.filter { it.date >= cal.timeInMillis }
            }
            query.contains("year") -> {
                cal.set(Calendar.DAY_OF_YEAR, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                transactions.filter { it.date >= cal.timeInMillis }
            }
            else -> transactions
        }
    }

    /**
     * Get time period label for display
     */
    private fun getTimePeriodLabel(query: String): String {
        return when {
            query.contains("today") -> "Today"
            query.contains("week") || query.contains("7 day") -> "This Week"
            query.contains("month") || query.contains("30 day") -> "This Month"
            query.contains("year") -> "This Year"
            else -> ""
        }
    }

    /**
     * Find all messages from a specific merchant
     */
    private fun findMerchantMessages(messages: List<SmsMessage>, merchant: String): String {
        val merchantMessages = messages.filter { msg ->
            msg.body.lowercase().contains(merchant.lowercase())
        }

        if (merchantMessages.isEmpty()) {
            return "📱 No messages found from ${merchant.replaceFirstChar { it.uppercase() }}."
        }

        val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.US)
        val recent = merchantMessages.take(10).joinToString("\n\n") {
            "${dateFormat.format(Date(it.date))}\n${it.body.take(120)}..."
        }

        return "📱 ${merchant.replaceFirstChar { it.uppercase() }} Messages (${merchantMessages.size} total):\n\n$recent"
    }

    /**
     * Analyze transactions
     */
    private fun analyzeTransactions(messages: List<SmsMessage>, query: String): String {
        val transactionMessages = messages.filter {
            it.body.contains(Regex("(debited|credited|transaction|payment)", RegexOption.IGNORE_CASE))
        }

        if (transactionMessages.isEmpty()) {
            return "📝 No transactions found."
        }

        val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.US)
        val transactions = transactionMessages.take(10).joinToString("\n\n") {
            "${dateFormat.format(Date(it.date))}\nFrom: ${it.address}\n${it.body.take(100)}"
        }

        return "📝 Recent Transactions (${transactionMessages.size} total):\n\n$transactions"
    }

    /**
     * Find OTP messages
     */
    private fun findOTPs(messages: List<SmsMessage>, query: String): String {
        val otpMessages = messages.filter {
            it.body.matches(Regex(".*\\b\\d{4,6}\\b.*")) &&
            it.body.contains(Regex("(otp|code|verification|verify|pin)", RegexOption.IGNORE_CASE))
        }

        if (otpMessages.isEmpty()) {
            return "🔐 No OTP messages found."
        }

        val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.US)
        val otps = otpMessages.take(10).joinToString("\n\n") {
            val otpMatch = Regex("\\b(\\d{4,6})\\b").find(it.body)
            val otp = otpMatch?.value ?: "N/A"
            "${dateFormat.format(Date(it.date))}\nOTP: $otp\nFrom: ${it.address}"
        }

        return "🔐 Recent OTPs (${otpMessages.size} total):\n\n$otps"
    }

    /**
     * Find balance information
     */
    private fun findBalanceInfo(messages: List<SmsMessage>): String {
        val balanceMessages = messages.filter {
            it.body.contains(Regex("(balance|available balance|a/c bal)", RegexOption.IGNORE_CASE))
        }

        if (balanceMessages.isEmpty()) {
            return "💳 No balance information found."
        }

        val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.US)
        val balances = balanceMessages.take(5).joinToString("\n\n") {
            "${dateFormat.format(Date(it.date))}\n${it.body.take(150)}"
        }

        return "💳 Account Balance Information:\n\n$balances"
    }

    /**
     * Find shopping-related messages
     */
    private fun findShoppingMessages(messages: List<SmsMessage>, query: String): String {
        val keywords = listOf("amazon", "flipkart", "myntra", "order", "delivered", "shipped")
        val shoppingMessages = messages.filter { msg ->
            keywords.any { keyword ->
                msg.body.contains(keyword, ignoreCase = true)
            }
        }

        if (shoppingMessages.isEmpty()) {
            return "🛍️ No shopping-related messages found."
        }

        val dateFormat = SimpleDateFormat("MMM dd", Locale.US)
        val orders = shoppingMessages.take(10).joinToString("\n\n") {
            "${dateFormat.format(Date(it.date))}\n${it.body.take(100)}..."
        }

        return "🛍️ Shopping Updates (${shoppingMessages.size} total):\n\n$orders"
    }

    /**
     * Find banking messages
     */
    private fun findBankingMessages(messages: List<SmsMessage>, query: String): String {
        val bankingMessages = messages.filter {
            it.body.contains(Regex("(bank|hdfc|icici|sbi|axis|kotak|payment|transfer|upi)", RegexOption.IGNORE_CASE))
        }

        if (bankingMessages.isEmpty()) {
            return "🏦 No banking messages found."
        }

        val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.US)
        val banking = bankingMessages.take(10).joinToString("\n\n") {
            "${dateFormat.format(Date(it.date))}\n${it.body.take(120)}"
        }

        return "🏦 Banking Messages (${bankingMessages.size} total):\n\n$banking"
    }

    /**
     * Analyzes all messages to extract and total currency amounts
     *
     * Scans ALL messages (not just bank SMS) for currency symbols and amounts,
     * groups by currency type, and displays totals per currency.
     *
     * Supported currencies:
     * - $ (USD), € (EUR), £ (GBP), ¥ (JPY), ₹ (INR)
     * - CA$ (CAD), AU$ (AUD), NZ$ (NZD)
     * - CHF, SEK, NOK, DKK (text-based)
     *
     * @param messages List of SMS messages to analyze
     * @return Formatted string with currency totals breakdown
     */
    private fun analyzeCurrencyTotals(messages: List<SmsMessage>): String {
        val currencyTotals = mutableMapOf<String, Double>()

        // Currency patterns with symbol and text detection
        val currencyPatterns = mapOf(
            "USD" to listOf(
                Regex("""\$\s*([0-9,]+(?:\.\d{1,2})?)"""),
                Regex("""([0-9,]+(?:\.\d{1,2})?)\s*USD""", RegexOption.IGNORE_CASE)
            ),
            "EUR" to listOf(
                Regex("""€\s*([0-9,]+(?:\.\d{1,2})?)"""),
                Regex("""([0-9,]+(?:\.\d{1,2})?)\s*EUR""", RegexOption.IGNORE_CASE)
            ),
            "GBP" to listOf(
                Regex("""£\s*([0-9,]+(?:\.\d{1,2})?)"""),
                Regex("""([0-9,]+(?:\.\d{1,2})?)\s*GBP""", RegexOption.IGNORE_CASE)
            ),
            "JPY" to listOf(
                Regex("""¥\s*([0-9,]+(?:\.\d{1,2})?)"""),
                Regex("""([0-9,]+(?:\.\d{1,2})?)\s*JPY""", RegexOption.IGNORE_CASE)
            ),
            "INR" to listOf(
                Regex("""₹\s*([0-9,]+(?:\.\d{1,2})?)"""),
                Regex("""(?:Rs\.?|INR)\s*([0-9,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE),
                Regex("""([0-9,]+(?:\.\d{1,2})?)\s*(?:Rs\.?|INR)""", RegexOption.IGNORE_CASE)
            ),
            "CAD" to listOf(
                Regex("""CA\$\s*([0-9,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE),
                Regex("""([0-9,]+(?:\.\d{1,2})?)\s*CAD""", RegexOption.IGNORE_CASE)
            ),
            "AUD" to listOf(
                Regex("""AU\$\s*([0-9,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE),
                Regex("""([0-9,]+(?:\.\d{1,2})?)\s*AUD""", RegexOption.IGNORE_CASE)
            ),
            "NZD" to listOf(
                Regex("""NZ\$\s*([0-9,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE),
                Regex("""([0-9,]+(?:\.\d{1,2})?)\s*NZD""", RegexOption.IGNORE_CASE)
            ),
            "CHF" to listOf(
                Regex("""([0-9,]+(?:\.\d{1,2})?)\s*CHF""", RegexOption.IGNORE_CASE),
                Regex("""CHF\s*([0-9,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)
            ),
            "SEK" to listOf(
                Regex("""([0-9,]+(?:\.\d{1,2})?)\s*SEK""", RegexOption.IGNORE_CASE),
                Regex("""SEK\s*([0-9,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)
            ),
            "NOK" to listOf(
                Regex("""([0-9,]+(?:\.\d{1,2})?)\s*NOK""", RegexOption.IGNORE_CASE),
                Regex("""NOK\s*([0-9,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)
            ),
            "DKK" to listOf(
                Regex("""([0-9,]+(?:\.\d{1,2})?)\s*DKK""", RegexOption.IGNORE_CASE),
                Regex("""DKK\s*([0-9,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)
            )
        )

        // Scan all messages for currency mentions
        for (msg in messages) {
            for ((currency, patterns) in currencyPatterns) {
                for (pattern in patterns) {
                    val matches = pattern.findAll(msg.body)
                    for (match in matches) {
                        val amountStr = match.groupValues[1].replace(",", "")
                        val amount = amountStr.toDoubleOrNull()

                        // Validate amount (positive, reasonable range)
                        if (amount != null && amount > 0 && amount < 10_000_000) {
                            currencyTotals[currency] = currencyTotals.getOrDefault(currency, 0.0) + amount
                        }
                    }
                }
            }
        }

        if (currencyTotals.isEmpty()) {
            return "💰 Currency Totals\n\n" +
                   "No currency amounts found in your messages.\n\n" +
                   "I can detect amounts in:\n" +
                   "• $, €, £, ¥, ₹\n" +
                   "• CA$, AU$, NZ$\n" +
                   "• CHF, SEK, NOK, DKK"
        }

        // Build formatted response
        val sortedTotals = currencyTotals.entries.sortedByDescending { it.value }

        val response = buildString {
            append("💰 Currency Totals\n\n")
            append("Found amounts in ${sortedTotals.size} currency/currencies:\n\n")

            for ((currency, total) in sortedTotals) {
                val symbol = getCurrencySymbol(currency)
                append("$symbol${String.format("%,.2f", total)} $currency\n")
            }

            append("\n📊 Scanned ${messages.size} messages")
        }

        return response
    }

    /**
     * Get display symbol for currency code
     */
    private fun getCurrencySymbol(currencyCode: String): String {
        return when (currencyCode) {
            "USD" -> "$"
            "EUR" -> "€"
            "GBP" -> "£"
            "JPY" -> "¥"
            "INR" -> "₹"
            "CAD" -> "CA$"
            "AUD" -> "AU$"
            "NZD" -> "NZ$"
            "CHF" -> "CHF "
            "SEK" -> "SEK "
            "NOK" -> "NOK "
            "DKK" -> "DKK "
            else -> ""
        }
    }

    // ==========================================
    // REGION: Conversation Summarization
    // ==========================================

    /**
     * Generates an intelligent summary of a conversation.
     *
     * Analyzes the messages to produce insights including:
     * - Message count and direction (sent vs received)
     * - Detected topics and themes
     * - Sentiment analysis (positive/negative/neutral)
     * - Activity pattern (very active, moderate, slow)
     * - Time period covered
     * - Recent message highlights
     * - Sentiment trend (improving/worsening)
     *
     * @param messages List of messages in the conversation
     * @return Formatted summary with emoji indicators
     */
    suspend fun summarizeConversation(messages: List<SmsMessage>): String = withContext(Dispatchers.IO) {
        if (messages.isEmpty()) return@withContext "No messages to summarize."

        val messageCount = messages.size
        val sentCount = messages.count { it.type == 2 }
        val receivedCount = messages.count { it.type == 1 }

        val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)
        val firstDate = dateFormat.format(Date(messages.minOf { it.date }))
        val lastDate = dateFormat.format(Date(messages.maxOf { it.date }))

        val context = analyzeConversationContext(messages.takeLast(10))
        val keyTopics = extractKeyTopics(messages)
        val activityPattern = analyzeActivityPattern(messages)
        val sentimentTrend = analyzeSentimentTrend(messages)

        return@withContext buildString {
            append("🧠 Smart Summary\n\n")

            // Key insights
            when {
                context.sentiment == Sentiment.POSITIVE && context.urgency == Urgency.LOW -> {
                    append("✨ This appears to be a friendly, casual conversation\n")
                }
                context.sentiment == Sentiment.NEGATIVE -> {
                    append("🤝 This conversation involves some concerns or issues\n")
                }
                context.urgency == Urgency.HIGH -> {
                    append("⚡ This conversation has time-sensitive topics\n")
                }
            }

            // Topics
            if (keyTopics.isNotEmpty()) {
                append("📋 Main Topics: ${keyTopics.joinToString(", ")}\n")
            }

            // Activity pattern
            append("📊 Activity: $messageCount messages ")
            append("($sentCount sent, $receivedCount received)\n")

            when (activityPattern) {
                "very_active" -> append("🔥 Very active conversation\n")
                "active" -> append("💬 Active conversation\n")
                "moderate" -> append("📝 Moderate activity\n")
                "slow" -> append("🐌 Slow conversation\n")
            }

            // Time period
            append("📅 Period: $firstDate to $lastDate\n\n")

            // Recent highlights
            append("💭 Recent Highlights:\n")
            messages.takeLast(3).forEachIndexed { index, msg ->
                val sender = if (msg.type == 2) "You" else "Contact"
                append("${index + 1}. $sender: ${msg.body.take(60)}${if (msg.body.length > 60) "..." else ""}\n")
            }

            // Sentiment insight
            when (sentimentTrend) {
                "improving" -> append("\n📈 Conversation tone is improving")
                "worsening" -> append("\n📉 Conversation tone has become more serious")
                "positive" -> append("\n😊 Generally positive conversation")
                "mixed" -> append("\n⚖️ Mixed sentiments throughout")
            }
        }
    }

    /**
     * Extract key topics from the entire conversation
     */
    private fun extractKeyTopics(messages: List<SmsMessage>): List<String> {
        val topics = mutableMapOf<String, Int>()
        messages.forEach { msg ->
            val foundTopics = extractTopics(listOf(msg))
            foundTopics.forEach { topic ->
                topics[topic] = topics.getOrDefault(topic, 0) + 1
            }
        }
        return topics.entries.sortedByDescending { it.value }.take(3).map { it.key }
    }

    /**
     * Analyze activity pattern
     */
    private fun analyzeActivityPattern(messages: List<SmsMessage>): String {
        if (messages.size < 5) return "minimal"

        val timeSpan = messages.maxOf { it.date } - messages.minOf { it.date }
        val days = timeSpan / (24 * 60 * 60 * 1000.0)
        val messagesPerDay = if (days > 0) messages.size / days else messages.size.toDouble()

        return when {
            messagesPerDay >= 10 -> "very_active"
            messagesPerDay >= 5 -> "active"
            messagesPerDay >= 2 -> "moderate"
            else -> "slow"
        }
    }

    /**
     * Analyze sentiment trend over time
     */
    private fun analyzeSentimentTrend(messages: List<SmsMessage>): String {
        if (messages.size < 3) return "neutral"

        val recent = messages.takeLast(messages.size / 2)
        val earlier = messages.take(messages.size / 2)

        val recentSentiment = analyzeSentiment(recent)
        val earlierSentiment = analyzeSentiment(earlier)

        return when {
            recentSentiment == Sentiment.POSITIVE && earlierSentiment != Sentiment.POSITIVE -> "improving"
            recentSentiment == Sentiment.NEGATIVE && earlierSentiment != Sentiment.NEGATIVE -> "worsening"
            recentSentiment == Sentiment.POSITIVE -> "positive"
            recentSentiment == Sentiment.NEGATIVE -> "negative"
            else -> "mixed"
        }
    }

    // ==========================================
    // REGION: Smart Reply Suggestions
    // ==========================================

    /**
     * Generates contextual reply suggestions based on conversation history.
     *
     * Analyzes the last few messages to understand context and sentiment,
     * then generates appropriate reply options that match the conversation tone.
     *
     * @param messages Recent messages in the conversation
     * @param context Additional context string (optional)
     * @param count Number of suggestions to generate (default 3)
     * @return List of suggested reply strings
     */
    suspend fun generateMessageSuggestions(
        messages: List<SmsMessage>,
        context: String = "",
        count: Int = 3
    ): List<String> = withContext(Dispatchers.IO) {
        if (messages.isEmpty()) return@withContext emptyList()

        val lastMessage = messages.last()
        val conversationContext = analyzeConversationContext(messages.takeLast(5))

        return@withContext generateContextualReplies(lastMessage.body, conversationContext, count)
    }

    // ==========================================
    // REGION: Conversation Analysis Helpers
    // ==========================================

    /**
     * Analyzes recent messages to extract conversation context.
     *
     * Examines the last few messages to determine:
     * - Message direction balance (who's talking more)
     * - Topics being discussed
     * - Overall sentiment
     * - Urgency level
     *
     * @param recentMessages Last few messages to analyze
     * @return ConversationContext with extracted insights
     */
    private fun analyzeConversationContext(recentMessages: List<SmsMessage>): ConversationContext {
        val sent = recentMessages.count { it.type == 2 }
        val received = recentMessages.count { it.type == 1 }

        val topics = extractTopics(recentMessages)
        val sentiment = analyzeSentiment(recentMessages)
        val urgency = detectUrgency(recentMessages.lastOrNull()?.body ?: "")

        return ConversationContext(sent, received, topics, sentiment, urgency)
    }

    /**
     * Generates contextual reply suggestions based on the last message and conversation context.
     *
     * Uses pattern matching to identify message types:
     * - Questions: Offers yes/no and clarification responses
     * - Gratitude: Offers "you're welcome" variations
     * - Apologies: Offers reassuring responses
     * - Greetings: Offers friendly greetings
     * - Scheduling: Offers availability responses
     * - Work-related: Offers professional acknowledgments
     *
     * Results are filtered based on conversation sentiment to ensure appropriate tone.
     *
     * @param message The last message received
     * @param context Conversation context for tone matching
     * @param count Number of suggestions to return
     * @return List of contextually appropriate reply suggestions
     */
    private fun generateContextualReplies(message: String, context: ConversationContext, count: Int): List<String> {
        val lowerMessage = message.lowercase()

        // Enhanced reply patterns based on context
        val suggestions = mutableListOf<String>()

        when {
            // Questions
            lowerMessage.contains("?") -> {
                suggestions.addAll(listOf(
                    "Yes, definitely!",
                    "Let me check that for you",
                    "I'm not sure, can you clarify?",
                    "That sounds good to me",
                    "I'll get back to you on that"
                ))
            }

            // Gratitude
            lowerMessage.contains("thank") -> {
                suggestions.addAll(listOf(
                    "You're welcome! 😊",
                    "Happy to help!",
                    "No problem at all",
                    "Anytime!",
                    "Glad I could assist"
                ))
            }

            // Apologies
            lowerMessage.contains("sorry") -> {
                suggestions.addAll(listOf(
                    "No worries at all",
                    "It's completely fine",
                    "Don't worry about it",
                    "All good!",
                    "No problem 😊"
                ))
            }

            // Time-sensitive (morning/afternoon/evening)
            lowerMessage.contains("morning") || lowerMessage.contains("good morning") -> {
                suggestions.addAll(listOf(
                    "Good morning! Hope you have a great day",
                    "Morning! How are you today?",
                    "Good morning! Ready for the day?",
                    "Hey there! Good morning to you too"
                ))
            }

            // Plans and scheduling
            lowerMessage.contains("meet") || lowerMessage.contains("see") || lowerMessage.contains("call") -> {
                suggestions.addAll(listOf(
                    "Sounds good! What time works for you?",
                    "I'm available. Let me know when",
                    "Perfect! Looking forward to it",
                    "Sure thing! Just let me know the details"
                ))
            }

            // Work-related
            lowerMessage.contains("work") || lowerMessage.contains("meeting") || lowerMessage.contains("project") -> {
                suggestions.addAll(listOf(
                    "Got it, I'll take care of that",
                    "Understood. I'll follow up",
                    "Thanks for the update",
                    "I'll get right on it"
                ))
            }

            // Default conversational responses
            else -> {
                val defaultReplies = listOf(
                    "Thanks for letting me know",
                    "Got it, thanks!",
                    "Okay, noted",
                    "Sounds good",
                    "Thanks for the info",
                    "I'll keep that in mind",
                    "Okay, perfect",
                    "Thanks! 🙏",
                    "Alright, got it",
                    "Thank you!"
                )
                suggestions.addAll(defaultReplies)
            }
        }

        // Filter based on conversation context
        val filtered = suggestions.filter { reply ->
            when (context.sentiment) {
                Sentiment.POSITIVE -> reply.contains("great") || reply.contains("good") || reply.contains("😊")
                Sentiment.NEGATIVE -> reply.contains("sorry") || reply.contains("worry")
                else -> true
            }
        }

        return filtered.distinct().take(count)
    }

    // ==========================================
    // REGION: Sentiment and Topic Analysis
    // ==========================================

    /**
     * Extracts topics from messages using keyword matching.
     *
     * Categories detected:
     * - work: meeting, project, office, deadline
     * - personal: family, friend, home, weekend
     * - shopping: buy, purchase, order, delivery
     * - travel: flight, trip, vacation, hotel
     * - health: doctor, appointment, medicine
     * - finance: money, payment, bank, bill
     *
     * @param messages Messages to analyze for topics
     * @return List of detected topic categories
     */
    private fun extractTopics(messages: List<SmsMessage>): List<String> {
        val topics = mutableSetOf<String>()
        val text = messages.joinToString(" ") { it.body.lowercase() }

        val topicKeywords = mapOf(
            "work" to listOf("meeting", "project", "work", "office", "deadline", "task"),
            "personal" to listOf("family", "friend", "home", "weekend", "party", "dinner"),
            "shopping" to listOf("buy", "purchase", "shopping", "store", "order", "delivery"),
            "travel" to listOf("flight", "travel", "trip", "vacation", "hotel", "booking"),
            "health" to listOf("doctor", "appointment", "medicine", "health", "sick"),
            "finance" to listOf("money", "payment", "bank", "account", "bill", "budget")
        )

        topicKeywords.forEach { (topic, keywords) ->
            if (keywords.any { text.contains(it) }) {
                topics.add(topic)
            }
        }

        return topics.toList()
    }

    /**
     * Analyzes the overall sentiment of a set of messages.
     *
     * Uses simple keyword counting:
     * - Positive words: good, great, excellent, awesome, love, thanks
     * - Negative words: bad, terrible, awful, hate, sorry, problem
     *
     * Returns POSITIVE if more positive words than negative,
     * NEGATIVE if more negative words, NEUTRAL otherwise.
     *
     * @param messages Messages to analyze
     * @return Overall sentiment classification
     */
    private fun analyzeSentiment(messages: List<SmsMessage>): Sentiment {
        val text = messages.joinToString(" ") { it.body.lowercase() }

        val positiveWords = listOf("good", "great", "excellent", "awesome", "amazing", "perfect",
                                 "happy", "love", "wonderful", "fantastic", "nice", "thanks", "thank")
        val negativeWords = listOf("bad", "terrible", "awful", "horrible", "sad", "angry",
                                 "hate", "worst", "disappointed", "sorry", "problem", "issue")

        val positiveCount = positiveWords.count { text.contains(it) }
        val negativeCount = negativeWords.count { text.contains(it) }

        return when {
            positiveCount > negativeCount -> Sentiment.POSITIVE
            negativeCount > positiveCount -> Sentiment.NEGATIVE
            else -> Sentiment.NEUTRAL
        }
    }

    /**
     * Detects urgency level from a message.
     *
     * HIGH urgency keywords: urgent, asap, emergency, important, critical, immediately
     * MEDIUM urgency keywords: today, tomorrow, this week, soon
     *
     * @param message Message text to analyze
     * @return Detected urgency level
     */
    private fun detectUrgency(message: String): Urgency {
        val lowerMessage = message.lowercase()

        val urgentKeywords = listOf("urgent", "asap", "emergency", "important", "critical",
                                  "immediately", "right now", "soon", "quickly", "deadline")

        val mediumKeywords = listOf("today", "tomorrow", "this week", "soon", "when you can")

        return when {
            urgentKeywords.any { lowerMessage.contains(it) } -> Urgency.HIGH
            mediumKeywords.any { lowerMessage.contains(it) } -> Urgency.MEDIUM
            else -> Urgency.LOW
        }
    }


}
