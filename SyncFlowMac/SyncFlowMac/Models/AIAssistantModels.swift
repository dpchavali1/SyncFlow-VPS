//
//  AIAssistantModels.swift
//  SyncFlowMac
//
//  Data models for the AI-powered Smart Assistant feature.
//
//  This file contains all models related to the AI assistant functionality, which analyzes
//  SMS messages to provide intelligent insights about:
//  - Financial transactions and spending patterns
//  - Bill reminders and due dates
//  - Package delivery tracking
//  - One-Time Passwords (OTPs) for quick copying
//  - Account balances from bank notifications
//  - Subscription tracking and recurring expenses
//
//  ## Architecture
//  The AI assistant uses local message parsing (no cloud AI) to extract structured data from
//  transactional SMS messages. Common sources include:
//  - Bank transaction alerts
//  - Credit card notifications
//  - E-commerce shipping updates
//  - Utility bill reminders
//  - Two-factor authentication codes
//
//  ## Model Categories
//  1. **Filters**: TimeFilter, TransactionCategory, BillType, DeliveryStatus, RecurringFrequency
//  2. **Parsed Data**: ParsedTransaction, BillReminder, PackageStatus, BalanceInfo, OTPInfo
//  3. **Analysis Results**: SpendingAnalysis, RecurringExpense, SmartDigest
//  4. **Query/Response**: AIQueryType, AIResponseDetails, AIResponse
//  5. **Chat UI**: AIChatMessage, AIChatContent, AIQuickAction
//
//  ## Currency Support
//  Currently optimized for INR (Indian Rupee) and USD. Currency detection is based on
//  message content patterns from banks and financial institutions.
//

import Foundation

// MARK: - Time Filters

/// Predefined time ranges for filtering transactions, spending analysis, and other date-based queries.
///
/// TimeFilter provides both absolute ranges (today, this week, this month, this year) and
/// relative ranges (last 7 days, last 30 days). Each filter computes its date range dynamically
/// based on the current date.
///
/// ## Usage
/// Used throughout the AI assistant for:
/// - Filtering transaction lists
/// - Calculating spending totals for specific periods
/// - Comparing spending across time periods (e.g., this month vs. last month)
enum TimeFilter: String, CaseIterable {
    case today = "today"
    case thisWeek = "this week"
    case thisMonth = "this month"
    case thisYear = "this year"
    case last7Days = "last 7 days"
    case last30Days = "last 30 days"

    /// Computes the start and end dates for this time filter.
    ///
    /// - Returns: A tuple containing the start date (inclusive) and end date (current time)
    ///
    /// ## Date Calculation Notes
    /// - `today`: From midnight today to now
    /// - `thisWeek`: From the start of the current week (locale-dependent) to now
    /// - `thisMonth`: From the 1st of the current month to now
    /// - `thisYear`: From January 1st of the current year to now
    /// - `last7Days`/`last30Days`: Rolling window from N days ago to now
    var dateRange: (start: Date, end: Date) {
        let now = Date()
        let calendar = Calendar.current

        switch self {
        case .today:
            let start = calendar.startOfDay(for: now)
            return (start, now)
        case .thisWeek:
            let start = calendar.date(from: calendar.dateComponents([.yearForWeekOfYear, .weekOfYear], from: now))!
            return (start, now)
        case .thisMonth:
            let start = calendar.date(from: calendar.dateComponents([.year, .month], from: now))!
            return (start, now)
        case .thisYear:
            let start = calendar.date(from: calendar.dateComponents([.year], from: now))!
            return (start, now)
        case .last7Days:
            let start = calendar.date(byAdding: .day, value: -7, to: now)!
            return (start, now)
        case .last30Days:
            let start = calendar.date(byAdding: .day, value: -30, to: now)!
            return (start, now)
        }
    }

