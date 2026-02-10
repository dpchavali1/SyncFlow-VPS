import Foundation
import Combine

class AIAssistantService: ObservableObject {
    static let shared = AIAssistantService()

    // MARK: - Merchant Recognition

    private let merchantAliases: [String: [String]] = [
        "Amazon": ["amazon", "amzn", "amazn", "amz"],
        "Walmart": ["walmart", "wmt"],
        "Target": ["target"],
        "Costco": ["costco"],
        "Uber": ["uber", "ubereats", "uber eats"],
        "Lyft": ["lyft"],
        "DoorDash": ["doordash", "door dash"],
        "Grubhub": ["grubhub"],
        "Starbucks": ["starbucks", "sbux"],
        "McDonald's": ["mcdonald", "mcdonalds"],
        "Netflix": ["netflix"],
        "Spotify": ["spotify"],
        "Apple": ["apple", "itunes", "app store", "apple.com"],
        "Google": ["google", "goog", "google play", "youtube premium"],
        "Venmo": ["venmo"],
        "PayPal": ["paypal"],
        "Zelle": ["zelle"],
        "Chase": ["chase"],
        "Bank of America": ["bank of america", "bofa", "boa"],
        "Wells Fargo": ["wells fargo", "wellsfargo"],
        "Capital One": ["capital one", "capitalone"],
        "American Express": ["amex", "american express"],
        "Discover": ["discover"],
        "Kroger": ["kroger"],
        "Whole Foods": ["whole foods", "wholefoods"],
        "CVS": ["cvs"],
        "Walgreens": ["walgreens"],
        "Home Depot": ["home depot", "homedepot"],
        "Lowe's": ["lowes", "lowe's"],
        "Best Buy": ["best buy", "bestbuy"],
        "Swiggy": ["swiggy"],
        "Zomato": ["zomato"],
        "Flipkart": ["flipkart", "fkrt"],
        "Paytm": ["paytm"],
        "PhonePe": ["phonepe"],
        "GPay": ["gpay", "google pay"],
        "Xfinity": ["xfinity", "comcast"],
        "AT&T": ["at&t", "att"],
        "Verizon": ["verizon", "vzw"],
        "T-Mobile": ["t-mobile", "tmobile"],
        "ICICI": ["icici", "icicbank", "icicibank"],
        "HDFC": ["hdfc", "hdfcbank"],
        "SBI": ["sbi", "state bank", "statebank"],
        "Axis": ["axis", "axisbank"],
        "Kotak": ["kotak", "kotakbank"],
    ]

    private let merchantCurrencyMap: [String: String] = [
        // Indian banks and services
        "icici": "INR",
        "hdfc": "INR",
        "sbi": "INR",
        "axis": "INR",
        "kotak": "INR",
        "paytm": "INR",
        "phonepe": "INR",
        "gpay": "INR",
        "google pay": "INR",
        "swiggy": "INR",
        "zomato": "INR",
        "flipkart": "INR",

        // US services and banks
        "xfinity": "USD",
        "comcast": "USD",
        "at&t": "USD",
        "att": "USD",
        "verizon": "USD",
        "tmobile": "USD",
        "t-mobile": "USD",
        "wells fargo": "USD",
        "wellsfargo": "USD",
        "chase": "USD",
        "bank of america": "USD",
        "bofa": "USD",
        "boa": "USD",
        "citi": "USD",
        "citibank": "USD",
        "amex": "USD",
        "american express": "USD",
        "discover": "USD",
        "capital one": "USD",
        "capitalone": "USD",

        // International services (default USD)
        "amazon": "USD",
        "uber": "USD",
        "netflix": "USD",
        "spotify": "USD",
        "apple": "USD",
        "google": "USD",
        "walmart": "USD",
        "doordash": "USD",
        "starbucks": "USD",
    ]

    // MARK: - Transaction Keywords

    private let debitKeywords = [
        "debited", "spent", "paid", "charged", "purchase", "payment",
        "debit", "deducted", "txn", "transaction", "pos", "withdrawn",
        "bought", "bill payment"
    ]

    private let creditKeywords = [
        "credited", "received", "refund", "reversal", "cashback",
        "credit", "deposit", "deposited", "added", "bonus", "reward"
    ]

    // MARK: - Category Keywords

    private let categoryKeywords: [TransactionCategory: [String]] = [
        .transport: ["uber", "lyft", "ola", "taxi", "bus", "metro", "cab", "ride"],
        .fuel: ["fuel", "gas", "shell", "bp", "chevron", "exxon", "petrol"],
        .groceries: ["kroger", "walmart", "costco", "aldi", "safeway", "trader joe", "grocery", "whole foods"],
        .shopping: ["amazon", "best buy", "target", "macy", "nordstrom", "shop", "flipkart"],
        .food: ["mcdonald", "starbucks", "restaurant", "dining", "doordash", "grubhub", "zomato", "swiggy", "coffee", "pizza", "ubereats"],
        .subscriptions: ["spotify", "netflix", "prime", "hulu", "disney", "subscription", "membership"],
        .travel: ["flight", "hotel", "airbnb", "expedia", "booking", "airline", "travel"],
        .entertainment: ["movie", "cinema", "theater", "concert", "tickets", "game"],
        .utilities: ["bill", "utility", "electricity", "water", "internet", "phone", "mobile"],
        .health: ["pharmacy", "walgreens", "cvs", "doctor", "hospital", "clinic", "medical"],
    ]

    // MARK: - Subscription Keywords

    private let subscriptionKeywords = [
        "netflix", "spotify", "hulu", "disney", "prime", "apple", "google",
        "youtube", "hbo", "paramount", "peacock", "audible", "dropbox",
        "icloud", "adobe", "microsoft", "gym", "fitness", "membership"
    ]

    // MARK: - Bill Keywords

    private let billKeywords = [
        "due", "payment due", "bill", "minimum payment", "pay by",
        "statement", "balance due", "amount due", "due date"
    ]

    // MARK: - Package Keywords

    private let packageKeywords = [
        "shipped", "dispatched", "out for delivery", "delivered",
        "delivery", "arriving", "tracking", "package", "order"
    ]

    private let carriers = ["fedex", "ups", "usps", "dhl", "amazon", "ontrac", "bluedart", "delhivery"]

    // MARK: - Main Query Processing

