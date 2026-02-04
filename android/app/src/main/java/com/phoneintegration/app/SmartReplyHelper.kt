package com.phoneintegration.app

import com.google.mlkit.nl.smartreply.SmartReply
import com.google.mlkit.nl.smartreply.SmartReplySuggestionResult
import com.google.mlkit.nl.smartreply.TextMessage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Helper class for generating smart reply suggestions using ML Kit.
 * Provides both synchronous (blocking) and asynchronous (coroutine) APIs.
 */
class SmartReplyHelper {

    private val smartReplyClient = SmartReply.getClient()

    /**
     * Generate smart reply suggestions asynchronously.
     * This is the preferred method for coroutine-based code.
     *
     * @param messages List of messages in the conversation
     * @param limit Maximum number of suggestions to return (default 3)
     * @param timeoutMs Timeout in milliseconds (default 500ms)
     * @return List of suggested replies, empty if no suggestions or timeout
     */
    suspend fun generateRepliesAsync(
        messages: List<SmsMessage>,
        limit: Int = 3,
        timeoutMs: Long = 500
    ): List<String> {
        if (messages.isEmpty()) return emptyList()

        val conversation = buildConversation(messages)
        if (conversation.isEmpty()) return emptyList()

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                smartReplyClient.suggestReplies(conversation)
                    .addOnSuccessListener { result ->
                        val suggestions = if (result.status == SmartReplySuggestionResult.STATUS_SUCCESS) {
                            result.suggestions
                                .take(limit)
                                .map { it.text }
                        } else {
                            emptyList()
                        }
                        if (continuation.isActive) {
                            continuation.resume(suggestions)
                        }
                    }
                    .addOnFailureListener {
                        if (continuation.isActive) {
                            continuation.resume(emptyList())
                        }
                    }
            }
        } ?: emptyList()
    }

    /**
     * Generate smart reply suggestions synchronously (blocking).
     * Use generateRepliesAsync() when possible for better performance.
     *
     * @param list List of messages in the conversation
     * @return List of suggested replies
     */
    fun generateReplies(list: List<SmsMessage>): List<String> {
        if (list.isEmpty()) return emptyList()

        val conversation = buildConversation(list)
        if (conversation.isEmpty()) return emptyList()

        var replies = emptyList<String>()
        val lock = Object()

        smartReplyClient.suggestReplies(conversation)
            .addOnSuccessListener { result ->
                replies = if (result.status == SmartReplySuggestionResult.STATUS_SUCCESS) {
                    result.suggestions.map { it.text }
                } else {
                    emptyList()
                }
                synchronized(lock) { lock.notify() }
            }
            .addOnFailureListener {
                synchronized(lock) { lock.notify() }
            }

        synchronized(lock) {
            try {
                lock.wait(300)
            } catch (e: InterruptedException) {
                // Ignore interruption
            }
        }

        return replies
    }

    /**
     * Build a conversation list from SMS messages for ML Kit.
     */
    private fun buildConversation(messages: List<SmsMessage>): List<TextMessage> {
        val sorted = messages.sortedBy { it.date }
        val conversation = mutableListOf<TextMessage>()

        sorted.forEach { sms ->
            // Skip empty messages
            if (sms.body.isBlank()) return@forEach

            // Unique user ID for remote user
            val uid = sms.address ?: "user"

            if (sms.type == 1) { // received
                conversation.add(
                    TextMessage.createForRemoteUser(
                        sms.body,
                        sms.date,
                        uid
                    )
                )
            } else { // sent
                conversation.add(
                    TextMessage.createForLocalUser(
                        sms.body,
                        sms.date
                    )
                )
            }
        }

        return conversation
    }

    /**
     * Release resources when no longer needed.
     */
    fun close() {
        smartReplyClient.close()
    }
}
