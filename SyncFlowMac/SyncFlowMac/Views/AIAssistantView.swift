//
//  AIAssistantView.swift
//  SyncFlowMac
//
//  =============================================================================
//  PURPOSE:
//  This view provides an AI-powered assistant for analyzing SMS message data.
//  It presents a chat interface where users can ask questions about their
//  messages, and the AI analyzes local data to provide insights on spending,
//  bills, packages, subscriptions, OTP codes, and more.
//
//  USER INTERACTIONS:
//  - Type questions in the input bar and press Enter or tap send
//  - Tap quick action cards for common queries (spending, bills, packages, etc.)
//  - View smart digest summary of monthly activity
//  - Copy AI responses to clipboard
//  - Tap follow-up suggestions to continue the conversation
//  - Start a new chat session with the "New Chat" button
//  - Dismiss the view with the X button
//
//  STATE MANAGEMENT:
//  - @EnvironmentObject for MessageStore (provides message data for analysis)
//  - Local @State for chat history, input text, processing state
//  - Cached digest data to avoid recalculating on every render
//  - @FocusState for input field keyboard focus
//
//  PERFORMANCE CONSIDERATIONS:
//  - Message analysis limited to 500-1000 recent messages for performance
//  - Digest generation runs on background thread
//  - Results are cached to avoid reprocessing
//  - LazyVStack used for chat history virtualization
//  - AI processing happens asynchronously to keep UI responsive
//  =============================================================================

import SwiftUI

// MARK: - Main AI Assistant View

/// Chat interface for the AI assistant that analyzes message data.
/// Provides insights on spending, bills, packages, subscriptions, and more.
struct AIAssistantView: View {

    // MARK: - Environment

    /// Message store providing SMS data for analysis
    @EnvironmentObject var messageStore: MessageStore
    /// Environment dismiss action for closing the view
    @Environment(\.dismiss) private var dismiss

    // MARK: - Chat State

    /// Current text in the input field
    @State private var inputText = ""
    /// Array of chat messages (user queries and AI responses)
    @State private var chatHistory: [AIChatMessage] = []
    /// Whether the AI is currently processing a query
    @State private var isProcessing = false
    /// Focus state for the input field
    @FocusState private var isInputFocused: Bool

    // MARK: - Services

    /// AI assistant service for processing queries
    private let aiService = AIAssistantService.shared

    // MARK: - Digest State

    /// Whether to show the smart digest card
    @State private var showDigest = true
    /// Whether a response was just copied (for UI feedback)
    @State private var copiedResponse = false
    /// Cached digest data to avoid recomputation
    @State private var cachedDigest: SmartDigest? = nil
    /// Cached subscription count for digest display
    @State private var cachedSubscriptionCount: Int = 0
    /// Whether digest is currently being generated
    @State private var isLoadingDigest = true

    // MARK: - Quick Actions

    /// Predefined quick action buttons for common queries
    private let quickActions: [AIQuickAction] = [
        AIQuickAction(icon: "chart.bar.fill", title: "Spending", subtitle: "Track expenses", query: "How much did I spend this month?", color: "blue"),
        AIQuickAction(icon: "calendar.badge.clock", title: "Bills", subtitle: "Due dates", query: "Show my upcoming bills", color: "orange"),
        AIQuickAction(icon: "shippingbox.fill", title: "Packages", subtitle: "Track orders", query: "Track my packages", color: "green"),
        AIQuickAction(icon: "creditcard.fill", title: "Balance", subtitle: "Account info", query: "What's my account balance?", color: "purple"),
        AIQuickAction(icon: "repeat", title: "Subscriptions", subtitle: "Recurring charges", query: "Show my subscriptions", color: "indigo"),
        AIQuickAction(icon: "arrow.up.arrow.down", title: "Compare", subtitle: "vs last month", query: "Compare spending vs last month", color: "cyan"),
        AIQuickAction(icon: "key.fill", title: "OTPs", subtitle: "Verification codes", query: "Show recent OTP codes", color: "red"),
        AIQuickAction(icon: "list.bullet.rectangle", title: "Transactions", subtitle: "Recent activity", query: "List my recent transactions", color: "teal"),
    ]

    // MARK: - Body