    func processQuery(_ query: String, messages: [Message]) -> AIResponse {
        let queryType = classifyQuery(query)

        switch queryType {
        case .spending(let merchant, let timeFilter):
            let analysis = analyzeSpending(messages: messages, merchant: merchant, timeFilter: timeFilter)
            return AIResponse(
                queryType: queryType,
                summary: "Found \(analysis.transactionCount) transactions totaling \(analysis.formattedTotal)",
                details: .spending(analysis),
                suggestedFollowUps: generateSpendingFollowUps(merchant: merchant, timeFilter: timeFilter)
            )

        case .upcomingBills:
            let bills = findUpcomingBills(messages: messages)
            let summary = bills.isEmpty ? "No upcoming bills found" : "Found \(bills.count) bill\(bills.count == 1 ? "" : "s")"
            return AIResponse(
                queryType: queryType,
                summary: summary,
                details: bills.isEmpty ? .noResults("No bill reminders found in your messages") : .bills(bills),
                suggestedFollowUps: ["Show my spending this month", "What's my account balance?"]
            )

        case .packageTracking:
            let packages = trackPackages(messages: messages)
            let summary = packages.isEmpty ? "No packages found" : "Found \(packages.count) package\(packages.count == 1 ? "" : "s")"
            return AIResponse(
                queryType: queryType,
                summary: summary,
                details: packages.isEmpty ? .noResults("No package tracking info found in your messages") : .packages(packages),
                suggestedFollowUps: ["Show my recent transactions", "Any upcoming bills?"]
            )

        case .balanceQuery:
            let balances = findBalanceInfo(messages: messages)
            let summary = balances.isEmpty ? "No balance info found" : "Found \(balances.count) account balance\(balances.count == 1 ? "" : "s")"
            return AIResponse(
                queryType: queryType,
                summary: summary,
                details: balances.isEmpty ? .noResults("No account balance information found in your messages") : .balances(balances),
                suggestedFollowUps: ["Show my spending this month", "Any upcoming bills?"]
            )

        case .otpFinding:
            let otps = extractOTPs(messages: messages)
            let summary = otps.isEmpty ? "No OTPs found" : "Found \(otps.count) OTP code\(otps.count == 1 ? "" : "s")"
            return AIResponse(
                queryType: queryType,
                summary: summary,
                details: otps.isEmpty ? .noResults("No OTP codes found in your recent messages") : .otps(otps),
                suggestedFollowUps: ["Show my transactions", "What's my account balance?"]
            )

        case .transactionList(let merchant, let timeFilter):
            let transactions = listTransactions(messages: messages, merchant: merchant, timeFilter: timeFilter)
            let summary = transactions.isEmpty ? "No transactions found" : "Found \(transactions.count) transaction\(transactions.count == 1 ? "" : "s")"
            return AIResponse(
                queryType: queryType,
                summary: summary,
                details: transactions.isEmpty ? .noResults("No transactions found matching your criteria") : .transactions(transactions),
                suggestedFollowUps: generateSpendingFollowUps(merchant: merchant, timeFilter: timeFilter)
            )

        case .subscriptions:
            let recurring = detectRecurringExpenses(messages: messages)
            let monthlyTotal = recurring.reduce(0.0) { sum, r in
                switch r.frequency {
                case .monthly: return sum + r.amount
                case .yearly: return sum + r.amount / 12
                case .weekly: return sum + r.amount * 4
                }
            }
            let currency = recurring.first?.currency ?? "USD"
            let symbol = currency == "INR" ? "₹" : "$"
            let summary = recurring.isEmpty ? "No subscriptions detected" : "Found \(recurring.count) subscription(s) totaling ~\(symbol)\(String(format: "%.2f", monthlyTotal))/month"
            return AIResponse(
                queryType: queryType,
                summary: summary,
                details: recurring.isEmpty ? .noResults("No recurring subscriptions detected in your messages") : .subscriptions(recurring),
                suggestedFollowUps: ["Show my spending this month", "Upcoming bills"]
            )

        case .summary:
            let digest = generateSmartDigest(messages: messages)
            return AIResponse(
                queryType: queryType,
                summary: "Your financial snapshot",
                details: .digest(digest),
                suggestedFollowUps: ["Show subscriptions", "Upcoming bills", "Track packages"]
            )

        case .spendingTrends:
            let thisMonth = analyzeSpending(messages: messages, merchant: nil, timeFilter: .thisMonth)
            let lastMonth = analyzeSpending(messages: messages, merchant: nil, timeFilter: .last30Days)
            let diff = thisMonth.total - lastMonth.total
            let trend = diff > 0 ? "increased" : "decreased"
            let summary = "Your spending has \(trend) by \(thisMonth.currency == "INR" ? "₹" : "$")\(String(format: "%.2f", abs(diff)))"
            return AIResponse(
                queryType: queryType,
                summary: summary,
                details: .trends(thisMonth, lastMonth),
                suggestedFollowUps: ["Show by category", "Top merchants", "Show subscriptions"]
            )

        case .currencyTotals:
            let totals = analyzeCurrencyTotals(messages: messages)
            let count = totals.count
            let summary = count == 0 ? "No currency amounts found" : "Found amounts in \(count) currency\(count == 1 ? "" : "/currencies")"
            return AIResponse(
                queryType: queryType,
                summary: summary,
                details: count == 0 ? .noResults("No currency amounts detected in your messages") : .currencyTotals(totals),
                suggestedFollowUps: ["Show spending this month", "Recent transactions", "Account balance"]
            )

        case .generalHelp:
            return AIResponse(
                queryType: queryType,
                summary: "Here's what I can help you with",
                details: .help(generateHelpText()),
                suggestedFollowUps: ["How much did I spend this month?", "Show my upcoming bills", "Track my packages"]
            )

        case .unknown(let originalQuery):
            return AIResponse(
                queryType: queryType,
                summary: "I'm not sure what you're looking for",
                details: .help("I can help you analyze your SMS messages. Try asking about:\n\n• Spending (\"How much did I spend at Amazon?\")\n• Bills (\"Show my upcoming bills\")\n• Packages (\"Track my packages\")\n• Account balances (\"What's my balance?\")\n• OTP codes (\"Show my recent OTPs\")"),
                suggestedFollowUps: ["How much did I spend this month?", "Show my upcoming bills", "Track my packages"]
            )
        }
    }

