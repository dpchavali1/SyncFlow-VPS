package com.phoneintegration.app.utils

import com.phoneintegration.app.SmsMessage
import com.phoneintegration.app.ConversationInfo
import com.phoneintegration.app.PhoneNumberUtils

/**
 * Utility for deduplicating messages and conversations
 * Prevents duplicate entries when loading from multiple sources or during real-time updates
 */
object MessageDeduplicator {

    /**
     * Deduplicate messages by ID, keeping the most recent version
     * Uses message ID as primary key, with fallback to body+date for temp messages
     */
    fun deduplicateMessages(messages: List<SmsMessage>): List<SmsMessage> {
        if (messages.isEmpty()) return messages

        val seenIds = mutableSetOf<Long>()
        val seenBodyDatePairs = mutableSetOf<String>()
        val result = mutableListOf<SmsMessage>()

        for (message in messages) {
            // For real messages (positive ID), dedupe by ID
            if (message.id > 0) {
                if (seenIds.add(message.id)) {
                    result.add(message)
                }
            } else {
                // For temp messages (negative ID), dedupe by body+date+address
                val key = "${message.address}:${message.body}:${message.date / 1000}" // Round to second
                if (seenBodyDatePairs.add(key)) {
                    result.add(message)
                }
            }
        }

        return result
    }

    /**
     * Merge new messages into existing list without duplicates
     * New messages take precedence (they may have updated fields)
     */
    fun mergeMessages(
        existing: List<SmsMessage>,
        incoming: List<SmsMessage>
    ): List<SmsMessage> {
        val existingById = existing.associateBy { it.id }.toMutableMap()

        // Incoming messages override existing ones
        for (message in incoming) {
            existingById[message.id] = message
        }

        return existingById.values.sortedByDescending { it.date }
    }

    /**
     * Append new messages, removing any duplicates
     * Used for pagination - keeps existing messages, adds only truly new ones
     */
    fun appendMessages(
        existing: List<SmsMessage>,
        newMessages: List<SmsMessage>
    ): List<SmsMessage> {
        val existingIds = existing.map { it.id }.toSet()
        val uniqueNew = newMessages.filter { it.id !in existingIds }
        return existing + uniqueNew
    }

    /**
     * Replace temp message with real message after send confirmation
     * Finds temp message by body content and replaces with the real one
     */
    fun replaceTempMessage(
        messages: List<SmsMessage>,
        tempId: Long,
        realMessage: SmsMessage
    ): List<SmsMessage> {
        return messages.map { msg ->
            if (msg.id == tempId) realMessage else msg
        }.distinctBy { it.id }
    }

    /**
     * Find and remove temp message that was superseded by a real message
     * Used after sending when the real message appears in the list
     */
    fun removeTempIfRealExists(
        messages: List<SmsMessage>,
        body: String,
        address: String
    ): List<SmsMessage> {
        // Normalize addresses for comparison to handle +1 country code differences
        val normalizedAddress = normalizePhoneNumber(address)

        // Check if a real message exists with matching body and normalized address
        val hasReal = messages.any { msg ->
            msg.id > 0 && msg.body == body && normalizePhoneNumber(msg.address) == normalizedAddress
        }

        return if (hasReal) {
            messages.filter { msg ->
                // Keep if it's a real message or if it's a temp message with different content
                val msgNormalized = normalizePhoneNumber(msg.address)
                msg.id > 0 || msg.body != body || msgNormalized != normalizedAddress
            }
        } else {
            messages
        }
    }

    /**
     * Deduplicate conversations by thread ID
     */
    fun deduplicateConversations(conversations: List<ConversationInfo>): List<ConversationInfo> {
        if (conversations.isEmpty()) return conversations

        return conversations.distinctBy { it.threadId }
    }

    /**
     * Deduplicate conversations by normalized phone number
     * This handles cases where the same contact has multiple threads due to
     * different phone number formats (e.g., +1234567890 vs 1234567890)
     * Keeps the conversation with the most recent timestamp BUT collects all thread IDs
     * so messages from all threads can be loaded together
     */
    fun deduplicateByNormalizedAddress(conversations: List<ConversationInfo>): List<ConversationInfo> {
        if (conversations.isEmpty()) return conversations

        // Group conversations by normalized address
        val groupedByAddress = mutableMapOf<String, MutableList<ConversationInfo>>()

        for (conv in conversations) {
            val normalized = normalizePhoneNumber(conv.address)
            groupedByAddress.getOrPut(normalized) { mutableListOf() }.add(conv)
        }

        // For each group, keep the one with most recent timestamp but collect all thread IDs
        val result = mutableListOf<ConversationInfo>()

        for ((_, group) in groupedByAddress) {
            if (group.size == 1) {
                // Single conversation, add its own threadId/address to related lists
                val conv = group[0]
                result.add(
                    conv.copy(
                        relatedThreadIds = listOf(conv.threadId),
                        relatedAddresses = listOf(conv.address)
                    )
                )
            } else {
                // Multiple conversations for same contact - merge them
                // Sort by timestamp descending to get the most recent
                val sorted = group.sortedByDescending { it.timestamp }
                val primary = sorted[0]

                // Collect all thread IDs from all conversations for this contact
                val allThreadIds = group.map { it.threadId }.distinct()
                val allAddresses = group.map { it.address }.distinct()
                val orderedAddresses = listOf(primary.address) + allAddresses.filter { it != primary.address }

                // Sum up unread counts from all threads
                val totalUnread = group.sumOf { it.unreadCount }

                result.add(primary.copy(
                    relatedThreadIds = allThreadIds,
                    relatedAddresses = orderedAddresses,
                    unreadCount = totalUnread
                ))
            }
        }

        return result.sortedByDescending { it.timestamp }
    }

    /**
     * Normalize phone number for comparison
     * Removes all non-digit characters except leading +
     * Also handles last 10 digits comparison for US numbers
     */
    private fun normalizePhoneNumber(address: String): String {
        return PhoneNumberUtils.normalizeForConversation(address)
    }

    /**
     * Merge conversation lists, preferring newer timestamps
     */
    fun mergeConversations(
        existing: List<ConversationInfo>,
        incoming: List<ConversationInfo>
    ): List<ConversationInfo> {
        val byThreadId = mutableMapOf<Long, ConversationInfo>()

        // Add existing first
        for (conv in existing) {
            byThreadId[conv.threadId] = conv
        }

        // Incoming overrides existing if newer
        for (conv in incoming) {
            val current = byThreadId[conv.threadId]
            if (current == null || conv.timestamp > current.timestamp) {
                byThreadId[conv.threadId] = conv
            }
        }

        return byThreadId.values.sortedByDescending { it.timestamp }
    }
}
