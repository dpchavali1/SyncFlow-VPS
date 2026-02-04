package com.phoneintegration.app.data

import android.content.Context
import com.phoneintegration.app.ConversationInfo
import com.phoneintegration.app.data.database.ArchivedConversation
import com.phoneintegration.app.data.database.SyncFlowDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository for managing archived conversations
 */
class ArchiveRepository(context: Context) {

    private val database = SyncFlowDatabase.getInstance(context)
    private val archiveDao = database.archivedConversationDao()

    /**
     * Archive a conversation
     */
    suspend fun archiveConversation(conversation: ConversationInfo): Long {
        val archived = ArchivedConversation(
            threadId = conversation.threadId,
            address = conversation.address,
            contactName = conversation.contactName,
            lastMessage = conversation.lastMessage,
            messageCount = conversation.unreadCount,
            photoUri = conversation.photoUri
        )

        return archiveDao.insert(archived)
    }

    /**
     * Unarchive a conversation
     */
    suspend fun unarchiveConversation(threadId: Long) {
        archiveDao.deleteByThreadId(threadId)
    }

    /**
     * Unarchive by address
     */
    suspend fun unarchiveByAddress(address: String) {
        archiveDao.deleteByAddress(address)
    }

    /**
     * Check if a conversation is archived
     */
    suspend fun isArchived(threadId: Long): Boolean {
        return archiveDao.isArchived(threadId)
    }

    /**
     * Observe if a conversation is archived
     */
    fun observeIsArchived(threadId: Long): Flow<Boolean> {
        return archiveDao.observeIsArchived(threadId)
    }

    /**
     * Get all archived conversations
     */
    fun getArchivedConversations(): Flow<List<ArchivedConversation>> {
        return archiveDao.getAll()
    }

    /**
     * Get archived conversations as ConversationInfo
     */
    fun getArchivedAsConversationInfo(): Flow<List<ConversationInfo>> {
        return archiveDao.getAll().map { list ->
            list.map { archived ->
                ConversationInfo(
                    threadId = archived.threadId,
                    address = archived.address,
                    contactName = archived.contactName,
                    lastMessage = archived.lastMessage,
                    timestamp = archived.archivedAt,
                    unreadCount = 0,
                    photoUri = archived.photoUri,
                    isArchived = true
                )
            }
        }
    }

    /**
     * Get all archived thread IDs
     */
    suspend fun getArchivedThreadIds(): List<Long> {
        return archiveDao.getAllThreadIds()
    }

    /**
     * Observe archived thread IDs
     */
    fun observeArchivedThreadIds(): Flow<List<Long>> {
        return archiveDao.observeThreadIds()
    }

    /**
     * Get archived count
     */
    fun observeArchivedCount(): Flow<Int> {
        return archiveDao.observeCount()
    }

    /**
     * Get archived count (suspend)
     */
    suspend fun getArchivedCount(): Int {
        return archiveDao.count()
    }

    /**
     * Search archived conversations
     */
    fun searchArchived(query: String): Flow<List<ArchivedConversation>> {
        return archiveDao.search("%$query%")
    }

    /**
     * Toggle archive status
     */
    suspend fun toggleArchive(conversation: ConversationInfo): Boolean {
        val isCurrentlyArchived = isArchived(conversation.threadId)

        if (isCurrentlyArchived) {
            unarchiveConversation(conversation.threadId)
            return false
        } else {
            archiveConversation(conversation)
            return true
        }
    }

    /**
     * Get archived conversation by thread ID
     */
    suspend fun getArchived(threadId: Long): ArchivedConversation? {
        return archiveDao.getByThreadId(threadId)
    }

    /**
     * Update archived conversation info (e.g., when new message arrives)
     */
    suspend fun updateArchived(threadId: Long, lastMessage: String) {
        val existing = archiveDao.getByThreadId(threadId) ?: return
        archiveDao.update(existing.copy(
            lastMessage = lastMessage,
            archivedAt = System.currentTimeMillis()
        ))
    }
}