    // MARK: - Query Classification

    private func classifyQuery(_ query: String) -> AIQueryType {
        let lowerQuery = query.lowercased()

        // Extract potential merchant
        let merchant = extractMerchantFromQuery(lowerQuery)

        // Extract time filter
        let timeFilter = extractTimeFilter(lowerQuery)

        // Pattern matching for query type
        if containsAny(lowerQuery, ["spend", "spent", "expense", "how much"]) {
            return .spending(merchant: merchant, timeFilter: timeFilter)
        }

        if containsAny(lowerQuery, ["bill", "due", "payment due", "upcoming bill"]) {
            return .upcomingBills
        }

        if containsAny(lowerQuery, ["package", "delivery", "shipped", "track", "order status", "where is my order"]) {
            return .packageTracking
        }

        if containsAny(lowerQuery, ["balance", "account balance", "available", "bank balance"]) {
            return .balanceQuery
        }

        if containsAny(lowerQuery, ["otp", "code", "verification", "pin", "one time"]) {
            return .otpFinding
        }

        if containsAny(lowerQuery, ["transaction", "list", "show"]) {
            return .transactionList(merchant: merchant, timeFilter: timeFilter)
        }

        if containsAny(lowerQuery, ["subscription", "recurring", "monthly charge"]) {
            return .subscriptions
        }

        if containsAny(lowerQuery, ["summary", "digest", "overview", "snapshot"]) {
            return .summary
        }

        if containsAny(lowerQuery, ["trend", "compare", "vs last", "versus"]) {
            return .spendingTrends
        }

        if containsAny(lowerQuery, ["money total", "currency total", "money summary", "financial summary", "show money", "currency breakdown"]) {
            return .currencyTotals
        }

        if containsAny(lowerQuery, ["help", "what can you", "how to", "?"]) {
            return .generalHelp
        }

        return .unknown(query: query)
    }

    // MARK: - Spending Analysis

    func analyzeSpending(messages: [Message], merchant: String?, timeFilter: TimeFilter?) -> SpendingAnalysis {
        var transactions = parseTransactions(from: messages)

        // Apply merchant filter
        if let merchant = merchant {
            transactions = transactions.filter { txn in
                txn.merchant?.lowercased().contains(merchant.lowercased()) == true ||
                txn.originalMessageBody.lowercased().contains(merchant.lowercased())
            }
        }

        // Apply time filter
        if let timeFilter = timeFilter {
            let range = timeFilter.dateRange
            transactions = transactions.filter { $0.date >= range.start && $0.date <= range.end }
        }

        let total = transactions.reduce(0) { $0 + $1.amount }
        let currency = transactions.first?.currency ?? "USD"
        let average = transactions.isEmpty ? 0 : total / Double(transactions.count)

        // Group by merchant
        var merchantTotals: [String: Double] = [:]
        for txn in transactions {
            let key = txn.merchant ?? "Unknown"
            merchantTotals[key, default: 0] += txn.amount
        }
        let byMerchant = merchantTotals.sorted { $0.value > $1.value }.map { ($0.key, $0.value) }

        // Group by category
        var categoryTotals: [TransactionCategory: Double] = [:]
        for txn in transactions {
            categoryTotals[txn.category, default: 0] += txn.amount
        }
        let byCategory = categoryTotals.sorted { $0.value > $1.value }.map { ($0.key, $0.value) }

        let timeRangeLabel = timeFilter?.displayName ?? "All Time"

        return SpendingAnalysis(
            total: total,
            currency: currency,
            transactionCount: transactions.count,
            averageTransaction: average,
            byMerchant: byMerchant,
            byCategory: byCategory,
            transactions: transactions,
            timeRange: timeRangeLabel
        )
    }

    // MARK: - Transaction Parsing

    /// Detects the currency for a transaction based on merchant, message content, and sender.
    ///
    /// Detection Strategy (in order of priority):
    /// 1. Check if merchant is in merchantCurrencyMap
    /// 2. Look for explicit currency symbols/codes in message ($, ₹, INR, USD)
    /// 3. Check sender phone number pattern (Indian numbers start with +91 or are 10 digits)
    /// 4. Default to USD for unrecognized patterns
    ///
    /// - Parameters:
    ///   - messageBody: The SMS message text
    ///   - merchant: Extracted merchant name (if any)
    ///   - senderAddress: The phone number/short code of the sender
    /// - Returns: Currency code (INR, USD, etc.)
    private func detectCurrency(messageBody: String, merchant: String?, senderAddress: String) -> String {
        let bodyLower = messageBody.lowercased()

        // 1. Check merchant-specific currency mapping
        if let merchant = merchant {
            let merchantLower = merchant.lowercased()
            if let currency = merchantCurrencyMap[merchantLower] {
                return currency
            }

            // Also check if any key in the map is contained in merchant name or body
            for (key, currency) in merchantCurrencyMap {
                if merchantLower.contains(key) || bodyLower.contains(key) {
                    return currency
                }
            }
        }

        // 2. Check for explicit currency indicators in message
        if bodyLower.contains("₹") || bodyLower.contains("inr") ||
           bodyLower.contains("rs.") || bodyLower.contains("rs ") {
            return "INR"
        }
        if bodyLower.contains("$") || bodyLower.contains("usd") {
            return "USD"
        }
        if bodyLower.contains("€") || bodyLower.contains("eur") {
            return "EUR"
        }
        if bodyLower.contains("£") || bodyLower.contains("gbp") {
            return "GBP"
        }
        if bodyLower.contains("¥") || bodyLower.contains("jpy") {
            return "JPY"
        }

        // 3. Check sender pattern for Indian numbers
        let cleanedSender = senderAddress.replacingOccurrences(of: "[^0-9+]", with: "", options: .regularExpression)
        if cleanedSender.hasPrefix("+91") {
            return "INR"
        }
        if cleanedSender.hasPrefix("91") && cleanedSender.count > 10 {
            return "INR"
        }
        if cleanedSender.count == 10 && !cleanedSender.hasPrefix("1") {
            return "INR" // Likely Indian
        }
        if cleanedSender.hasPrefix("+1") || cleanedSender.hasPrefix("1") {
            return "USD"
        }

        // 4. Check for Indian bank keywords
        if bodyLower.contains("bank") && (bodyLower.contains("india") ||
           bodyLower.contains("mumbai") || bodyLower.contains("delhi")) {
            return "INR"
        }

        // Default to USD for short codes and unknown patterns
        return "USD"
    }

