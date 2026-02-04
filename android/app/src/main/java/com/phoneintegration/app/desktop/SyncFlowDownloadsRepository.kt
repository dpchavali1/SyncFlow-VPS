package com.phoneintegration.app.desktop

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncFlowDownloadsRepository(private val context: Context) {

    data class DownloadEntry(
        val id: Long,
        val displayName: String,
        val sizeBytes: Long,
        val modifiedSeconds: Long,
        val contentType: String?,
        val uri: Uri
    )

    suspend fun loadDownloads(): List<DownloadEntry> = withContext(Dispatchers.IO) {
        val entries = mutableListOf<DownloadEntry>()
        val resolver = context.contentResolver
        val downloadsUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI

        val projection = mutableListOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.DATE_MODIFIED,
            MediaStore.Downloads.MIME_TYPE
        )

        val selection: String
        val selectionArgs: Array<String>

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection.add(MediaStore.Downloads.RELATIVE_PATH)
            selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
            selectionArgs = arrayOf("%Download/SyncFlow%")
        } else {
            @Suppress("DEPRECATION")
            projection.add(MediaStore.Downloads.DATA)
            @Suppress("DEPRECATION")
            selection = "${MediaStore.Downloads.DATA} LIKE ?"
            selectionArgs = arrayOf("%/Download/SyncFlow/%")
        }

        val sortOrder = "${MediaStore.Downloads.DATE_MODIFIED} DESC"

        try {
            resolver.query(downloadsUri, projection.toTypedArray(), selection, selectionArgs, sortOrder)
                ?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                    val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                    val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
                    val typeIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idIndex)
                        val name = cursor.getString(nameIndex) ?: "file"
                        val size = cursor.getLong(sizeIndex)
                        val modified = cursor.getLong(modifiedIndex)
                        val type = cursor.getString(typeIndex)
                        val uri = ContentUris.withAppendedId(downloadsUri, id)

                        entries.add(
                            DownloadEntry(
                                id = id,
                                displayName = name,
                                sizeBytes = size,
                                modifiedSeconds = modified,
                                contentType = type,
                                uri = uri
                            )
                        )
                    }
                }
        } catch (_: SecurityException) {
            return@withContext emptyList()
        }

        entries
    }

    suspend fun deleteDownloads(entries: List<DownloadEntry>): Int = withContext(Dispatchers.IO) {
        var deleted = 0
        val resolver = context.contentResolver
        for (entry in entries) {
            try {
                val result = resolver.delete(entry.uri, null, null)
                if (result > 0) {
                    deleted += 1
                }
            } catch (_: SecurityException) {
                // Ignore individual failures.
            }
        }
        deleted
    }
}
