//
//  MessageStore+Search.swift
//  SyncFlowMac
//
//  Search functionality: conversation search, message search, and text normalization.
//

import Foundation

extension MessageStore {

    // MARK: - Search Helpers

    /// Extract only digits from a string for phone number comparison
    private func digitsOnly(_ value: String) -> String {
        return value.filter { $0.isNumber }
    }

    private func normalizeSearchText(_ value: String) -> String {
        let folded = value
            .folding(options: [.diacriticInsensitive], locale: .current)
            .lowercased()
        let cleaned = folded.map { char in
            char.isLetter || char.isNumber ? char : " "
        }
        let collapsed = String(cleaned)
            .split(whereSeparator: { $0.isWhitespace })
            .joined(separator: " ")
        return collapsed
    }

    // MARK: - Conversation Search

    func search(query: String, in conversationsList: [Conversation] = []) -> [Conversation] {
        let list = conversationsList.isEmpty ? conversations : conversationsList

        if query.isEmpty {
            return list
        }

        let lowercaseQuery = query.lowercased()
        let queryDigits = query.filter { $0.isNumber }
        let normalizedQuery = normalizeSearchText(query)
        let compactQuery = normalizedQuery.replacingOccurrences(of: " ", with: "")

        if normalizedQuery.isEmpty {
            return list
        }

        return list.filter { conversation in
            // Match by display name (contact name or address)
            if conversation.displayName.lowercased().contains(lowercaseQuery) {
                return true
            }

            // Match by contact name if available
            if let contactName = conversation.contactName,
               contactName.lowercased().contains(lowercaseQuery) {
                return true
            }

            // Match by last message content
            if conversation.lastMessage.lowercased().contains(lowercaseQuery) {
                return true
            }

            // Match by address (exact or partial)
            if conversation.address.lowercased().contains(lowercaseQuery) {
                return true
            }

            // Match by conversation ID
            if conversation.id.lowercased().contains(lowercaseQuery) {
                return true
            }

            // Normalized/compact matching (handles punctuation/spacing)
            let displayNameNormalized = normalizeSearchText(conversation.displayName)
            let displayNameCompact = displayNameNormalized.replacingOccurrences(of: " ", with: "")
            if displayNameNormalized.contains(normalizedQuery) || displayNameCompact.contains(compactQuery) {
                return true
            }
            if let contactName = conversation.contactName {
                let contactNormalized = normalizeSearchText(contactName)
                let contactCompact = contactNormalized.replacingOccurrences(of: " ", with: "")
                if contactNormalized.contains(normalizedQuery) || contactCompact.contains(compactQuery) {
                    return true
                }
            }
            let lastMessageNormalized = normalizeSearchText(conversation.lastMessage)
            let lastMessageCompact = lastMessageNormalized.replacingOccurrences(of: " ", with: "")
            if lastMessageNormalized.contains(normalizedQuery) || lastMessageCompact.contains(compactQuery) {
                return true
            }
            let addressNormalized = normalizeSearchText(conversation.address)
            let addressCompact = addressNormalized.replacingOccurrences(of: " ", with: "")
            if addressNormalized.contains(normalizedQuery) || addressCompact.contains(compactQuery) {
                return true
            }
            let idNormalized = normalizeSearchText(conversation.id)
            let idCompact = idNormalized.replacingOccurrences(of: " ", with: "")
            if idNormalized.contains(normalizedQuery) || idCompact.contains(compactQuery) {
                return true
            }

            // Phone number digit matching
            if queryDigits.count >= 3 {
                let addressDigits = conversation.address.filter { $0.isNumber }
                let idDigits = conversation.id.filter { $0.isNumber }

                if addressDigits.contains(queryDigits) {
                    return true
                }
                if idDigits.contains(queryDigits) {
                    return true
                }
                if queryDigits.contains(addressDigits) && !addressDigits.isEmpty {
                    return true
                }
                if queryDigits.contains(idDigits) && !idDigits.isEmpty {
                    return true
                }
            }

            return false
        }
    }

    // MARK: - Message Search

    func searchMessages(query: String) -> [Message] {
        if query.isEmpty {
            return []
        }

        let normalizedQuery = normalizeSearchText(query)
        let compactQuery = normalizedQuery.replacingOccurrences(of: " ", with: "")
        if normalizedQuery.isEmpty {
            return []
        }

        let queryDigits = digitsOnly(query)
        let isPhoneSearch = queryDigits.count >= 4

        return messages.filter { message in
            // Match by message body
            if message.body.localizedCaseInsensitiveContains(query) {
                return true
            }
            // Match by exact address
            if message.address.contains(query) {
                return true
            }
            // Match by contact name
            if message.contactName?.localizedCaseInsensitiveContains(query) == true {
                return true
            }
            // Normalized/compact matching for punctuation/spacing variations
            let bodyNormalized = normalizeSearchText(message.body)
            let bodyCompact = bodyNormalized.replacingOccurrences(of: " ", with: "")
            if bodyNormalized.contains(normalizedQuery) || bodyCompact.contains(compactQuery) {
                return true
            }
            let addressNormalized = normalizeSearchText(message.address)
            let addressCompact = addressNormalized.replacingOccurrences(of: " ", with: "")
            if addressNormalized.contains(normalizedQuery) || addressCompact.contains(compactQuery) {
                return true
            }
            if let contactName = message.contactName {
                let contactNormalized = normalizeSearchText(contactName)
                let contactCompact = contactNormalized.replacingOccurrences(of: " ", with: "")
                if contactNormalized.contains(normalizedQuery) || contactCompact.contains(compactQuery) {
                    return true
                }
            }
            // Match by phone number digits (handles all formats)
            if isPhoneSearch {
                let addressDigits = digitsOnly(message.address)
                if addressDigits.contains(queryDigits) || queryDigits.contains(addressDigits) {
                    return true
                }
                if queryDigits.count >= 7 && addressDigits.count >= 7 {
                    let queryLast7 = String(queryDigits.suffix(7))
                    let addressLast7 = String(addressDigits.suffix(7))
                    if queryLast7 == addressLast7 {
                        return true
                    }
                }
            }
            return false
        }
    }
}