    /// Human-readable name for display in the UI.
    var displayName: String {
        switch self {
        case .today: return "Today"
        case .thisWeek: return "This Week"
        case .thisMonth: return "This Month"
        case .thisYear: return "This Year"
        case .last7Days: return "Last 7 Days"
        case .last30Days: return "Last 30 Days"
        }
    }
}

// MARK: - Transaction Categories

/// Categories for classifying financial transactions parsed from SMS messages.
///
/// The AI assistant attempts to categorize transactions based on merchant names,
/// keywords in the message body, and known merchant databases. Categories are used
/// for spending breakdowns and budget analysis.
///
/// ## Categorization Logic
/// Merchant names are matched against known patterns:
/// - "Amazon", "Flipkart" -> Shopping
/// - "Swiggy", "Zomato", "Uber Eats" -> Food & Dining
/// - "Uber", "Ola", "Lyft" -> Transport
/// - "Netflix", "Spotify" -> Subscriptions
/// etc.
enum TransactionCategory: String, CaseIterable {
    /// General retail and e-commerce purchases (Amazon, Flipkart, etc.)
    case shopping = "Shopping"

    /// Restaurants, food delivery, dining out
    case food = "Food & Dining"

    /// Taxi, ride-sharing, public transit
    case transport = "Transport"

    /// Movies, streaming services, games, events
    case entertainment = "Entertainment"

    /// Electricity, water, internet, phone bills
    case utilities = "Utilities"

    /// Recurring subscription services (Netflix, Spotify, etc.)
    case subscriptions = "Subscriptions"

    /// Flights, hotels, vacation expenses
    case travel = "Travel"

    /// Medical expenses, pharmacy, health insurance
    case health = "Health"

    /// Supermarket and grocery store purchases
    case groceries = "Groceries"

    /// Gas/petrol station purchases
    case fuel = "Fuel"

    /// Uncategorized transactions
    case other = "Other"

    /// SF Symbol icon name for displaying this category in the UI.
    var icon: String {
        switch self {
        case .shopping: return "bag.fill"
        case .food: return "fork.knife"
        case .transport: return "car.fill"
        case .entertainment: return "tv.fill"
        case .utilities: return "bolt.fill"
        case .subscriptions: return "repeat"
        case .travel: return "airplane"
        case .health: return "heart.fill"
        case .groceries: return "cart.fill"
        case .fuel: return "fuelpump.fill"
        case .other: return "creditcard.fill"
        }
    }
}

// MARK: - Parsed Transaction

/// Represents a financial transaction extracted from a bank or payment SMS notification.
///
/// The AI assistant parses transactional SMS messages from banks, credit cards, and payment
/// services to extract structured transaction data. This enables spending analysis, budget
/// tracking, and merchant-based categorization.
///
/// ## Parsing Sources
/// Transactions are parsed from SMS messages matching patterns like:
/// - "Spent Rs. 500 at Amazon using HDFC Credit Card"
/// - "INR 1,234 debited from A/c XX1234"
/// - "Payment of $50.00 to Uber completed"
///
/// ## Relationships
/// - Used by: `SpendingAnalysis` - Transactions are aggregated for spending reports
/// - Category: `TransactionCategory` - Each transaction is assigned a category
struct ParsedTransaction: Identifiable {
    /// Unique identifier, typically derived from the source message ID
    let id: String

    /// Transaction amount as a decimal number (e.g., 1234.56)
    let amount: Double

    /// Currency code (e.g., "INR", "USD"). Used for formatting and multi-currency support.
    let currency: String

    /// Merchant or payee name extracted from the message (e.g., "Amazon", "Uber")
    let merchant: String?

    /// Category assigned based on merchant name and message keywords
    let category: TransactionCategory

    /// Date/time of the transaction, extracted from the message or using message timestamp
    let date: Date

    /// The complete original SMS message text, preserved for reference and verification
    let originalMessageBody: String

    /// True if this is a debit (money spent/withdrawn), false if credit (money received)
    let isDebit: Bool

