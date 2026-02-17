//
//  MessageStore+Spam.swift
//  SyncFlowMac
//
//  Spam management: filtered conversations, spam CRUD, spam WebSocket listener,
//  and VPS spam loading.
//

import Foundation
import Combine

extension MessageStore {

    // MARK: - Filtered Conversations

    private var spamAddressLookup: Set<String> {
        let normalized = spamMessages.map { normalizePhoneNumber($0.address) }
        return Set(normalized)
    }

    func isSpamConversation(_ conversation: Conversation) -> Bool {
        let normalized = normalizePhoneNumber(conversation.address)
        return spamAddressLookup.contains(normalized)
    }

    var activeConversations: [Conversation] {
        return conversations.filter { !$0.isArchived && !isSpamConversation($0) }
    }

    var archivedConversations: [Conversation] {
        return conversations.filter { $0.isArchived && !isSpamConversation($0) }
    }

    var unreadConversations: [Conversation] {
        return activeConversations.filter { $0.unreadCount > 0 }
    }

    var displayedConversations: [Conversation] {
        if showSpamOnly {
            return []
        } else if showArchived {
            return archivedConversations
        } else if showUnreadOnly {
            return unreadConversations
        } else {
            return activeConversations
        }
    }

    // MARK: - Spam Conversations

    var spamConversations: [SpamConversation] {
        let grouped = Dictionary(grouping: spamMessages) { $0.address }
        return grouped.map { (address, messages) in
            let latest = messages.max(by: { $0.date < $1.date })
            return SpamConversation(
                address: address,
                contactName: latest?.contactName ?? address,
                latestMessage: latest?.body ?? "",
                timestamp: latest?.date ?? 0,
                messageCount: messages.count
            )
        }.sorted { $0.timestamp > $1.timestamp }
    }

    // MARK: - Spam Actions

    func spamMessages(for address: String) -> [SpamMessage] {
        return spamMessages
            .filter { $0.address == address }
            .sorted { $0.date < $1.date }
    }

    func deleteSpamMessages(for address: String) async {
        let ids = spamMessages.filter { $0.address == address }.map { $0.id }
        for id in ids {
            try? await VPSService.shared.deleteSpamMessage(messageId: id)
        }
        await MainActor.run {
            self.spamMessages.removeAll { $0.address == address }
        }
    }

    func clearAllSpam() async {
        try? await VPSService.shared.clearAllSpamMessages()
        await MainActor.run {
            self.spamMessages.removeAll()
        }
    }

    func markMessageAsSpam(_ message: Message) {
        Task {
            try? await VPSService.shared.syncSpamMessage(
                address: message.address,
                body: message.body,
                date: message.date,
                spamScore: 1.0,
                spamReason: "Manually marked as spam"
            )
            loadSpamMessagesFromVPS()
        }
    }

    func markConversationAsSpam(_ conversation: Conversation) {
        guard let latest = messages
            .filter({ $0.address == conversation.address })
            .max(by: { $0.date < $1.date }) else { return }
        markMessageAsSpam(latest)
    }

    /// Mark a spam conversation as "not spam" - removes from spam and adds to whitelist
    func markSpamAsNotSpam(address: String) async {
        try? await VPSService.shared.addToWhitelist(phoneNumber: address)
        await deleteSpamMessages(for: address)
    }

    // MARK: - VPS Spam Helpers

    func loadSpamMessagesFromVPS() {
        Task {
            do {
                let response = try await VPSService.shared.getSpamMessages(limit: 100)
                let mapped = response.messages.map { vpsSpam in
                    SpamMessage(
                        id: vpsSpam.id,
                        address: vpsSpam.address,
                        body: vpsSpam.body ?? "",
                        date: Double(vpsSpam.date),
                        contactName: nil,
                        spamConfidence: Double(vpsSpam.spamScore ?? 0.5),
                        spamReasons: vpsSpam.spamReason,
                        detectedAt: Double(vpsSpam.date),
                        isUserMarked: false,
                        isRead: false
                    )
                }
                await MainActor.run {
                    let previousCount = self.spamMessages.count
                    self.spamMessages = mapped.sorted { $0.date > $1.date }
                    if self.selectedSpamAddress == nil {
                        self.selectedSpamAddress = self.spamMessages.first?.address
                    }
                    #if DEBUG
                    if mapped.count != previousCount {
                        print("[MessageStore VPS] Loaded \(mapped.count) spam messages")
                    }
                    #endif
                }
            } catch {
                #if DEBUG
                print("[MessageStore VPS] Error loading spam: \(error.localizedDescription)")
                #endif
            }
        }
    }

    func startSpamWebSocketListener() {
        VPSService.shared.spamUpdated
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                self?.loadSpamMessagesFromVPS()
            }
            .store(in: &vpsCancellables)
    }
}

// MARK: - SpamConversation Model

struct SpamConversation: Identifiable, Hashable {
    let address: String
    let contactName: String
    let latestMessage: String
    let timestamp: Double
    let messageCount: Int

    var id: String { address }
}
