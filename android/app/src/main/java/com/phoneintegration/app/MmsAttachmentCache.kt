package com.phoneintegration.app

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

class MmsAttachmentCache(context: Context) {
    private val appContext = context.applicationContext
    private val baseDir = File(appContext.filesDir, "mms_cache")

    fun cacheMessage(message: SmsMessage) {
        if (!message.isMms || message.id <= 0) return
        if (message.mmsAttachments.isEmpty() && message.body.isBlank()) return

        try {
            val messageDir = File(baseDir, message.id.toString())
            if (!messageDir.exists() && !messageDir.mkdirs()) {
                Log.w(TAG, "Failed to create cache dir for MMS ${message.id}")
                return
            }

            val cachedAttachments = JSONArray()
            message.mmsAttachments.forEach { attachment ->
                val cachedPath = cacheAttachment(messageDir, attachment) ?: return@forEach
                val entry = JSONObject()
                entry.put("id", attachment.id)
                entry.put("contentType", attachment.contentType)
                entry.put("fileName", attachment.fileName ?: JSONObject.NULL)
                entry.put("filePath", cachedPath)
                cachedAttachments.put(entry)
            }

            val root = JSONObject()
            root.put("body", message.body)
            root.put("attachments", cachedAttachments)

            val metaFile = File(messageDir, META_FILE)
            metaFile.writeText(root.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache MMS ${message.id}: ${e.message}")
        }
    }

    fun loadAttachments(messageId: Long): List<MmsAttachment> {
        val meta = readMeta(messageId) ?: return emptyList()
        val attachmentsJson = meta.optJSONArray("attachments") ?: return emptyList()
        if (attachmentsJson.length() == 0) return emptyList()

        val list = mutableListOf<MmsAttachment>()
        for (i in 0 until attachmentsJson.length()) {
            val entry = attachmentsJson.optJSONObject(i) ?: continue
            val id = entry.optLong("id", -1L)
            val contentType = entry.optString("contentType", "")
            val fileName = entry.optString("fileName", null)
            val pathRaw = entry.optString("filePath", "")
            if (id <= 0 || contentType.isBlank() || pathRaw.isBlank()) continue

            val file = File(pathRaw)
            if (!file.exists()) continue

            val filePath = Uri.fromFile(file).toString()
            list.add(
                MmsAttachment(
                    id = id,
                    contentType = contentType,
                    filePath = filePath,
                    data = null,
                    fileName = fileName
                )
            )
        }

        return list
    }

    fun loadBody(messageId: Long): String? {
        val meta = readMeta(messageId) ?: return null
        val body = meta.optString("body", "")
        return body.ifBlank { null }
    }

    private fun cacheAttachment(messageDir: File, attachment: MmsAttachment): String? {
        val extension = resolveExtension(attachment.contentType, attachment.fileName)
        val target = File(messageDir, "${attachment.id}.$extension")
        if (target.exists()) return target.absolutePath

        if (attachment.data != null) {
            try {
                target.outputStream().use { it.write(attachment.data) }
                return target.absolutePath
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write attachment ${attachment.id}: ${e.message}")
                return null
            }
        }

        val uri = attachment.filePath?.let { Uri.parse(it) } ?: return null
        val inputStream = try {
            when (uri.scheme) {
                "file" -> FileInputStream(uri.path)
                else -> appContext.contentResolver.openInputStream(uri)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open attachment ${attachment.id}: ${e.message}")
            null
        } ?: return null

        try {
            inputStream.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache attachment ${attachment.id}: ${e.message}")
            return null
        }

        return target.absolutePath
    }

    private fun readMeta(messageId: Long): JSONObject? {
        val metaFile = File(File(baseDir, messageId.toString()), META_FILE)
        if (!metaFile.exists()) return null
        return try {
            JSONObject(metaFile.readText())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse MMS cache meta for $messageId: ${e.message}")
            null
        }
    }

    private fun resolveExtension(contentType: String, fileName: String?): String {
        val lowerName = fileName?.lowercase() ?: ""
        val nameExt = lowerName.substringAfterLast('.', "")
        if (nameExt.isNotBlank()) {
            return nameExt
        }
        return when {
            contentType.contains("jpeg") || contentType.contains("jpg") -> "jpg"
            contentType.contains("png") -> "png"
            contentType.contains("gif") -> "gif"
            contentType.contains("webp") -> "webp"
            contentType.contains("mp4") -> "mp4"
            contentType.contains("3gp") -> "3gp"
            contentType.contains("mpeg") -> "mpg"
            contentType.contains("audio") -> "m4a"
            else -> "bin"
        }
    }

    companion object {
        private const val TAG = "MmsAttachmentCache"
        private const val META_FILE = "meta.json"
    }
}