    /// Returns the amount formatted with the appropriate currency symbol.
    /// Uses INR (₹) or USD ($) symbol based on the currency field.
    var formattedAmount: String {
        let symbol = currency == "INR" ? "₹" : "$"
        return "\(symbol)\(String(format: "%.2f", amount))"
    }

    /// Returns the transaction date in a medium date format (e.g., "Jan 15, 2024")
    var formattedDate: String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        return formatter.string(from: date)
    }
}

// MARK: - Spending Analysis

/// Aggregated spending analysis for a given time period.
///
/// This model provides a comprehensive spending summary including totals, averages,
/// breakdowns by merchant and category, and the underlying transaction list.
/// It's the primary response model for spending-related AI queries.
///
/// ## Usage
/// Created by the AI assistant in response to queries like:
/// - "How much did I spend this month?"
/// - "Show my Amazon purchases"
/// - "What's my spending by category?"
///
/// ## Relationships
/// - Contains: `ParsedTransaction` array - The individual transactions in this analysis
/// - Uses: `TransactionCategory` - For category-based breakdowns
struct SpendingAnalysis {
    /// Total amount spent across all transactions in this analysis
    let total: Double

    /// Currency code for the amounts (e.g., "INR", "USD")
    let currency: String

    /// Number of transactions included in this analysis
    let transactionCount: Int

    /// Average transaction amount (total / transactionCount)
    let averageTransaction: Double

    /// Spending breakdown by merchant, sorted by amount descending.
    /// Each tuple contains the merchant name and total amount spent at that merchant.
    let byMerchant: [(merchant: String, amount: Double)]

    /// Spending breakdown by category, sorted by amount descending.
    /// Each tuple contains the category and total amount spent in that category.
    let byCategory: [(category: TransactionCategory, amount: Double)]

    /// The individual transactions included in this analysis
    let transactions: [ParsedTransaction]

    /// Human-readable description of the time range (e.g., "This Month", "Last 7 Days")
    let timeRange: String

    /// Returns the total formatted with the appropriate currency symbol.
    var formattedTotal: String {
        let symbol = currency == "INR" ? "₹" : "$"
        return "\(symbol)\(String(format: "%.2f", total))"
    }

    /// Returns the average transaction amount formatted with the appropriate currency symbol.
    var formattedAverage: String {
        let symbol = currency == "INR" ? "₹" : "$"
        return "\(symbol)\(String(format: "%.2f", averageTransaction))"
    }
}

// MARK: - Bill Types

/// Categories for classifying bill reminders and payment due notices.
///
/// Bill types help organize and prioritize upcoming payments extracted from
/// reminder SMS messages sent by billers and financial institutions.
enum BillType: String, CaseIterable {
    /// Credit card statement due (HDFC, ICICI, Amex, etc.)
    case creditCard = "Credit Card"

    /// Utility bills (electricity, water, gas, internet)
    case utility = "Utility"

    /// Subscription renewals (streaming services, memberships)
    case subscription = "Subscription"

    /// Loan EMI payments (home loan, car loan, personal loan)
    case loan = "Loan"

    /// Rent or lease payments
    case rent = "Rent"

    /// Insurance premium payments
    case insurance = "Insurance"

    /// Other uncategorized bill reminders
    case other = "Other"

    /// SF Symbol icon name for displaying this bill type in the UI.
    var icon: String {
        switch self {
        case .creditCard: return "creditcard.fill"
        case .utility: return "bolt.fill"
        case .subscription: return "repeat"
        case .loan: return "banknote.fill"
        case .rent: return "house.fill"
        case .insurance: return "shield.fill"
        case .other: return "doc.text.fill"
        }
    }
}

// MARK: - Bill Reminder

