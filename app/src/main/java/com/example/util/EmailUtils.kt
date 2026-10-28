package com.example.util

object EmailUtils {
    private const val GMAIL_DOMAIN = "@gmail.com"

    /**
     * Formats an email address to display strictly from the beginning up to and
     * including "@gmail.com".
     *
     * Any extra characters, identifiers, suffixes, or trailing content appearing
     * after "@gmail.com" (such as query parameters, session suffixes, or tags) are
     * cleanly stripped from the UI display.
     *
     * Example:
     * - "bhandekunal16@gmail.com" -> "bhandekunal16@gmail.com"
     * - "bhandekunal16@gmail.com_user123" -> "bhandekunal16@gmail.com"
     * - "bhandekunal16@gmail.com#session" -> "bhandekunal16@gmail.com"
     * - "bhandekunal16@GMAIL.COM+suffix" -> "bhandekunal16@gmail.com"
     * - null / empty -> fallback
     */
    fun formatDisplayGmail(email: String?, fallback: String = ""): String {
        if (email.isNullOrBlank()) return fallback
        val trimmed = email.trim()
        val index = trimmed.indexOf(GMAIL_DOMAIN, ignoreCase = true)
        return if (index != -1) {
            trimmed.substring(0, index) + GMAIL_DOMAIN
        } else {
            trimmed
        }
    }
}