    private func parseTransactions(from messages: [Message]) -> [ParsedTransaction] {
        var transactions: [ParsedTransaction] = []

        // Amount patterns — match Android's SpendingParser: INR first, then USD, then generic "amount"
        let inrPattern = #"(?:Rs\.?|₹|INR)\s*([0-9,]+(?:\.\d{1,2})?)"#
        let usdPattern = #"(?:USD|\$)\s*([0-9,]+(?:\.\d{1,2})?)"#
        let genericAmountPattern = #"amount[: ]*([0-9,]+(?:\.\d{1,2})?)"#

        for message in messages {
            let body = message.body
            let bodyLower = body.lowercased()

            // Skip credit/refund messages (same as Android)
            if bodyLower.contains("credited") || bodyLower.contains("refund") ||
               bodyLower.contains("reversal") || bodyLower.contains("deposit") {
                continue
            }

            // Extract merchant first (needed for currency detection)
            let merchant = extractMerchantFromMessage(body)

            // Extract amount — try each pattern in order (matches Android's approach)
            var amount: Double? = nil

            if let match = body.range(of: inrPattern, options: [.regularExpression, .caseInsensitive]) {
                var amountStr = String(body[match])
                amountStr = amountStr.replacingOccurrences(of: "Rs.", with: "")
                    .replacingOccurrences(of: "Rs", with: "")
                    .replacingOccurrences(of: "₹", with: "")
                    .replacingOccurrences(of: "INR", with: "")
                    .replacingOccurrences(of: ",", with: "")
                    .trimmingCharacters(in: CharacterSet.whitespaces)
                amount = Double(amountStr)
            } else if let match = body.range(of: usdPattern, options: [.regularExpression, .caseInsensitive]) {
                let amountStr = String(body[match])
                    .replacingOccurrences(of: "$", with: "")
                    .replacingOccurrences(of: "USD", with: "", options: .caseInsensitive)
                    .replacingOccurrences(of: ",", with: "")
                    .trimmingCharacters(in: CharacterSet.whitespaces)
                amount = Double(amountStr)
            } else if let match = body.range(of: genericAmountPattern, options: [.regularExpression, .caseInsensitive]) {
                let amountStr = String(body[match])
                    .replacingOccurrences(of: "amount", with: "", options: .caseInsensitive)
                    .replacingOccurrences(of: ":", with: "")
                    .replacingOccurrences(of: ",", with: "")
                    .trimmingCharacters(in: CharacterSet.whitespaces)
                amount = Double(amountStr)
            }

            // Skip if no valid amount or unreasonably large
            guard let validAmount = amount, validAmount > 0, validAmount < 10_000_000 else {
                continue
            }

            // Detect currency based on merchant, message content, and sender
            let currency = detectCurrency(messageBody: body, merchant: merchant, senderAddress: message.address)

            // Determine category
            let category = guessCategory(message: body, merchant: merchant)

            let transaction = ParsedTransaction(
                id: message.id,
                amount: validAmount,
                currency: currency,
                merchant: merchant,
                category: category,
                date: Date(timeIntervalSince1970: message.date / 1000),
                originalMessageBody: body,
                isDebit: true
            )

            transactions.append(transaction)
        }

        return transactions.sorted { $0.date > $1.date }
    }

    // MARK: - Bill Detection

    func findUpcomingBills(messages: [Message]) -> [BillReminder] {
        var bills: [BillReminder] = []

        // Date patterns
        let dueDatePatterns = [
            #"(?:due|pay by|payment due)[:\s]*(\d{1,2}[/-]\d{1,2}(?:[/-]\d{2,4})?)"#,
            #"(?:due|pay by)[:\s]*(\d{1,2}\s*(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*)"#
        ]

        // Amount pattern
        let amountPattern = #"(?:minimum|min|amount|balance)[:\s]*(?:\$|Rs\.?|₹)?\s*([0-9,]+(?:\.\d{2})?)"#

        for message in messages {
            let body = message.body
            let bodyLower = body.lowercased()

            // Check if it's a bill-related message
            guard billKeywords.contains(where: { bodyLower.contains($0) }) else {
                continue
            }

            // Determine bill type
            let billType = determineBillType(body)

            // Extract due date
            var dueDate: Date? = nil
            for pattern in dueDatePatterns {
                if let match = body.range(of: pattern, options: [String.CompareOptions.regularExpression, String.CompareOptions.caseInsensitive]) {
                    let dateStr = String(body[match])
                    dueDate = parseDate(from: dateStr)
                    if dueDate != nil { break }
                }
            }

            // Extract amount
            var amount: Double? = nil
            var currency = "USD"
            if let match = body.range(of: amountPattern, options: [String.CompareOptions.regularExpression, String.CompareOptions.caseInsensitive]) {
                let amountStr = String(body[match])
                    .replacingOccurrences(of: "$", with: "")
                    .replacingOccurrences(of: "Rs.", with: "")
                    .replacingOccurrences(of: "₹", with: "")
                    .replacingOccurrences(of: ",", with: "")
                if let numMatch = amountStr.range(of: #"[0-9]+(?:\.\d{2})?"#, options: String.CompareOptions.regularExpression) {
                    amount = Double(amountStr[numMatch])
                }
                currency = bodyLower.contains("rs") || bodyLower.contains("₹") ? "INR" : "USD"
            }

            // Extract merchant/institution
            let merchant = extractMerchantFromMessage(body) ?? "Unknown"

            let bill = BillReminder(
                id: message.id,
                billType: billType,
                dueDate: dueDate,
                amount: amount,
                currency: currency,
                merchant: merchant,
                originalMessageBody: body,
                messageDate: Date(timeIntervalSince1970: message.date / 1000)
            )

            bills.append(bill)
        }

        // Sort by due date (upcoming first)
        return bills.sorted {
            guard let date1 = $0.dueDate, let date2 = $1.dueDate else {
                return $0.dueDate != nil
            }
            return date1 < date2
        }
    }

    // MARK: - Package Tracking