/// Represents an upcoming bill payment extracted from a reminder SMS.
///
/// Bill reminders are parsed from SMS messages sent by banks, utilities, and service
/// providers alerting users about upcoming payment due dates. The AI assistant
/// aggregates these to show a consolidated view of upcoming payments.
///
/// ## Parsing Sources
/// Bill reminders are extracted from messages like:
/// - "Your credit card payment of Rs. 5000 is due on 15-Jan"
/// - "Electricity bill due: Rs. 1,234. Pay by 20th to avoid late fee"
/// - "Your Netflix subscription will renew on Jan 15"
///
/// ## Relationships
/// - Type: `BillType` - Categorizes the type of bill
struct BillReminder: Identifiable {
    /// Unique identifier, typically derived from the source message ID
    let id: String

    /// Category of bill (credit card, utility, etc.)
    let billType: BillType

    /// Payment due date, if specified in the message. May be nil if not parseable.
    let dueDate: Date?

    /// Amount due, if specified. May be nil for some reminder messages.
    let amount: Double?

    /// Currency code for the amount
    let currency: String

    /// Name of the biller or service provider (e.g., "HDFC Bank", "Netflix")
    let merchant: String

    /// The complete original SMS message text
    let originalMessageBody: String

    /// When the reminder message was received
    let messageDate: Date

    /// Returns true if the due date has passed (payment is late).
    var isOverdue: Bool {
        guard let dueDate = dueDate else { return false }
        return dueDate < Date()
    }

    /// Returns true if the payment is due within the next 7 days.
    /// Used to highlight urgent upcoming bills in the UI.
    var isDueSoon: Bool {
        guard let dueDate = dueDate else { return false }
        let daysUntilDue = Calendar.current.dateComponents([.day], from: Date(), to: dueDate).day ?? 0
        return daysUntilDue >= 0 && daysUntilDue <= 7
    }

    /// Returns the amount formatted with currency symbol, or nil if amount is unknown.
    var formattedAmount: String? {
        guard let amount = amount else { return nil }
        let symbol = currency == "INR" ? "₹" : "$"
        return "\(symbol)\(String(format: "%.2f", amount))"
    }

    /// Returns the due date in medium format, or nil if date is unknown.
    var formattedDueDate: String? {
        guard let dueDate = dueDate else { return nil }
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        return formatter.string(from: dueDate)
    }
}

// MARK: - Delivery Status

/// Stages of package delivery lifecycle, from order to delivery.
///
/// Used by `PackageStatus` to indicate the current state of a tracked package.
/// The status is parsed from shipping notification SMS messages.
enum DeliveryStatus: String, CaseIterable {
    /// Order has been placed but not yet shipped
    case ordered = "Ordered"

    /// Package has been handed to the carrier
    case shipped = "Shipped"

    /// Package is moving through the carrier network
    case inTransit = "In Transit"

    /// Package is with the local delivery agent for final delivery
    case outForDelivery = "Out for Delivery"

    /// Package has been successfully delivered
    case delivered = "Delivered"

    /// Delivery issue (failed attempt, address problem, etc.)
    case exception = "Exception"

    /// SF Symbol icon name for displaying this status in the UI.
    var icon: String {
        switch self {
        case .ordered: return "bag.fill"
        case .shipped: return "shippingbox.fill"
        case .inTransit: return "box.truck.fill"
        case .outForDelivery: return "figure.walk"
        case .delivered: return "checkmark.circle.fill"
        case .exception: return "exclamationmark.triangle.fill"
        }
    }

    /// Color name for status indication.
    /// - Blue: Waiting (ordered)
    /// - Orange: In progress (shipped, in transit)
    /// - Green: Positive (out for delivery, delivered)
    /// - Red: Problem (exception)
    var color: String {
        switch self {
        case .ordered: return "blue"
        case .shipped: return "orange"
        case .inTransit: return "orange"
        case .outForDelivery: return "green"
        case .delivered: return "green"
        case .exception: return "red"
        }
    }
}

// MARK: - Package Status

