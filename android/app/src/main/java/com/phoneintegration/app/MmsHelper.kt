package com.phoneintegration.app

import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Telephony
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.Charset
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Random
import com.klinker.android.send_message.Message
import com.klinker.android.send_message.Settings
import com.klinker.android.send_message.Transaction
import com.google.android.mms.MMSPart
import android.graphics.BitmapFactory
import android.graphics.Matrix

object MmsHelper {

    private const val TAG = "MmsHelper"

    // Klinker library settings - will be initialized on first use
    private var klinkerSettings: Settings? = null

    /**
     * Initialize Klinker MMS settings
     */
    private fun getKlinkerSettings(ctx: Context): Settings {
        if (klinkerSettings == null) {
            Settings.setDebugLogging(true, "KlinkerMMS")
            klinkerSettings = Settings().apply {
                // Prefer system sending so carrier + platform handle delivery.
                useSystemSending = true

                // MMS Settings - Klinker will auto-detect APN if these are empty
                mmsc = ""  // Will be auto-detected
                proxy = ""
                port = ""

                // Delivery reports
                deliveryReports = true

                // Split messages
                split = false

                // Strip unicode
                stripUnicode = false

                // Signature
                signature = ""

                // Send long as MMS
                sendLongAsMms = true
                sendLongAsMmsAfter = 3  // After 3 SMS segments

                // Group messaging
                group = true
            }
        }
        val defaultSubId = SubscriptionManager.getDefaultSmsSubscriptionId()
        if (defaultSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            klinkerSettings?.setSubscriptionId(defaultSubId)
        }
        return klinkerSettings!!
    }

