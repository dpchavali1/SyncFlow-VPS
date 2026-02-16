package com.phoneintegration.app

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat
import java.util.Locale

object PhoneNumberUtils {

    private val phoneUtil: PhoneNumberUtil = PhoneNumberUtil.getInstance()

    /** Device locale region code (e.g. "IN" for India, "US" for United States) */
    private fun defaultRegion(): String = Locale.getDefault().country.ifEmpty { "US" }

    /**
     * Normalizes a phone number by removing all non-digit characters
     * and converting to a canonical form for comparison.
     * Uses libphonenumber with device locale for correct country inference.
     */
    fun normalizePhoneNumber(phoneNumber: String): String {
        if (phoneNumber.isBlank()) return phoneNumber

        val stripped = phoneNumber.replace(Regex("[^0-9+]"), "")
        val digitsOnly = stripped.replace("+", "")
        if (digitsOnly.isEmpty()) return phoneNumber

        // Try libphonenumber
        try {
            val parsed = phoneUtil.parse(stripped, defaultRegion())
            if (phoneUtil.isValidNumber(parsed)) {
                // Return digits without '+' for backward compat with normalize callers
                return phoneUtil.format(parsed, PhoneNumberFormat.E164).removePrefix("+")
            }
        } catch (_: NumberParseException) {
            // Fall through
        }

        // Legacy: US country code handling
        return if (digitsOnly.length == 11 && digitsOnly.startsWith("1")) {
            digitsOnly.substring(1)
        } else {
            digitsOnly
        }
    }

    /**
     * Normalizes a phone number for conversation grouping.
     * Uses E.164 as the canonical key instead of "last 10 digits".
     * Keeps alphanumeric sender IDs (like "JM-HDFCEL-S") as-is.
     */
    fun normalizeForConversation(address: String): String {
        if (address.isBlank()) return address

        // Handle email-like addresses
        if (address.contains("@")) {
            return address.lowercase(Locale.getDefault())
        }

        // Alphanumeric sender IDs — keep as-is
        if (address.any { it.isLetter() }) {
            return address.uppercase(Locale.getDefault())
        }

        val stripped = address.replace(Regex("[^0-9+]"), "")
        val digitsOnly = stripped.replace("+", "")

        // Short codes (typically 5-6 digits) - keep as-is
        if (digitsOnly.length <= 6) return digitsOnly

        // Use E.164 as canonical conversation key
        return toE164(address)
    }

    /**
     * Alias for normalizePhoneNumber (for compatibility)
     */
    fun normalizeNumber(phoneNumber: String): String {
        return normalizePhoneNumber(phoneNumber)
    }

    /**
     * Converts a phone number to E.164 format for server communication.
     * Uses libphonenumber with device locale as default region.
     */
    fun toE164(phoneNumber: String): String {
        if (phoneNumber.isBlank()) return phoneNumber

        // Leave emails unchanged
        if (phoneNumber.contains("@")) return phoneNumber

        // Leave alphanumeric sender IDs unchanged
        if (phoneNumber.any { it.isLetter() }) return phoneNumber

        // Strip everything except digits and '+'
        val stripped = phoneNumber.replace(Regex("[^0-9+]"), "")
        if (stripped.isEmpty() || stripped == "+") return phoneNumber

        val digitsOnly = stripped.replace("+", "")

        // Short codes (<=6 digits)
        if (digitsOnly.length <= 6) return digitsOnly

        // Try libphonenumber parsing
        try {
            val parsed = phoneUtil.parse(stripped, defaultRegion())
            if (phoneUtil.isValidNumber(parsed)) {
                return phoneUtil.format(parsed, PhoneNumberFormat.E164)
            }
        } catch (_: NumberParseException) {
            // Fall through
        }

        // Legacy fallback: already has '+' prefix → keep as-is
        if (stripped.startsWith("+")) return stripped

        // Legacy fallback: 10 digits → US number
        if (digitsOnly.length == 10) return "+1$digitsOnly"

        // Legacy fallback: 11 digits starting with '1' → US number
        if (digitsOnly.length == 11 && digitsOnly.startsWith("1")) return "+$digitsOnly"

        return digitsOnly
    }

    /**
     * Checks if two phone numbers are the same
     */
    fun areNumbersEqual(number1: String, number2: String): Boolean {
        return toE164(number1) == toE164(number2)
    }

    /**
     * Formats a phone number for display using libphonenumber.
     * Same-region: NATIONAL format. Different-region: INTERNATIONAL format.
     */
    fun formatForDisplay(phoneNumber: String): String {
        if (phoneNumber.isBlank()) return phoneNumber

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

        return phoneNumber
    }

    /**
     * Formats a phone number with country code support using libphonenumber.
     */
    fun formatNumber(phoneNumber: String, defaultCountry: String): String? {
        return try {
            val parsed = phoneUtil.parse(phoneNumber, defaultCountry)
            if (phoneUtil.isValidNumber(parsed)) {
                val numberRegion = phoneUtil.getRegionCodeForNumber(parsed)
                if (numberRegion == defaultCountry) {
                    phoneUtil.format(parsed, PhoneNumberFormat.NATIONAL)
                } else {
                    phoneUtil.format(parsed, PhoneNumberFormat.INTERNATIONAL)
                }
            } else {
                formatForDisplay(phoneNumber)
            }
        } catch (_: NumberParseException) {
            formatForDisplay(phoneNumber)
        }
    }

    /**
     * Overload without country parameter — uses device locale
     */
    fun formatNumber(phoneNumber: String): String {
        return formatNumber(phoneNumber, defaultRegion()) ?: phoneNumber
    }
}