/// Represents a package being tracked, extracted from shipping notification SMS messages.
///
/// Package tracking information is parsed from SMS messages sent by e-commerce platforms
/// (Amazon, Flipkart, etc.) and shipping carriers (FedEx, UPS, Delhivery, etc.).
///
/// ## Parsing Sources
/// Package status is extracted from messages like:
/// - "Your Amazon order has shipped! Track: AWB123456"
/// - "Your package is out for delivery"
/// - "Delivered: Your order was delivered today at 2:30 PM"
///
/// ## Relationships
/// - Status: `DeliveryStatus` - Current stage in the delivery lifecycle
struct PackageStatus: Identifiable {
    /// Unique identifier, typically derived from the source message ID
    let id: String

    /// Shipping carrier name (e.g., "FedEx", "Delhivery", "BlueDart")
    let carrier: String?

    /// Package tracking number/AWB for looking up detailed status
    let trackingNumber: String?

    /// Current delivery status stage
    let status: DeliveryStatus

    /// Expected delivery date, if provided in the message
    let estimatedDelivery: Date?

    /// E-commerce platform or seller name (e.g., "Amazon", "Flipkart")
    let merchant: String?

    /// The complete original SMS message text
    let originalMessageBody: String

    /// When the status update message was received
    let messageDate: Date

    /// Returns the estimated delivery date in medium format, or nil if unknown.
    var formattedEstimatedDelivery: String? {
        guard let estimatedDelivery = estimatedDelivery else { return nil }
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        return formatter.string(from: estimatedDelivery)
    }
}

// MARK: - Balance Info

/// Represents an account balance notification extracted from a bank SMS.
///
/// Banks often send balance information after transactions or as periodic updates.
/// The AI assistant parses these to provide a quick view of recent account balances.
///
/// ## Parsing Sources
/// Balance info is extracted from messages like:
/// - "Avl Bal: INR 12,345.67 in A/c XX1234"
/// - "Your savings account balance is Rs. 50,000"
/// - "Balance after txn: $1,234.56"
///
/// ## Note on Accuracy
/// This represents the balance at the time of the message. It may not reflect
/// the current balance due to subsequent transactions.
struct BalanceInfo: Identifiable {
    /// Unique identifier, typically derived from the source message ID
    let id: String

    /// Type of account (e.g., "Savings", "Current", "Credit Card")
    let accountType: String

    /// Account balance amount, may be nil if not parseable
    let balance: Double?

    /// Currency code for the balance
    let currency: String

    /// Date/time when this balance was reported
    let asOfDate: Date

    /// Bank or financial institution name (e.g., "HDFC Bank", "Chase")
    let institution: String?

    /// The complete original SMS message text
    let originalMessageBody: String

    /// Returns the balance formatted with currency symbol, or nil if balance is unknown.
    var formattedBalance: String? {
        guard let balance = balance else { return nil }
        let symbol = currency == "INR" ? "₹" : "$"
        return "\(symbol)\(String(format: "%.2f", balance))"
    }

    /// Returns the as-of date in medium date and short time format.
    var formattedDate: String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: asOfDate)
    }
}

// MARK: - OTP Info

/// Represents a One-Time Password (OTP) extracted from an SMS message.
///
/// OTPs are commonly used for two-factor authentication (2FA) and transaction verification.
/// The AI assistant identifies and extracts these codes for easy copying, helping users
/// quickly authenticate without manually searching through messages.
///
/// ## Parsing Sources
/// OTPs are extracted from messages like:
/// - "Your OTP is 123456. Valid for 10 minutes."
/// - "Use code 4567 to verify your login"
/// - "Transaction OTP: 789012. Do not share."
///
/// ## Security Considerations
/// OTPs are displayed for convenience but should be treated as sensitive.
/// The app does not store OTPs persistently and marks them as expired after 10 minutes.
struct OTPInfo: Identifiable {
    /// Unique identifier, typically derived from the source message ID
    let id: String

