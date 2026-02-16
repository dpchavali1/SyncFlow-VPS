package com.phoneintegration.app.utils

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat
import java.util.Locale

/**
 * Consistent phone number normalization across all platforms
 * Ensures contacts sync correctly between Android, macOS, and Web
 *
 * Uses google-libphonenumber for proper international support.
 */
object PhoneNumberNormalizer {

    private val phoneUtil: PhoneNumberUtil = PhoneNumberUtil.getInstance()

    private fun defaultRegion(): String = Locale.getDefault().country.ifEmpty { "US" }

    /**
     * Normalize phone number to E.164 format for comparison.
     */
    fun normalize(phoneNumber: String?): String {
        if (phoneNumber.isNullOrBlank()) return ""

        val trimmed = phoneNumber.trim()

        // Leave emails unchanged
        if (trimmed.contains("@")) return trimmed

        // Leave alphanumeric sender IDs unchanged
        if (trimmed.any { it.isLetter() }) return trimmed

        val stripped = trimmed.replace(Regex("[^0-9+]"), "")
        val digitsOnly = stripped.replace("+", "")
        if (digitsOnly.isEmpty()) return trimmed

        // Short codes
        if (digitsOnly.length <= 6) return digitsOnly

        // Try libphonenumber
        try {
            val parsed = phoneUtil.parse(stripped, defaultRegion())
            if (phoneUtil.isValidNumber(parsed)) {
                return phoneUtil.format(parsed, PhoneNumberFormat.E164)
            }
        } catch (_: NumberParseException) {
            // Fall through
        }

        // Legacy fallback
        if (stripped.startsWith("+")) return stripped
        if (digitsOnly.length == 10) return "+1$digitsOnly"
        if (digitsOnly.length == 11 && digitsOnly.startsWith("1")) return "+$digitsOnly"

        return digitsOnly
    }

    /**
     * Format phone number for display using libphonenumber.
     * Same-region: NATIONAL format. Different-region: INTERNATIONAL format.
     */
    fun formatForDisplay(phoneNumber: String?): String {
        if (phoneNumber.isNullOrBlank()) return ""

        val region = defaultRegion()
        try {
            val parsed = phoneUtil.parse(phoneNumber, region)
            if (phoneUtil.isValidNumber(parsed)) {
                val numberRegion = phoneUtil.getRegionCodeForNumber(parsed)
                return if (numberRegion == region) {
                    phoneUtil.format(parsed, PhoneNumberFormat.NATIONAL)
                } else {
                    phoneUtil.format(parsed, PhoneNumberFormat.INTERNATIONAL)
                }
            }
        } catch (_: NumberParseException) {
            // Fall through
        }

        return phoneNumber ?: ""
    }

    /**
     * Create deduplication key for contacts
     * Uses E.164 phone number (not hashCode which changes)
     */
    fun getDeduplicationKey(phoneNumber: String?, displayName: String? = null): String {
        val normalized = normalize(phoneNumber)
        return if (normalized.isNotEmpty() && normalized.startsWith("+")) {
            "phone_$normalized"
        } else if (!displayName.isNullOrBlank()) {
            "name_${displayName.lowercase().replace(Regex("[^a-z0-9]"), "")}"
        } else {
            "unknown_${System.currentTimeMillis()}"
        }
    }

    /**
     * Check if two phone numbers are the same person
     */
    fun isSameContact(phone1: String?, phone2: String?): Boolean {
        val norm1 = normalize(phone1)
        val norm2 = normalize(phone2)
        return norm1.isNotEmpty() && norm1 == norm2
    }
}
