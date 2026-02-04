package com.phoneintegration.app.data

import android.content.ContentResolver
import android.content.Context
import android.provider.Telephony
import com.phoneintegration.app.MessageCategorizer
import com.phoneintegration.app.MessageCategory
import com.phoneintegration.app.SmsMessage
import com.phoneintegration.app.SmsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Advanced search filters
 */
data class SearchFilters(
    val query: String = "",
    val startDate: Long? = null,
    val endDate: Long? = null,
    val senderAddress: String? = null,
    val messageType: MessageTypeFilter = MessageTypeFilter.ALL,
    val category: MessageCategory? = null,
    val hasAttachments: Boolean? = null,
    val isUnread: Boolean? = null,
    val containsLinks: Boolean? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null
)

enum class MessageTypeFilter {
    ALL,
    RECEIVED,
    SENT
}

/**
 * Search result with highlighted matches
 */
data class SearchResult(
    val message: SmsMessage,
    val matchRanges: List<IntRange> = emptyList(),
    val relevanceScore: Float = 0f
)

/**
 * Repository for advanced message search
 */
class AdvancedSearchRepository(private val context: Context) {

    private val resolver: ContentResolver = context.contentResolver
    private val smsRepository = SmsRepository(context)

    /**
     * Perform advanced search with filters
     */
    suspend fun search(
        filters: SearchFilters,
        limit: Int = 100,
        offset: Int = 0
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SearchResult>()

        // Build selection clauses
        val selections = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()

        // Date range filter
        filters.startDate?.let {
            selections.add("${Telephony.Sms.DATE} >= ?")
            selectionArgs.add(it.toString())
        }
        filters.endDate?.let {
            selections.add("${Telephony.Sms.DATE} <= ?")
            selectionArgs.add(it.toString())
        }

        // Sender filter
        filters.senderAddress?.let {
            selections.add("${Telephony.Sms.ADDRESS} LIKE ?")
            selectionArgs.add("%$it%")
        }

        // Message type filter
        when (filters.messageType) {
            MessageTypeFilter.RECEIVED -> {
                selections.add("${Telephony.Sms.TYPE} = ?")
                selectionArgs.add("1")
            }
            MessageTypeFilter.SENT -> {
                selections.add("${Telephony.Sms.TYPE} = ?")
                selectionArgs.add("2")
            }
            MessageTypeFilter.ALL -> { /* No filter */ }
        }

        // Text search
        if (filters.query.isNotBlank()) {
            selections.add("${Telephony.Sms.BODY} LIKE ?")
            selectionArgs.add("%${filters.query}%")
        }

        // Unread filter
        filters.isUnread?.let { unread ->
            if (unread) {
                selections.add("${Telephony.Sms.READ} = ?")
                selectionArgs.add("0")
            }
        }

        val selection = if (selections.isNotEmpty()) {
            selections.joinToString(" AND ")
        } else null

        val cursor = resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
                Telephony.Sms.READ
            ),
            selection,
            selectionArgs.toTypedArray(),
            "${Telephony.Sms.DATE} DESC LIMIT ${limit + offset}"
        ) ?: return@withContext emptyList()

        cursor.use { c ->
            // Skip offset
            var skipped = 0
            while (skipped < offset && c.moveToNext()) {
                skipped++
            }

            while (c.moveToNext() && results.size < limit) {
                val body = c.getString(2) ?: ""

                val sms = SmsMessage(
                    id = c.getLong(0),
                    address = c.getString(1) ?: "",
                    body = body,
                    date = c.getLong(3),
                    type = c.getInt(4),
                    contactName = null
                )

                // Apply additional filters that can't be done in SQL

                // Category filter
                if (filters.category != null) {
                    val msgCategory = MessageCategorizer.categorizeMessage(sms)
                    if (msgCategory != filters.category) continue
                }

                // Links filter
                if (filters.containsLinks == true) {
                    if (!containsUrl(body)) continue
                } else if (filters.containsLinks == false) {
                    if (containsUrl(body)) continue
                }

                // Length filters
                filters.minLength?.let {
                    if (body.length < it) return@let
                }
                filters.maxLength?.let {
                    if (body.length > it) return@let
                }

                // Calculate match ranges for highlighting
                val matchRanges = if (filters.query.isNotBlank()) {
                    findMatchRanges(body, filters.query)
                } else emptyList()

                // Calculate relevance score
                val score = calculateRelevance(sms, filters, matchRanges)

                // Resolve contact name
                sms.contactName = smsRepository.resolveContactName(sms.address)
                sms.category = MessageCategorizer.categorizeMessage(sms)

                results.add(SearchResult(sms, matchRanges, score))
            }
        }

        // Sort by relevance if there's a search query
        if (filters.query.isNotBlank()) {
            results.sortByDescending { it.relevanceScore }
        }

        results
    }

    /**
     * Search by date range only
     */
    suspend fun searchByDateRange(
        startDate: Long,
        endDate: Long,
        limit: Int = 100
    ): List<SmsMessage> = withContext(Dispatchers.IO) {
        val results = search(
            SearchFilters(startDate = startDate, endDate = endDate),
            limit = limit
        )
        results.map { it.message }
    }

    /**
     * Search messages from specific sender
     */
    suspend fun searchBySender(
        address: String,
        query: String = "",
        limit: Int = 50
    ): List<SearchResult> {
        return search(
            SearchFilters(query = query, senderAddress = address),
            limit = limit
        )
    }

    /**
     * Search messages by category
     */
    suspend fun searchByCategory(
        category: MessageCategory,
        limit: Int = 100
    ): List<SmsMessage> = withContext(Dispatchers.IO) {
        val results = search(
            SearchFilters(category = category),
            limit = limit
        )
        results.map { it.message }
    }

    /**
     * Get messages with links
     */
    suspend fun getMessagesWithLinks(limit: Int = 50): List<SmsMessage> = withContext(Dispatchers.IO) {
        val results = search(
            SearchFilters(containsLinks = true),
            limit = limit
        )
        results.map { it.message }
    }

    /**
     * Get unread messages
     */
    suspend fun getUnreadMessages(limit: Int = 100): List<SmsMessage> = withContext(Dispatchers.IO) {
        val results = search(
            SearchFilters(isUnread = true),
            limit = limit
        )
        results.map { it.message }
    }

    /**
     * Get messages from today
     */
    suspend fun getTodayMessages(limit: Int = 100): List<SmsMessage> {
        val startOfDay = getStartOfDay()
        return searchByDateRange(startOfDay, System.currentTimeMillis(), limit)
    }

    /**
     * Get messages from this week
     */
    suspend fun getThisWeekMessages(limit: Int = 200): List<SmsMessage> {
        val weekAgo = System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000)
        return searchByDateRange(weekAgo, System.currentTimeMillis(), limit)
    }

    /**
     * Find match ranges for highlighting
     */
    private fun findMatchRanges(text: String, query: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        val lowerText = text.lowercase()
        val lowerQuery = query.lowercase()

        var startIndex = 0
        while (true) {
            val index = lowerText.indexOf(lowerQuery, startIndex)
            if (index == -1) break
            ranges.add(index until (index + query.length))
            startIndex = index + 1
        }

        return ranges
    }

    /**
     * Calculate relevance score for sorting
     */
    private fun calculateRelevance(
        message: SmsMessage,
        filters: SearchFilters,
        matchRanges: List<IntRange>
    ): Float {
        var score = 0f

        // More matches = higher score
        score += matchRanges.size * 10f

        // Recency bonus (messages from last 24h get bonus)
        val dayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
        if (message.date > dayAgo) {
            score += 5f
        }

        // Exact match at start of message
        if (filters.query.isNotBlank() &&
            message.body.lowercase().startsWith(filters.query.lowercase())) {
            score += 20f
        }

        // Shorter messages with matches are more relevant
        if (matchRanges.isNotEmpty()) {
            val density = matchRanges.size.toFloat() / message.body.length * 100
            score += density
        }

        return score
    }

    private fun containsUrl(text: String): Boolean {
        return text.contains("http://") ||
               text.contains("https://") ||
               text.contains("www.")
    }

    private fun getStartOfDay(): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
}
