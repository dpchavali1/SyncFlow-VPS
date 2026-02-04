package com.phoneintegration.app.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Entity for tracking spam messages.
 * Messages detected as spam are stored here for the spam folder.
 */
@Entity(
    tableName = "spam_messages",
    indices = [Index(value = ["address"])]
)
data class SpamMessage(
    @PrimaryKey
    val messageId: Long,  // Original SMS message ID from content provider
    val address: String,  // Phone number
    val body: String,  // Message body
    val date: Long,  // Message timestamp
    val contactName: String? = null,  // Contact name if available
    val spamConfidence: Float = 0.5f,  // Spam confidence score (0-1)
    val spamReasons: String? = null,  // JSON array of reasons
    val detectedAt: Long = System.currentTimeMillis(),
    val isUserMarked: Boolean = false,  // True if user manually marked as spam
    val isRead: Boolean = false  // Track if user has seen this spam message
)

/**
 * DAO for spam messages
 */
@Dao
interface SpamMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: SpamMessage)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<SpamMessage>)

    @Query("DELETE FROM spam_messages WHERE messageId = :messageId")
    suspend fun delete(messageId: Long)

    @Query("DELETE FROM spam_messages WHERE address = :address")
    suspend fun deleteByAddress(address: String)

    @Query("SELECT * FROM spam_messages ORDER BY date DESC")
    fun getAllSpam(): Flow<List<SpamMessage>>

    @Query("SELECT * FROM spam_messages ORDER BY date DESC")
    suspend fun getSpamMessages(): List<SpamMessage>

    @Query("SELECT * FROM spam_messages WHERE address = :address ORDER BY date DESC")
    fun getSpamByAddress(address: String): Flow<List<SpamMessage>>

    @Query("SELECT EXISTS(SELECT 1 FROM spam_messages WHERE messageId = :messageId)")
    suspend fun isSpam(messageId: Long): Boolean

    @Query("SELECT messageId FROM spam_messages")
    suspend fun getAllSpamIds(): List<Long>

    @Query("SELECT DISTINCT address FROM spam_messages")
    suspend fun getSpamAddresses(): List<String>

    @Query("SELECT DISTINCT address FROM spam_messages")
    fun getSpamAddressesFlow(): Flow<List<String>>


    @Query("SELECT COUNT(*) FROM spam_messages WHERE isRead = 0")
    fun getUnreadSpamCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM spam_messages")
    suspend fun getTotalSpamCount(): Int

    @Query("UPDATE spam_messages SET isRead = 1 WHERE messageId = :messageId")
    suspend fun markAsRead(messageId: Long)

    @Query("UPDATE spam_messages SET isRead = 1")
    suspend fun markAllAsRead()

    @Query("DELETE FROM spam_messages")
    suspend fun clearAll()

    // Move message out of spam (user marked as not spam)
    @Query("DELETE FROM spam_messages WHERE messageId = :messageId")
    suspend fun removeFromSpam(messageId: Long)

    // Get most recent spam for a conversation
    @Query("SELECT * FROM spam_messages WHERE address = :address ORDER BY date DESC LIMIT 1")
    suspend fun getLatestSpamForAddress(address: String): SpamMessage?

    // Get spam message by ID
    @Query("SELECT * FROM spam_messages WHERE messageId = :messageId")
    suspend fun getByMessageId(messageId: Long): SpamMessage?
}
