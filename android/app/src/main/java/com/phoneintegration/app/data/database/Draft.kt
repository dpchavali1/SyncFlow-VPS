package com.phoneintegration.app.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Entity for message drafts
 */
@Entity(
    tableName = "drafts",
    indices = [Index(value = ["address"], unique = true)]
)
data class Draft(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val address: String,
    val threadId: Long? = null,
    val body: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val contactName: String? = null,
    val attachmentUri: String? = null,
    val attachmentType: String? = null // "image", "video", "audio"
)

/**
 * DAO for drafts
 */
@Dao
interface DraftDao {
    @Query("SELECT * FROM drafts ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<Draft>>

    @Query("SELECT * FROM drafts WHERE address = :address LIMIT 1")
    suspend fun getForAddress(address: String): Draft?

    @Query("SELECT * FROM drafts WHERE threadId = :threadId LIMIT 1")
    suspend fun getForThread(threadId: Long): Draft?

    @Query("SELECT * FROM drafts WHERE address = :address LIMIT 1")
    fun observeForAddress(address: String): Flow<Draft?>

    @Query("SELECT * FROM drafts WHERE id = :id")
    suspend fun getById(id: Long): Draft?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(draft: Draft): Long

    @Update
    suspend fun update(draft: Draft)

    @Delete
    suspend fun delete(draft: Draft)

    @Query("DELETE FROM drafts WHERE address = :address")
    suspend fun deleteForAddress(address: String)

    @Query("DELETE FROM drafts WHERE threadId = :threadId")
    suspend fun deleteForThread(threadId: Long)

    @Query("DELETE FROM drafts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM drafts")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM drafts WHERE body != ''")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM drafts WHERE updatedAt < :before")
    suspend fun deleteOldDrafts(before: Long)
}
