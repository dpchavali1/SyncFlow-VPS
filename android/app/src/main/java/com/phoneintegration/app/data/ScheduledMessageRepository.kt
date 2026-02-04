package com.phoneintegration.app.data

import android.content.Context
import com.phoneintegration.app.data.database.ScheduledMessage
import com.phoneintegration.app.data.database.ScheduledStatus
import com.phoneintegration.app.data.database.SyncFlowDatabase
import com.phoneintegration.app.workers.ScheduledMessageWorker
import kotlinx.coroutines.flow.Flow

/**
 * Repository for managing scheduled messages
 */
class ScheduledMessageRepository(private val context: Context) {

    private val database = SyncFlowDatabase.getInstance(context)
    private val scheduledDao = database.scheduledMessageDao()

    /**
     * Schedule a new message
     */
    suspend fun scheduleMessage(
        address: String,
        body: String,
        scheduledTime: Long,
        contactName: String? = null
    ): Long {
        val message = ScheduledMessage(
            address = address,
            body = body,
            scheduledTime = scheduledTime,
            contactName = contactName,
            status = ScheduledStatus.PENDING
        )

        val id = scheduledDao.insert(message)

        // Calculate delay and schedule worker
        val delay = scheduledTime - System.currentTimeMillis()
        if (delay > 0) {
            ScheduledMessageWorker.scheduleMessage(context, id, delay)
        }

        return id
    }

    /**
     * Update scheduled message
     */
    suspend fun updateMessage(
        id: Long,
        body: String? = null,
        scheduledTime: Long? = null
    ) {
        val existing = scheduledDao.getById(id) ?: return

        val updated = existing.copy(
            body = body ?: existing.body,
            scheduledTime = scheduledTime ?: existing.scheduledTime
        )

        scheduledDao.update(updated)

        // Reschedule if time changed
        if (scheduledTime != null && scheduledTime != existing.scheduledTime) {
            ScheduledMessageWorker.cancelMessage(context, id)
            val delay = scheduledTime - System.currentTimeMillis()
            if (delay > 0) {
                ScheduledMessageWorker.scheduleMessage(context, id, delay)
            }
        }
    }

    /**
     * Cancel a scheduled message
     */
    suspend fun cancelMessage(id: Long) {
        scheduledDao.updateStatus(id, ScheduledStatus.CANCELLED)
        ScheduledMessageWorker.cancelMessage(context, id)
    }

    /**
     * Delete a scheduled message
     */
    suspend fun deleteMessage(id: Long) {
        ScheduledMessageWorker.cancelMessage(context, id)
        scheduledDao.deleteById(id)
    }

    /**
     * Get all pending scheduled messages
     */
    fun getPendingMessages(): Flow<List<ScheduledMessage>> {
        return scheduledDao.getByStatus(ScheduledStatus.PENDING)
    }

    /**
     * Get all scheduled messages
     */
    fun getAllMessages(): Flow<List<ScheduledMessage>> {
        return scheduledDao.getAll()
    }

    /**
     * Get scheduled messages for a conversation
     */
    fun getForConversation(address: String): Flow<List<ScheduledMessage>> {
        return scheduledDao.getForConversation(address, ScheduledStatus.PENDING)
    }

    /**
     * Get message by ID
     */
    suspend fun getMessage(id: Long): ScheduledMessage? {
        return scheduledDao.getById(id)
    }

    /**
     * Get count of pending messages
     */
    suspend fun getPendingCount(): Int {
        return scheduledDao.countByStatus(ScheduledStatus.PENDING)
    }

    /**
     * Retry a failed message
     */
    suspend fun retryMessage(id: Long) {
        val message = scheduledDao.getById(id) ?: return

        if (message.status == ScheduledStatus.FAILED) {
            scheduledDao.update(message.copy(
                status = ScheduledStatus.PENDING,
                scheduledTime = System.currentTimeMillis() + 60000, // 1 minute from now
                retryCount = 0,
                errorMessage = null
            ))

            ScheduledMessageWorker.scheduleMessage(context, id, 60000)
        }
    }
}
