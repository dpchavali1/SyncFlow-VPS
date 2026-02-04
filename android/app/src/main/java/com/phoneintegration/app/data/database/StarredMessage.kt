package com.phoneintegration.app.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Entity for storing starred/important messages
 */
@Entity(tableName = "starred_messages")
data class StarredMessage(
    @PrimaryKey
    val messageId: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val timestamp: Long,
    val isMms: Boolean = false,
    val starredAt: Long = System.currentTimeMillis()
)

/**
 * DAO for starred messages
 */
@Dao
interface StarredMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun star(message: StarredMessage)

    @Query("DELETE FROM starred_messages WHERE messageId = :messageId")
    suspend fun unstar(messageId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM starred_messages WHERE messageId = :messageId)")
    suspend fun isStarred(messageId: Long): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM starred_messages WHERE messageId = :messageId)")
    fun observeIsStarred(messageId: Long): Flow<Boolean>

    @Query("SELECT * FROM starred_messages ORDER BY starredAt DESC")
    fun getAllStarred(): Flow<List<StarredMessage>>

    @Query("SELECT * FROM starred_messages ORDER BY starredAt DESC")
    suspend fun getStarredMessages(): List<StarredMessage>

    @Query("SELECT * FROM starred_messages WHERE threadId = :threadId ORDER BY starredAt DESC")
    fun getStarredForThread(threadId: Long): Flow<List<StarredMessage>>

    @Query("SELECT messageId FROM starred_messages")
    suspend fun getAllStarredIds(): List<Long>

    @Query("SELECT COUNT(*) FROM starred_messages")
    suspend fun count(): Int

    @Query("DELETE FROM starred_messages")
    suspend fun clearAll()
}
