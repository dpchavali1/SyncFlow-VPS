package com.phoneintegration.app.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Entity for archived conversations
 */
@Entity(
    tableName = "archived_conversations",
    indices = [Index(value = ["threadId"], unique = true)]
)
data class ArchivedConversation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val threadId: Long,
    val address: String,
    val contactName: String? = null,
    val lastMessage: String,
    val archivedAt: Long = System.currentTimeMillis(),
    val messageCount: Int = 0,
    val photoUri: String? = null
)

/**
 * DAO for archived conversations
 */
@Dao
interface ArchivedConversationDao {
    @Query("SELECT * FROM archived_conversations ORDER BY archivedAt DESC")
    fun getAll(): Flow<List<ArchivedConversation>>

    @Query("SELECT * FROM archived_conversations WHERE threadId = :threadId LIMIT 1")
    suspend fun getByThreadId(threadId: Long): ArchivedConversation?

    @Query("SELECT * FROM archived_conversations WHERE address = :address LIMIT 1")
    suspend fun getByAddress(address: String): ArchivedConversation?

    @Query("SELECT threadId FROM archived_conversations")
    suspend fun getAllThreadIds(): List<Long>

    @Query("SELECT threadId FROM archived_conversations")
    fun observeThreadIds(): Flow<List<Long>>

    @Query("SELECT COUNT(*) FROM archived_conversations")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM archived_conversations")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(archived: ArchivedConversation): Long

    @Update
    suspend fun update(archived: ArchivedConversation)

    @Delete
    suspend fun delete(archived: ArchivedConversation)

    @Query("DELETE FROM archived_conversations WHERE threadId = :threadId")
    suspend fun deleteByThreadId(threadId: Long)

    @Query("DELETE FROM archived_conversations WHERE address = :address")
    suspend fun deleteByAddress(address: String)

    @Query("SELECT EXISTS(SELECT 1 FROM archived_conversations WHERE threadId = :threadId)")
    suspend fun isArchived(threadId: Long): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM archived_conversations WHERE threadId = :threadId)")
    fun observeIsArchived(threadId: Long): Flow<Boolean>

    @Query("SELECT * FROM archived_conversations WHERE contactName LIKE :query OR address LIKE :query OR lastMessage LIKE :query")
    fun search(query: String): Flow<List<ArchivedConversation>>
}
