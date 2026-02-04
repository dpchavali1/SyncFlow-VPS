package com.phoneintegration.app.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Entity for storing custom notification settings per conversation
 */
@Entity(tableName = "notification_settings")
data class NotificationSettings(
    @PrimaryKey
    val threadId: Long,
    val customSoundUri: String? = null,  // URI of custom notification sound
    val vibrationEnabled: Boolean = true,
    val ledEnabled: Boolean = true,
    val ledColor: Int? = null,  // Custom LED color (if supported)
    val priority: Int = 0,  // 0 = default, 1 = high, -1 = low
    val popupEnabled: Boolean = true,  // Show popup notification
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * DAO for notification settings
 */
@Dao
interface NotificationSettingsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(settings: NotificationSettings)

    @Query("SELECT * FROM notification_settings WHERE threadId = :threadId")
    suspend fun get(threadId: Long): NotificationSettings?

    @Query("SELECT * FROM notification_settings WHERE threadId = :threadId")
    fun observe(threadId: Long): Flow<NotificationSettings?>

    @Query("SELECT customSoundUri FROM notification_settings WHERE threadId = :threadId")
    suspend fun getCustomSoundUri(threadId: Long): String?

    @Query("UPDATE notification_settings SET customSoundUri = :soundUri, updatedAt = :timestamp WHERE threadId = :threadId")
    suspend fun updateSoundUri(threadId: Long, soundUri: String?, timestamp: Long)

    @Query("UPDATE notification_settings SET vibrationEnabled = :enabled, updatedAt = :timestamp WHERE threadId = :threadId")
    suspend fun updateVibration(threadId: Long, enabled: Boolean, timestamp: Long)

    @Query("SELECT * FROM notification_settings")
    fun getAllSettings(): Flow<List<NotificationSettings>>

    @Query("SELECT * FROM notification_settings WHERE customSoundUri IS NOT NULL")
    suspend fun getCustomSoundSettings(): List<NotificationSettings>

    @Query("DELETE FROM notification_settings WHERE threadId = :threadId")
    suspend fun delete(threadId: Long)

    @Query("DELETE FROM notification_settings")
    suspend fun clearAll()
}
