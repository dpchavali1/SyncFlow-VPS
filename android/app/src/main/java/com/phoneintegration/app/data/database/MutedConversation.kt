package com.phoneintegration.app.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Entity for storing muted conversation thread IDs
 */
@Entity(tableName = "muted_conversations")
data class MutedConversation(
    @PrimaryKey
    val threadId: Long,
    val mutedAt: Long = System.currentTimeMillis(),
    val mutedUntil: Long? = null  // null = muted indefinitely
)

/**
 * DAO for muted conversations
 */
@Dao
interface MutedConversationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun mute(muted: MutedConversation)

    @Query("DELETE FROM muted_conversations WHERE threadId = :threadId")
    suspend fun unmute(threadId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM muted_conversations WHERE threadId = :threadId AND (mutedUntil IS NULL OR mutedUntil > :currentTime))")
    suspend fun isMuted(threadId: Long, currentTime: Long = System.currentTimeMillis()): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM muted_conversations WHERE threadId = :threadId)")
    fun observeIsMuted(threadId: Long): Flow<Boolean>

    @Query("SELECT threadId FROM muted_conversations WHERE mutedUntil IS NULL OR mutedUntil > :currentTime")
    fun getAllMutedIds(currentTime: Long): Flow<List<Long>>

    @Query("SELECT threadId FROM muted_conversations WHERE mutedUntil IS NULL OR mutedUntil > :currentTime")
    suspend fun getMutedIds(currentTime: Long = System.currentTimeMillis()): List<Long>

    @Query("SELECT * FROM muted_conversations WHERE threadId = :threadId")
    suspend fun getMutedConversation(threadId: Long): MutedConversation?

    @Query("SELECT COUNT(*) FROM muted_conversations")
    suspend fun count(): Int

    @Query("DELETE FROM muted_conversations WHERE mutedUntil IS NOT NULL AND mutedUntil < :currentTime")
    suspend fun clearExpired(currentTime: Long = System.currentTimeMillis())

    @Query("DELETE FROM muted_conversations")
    suspend fun clearAll()
}