    func trackPackages(messages: [Message]) -> [PackageStatus] {
        var packages: [PackageStatus] = []

        // Tracking number pattern
        let trackingPattern = #"(?:tracking|track)[:\s#]*([A-Z0-9]{10,22})"#

        for message in messages {
            let body = message.body
            let bodyLower = body.lowercased()

            // Check if it's a package-related message
            guard packageKeywords.contains(where: { bodyLower.contains($0) }) else {
                continue
            }

            // Detect carrier
            let carrier = carriers.first { bodyLower.contains($0) }?.capitalized

            // Extract tracking number
            var trackingNumber: String? = nil
            if let match = body.range(of: trackingPattern, options: [String.CompareOptions.regularExpression, String.CompareOptions.caseInsensitive]) {
                trackingNumber = String(body[match]).uppercased()
                // Clean up the tracking number
                trackingNumber = trackingNumber?.replacingOccurrences(of: "TRACKING", with: "")
                    .replacingOccurrences(of: "TRACK", with: "")
                    .replacingOccurrences(of: ":", with: "")
                    .replacingOccurrences(of: "#", with: "")
                    .trimmingCharacters(in: .whitespaces)
            }

            // Determine status
            let status = determineDeliveryStatus(bodyLower)

            // Extract merchant
            let merchant = extractMerchantFromMessage(body)

            let package = PackageStatus(
                id: message.id,
                carrier: carrier,
                trackingNumber: trackingNumber,
                status: status,
                estimatedDelivery: nil, // Could parse from message if available
                merchant: merchant,
                originalMessageBody: body,
                messageDate: Date(timeIntervalSince1970: message.date / 1000)
            )

            packages.append(package)
        }

        return packages.sorted { $0.messageDate > $1.messageDate }
    }

    // MARK: - Balance Info

    func findBalanceInfo(messages: [Message]) -> [BalanceInfo] {
        var balances: [BalanceInfo] = []

        let balanceKeywords = ["balance", "avl bal", "avl", "available", "a/c bal", "current bal"]

        // Multiple patterns to extract balance amount
        let balancePatterns = [
            #"(?:avl\.?\s*bal\.?|available\s*(?:bal\.?|balance)?|current\s*bal\.?|a\/c\s*bal\.?|balance)[:\s]*(?:is\s*)?(?:\$|Rs\.?|₹|INR|USD)?\s*([0-9,]+(?:\.\d{1,2})?)"#,
            #"(?:\$|Rs\.?|₹)\s*([0-9,]+(?:\.\d{1,2})?)\s*(?:available|avl|balance)"#,
            #"balance[:\s]+(?:\$|Rs\.?|₹)?\s*([0-9,]+(?:\.\d{1,2})?)"#,
            #"(?:\$|Rs\.?|₹)\s*([0-9,]+(?:\.\d{1,2})?)"#,
        ]

        for message in messages {
            let body = message.body
            let bodyLower = body.lowercased()

            // Check if it's a balance-related message
            guard balanceKeywords.contains(where: { bodyLower.contains($0) }) else {
                continue
            }

            // Extract balance amount using multiple patterns
            var balance: Double? = nil
            var currency = "USD"

            for pattern in balancePatterns {
                if let match = body.range(of: pattern, options: [String.CompareOptions.regularExpression, String.CompareOptions.caseInsensitive]) {
                    let matchedStr = String(body[match])
                    // Extract just the number
                    let numberPattern = #"[0-9,]+(?:\.\d{1,2})?"#
                    if let numMatch = matchedStr.range(of: numberPattern, options: String.CompareOptions.regularExpression) {
                        let numStr = String(matchedStr[numMatch]).replacingOccurrences(of: ",", with: "")
                        if let parsed = Double(numStr), parsed > 0, parsed < 10_000_000 {
                            balance = parsed
                            break
                        }
                    }
                }
            }

            // Determine currency
            if bodyLower.contains("rs") || bodyLower.contains("₹") || bodyLower.contains("inr") {
                currency = "INR"
            } else if body.contains("$") || bodyLower.contains("usd") {
                currency = "USD"
            }

            // Extract institution
            var institution = extractMerchantFromMessage(body)

            // Also check address for bank names
            if institution == nil {
                let addressLower = message.address.lowercased()
                let bankNames = ["chase", "bofa", "wellsfargo", "citi", "amex", "discover", "capital", "usbank", "pnc", "td", "ally", "paypal"]
                for bank in bankNames {
                    if addressLower.contains(bank) {
                        institution = bank.capitalized
                        break
                    }
                }
            }

            // Determine account type
            let accountType = bodyLower.contains("credit card") || bodyLower.contains("card ending") ? "Credit Card" :
                              bodyLower.contains("saving") ? "Savings" :
                              bodyLower.contains("checking") || bodyLower.contains("current a/c") ? "Checking" :
                              bodyLower.contains("debit") ? "Debit Card" : "Account"

            let balanceInfo = BalanceInfo(
                id: message.id,
                accountType: accountType,
                balance: balance,
                currency: currency,
                asOfDate: Date(timeIntervalSince1970: message.date / 1000),
                institution: institution,
                originalMessageBody: body
            )

            balances.append(balanceInfo)
        }

        // Remove duplicates - keep most recent per institution
        var seen: [String: BalanceInfo] = [:]
        for bal in balances.sorted(by: { $0.asOfDate > $1.asOfDate }) {
            let key = "\(bal.institution ?? "unknown")-\(bal.accountType)"
            if seen[key] == nil {
                seen[key] = bal
            }
        }

        return Array(seen.values).sorted { $0.asOfDate > $1.asOfDate }
    }

    // MARK: - OTP Extraction

    func extractOTPs(messages: [Message]) -> [OTPInfo] {
        var otps: [OTPInfo] = []

        let otpKeywords = ["otp", "code", "verification", "verify", "pin", "one time", "one-time"]
        let otpPattern = #"\b(\d{4,6})\b"#

        for message in messages {
            let body = message.body
            let bodyLower = body.lowercased()

            // Check if it's an OTP-related message
            guard otpKeywords.contains(where: { bodyLower.contains($0) }) else {
                continue
            }

            // Extract OTP code
            guard let match = body.range(of: otpPattern, options: String.CompareOptions.regularExpression) else {
                continue
            }

            let code = String(body[match])

            // Determine source
            let source = extractMerchantFromMessage(body) ?? message.address

            // Check for expiry info
            var expiresIn: String? = nil
            if bodyLower.contains("valid for") || bodyLower.contains("expires in") {
                let expiryPattern = #"(?:valid for|expires in)[:\s]*(\d+\s*(?:min|minute|sec|second|hr|hour))"#
                if let expiryMatch = body.range(of: expiryPattern, options: [String.CompareOptions.regularExpression, String.CompareOptions.caseInsensitive]) {
                    expiresIn = String(body[expiryMatch])
                }
            }

            let otp = OTPInfo(
                id: message.id,
                code: code,
                source: source,
                timestamp: Date(timeIntervalSince1970: message.date / 1000),
                expiresIn: expiresIn,
                originalMessageBody: body
            )

            otps.append(otp)
        }

        return otps.sorted { $0.timestamp > $1.timestamp }
    }

