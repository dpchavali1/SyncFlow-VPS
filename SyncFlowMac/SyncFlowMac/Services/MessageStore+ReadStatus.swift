//
//  MessageStore+ReadStatus.swift
//  SyncFlowMac
//
//  Read status management: applying read status to messages, marking
//  conversations as read, and tracking unread counts.
//

import Foundation

// MARK: - Read Status

extension MessageStore {

    // MARK: - Apply Read Status

    /// Applies read status to messages based on multiple sources.
    ///
    /// Read status is determined by checking (in order):
    /// 1. Sent messages (type == 2) are always read
    /// 2. Local macOS read tracking (UserDefaults)
    /// 3. Android read receipts synced via VPS
    /// 4. Default to read if read receipts haven't loaded yet
    ///
    /// - Parameter messages: Array of messages to update
    /// - Returns: Messages with updated isRead property
    func applyReadStatus(to messages: [Message]) -> [Message] {
        // Batch read all read message IDs once (O(1) lookups vs O(n) UserDefaults reads)
        let readMessageIds = Set(UserDefaults.standard.stringArray(forKey: "readMessages") ?? [])
        let readReceiptIds = Set(readReceipts.keys)

        return messages.map { message in
            var updatedMessage = message

            // Sent messages (type == 2) are always considered read
            if message.type == 2 {
                updatedMessage.isRead = true
            }
            // Check local macOS read status (user marked as read on Mac)
            else if readMessageIds.contains(message.id) {
                updatedMessage.isRead = true
            }
            // Check if Android marked it as read (read receipt synced from phone)
            else if readReceiptIds.contains(message.id) {
                updatedMessage.isRead = true
            }
            // If read receipts haven't loaded yet, assume synced messages are read
            // (prevents flash of unread badges on initial load)
            else if !readReceiptsLoaded {
                updatedMessage.isRead = true
            }
            // Preserve the message's original isRead status (unread if not matched above)
            else {
                updatedMessage.isRead = message.isRead
            }

            return updatedMessage
        }
    }

    // MARK: - Unread Count

    /// Total count of unread messages across all non-archived conversations.
    /// Used for dock badge display.
    var totalUnreadCount: Int {
        return conversations.filter { !$0.isArchived }.reduce(0) { $0 + $1.unreadCount }
    }

    // MARK: - Mark as Read

    /// Marks all messages in a conversation as read.
    ///
    /// Updates both local state (UserDefaults) and syncs to VPS
    /// so other devices know the messages have been read.
    ///
    /// - Parameter conversation: The conversation to mark as read
    func markConversationAsRead(_ conversation: Conversation) {
        // Get all messages for this conversation (using normalized address matching)
        let conversationMessages = messages(for: conversation)
        let unreadMessageIds = conversationMessages.filter { $0.isReceived && !$0.isRead }.map { $0.id }
        preferences.markConversationAsRead(conversation.address, messageIds: unreadMessageIds)

        if !unreadMessageIds.isEmpty {
            Task {
                for messageId in unreadMessageIds {
                    try? await VPSService.shared.markMessageRead(messageId: messageId)
                }
            }
        }

        // Refresh conversations
        messages = applyReadStatus(to: messages)
        updateConversations(from: messages)
        notificationService.setBadgeCount(totalUnreadCount)
    }
}