    /**
     * Send group MMS to multiple recipients (creates a true group conversation)
     * Uses Klinker library for reliable delivery
     */
    fun sendGroupMms(
        ctx: Context,
        recipients: List<String>,
        messageText: String?,
        imageUri: Uri? = null
    ): Boolean {
        return try {
            Log.d(TAG, "=== Starting GROUP MMS send via Klinker ===")
            Log.d(TAG, "Recipients: ${recipients.joinToString(", ")}")
            Log.d(TAG, "Message: $messageText")
            Log.d(TAG, "Image URI: $imageUri")

            // Check if app is default SMS app
            val isDefault = SmsPermissions.isDefaultSmsApp(ctx)
            Log.d(TAG, "Is default SMS app: $isDefault")
            if (!isDefault) {
                Log.w(TAG, "⚠ WARNING: App is NOT set as default SMS app. MMS may fail!")
            }

            val settings = getKlinkerSettings(ctx)
            val transaction = Transaction(ctx, settings)

            // Clean recipient numbers
            val cleanRecipients = recipients.map { it.replace(Regex("[^0-9+]"), "") }
                .filter { it.isNotBlank() }
                .toTypedArray()

            if (cleanRecipients.isEmpty()) {
                Log.e(TAG, "No valid recipients")
                return false
            }

            // Add image if present
            val bitmap: android.graphics.Bitmap? = if (imageUri != null) {
                val resolver = ctx.contentResolver
                val imageBytes = resolver.openInputStream(imageUri)?.use { it.readBytes() }

                if (imageBytes != null && imageBytes.isNotEmpty()) {
                    BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)?.also {
                        Log.d(TAG, "Decoded image for group message: ${it.width}x${it.height}")
                    }
                } else null
            } else null

            // Create message with all recipients (and image if present)
            val message = if (bitmap != null) {
                Message(messageText ?: "", cleanRecipients, bitmap)
            } else {
                Message(messageText ?: "", cleanRecipients)
            }

            // Send the group message
            Log.d(TAG, "Calling Klinker transaction.sendNewMessage() for group...")
            transaction.sendNewMessage(message, Transaction.NO_THREAD_ID)

            Log.d(TAG, "✓ Group MMS sent successfully via Klinker")
            true

        } catch (e: Exception) {
            Log.e(TAG, "✗ GROUP MMS SEND FAILED - Exception: ${e.message}", e)
            e.printStackTrace()
            false
        }
    }

    /**
     * Public API: Sends MMS with a single image attachment
     * Tries multiple approaches for maximum compatibility
     */
    fun sendMms(
        ctx: Context,
        address: String,
        sourceUri: Uri,
        messageText: String? = null
    ): Boolean {
        return try {
            Log.d(TAG, "=== Starting MMS send ===")
            Log.d(TAG, "To: $address")
            Log.d(TAG, "Source URI: $sourceUri")
            Log.d(TAG, "Message text: $messageText")

            val isDefault = SmsPermissions.isDefaultSmsApp(ctx)
            Log.d(TAG, "Is default SMS app: $isDefault")

            if (!isDefault) {
                Log.w(TAG, "Not default SMS app - using intent fallback")
                return sendMmsViaIntent(ctx, address, sourceUri, messageText)
            }

            val resolver = ctx.contentResolver

            // Get MIME type
            val mimeType = resolver.getType(sourceUri)
                ?: guessMimeTypeFromUri(resolver, sourceUri)
                ?: "image/jpeg"
            Log.d(TAG, "MIME type: $mimeType")

            // Read image bytes
            val imageBytes = resolver.openInputStream(sourceUri)?.use { it.readBytes() }
            if (imageBytes == null || imageBytes.isEmpty()) {
                Log.e(TAG, "Failed to read image")
                return false
            }
            Log.d(TAG, "Image size: ${imageBytes.size} bytes")

            // Clean recipient number
            val cleanAddress = address.replace(Regex("[^0-9+]"), "")
            Log.d(TAG, "Clean address: $cleanAddress")

            val prepared = prepareMmsImagePayload(ctx, imageBytes, mimeType, sourceUri)
            Log.d(TAG, "Prepared MMS image: ${prepared.bytes.size} bytes, ${prepared.mimeType}, ${prepared.fileName}")

            // Try Klinker library first (system send)
            Log.d(TAG, "Trying Klinker library (system send)...")
            if (sendMmsViaKlinker(
                    ctx,
                    cleanAddress,
                    messageText,
                    prepared.bytes,
                    prepared.mimeType,
                    prepared.fileName
                )
            ) {
                Log.d(TAG, "✓ MMS sent via Klinker library")
                return true
            }

            // Second attempt: Native SmsManager send (default SMS experience)
            Log.d(TAG, "Klinker failed, trying native SmsManager sendMultimediaMessage...")
            if (sendMmsNative(
                    ctx,
                    cleanAddress,
                    messageText,
                    sourceUri,
                    prepared.mimeType
                )
            ) {
                Log.d(TAG, "✓ MMS sent via native SmsManager stream")
                return true
            }

            // Third attempt: provider + SmsManager fallback
            Log.d(TAG, "Native send failed, trying provider-based MMS send...")
            if (sendMmsViaContentProvider(
                    ctx,
                    cleanAddress,
                    messageText,
                    sourceUri,
                    prepared.mimeType,
                    prepared.bytes,
                    prepared.fileName
                )
            ) {
                Log.d(TAG, "✓ MMS sent via provider-based send")
                return true
            }

            // Final fallback: open the system messaging UI
            Log.d(TAG, "All methods failed, opening system messaging...")
            return sendMmsViaIntent(ctx, address, sourceUri, messageText)

        } catch (e: Exception) {
            Log.e(TAG, "✗ MMS SEND FAILED: ${e.message}", e)
            e.printStackTrace()
            false
        }
    }

    /**
     * Send MMS using native Android APIs - insert to database and use SmsManager
     */
    private fun sendMmsNative(
        ctx: Context,
        address: String,
        messageText: String?,
        sourceUri: Uri,
        mimeType: String
    ): Boolean {
        return try {
            val resolver = ctx.contentResolver
            val threadId = Telephony.Threads.getOrCreateThreadId(ctx, setOf(address))
            val nowSec = System.currentTimeMillis() / 1000L

            Log.d(TAG, "Native MMS - Creating for thread: $threadId")

            // Read and scale image
            val imageBytes = resolver.openInputStream(sourceUri)?.use { it.readBytes() } ?: return false
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return false
            val scaledBitmap = scaleDownBitmap(bitmap, 800)  // Scale to max 800px for MMS

            // Compress to JPEG with quality reduction for MMS size limits
            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, outputStream)
            val compressedBytes = outputStream.toByteArray()
            Log.d(TAG, "Compressed image: ${compressedBytes.size} bytes")

            if (scaledBitmap != bitmap) scaledBitmap.recycle()

            // Write compressed image to temp file
            val tempFile = File(ctx.cacheDir, "mms_temp_${System.currentTimeMillis()}.jpg")
            tempFile.writeBytes(compressedBytes)
            val tempUri = FileProvider.getUriForFile(ctx, ctx.packageName + ".provider", tempFile)

            // Insert MMS to Sent folder directly (not outbox)
            val mmsValues = ContentValues().apply {
                put(Telephony.Mms.THREAD_ID, threadId)
                put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_SENT)
                put(Telephony.Mms.DATE, nowSec)
                put(Telephony.Mms.DATE_SENT, nowSec)
                put(Telephony.Mms.READ, 1)
                put(Telephony.Mms.SEEN, 1)
                put(Telephony.Mms.MESSAGE_TYPE, 128)  // SEND_REQ
                put(Telephony.Mms.MMS_VERSION, 18)
                put(Telephony.Mms.CONTENT_TYPE, "application/vnd.wap.multipart.related")
            }

            val mmsUri = resolver.insert(Telephony.Mms.CONTENT_URI, mmsValues) ?: return false
            val mmsId = ContentUris.parseId(mmsUri)
            Log.d(TAG, "Created MMS entry: $mmsId")

            // Insert recipient address
            val addrValues = ContentValues().apply {
                put(Telephony.Mms.Addr.ADDRESS, address)
                put(Telephony.Mms.Addr.TYPE, 151)  // TO
                put(Telephony.Mms.Addr.CHARSET, 106)  // UTF-8
            }
            resolver.insert(Uri.parse("content://mms/$mmsId/addr"), addrValues)

            // Insert text part
            if (!messageText.isNullOrBlank()) {
                val textValues = ContentValues().apply {
                    put(Telephony.Mms.Part.MSG_ID, mmsId)
                    put(Telephony.Mms.Part.CONTENT_TYPE, "text/plain")
                    put(Telephony.Mms.Part.CHARSET, 106)
                    put(Telephony.Mms.Part.TEXT, messageText)
                }
                resolver.insert(Telephony.Mms.Part.CONTENT_URI, textValues)
            }

            // Insert image part
            val imageValues = ContentValues().apply {
                put(Telephony.Mms.Part.MSG_ID, mmsId)
                put(Telephony.Mms.Part.CONTENT_TYPE, "image/jpeg")
                put(Telephony.Mms.Part.NAME, "image.jpg")
                put(Telephony.Mms.Part.CONTENT_LOCATION, "image.jpg")
            }
            val partUri = resolver.insert(Telephony.Mms.Part.CONTENT_URI, imageValues)
            if (partUri != null) {
                resolver.openOutputStream(partUri)?.use { output ->
                    output.write(compressedBytes)
                }
                Log.d(TAG, "Image part written to: $partUri")
            }

            // Now send via SmsManager
            val pdu = buildSendPdu(address, messageText, compressedBytes)
            val pduFile = File(ctx.cacheDir, "mms_pdu_$mmsId.dat")
            pduFile.writeBytes(pdu)
            val pduUri = FileProvider.getUriForFile(ctx, ctx.packageName + ".provider", pduFile)

            // Grant permissions to system MMS service
            listOf("com.android.mms.service", "com.android.providers.telephony").forEach { pkg ->
                try {
                    ctx.grantUriPermission(pkg, pduUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
            }

            val sentIntent = PendingIntent.getBroadcast(
                ctx,
                mmsId.toInt(),
                Intent("com.phoneintegration.app.MMS_SENT")
                    .setClass(ctx, com.phoneintegration.app.mms.MmsSentReceiver::class.java)
                    .putExtra("mms_id", mmsId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val smsManager = getDefaultSmsManager(ctx)

            Log.d(TAG, "Calling sendMultimediaMessage with PDU size: ${pdu.size}")
            smsManager.sendMultimediaMessage(
                ctx.applicationContext,
                pduUri,
                null,
                Bundle(),
                sentIntent
            )

            Log.d(TAG, "✓ Native MMS sendMultimediaMessage called")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Native MMS send failed: ${e.message}", e)
            e.printStackTrace()
            false
        }
    }

    /**
     * Build a minimal but complete MMS PDU for sending
     */
    private fun buildSendPdu(address: String, text: String?, imageBytes: ByteArray): ByteArray {
        val pdu = ByteArrayOutputStream()

        // MMS Headers
        pdu.write(0x8C); pdu.write(0x80)  // Message-Type: m-send-req

        pdu.write(0x98)  // Transaction-ID
        val txnId = "T${System.currentTimeMillis()}"
        pdu.write(txnId.toByteArray(Charsets.US_ASCII))
        pdu.write(0x00)

        pdu.write(0x8D); pdu.write(0x90)  // MMS-Version: 1.0

        pdu.write(0x89); pdu.write(0x01); pdu.write(0x81)  // From: insert-address-token

        pdu.write(0x97)  // To
        pdu.write(address.toByteArray(Charsets.US_ASCII))
        pdu.write(0x00)

        pdu.write(0x84); pdu.write(0xA3)  // Content-Type: multipart/mixed

        // Body - parts count
        val hasText = !text.isNullOrBlank()
        pdu.write(if (hasText) 2 else 1)

        // Text part
        if (hasText) {
            val textBytes = text!!.toByteArray(Charsets.UTF_8)
            pdu.write(1)  // headers length
            writeUintVar(pdu, textBytes.size)
            pdu.write(0x83)  // text/plain
            pdu.write(textBytes)
        }

        // Image part
        pdu.write(1)  // headers length
        writeUintVar(pdu, imageBytes.size)
        pdu.write(0x9E)  // image/jpeg (well-known code)
        pdu.write(imageBytes)

        return pdu.toByteArray()
    }

    /**
     * Send MMS using Klinker android-smsmms library
     * This is the most reliable method across carriers
     */
    private fun sendMmsViaKlinker(
        ctx: Context,
        address: String,
        messageText: String?,
        imageBytes: ByteArray,
        mimeType: String,
        fileName: String
    ): Boolean {
        return try {
            Log.d(TAG, "Sending MMS via Klinker library...")
            Log.d(TAG, "Address: $address, Text: ${messageText?.take(50)}, Image size: ${imageBytes.size}")

            val settings = getKlinkerSettings(ctx)
            Log.d(TAG, "Klinker settings - useSystemSending: ${settings.useSystemSending}, mmsc: ${settings.mmsc}")

            Transaction(ctx, settings)

            val recipients = arrayOf(address)
            val parts = mutableListOf<MMSPart>()
            val imagePart = MMSPart().apply {
                MimeType = mimeType
                Name = fileName
                Data = imageBytes
            }
            parts.add(imagePart)
            if (!messageText.isNullOrBlank()) {
                parts.add(
                    MMSPart().apply {
                        MimeType = "text/plain"
                        Name = "text"
                        Data = messageText.toByteArray(Charsets.UTF_8)
                    }
                )
            }
            val info = Transaction.getBytes(
                ctx,
                true,
                null,
                recipients,
                parts.toTypedArray(),
                null
            )
            val pdu = info.bytes
            if (pdu == null || pdu.isEmpty()) {
                Log.e(TAG, "Klinker getBytes returned empty PDU")
                return false
            }

            val pduFile = File(ctx.cacheDir, "mms_send_${System.currentTimeMillis()}.dat")
            pduFile.writeBytes(pdu)
            val pduUri = FileProvider.getUriForFile(ctx, ctx.packageName + ".provider", pduFile)
            listOf("com.android.mms.service", "com.google.android.apps.messaging").forEach { pkg ->
                try {
                    ctx.grantUriPermission(pkg, pduUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
            }

            val mmsId = info.location?.let { uri ->
                runCatching { ContentUris.parseId(uri) }.getOrNull()
            }
            val sentIntent = PendingIntent.getBroadcast(
                ctx,
                (mmsId ?: System.currentTimeMillis()).toInt(),
                Intent("MMS_SENT").setClass(ctx, com.phoneintegration.app.mms.MmsSentReceiver::class.java)
                    .putExtra("mms_id", mmsId ?: -1L)
                    .putExtra("content_uri", info.location?.toString() ?: ""),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val smsManager = getDefaultSmsManager(ctx)
            Log.d(TAG, "Calling sendMultimediaMessage with PDU size: ${pdu.size}")
            smsManager.sendMultimediaMessage(
                ctx.applicationContext,
                pduUri,
                null,
                Bundle(),
                sentIntent
            )

            Log.d(TAG, "✓ Klinker PDU sendMultimediaMessage called successfully")

            true

        } catch (e: Exception) {
            Log.e(TAG, "Klinker MMS send failed: ${e.message}", e)
            e.printStackTrace()
            false
        }
    }

    /**
     * Scale down bitmap if too large for MMS
     */
    private fun scaleDownBitmap(bitmap: android.graphics.Bitmap, maxSize: Int): android.graphics.Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxSize && height <= maxSize) {
            return bitmap
        }

        val ratio = minOf(maxSize.toFloat() / width, maxSize.toFloat() / height)
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()

        return android.graphics.Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * Fallback: Send MMS via system APIs
     */
    private fun sendMmsViaSystem(
        ctx: Context,
        address: String,
        messageText: String?,
        sourceUri: Uri,
        mimeType: String
    ): Boolean {
        return try {
            val resolver = ctx.contentResolver
            val imageBytes = resolver.openInputStream(sourceUri)?.use { it.readBytes() } ?: return false

            // Try content provider approach
            val prepared = prepareMmsImagePayload(ctx, imageBytes, mimeType, sourceUri)
            val mmsSent = sendMmsViaContentProvider(
                ctx,
                address,
                messageText,
                sourceUri,
                prepared.mimeType,
                prepared.bytes,
                prepared.fileName
            )
            if (mmsSent) {
                Log.d(TAG, "✓ MMS sent via content provider")
                return true
            }

            // Fallback to PDU approach
            Log.d(TAG, "Content provider failed, trying PDU approach...")
            return sendMmsViaPdu(ctx, address, messageText, imageBytes, mimeType, sourceUri)

        } catch (e: Exception) {
            Log.e(TAG, "System MMS send failed: ${e.message}", e)
            false
        }
    }

    /**
     * Send MMS via content provider - lets system handle PDU construction
     * This is the most reliable method on modern Android
     */
    private fun sendMmsViaContentProvider(
        ctx: Context,
        address: String,
        messageText: String?,
        sourceUri: Uri,
        mimeType: String,
        imageBytes: ByteArray,
        fileName: String
    ): Boolean {
        return try {
            val resolver = ctx.contentResolver
            val cleanAddress = address.replace(Regex("[^0-9+]"), "")
            val threadId = Telephony.Threads.getOrCreateThreadId(ctx, setOf(cleanAddress))
            val nowSec = System.currentTimeMillis() / 1000L

            Log.d(TAG, "Inserting MMS to outbox for thread: $threadId")

            // Insert into Outbox (msg_box = 4) - system will process and send
            val mmsValues = ContentValues().apply {
                put("thread_id", threadId)
                put("msg_box", 4)  // MESSAGE_BOX_OUTBOX = 4
                put("date", nowSec)
                put("date_sent", nowSec)
                put("read", 1)
                put("seen", 1)
                put("m_type", 128)  // MESSAGE_TYPE_SEND_REQ
                put("v", 18)  // MMS version 1.2
                put("ct_t", "application/vnd.wap.multipart.related")
                put("exp", nowSec + 604800)  // Expiry: 1 week
                put("pri", 129)  // Priority: normal
                put("rr", 129)  // Read report: no
                put("d_rpt", 129)  // Delivery report: no
            }

            val mmsUri = resolver.insert(Telephony.Mms.Outbox.CONTENT_URI, mmsValues)
            if (mmsUri == null) {
                Log.e(TAG, "Failed to insert MMS to outbox")
                return false
            }

            val mmsId = ContentUris.parseId(mmsUri)
            Log.d(TAG, "MMS inserted to outbox with ID: $mmsId")

            // Insert SMIL part for better carrier compatibility
            val hasText = !messageText.isNullOrBlank()
            val smil = buildSmilForSend(hasText, mimeType)
            val smilValues = ContentValues().apply {
                put("mid", mmsId)
                put("ct", "application/smil")
                put("name", "smil.xml")
                put("cid", "<smil>")
                put("seq", 0)
            }
            val smilPartUri = resolver.insert(Uri.parse("content://mms/part"), smilValues)
            if (smilPartUri != null) {
                resolver.openOutputStream(smilPartUri)?.use { output ->
                    output.write(smil.toByteArray(Charsets.UTF_8))
                }
                Log.d(TAG, "Added SMIL part")
            }

            // Insert recipient address
            val addrValues = ContentValues().apply {
                put("address", cleanAddress)
                put("type", 151)  // TO
                put("charset", 106)  // UTF-8
            }
            resolver.insert(Uri.parse("content://mms/$mmsId/addr"), addrValues)
            Log.d(TAG, "Added recipient address")

            // Insert text part if present
            if (hasText) {
                val textValues = ContentValues().apply {
                    put("mid", mmsId)
                    put("ct", "text/plain")
                    put("charset", 106)
                    put("text", messageText)
                    put("seq", 1)
                }
                resolver.insert(Uri.parse("content://mms/part"), textValues)
                Log.d(TAG, "Added text part")
            }

            // Insert image part
            val imageSeq = if (hasText) 2 else 1
            val imageValues = ContentValues().apply {
                put("mid", mmsId)
                put("ct", mimeType)
                put("name", fileName)
                put("fn", fileName)
                put("cl", fileName)
                put("cid", "<image>")
                put("seq", imageSeq)
            }
            val partUri = resolver.insert(Uri.parse("content://mms/part"), imageValues)
            if (partUri != null) {
                resolver.openOutputStream(partUri)?.use { output ->
                    output.write(imageBytes)
                }
                Log.d(TAG, "Added image part to: $partUri")
            }

            // Now trigger the MMS service to send
            val smsManager = getDefaultSmsManager(ctx)

            // Build a full PDU for sendMultimediaMessage
            val pdu = buildMmsSendReqPdu(cleanAddress, messageText, imageBytes, mimeType)
            val pduFile = File(ctx.cacheDir, "mms_send_$mmsId.dat")
            pduFile.writeBytes(pdu)
            val pduUri = FileProvider.getUriForFile(ctx, ctx.packageName + ".provider", pduFile)

            // Grant permissions
            listOf("com.android.mms.service", "com.google.android.apps.messaging").forEach { pkg ->
                try {
                    ctx.grantUriPermission(pkg, pduUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
            }

            val sentIntent = PendingIntent.getBroadcast(
                ctx,
                mmsId.toInt(),
                Intent("MMS_SENT").setClass(ctx, com.phoneintegration.app.mms.MmsSentReceiver::class.java)
                    .putExtra("mms_id", mmsId)
                    .putExtra("thread_id", threadId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            smsManager.sendMultimediaMessage(
                ctx.applicationContext,
                pduUri,
                null,
                Bundle(),
                sentIntent
            )

            Log.d(TAG, "Triggered MMS send via SmsManager")
            true

        } catch (e: Exception) {
            Log.e(TAG, "sendMmsViaContentProvider failed: ${e.message}", e)
            false
        }
    }

    /**
     * Build minimal PDU just for triggering send (headers only)
     */
    private fun buildMinimalSendPdu(recipient: String, mmsId: Long): ByteArray {
        val out = ByteArrayOutputStream()

        // X-Mms-Message-Type: m-send-req
        out.write(0x8C); out.write(0x80)

        // X-Mms-Transaction-Id
        out.write(0x98)
        out.write("SF$mmsId".toByteArray(Charsets.US_ASCII))
        out.write(0x00)

        // X-Mms-MMS-Version: 1.3
        out.write(0x8D); out.write(0x93)

        // From: insert-address-token
        out.write(0x89); out.write(0x01); out.write(0x81)

        // To:
        out.write(0x97)
        out.write(recipient.toByteArray(Charsets.US_ASCII))
        out.write(0x00)

        // Content-Type: multipart/related (minimal)
        out.write(0x84); out.write(0xB3)

        return out.toByteArray()
    }

    /**
     * Fallback: Send MMS via PDU construction
     */
    private fun sendMmsViaPdu(
        ctx: Context,
        cleanAddress: String,
        messageText: String?,
        imageBytes: ByteArray,
        mimeType: String,
        sourceUri: Uri
    ): Boolean {
        // Build full PDU with SMIL and proper headers
        val pdu = buildMmsSendReqPdu(cleanAddress, messageText, imageBytes, mimeType)
        Log.d(TAG, "PDU size: ${pdu.size} bytes")

        // Write PDU to cache file
        val pduFile = File(ctx.cacheDir, "mms_pdu_${System.currentTimeMillis()}.dat")
        pduFile.writeBytes(pdu)

        // Get content URI for the PDU file
        val pduUri = FileProvider.getUriForFile(ctx, ctx.packageName + ".provider", pduFile)
        Log.d(TAG, "PDU URI: $pduUri")

        // Grant permissions to MMS service
        val mmsPackages = listOf(
            "com.android.mms.service",
            "com.google.android.apps.messaging",
            "com.android.providers.telephony"
        )
        mmsPackages.forEach { pkg ->
            try {
                ctx.grantUriPermission(pkg, pduUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) { /* ignore */ }
        }

        // Create pending intent for result
        val sentIntent = PendingIntent.getBroadcast(
            ctx,
            System.currentTimeMillis().toInt(),
            Intent("MMS_SENT").setClass(ctx, com.phoneintegration.app.mms.MmsSentReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Send using SmsManager
        val smsManager = getDefaultSmsManager(ctx)
        Log.d(TAG, "Calling sendMultimediaMessage...")
        smsManager.sendMultimediaMessage(
            ctx.applicationContext,
            pduUri,
            null,  // Use default MMSC
            Bundle(),
            sentIntent
        )

        // Also save to sent folder for display
        persistSentMmsToProvider(ctx, cleanAddress, messageText, sourceUri, mimeType)

        Log.d(TAG, "✓ sendMultimediaMessage called via PDU")
        return true
    }

    private fun getDefaultSmsManager(ctx: Context): SmsManager {
        val defaultSubId = SubscriptionManager.getDefaultSmsSubscriptionId()
        return if (defaultSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            SmsManager.getSmsManagerForSubscriptionId(defaultSubId)
        } else {
            ctx.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
        }
    }

    /**
     * Build minimal MMS PDU following WAP-209 spec exactly
     * Key fixes: No /TYPE=PLMN suffix, proper WSP encoding
     */
    private fun buildMinimalMmsPdu(
        recipient: String,
        text: String?,
        imageBytes: ByteArray,
        imageMimeType: String
    ): ByteArray {
        val out = ByteArrayOutputStream()

        // === MMS HEADERS ===

        // X-Mms-Message-Type: m-send-req (0x8C = header, 0x80 = m-send-req)
        out.write(0x8C)
        out.write(0x80)

        // X-Mms-Transaction-Id (0x98)
        out.write(0x98)
        val txnId = "SF${System.currentTimeMillis()}"
        out.write(txnId.toByteArray(Charsets.US_ASCII))
        out.write(0x00)

        // X-Mms-MMS-Version: 1.3 (0x8D = header, 0x93 = version 1.3)
        out.write(0x8D)
        out.write(0x93)

        // From: (0x89) - insert-address-token
        out.write(0x89)
        out.write(0x01)  // length = 1
        out.write(0x81)  // insert-address-token

        // To: (0x97) - just the phone number, NO /TYPE=PLMN suffix
        out.write(0x97)
        out.write(recipient.toByteArray(Charsets.US_ASCII))
        out.write(0x00)

        // Content-Type: application/vnd.wap.multipart.mixed (0x84 = header)
        // Use value-length + content-type approach
        out.write(0x84)
        out.write(0xA3)  // Well-known: application/vnd.wap.multipart.mixed

        // === MULTIPART BODY ===

        // Number of parts (uintvar)
        val hasText = !text.isNullOrBlank()
        val partCount = if (hasText) 2 else 1
        out.write(partCount)

        // Text part (if present)
        if (hasText) {
            writeTextPartSimple(out, text!!)
        }

        // Image part
        writeImagePartSimple(out, imageBytes, imageMimeType)

        return out.toByteArray()
    }

    /**
     * Write text part with simple headers (no content-id, just content-type)
     */
    private fun writeTextPartSimple(out: ByteArrayOutputStream, text: String) {
        val textBytes = text.toByteArray(Charsets.UTF_8)

        // Content-type: text/plain (well-known code 0x83)
        val headerLen = 1  // Just the well-known code
        writeUintVar(out, headerLen)

        // Data length
        writeUintVar(out, textBytes.size)

        // Content-Type: text/plain (well-known: 0x03 + 0x80 = 0x83)
        out.write(0x83)

        // Data
        out.write(textBytes)
    }

    /**
     * Write image part with simple headers
     */
    private fun writeImagePartSimple(out: ByteArrayOutputStream, imageBytes: ByteArray, mimeType: String) {
        // Get well-known content type code or use string
        val wellKnownCode = when {
            mimeType.contains("jpeg") || mimeType.contains("jpg") -> 0x9E  // image/jpeg
            mimeType.contains("gif") -> 0x9D  // image/gif
            mimeType.contains("png") -> null  // no well-known code, use string
            else -> null
        }

        if (wellKnownCode != null) {
            // Header length (just the code)
            writeUintVar(out, 1)
            // Data length
            writeUintVar(out, imageBytes.size)
            // Content-Type (well-known)
            out.write(wellKnownCode)
        } else {
            // Use string content type
            val ctBytes = mimeType.toByteArray(Charsets.US_ASCII)
            // Header length
            writeUintVar(out, ctBytes.size + 1)  // +1 for null terminator
            // Data length
            writeUintVar(out, imageBytes.size)
            // Content-Type (string)
            out.write(ctBytes)
            out.write(0x00)
        }

        // Data
        out.write(imageBytes)
    }

    private fun writeTextPart(out: ByteArrayOutputStream, text: String) {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val contentType = "text/plain; charset=utf-8"

        // Header length (content-type + null)
        val headerLen = contentType.length + 1
        writeUintVar(out, headerLen)

        // Data length
        writeUintVar(out, textBytes.size)

        // Content-Type header
        out.write(contentType.toByteArray())
        out.write(0x00)

        // Data
        out.write(textBytes)
    }

    private fun writeImagePart(out: ByteArrayOutputStream, imageBytes: ByteArray, mimeType: String) {
        // Header length (content-type + null)
        val headerLen = mimeType.length + 1
        writeUintVar(out, headerLen)

        // Data length
        writeUintVar(out, imageBytes.size)

        // Content-Type header
        out.write(mimeType.toByteArray())
        out.write(0x00)

        // Data
        out.write(imageBytes)
    }

    /**
     * Persist sent MMS to provider for our records
     */
    private fun persistSentMmsToProvider(
        ctx: Context,
        address: String,
        messageText: String?,
        sourceUri: Uri,
        mimeType: String
    ) {
        try {
            val resolver = ctx.contentResolver
            val threadId = Telephony.Threads.getOrCreateThreadId(ctx, setOf(address))
            val nowSec = System.currentTimeMillis() / 1000L

            // Insert into Sent folder
            val mmsValues = ContentValues().apply {
                put("thread_id", threadId)
                put("msg_box", 2)  // MESSAGE_BOX_SENT = 2
                put("date", nowSec)
                put("date_sent", nowSec)
                put("read", 1)
                put("seen", 1)
                put("m_type", 128)
                put("v", 18)
                put("ct_t", "application/vnd.wap.multipart.related")
            }

            val mmsUri = resolver.insert(Telephony.Mms.Sent.CONTENT_URI, mmsValues) ?: return
            val mmsId = ContentUris.parseId(mmsUri)

            // Insert address
            val addrValues = ContentValues().apply {
                put("address", address)
                put("type", 151)
                put("charset", 106)
            }
            resolver.insert(Uri.parse("content://mms/$mmsId/addr"), addrValues)

            // Insert text part if present
            if (!messageText.isNullOrBlank()) {
                val textValues = ContentValues().apply {
                    put("mid", mmsId)
                    put("ct", "text/plain")
                    put("charset", 106)
                    put("text", messageText)
                }
                resolver.insert(Uri.parse("content://mms/part"), textValues)
            }

            // Insert image part
            val imageExt = getExtensionFromMime(mimeType)
            val imageValues = ContentValues().apply {
                put("mid", mmsId)
                put("ct", mimeType)
                put("name", "image.$imageExt")
                put("cl", "image.$imageExt")
            }
            val partUri = resolver.insert(Uri.parse("content://mms/part"), imageValues)
            if (partUri != null) {
                resolver.openOutputStream(partUri)?.use { output ->
                    resolver.openInputStream(sourceUri)?.use { input ->
                        input.copyTo(output)
                    }
                }
            }

            Log.d(TAG, "Persisted sent MMS to provider: $mmsId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist MMS: ${e.message}")
        }
    }

    /**
     * Fallback: Send MMS via Intent (opens default MMS app)
     */
    private fun sendMmsViaIntent(
        ctx: Context,
        address: String,
        imageUri: Uri,
        messageText: String?
    ): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                putExtra("address", address)
                putExtra(Intent.EXTRA_STREAM, imageUri)
                putExtra("sms_body", messageText ?: "")
                type = ctx.contentResolver.getType(imageUri) ?: "image/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(Intent.createChooser(intent, "Send MMS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            Log.d(TAG, "Opened MMS intent chooser")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send via intent: ${e.message}")
            false
        }
    }

    /**
     * Build SMIL for sending
     */
    private fun buildSmilForSend(hasText: Boolean, imageMimeType: String): String {
        val imageExt = getExtensionFromMime(imageMimeType)
        return if (hasText) {
            """<smil><head><layout><root-layout width="100%" height="100%"/><region id="Text" top="0" left="0" height="20%" width="100%" fit="scroll"/><region id="Image" top="20%" left="0" height="80%" width="100%" fit="meet"/></layout></head><body><par dur="10s"><text src="text.txt" region="Text"/><img src="image.$imageExt" region="Image"/></par></body></smil>"""
        } else {
            """<smil><head><layout><root-layout width="100%" height="100%"/><region id="Image" top="0" left="0" height="100%" width="100%" fit="meet"/></layout></head><body><par dur="10s"><img src="image.$imageExt" region="Image"/></par></body></smil>"""
        }
    }

    /**
     * Build a simpler MMS PDU that follows carrier standards more closely
     */
    private fun buildSimpleMmsPdu(
        recipient: String,
        text: String?,
        imageUri: Uri,
        imageMimeType: String,
        ctx: Context
    ): ByteArray {
        val pdu = ByteArrayOutputStream()
        val cleanRecipient = recipient.replace(Regex("[^0-9+]"), "")

        // Read image bytes
        val imageBytes = ctx.contentResolver.openInputStream(imageUri)?.use { it.readBytes() } ?: ByteArray(0)

        // === MMS Headers ===
        // X-Mms-Message-Type: m-send-req
        pdu.write(0x8C); pdu.write(0x80)

        // X-Mms-Transaction-Id
        pdu.write(0x98)
        val txnId = "T${System.currentTimeMillis()}"
        pdu.write(txnId.toByteArray(Charsets.US_ASCII))
        pdu.write(0x00)

        // X-Mms-MMS-Version: 1.0
        pdu.write(0x8D); pdu.write(0x90)

        // From: insert-address-token
        pdu.write(0x89)
        pdu.write(1)  // length
        pdu.write(0x81)  // insert-address-token

        // To
        pdu.write(0x97)
        pdu.write(cleanRecipient.toByteArray(Charsets.US_ASCII))
        pdu.write(0x00)

        // Content-Type: multipart/mixed (simpler than multipart/related)
        pdu.write(0x84)
        pdu.write(0xA3)  // application/vnd.wap.multipart.mixed

        // === Body: Number of parts ===
        val hasText = !text.isNullOrBlank()
        pdu.write(if (hasText) 2 else 1)

        // Part 1: Text (if present)
        if (hasText) {
            val textBytes = text!!.toByteArray(Charsets.UTF_8)
            // Headers length
            pdu.write(18)  // "text/plain" + null = 11, plus content-id
            // Data length
            writeUintVar(pdu, textBytes.size)
            // Content-Type
            pdu.write("text/plain".toByteArray(Charsets.US_ASCII))
            pdu.write(0x00)
            // Content-ID
            pdu.write(0xC0)
            pdu.write("<text>".toByteArray(Charsets.US_ASCII))
            pdu.write(0x00)
            // Data
            pdu.write(textBytes)
        }

        // Part 2: Image
        val imageExt = getExtensionFromMime(imageMimeType)
        val contentTypeBytes = imageMimeType.toByteArray(Charsets.US_ASCII)
        val contentIdBytes = "<image>".toByteArray(Charsets.US_ASCII)
        val headerLen = contentTypeBytes.size + 1 + 1 + contentIdBytes.size + 1

        // Headers length
        writeUintVar(pdu, headerLen)
        // Data length
        writeUintVar(pdu, imageBytes.size)
        // Content-Type
        pdu.write(contentTypeBytes)
        pdu.write(0x00)
        // Content-ID
        pdu.write(0xC0)
        pdu.write(contentIdBytes)
        pdu.write(0x00)
        // Data
        pdu.write(imageBytes)

        return pdu.toByteArray()
    }

    /**
     * Build a proper MMS M-Send.req PDU according to WAP-209 specification
     */
    private fun buildMmsSendReqPdu(
        recipient: String,
        text: String?,
        imageBytes: ByteArray,
        imageMimeType: String
    ): ByteArray {
        val pdu = ByteArrayOutputStream()

        // Clean up recipient number
        val cleanRecipient = recipient.replace(Regex("[^0-9+]"), "")

        // ========== MMS Headers ==========

        // X-Mms-Message-Type: m-send-req (0x80)
        pdu.write(0x8C)  // header field
        pdu.write(0x80)  // m-send-req value

        // X-Mms-Transaction-Id
        pdu.write(0x98)  // header field
        val transactionId = "T${System.currentTimeMillis()}"
        pdu.write(transactionId.toByteArray(Charsets.US_ASCII))
        pdu.write(0x00)  // null terminator

        // X-Mms-MMS-Version: 1.2 (0x92)
        pdu.write(0x8D)  // header field
        pdu.write(0x92)  // version 1.2

        // To: just the phone number (no /TYPE=PLMN suffix)
        pdu.write(0x97)  // header field
        pdu.write(cleanRecipient.toByteArray(Charsets.US_ASCII))
        pdu.write(0x00)  // null terminator

        // Content-Type: application/vnd.wap.multipart.related
        // This is complex - we need to specify start and type parameters
        pdu.write(0x84)  // Content-Type header

        // Build content-type with parameters
        val contentTypeBytes = buildContentTypeHeader(imageMimeType)
        pdu.write(contentTypeBytes)

        // ========== Multipart Body ==========
        // Number of parts
        val hasText = !text.isNullOrBlank()
        val numParts = if (hasText) 3 else 2  // SMIL + text (optional) + image
        writeUintVar(pdu, numParts)

        // Part 0: SMIL (required for proper MMS)
        val smil = buildSmil(hasText, imageMimeType)
        writePart(pdu, "application/smil", "smil.xml", smil.toByteArray(Charsets.UTF_8))

        // Part 1: Text (if present)
        if (hasText) {
            writePart(pdu, "text/plain; charset=utf-8", "text.txt", text!!.toByteArray(Charsets.UTF_8))
        }

        // Part 2: Image
        val imageExt = getExtensionFromMime(imageMimeType)
        writePart(pdu, imageMimeType, "image.$imageExt", imageBytes)

        return pdu.toByteArray()
    }

    /**
     * Build Content-Type header with parameters for multipart/related
     */
    private fun buildContentTypeHeader(imageMimeType: String): ByteArray {
        val out = ByteArrayOutputStream()

        // Value length (will be filled in)
        val valueBytes = ByteArrayOutputStream()

        // Content-Type: application/vnd.wap.multipart.related (0x33 + 0x80 = 0xB3)
        valueBytes.write(0xB3)

        // Parameter: type (0x09)
        valueBytes.write(0x89)  // type parameter (well-known, short form)
        // Start content type - application/smil
        valueBytes.write(0x84)  // application/smil, short form: 0x04 + 0x80

        // Parameter: start (0x0A)
        valueBytes.write(0x8A)  // start parameter
        val startValue = "<smil>"
        valueBytes.write(startValue.toByteArray(Charsets.US_ASCII))
        valueBytes.write(0x00)

        // Write length + value
        val valueArray = valueBytes.toByteArray()
        writeValueLength(out, valueArray.size)
        out.write(valueArray)

        return out.toByteArray()
    }

    /**
     * Build SMIL presentation for MMS
     */
    private fun buildSmil(hasText: Boolean, imageMimeType: String): String {
        val imageExt = getExtensionFromMime(imageMimeType)
        return if (hasText) {
            """<smil><head><layout><root-layout/><region id="Text" top="0" left="0" height="30%" width="100%"/><region id="Image" top="30%" left="0" height="70%" width="100%"/></layout></head><body><par dur="5000ms"><text src="text.txt" region="Text"/><img src="image.$imageExt" region="Image"/></par></body></smil>"""
        } else {
            """<smil><head><layout><root-layout/><region id="Image" top="0" left="0" height="100%" width="100%"/></layout></head><body><par dur="5000ms"><img src="image.$imageExt" region="Image"/></par></body></smil>"""
        }
    }

    /**
     * Write a single part to the PDU
     */
    private fun writePart(pdu: ByteArrayOutputStream, contentType: String, name: String, data: ByteArray) {
        // Build headers
        val headers = ByteArrayOutputStream()

        // Content-Type
        headers.write(contentType.toByteArray(Charsets.US_ASCII))
        headers.write(0x00)

        // Content-Location (0x8E)
        headers.write(0x8E)
        headers.write(name.toByteArray(Charsets.US_ASCII))
        headers.write(0x00)

        // Content-ID (0xC0)
        headers.write(0xC0)
        val contentId = "<${name.substringBefore('.')}>"
        headers.write(contentId.toByteArray(Charsets.US_ASCII))
        headers.write(0x00)

        val headerBytes = headers.toByteArray()

        // Write headers length
        writeUintVar(pdu, headerBytes.size)

        // Write data length
        writeUintVar(pdu, data.size)

        // Write headers
        pdu.write(headerBytes)

        // Write data
        pdu.write(data)
    }

    /**
     * Write value length (WSP encoding)
     */
    private fun writeValueLength(out: ByteArrayOutputStream, length: Int) {
        if (length < 31) {
            out.write(length)
        } else {
            out.write(31)  // Length quote
            writeUintVar(out, length)
        }
    }

    /**
     * Write variable length unsigned integer (uintvar)
     */
    private fun writeUintVar(out: ByteArrayOutputStream, value: Int) {
        if (value < 0x80) {
            out.write(value)
        } else {
            val bytes = mutableListOf<Int>()
            var remaining = value
            while (remaining > 0) {
                bytes.add(0, remaining and 0x7F)
                remaining = remaining shr 7
            }
            for (i in 0 until bytes.size - 1) {
                out.write(bytes[i] or 0x80)
            }
            out.write(bytes.last())
        }
    }

    private fun getExtensionFromMime(mimeType: String): String {
        return when {
            mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
            mimeType.contains("png") -> "png"
            mimeType.contains("gif") -> "gif"
            mimeType.contains("webp") -> "webp"
            else -> "jpg"
        }
    }

    private data class MmsImagePayload(
        val bytes: ByteArray,
        val mimeType: String,
        val fileName: String
    )

    private fun prepareMmsImagePayload(
        ctx: Context,
        imageBytes: ByteArray,
        originalMimeType: String,
        sourceUri: Uri
    ): MmsImagePayload {
        val smsManager = getDefaultSmsManager(ctx)
        val config = smsManager.getCarrierConfigValues()
        val maxBytes = config?.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE)
            ?.takeIf { it > 0 } ?: 2500 * 1024
        val configuredMaxWidth = config?.getInt(SmsManager.MMS_CONFIG_MAX_IMAGE_WIDTH)?.takeIf { it > 0 }
        val configuredMaxHeight = config?.getInt(SmsManager.MMS_CONFIG_MAX_IMAGE_HEIGHT)?.takeIf { it > 0 }
        Log.d(
            TAG,
            "Carrier MMS limits: maxBytes=$maxBytes maxWidth=${configuredMaxWidth ?: "n/a"} maxHeight=${configuredMaxHeight ?: "n/a"}"
        )

        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, boundsOptions)
        val exifOrientation = readExifOrientation(ctx, sourceUri)
        val swapDimensions = exifOrientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            exifOrientation == ExifInterface.ORIENTATION_ROTATE_270 ||
            exifOrientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            exifOrientation == ExifInterface.ORIENTATION_TRANSVERSE
        val sourceWidth = if (swapDimensions) boundsOptions.outHeight else boundsOptions.outWidth
        val sourceHeight = if (swapDimensions) boundsOptions.outWidth else boundsOptions.outHeight
        val maxWidth = configuredMaxWidth ?: boundsOptions.outWidth.coerceAtLeast(1)
        val maxHeight = configuredMaxHeight ?: boundsOptions.outHeight.coerceAtLeast(1)

        val needsResize =
            configuredMaxWidth != null && sourceWidth > maxWidth ||
                configuredMaxHeight != null && sourceHeight > maxHeight
        val needsRotate = exifOrientation != ExifInterface.ORIENTATION_NORMAL &&
            exifOrientation != ExifInterface.ORIENTATION_UNDEFINED
        val needsReencode =
            !originalMimeType.contains("jpeg", ignoreCase = true) || imageBytes.size > maxBytes || needsRotate

        if (!needsResize && !needsReencode) {
            val ext = getExtensionFromMime(originalMimeType)
            return MmsImagePayload(imageBytes, originalMimeType, "image.$ext")
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            val targetWidth = if (needsResize) maxWidth else sourceWidth
            val targetHeight = if (needsResize) maxHeight else sourceHeight
            inSampleSize = calculateInSampleSize(boundsOptions, targetWidth, targetHeight)
        }
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, decodeOptions)
            ?: return MmsImagePayload(imageBytes, originalMimeType, "image.${getExtensionFromMime(originalMimeType)}")

        val oriented = applyExifOrientation(bitmap, exifOrientation)
        if (oriented != bitmap) {
            bitmap.recycle()
        }

        val scaled = if (needsResize) {
            scaleToFit(oriented, maxWidth, maxHeight)
        } else {
            oriented
        }
        val compressed = compressBitmapToSize(scaled, maxBytes)
        Log.d(
            TAG,
            "Prepared MMS image result: ${compressed.size} bytes (${scaled.width}x${scaled.height})"
        )
        if (scaled != oriented) {
            scaled.recycle()
        }

        return MmsImagePayload(compressed, "image/jpeg", "image.jpg")
    }

    private fun calculateInSampleSize(
        bounds: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = bounds.outHeight
        val width = bounds.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun scaleToFit(
        bitmap: android.graphics.Bitmap,
        maxWidth: Int,
        maxHeight: Int
    ): android.graphics.Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxWidth && height <= maxHeight) return bitmap

        val ratio = minOf(maxWidth.toFloat() / width, maxHeight.toFloat() / height)
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()
        return android.graphics.Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun readExifOrientation(ctx: Context, uri: Uri): Int {
        return try {
            val exif = when (uri.scheme) {
                "file" -> uri.path?.let { ExifInterface(it) }
                else -> ctx.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
            }
            exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun applyExifOrientation(
        bitmap: android.graphics.Bitmap,
        orientation: Int
    ): android.graphics.Bitmap {
        if (orientation == ExifInterface.ORIENTATION_NORMAL ||
            orientation == ExifInterface.ORIENTATION_UNDEFINED) {
            return bitmap
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postScale(-1f, 1f)
                matrix.postRotate(90f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postScale(-1f, 1f)
                matrix.postRotate(270f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bitmap
        }

        return try {
            android.graphics.Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                matrix,
                true
            )
        } catch (_: Exception) {
            bitmap
        }
    }

    private fun compressBitmapToSize(
        bitmap: android.graphics.Bitmap,
        maxBytes: Int
    ): ByteArray {
        var working = bitmap
        var attempt = 0

        while (attempt < 8) {
            val best = compressToLimit(working, maxBytes, 90, 100)
            if (best != null) {
                if (working != bitmap) {
                    working.recycle()
                }
                return best
            }

            if (working.width <= 720 && working.height <= 720) {
                break
            }

            val nextWidth = (working.width * 0.96f).toInt().coerceAtLeast(1)
            val nextHeight = (working.height * 0.96f).toInt().coerceAtLeast(1)
            val scaled = android.graphics.Bitmap.createScaledBitmap(working, nextWidth, nextHeight, true)
            if (working != bitmap) {
                working.recycle()
            }
            working = scaled
            attempt++
        }

        val fallback = compressJpeg(working, 90)
        if (working != bitmap) {
            working.recycle()
        }
        return fallback
    }

    private fun compressToLimit(
        bitmap: android.graphics.Bitmap,
        maxBytes: Int,
        minQuality: Int,
        maxQuality: Int
    ): ByteArray? {
        val initial = compressJpeg(bitmap, maxQuality)
        if (initial.size <= maxBytes) {
            return initial
        }

        var low = minQuality
        var high = maxQuality
        var best: ByteArray? = null

        while (low <= high) {
            val mid = (low + high) / 2
            val attempt = compressJpeg(bitmap, mid)
            if (attempt.size <= maxBytes) {
                best = attempt
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        return best
    }

    private fun compressJpeg(bitmap: android.graphics.Bitmap, quality: Int): ByteArray {
        val output = ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, output)
        return output.toByteArray()
    }

    fun persistSentMmsIfMissing(
        ctx: Context,
        address: String,
        messageText: String?,
        attachmentUri: Uri?
    ): Long? {
        return try {
            val resolver = ctx.contentResolver
            val nowSec = System.currentTimeMillis() / 1000L
            val threadId = Telephony.Threads.getOrCreateThreadId(ctx, setOf(address))
            val recentId = findRecentSentMmsId(resolver, threadId, nowSec)

            if (recentId != null) {
                if (!messageText.isNullOrBlank() && !hasTextPart(resolver, recentId)) {
                    insertTextPart(resolver, recentId, messageText)
                }
                if (attachmentUri != null && !hasAttachmentPart(resolver, recentId)) {
                    insertAttachmentPart(resolver, recentId, attachmentUri)
                }
                return recentId
            }

            val values = ContentValues().apply {
                put("thread_id", threadId)
                put("msg_box", 2) // MESSAGE_BOX_SENT
                put("date", nowSec)
                put("date_sent", nowSec)
                put("read", 1)
                put("seen", 1)
                put("m_type", 128) // MESSAGE_TYPE_SEND_REQ
                put("v", 18) // MMS 1.2+
                put("ct_t", "application/vnd.wap.multipart.related")
            }

            val mmsUri = resolver.insert(Telephony.Mms.Sent.CONTENT_URI, values) ?: return null
            val mmsId = ContentUris.parseId(mmsUri)

            insertAddress(resolver, mmsId, address)

            if (!messageText.isNullOrBlank()) {
                insertTextPart(resolver, mmsId, messageText)
            }
            if (attachmentUri != null) {
                insertAttachmentPart(resolver, mmsId, attachmentUri)
            }

            mmsId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist sent MMS", e)
            null
        }
    }

    private fun findRecentSentMmsId(
        resolver: android.content.ContentResolver,
        threadId: Long,
        nowSec: Long
    ): Long? {
        val cutoff = nowSec - 90
        val cursor = resolver.query(
            Uri.parse("content://mms"),
            arrayOf("_id", "date"),
            "thread_id = ? AND msg_box = 2 AND date >= ?",
            arrayOf(threadId.toString(), cutoff.toString()),
            "date DESC"
        ) ?: return null

        cursor.use {
            if (it.moveToFirst()) {
                return it.getLong(0)
            }
        }

        return null
    }

    private fun insertAddress(
        resolver: android.content.ContentResolver,
        mmsId: Long,
        address: String
    ) {
        val addrValues = ContentValues().apply {
            put("address", address)
            put("type", 151) // TO
            put("charset", 106)
        }

        resolver.insert(Uri.parse("content://mms/$mmsId/addr"), addrValues)
    }

    private fun insertTextPart(
        resolver: android.content.ContentResolver,
        mmsId: Long,
        messageText: String
    ) {
        val partValues = ContentValues().apply {
            put("mid", mmsId)
            put("ct", "text/plain")
            put("charset", 106)
            put("text", messageText)
        }

        resolver.insert(Uri.parse("content://mms/part"), partValues)
    }

    private fun insertAttachmentPart(
        resolver: android.content.ContentResolver,
        mmsId: Long,
        attachmentUri: Uri
    ) {
        val mimeType = resolver.getType(attachmentUri)
            ?: guessMimeTypeFromUri(resolver, attachmentUri)
            ?: "application/octet-stream"
        val fileName = getDisplayName(resolver, attachmentUri)

        val partValues = ContentValues().apply {
            put("mid", mmsId)
            put("ct", mimeType)
            if (!fileName.isNullOrBlank()) {
                put("name", fileName)
                put("fn", fileName)
                put("cl", fileName)
            }
            put("cid", "<${System.currentTimeMillis()}>")
        }

        val partUri = resolver.insert(Uri.parse("content://mms/part"), partValues) ?: return

        resolver.openOutputStream(partUri)?.use { output ->
            val inputStream = try {
                when (attachmentUri.scheme) {
                    "file" -> FileInputStream(attachmentUri.path)
                    else -> resolver.openInputStream(attachmentUri)
                }
            } catch (e: Exception) {
                null
            }

            inputStream?.use { input ->
                input.copyTo(output)
            }
        }
    }

    private fun hasTextPart(
        resolver: android.content.ContentResolver,
        mmsId: Long
    ): Boolean {
        val cursor = resolver.query(
            Uri.parse("content://mms/part"),
            arrayOf("_id"),
            "mid = ? AND ct = 'text/plain'",
            arrayOf(mmsId.toString()),
            null
        ) ?: return false

        cursor.use {
            return it.moveToFirst()
        }
    }

    private fun hasAttachmentPart(
        resolver: android.content.ContentResolver,
        mmsId: Long
    ): Boolean {
        val cursor = resolver.query(
            Uri.parse("content://mms/part"),
            arrayOf("_id", "ct"),
            "mid = ?",
            arrayOf(mmsId.toString()),
            null
        ) ?: return false

        cursor.use {
            while (it.moveToNext()) {
                val contentType = it.getString(1) ?: ""
                if (contentType != "text/plain" && contentType != "application/smil") {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Carrier requires file to be local: /data/data/<package>/cache/...
     */
    private fun copyUriToCache(ctx: Context, uri: Uri): File {
        val resolver = ctx.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                } else {
                    null
                }
            }

        val safeName = displayName
            ?.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            ?.takeIf { it.isNotBlank() }

        val fallbackName = run {
            val extension = MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(resolver.getType(uri))
            if (!extension.isNullOrBlank()) {
                "mms_${System.currentTimeMillis()}.$extension"
            } else {
                "mms_${System.currentTimeMillis()}"
            }
        }

        val fileName = safeName ?: fallbackName
        var file = File(ctx.cacheDir, fileName)
        if (file.exists()) {
            file = File(ctx.cacheDir, "${System.currentTimeMillis()}_$fileName")
        }

        ctx.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }

        return file
    }

    private fun guessMimeTypeFromUri(
        resolver: android.content.ContentResolver,
        uri: Uri
    ): String? {
        val name = getDisplayName(resolver, uri) ?: return null
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension.isBlank()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }

    private fun getDisplayName(
        resolver: android.content.ContentResolver,
        uri: Uri
    ): String? {
        val cursor = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                return it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            }
        }
        return uri.lastPathSegment
    }

    /**
     * ----- MMS Reading (unchanged) -----
     */
    fun getMmsAddresses(contentResolver: android.content.ContentResolver, mmsIds: List<Long>): Map<Long, String> {
        if (mmsIds.isEmpty()) return emptyMap()

        val addresses = mutableMapOf<Long, String>()
        val uri = Uri.parse("content://mms/addr")
        val selection = "${Telephony.Mms.Addr.MSG_ID} IN (${mmsIds.joinToString(",")}) AND ${Telephony.Mms.Addr.TYPE} IN (137, 151)"
        val cursor = contentResolver.query(
            uri,
            arrayOf(Telephony.Mms.Addr.MSG_ID, Telephony.Mms.Addr.ADDRESS),
            selection, null, null
        )

        cursor?.use {
            while (it.moveToNext()) {
                val msgId = it.getLong(it.getColumnIndexOrThrow(Telephony.Mms.Addr.MSG_ID))
                val address = it.getString(it.getColumnIndexOrThrow(Telephony.Mms.Addr.ADDRESS))
                if (!address.isNullOrBlank() && !addresses.containsKey(msgId)) {
                    addresses[msgId] = address
                }
            }
        }
        return addresses
    }

    fun getMmsTexts(contentResolver: android.content.ContentResolver, mmsIds: List<Long>): Map<Long, String> {
        if (mmsIds.isEmpty()) return emptyMap()

        val texts = mutableMapOf<Long, String>()
        val uri = Uri.parse("content://mms/part")
        val selection = "${Telephony.Mms.Part.MSG_ID} IN (${mmsIds.joinToString(",")}) AND ${Telephony.Mms.Part.CONTENT_TYPE} = 'text/plain'"
        val cursor = contentResolver.query(
            uri,
            arrayOf(Telephony.Mms.Part.MSG_ID, Telephony.Mms.Part.TEXT),
            selection,
            null,
            null
        )

        cursor?.use {
            while (it.moveToNext()) {
                val msgId = it.getLong(it.getColumnIndexOrThrow(Telephony.Mms.Part.MSG_ID))
                val text = it.getString(it.getColumnIndexOrThrow(Telephony.Mms.Part.TEXT))
                if (!text.isNullOrBlank() && !texts.containsKey(msgId)) {
                    texts[msgId] = text.trim()
                }
            }
        }
        return texts
    }

    fun getMmsAddress(contentResolver: android.content.ContentResolver, mmsId: Long): String? {
        val uri = Uri.parse("content://mms/$mmsId/addr")
        val cursor = contentResolver.query(
            uri,
            arrayOf(Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE),
            null, null, null
        )

        cursor?.use {
            while (it.moveToNext()) {
                val address = it.getString(it.getColumnIndexOrThrow(Telephony.Mms.Addr.ADDRESS))
                val type = it.getInt(it.getColumnIndexOrThrow(Telephony.Mms.Addr.TYPE))
                Log.d("MmsHelper", "MMS $mmsId address entry: address='$address', type=$type")
                // Type 137 = FROM, Type 151 = TO
                if (!address.isNullOrBlank() && (type == 137 || type == 151)) {
                    Log.d("MmsHelper", "MMS $mmsId using address: '$address' (type: $type)")
                    return address
                }
            }
        }
        return null
    }

    /**
     * Get ALL recipients for an MMS (for group conversations)
     * Returns list of addresses (excluding own number)
     */
    fun getMmsAllRecipients(contentResolver: android.content.ContentResolver, mmsId: Long): List<String> {
        val uri = Uri.parse("content://mms/$mmsId/addr")
        val cursor = contentResolver.query(
            uri,
            arrayOf(Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE),
            null, null, null
        )

        val recipients = mutableListOf<String>()
        cursor?.use {
            while (it.moveToNext()) {
                val address = it.getString(it.getColumnIndexOrThrow(Telephony.Mms.Addr.ADDRESS))
                val type = it.getInt(it.getColumnIndexOrThrow(Telephony.Mms.Addr.TYPE))
                // Type 137 = FROM, Type 151 = TO
                if (!address.isNullOrBlank() && (type == 137 || type == 151)) {
                    // Filter out insert-address-token (self)
                    if (!address.contains("insert-address-token", ignoreCase = true)) {
                        recipients.add(address)
                    }
                }
            }
        }
        return recipients
    }

    /**
     * Get the sender (FROM) address of an MMS message.
     * Returns null if no sender found.
     */
    fun getMmsSender(contentResolver: android.content.ContentResolver, mmsId: Long): String? {
        val uri = Uri.parse("content://mms/$mmsId/addr")
        val cursor = contentResolver.query(
            uri,
            arrayOf(Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE),
            null, null, null
        )

        cursor?.use {
            while (it.moveToNext()) {
                val address = it.getString(it.getColumnIndexOrThrow(Telephony.Mms.Addr.ADDRESS))
                val type = it.getInt(it.getColumnIndexOrThrow(Telephony.Mms.Addr.TYPE))
                // Type 137 = FROM (sender)
                if (!address.isNullOrBlank() && type == 137) {
                    if (!address.contains("insert-address-token", ignoreCase = true)) {
                        return address
                    }
                }
            }
        }
        return null
    }

    fun getMmsText(contentResolver: android.content.ContentResolver, mmsId: Long): String? {
        val uri = Uri.parse("content://mms/part")
        val cursor = contentResolver.query(
            uri,
            arrayOf(
                Telephony.Mms.Part._ID,
                Telephony.Mms.Part.CONTENT_TYPE,
                Telephony.Mms.Part.TEXT,
                Telephony.Mms.Part._DATA,
                Telephony.Mms.Part.CHARSET
            ),
            "${Telephony.Mms.Part.MSG_ID} = ?",
            arrayOf(mmsId.toString()),
            null
        )

        val textParts = mutableListOf<String>()
        cursor?.use {
            var partCount = 0
            while (it.moveToNext()) {
                val contentType = it.getString(it.getColumnIndexOrThrow(Telephony.Mms.Part.CONTENT_TYPE))
                if (contentType == "text/plain") {
                    val partId = it.getLong(it.getColumnIndexOrThrow(Telephony.Mms.Part._ID))
                    val text = it.getString(it.getColumnIndexOrThrow(Telephony.Mms.Part.TEXT))
                    val data = it.getString(it.getColumnIndexOrThrow(Telephony.Mms.Part._DATA))
                    val charset = it.getInt(it.getColumnIndexOrThrow(Telephony.Mms.Part.CHARSET))

                    val decodedText = when {
                        !text.isNullOrBlank() -> decodeEncodedWords(text.trim(), charset)
                        !data.isNullOrBlank() -> readTextPart(contentResolver, partId, charset)
                        else -> null
                    }

                    if (!decodedText.isNullOrBlank()) {
                        partCount++
                        android.util.Log.d("MmsHelper", "MMS $mmsId part $partCount: ${decodedText.take(50)}")
                        textParts.add(decodedText.trim())
                    }
                }
            }
            android.util.Log.d("MmsHelper", "MMS $mmsId: Found $partCount text/plain parts")
        }

        // Take only the FIRST text part to avoid duplicates
        val result = textParts.firstOrNull()
        android.util.Log.d("MmsHelper", "MMS $mmsId: Returning text length=${result?.length}")
        return result
    }

    fun decodeMmsSubject(subjectBytes: ByteArray?, subjectFallback: String?, charset: Int?): String? {
        if (subjectBytes != null && subjectBytes.isNotEmpty()) {
            return decodeBytes(subjectBytes, charset)
        }

        if (!subjectFallback.isNullOrBlank()) {
            return decodeEncodedWords(subjectFallback.trim(), charset)
        }

        return null
    }

    private fun readTextPart(
        resolver: android.content.ContentResolver,
        partId: Long,
        charset: Int?
    ): String? {
        val partUri = Uri.parse("content://mms/part/$partId")
        val bytes = resolver.openInputStream(partUri)?.use { it.readBytes() } ?: return null
        return decodeBytes(bytes, charset)
    }

    private fun decodeBytes(bytes: ByteArray, charset: Int?): String {
        val targetCharset = charsetForMms(charset)
        return try {
            String(bytes, targetCharset)
        } catch (e: Exception) {
            String(bytes, Charsets.UTF_8)
        }
    }

    private fun charsetForMms(charset: Int?): Charset {
        return when (charset) {
            3 -> Charsets.US_ASCII
            4 -> Charset.forName("ISO-8859-1")
            8 -> Charset.forName("ISO-8859-2")
            33 -> Charsets.UTF_16
            34 -> Charset.forName("UTF-16BE")
            35 -> Charset.forName("UTF-16LE")
            106 -> Charsets.UTF_8
            else -> Charsets.UTF_8
        }
    }

    private fun decodeEncodedWords(value: String, defaultCharset: Int?): String {
        val pattern = Regex("=\\?([^?]+)\\?([bBqQ])\\?([^?]+)\\?=")
        var result = value
        pattern.findAll(value).forEach { match ->
            val charsetName = match.groupValues[1]
            val encoding = match.groupValues[2]
            val encodedText = match.groupValues[3]
            val decodedBytes = when (encoding.uppercase()) {
                "B" -> Base64.decode(encodedText, Base64.DEFAULT)
                "Q" -> decodeQuotedPrintable(encodedText)
                else -> null
            }
            if (decodedBytes != null) {
                val charset = runCatching { Charset.forName(charsetName) }
                    .getOrElse { charsetForMms(defaultCharset) }
                val decoded = try {
                    String(decodedBytes, charset)
                } catch (e: Exception) {
                    String(decodedBytes, Charsets.UTF_8)
                }
                result = result.replace(match.value, decoded)
            }
        }
        return result
    }

    private fun decodeQuotedPrintable(value: String): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            when {
                ch == '_' -> {
                    out.write(' '.code)
                    i++
                }
                ch == '=' && i + 2 < value.length -> {
                    val hex = value.substring(i + 1, i + 3)
                    val byte = hex.toIntOrNull(16)
                    if (byte != null) {
                        out.write(byte)
                        i += 3
                    } else {
                        out.write(ch.code)
                        i++
                    }
                }
                else -> {
                    out.write(ch.code)
                    i++
                }
            }
        }
        return out.toByteArray()
    }
}
