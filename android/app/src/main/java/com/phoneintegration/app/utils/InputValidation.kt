package com.phoneintegration.app.utils

import android.content.Context
import android.util.Patterns
import com.phoneintegration.app.security.SecurityEvent
import com.phoneintegration.app.security.SecurityEventType
import com.phoneintegration.app.security.SecurityMonitor
import java.util.regex.Pattern

/**
 * Input validation utilities for SyncFlow
 * Provides validation for phone numbers, messages, and other user inputs
 */
object InputValidation {

    private var securityMonitor: SecurityMonitor? = null

    /**
     * Initialize with security monitoring (call from Application)
     * This is optional and will be called automatically when security features are available
     */
    fun initializeSecurityMonitoring(context: Context) {
        try {
            securityMonitor = SecurityMonitor.getInstance(context)
        } catch (e: Exception) {
            // Security monitoring not available in test environments
            securityMonitor = null
        }
    }

    // Maximum lengths
    const val MAX_MESSAGE_LENGTH = 1600 // SMS limit with multipart
    const val MAX_SINGLE_SMS_LENGTH = 160
    const val MAX_PHONE_NUMBER_LENGTH = 20
    const val MAX_CONTACT_NAME_LENGTH = 100
    const val MAX_GROUP_NAME_LENGTH = 50

    // Patterns - use simple regex for test compatibility
    private val PHONE_PATTERN = Pattern.compile("^[+]?[0-9]{7,15}$") // Just validate digits after cleaning
    private val URL_PATTERN = Pattern.compile("https?://(www\\.)?[-a-zA-Z0-9@:%._+~#=]{1,256}\\.[a-zA-Z0-9()]{1,6}\\b([-a-zA-Z0-9()@:%_+.~#?&//=]*)")
    private val EMOJI_PATTERN = Pattern.compile("[\uD83C-\uDBFF\uDC00-\uDFFF]+")
    private val DANGEROUS_CHARS = Pattern.compile("[<>\"'&;\\\\]")

    /**
     * Validation result with optional error message
     */
    data class ValidationResult(
        val isValid: Boolean,
        val errorMessage: String? = null,
        val sanitizedValue: String? = null
    ) {
        companion object {
            fun valid(sanitizedValue: String? = null) = ValidationResult(true, sanitizedValue = sanitizedValue)
            fun invalid(message: String) = ValidationResult(false, message)
        }
    }

    // ========================================
    // PHONE NUMBER VALIDATION
    // ========================================

    /**
     * Validate phone number format
     */
    fun validatePhoneNumber(phoneNumber: String?): ValidationResult {
        if (phoneNumber.isNullOrBlank()) {
            return ValidationResult.invalid("Phone number is required")
        }

        // Check for email-like patterns (should not be treated as phone numbers)
        if (phoneNumber.contains('@')) {
            return ValidationResult.invalid("Invalid phone number format")
        }

        // First clean the phone number (remove all non-digits except +)
        val cleaned = phoneNumber.replace(Regex("[^0-9+]"), "")

        if (cleaned.length > MAX_PHONE_NUMBER_LENGTH) {
            return ValidationResult.invalid("Phone number is too long")
        }

        if (cleaned.length < 7) { // Minimum 7 digits for a phone number
            return ValidationResult.invalid("Phone number is too short")
        }

        // Allow standard phone patterns
        if (!PHONE_PATTERN.matcher(cleaned).matches()) {

            // Log validation failure for security monitoring
            try {
                securityMonitor?.logEvent(SecurityEvent(
                    type = SecurityEventType.INPUT_VALIDATION_FAILED,
                    message = "Invalid phone number format",
                    metadata = mapOf(
                        "inputLength" to cleaned.length.toString(),
                        "validationType" to "phone_number"
                    )
                ))
            } catch (e: Exception) {
                // Ignore logging errors in test environments
            }

            return ValidationResult.invalid("Invalid phone number format")
        }

        return ValidationResult.valid(cleaned)
    }

    /**
     * Sanitize phone number (remove formatting for storage)
     */
    fun sanitizePhoneNumber(phoneNumber: String): String {
        return phoneNumber.replace(Regex("[^+0-9]"), "")
    }

    /**
     * Format phone number for display
     */
    fun formatPhoneForDisplay(phoneNumber: String): String {
        val digits = sanitizePhoneNumber(phoneNumber)
        return when {
            digits.startsWith("+1") && digits.length == 12 -> {
                // US format: +1 (XXX) XXX-XXXX
                "+1 (${digits.substring(2, 5)}) ${digits.substring(5, 8)}-${digits.substring(8)}"
            }
            digits.length == 10 -> {
                // US format without country code
                "(${digits.substring(0, 3)}) ${digits.substring(3, 6)}-${digits.substring(6)}"
            }
            else -> phoneNumber
        }
    }

    // ========================================
    // MESSAGE VALIDATION
    // ========================================

    /**
     * Validate message content
     */
    fun validateMessage(message: String?): ValidationResult {
        if (message.isNullOrBlank()) {
            return ValidationResult.invalid("Message cannot be empty")
        }

        val trimmed = message.trim()

        if (trimmed.length > MAX_MESSAGE_LENGTH) {
            // Log validation failure for security monitoring
            try {
                securityMonitor?.logEvent(SecurityEvent(
                    type = SecurityEventType.INPUT_VALIDATION_FAILED,
                    message = "Message exceeds maximum length",
                    metadata = mapOf(
                        "inputLength" to trimmed.length.toString(),
                        "maxLength" to MAX_MESSAGE_LENGTH.toString(),
                        "validationType" to "message_length"
                    )
                ))
            } catch (e: Exception) {
                // Ignore logging errors in test environments
            }

            return ValidationResult.invalid("Message exceeds maximum length of $MAX_MESSAGE_LENGTH characters")
        }

        // Check for only whitespace
        if (trimmed.replace(Regex("\\s"), "").isEmpty()) {
            return ValidationResult.invalid("Message cannot contain only whitespace")
        }

        return ValidationResult.valid(trimmed)
    }