    // MARK: - Recurring Expense Detection

    func detectRecurringExpenses(messages: [Message]) -> [RecurringExpense] {
        let transactions = parseTransactions(from: messages)
        var merchantGroups: [String: [ParsedTransaction]] = [:]

        // Group by merchant
        for txn in transactions {
            let merchant = txn.merchant?.lowercased() ?? "unknown"
            merchantGroups[merchant, default: []].append(txn)
        }

        var recurring: [RecurringExpense] = []

        for (merchant, txns) in merchantGroups {
            guard txns.count >= 2 else { continue }

            // Check if it's a known subscription
            let isKnownSubscription = subscriptionKeywords.contains { merchant.contains($0) }

            // Sort by date
            let sorted = txns.sorted { $0.date < $1.date }

            // Check if amounts are similar (within 10%)
            let amounts = sorted.map { $0.amount }
            let avgAmount = amounts.reduce(0, +) / Double(amounts.count)
            let similarAmounts = amounts.allSatisfy { abs($0 - avgAmount) / avgAmount < 0.1 }

            if !similarAmounts && !isKnownSubscription { continue }

            // Check intervals between charges
            var intervals: [Double] = []
            for i in 1..<sorted.count {
                let daysDiff = sorted[i].date.timeIntervalSince(sorted[i-1].date) / (60 * 60 * 24)
                intervals.append(daysDiff)
            }

            guard !intervals.isEmpty else { continue }

            let avgInterval = intervals.reduce(0, +) / Double(intervals.count)

            // Determine frequency
            var frequency: RecurringFrequency = .monthly
            if avgInterval >= 5 && avgInterval <= 10 {
                frequency = .weekly
            } else if avgInterval >= 25 && avgInterval <= 35 {
                frequency = .monthly
            } else if avgInterval >= 350 && avgInterval <= 380 {
                frequency = .yearly
            } else if !isKnownSubscription {
                continue // Not a regular interval
            }

            let expense = RecurringExpense(
                id: UUID().uuidString,
                merchant: merchant.capitalized,
                amount: avgAmount,
                currency: sorted.first?.currency ?? "USD",
                frequency: frequency,
                lastCharge: sorted.last?.date ?? Date(),
                occurrences: sorted.count
            )

            recurring.append(expense)
        }

        return recurring.sorted { $0.amount > $1.amount }
    }

    // MARK: - Smart Digest Generation

    func generateSmartDigest(messages: [Message]) -> SmartDigest {
        let transactions = parseTransactions(from: messages)
        let now = Date()
        let calendar = Calendar.current
        let thisMonthStart = calendar.date(from: calendar.dateComponents([.year, .month], from: now))!
        let lastMonthStart = calendar.date(byAdding: .month, value: -1, to: thisMonthStart)!

        let thisMonthTxns = transactions.filter { $0.date >= thisMonthStart }
        let lastMonthTxns = transactions.filter { $0.date >= lastMonthStart && $0.date < thisMonthStart }

        let totalThisMonth = thisMonthTxns.reduce(0) { $0 + $1.amount }
        let totalLastMonth = lastMonthTxns.reduce(0) { $0 + $1.amount }

        // Top merchant this month
        var merchantTotals: [String: Double] = [:]
        for t in thisMonthTxns {
            let m = t.merchant ?? "Unknown"
            merchantTotals[m, default: 0] += t.amount
        }
        let topMerchant = merchantTotals.max { $0.value < $1.value }?.key

        // Subscription total
        let recurring = detectRecurringExpenses(messages: messages)
        let subscriptionTotal = recurring.reduce(0.0) { sum, r in
            switch r.frequency {
            case .monthly: return sum + r.amount
            case .yearly: return sum + r.amount / 12
            case .weekly: return sum + r.amount * 4
            }
        }

        // Count bills and packages
        let bills = findUpcomingBills(messages: messages)
        let upcomingBills = bills.filter { !$0.isOverdue && $0.dueDate != nil && $0.dueDate! > Date() }.count

        let weekAgo = calendar.date(byAdding: .day, value: -7, to: now)!
        let recentPackages = messages.filter { msg in
            let bodyLower = msg.body.lowercased()
            let msgDate = Date(timeIntervalSince1970: msg.date / 1000)
            return (bodyLower.contains("shipped") || bodyLower.contains("delivery") || bodyLower.contains("arriving")) &&
                   msgDate > weekAgo
        }.count

        let spendingChange = totalLastMonth > 0 ? ((totalThisMonth - totalLastMonth) / totalLastMonth) * 100 : 0

        return SmartDigest(
            totalSpentThisMonth: totalThisMonth,
            totalSpentLastMonth: totalLastMonth,
            spendingChange: spendingChange,
            transactionCount: thisMonthTxns.count,
            upcomingBills: upcomingBills,
            recentPackages: recentPackages,
            subscriptionTotal: subscriptionTotal,
            topMerchant: topMerchant,
            currency: transactions.first?.currency ?? "USD"
        )
    }

    // MARK: - Transaction Listing

    func listTransactions(messages: [Message], merchant: String?, timeFilter: TimeFilter?) -> [ParsedTransaction] {
        var transactions = parseTransactions(from: messages)

        if let merchant = merchant {
            transactions = transactions.filter { txn in
                txn.merchant?.lowercased().contains(merchant.lowercased()) == true ||
                txn.originalMessageBody.lowercased().contains(merchant.lowercased())
            }
        }

        if let timeFilter = timeFilter {
            let range = timeFilter.dateRange
            transactions = transactions.filter { $0.date >= range.start && $0.date <= range.end }
        }

        return transactions
    }

