package com.phoneintegration.app.desktop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import android.util.Base64
import android.util.Log
import com.google.firebase.database.ServerValue
import com.phoneintegration.app.utils.PhoneNumberNormalizer
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Handles syncing contacts to Firebase for desktop access
 */
class ContactsSyncService(private val context: Context) {

    private val syncService = DesktopSyncService(context)

    companion object {
        private const val TAG = "ContactsSyncService"
    }

    /**
     * Data class for contact information
     */
    data class Contact(
        val id: String,
        val displayName: String,
        val phoneNumber: String,
        val normalizedNumber: String,
        val phoneType: String?,
        val photoUri: String? = null,
        val photoBase64: String? = null,
        val email: String? = null,
        val notes: String? = null
    )

    /**
     * Get the user's own phone numbers from SIM cards and profile to exclude from contact sync
     */
    private fun getUserOwnPhoneNumbers(): Set<String> {
        val userNumbers = mutableSetOf<String>()

        // Get numbers from SIM cards
        try {
            val simManager = com.phoneintegration.app.SimManager(context)
            simManager.getActiveSims().forEach { sim ->
                sim.phoneNumber?.let { phone ->
                    val normalized = phone.replace(Regex("[^0-9]"), "")
                    if (normalized.length >= 10) {
                        userNumbers.add(normalized.takeLast(10))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get SIM phone numbers: ${e.message}")
        }

        // Also get numbers from the user's profile contact (the "Me" contact)
        try {
            val profileUri = ContactsContract.Profile.CONTENT_URI
            context.contentResolver.query(
                Uri.withAppendedPath(profileUri, ContactsContract.Contacts.Data.CONTENT_DIRECTORY),
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE),
                null
            )?.use { cursor ->
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val number = cursor.getString(numberIndex)
                    if (!number.isNullOrEmpty()) {
                        val normalized = number.replace(Regex("[^0-9]"), "")
                        if (normalized.length >= 10) {
                            userNumbers.add(normalized.takeLast(10))
                            Log.d(TAG, "Found user profile number: $number")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get profile phone numbers: ${e.message}")
        }

        Log.d(TAG, "User's own phone numbers (for filtering): $userNumbers")
        return userNumbers
    }

    /**
     * Get all contacts with phone numbers
     */
    suspend fun getAllContacts(): List<Contact> = withContext(Dispatchers.IO) {
        val contacts = mutableListOf<Contact>()
        val seenNumbers = mutableSetOf<String>()

        // Get user's own phone numbers to exclude
        val userOwnNumbers = getUserOwnPhoneNumbers()

        // Permission guard
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_CONTACTS not granted; skipping contact sync")
            return@withContext emptyList()
        }

        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.PHOTO_URI
        )

        try {
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val normalizedIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER)
                val typeIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                val photoIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)

                while (cursor.moveToNext()) {
                    val contactId = cursor.getString(idIndex) ?: continue
                    val name = cursor.getString(nameIndex) ?: "Unknown"
                    val number = cursor.getString(numberIndex) ?: continue
                    val normalized = cursor.getString(normalizedIndex) ?: number
                    val type = cursor.getInt(typeIndex)
                    val photoUri = cursor.getString(photoIndex)

                    // Skip duplicates based on normalized number
                    val uniqueKey = "$contactId:$normalized"
                    if (seenNumbers.contains(uniqueKey)) continue
                    seenNumbers.add(uniqueKey)

                    // Skip contacts that have the user's own phone number
                    // This prevents the user's name from being associated with their own numbers
                    val normalizedDigits = normalized.replace(Regex("[^0-9]"), "")
                    if (normalizedDigits.length >= 10 && userOwnNumbers.contains(normalizedDigits.takeLast(10))) {
                        Log.d(TAG, "Skipping user's own number in contacts: $number (contact: $name)")
                        continue
                    }

                    val phoneType = getPhoneTypeLabel(type)

                    // Get contact photo as Base64 (small thumbnail for sync)
                    val photoBase64 = photoUri?.let { getContactPhotoBase64(it) }

                    val email = getContactEmail(contactId)
                    val notes = getContactNotes(contactId)
                    contacts.add(
                        Contact(
                            id = contactId,
                            displayName = name,
                            phoneNumber = number,
                            normalizedNumber = normalized,
                            phoneType = phoneType,
                            photoUri = photoUri,
                            photoBase64 = photoBase64,
                            email = email,
                            notes = notes
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading contacts", e)
        }

        Log.d(TAG, "Retrieved ${contacts.size} contacts")
        return@withContext contacts
    }

    /**
     * Sync all contacts to Firebase using the universal contact schema.
     * Ensures desktop-originated contacts are preserved while Android data takes priority.
     */
    suspend fun syncContacts() {
        val userId = syncService.getCurrentUserId()
        syncContactsForUser(userId)
    }

    /**
     * Sync all contacts for a specific user ID via Cloud Function
     * Uses Cloud Function to avoid OOM from Firebase WebSocket sync
     */
    suspend fun syncContactsForUser(userId: String) {
        try {
            val contacts = getAllContacts()

            if (contacts.isEmpty()) {
                Log.d(TAG, "No contacts to sync")
                return
            }

            Log.d(TAG, "Syncing ${contacts.size} contacts via Cloud Function...")

            // Convert contacts to list of maps for Cloud Function
            // Skip photos to reduce payload size
            val contactsList = contacts.map { contact ->
                val contactId = PhoneNumberNormalizer.getDeduplicationKey(contact.phoneNumber, contact.displayName)
                mapOf(
                    "id" to contactId,
                    "displayName" to contact.displayName,
                    "phoneNumbers" to mapOf(
                        "primary" to mapOf(
                            "number" to contact.phoneNumber,
                            "type" to (contact.phoneType ?: "Mobile")
                        )
                    ),
                    "email" to contact.email,
                    "notes" to contact.notes
                    // Skip photo to reduce payload - can sync separately if needed
                )
            }

            // Call Cloud Function to sync (avoids OOM from Firebase WebSocket)
            val functions = com.google.firebase.functions.FirebaseFunctions.getInstance()
            val result = functions
                .getHttpsCallable("syncContacts")
                .call(mapOf("userId" to userId, "contacts" to contactsList))
                .await()

            val data = result.data as? Map<*, *>
            val success = data?.get("success") as? Boolean ?: false
            val count = data?.get("count") as? Int ?: 0

            if (success) {
                Log.d(TAG, "Successfully synced $count contacts via Cloud Function")
            } else {
                Log.e(TAG, "Cloud Function sync failed: ${data?.get("error")}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing contacts", e)
            throw e
        }
    }

    /**
     * Get phone type label
     */
    private fun getPhoneTypeLabel(type: Int): String {
        return when (type) {
            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "Mobile"
            ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Home"
            ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Work"
            ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "Main"
            ContactsContract.CommonDataKinds.Phone.TYPE_OTHER -> "Other"
            else -> "Mobile"
        }
    }

    /**
     * Get contact photo as Base64 string
     * Uses multiple methods to ensure photo is loaded:
     * 1. Try openContactPhotoInputStream (most reliable)
     * 2. Fallback to direct photo URI
     */
    private fun getContactPhotoBase64(photoUriString: String): String? {
        return try {
            // Method 1: Try to open the photo URI directly
            val photoUri = Uri.parse(photoUriString)
            var inputStream = context.contentResolver.openInputStream(photoUri)

            // Method 2: If direct URI fails, try using contact ID to get photo
            if (inputStream == null) {
                // Extract contact ID from photo URI and try openContactPhotoInputStream
                val contactId = extractContactIdFromPhotoUri(photoUriString)
                if (contactId != null) {
                    val contactUri = android.content.ContentUris.withAppendedId(
                        ContactsContract.Contacts.CONTENT_URI,
                        contactId
                    )
                    inputStream = ContactsContract.Contacts.openContactPhotoInputStream(
                        context.contentResolver,
                        contactUri,
                        true // preferHighRes
                    )
                }
            }

            inputStream?.use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
                if (bitmap == null) {
                    Log.w(TAG, "Failed to decode bitmap from stream for: $photoUriString")
                    return null
                }

                // Resize image to reduce size (max 120x120)
                val resized = Bitmap.createScaledBitmap(
                    bitmap,
                    minOf(bitmap.width, 120),
                    minOf(bitmap.height, 120),
                    true
                )

                // Convert to Base64
                val outputStream = ByteArrayOutputStream()
                resized.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                val byteArray = outputStream.toByteArray()

                bitmap.recycle()
                if (resized !== bitmap) {
                    resized.recycle()
                }

                Base64.encodeToString(byteArray, Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading contact photo: ${e.message}")
            null
        }
    }

    /**
     * Extract contact ID from a photo URI string
     */
    private fun extractContactIdFromPhotoUri(photoUriString: String): Long? {
        return try {
            // Photo URIs typically look like: content://com.android.contacts/contacts/123/photo
            val uri = Uri.parse(photoUriString)
            val segments = uri.pathSegments
            // Find "contacts" segment and get the ID after it
            val contactsIndex = segments.indexOf("contacts")
            if (contactsIndex >= 0 && contactsIndex + 1 < segments.size) {
                segments[contactsIndex + 1].toLongOrNull()
            } else {
                // Try to extract from display_photo path
                val displayPhotoIndex = segments.indexOf("display_photo")
                if (displayPhotoIndex > 0) {
                    segments[displayPhotoIndex - 1].toLongOrNull()
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract contact ID from photo URI: $photoUriString")
            null
        }
    }

    private fun getContactEmail(contactId: String): String? {
        val uri = ContactsContract.CommonDataKinds.Email.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS)
        val selection = "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?"
        val selectionArgs = arrayOf(contactId)

        try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading email for contact $contactId", e)
        }

        return null
    }

    private fun getContactNotes(contactId: String): String? {
        val uri = ContactsContract.Data.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.Note.NOTE)
        val selection = "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
        val selectionArgs = arrayOf(contactId, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)

        try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading notes for contact $contactId", e)
        }

        return null
    }

    private fun sha1(data: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
        val bytes = digest.digest(data.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun asStringMap(value: Any?): Map<String, Any?>? {
        if (value !is Map<*, *>) return null
        val map = mutableMapOf<String, Any?>()
        for ((key, entry) in value) {
            if (key is String) {
                map[key] = entry
            }
        }
        return map
    }

    private fun copyMap(value: Map<*, *>?): MutableMap<String, Any?> {
        val copy = mutableMapOf<String, Any?>()
        value?.forEach { (key, entry) ->
            if (key !is String) return@forEach
            copy[key] = when (entry) {
                is Map<*, *> -> copyMap(entry)
                else -> entry
            }
        }
        return copy
    }

    private fun copyContactData(existing: Map<String, Any?>?): MutableMap<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        existing?.forEach { (key, value) ->
            if (key == "sync") return@forEach
            map[key] = if (value is Map<*, *>) copyMap(value) else value
        }
        return map
    }

    private fun buildContactPayload(
        existingData: Map<String, Any?>?,
        contact: Contact
    ): MutableMap<String, Any?> {
        val payload = copyContactData(existingData)
        payload["displayName"] = contact.displayName
        payload["phoneNumbers"] = buildPhoneNumbersMap(contact)
        payload["photo"] = buildPhotoMap(existingData?.get("photo") as? Map<String, Any?>, contact.photoBase64)
        if (!contact.notes.isNullOrBlank()) {
            payload["notes"] = contact.notes
        } else {
            payload.remove("notes")
        }
        if (!contact.email.isNullOrBlank()) {
            val normalizedEmail = contact.email.trim().lowercase()
            payload["emails"] = mapOf(
                normalizedEmail to mapOf(
                    "address" to contact.email.trim(),
                    "type" to "primary",
                    "isPrimary" to true
                )
            )
        } else {
            payload.remove("emails")
        }
        payload["sync"] = buildSyncMetadata(existingData?.get("sync") as? Map<String, Any?>)
        payload["sources"] = buildSourcesMap(existingData?.get("sources") as? Map<String, Any?>)
        return payload
    }

    private fun buildPhoneNumbersMap(contact: Contact): Map<String, Map<String, Any?>> {
        val normalized = contact.normalizedNumber.ifBlank {
            PhoneNumberNormalizer.normalize(contact.phoneNumber)
        }
        val fallbackKey = contact.phoneNumber.ifBlank { contact.id }
        val key = if (normalized.isNotBlank()) normalized else fallbackKey
        val phoneEntry = mutableMapOf<String, Any?>(
            "number" to contact.phoneNumber,
            "normalizedNumber" to normalized,
            "type" to (contact.phoneType ?: "Mobile"),
            "label" to (contact.phoneType ?: "Mobile"),
            "isPrimary" to true
        )
        return mapOf(key to phoneEntry)
    }

    private fun buildPhotoMap(existingPhoto: Map<String, Any?>?, photoBase64: String?): MutableMap<String, Any?> {
        val map = copyMap(existingPhoto)
        if (!photoBase64.isNullOrBlank()) {
            // New photo provided - update it
            map["thumbnailBase64"] = photoBase64
            map["hash"] = sha1(photoBase64)
            map["updatedAt"] = ServerValue.TIMESTAMP
        }
        // If photoBase64 is null/blank, preserve existing photo (don't remove it)
        // This prevents accidental photo loss due to temporary read errors
        // Photos can only be explicitly removed via a separate operation
        return map
    }

    private fun buildSyncMetadata(existingSync: Map<String, Any?>?): MutableMap<String, Any?> {
        val existingVersion = (existingSync?.get("version") as? Number)?.toLong() ?: 0L
        val version = existingVersion + 1
        return mutableMapOf(
            "lastUpdatedAt" to ServerValue.TIMESTAMP,
            "lastUpdatedBy" to "android",
            "version" to version,
            "pendingAndroidSync" to false,
            "desktopOnly" to false
        )
    }

    private fun buildSourcesMap(existingSources: Map<String, Any?>?): MutableMap<String, Boolean> {
        val sources = mutableMapOf<String, Boolean>()
        existingSources?.forEach { (key, value) ->
            if (value is Boolean) {
                sources[key] = value
            }
        }
        sources["android"] = true
        return sources
    }

    private fun shouldSkipSync(existingData: Map<String, Any?>?): Boolean {
        val sync = existingData?.get("sync") as? Map<String, Any?>
        val pending = (sync?.get("pendingAndroidSync") as? Boolean) ?: false
        val lastUpdatedBy = (sync?.get("lastUpdatedBy") as? String) ?: ""
        return pending && !lastUpdatedBy.equals("android", ignoreCase = true)
    }
}