    /// The OTP code itself (e.g., "123456")
    let code: String

    /// Service or sender that generated the OTP (e.g., "Google", "HDFC Bank")
    let source: String

    /// When the OTP message was received
    let timestamp: Date

    /// Stated validity period from the message (e.g., "10 minutes"), if specified
    let expiresIn: String?

    /// The complete original SMS message text
    let originalMessageBody: String

    /// Returns true if the OTP is likely expired (received more than 10 minutes ago).
    /// This is a heuristic based on typical OTP validity periods.
    var isExpired: Bool {
        let minutesSinceReceived = Calendar.current.dateComponents([.minute], from: timestamp, to: Date()).minute ?? 0
        return minutesSinceReceived > 10 // Consider OTPs expired after 10 minutes
    }

    /// Returns the timestamp in short date and time format.
    var formattedTimestamp: String {
        let formatter = DateFormatter()
        formatter.dateStyle = .short
        formatter.timeStyle = .short
        return formatter.string(from: timestamp)
    }

    /// Returns a relative time string (e.g., "2 min ago", "1 hr ago").
    /// Useful for showing how fresh the OTP is.
    var timeAgo: String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(for: timestamp, relativeTo: Date())
    }
}

// MARK: - Recurring Expense

/// Represents a detected recurring expense (subscription or regular payment).
///
/// The AI assistant analyzes transaction patterns to identify recurring expenses.
/// This helps users understand their subscription costs and regular financial commitments.
///
/// ## Detection Logic
/// Recurring expenses are identified by:
/// - Same merchant with similar amounts appearing multiple times
/// - Regular intervals between charges (weekly, monthly, yearly)
/// - Known subscription services (Netflix, Spotify, etc.)
///
/// ## Relationships
/// - Frequency: `RecurringFrequency` - The detected billing cycle
struct RecurringExpense: Identifiable {
    /// Unique identifier for this recurring expense
    let id: String

    /// Merchant or service name (e.g., "Netflix", "Spotify", "Gym Membership")
    let merchant: String

    /// Typical charge amount (may vary slightly between occurrences)
    let amount: Double

    /// Currency code for the amount
    let currency: String

    /// Detected billing frequency
    let frequency: RecurringFrequency

    /// Date of the most recent charge
    let lastCharge: Date

    /// Number of times this charge has been detected
    let occurrences: Int

    /// Returns the amount formatted with currency symbol.
    var formattedAmount: String {
        let symbol = currency == "INR" ? "₹" : "$"
        return "\(symbol)\(String(format: "%.2f", amount))"
    }

    /// Returns a short frequency suffix for compact display (e.g., "/mo" for monthly).
    var frequencyLabel: String {
        switch frequency {
        case .weekly: return "/wk"
        case .monthly: return "/mo"
        case .yearly: return "/yr"
        }
    }
}

/// Billing frequency for recurring expenses.
enum RecurringFrequency: String {
    /// Charged approximately every week
    case weekly = "Weekly"

    /// Charged approximately every month
    case monthly = "Monthly"

    /// Charged approximately every year
    case yearly = "Yearly"
}

// MARK: - Smart Digest

/// A comprehensive financial summary combining multiple data sources.
///
/// The Smart Digest provides a dashboard-style overview of the user's financial activity,
/// combining spending analysis, bill reminders, package tracking, and subscription costs.
/// It's designed as the primary response for general summary queries.
///
/// ## Usage
/// Created in response to queries like:
/// - "Give me a summary"
/// - "What's my financial overview?"
/// - "Show me my digest"
struct SmartDigest {
    /// Total spending for the current calendar month
    let totalSpentThisMonth: Double

    /// Total spending for the previous calendar month (for comparison)
    let totalSpentLastMonth: Double

    /// Percentage change from last month (positive = spending increase)
    let spendingChange: Double

    /// Number of transactions this month
    let transactionCount: Int

