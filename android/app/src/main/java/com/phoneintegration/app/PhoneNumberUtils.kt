package com.phoneintegration.app

import java.util.Locale

object PhoneNumberUtils {

    /**
     * Normalizes a phone number by removing all non-digit characters
     * and handling country codes
     */
    fun normalizePhoneNumber(phoneNumber: String): String {
        // Remove all non-digit characters
        val digitsOnly = phoneNumber.replace(Regex("[^0-9]"), "")

        // Handle US country code (+1)
        // If number starts with 1 and is 11 digits, remove the 1
        return if (digitsOnly.length == 11 && digitsOnly.startsWith("1")) {
            digitsOnly.substring(1) // Remove leading 1
        } else if (digitsOnly.length == 10) {
            digitsOnly // Already 10 digits
        } else {
            digitsOnly // Keep as is for other formats
        }
    }

    /**
     * Normalizes a phone number for conversation grouping.
     * Uses last 10 digits to merge numbers with country code differences.
     * Keeps alphanumeric sender IDs (like "JM-HDFCEL-S") as-is.
     */
    fun normalizeForConversation(address: String): String {
        if (address.isBlank()) return address

        // Handle email-like addresses
        if (address.contains("@")) {
            return address.lowercase(Locale.getDefault())
        }

        // Check if this is an alphanumeric sender ID (like "JM-HDFCEL-S", "AD-AMAZON", etc.)
        // These typically have letters and are used by businesses for SMS
        val hasLetters = address.any { it.isLetter() }

        // If address has ANY letters, it's an alphanumeric sender ID
        // Keep it exactly as-is (uppercase for case-insensitive matching)
        // Only merge conversations if the sender ID is EXACTLY the same
        if (hasLetters) {
            return address.uppercase(Locale.getDefault())
        }

        val digitsOnly = address.replace(Regex("[^0-9]"), "")

        // Short codes (typically 5-6 digits) - keep as-is
        if (address.length <= 6 && digitsOnly.length == address.length) {
            return digitsOnly
        }

        // Regular phone numbers - normalize by taking last 10 digits
        return if (digitsOnly.length >= 10) {
            digitsOnly.takeLast(10)
        } else {
            digitsOnly
        }
    }

    /**
     * Alias for normalizePhoneNumber (for compatibility)
     * This fixes the crash where code was calling normalizeNumber instead of normalizePhoneNumber
     */
    fun normalizeNumber(phoneNumber: String): String {
        return normalizePhoneNumber(phoneNumber)
    }

    /**
     * Converts a phone number to E.164 format for server communication.
     * Rules:
     * - 10 digits → +1XXXXXXXXXX (US)
     * - 11 digits starting with 1 → +1XXXXXXXXXX (US)
     * - Already starts with + → keep as-is
     * - Short codes (<=6 digits), emails, alphanumeric sender IDs → unchanged
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

        // Already has '+' prefix → keep as-is
        if (stripped.startsWith("+")) return stripped

        // 10 digits → US number
        if (digitsOnly.length == 10) return "+1$digitsOnly"

        // 11 digits starting with '1' → US number
        if (digitsOnly.length == 11 && digitsOnly.startsWith("1")) return "+$digitsOnly"

        return digitsOnly
    }

    /**
     * Checks if two phone numbers are the same
     */
    fun areNumbersEqual(number1: String, number2: String): Boolean {
        return normalizePhoneNumber(number1) == normalizePhoneNumber(number2)
    }

    /**
     * Formats a phone number for display
     * Keeps the original format but ensures consistency
     */
    fun formatForDisplay(phoneNumber: String): String {
        val normalized = normalizePhoneNumber(phoneNumber)

        // Format US numbers as (XXX) XXX-XXXX
        return if (normalized.length == 10) {
            "(${normalized.substring(0, 3)}) ${normalized.substring(3, 6)}-${normalized.substring(6)}"
        } else {
            phoneNumber // Keep original format for non-US numbers
        }
    }

    /**
     * Formats a phone number with country code support
     * This matches the signature of Android's PhoneNumberUtils.formatNumber
     */
    fun formatNumber(phoneNumber: String, defaultCountry: String): String? {
        return try {
            // Use Android's built-in formatter
            android.telephony.PhoneNumberUtils.formatNumber(phoneNumber, defaultCountry)
        } catch (e: Exception) {
            // Fallback to our custom formatter
            formatForDisplay(phoneNumber)
        }
    }

    /**
     * Overload without country parameter
     */
    fun formatNumber(phoneNumber: String): String {
        return formatNumber(phoneNumber, Locale.getDefault().country) ?: phoneNumber
    }
}
