package com.phoneintegration.app.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        Group::class,
        GroupMember::class,
        ScheduledMessage::class,
        Draft::class,
        ArchivedConversation::class,
        PinnedConversation::class,
        MutedConversation::class,
        StarredMessage::class,
        BlockedContact::class,
        NotificationSettings::class,
        CachedConversation::class,
        SpamMessage::class
    ],
    version = 9,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun groupDao(): GroupDao
    abstract fun scheduledMessageDao(): ScheduledMessageDao
    abstract fun draftDao(): DraftDao
    abstract fun archivedConversationDao(): ArchivedConversationDao
    abstract fun pinnedConversationDao(): PinnedConversationDao
    abstract fun mutedConversationDao(): MutedConversationDao
    abstract fun starredMessageDao(): StarredMessageDao
    abstract fun blockedContactDao(): BlockedContactDao
    abstract fun notificationSettingsDao(): NotificationSettingsDao
    abstract fun cachedConversationDao(): CachedConversationDao
    abstract fun spamMessageDao(): SpamMessageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "syncflow_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// Alias for compatibility
typealias SyncFlowDatabase = AppDatabase