    /// Number of bills due in the next 7 days
    let upcomingBills: Int

    /// Number of packages currently in transit or recently delivered
    let recentPackages: Int

    /// Total monthly subscription costs
    let subscriptionTotal: Double

    /// The merchant with the highest spending this month
    let topMerchant: String?

    /// Currency code for all amounts
    let currency: String

    /// Returns this month's spending formatted with currency symbol.
    var formattedThisMonth: String {
        let symbol = currency == "INR" ? "₹" : "$"
        return "\(symbol)\(String(format: "%.2f", totalSpentThisMonth))"
    }

    /// Returns last month's spending formatted with currency symbol.
    var formattedLastMonth: String {
        let symbol = currency == "INR" ? "₹" : "$"
        return "\(symbol)\(String(format: "%.2f", totalSpentLastMonth))"
    }

    /// Returns the total subscription cost formatted with currency symbol.
    var formattedSubscriptionTotal: String {
        let symbol = currency == "INR" ? "₹" : "$"
        return "\(symbol)\(String(format: "%.2f", subscriptionTotal))"
    }
}

// MARK: - AI Query Type

/// Categorizes user queries to the AI assistant for appropriate handling.
///
/// The query type is determined by parsing the user's natural language input
/// and matching keywords, phrases, and patterns. This enables the assistant
/// to provide structured, relevant responses.
///
/// ## Query Parsing
/// Examples of how queries map to types:
/// - "How much did I spend at Amazon?" -> `.spending(merchant: "Amazon", timeFilter: nil)`
/// - "Show my upcoming bills" -> `.upcomingBills`
/// - "Where's my package?" -> `.packageTracking`
/// - "What's my latest OTP?" -> `.otpFinding`
enum AIQueryType {
    /// Spending analysis query, optionally filtered by merchant and/or time period
    case spending(merchant: String?, timeFilter: TimeFilter?)

    /// Query for upcoming bill payments and due dates
    case upcomingBills

    /// Query for package delivery status and tracking
    case packageTracking

    /// Query for recent account balance information
    case balanceQuery

    /// Query to find recent OTP codes
    case otpFinding

    /// List of transactions, optionally filtered by merchant and/or time period
    case transactionList(merchant: String?, timeFilter: TimeFilter?)

    /// Query for recurring subscriptions and their costs
    case subscriptions

    /// General financial summary/digest request
    case summary

    /// Month-over-month spending comparison and trends
    case spendingTrends

    /// Currency totals breakdown across all messages
    case currencyTotals

    /// General help or capability explanation
    case generalHelp

    /// Unrecognized query - includes the original text for potential fallback handling
    case unknown(query: String)
}

// MARK: - AI Response Details

/// The structured data payload of an AI assistant response.
///
/// Each case contains the specific data type relevant to the query.
/// The UI uses pattern matching on this enum to render appropriate visualizations.
///
/// ## Rendering
/// Different cases render differently:
/// - `.spending` -> Charts and breakdowns
/// - `.bills` -> List with due date indicators
/// - `.packages` -> Tracking timeline view
/// - `.otps` -> Copyable code cards
/// etc.
enum AIResponseDetails {
    /// Spending analysis with totals, breakdowns, and transactions
    case spending(SpendingAnalysis)

    /// List of upcoming bill reminders
    case bills([BillReminder])

    /// List of tracked packages with delivery status
    case packages([PackageStatus])

    /// List of recent account balance notifications
    case balances([BalanceInfo])

    /// List of recent OTP codes (for copy functionality)
    case otps([OTPInfo])

    /// Raw transaction list without aggregation
    case transactions([ParsedTransaction])

    /// Detected recurring subscriptions
    case subscriptions([RecurringExpense])

    /// Comprehensive financial digest/summary
    case digest(SmartDigest)

    /// Month-over-month comparison (this month analysis, last month analysis)
    case trends(SpendingAnalysis, SpendingAnalysis)

