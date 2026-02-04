package com.phoneintegration.app

import android.content.Context
import android.provider.ContactsContract
import java.util.Locale
import com.phoneintegration.app.PhoneNumberUtils

class ContactHelper(private val context: Context) {

    private val contactCache = mutableMapOf<String, String>()

    fun getContactName(phoneNumber: String): String? {
        val normalizedNumber = PhoneNumberUtils.normalizePhoneNumber(phoneNumber)

        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER
        )

        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (cursor.moveToNext()) {
                    val contactNumber = cursor.getString(numberIndex)
                    val contactNormalized = PhoneNumberUtils.normalizePhoneNumber(contactNumber)

                    // Compare normalized numbers
                    if (contactNormalized == normalizedNumber) {
                        return cursor.getString(nameIndex)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ContactHelper", "Error getting contact name: ${e.message}")
        }

        return null
    }

    private fun formatPhoneNumber(number: String): String {
        return try {
            PhoneNumberUtils.formatNumber(number, Locale.getDefault().country) ?: number
        } catch (e: Exception) {
            number
        }
    }
}