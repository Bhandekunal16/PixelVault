package com.example.util

object ValidationUtil {

    // Backend requires valid Gmail address strictly: person@gmail.com
    private val GMAIL_REGEX = Regex("^[a-zA-Z0-9._%+-]+@gmail\\.com$", RegexOption.IGNORE_CASE)

    fun isValidGmail(email: String): Boolean {
        return email.isNotBlank() && GMAIL_REGEX.matches(email.trim())
    }

    fun isValidPassword(password: String): Boolean {
        return password.length >= 8
    }

    fun doPasswordsMatch(pass1: String, pass2: String): Boolean {
        return pass1 == pass2
    }
}
