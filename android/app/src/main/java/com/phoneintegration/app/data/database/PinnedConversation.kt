package com.phoneintegration.app.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Entity for storing pinned conversation thread IDs
 */
@Entity(tableName = "pinned_conversations")
data class PinnedConversation(
    @PrimaryKey
    val threadId: Long,
    val pinnedAt: Long = System.currentTimeMillis()
)

/**
 * DAO for pinned conversations
 */
@Dao
interface PinnedConversationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun pin(pinned: PinnedConversation)

    @Query("DELETE FROM pinned_conversations WHERE threadId = :threadId")
    suspend fun unpin(threadId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM pinned_conversations WHERE threadId = :threadId)")
    suspend fun isPinned(threadId: Long): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM pinned_conversations WHERE threadId = :threadId)")
    fun observeIsPinned(threadId: Long): Flow<Boolean>

    @Query("SELECT threadId FROM pinned_conversations ORDER BY pinnedAt ASC")
    fun getAllPinnedIds(): Flow<List<Long>>

    @Query("SELECT threadId FROM pinned_conversations ORDER BY pinnedAt ASC")
    suspend fun getPinnedIds(): List<Long>

    @Query("SELECT COUNT(*) FROM pinned_conversations")
    suspend fun count(): Int

    @Query("DELETE FROM pinned_conversations")
    suspend fun clearAll()
}
