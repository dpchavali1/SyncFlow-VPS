package com.phoneintegration.app.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Entity for storing blocked contacts/phone numbers
 */
@Entity(tableName = "blocked_contacts")
data class BlockedContact(
    @PrimaryKey
    val phoneNumber: String,  // Normalized phone number
    val displayName: String? = null,  // Optional display name at time of blocking
    val blockedAt: Long = System.currentTimeMillis(),
    val blockSms: Boolean = true,  // Block SMS from this contact
    val blockCalls: Boolean = true,  // Block calls from this contact
    val reason: String? = null  // Optional reason for blocking
)

/**
 * DAO for blocked contacts
 */
@Dao
interface BlockedContactDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun block(contact: BlockedContact)

    @Query("DELETE FROM blocked_contacts WHERE phoneNumber = :phoneNumber")
    suspend fun unblock(phoneNumber: String)

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_contacts WHERE phoneNumber = :phoneNumber)")
    suspend fun isBlocked(phoneNumber: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_contacts WHERE phoneNumber = :phoneNumber)")
    fun observeIsBlocked(phoneNumber: String): Flow<Boolean>

    @Query("SELECT * FROM blocked_contacts ORDER BY blockedAt DESC")
    fun getAllBlocked(): Flow<List<BlockedContact>>

    @Query("SELECT * FROM blocked_contacts ORDER BY blockedAt DESC")
    suspend fun getBlockedContacts(): List<BlockedContact>

    @Query("SELECT phoneNumber FROM blocked_contacts")
    suspend fun getAllBlockedNumbers(): List<String>

    @Query("SELECT phoneNumber FROM blocked_contacts")
    fun observeAllBlockedNumbers(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM blocked_contacts")
    suspend fun count(): Int

    @Query("DELETE FROM blocked_contacts")
    suspend fun clearAll()

    @Query("UPDATE blocked_contacts SET blockSms = :blockSms, blockCalls = :blockCalls WHERE phoneNumber = :phoneNumber")
    suspend fun updateBlockSettings(phoneNumber: String, blockSms: Boolean, blockCalls: Boolean)
}
