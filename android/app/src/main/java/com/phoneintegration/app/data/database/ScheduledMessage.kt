package com.phoneintegration.app.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Entity for scheduled messages
 */
@Entity(tableName = "scheduled_messages")
data class ScheduledMessage(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val address: String,
    val body: String,
    val scheduledTime: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val status: ScheduledStatus = ScheduledStatus.PENDING,
    val contactName: String? = null,
    val isMms: Boolean = false,
    val attachmentUri: String? = null,
    val errorMessage: String? = null,
    val retryCount: Int = 0
)

enum class ScheduledStatus {
    PENDING,
    SENDING,
    SENT,
    FAILED,
    CANCELLED
}

/**
 * DAO for scheduled messages
 */
@Dao
interface ScheduledMessageDao {
    @Query("SELECT * FROM scheduled_messages WHERE status = :status ORDER BY scheduledTime ASC")
    fun getByStatus(status: ScheduledStatus): Flow<List<ScheduledMessage>>

    @Query("SELECT * FROM scheduled_messages WHERE status IN (:statuses) ORDER BY scheduledTime ASC")
    fun getByStatuses(statuses: List<ScheduledStatus>): Flow<List<ScheduledMessage>>

    @Query("SELECT * FROM scheduled_messages ORDER BY scheduledTime ASC")
    fun getAll(): Flow<List<ScheduledMessage>>

    @Query("SELECT * FROM scheduled_messages WHERE id = :id")
    suspend fun getById(id: Long): ScheduledMessage?

    @Query("SELECT * FROM scheduled_messages WHERE scheduledTime <= :time AND status = :status")
    suspend fun getDueMessages(time: Long, status: ScheduledStatus): List<ScheduledMessage>

    @Query("SELECT * FROM scheduled_messages WHERE address = :address AND status = :status ORDER BY scheduledTime ASC")
    fun getForConversation(address: String, status: ScheduledStatus): Flow<List<ScheduledMessage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ScheduledMessage): Long

    @Update
    suspend fun update(message: ScheduledMessage)

    @Delete
    suspend fun delete(message: ScheduledMessage)

    @Query("DELETE FROM scheduled_messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE scheduled_messages SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: ScheduledStatus)

    @Query("UPDATE scheduled_messages SET status = :status, errorMessage = :error, retryCount = retryCount + 1 WHERE id = :id")
    suspend fun markFailed(id: Long, status: ScheduledStatus = ScheduledStatus.FAILED, error: String?)

    @Query("SELECT COUNT(*) FROM scheduled_messages WHERE status = :status")
    suspend fun countByStatus(status: ScheduledStatus): Int
}
