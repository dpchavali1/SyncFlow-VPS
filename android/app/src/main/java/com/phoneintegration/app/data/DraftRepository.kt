package com.phoneintegration.app.data

import android.content.Context
import com.phoneintegration.app.data.database.Draft
import com.phoneintegration.app.data.database.SyncFlowDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Repository for managing message drafts with auto-save functionality
 */
class DraftRepository(context: Context) {

    private val database = SyncFlowDatabase.getInstance(context)
    private val draftDao = database.draftDao()
    private val scope = CoroutineScope(Dispatchers.IO)

    // Auto-save debounce job
    private var autoSaveJob: Job? = null
    private val AUTO_SAVE_DELAY_MS = 1000L // 1 second debounce

    // Cache of current draft being edited
    private var currentDraftAddress: String? = null
    private var pendingDraftText: String? = null

    /**
     * Save or update a draft with debouncing
     */
    fun saveDraftDebounced(
        address: String,
        body: String,
        threadId: Long? = null,
        contactName: String? = null
    ) {
        currentDraftAddress = address
        pendingDraftText = body

        autoSaveJob?.cancel()
        autoSaveJob = scope.launch {
            delay(AUTO_SAVE_DELAY_MS)
            saveDraftImmediate(address, body, threadId, contactName)
        }
    }

    /**
     * Save draft immediately (no debounce)
     */
    suspend fun saveDraftImmediate(
        address: String,
        body: String,
        threadId: Long? = null,
        contactName: String? = null,
        attachmentUri: String? = null,
        attachmentType: String? = null
    ) {
        // Don't save empty drafts
        if (body.isBlank() && attachmentUri == null) {
            // Delete existing draft if body is empty
            draftDao.deleteForAddress(address)
            return
        }

        val existing = draftDao.getForAddress(address)

        if (existing != null) {
            draftDao.update(existing.copy(
                body = body,
                updatedAt = System.currentTimeMillis(),
                contactName = contactName ?: existing.contactName,
                attachmentUri = attachmentUri ?: existing.attachmentUri,
                attachmentType = attachmentType ?: existing.attachmentType
            ))
        } else {
            draftDao.insert(Draft(
                address = address,
                threadId = threadId,
                body = body,
                contactName = contactName,
                attachmentUri = attachmentUri,
                attachmentType = attachmentType
            ))
        }
    }

    /**
     * Get draft for a conversation
     */
    suspend fun getDraft(address: String): Draft? {
        return draftDao.getForAddress(address)
    }

    /**
     * Get draft for a thread ID
     */
    suspend fun getDraftForThread(threadId: Long): Draft? {
        return draftDao.getForThread(threadId)
    }

    /**
     * Observe draft for a conversation
     */
    fun observeDraft(address: String): Flow<Draft?> {
        return draftDao.observeForAddress(address)
    }

    /**
     * Get all drafts
     */
    fun getAllDrafts(): Flow<List<Draft>> {
        return draftDao.getAll()
    }

    /**
     * Observe draft count
     */
    fun observeDraftCount(): Flow<Int> {
        return draftDao.observeCount()
    }

    /**
     * Delete draft after sending message
     */
    suspend fun deleteDraft(address: String) {
        autoSaveJob?.cancel()
        if (currentDraftAddress == address) {
            currentDraftAddress = null
            pendingDraftText = null
        }
        draftDao.deleteForAddress(address)
    }

    /**
     * Delete draft by thread ID
     */
    suspend fun deleteDraftForThread(threadId: Long) {
        draftDao.deleteForThread(threadId)
    }

    /**
     * Delete draft by ID
     */
    suspend fun deleteDraftById(id: Long) {
        draftDao.deleteById(id)
    }

    /**
     * Clean up old drafts (older than 30 days)
     */
    suspend fun cleanupOldDrafts() {
        val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
        draftDao.deleteOldDrafts(thirtyDaysAgo)
    }

    /**
     * Flush any pending saves
     */
    suspend fun flushPendingSaves() {
        autoSaveJob?.cancel()
        val address = currentDraftAddress
        val text = pendingDraftText
        if (address != null && text != null) {
            saveDraftImmediate(address, text)
        }
    }

    /**
     * Get draft count
     */
    suspend fun getDraftCount(): Int {
        return draftDao.count()
    }
}