    /// Currency totals breakdown (currency code -> total amount map)
    case currencyTotals([String: Double])

    /// Help text explaining assistant capabilities
    case help(String)

    /// No results found message with explanation
    case noResults(String)

    /// Error message when query processing fails
    case error(String)
}

// MARK: - AI Response

/// Complete response from the AI assistant for a user query.
///
/// Contains the parsed query type, a human-readable summary, structured data details,
/// and suggested follow-up questions to guide continued conversation.
///
/// ## Relationships
/// - Query: `AIQueryType` - The interpreted query type
/// - Details: `AIResponseDetails` - The structured response data
struct AIResponse {
    /// The interpreted type of the user's query
    let queryType: AIQueryType

    /// Human-readable summary of the response (e.g., "You spent $500 this month")
    let summary: String

    /// Structured data for detailed display
    let details: AIResponseDetails

    /// Suggested follow-up queries to continue the conversation
    /// (e.g., "Show spending by category", "Compare to last month")
    let suggestedFollowUps: [String]
}

// MARK: - Chat Message

/// A single message in the AI assistant chat conversation.
///
/// The chat interface displays a scrollable list of messages, alternating between
/// user queries and assistant responses. Each message has a timestamp for display
/// and sorting.
///
/// ## Content Types
/// - User messages are always text (`.text`)
/// - Assistant messages can be text or rich responses (`.response`)
///
/// ## Relationships
/// - Content: `AIChatContent` - The message content (text or structured response)
struct AIChatMessage: Identifiable {
    /// Unique identifier for this message (UUID-based)
    let id: String

    /// True if this message is from the user, false if from the assistant
    let isUser: Bool

    /// The message content
    let content: AIChatContent

    /// When this message was created
    let timestamp: Date

    /// Creates a new chat message with auto-generated ID and current timestamp.
    ///
    /// - Parameters:
    ///   - isUser: Whether this is a user message (true) or assistant message (false)
    ///   - content: The message content
    init(isUser: Bool, content: AIChatContent) {
        self.id = UUID().uuidString
        self.isUser = isUser
        self.content = content
        self.timestamp = Date()
    }
}

/// Content types for chat messages.
///
/// User messages are always plain text. Assistant messages may be plain text
/// (for simple responses or errors) or structured responses with rich data.
enum AIChatContent {
    /// Plain text content (user queries or simple assistant responses)
    case text(String)

    /// Structured AI response with data visualizations
    case response(AIResponse)
}

// MARK: - Quick Action

/// A predefined quick action button for the AI assistant interface.
///
/// Quick actions provide one-tap access to common queries, displayed as
/// cards or buttons in the assistant UI. They help users discover capabilities
/// and quickly access frequently-needed information.
///
/// ## Example Quick Actions
/// - "My Spending" - Shows spending summary
/// - "Find OTP" - Lists recent OTP codes
/// - "Track Packages" - Shows delivery status
struct AIQuickAction: Identifiable {
    /// Unique identifier for this action (UUID-based)
    let id: String

    /// SF Symbol name for the action icon
    let icon: String

    /// Primary label for the action (e.g., "My Spending")
    let title: String

    /// Secondary descriptive text (e.g., "View spending analysis")
    let subtitle: String

    /// The query to execute when this action is tapped
    let query: String

    /// Color name for the action card background or icon tint
    let color: String

    /// Creates a new quick action with auto-generated ID.
    ///
    /// - Parameters:
    ///   - icon: SF Symbol name
    ///   - title: Primary label text
    ///   - subtitle: Secondary description
    ///   - query: Query string to execute
    ///   - color: Color name for styling
    init(icon: String, title: String, subtitle: String, query: String, color: String) {
        self.id = UUID().uuidString
        self.icon = icon
        self.title = title
        self.subtitle = subtitle
        self.query = query
        self.color = color
    }
}