    /**
     * Calculate SMS segment count
     */
    fun calculateSmsSegments(message: String): Int {
        if (message.isEmpty()) return 0

        // Check if message contains non-GSM characters (requires UCS-2 encoding)
        val isUnicode = message.any { !isGsmCharacter(it) }

        return if (isUnicode) {
            // UCS-2: 70 chars per segment, 67 for multipart
            when {
                message.length <= 70 -> 1
                else -> (message.length + 66) / 67
            }
        } else {
            // GSM-7: 160 chars per segment, 153 for multipart
            when {
                message.length <= 160 -> 1
                else -> (message.length + 152) / 153
            }
        }
    }

    /**
     * Check if character is in GSM-7 character set
     */
    private fun isGsmCharacter(char: Char): Boolean {
        val gsmChars = "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞ ÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"
        val gsmExtChars = "^{}\\[~]|€"
        return char in gsmChars || char in gsmExtChars
    }

    // ========================================
    // GROUP/CONTACT NAME VALIDATION
    // ========================================

    /**
     * Validate group name
     */
    fun validateGroupName(name: String?): ValidationResult {
        if (name.isNullOrBlank()) {
            return ValidationResult.invalid("Group name is required")
        }

        val trimmed = name.trim()

        if (trimmed.length > MAX_GROUP_NAME_LENGTH) {
            return ValidationResult.invalid("Group name cannot exceed $MAX_GROUP_NAME_LENGTH characters")
        }

        if (trimmed.length < 1) {
            return ValidationResult.invalid("Group name is too short")
        }

        // Sanitize potentially dangerous characters
        val sanitized = sanitizeText(trimmed)

        return ValidationResult.valid(sanitized)
    }

    /**
     * Validate contact name
     */
    fun validateContactName(name: String?): ValidationResult {
        if (name.isNullOrBlank()) {
            return ValidationResult.valid("") // Contact name is optional
        }

        val trimmed = name.trim()

        if (trimmed.length > MAX_CONTACT_NAME_LENGTH) {
            return ValidationResult.invalid("Contact name is too long")
        }

        val sanitized = sanitizeText(trimmed)
        return ValidationResult.valid(sanitized)
    }

    // ========================================
    // SECURITY SANITIZATION
    // ========================================

    /**
     * Sanitize text to prevent injection attacks
     */
    fun sanitizeText(input: String): String {
        // Replace potentially dangerous characters
        return input
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
            .trim()
    }

    /**
     * Check if text contains potentially dangerous content
     */
    fun containsDangerousContent(input: String): Boolean {
        return DANGEROUS_CHARS.matcher(input).find()
    }

    /**
     * Extract and validate URLs from text
     */
    fun extractUrls(text: String): List<String> {
        val urls = mutableListOf<String>()
        val matcher = URL_PATTERN.matcher(text)
        while (matcher.find()) {
            urls.add(matcher.group())
        }
        return urls
    }

    /**
     * Check if URL is potentially malicious
     */
    fun isUrlSafe(url: String): Boolean {
        val lowerUrl = url.lowercase()

        // Block common malicious patterns
        val dangerousPatterns = listOf(
            "javascript:",
            "data:",
            "vbscript:",
            "file://",
            ".exe",
            ".bat",
            ".cmd",
            ".scr",
            ".pif"
        )

        return dangerousPatterns.none { lowerUrl.contains(it) }
    }

    // ========================================
    // EMOJI HANDLING
    // ========================================

    /**
     * Check if string contains emojis
     */
    fun containsEmoji(text: String): Boolean {
        return EMOJI_PATTERN.matcher(text).find()
    }

    /**
     * Count emojis in text
     */
    fun countEmojis(text: String): Int {
        val matcher = EMOJI_PATTERN.matcher(text)
        var count = 0
        while (matcher.find()) count++
        return count
    }

    // ========================================
    // GENERAL VALIDATION HELPERS
    // ========================================

    /**
     * Validate that a required field is not empty
     */
    fun validateRequired(value: String?, fieldName: String): ValidationResult {
        return if (value.isNullOrBlank()) {
            ValidationResult.invalid("$fieldName is required")
        } else {
            ValidationResult.valid(value.trim())
        }
    }

    /**
     * Validate string length
     */
    fun validateLength(
        value: String,
        minLength: Int = 0,
        maxLength: Int = Int.MAX_VALUE,
        fieldName: String = "Field"
    ): ValidationResult {
        return when {
            value.length < minLength -> ValidationResult.invalid("$fieldName must be at least $minLength characters")
            value.length > maxLength -> ValidationResult.invalid("$fieldName cannot exceed $maxLength characters")
            else -> ValidationResult.valid(value)
        }
    }

    /**
     * Combine multiple validation results
     */
    fun combineValidations(vararg results: ValidationResult): ValidationResult {
        val firstError = results.firstOrNull { !it.isValid }
        return firstError ?: ValidationResult.valid()
    }
}

/**
 * Extension function to validate and get result
 */
inline fun String.validateAs(validator: (String) -> InputValidation.ValidationResult): InputValidation.ValidationResult {
    return validator(this)
}

/**
 * Extension to check if phone number is valid
 */
fun String.isValidPhoneNumber(): Boolean {
    return InputValidation.validatePhoneNumber(this).isValid
}

/**
 * Extension to check if message is valid
 */
fun String.isValidMessage(): Boolean {
    return InputValidation.validateMessage(this).isValid
}
