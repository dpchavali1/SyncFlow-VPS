package com.phoneintegration.app.data.database

import androidx.room.*

/**
 * Cached conversation for instant app startup.
 * This table stores the conversation list so it can be displayed
 * immediately when the app opens, without waiting for ContentProvider queries.
 */
@Entity(tableName = "cached_conversations")
data class CachedConversation(
    @PrimaryKey
    val threadId: Long,
    val address: String,
    val contactName: String?,
    val lastMessage: String,
    val timestamp: Long,
    val unreadCount: Int = 0,
    val photoUri: String? = null,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
    val isGroupConversation: Boolean = false,
    val recipientCount: Int = 1,
    val groupId: Long? = null,
    val cachedAt: Long = System.currentTimeMillis()
)

@Dao
interface CachedConversationDao {

    @Query("SELECT * FROM cached_conversations ORDER BY isPinned DESC, timestamp DESC")
    suspend fun getAll(): List<CachedConversation>

    @Query("SELECT * FROM cached_conversations ORDER BY isPinned DESC, timestamp DESC LIMIT :limit")
    suspend fun getTop(limit: Int): List<CachedConversation>

    @Query("SELECT COUNT(*) FROM cached_conversations")
    suspend fun getCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(conversations: List<CachedConversation>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: CachedConversation)

    @Query("DELETE FROM cached_conversations")
    suspend fun deleteAll()

    @Query("DELETE FROM cached_conversations WHERE threadId = :threadId")
    suspend fun delete(threadId: Long)

    @Query("UPDATE cached_conversations SET isPinned = :isPinned WHERE threadId = :threadId")
    suspend fun updatePinned(threadId: Long, isPinned: Boolean)

    @Query("UPDATE cached_conversations SET isMuted = :isMuted WHERE threadId = :threadId")
    suspend fun updateMuted(threadId: Long, isMuted: Boolean)

    @Query("UPDATE cached_conversations SET lastMessage = :message, timestamp = :timestamp WHERE threadId = :threadId")
    suspend fun updateLastMessage(threadId: Long, message: String, timestamp: Long)

    @Query("SELECT MAX(cachedAt) FROM cached_conversations")
    suspend fun getLastCacheTime(): Long?

    @Transaction
    suspend fun replaceAll(conversations: List<CachedConversation>) {
        deleteAll()
        insertAll(conversations)
    }
}
