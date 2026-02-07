//
//  GlobalSearchView.swift
//  SyncFlowMac
//
//  Full-screen global search view with filters for searching across all conversations
//

import SwiftUI

struct GlobalSearchView: View {
    @EnvironmentObject var messageStore: MessageStore
    @Environment(\.dismiss) private var dismiss

    @State private var searchText = ""
    @State private var searchResults: [Message] = []
    @State private var isSearching = false

    // Filters
    @State private var filterSentOnly = false
    @State private var filterReceivedOnly = false
    @State private var filterWithAttachments = false
    @State private var dateRangeEnabled = false
    @State private var startDate = Calendar.current.date(byAdding: .month, value: -1, to: Date())!
    @State private var endDate = Date()

    // Sort options
    enum SortOption: String, CaseIterable {
        case dateNewest = "Newest First"
        case dateOldest = "Oldest First"
    }
    @State private var sortOption: SortOption = .dateNewest

    // Debounce timer
    @State private var searchTask: DispatchWorkItem?

    // Navigation
    var onSelectMessage: ((Message, Conversation?) -> Void)?

    private var totalResultCount: Int {
        searchResults.count
    }

    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                Image(systemName: "magnifyingglass")
                    .font(.title2)
                    .foregroundColor(.blue)

                Text("Search All Messages")
                    .font(.title2)
                    .fontWeight(.semibold)

                Spacer()

                Button(action: { dismiss() }) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.title2)
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.plain)
                .keyboardShortcut(.escape, modifiers: [])
            }
            .padding()
            .background(Color(nsColor: .windowBackgroundColor))

            Divider()

            // Search input
            HStack {
                Image(systemName: "magnifyingglass")
                    .foregroundColor(.secondary)

                TextField("Search messages...", text: $searchText)
                    .textFieldStyle(.plain)
                    .font(.body)
                    .onSubmit {
                        performSearch()
                    }

                if !searchText.isEmpty {
                    Button(action: {
                        searchText = ""
                        searchResults = []
                    }) {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundColor(.secondary)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(12)
            .background(Color(nsColor: .controlBackgroundColor))
            .cornerRadius(10)
            .padding()

            // Filters
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 16) {
                    Text("Filters:")
                        .font(.caption)
                        .foregroundColor(.secondary)

                    Toggle("Sent", isOn: $filterSentOnly)
                        .toggleStyle(.checkbox)
                        .onChange(of: filterSentOnly) { newValue in
                            if newValue { filterReceivedOnly = false }
                            performSearch()
                        }

                    Toggle("Received", isOn: $filterReceivedOnly)
                        .toggleStyle(.checkbox)
                        .onChange(of: filterReceivedOnly) { newValue in
                            if newValue { filterSentOnly = false }
                            performSearch()
                        }

                    Toggle("Has Attachment", isOn: $filterWithAttachments)
                        .toggleStyle(.checkbox)
                        .onChange(of: filterWithAttachments) { _ in
                            performSearch()
                        }

                    Spacer()
                }

                HStack(spacing: 16) {
                    Toggle("Date range:", isOn: $dateRangeEnabled)
                        .toggleStyle(.checkbox)
                        .onChange(of: dateRangeEnabled) { _ in
                            performSearch()
                        }

                    if dateRangeEnabled {
                        DatePicker("From", selection: $startDate, displayedComponents: .date)
                            .labelsHidden()
                            .datePickerStyle(.field)
                            .frame(width: 120)
                            .onChange(of: startDate) { _ in
                                performSearch()
                            }

                        Text("to")
                            .foregroundColor(.secondary)

                        DatePicker("To", selection: $endDate, displayedComponents: .date)
                            .labelsHidden()
                            .datePickerStyle(.field)
                            .frame(width: 120)
                            .onChange(of: endDate) { _ in
                                performSearch()
                            }
                    }

                    Spacer()
                }
            }
            .padding(.horizontal)
            .padding(.bottom, 8)

            Divider()

            // Results header
            HStack {
                if isSearching {
                    ProgressView()
                        .scaleEffect(0.7)
                    Text("Searching...")
                        .font(.caption)
                        .foregroundColor(.secondary)
                } else if !searchText.isEmpty {
                    Text("Found \(totalResultCount) message\(totalResultCount == 1 ? "" : "s")")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }

                Spacer()

                Picker("Sort", selection: $sortOption) {
                    ForEach(SortOption.allCases, id: \.self) { option in
                        Text(option.rawValue).tag(option)
                    }
                }
                .pickerStyle(.menu)
                .frame(width: 140)
                .onChange(of: sortOption) { _ in
                    sortResults()
                }
            }
            .padding(.horizontal)
            .padding(.vertical, 8)

            Divider()

            // Results list
            if searchResults.isEmpty && !searchText.isEmpty && !isSearching {
                VStack(spacing: 12) {
                    Spacer()
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 48))
                        .foregroundColor(.secondary.opacity(0.5))
                    Text("No messages found")
                        .font(.headline)
                        .foregroundColor(.secondary)
                    Text("Try adjusting your search or filters")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Spacer()
                }
            } else if searchText.isEmpty {
                VStack(spacing: 12) {
                    Spacer()
                    Image(systemName: "text.magnifyingglass")
                        .font(.system(size: 48))
                        .foregroundColor(.secondary.opacity(0.5))
                    Text("Search all your messages")
                        .font(.headline)
                        .foregroundColor(.secondary)
                    Text("Enter a search term to find messages across all conversations")
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                    Spacer()
                }
            } else {
                ScrollView {
                    LazyVStack(spacing: 8) {
                        ForEach(searchResults) { message in
                            GlobalSearchResultRow(
                                message: message,
                                searchText: searchText,
                                onTap: {
                                    // Find the conversation for this message
                                    let conversation = messageStore.conversations.first { conv in
                                        normalizePhoneNumber(conv.address) == normalizePhoneNumber(message.address)
                                    }
                                    onSelectMessage?(message, conversation)
                                    dismiss()
                                }
                            )
                        }
                    }
                    .padding()
                }
            }
        }
        .frame(minWidth: 600, minHeight: 500)
        .background(Color(nsColor: .windowBackgroundColor))
        .onChange(of: searchText) { _ in
            // Debounce search
            searchTask?.cancel()
            let task = DispatchWorkItem {
                performSearch()
            }
            searchTask = task
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3, execute: task)
        }
    }

    private func performSearch() {
        guard searchText.count >= 2 else {
            searchResults = []
            return
        }

        isSearching = true

        DispatchQueue.global(qos: .userInitiated).async {
            // Get all messages matching the query
            var results = messageStore.searchMessages(query: searchText)

            // Apply filters
            if filterSentOnly {
                results = results.filter { $0.type == 2 }
            }

            if filterReceivedOnly {
                results = results.filter { $0.type == 1 }
            }

            if filterWithAttachments {
                results = results.filter { $0.hasAttachments }
            }

            if dateRangeEnabled {
                let startTimestamp = startDate.timeIntervalSince1970 * 1000
                let endTimestamp = endDate.timeIntervalSince1970 * 1000 + (24 * 60 * 60 * 1000) // End of day
                results = results.filter { $0.date >= startTimestamp && $0.date <= endTimestamp }
            }

            // Sort results
            results = sortMessages(results)

            DispatchQueue.main.async {
                searchResults = results
                isSearching = false
            }
        }
    }

    private func sortResults() {
        searchResults = sortMessages(searchResults)
    }

    private func sortMessages(_ messages: [Message]) -> [Message] {
        switch sortOption {
        case .dateNewest:
            return messages.sorted { $0.date > $1.date }
        case .dateOldest:
            return messages.sorted { $0.date < $1.date }
        }
    }

    private func normalizePhoneNumber(_ address: String) -> String {
        if address.contains("@") || address.count < 6 {
            return address.lowercased()
        }
        let digitsOnly = address.filter { $0.isNumber }
        if digitsOnly.count >= 10 {
            return String(digitsOnly.suffix(10))
        }
        return digitsOnly
    }
}

