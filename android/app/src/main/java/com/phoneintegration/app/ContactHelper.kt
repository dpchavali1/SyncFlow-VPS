package com.phoneintegration.app

import android.content.Context
import android.provider.ContactsContract
import java.util.Locale
import com.phoneintegration.app.PhoneNumberUtils

class ContactHelper(private val context: Context) {

    private val contactCache = mutableMapOf<String, String>()

    /**
     * Get contact display name for a phone number.
     * Uses Android's PhoneLookup which handles all number format variations
     * (e.g. +12488542993 vs 2488542993 vs (248) 854-2993).
     */
    fun getContactName(phoneNumber: String): String? {
        if (phoneNumber.isBlank()) return null

        try {
            // PhoneLookup handles number normalization automatically — no manual comparison needed
            val uri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI
                .buildUpon()
                .appendPath(phoneNumber)
                .build()
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)

            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(
                        cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ContactHelper", "Error getting contact name: ${e.message}")
        }

        return null
    }

    /**
     * Check if a phone number belongs to a saved contact.
     * Uses Android's PhoneLookup for reliable matching across all number formats.
     */
    fun isContact(phoneNumber: String): Boolean {
        if (phoneNumber.isBlank()) return false

        try {
            val uri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI
                .buildUpon()
                .appendPath(phoneNumber)
                .build()
            val projection = arrayOf(ContactsContract.PhoneLookup._ID)

            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                return cursor.count > 0
            }
        } catch (e: Exception) {
            android.util.Log.e("ContactHelper", "Error checking contact: ${e.message}")
        }

        return false
    }

    private fun formatPhoneNumber(number: String): String {
        return try {
            PhoneNumberUtils.formatNumber(number, Locale.getDefault().country) ?: number
        } catch (e: Exception) {
            number
        }
    }
}