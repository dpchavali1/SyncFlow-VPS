package com.phoneintegration.app.utils

import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for InputValidation security features
 */
class InputValidationTest {

    @Test
    fun `valid phone number should pass validation`() {
        val result = InputValidation.validatePhoneNumber("1234567890")
        assertTrue("Valid phone should pass", result.isValid)
        assertEquals("Sanitized phone should match", "1234567890", result.sanitizedValue)
    }

    @Test
    fun `invalid phone number should fail validation`() {
        val result = InputValidation.validatePhoneNumber("invalid")
        assertFalse("Invalid phone should fail", result.isValid)
        assertNotNull("Should have error message", result.errorMessage)
    }

    @Test
    fun `valid message should pass validation`() {
        val result = InputValidation.validateMessage("Hello world!")
        assertTrue("Valid message should pass", result.isValid)
        assertEquals("Sanitized message should match", "Hello world!", result.sanitizedValue)
    }

    @Test
    fun `oversized message should fail validation`() {
        val longMessage = "A".repeat(2000) // Exceeds max length
        val result = InputValidation.validateMessage(longMessage)
        assertFalse("Oversized message should fail", result.isValid)
        assertNotNull("Should have error message", result.errorMessage)
    }

    @Test
    fun `valid group name should pass validation`() {
        val result = InputValidation.validateGroupName("My Group")
        assertTrue("Valid group name should pass", result.isValid)
        assertEquals("Sanitized name should match", "My Group", result.sanitizedValue)
    }

    @Test
    fun `empty message should fail validation`() {
        val result = InputValidation.validateMessage("")
        assertFalse("Empty message should fail", result.isValid)
        assertNotNull("Should have error message", result.errorMessage)
    }

    @Test
    fun `whitespace-only message should fail validation`() {
        val result = InputValidation.validateMessage("   ")
        assertFalse("Whitespace message should fail", result.isValid)
        assertNotNull("Should have error message", result.errorMessage)
    }

    @Test
    fun `phone number with special chars should be sanitized`() {
        val result = InputValidation.validatePhoneNumber("(123) 456-7890")
        // Debug what we're actually getting
        if (!result.isValid) {
            println("Phone validation failed: ${result.errorMessage}")
        }
        if (result.sanitizedValue != "1234567890") {
            println("Expected '1234567890' but got '${result.sanitizedValue}'")
        }
        assertTrue("Phone with formatting should pass: ${result.errorMessage}", result.isValid)
        assertEquals("Should be sanitized", "1234567890", result.sanitizedValue)
    }

    @Test
    fun `very short phone number should fail validation`() {
        val result = InputValidation.validatePhoneNumber("123")
        assertFalse("Very short phone should fail", result.isValid)
        assertNotNull("Should have error message", result.errorMessage)
    }

    @Test
    fun `simple phone number should work`() {
        val result = InputValidation.validatePhoneNumber("1234567890")
        assertTrue("Simple phone should pass", result.isValid)
        assertEquals("Should match input", "1234567890", result.sanitizedValue)
    }

    @Test
    fun `email-like input should be treated as phone number if numeric`() {
        val result = InputValidation.validatePhoneNumber("1234567890@domain.com")
        assertFalse("Email-like input should fail", result.isValid)
    }
}