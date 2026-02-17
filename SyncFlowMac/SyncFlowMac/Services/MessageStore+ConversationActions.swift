//
//  MessageStore+ConversationActions.swift
//  SyncFlowMac
//
//  Conversation-level actions: pin, archive, block, and delete conversations.
//

import Foundation

// MARK: - Conversation Actions

extension MessageStore {

    // MARK: - Pin

    func togglePin(_ conversation: Conversation) {
        let addresses = Set(conversation.allAddresses + [conversation.address])
        let normalizedAddresses = Set(addresses.map { normalizePhoneNumber($0) })
        let pinnedList = preferences.getAllPinned()
        let pinnedSet = Set(pinnedList)
        let normalizedConversation = normalizePhoneNumber(conversation.address)
        let isPinned = addresses.contains { isAddressPinned($0, pinnedSet: pinnedSet) }

        if isPinned {
            for pinned in pinnedList {
                if normalizePhoneNumber(pinned) == normalizedConversation {
                    preferences.setPinned(pinned, pinned: false)
                }
            }
            for address in addresses {
                preferences.setPinned(address, pinned: false)
            }
            for normalized in normalizedAddresses {
                preferences.setPinned(normalized, pinned: false)
            }
        } else {
            preferences.setPinned(conversation.address, pinned: true)
            let normalized = normalizePhoneNumber(conversation.address)
            if normalized != conversation.address {
                preferences.setPinned(normalized, pinned: true)
            }
        }
        updateConversations(from: messages)
    }

    func isConversationPinned(_ conversation: Conversation, allAddresses: [String]) -> Bool {
        let addresses = Set(allAddresses + [conversation.address])
        let pinnedSet = Set(preferences.getAllPinned())
        return addresses.contains { isAddressPinned($0, pinnedSet: pinnedSet) }
    }

    // MARK: - Archive

    func toggleArchive(_ conversation: Conversation) {
        preferences.setArchived(conversation.address, archived: !conversation.isArchived)
        updateConversations(from: messages)
    }

    // MARK: - Block

    func toggleBlock(_ conversation: Conversation) {
        preferences.setBlocked(conversation.address, blocked: !conversation.isBlocked)
        updateConversations(from: messages)
    }

    // MARK: - Delete

    func deleteConversation(_ conversation: Conversation) {
        deleteConversations([conversation])
    }

    // MARK: - Delete

    /// Delete multiple conversations (and all messages in them)
    func deleteConversations(_ conversations: [Conversation]) {
        guard !conversations.isEmpty else { return }

        let addresses = Set(conversations.map { $0.address })
        let messagesToDelete = messages.filter { addresses.contains($0.address) }

        // Remove from preferences
        for convo in conversations {
            preferences.setArchived(convo.address, archived: false)
            preferences.setPinned(convo.address, pinned: false)
        }

        deleteMessages(messagesToDelete)
    }
}
