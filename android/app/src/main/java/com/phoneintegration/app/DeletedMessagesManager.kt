package com.phoneintegration.app

import android.content.Context
import android.content.SharedPreferences

class DeletedMessagesManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "deleted_messages",
        Context.MODE_PRIVATE
    )

    private val DELETED_IDS_KEY = "deleted_ids"

    fun markAsDeleted(messageId: Long) {
        val deletedIds = getDeletedIds().toMutableSet()
        deletedIds.add(messageId)
        saveDeletedIds(deletedIds)
    }

    fun isDeleted(messageId: Long): Boolean {
        return getDeletedIds().contains(messageId)
    }

    fun getDeletedIds(): Set<Long> {
        val idsString = prefs.getString(DELETED_IDS_KEY, "") ?: ""
        return if (idsString.isEmpty()) {
            emptySet()
        } else {
            idsString.split(",").mapNotNull { it.toLongOrNull() }.toSet()
        }
    }

    private fun saveDeletedIds(ids: Set<Long>) {
        val idsString = ids.joinToString(",")
        prefs.edit().putString(DELETED_IDS_KEY, idsString).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}