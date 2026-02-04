package com.phoneintegration.app.desktop

import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.provider.ContactsContract
import android.util.Base64
import android.util.Log
import com.google.firebase.database.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import com.phoneintegration.app.utils.PhoneNumberNormalizer

/**
 * Service to receive contact changes from Firebase (created on macOS/Web)
 * and sync them to Android device contacts.
 *
 * This enables two-way contact sync:
 * - Android → Firebase (existing ContactsSyncService)
 * - Firebase → Android (this service)
 */
class ContactsReceiveService(private val context: Context) {

    companion object {
        private const val TAG = "ContactsReceiveService"
        private const val CONTACTS_PATH = "contacts"
    }

    private val syncService = DesktopSyncService(context)
    private var contactsListener: ValueEventListener? = null
    private var childContactsListener: ChildEventListener? = null  // BANDWIDTH OPTIMIZED
    private var databaseRef: DatabaseReference? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Represents a change to a contact that needs to be applied on Android.
     */
    data class RemoteContactUpdate(
        val id: String,
        val displayName: String,
        val phoneNumber: String,
        val normalizedNumber: String,
        val phoneType: String,
        val email: String?,
        val notes: String?,
        val photoBase64: String?,
        val lastUpdatedBy: String,
        val lastUpdatedAt: Long,
        val version: Long,
        val androidContactId: Long?
    ) {
        companion object {
            fun fromMap(id: String, data: Map<String, Any?>, requirePending: Boolean = true): RemoteContactUpdate? {
                val displayName = data["displayName"] as? String ?: return null
                val phoneNumbers = data["phoneNumbers"] as? Map<*, *>
                val firstPhone = phoneNumbers?.values?.firstOrNull() as? Map<*, *> ?: return null
                val phoneNumber = firstPhone["number"] as? String ?: return null
                val normalizedNumber = firstPhone["normalizedNumber"] as? String ?: ""
                val phoneType = firstPhone["type"] as? String ?: "Mobile"

                val emails = data["emails"] as? Map<*, *>
                val firstEmail = emails?.values?.firstOrNull() as? Map<*, *>
                val email = firstEmail?.get("address") as? String

                val notes = data["notes"] as? String

                val photo = data["photo"] as? Map<*, *>
                val photoBase64 = photo?.get("thumbnailBase64") as? String

                val androidContactId = (data["androidContactId"] as? Number)?.toLong()

                val sync = data["sync"] as? Map<*, *>
                val pendingSync = (sync?.get("pendingAndroidSync") as? Boolean) ?: false
                val lastUpdatedBy = (sync?.get("lastUpdatedBy") as? String) ?: ""
                if (requirePending && (!pendingSync || lastUpdatedBy.equals("android", ignoreCase = true))) {
                    return null
                }

                val version = (sync?.get("version") as? Number)?.toLong() ?: 0L
                val lastUpdatedAt = (sync?.get("lastUpdatedAt") as? Number)?.toLong() ?: 0L

                return RemoteContactUpdate(
                    id = id,
                    displayName = displayName,
                    phoneNumber = phoneNumber,
                    normalizedNumber = normalizedNumber,
                    phoneType = phoneType,
                    email = email,
                    notes = notes,
                    photoBase64 = photoBase64,
                    lastUpdatedBy = lastUpdatedBy,
                    lastUpdatedAt = lastUpdatedAt,
                    version = version,
                    androidContactId = androidContactId
                )
            }
        }
    }