// MARK: - Search Result Row

struct GlobalSearchResultRow: View {
    let message: Message
    let searchText: String
    let onTap: () -> Void

    @State private var isHovering = false

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                // Direction indicator
                Circle()
                    .fill(message.isReceived ? Color.blue.opacity(0.15) : Color.green.opacity(0.15))
                    .frame(width: 40, height: 40)
                    .overlay(
                        Image(systemName: message.isReceived ? "arrow.down.left" : "arrow.up.right")
                            .font(.system(size: 16))
                            .foregroundColor(message.isReceived ? .blue : .green)
                    )

                VStack(alignment: .leading, spacing: 4) {
                    // Contact name and date
                    HStack {
                        Text(message.contactName ?? message.address)
                            .font(.subheadline)
                            .fontWeight(.medium)
                            .foregroundColor(.primary)

                        if message.hasAttachments {
                            Image(systemName: "paperclip")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }

                        Spacer()

                        Text(formatDate(message.date))
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }

                    // Message body with highlighted search term
                    highlightedText(message.body, searchText: searchText)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                        .lineLimit(2)
                }
            }
            .padding(12)
            .background(
                RoundedRectangle(cornerRadius: 10)
                    .fill(isHovering ? Color(nsColor: .controlBackgroundColor) : Color.clear)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hovering in
            isHovering = hovering
        }
    }

    private func highlightedText(_ text: String, searchText: String) -> Text {
        guard !searchText.isEmpty else {
            return Text(text)
        }

        let lowercasedText = text.lowercased()
        let lowercasedSearch = searchText.lowercased()

        guard let range = lowercasedText.range(of: lowercasedSearch) else {
            return Text(text)
        }

        let startIndex = text.index(text.startIndex, offsetBy: lowercasedText.distance(from: lowercasedText.startIndex, to: range.lowerBound))
        let endIndex = text.index(startIndex, offsetBy: searchText.count)

        let before = String(text[..<startIndex])
        let match = String(text[startIndex..<endIndex])
        let after = String(text[endIndex...])

        return Text(before) + Text(match).bold().foregroundColor(.blue) + Text(after)
    }

    private func formatDate(_ timestamp: Double) -> String {
        let date = Date(timeIntervalSince1970: timestamp / 1000)
        let formatter = DateFormatter()
        let calendar = Calendar.current

        if calendar.isDateInToday(date) {
            formatter.dateFormat = "h:mm a"
        } else if calendar.isDateInYesterday(date) {
            return "Yesterday"
        } else if calendar.isDate(date, equalTo: Date(), toGranularity: .year) {
            formatter.dateFormat = "MMM d"
        } else {
            formatter.dateFormat = "MMM d, yyyy"
        }

        return formatter.string(from: date)
    }
}