    // MARK: - Currency Totals Analysis

    /// Analyzes all messages to extract and total currency amounts
    ///
    /// Scans ALL messages (not just bank SMS) for currency symbols and amounts,
    /// groups by currency type, and returns totals per currency.
    ///
    /// Supported currencies:
    /// - $ (USD), € (EUR), £ (GBP), ¥ (JPY), ₹ (INR)
    /// - CA$ (CAD), AU$ (AUD), NZ$ (NZD)
    /// - CHF, SEK, NOK, DKK (text-based)
    ///
    /// - Parameter messages: All messages to scan
    /// - Returns: Dictionary mapping currency code to total amount
    func analyzeCurrencyTotals(messages: [Message]) -> [String: Double] {
        var currencyTotals: [String: Double] = [:]

        // Currency patterns with symbol and text detection
        let currencyPatterns: [String: [NSRegularExpression]] = [
            "USD": [
                try! NSRegularExpression(pattern: #"\$\s*([0-9,]+(?:\.\d{1,2})?)"#),
                try! NSRegularExpression(pattern: #"([0-9,]+(?:\.\d{1,2})?)\s*USD"#, options: .caseInsensitive)
            ],
            "EUR": [
                try! NSRegularExpression(pattern: #"€\s*([0-9,]+(?:\.\d{1,2})?)"#),
                try! NSRegularExpression(pattern: #"([0-9,]+(?:\.\d{1,2})?)\s*EUR"#, options: .caseInsensitive)
            ],
            "GBP": [
                try! NSRegularExpression(pattern: #"£\s*([0-9,]+(?:\.\d{1,2})?)"#),
                try! NSRegularExpression(pattern: #"([0-9,]+(?:\.\d{1,2})?)\s*GBP"#, options: .caseInsensitive)
            ],
            "JPY": [
                try! NSRegularExpression(pattern: #"¥\s*([0-9,]+(?:\.\d{1,2})?)"#),
                try! NSRegularExpression(pattern: #"([0-9,]+(?:\.\d{1,2})?)\s*JPY"#, options: .caseInsensitive)
            ],
            "INR": [
                try! NSRegularExpression(pattern: #"₹\s*([0-9,]+(?:\.\d{1,2})?)"#),
                try! NSRegularExpression(pattern: #"(?:Rs\.?|INR)\s*([0-9,]+(?:\.\d{1,2})?)"#, options: .caseInsensitive),
                try! NSRegularExpression(pattern: #"([0-9,]+(?:\.\d{1,2})?)\s*(?:Rs\.?|INR)"#, options: .caseInsensitive)
            ],
            "CAD": [
                try! NSRegularExpression(pattern: #"CA\$\s*([0-9,]+(?:\.\d{1,2})?)"#, options: .caseInsensitive),
                try! NSRegularExpression(pattern: #"([0-9,]+(?:\.\d{1,2})?)\s*CAD"#, options: .caseInsensitive)
            ],
            "AUD": [
                try! NSRegularExpression(pattern: #"AU\$\s*([0-9,]+(?:\.\d{1,2})?)"#, options: .caseInsensitive),
                try! NSRegularExpression(pattern: #"([0-9,]+(?:\.\d{1,2})?)\s*AUD"#, options: .caseInsensitive)
            ],
            "NZD": [
                try! NSRegularExpression(pattern: #"NZ\$\s*([0-9,]+(?:\.\d{1,2})?)"#, options: .caseInsensitive),
                try! NSRegularExpression(pattern: #"([0-9,]+(?:\.\d{1,2})?)\s*NZD"#, options: .caseInsensitive)
            ],
            "CHF": [
                try! NSRegularExpression(pattern: #"([0-9,]+(?:\.\d{1,2})?)\s*CHF"#, options: .caseInsensitive),
                try! NSRegularExpression(pattern: #"CHF\s*([0-9,]+(?:\.\d{1,2})?)"#, options: .caseInsensitive)
            ],
            "SEK": [
                try! NSRegularExpression(pattern: #"([0-9,]+(?:\.\d{1,2})?)\s*SEK"#, options: .caseInsensitive),
                try! NSRegularExpression(pattern: #"SEK\s*([0-9,]+(?:\.\d{1,2})?)"#, options: .caseInsensitive)
            ],
            "NOK": [
                try! NSRegularExpression(pattern: #"([0-9,]+(?:\.\d{1,2})?)\s*NOK"#, options: .caseInsensitive),
                try! NSRegularExpression(pattern: #"NOK\s*([0-9,]+(?:\.\d{1,2})?)"#, options: .caseInsensitive)
            ],
            "DKK": [
                try! NSRegularExpression(pattern: #"([0-9,]+(?:\.\d{1,2})?)\s*DKK"#, options: .caseInsensitive),
                try! NSRegularExpression(pattern: #"DKK\s*([0-9,]+(?:\.\d{1,2})?)"#, options: .caseInsensitive)
            ]
        ]

        // Scan all messages for currency mentions
        for message in messages {
            let body = message.body
            let nsBody = body as NSString
            let range = NSRange(location: 0, length: nsBody.length)

            for (currency, patterns) in currencyPatterns {
                for pattern in patterns {
                    let matches = pattern.matches(in: body, options: [], range: range)

                    for match in matches {
                        if match.numberOfRanges > 1 {
                            let amountRange = match.range(at: 1)
                            if let swiftRange = Range(amountRange, in: body) {
                                let amountStr = String(body[swiftRange]).replacingOccurrences(of: ",", with: "")
                                if let amount = Double(amountStr), amount > 0, amount < 10_000_000 {
                                    currencyTotals[currency, default: 0] += amount
                                }
                            }
                        }
                    }
                }
            }
        }

        return currencyTotals
    }

    // MARK: - Helper Methods

    private func extractMerchantFromQuery(_ query: String) -> String? {
        for (merchant, aliases) in merchantAliases {
            if aliases.contains(where: { query.contains($0) }) {
                return merchant
            }
        }
        return nil
    }

    private func extractMerchantFromMessage(_ body: String) -> String? {
        let bodyLower = body.lowercased()

        // First check known merchant aliases
        for (merchant, aliases) in merchantAliases {
            if aliases.contains(where: { bodyLower.contains($0) }) {
                return merchant
            }
        }

        // Try to extract bank name from patterns like "HDFC Bank", "SBI Alert", "ICICI Bank Alert"
        let bankPatterns = [
            #"([A-Z]{2,10})\s+Bank"#,           // "HDFC Bank", "ICICI Bank"
            #"([A-Z]{2,10})\s+Alert"#,          // "SBI Alert"
            #"([A-Z][A-Za-z]+)\s+Bank"#,        // "Axis Bank", "Kotak Bank"
            #"([A-Z][A-Za-z]+)\s+Credit"#,      // "Amex Credit"
            #"Dear\s+([A-Z][A-Za-z]+)\s+Card"#, // "Dear HDFC Card"
            #"from\s+([A-Z][A-Za-z]+)\s+A/c"#,  // "from HDFC A/c"
            #"([A-Z]{2,10})Bank"#,              // "HDFCBank" (no space)
        ]

        for pattern in bankPatterns {
            if let regex = try? NSRegularExpression(pattern: pattern, options: []),
               let match = regex.firstMatch(in: body, options: [], range: NSRange(body.startIndex..., in: body)),
               let range = Range(match.range(at: 1), in: body) {
                let extracted = String(body[range])
                // Skip common false positives
                let skipWords = ["your", "the", "dear", "from", "with", "card"]
                if !skipWords.contains(extracted.lowercased()) && extracted.count >= 2 {
                    return extracted
                }
            }
        }

        // Try to extract from sender patterns in the message (VK-HDFCBK, AD-ICICIB, etc.)
        let senderPattern = #"(?:VK|AD|VM|BZ|HP|TD|DM|AX)-([A-Z]{3,8})"#
        if let regex = try? NSRegularExpression(pattern: senderPattern, options: []),
           let match = regex.firstMatch(in: body, options: [], range: NSRange(body.startIndex..., in: body)),
           let range = Range(match.range(at: 1), in: body) {
            let code = String(body[range])
            // Map common bank codes
            let bankCodes: [String: String] = [
                "HDFCBK": "HDFC", "ICICIB": "ICICI", "SBIINB": "SBI", "AXISBK": "Axis",
                "KOTAKB": "Kotak", "PNBBK": "PNB", "BOBBK": "BOB", "YESBK": "Yes Bank",
                "IABORB": "IOB", "CANBNK": "Canara", "UNIONB": "Union", "INDUSB": "IndusInd"
            ]
            return bankCodes[code] ?? code
        }

        return nil
    }

    private func extractTimeFilter(_ query: String) -> TimeFilter? {
        if query.contains("today") {
            return .today
        } else if query.contains("week") || query.contains("7 day") {
            return .thisWeek
        } else if query.contains("month") || query.contains("30 day") {
            return .thisMonth
        } else if query.contains("year") {
            return .thisYear
        }
        return nil
    }

    private func guessCategory(message: String, merchant: String?) -> TransactionCategory {
        let combined = "\(merchant?.lowercased() ?? "") \(message.lowercased())"

        for (category, keywords) in categoryKeywords {
            if keywords.contains(where: { combined.contains($0) }) {
                return category
            }
        }

        return .other
    }

    private func determineBillType(_ body: String) -> BillType {
        let bodyLower = body.lowercased()

        if bodyLower.contains("credit card") || bodyLower.contains("card payment") {
            return .creditCard
        } else if bodyLower.contains("electricity") || bodyLower.contains("water") || bodyLower.contains("gas") || bodyLower.contains("utility") {
            return .utility
        } else if bodyLower.contains("subscription") || bodyLower.contains("membership") {
            return .subscription
        } else if bodyLower.contains("loan") || bodyLower.contains("emi") {
            return .loan
        } else if bodyLower.contains("rent") {
            return .rent
        } else if bodyLower.contains("insurance") {
            return .insurance
        }

        return .other
    }

    private func determineDeliveryStatus(_ body: String) -> DeliveryStatus {
        if body.contains("delivered") {
            return .delivered
        } else if body.contains("out for delivery") {
            return .outForDelivery
        } else if body.contains("in transit") || body.contains("on the way") {
            return .inTransit
        } else if body.contains("shipped") || body.contains("dispatched") {
            return .shipped
        } else if body.contains("exception") || body.contains("delayed") || body.contains("failed") {
            return .exception
        }

        return .ordered
    }

    private func parseDate(from string: String) -> Date? {
        let formatters = [
            "MM/dd/yyyy",
            "MM-dd-yyyy",
            "dd/MM/yyyy",
            "dd-MM-yyyy",
            "MM/dd",
            "dd MMM",
            "MMM dd"
        ]

        for format in formatters {
            let formatter = DateFormatter()
            formatter.dateFormat = format
            if let date = formatter.date(from: string) {
                // If year is not in format, use current year
                if !format.contains("yyyy") {
                    var components = Calendar.current.dateComponents([.month, .day], from: date)
                    components.year = Calendar.current.component(.year, from: Date())
                    return Calendar.current.date(from: components)
                }
                return date
            }
        }

        return nil
    }

    private func containsAny(_ text: String, _ keywords: [String]) -> Bool {
        return keywords.contains { text.contains($0) }
    }

    private func generateSpendingFollowUps(merchant: String?, timeFilter: TimeFilter?) -> [String] {
        var followUps: [String] = []

        if merchant == nil {
            followUps.append("Spent at Amazon")
            followUps.append("Top merchants")
        }

        if timeFilter == nil {
            followUps.append("Spent this week")
            followUps.append("Spent this month")
        } else if timeFilter == .thisMonth {
            followUps.append("Spent this year")
        }

        followUps.append("Show my upcoming bills")

        return Array(followUps.prefix(3))
    }

    private func generateHelpText() -> String {
        return """
        I can help you analyze your SMS messages. Here's what I can do:

        **Spending Analysis**
        • "How much did I spend this month?"
        • "Spent at Amazon"
        • "Show my expenses this week"
        • "Show money totals" or "Currency totals"

        **Bills & Payments**
        • "Show my upcoming bills"
        • "Any payment due?"

        **Package Tracking**
        • "Track my packages"
        • "Where is my order?"

        **Account Information**
        • "What's my account balance?"
        • "Show my bank balance"

        **OTP Codes**
        • "Show recent OTPs"
        • "Verification codes"

        **Transactions**
        • "List my transactions"
        • "Recent payments"
        """
    }
}