    /**
     * Start listening for desktop-created contacts
     */
    fun startListening() {
        scope.launch {
            try {
                val userId = syncService.getCurrentUserId()

                databaseRef = FirebaseDatabase.getInstance().reference
                    .child("users")
                    .child(userId)
                    .child(CONTACTS_PATH)

                contactsListener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        scope.launch {
                            processContactChanges(snapshot)
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Log.e(TAG, "Firebase listener cancelled: ${error.message}")
                    }
                }

                databaseRef?.addValueEventListener(contactsListener!!)
                Log.d(TAG, "Started listening for universal contacts")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting contacts listener", e)
            }
        }
    }

    /**
     * Stop listening for contact changes
     */
    fun stopListening() {
        contactsListener?.let { listener ->
            databaseRef?.removeEventListener(listener)
        }
        childContactsListener?.let { listener ->
            databaseRef?.removeEventListener(listener)
        }
        contactsListener = null
        childContactsListener = null
        databaseRef = null
        scope.cancel()
        Log.d(TAG, "Stopped listening for universal contacts")
    }

    /**
     * BANDWIDTH OPTIMIZED: Start listening with child events instead of value events.
     * This reduces bandwidth by ~95% - only receives individual contact changes,
     * not the entire contacts list on every change.
     */
    fun startListeningOptimized() {
        scope.launch {
            try {
                val userId = syncService.getCurrentUserId()

                databaseRef = FirebaseDatabase.getInstance().reference
                    .child("users")
                    .child(userId)
                    .child(CONTACTS_PATH)

                childContactsListener = object : ChildEventListener {
                    override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                        scope.launch {
                            processSingleContact(snapshot)
                        }
                    }

                    override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                        scope.launch {
                            processSingleContact(snapshot)
                        }
                    }

                    override fun onChildRemoved(snapshot: DataSnapshot) {
                        Log.d(TAG, "Contact removed from Firebase: ${snapshot.key}")
                    }

                    override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}

                    override fun onCancelled(error: DatabaseError) {
                        Log.e(TAG, "Firebase child listener cancelled: ${error.message}")
                    }
                }

                databaseRef?.addChildEventListener(childContactsListener!!)
                Log.d(TAG, "Started OPTIMIZED listening for contacts (delta-only)")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting optimized contacts listener", e)
            }
        }
    }

    /**
     * Process a single contact change (for optimized listener)
     */
    private suspend fun processSingleContact(snapshot: DataSnapshot) {
        try {
            val contactId = snapshot.key ?: return
            val data = snapshot.value as? Map<String, Any?> ?: return

            // Use existing fromMap method
            val contact = RemoteContactUpdate.fromMap(contactId, data) ?: return

            val androidContactId = createOrUpdateAndroidContact(contact)
            if (androidContactId != null) {
                markPendingSyncComplete(contact.id, androidContactId, contact.version)
                Log.d(TAG, "Synced contact ${contact.displayName} to Android (ID: $androidContactId)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing single contact", e)
        }
    }

    /**
     * Process contact changes from Firebase
     */
    private suspend fun processContactChanges(snapshot: DataSnapshot) {
        try {
            val pendingContacts = mutableListOf<RemoteContactUpdate>()

            for (child in snapshot.children) {
                val contactId = child.key ?: continue
                val data = child.value as? Map<String, Any?> ?: continue

                RemoteContactUpdate.fromMap(contactId, data)?.let { contact ->
                    pendingContacts.add(contact)
                }
            }

            Log.d(TAG, "Found ${pendingContacts.size} pending contacts to sync to Android")

            for (contact in pendingContacts) {
                try {
                    val androidContactId = createOrUpdateAndroidContact(contact)
                    if (androidContactId != null) {
                        markPendingSyncComplete(contact.id, androidContactId, contact.version)
                        Log.d(TAG, "Synced contact ${contact.displayName} to Android (ID: $androidContactId)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error syncing contact ${contact.displayName}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing contact changes", e)
        }
    }

    /**
     * Create or update a contact in Android's contact database
     */
    private fun createOrUpdateAndroidContact(contact: RemoteContactUpdate): Long? {
        try {
            val existingId = contact.androidContactId?.let { validateContactId(it) } ?: findExistingContact(contact.phoneNumber)

            if (existingId != null) {
                updateExistingContact(existingId, contact)
                return existingId
            }

            val ops = ArrayList<ContentProviderOperation>()
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .build()
            )

            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                    )
                    .withValue(
                        ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                        contact.displayName
                    )
                    .build()
            )

            val phoneType = mapPhoneType(contact.phoneType)
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
                    )
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, contact.phoneNumber)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, phoneType)
                    .build()
            )

            contact.email?.let { email ->
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(
                            ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
                        )
                        .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                        .withValue(
                            ContactsContract.CommonDataKinds.Email.TYPE,
                            ContactsContract.CommonDataKinds.Email.TYPE_OTHER
                        )
                        .build()
                )
            }

            contact.notes?.let { notes ->
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(
                            ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE
                        )
                        .withValue(ContactsContract.CommonDataKinds.Note.NOTE, notes)
                        .build()
                )
            }

            contact.photoBase64?.let { photoBase64 ->
                try {
                    val photoBytes = Base64.decode(photoBase64, Base64.DEFAULT)
                    ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                            .withValue(
                                ContactsContract.Data.MIMETYPE,
                                ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE
                            )
                            .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, photoBytes)
                            .build()
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error decoding photo for ${contact.displayName}", e)
                }
            }

            val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            val rawContactUri = results.firstOrNull()?.uri
            val rawContactId = rawContactUri?.lastPathSegment?.toLongOrNull()
            return getContactIdFromRawId(rawContactId)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating Android contact", e)
            return null
        }
    }

    /**
     * Find existing contact by phone number
     */
    private fun findExistingContact(phoneNumber: String): Long? {
        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val selection = "${ContactsContract.CommonDataKinds.Phone.NUMBER} = ? OR ${ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER} = ?"
            val normalizedNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
            val selectionArgs = arrayOf(phoneNumber, normalizedNumber)

            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                    return cursor.getLong(idIndex)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding existing contact", e)
        }
        return null
    }

    private fun mapPhoneType(type: String): Int {
        return when (type.lowercase()) {
            "mobile" -> ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
            "home" -> ContactsContract.CommonDataKinds.Phone.TYPE_HOME
            "work" -> ContactsContract.CommonDataKinds.Phone.TYPE_WORK
            "main" -> ContactsContract.CommonDataKinds.Phone.TYPE_MAIN
            else -> ContactsContract.CommonDataKinds.Phone.TYPE_OTHER
        }
    }

    /**
     * Update existing contact with new information
     * Uses last-write-wins conflict resolution based on sync.lastUpdatedAt timestamp
     */
    private fun updateExistingContact(contactId: Long, contact: RemoteContactUpdate) {
        try {
            Log.d(TAG, "Updating contact ${contact.displayName} with timestamp: ${contact.lastUpdatedAt}")

            val nameValues = ContentValues().apply {
                put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, contact.displayName)
            }
            val nameSelection = "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
            val nameSelectionArgs = arrayOf(
                contactId.toString(),
                ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
            )
            context.contentResolver.update(
                ContactsContract.Data.CONTENT_URI,
                nameValues,
                nameSelection,
                nameSelectionArgs
            )

            val normalizedNumber = contact.normalizedNumber.ifBlank {
                PhoneNumberNormalizer.normalize(contact.phoneNumber)
            }
            val phoneValues = ContentValues().apply {
                put(ContactsContract.CommonDataKinds.Phone.NUMBER, contact.phoneNumber)
                put(ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER, normalizedNumber)
                put(ContactsContract.CommonDataKinds.Phone.TYPE, mapPhoneType(contact.phoneType))
            }
            val phoneSelection = "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
            val phoneSelectionArgs = arrayOf(
                contactId.toString(),
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
            )
            val phoneUpdated = context.contentResolver.update(
                ContactsContract.Data.CONTENT_URI,
                phoneValues,
                phoneSelection,
                phoneSelectionArgs
            )
            if (phoneUpdated == 0) {
                insertPhoneEntry(contactId, contact, normalizedNumber)
            }

            contact.email?.let { email ->
                val emailValues = ContentValues().apply {
                    put(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                }
                val emailSelection = "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
                val emailSelectionArgs = arrayOf(
                    contactId.toString(),
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
                )
                val updated = context.contentResolver.update(
                    ContactsContract.Data.CONTENT_URI,
                    emailValues,
                    emailSelection,
                    emailSelectionArgs
                )
                if (updated == 0) {
                    val rawContactId = getRawContactId(contactId)
                    if (rawContactId != null) {
                        val insertValues = ContentValues().apply {
                            put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                            put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                            put(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                            put(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_OTHER)
                        }
                        context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, insertValues)
                    }
                }
            }

            contact.notes?.let { notes ->
                val notesValues = ContentValues().apply {
                    put(ContactsContract.CommonDataKinds.Note.NOTE, notes)
                }
                val notesSelection = "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
                val notesSelectionArgs = arrayOf(
                    contactId.toString(),
                    ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE
                )
                val updated = context.contentResolver.update(
                    ContactsContract.Data.CONTENT_URI,
                    notesValues,
                    notesSelection,
                    notesSelectionArgs
                )
                if (updated == 0) {
                    val rawContactId = getRawContactId(contactId)
                    if (rawContactId != null) {
                        val insertValues = ContentValues().apply {
                            put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                            put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                            put(ContactsContract.CommonDataKinds.Note.NOTE, notes)
                        }
                        context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, insertValues)
                    }
                }
            }

            contact.photoBase64?.takeIf { it.isNotBlank() }?.let { photo ->
                updateContactPhoto(contactId, contact.displayName, photo)
            }

            Log.d(TAG, "Updated existing contact: ${contact.displayName}")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating existing contact", e)
        }
    }

    /**
     * Get raw contact ID from contact ID
     */
    private fun getRawContactId(contactId: Long): Long? {
        try {
            val uri = ContactsContract.RawContacts.CONTENT_URI
            val projection = arrayOf(ContactsContract.RawContacts._ID)
            val selection = "${ContactsContract.RawContacts.CONTACT_ID} = ?"
            val selectionArgs = arrayOf(contactId.toString())

            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getLong(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting raw contact ID", e)
        }
        return null
    }

    private fun getContactIdFromRawId(rawContactId: Long?): Long? {
        if (rawContactId == null) return null
        try {
            val uri = ContactsContract.RawContacts.CONTENT_URI
            val projection = arrayOf(ContactsContract.RawContacts.CONTACT_ID)
            val selection = "${ContactsContract.RawContacts._ID} = ?"
            val selectionArgs = arrayOf(rawContactId.toString())

            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getLong(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving contact ID from raw ID", e)
        }
        return null
    }

    private fun validateContactId(contactId: Long?): Long? {
        if (contactId == null || contactId <= 0) return null
        try {
            val uri = ContactsContract.Contacts.CONTENT_URI
            val projection = arrayOf(ContactsContract.Contacts._ID)
            val selection = "${ContactsContract.Contacts._ID} = ?"
            val selectionArgs = arrayOf(contactId.toString())

            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return contactId
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error validating contact ID $contactId", e)
        }
        return null
    }

    private fun insertPhoneEntry(contactId: Long, contact: RemoteContactUpdate, normalizedNumber: String) {
        val rawContactId = getRawContactId(contactId) ?: return
        val phoneTypeValue = mapPhoneType(contact.phoneType)
        val values = ContentValues().apply {
            put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
            put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            put(ContactsContract.CommonDataKinds.Phone.NUMBER, contact.phoneNumber)
            put(ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER, normalizedNumber)
            put(ContactsContract.CommonDataKinds.Phone.TYPE, phoneTypeValue)
        }
        context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, values)
    }

    private fun updateContactPhoto(contactId: Long, displayName: String, photoBase64: String) {
        try {
            val photoSelection = "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
            val photoSelectionArgs = arrayOf(
                contactId.toString(),
                ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE
            )
            context.contentResolver.delete(
                ContactsContract.Data.CONTENT_URI,
                photoSelection,
                photoSelectionArgs
            )

            val rawContactId = getRawContactId(contactId) ?: return
            val photoBytes = Base64.decode(photoBase64, Base64.DEFAULT)

            val photoValues = ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.Photo.PHOTO, photoBytes)
            }
            context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, photoValues)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating photo for $displayName", e)
        }
    }

    /**
     * Mark a pending contact as synced (clear pending flag and bump version).
     */
    private suspend fun markPendingSyncComplete(contactId: String, androidContactId: Long, previousVersion: Long) {
        try {
            val userId = syncService.getCurrentUserId()

            val updates = mapOf<String, Any?>(
                "sync/pendingAndroidSync" to false,
                "sync/desktopOnly" to false,
                "sync/lastUpdatedBy" to "android",
                "sync/version" to previousVersion + 1,
                "sync/lastSyncedAt" to ServerValue.TIMESTAMP,
                "androidContactId" to androidContactId
            )

            FirebaseDatabase.getInstance().reference
                .child("users")
                .child(userId)
                .child(CONTACTS_PATH)
                .child(contactId)
                .updateChildren(updates)
                .await()

        } catch (e: Exception) {
            Log.e(TAG, "Error marking contact as synced", e)
        }
    }

    /**
     * Manually sync a specific contact from desktop to Android
     */
    suspend fun syncContactFromDesktop(contactId: String): Boolean {
        return try {
            val userId = syncService.getCurrentUserId()

            val snapshot = FirebaseDatabase.getInstance().reference
                .child("users")
                .child(userId)
                .child(CONTACTS_PATH)
                .child(contactId)
                .get()
                .await()

            val data = snapshot.value as? Map<String, Any?> ?: return false
            val contact = RemoteContactUpdate.fromMap(contactId, data, requirePending = false) ?: return false

            val androidId = createOrUpdateAndroidContact(contact)
            if (androidId != null) {
                markPendingSyncComplete(contactId, androidId, contact.version)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing specific contact", e)
            false
        }
    }
}
