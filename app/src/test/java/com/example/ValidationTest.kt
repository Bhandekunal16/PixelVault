package com.example

import com.example.util.ValidationUtil
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationTest {

    @Test
    fun `gmail validation accepts valid gmail addresses`() {
        assertTrue(ValidationUtil.isValidGmail("person@gmail.com"))
        assertTrue(ValidationUtil.isValidGmail("john.doe@gmail.com"))
        assertTrue(ValidationUtil.isValidGmail("user+tag@gmail.com"))
        assertTrue(ValidationUtil.isValidGmail("TEST@GMAIL.COM"))
    }

    @Test
    fun `gmail validation rejects non-gmail domains and invalid formats`() {
        assertFalse(ValidationUtil.isValidGmail("user@yahoo.com"))
        assertFalse(ValidationUtil.isValidGmail("user@outlook.com"))
        assertFalse(ValidationUtil.isValidGmail("user@company.org"))
        assertFalse(ValidationUtil.isValidGmail("invalid-email"))
        assertFalse(ValidationUtil.isValidGmail("@gmail.com"))
        assertFalse(ValidationUtil.isValidGmail("user@gmail"))
        assertFalse(ValidationUtil.isValidGmail(""))
        assertFalse(ValidationUtil.isValidGmail("   "))
    }

    @Test
    fun `password validation enforces minimum 8 characters`() {
        assertTrue(ValidationUtil.isValidPassword("12345678"))
        assertTrue(ValidationUtil.isValidPassword("a-very-long-password-123"))
        assertFalse(ValidationUtil.isValidPassword("1234567"))
        assertFalse(ValidationUtil.isValidPassword("short"))
        assertFalse(ValidationUtil.isValidPassword(""))
    }

    @Test
    fun `password match verification`() {
        assertTrue(ValidationUtil.doPasswordsMatch("secretPassword123", "secretPassword123"))
        assertFalse(ValidationUtil.doPasswordsMatch("secretPassword123", "differentPassword123"))
    }
}