    var body: some View {
        VStack(spacing: 0) {
            // Header
            header

            Divider()

            // Chat area
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 16) {
                        if chatHistory.isEmpty {
                            // Smart Digest Card
                            if showDigest {
                                smartDigestCard
                            }

                            welcomeSection
                            quickActionsGrid
                        }

                        ForEach(chatHistory) { message in
                            chatBubble(for: message)
                                .id(message.id)
                        }

                        if isProcessing {
                            typingIndicator
                        }
                    }
                    .padding()
                }
                .onChange(of: chatHistory.count) { _ in
                    if let lastId = chatHistory.last?.id {
                        withAnimation {
                            proxy.scrollTo(lastId, anchor: .bottom)
                        }
                    }
                }
            }

            Divider()

            // Input bar
            inputBar
        }
        .frame(minWidth: 500, minHeight: 600)
        .background(Color(NSColor.windowBackgroundColor))
    }

    // MARK: - Header

    /// Header section with AI avatar, title, new chat button, and close button.
    private var header: some View {
        HStack(spacing: 12) {
            // AI Avatar
            ZStack {
                Circle()
                    .fill(LinearGradient(
                        colors: [SyncFlowColors.adaptiveBlue, SyncFlowColors.adaptivePurple],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    ))
                    .frame(width: 40, height: 40)

                Text("AI")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundColor(.white)
            }

            VStack(alignment: .leading, spacing: 2) {
                Text("AI Assistant")
                    .font(.headline)

                Text("Understands your messages & spending")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            Spacer()

            // New Chat button - only show when there's chat history
            if !chatHistory.isEmpty {
                Button {
                    withAnimation {
                        chatHistory.removeAll()
                        cachedDigest = nil
                        isLoadingDigest = true
                        loadDigestAsync()
                    }
                } label: {
                    Image(systemName: "plus.message")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundColor(SyncFlowColors.adaptiveBlue)
                        .frame(width: 28, height: 28)
                        .background(SyncFlowColors.adaptiveBlue.opacity(0.1))
                        .clipShape(Circle())
                }
                .buttonStyle(.plain)
                .help("New Chat")
            }

            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundColor(.secondary)
                    .frame(width: 28, height: 28)
                    .background(Color(NSColor.controlBackgroundColor))
                    .clipShape(Circle())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(Color(NSColor.controlBackgroundColor).opacity(0.5))
    }

    // MARK: - Smart Digest Card

    /// Card showing monthly spending summary, subscriptions, bills, and packages.
    /// Loads data asynchronously on first appear.
    private var smartDigestCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                HStack(spacing: 8) {
                    Image(systemName: "chart.line.uptrend.xyaxis")
                        .foregroundColor(SyncFlowColors.adaptiveBlue)
                    Text("This Month")
                        .font(.headline)
                }
                Spacer()
                Button {
                    withAnimation {
                        showDigest = false
                    }
                } label: {
                    Image(systemName: "xmark")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.plain)
            }

            if isLoadingDigest {
                HStack {
                    ProgressView()
                        .scaleEffect(0.8)
                    Text("Analyzing messages...")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .center)
                .padding(.vertical, 20)
            } else if let digest = cachedDigest {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(digest.formattedThisMonth)
                            .font(.title)
                            .fontWeight(.bold)

                        if digest.spendingChange != 0 {
                            HStack(spacing: 4) {
                                Image(systemName: digest.spendingChange > 0 ? "arrow.up" : "arrow.down")
                                    .font(.caption2)
                                Text("\(String(format: "%.0f", abs(digest.spendingChange)))% vs last month")
                                    .font(.caption)
                            }
                            .foregroundColor(digest.spendingChange > 0 ? SyncFlowColors.adaptiveRed : SyncFlowColors.adaptiveGreen)
                        }
                    }

                    Spacer()

                    VStack(alignment: .trailing, spacing: 4) {
                        Text("\(digest.transactionCount)")
                            .font(.title)
                            .fontWeight(.bold)

                        Text("transactions")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
            }
        }
        .padding()
        .background(
            LinearGradient(
                colors: [SyncFlowColors.adaptiveBlue.opacity(0.1), SyncFlowColors.adaptivePurple.opacity(0.1)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        )
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .stroke(SyncFlowColors.adaptiveBlue.opacity(0.2), lineWidth: 1)
        )
        .padding(.horizontal)
        .onAppear {
            loadDigestAsync()
        }
    }

    /// Loads digest data asynchronously on a background thread.
    /// Limits processing to 500 most recent messages for performance.
    private func loadDigestAsync() {
        guard cachedDigest == nil else { return }
        isLoadingDigest = true

        DispatchQueue.global(qos: .userInitiated).async {
            // Limit to most recent 500 messages for faster processing
            let recentMessages = Array(messageStore.messages.prefix(500))
            let digest = aiService.generateSmartDigest(messages: recentMessages)
            let subscriptions = aiService.detectRecurringExpenses(messages: recentMessages)

            DispatchQueue.main.async {
                cachedDigest = digest
                cachedSubscriptionCount = subscriptions.count
                isLoadingDigest = false
            }
        }
    }

    // MARK: - Welcome Section

    /// Welcome message shown when chat history is empty.
    /// Introduces the AI assistant and its capabilities.
    private var welcomeSection: some View {
        VStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(LinearGradient(
                        colors: [SyncFlowColors.adaptiveBlue.opacity(0.2), SyncFlowColors.adaptivePurple.opacity(0.2)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    ))
                    .frame(width: 80, height: 80)

                Text("AI")
                    .font(.system(size: 28, weight: .bold))
                    .foregroundColor(SyncFlowColors.adaptiveBlue)
            }

            Text("How can I help you?")
                .font(.title2)
                .fontWeight(.semibold)

            Text("I can analyze your SMS messages to find spending patterns, track packages, find bills, and more.")
                .font(.subheadline)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
        }
        .padding(.top, 32)
        .padding(.bottom, 16)
    }

    // MARK: - Quick Actions Grid

    /// Grid of quick action buttons for common queries.
    /// Each button sends a predefined query to the AI.
    private var quickActionsGrid: some View {
        LazyVGrid(columns: [
            GridItem(.flexible()),
            GridItem(.flexible())
        ], spacing: 12) {
            ForEach(quickActions) { action in
                quickActionCard(action)
            }
        }
        .padding(.horizontal, 16)
    }

    /// Individual quick action card with icon, title, and subtitle.
    /// - Parameter action: The quick action data to display
    private func quickActionCard(_ action: AIQuickAction) -> some View {
        Button {
            processQuery(action.query)
        } label: {
            HStack(spacing: 12) {
                Image(systemName: action.icon)
                    .font(.system(size: 20))
                    .foregroundColor(colorForString(action.color))
                    .frame(width: 36, height: 36)
                    .background(colorForString(action.color).opacity(0.15))
                    .clipShape(RoundedRectangle(cornerRadius: 8))

                VStack(alignment: .leading, spacing: 2) {
                    Text(action.title)
                        .font(.subheadline)
                        .fontWeight(.medium)
                        .foregroundColor(.primary)

                    Text(action.subtitle)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }

                Spacer()
            }
            .padding(12)
            .background(Color(NSColor.controlBackgroundColor))
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(Color(NSColor.separatorColor), lineWidth: 0.5)
            )
        }
        .buttonStyle(.plain)
    }

    // MARK: - Chat Bubble

    /// Creates a chat bubble for a user or AI message.
    /// User messages align right, AI messages align left with avatar.
    /// - Parameter message: The chat message to display
    private func chatBubble(for message: AIChatMessage) -> some View {
        HStack(alignment: .top, spacing: 8) {
            if !message.isUser {
                aiAvatar
            } else {
                Spacer(minLength: 60)
            }

            VStack(alignment: message.isUser ? .trailing : .leading, spacing: 4) {
                switch message.content {
                case .text(let text):
                    textBubble(text: text, isUser: message.isUser)
                case .response(let response):
                    responseBubble(response: response)
                }

                Text(formatTime(message.timestamp))
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }

            if message.isUser {
                userAvatar
            } else {
                Spacer(minLength: 60)
            }
        }
    }

    // MARK: - Avatars

    /// AI avatar with gradient background
    private var aiAvatar: some View {
        ZStack {
            Circle()
                .fill(LinearGradient(
                    colors: [SyncFlowColors.adaptiveBlue, SyncFlowColors.adaptivePurple],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                ))
                .frame(width: 28, height: 28)

            Text("AI")
                .font(.system(size: 10, weight: .bold))
                .foregroundColor(.white)
        }
    }

    /// User avatar with person icon
    private var userAvatar: some View {
        ZStack {
            Circle()
                .fill(SyncFlowColors.adaptiveGray.opacity(0.3))
                .frame(width: 28, height: 28)

            Image(systemName: "person.fill")
                .font(.system(size: 12))
                .foregroundColor(SyncFlowColors.adaptiveGray)
        }
    }

    // MARK: - Message Bubbles

    /// Simple text bubble for user messages
    private func textBubble(text: String, isUser: Bool) -> some View {
        Text(text)
            .font(.body)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(isUser ? SyncFlowColors.primary : Color(NSColor.controlBackgroundColor))
            .foregroundColor(isUser ? .white : .primary)
            .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    /// Rich response bubble for AI answers with various result card types.
    /// Includes summary, detailed results, and follow-up suggestions.
    private func responseBubble(response: AIResponse) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            // Summary with copy button
            HStack(alignment: .top) {
                Text(response.summary)
                    .font(.subheadline)
                    .foregroundColor(.secondary)

                Spacer()

                Button {
                    copyResponseToClipboard(response)
                } label: {
                    Image(systemName: copiedResponse ? "checkmark" : "doc.on.doc")
                        .font(.caption)
                        .foregroundColor(copiedResponse ? SyncFlowColors.adaptiveGreen : .secondary)
                }
                .buttonStyle(.plain)
                .help("Copy to clipboard")
            }

            // Details
            switch response.details {
            case .spending(let analysis):
                spendingCard(analysis)
            case .bills(let bills):
                billsCard(bills)
            case .packages(let packages):
                packagesCard(packages)
            case .balances(let balances):
                balancesCard(balances)
            case .otps(let otps):
                otpsCard(otps)
            case .transactions(let transactions):
                transactionsCard(transactions)
            case .subscriptions(let recurring):
                subscriptionsCard(recurring)
            case .digest(let digest):
                digestCard(digest)
            case .trends(let thisMonth, let lastMonth):
                trendsCard(thisMonth: thisMonth, lastMonth: lastMonth)
            case .currencyTotals(let totals):
                currencyTotalsCard(totals)
            case .help(let text):
                helpCard(text)
            case .noResults(let message):
                noResultsCard(message)
            case .error(let message):
                errorCard(message)
            }

            // Follow-up suggestions
            if !response.suggestedFollowUps.isEmpty {
                followUpSuggestions(response.suggestedFollowUps)
            }
        }
        .padding(12)
        .background(Color(NSColor.controlBackgroundColor))
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .frame(maxWidth: 400, alignment: .leading)
    }

    // MARK: - Result Cards
    // These methods create specialized cards for different AI response types

    /// Card displaying spending analysis with total, stats, and top merchants
    private func spendingCard(_ analysis: SpendingAnalysis) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            // Total
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Total Spent")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Text(analysis.formattedTotal)
                        .font(.title2)
                        .fontWeight(.bold)
                }
                Spacer()
                Text(analysis.timeRange)
                    .font(.caption)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(SyncFlowColors.adaptiveBlue.opacity(0.1))
                    .clipShape(Capsule())
            }

            Divider()

            // Stats
            HStack(spacing: 24) {
                statPill(label: "Transactions", value: "\(analysis.transactionCount)")
                statPill(label: "Average", value: analysis.formattedAverage)
            }

            // Top merchants
            if !analysis.byMerchant.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Top Merchants")
                        .font(.caption)
                        .foregroundColor(.secondary)

                    ForEach(Array(analysis.byMerchant.prefix(5)), id: \.merchant) { item in
                        HStack {
                            Text(item.merchant)
                                .font(.subheadline)
                            Spacer()
                            Text(formatCurrency(item.amount, analysis.currency))
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                        }
                    }
                }
            }
        }
    }

    /// Card displaying upcoming bills with due dates and amounts
    private func billsCard(_ bills: [BillReminder]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(bills.prefix(5)) { bill in
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Image(systemName: bill.billType.icon)
                            .foregroundColor(bill.isOverdue ? SyncFlowColors.adaptiveRed : bill.isDueSoon ? SyncFlowColors.adaptiveOrange : SyncFlowColors.adaptiveBlue)
                            .frame(width: 24)

                        VStack(alignment: .leading, spacing: 2) {
                            Text(bill.merchant == "Unknown" ? bill.billType.rawValue : bill.merchant)
                                .font(.subheadline)
                                .fontWeight(.medium)

                            HStack(spacing: 8) {
                                if let dueDate = bill.formattedDueDate {
                                    Text("Due: \(dueDate)")
                                        .font(.caption)
                                        .foregroundColor(bill.isOverdue ? SyncFlowColors.adaptiveRed : .secondary)
                                }

                                Text(formatRelativeDate(bill.messageDate))
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                        }

                        Spacer()

                        if let amount = bill.formattedAmount {
                            Text(amount)
                                .font(.subheadline)
                                .fontWeight(.medium)
                        }
                    }

                    // Show message preview for context
                    Text(bill.originalMessageBody.prefix(100) + (bill.originalMessageBody.count > 100 ? "..." : ""))
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .lineLimit(2)
                        .padding(.leading, 32)
                }
                .padding(.vertical, 4)

                if bill.id != bills.prefix(5).last?.id {
                    Divider()
                }
            }
        }
    }

    private func formatRelativeDate(_ date: Date) -> String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(for: date, relativeTo: Date())
    }

    /// Card displaying package tracking with status and carrier info
    private func packagesCard(_ packages: [PackageStatus]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(packages.prefix(6)) { package in
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Image(systemName: package.status.icon)
                            .font(.system(size: 16))
                            .foregroundColor(colorForDeliveryStatus(package.status))
                            .frame(width: 28, height: 28)
                            .background(colorForDeliveryStatus(package.status).opacity(0.15))
                            .clipShape(RoundedRectangle(cornerRadius: 6))

                        VStack(alignment: .leading, spacing: 2) {
                            Text(package.merchant ?? package.carrier ?? "Package")
                                .font(.subheadline)
                                .fontWeight(.medium)

                            HStack(spacing: 4) {
                                Text(package.status.rawValue)
                                    .foregroundColor(colorForDeliveryStatus(package.status))
                                if let carrier = package.carrier {
                                    Text("•")
                                    Text(carrier)
                                }
                                Text("•")
                                Text(formatRelativeDate(package.messageDate))
                            }
                            .font(.caption)
                        }

                        Spacer()

                        // Show tracking number if available
                        if let tracking = package.trackingNumber {
                            Text(String(tracking.suffix(6)))
                                .font(.caption)
                                .fontDesign(.monospaced)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(SyncFlowColors.adaptiveGray.opacity(0.1))
                                .clipShape(Capsule())
                        }
                    }

                    // Show message preview for context
                    Text(package.originalMessageBody.prefix(80) + (package.originalMessageBody.count > 80 ? "..." : ""))
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .lineLimit(2)
                        .padding(.leading, 36)
                }
                .padding(.vertical, 6)

                if package.id != packages.prefix(6).last?.id {
                    Divider()
                }
            }
        }
    }

    /// Card displaying account balances from bank notifications
    private func balancesCard(_ balances: [BalanceInfo]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            // Filter to show balances with amounts first
            let withAmounts = balances.filter { $0.balance != nil && $0.balance! > 0 }
            let toShow = withAmounts.isEmpty ? balances : withAmounts

            ForEach(toShow.prefix(6)) { balance in
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Image(systemName: "creditcard.fill")
                            .foregroundColor(SyncFlowColors.adaptiveBlue)
                            .frame(width: 24)

                        VStack(alignment: .leading, spacing: 2) {
                            Text(balance.institution ?? balance.accountType)
                                .font(.subheadline)
                                .fontWeight(.medium)

                            HStack(spacing: 4) {
                                Text(balance.accountType)
                                Text("•")
                                Text(balance.formattedDate)
                            }
                            .font(.caption)
                            .foregroundColor(.secondary)
                        }

                        Spacer()

                        if let balanceAmount = balance.balance, balanceAmount > 0 {
                            Text(balance.formattedBalance ?? "")
                                .font(.title3)
                                .fontWeight(.semibold)
                                .foregroundColor(SyncFlowColors.adaptiveGreen)
                        } else {
                            Text("--")
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                        }
                    }

                    // Show message preview for context
                    Text(balance.originalMessageBody.prefix(80) + (balance.originalMessageBody.count > 80 ? "..." : ""))
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .lineLimit(2)
                        .padding(.leading, 32)
                }
                .padding(.vertical, 6)

                if balance.id != toShow.prefix(6).last?.id {
                    Divider()
                }
            }

            if withAmounts.isEmpty && !balances.isEmpty {
                Text("Could not extract exact balance amounts from messages")
                    .font(.caption)
                    .foregroundColor(SyncFlowColors.adaptiveOrange)
                    .padding(.top, 4)
            }
        }
    }

    /// Card displaying OTP codes with copy functionality and expiry status
    private func otpsCard(_ otps: [OTPInfo]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(otps.prefix(10)) { otp in
                HStack {
                    Text(otp.code)
                        .font(.system(.title3, design: .monospaced))
                        .fontWeight(.bold)
                        .foregroundColor(otp.isExpired ? .secondary : .primary)

                    VStack(alignment: .leading, spacing: 2) {
                        Text(otp.source)
                            .font(.caption)
                            .foregroundColor(.secondary)

                        Text(otp.timeAgo)
                            .font(.caption2)
                            .foregroundColor(.secondary)
                    }

                    Spacer()

                    if otp.isExpired {
                        Text("Expired")
                            .font(.caption2)
                            .foregroundColor(SyncFlowColors.adaptiveRed)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(SyncFlowColors.adaptiveRed.opacity(0.1))
                            .clipShape(Capsule())
                    }

                    Button {
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(otp.code, forType: .string)
                    } label: {
                        Image(systemName: "doc.on.doc")
                            .font(.caption)
                    }
                    .buttonStyle(.plain)
                    .disabled(otp.isExpired)
                }
                .padding(.vertical, 4)

                if otp.id != otps.prefix(10).last?.id {
                    Divider()
                }
            }
        }
    }

    /// Card displaying recent transactions with merchant and amount
    private func transactionsCard(_ transactions: [ParsedTransaction]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(transactions.prefix(10)) { txn in
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Image(systemName: txn.category.icon)
                            .foregroundColor(SyncFlowColors.adaptiveBlue)
                            .frame(width: 24)

                        VStack(alignment: .leading, spacing: 2) {
                            Text(txn.merchant ?? txn.category.rawValue)
                                .font(.subheadline)
                                .fontWeight(.medium)

                            Text(txn.formattedDate)
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }

                        Spacer()

                        Text(txn.formattedAmount)
                            .font(.subheadline)
                            .fontWeight(.medium)
                            .foregroundColor(SyncFlowColors.adaptiveRed)
                    }

                    // Show message preview when merchant is unknown
                    if txn.merchant == nil {
                        Text(txn.originalMessageBody.prefix(80) + (txn.originalMessageBody.count > 80 ? "..." : ""))
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .lineLimit(2)
                            .padding(.leading, 32)
                    }
                }
                .padding(.vertical, 4)

                if txn.id != transactions.prefix(10).last?.id {
                    Divider()
                }
            }
        }
    }

    /// Card displaying detected recurring subscriptions with monthly estimate
    private func subscriptionsCard(_ recurring: [RecurringExpense]) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            // Monthly total
            let monthlyTotal = recurring.reduce(0.0) { sum, r in
                switch r.frequency {
                case .monthly: return sum + r.amount
                case .yearly: return sum + r.amount / 12
                case .weekly: return sum + r.amount * 4
                }
            }
            let currency = recurring.first?.currency ?? "USD"

            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Estimated Monthly")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Text(formatCurrency(monthlyTotal, currency))
                        .font(.title2)
                        .fontWeight(.bold)
                }
                Spacer()
                Image(systemName: "repeat")
                    .font(.title2)
                    .foregroundColor(SyncFlowColors.adaptiveIndigo)
            }

            Divider()

            ForEach(recurring.prefix(6)) { expense in
                HStack {
                    Image(systemName: "repeat")
                        .foregroundColor(SyncFlowColors.adaptiveIndigo)
                        .frame(width: 24)

                    VStack(alignment: .leading, spacing: 2) {
                        Text(expense.merchant)
                            .font(.subheadline)
                            .fontWeight(.medium)

                        HStack(spacing: 4) {
                            Text(expense.frequency.rawValue)
                            Text("•")
                            Text("\(expense.occurrences) charges")
                        }
                        .font(.caption)
                        .foregroundColor(.secondary)
                    }

                    Spacer()

                    Text("\(expense.formattedAmount)\(expense.frequencyLabel)")
                        .font(.subheadline)
                        .fontWeight(.medium)
                }
                .padding(.vertical, 4)
            }
        }
    }

    /// Card displaying smart digest summary with key metrics
    private func digestCard(_ digest: SmartDigest) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            // This month spending
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("This Month")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Text(digest.formattedThisMonth)
                        .font(.title2)
                        .fontWeight(.bold)
                }
                Spacer()
                if digest.spendingChange != 0 {
                    HStack(spacing: 2) {
                        Image(systemName: digest.spendingChange > 0 ? "arrow.up" : "arrow.down")
                        Text("\(String(format: "%.1f", abs(digest.spendingChange)))%")
                    }
                    .font(.caption)
                    .foregroundColor(digest.spendingChange > 0 ? SyncFlowColors.adaptiveRed : SyncFlowColors.adaptiveGreen)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background((digest.spendingChange > 0 ? SyncFlowColors.adaptiveRed : SyncFlowColors.adaptiveGreen).opacity(0.1))
                    .clipShape(Capsule())
                }
            }

            Divider()

            // Stats grid
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                statBox(icon: "creditcard.fill", title: "\(digest.transactionCount)", subtitle: "Transactions", color: .blue)

                if let topMerchant = digest.topMerchant {
                    statBox(icon: "storefront.fill", title: topMerchant, subtitle: "Top Merchant", color: .purple)
                }

                if digest.subscriptionTotal > 0 {
                    statBox(icon: "repeat", title: digest.formattedSubscriptionTotal, subtitle: "Subscriptions/mo", color: .indigo)
                }

                if digest.upcomingBills > 0 {
                    statBox(icon: "calendar.badge.clock", title: "\(digest.upcomingBills)", subtitle: "Bills Due", color: .orange)
                }
            }
        }
    }

    /// Card showing spending comparison between this month and last month
    private func trendsCard(thisMonth: SpendingAnalysis, lastMonth: SpendingAnalysis) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            // Comparison
            HStack(spacing: 24) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("This Month")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Text(thisMonth.formattedTotal)
                        .font(.title3)
                        .fontWeight(.bold)
                    Text("\(thisMonth.transactionCount) transactions")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }

                VStack(alignment: .leading, spacing: 2) {
                    Text("Last Month")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Text(lastMonth.formattedTotal)
                        .font(.title3)
                        .fontWeight(.medium)
                    Text("\(lastMonth.transactionCount) transactions")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }
            }

            Divider()

            // Change indicator
            let diff = thisMonth.total - lastMonth.total
            let percentChange = lastMonth.total > 0 ? ((diff / lastMonth.total) * 100) : 0

            HStack {
                Image(systemName: diff > 0 ? "arrow.up.circle.fill" : "arrow.down.circle.fill")
                    .foregroundColor(diff > 0 ? SyncFlowColors.adaptiveRed : SyncFlowColors.adaptiveGreen)

                Text(diff > 0 ? "Spending increased" : "Spending decreased")
                    .font(.subheadline)

                Spacer()

                Text("\(String(format: "%.1f", abs(percentChange)))%")
                    .font(.subheadline)
                    .fontWeight(.semibold)
                    .foregroundColor(diff > 0 ? SyncFlowColors.adaptiveRed : SyncFlowColors.adaptiveGreen)
            }
            .padding(8)
            .background(SyncFlowColors.adaptiveGray.opacity(0.1))
            .clipShape(RoundedRectangle(cornerRadius: 8))
        }
    }

    // MARK: - Helper Components

    /// Compact stat box with icon, value, and label
    private func statBox(icon: String, title: String, subtitle: String, color: Color) -> some View {
        HStack(spacing: 8) {
            Image(systemName: icon)
                .foregroundColor(color)
                .frame(width: 24)

            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(.subheadline)
                    .fontWeight(.medium)
                    .lineLimit(1)
                Text(subtitle)
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
        }
        .padding(8)
        .background(SyncFlowColors.adaptiveGray.opacity(0.05))
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    /// Card displaying currency totals breakdown
    private func currencyTotalsCard(_ totals: [String: Double]) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            if totals.isEmpty {
                Text("No currency amounts found in messages")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
            } else {
                ForEach(totals.sorted(by: { $0.value > $1.value }), id: \.key) { currency, amount in
                    HStack {
                        Text(getCurrencySymbol(currency))
                            .font(.title2)
                            .foregroundColor(.primary)

                        VStack(alignment: .leading, spacing: 2) {
                            Text(String(format: "%.2f", amount))
                                .font(.title3)
                                .fontWeight(.semibold)
                            Text(currency)
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }

                        Spacer()
                    }
                    .padding(.vertical, 8)

                    if currency != totals.sorted(by: { $0.value > $1.value }).last?.key {
                        Divider()
                    }
                }
            }
        }
        .padding()
        .background(Color(nsColor: .controlBackgroundColor))
        .cornerRadius(12)
    }

    /// Simple help text card
    private func helpCard(_ text: String) -> some View {
        Text(text)
            .font(.subheadline)
            .lineSpacing(4)
    }

    /// Card shown when AI query returns no results
    private func noResultsCard(_ message: String) -> some View {
        HStack {
            Image(systemName: "magnifyingglass")
                .foregroundColor(.secondary)
            Text(message)
                .font(.subheadline)
                .foregroundColor(.secondary)
        }
    }

    /// Card shown when an error occurs during processing
    private func errorCard(_ message: String) -> some View {
        HStack {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundColor(SyncFlowColors.adaptiveRed)
            Text(message)
                .font(.subheadline)
                .foregroundColor(SyncFlowColors.adaptiveRed)
        }
    }

    /// Displays clickable follow-up query suggestions
    private func followUpSuggestions(_ suggestions: [String]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Try asking:")
                .font(.caption)
                .foregroundColor(.secondary)

            FlowLayout(spacing: 8) {
                ForEach(suggestions, id: \.self) { suggestion in
                    Button {
                        processQuery(suggestion)
                    } label: {
                        Text(suggestion)
                            .font(.caption)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 6)
                            .background(SyncFlowColors.adaptiveBlue.opacity(0.1))
                            .foregroundColor(SyncFlowColors.adaptiveBlue)
                            .clipShape(Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    // MARK: - Typing Indicator

    /// Animated typing indicator shown while AI is processing
    private var typingIndicator: some View {
        HStack(alignment: .top, spacing: 8) {
            aiAvatar

            HStack(spacing: 4) {
                ForEach(0..<3) { index in
                    Circle()
                        .fill(SyncFlowColors.adaptiveGray)
                        .frame(width: 8, height: 8)
                        .opacity(0.5)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 12)
            .background(Color(NSColor.controlBackgroundColor))
            .clipShape(RoundedRectangle(cornerRadius: 16))

            Spacer()
        }
    }

    // MARK: - Input Bar

    /// Text input field with send button for user queries
    private var inputBar: some View {
        HStack(spacing: 12) {
            TextField("Ask about spending, bills, packages...", text: $inputText)
                .textFieldStyle(.plain)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Color(NSColor.controlBackgroundColor))
                .clipShape(RoundedRectangle(cornerRadius: 20))
                .focused($isInputFocused)
                .onSubmit {
                    if !inputText.isEmpty && !isProcessing {
                        processQuery(inputText)
                    }
                }

            Button {
                processQuery(inputText)
            } label: {
                Image(systemName: "arrow.up.circle.fill")
                    .font(.system(size: 28))
                    .foregroundColor(inputText.isEmpty || isProcessing ? SyncFlowColors.adaptiveGray : SyncFlowColors.adaptiveBlue)
            }
            .buttonStyle(.plain)
            .disabled(inputText.isEmpty || isProcessing)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(Color(NSColor.controlBackgroundColor).opacity(0.5))
    }

    // MARK: - Helper Methods

    /// Processes a user query through the AI service.
    /// Adds user message to history, processes on background thread,
    /// then adds AI response to history.
    /// - Parameter query: The user's question or command
    private func processQuery(_ query: String) {
        guard !query.isEmpty else { return }

        let userMessage = AIChatMessage(isUser: true, content: .text(query))
        chatHistory.append(userMessage)
        inputText = ""
        isProcessing = true

        // Process async with limited messages for performance
        DispatchQueue.global(qos: .userInitiated).async {
            // Limit to most recent 1000 messages for faster processing
            // This covers ~3-4 months of typical usage which is enough for most queries
            let recentMessages = Array(messageStore.messages.prefix(1000))
            let response = aiService.processQuery(query, messages: recentMessages)

            DispatchQueue.main.async {
                let aiMessage = AIChatMessage(isUser: false, content: .response(response))
                chatHistory.append(aiMessage)
                isProcessing = false
            }
        }
    }

    /// Formats a date as a short time string
    private func formatTime(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }

    /// Copies the AI response to clipboard in plain text format.
    /// Formats different response types appropriately.
    private func copyResponseToClipboard(_ response: AIResponse) {
        var text = response.summary + "\n\n"

        switch response.details {
        case .spending(let analysis):
            text += "Total: \(analysis.formattedTotal)\n"
            text += "Transactions: \(analysis.transactionCount)\n"
            text += "Average: \(analysis.formattedAverage)\n"
            if !analysis.byMerchant.isEmpty {
                text += "\nTop Merchants:\n"
                for (merchant, amount) in analysis.byMerchant.prefix(5) {
                    text += "- \(merchant): \(formatCurrency(amount, analysis.currency))\n"
                }
            }
        case .bills(let bills):
            for bill in bills.prefix(5) {
                text += "- \(bill.merchant): \(bill.formattedAmount ?? "N/A") - Due: \(bill.formattedDueDate ?? "Unknown")\n"
            }
        case .balances(let balances):
            for bal in balances.prefix(5) {
                text += "- \(bal.institution ?? bal.accountType): \(bal.formattedBalance ?? "N/A")\n"
            }
        case .packages(let packages):
            for pkg in packages.prefix(5) {
                text += "- \(pkg.merchant ?? pkg.carrier ?? "Package"): \(pkg.status.rawValue)\n"
            }
        case .otps(let otps):
            for otp in otps.prefix(5) {
                text += "- \(otp.code) from \(otp.source) - \(otp.timeAgo)\n"
            }
        case .transactions(let txns):
            for txn in txns.prefix(10) {
                text += "- \(txn.formattedAmount) at \(txn.merchant ?? "Unknown") - \(txn.formattedDate)\n"
            }
        case .subscriptions(let subs):
            for sub in subs.prefix(5) {
                text += "- \(sub.merchant): \(sub.formattedAmount)\(sub.frequencyLabel)\n"
            }
        case .digest(let digest):
            text += "This Month: \(digest.formattedThisMonth)\n"
            text += "Transactions: \(digest.transactionCount)\n"
            if digest.spendingChange != 0 {
                text += "Change: \(String(format: "%.1f", digest.spendingChange))%\n"
            }
        case .trends(let thisMonth, let lastMonth):
            text += "This Month: \(thisMonth.formattedTotal) (\(thisMonth.transactionCount) transactions)\n"
            text += "Last Month: \(lastMonth.formattedTotal) (\(lastMonth.transactionCount) transactions)\n"
        case .currencyTotals(let totals):
            text += "Currency Totals:\n"
            for (currency, amount) in totals.sorted(by: { $0.value > $1.value }) {
                let symbol = getCurrencySymbol(currency)
                text += "\(symbol)\(String(format: "%.2f", amount)) \(currency)\n"
            }
        case .help(let helpText):
            text += helpText
        case .noResults(let msg), .error(let msg):
            text += msg
        }

        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)

        copiedResponse = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
            copiedResponse = false
        }
    }

    /// Formats an amount with the appropriate currency symbol
    private func formatCurrency(_ amount: Double, _ currency: String) -> String {
        let symbol = getCurrencySymbol(currency)
        return "\(symbol)\(String(format: "%.2f", amount))"
    }

    /// Gets the currency symbol for a given currency code
    private func getCurrencySymbol(_ currency: String) -> String {
        switch currency.uppercased() {
        case "USD": return "$"
        case "EUR": return "€"
        case "GBP": return "£"
        case "JPY": return "¥"
        case "INR": return "₹"
        case "CAD": return "CA$"
        case "AUD": return "AU$"
        case "NZD": return "NZ$"
        case "CHF": return "CHF"
        case "SEK": return "SEK"
        case "NOK": return "NOK"
        case "DKK": return "DKK"
        default: return currency
        }
    }

    /// Creates a compact stat pill with value and label
    private func statPill(label: String, value: String) -> some View {
        VStack(spacing: 2) {
            Text(value)
                .font(.subheadline)
                .fontWeight(.semibold)
            Text(label)
                .font(.caption2)
                .foregroundColor(.secondary)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(SyncFlowColors.adaptiveGray.opacity(0.1))
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    /// Converts color name string to SwiftUI Color with dark/light mode support
    private func colorForString(_ color: String) -> Color {
        switch color {
        case "blue": return SyncFlowColors.adaptiveBlue
        case "orange": return SyncFlowColors.adaptiveOrange
        case "green": return SyncFlowColors.adaptiveGreen
        case "purple": return SyncFlowColors.adaptivePurple
        case "red": return SyncFlowColors.adaptiveRed
        case "teal": return SyncFlowColors.adaptiveTeal
        case "indigo": return SyncFlowColors.adaptiveIndigo
        case "cyan": return SyncFlowColors.adaptiveCyan
        default: return SyncFlowColors.adaptiveBlue
        }
    }

    /// Returns appropriate color for package delivery status
    private func colorForDeliveryStatus(_ status: DeliveryStatus) -> Color {
        switch status {
        case .delivered, .outForDelivery: return SyncFlowColors.adaptiveGreen
        case .exception: return SyncFlowColors.adaptiveRed
        default: return SyncFlowColors.adaptiveOrange
        }
    }
}

// MARK: - Flow Layout

/// Custom layout that arranges views in a flowing grid, wrapping to new lines as needed.
/// Used for displaying follow-up suggestion chips that wrap based on available width.
struct FlowLayout: Layout {
    /// Spacing between items
    var spacing: CGFloat = 8

    // MARK: - Layout Protocol

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let result = arrangeSubviews(proposal: proposal, subviews: subviews)
        return result.size
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let result = arrangeSubviews(proposal: proposal, subviews: subviews)

        for (index, position) in result.positions.enumerated() {
            subviews[index].place(at: CGPoint(x: bounds.minX + position.x, y: bounds.minY + position.y), proposal: .unspecified)
        }
    }

    // MARK: - Private Helpers

    /// Calculates positions for all subviews in a flowing layout
    private func arrangeSubviews(proposal: ProposedViewSize, subviews: Subviews) -> (size: CGSize, positions: [CGPoint]) {
        let maxWidth = proposal.width ?? .infinity
        var positions: [CGPoint] = []
        var currentX: CGFloat = 0
        var currentY: CGFloat = 0
        var lineHeight: CGFloat = 0

        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)

            // Wrap to next line if needed
            if currentX + size.width > maxWidth && currentX > 0 {
                currentX = 0
                currentY += lineHeight + spacing
                lineHeight = 0
            }

            positions.append(CGPoint(x: currentX, y: currentY))
            lineHeight = max(lineHeight, size.height)
            currentX += size.width + spacing
        }

        return (CGSize(width: maxWidth, height: currentY + lineHeight), positions)
    }
}
