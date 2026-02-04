package com.phoneintegration.app.utils

/**
 * Consistent phone number normalization across all platforms
 * Ensures contacts sync correctly between Android, macOS, and Web
 */
object PhoneNumberNormalizer {

    /**
     * Normalize phone number to standard format for comparison
     * Handles: +1-234-567-8900, (234) 567-8900, 234-567-8900, 2345678900, etc.
     */
    fun normalize(phoneNumber: String?): String {
        if (phoneNumber.isNullOrBlank()) return ""

        // Remove all non-digit characters
        val digitsOnly = phoneNumber.replace(Regex("[^0-9+]"), "")

        // Remove leading + if present
        val withoutPlus = digitsOnly.replace("+", "")

        // If starts with 1 (US country code), remove it to get 10 digits
        val normalized = if (withoutPlus.startsWith("1") && withoutPlus.length > 10) {
            withoutPlus.substring(1)
        } else {
            withoutPlus
        }

        return normalized
    }

    /**
     * Format phone number for display
     * US: (234) 567-8900
     * International: keep as-is
     */
    fun formatForDisplay(phoneNumber: String?): String {
        if (phoneNumber.isNullOrBlank()) return ""

        val normalized = normalize(phoneNumber)

        // US format (10 digits)
        return if (normalized.length == 10) {
            val areaCode = normalized.substring(0, 3)
            val prefix = normalized.substring(3, 6)
            val lineNumber = normalized.substring(6)
            "($areaCode) $prefix-$lineNumber"
        } else {
            // International or non-standard: return normalized
            normalized
        }
    }

    /**
     * Create deduplication key for contacts
     * Uses normalized phone number only (not hashCode which changes)
     */
    fun getDeduplicationKey(phoneNumber: String?, displayName: String? = null): String {
        val normalized = normalize(phoneNumber)
        // Use phone number as primary key, fall back to name if no phone
        return if (normalized.isNotEmpty()) {
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
        return normalize(phone1) == normalize(phone2) && normalize(phone1).isNotEmpty()
    }
}
