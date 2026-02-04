package com.phoneintegration.app

import android.content.Context
import android.provider.ContactsContract

data class Contact(
    val name: String,
    val phoneNumber: String
)

class ContactPicker(private val context: Context) {

    fun getAllContacts(): List<Contact> {
        val contacts = mutableListOf<Contact>()

        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )

        cursor?.use {
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (it.moveToNext()) {
                val name = it.getString(nameIndex) ?: continue
                val number = it.getString(numberIndex) ?: continue

                // Clean phone number (remove spaces, dashes, etc)
                val cleanNumber = number.replace(Regex("[^0-9+]"), "")

                if (cleanNumber.isNotEmpty()) {
                    contacts.add(Contact(name, cleanNumber))
                }
            }
        }

        return contacts
    }
}