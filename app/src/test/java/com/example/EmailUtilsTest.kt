package com.example

import com.example.util.EmailUtils
import org.junit.Assert.assertEquals
import org.junit.Test

class EmailUtilsTest {

    @Test
    fun `test standard gmail address display`() {
        val result = EmailUtils.formatDisplayGmail("bhandekunal16@gmail.com")
        assertEquals("bhandekunal16@gmail.com", result)
    }

    @Test
    fun `test gmail address with trailing suffixes or identifiers`() {
        assertEquals("bhandekunal16@gmail.com", EmailUtils.formatDisplayGmail("bhandekunal16@gmail.com_user123"))
        assertEquals("bhandekunal16@gmail.com", EmailUtils.formatDisplayGmail("bhandekunal16@gmail.com#session"))
        assertEquals("bhandekunal16@gmail.com", EmailUtils.formatDisplayGmail("bhandekunal16@gmail.com?id=999"))
        assertEquals("bhandekunal16@gmail.com", EmailUtils.formatDisplayGmail("bhandekunal16@gmail.com+suffix"))
        assertEquals("bhandekunal16@gmail.com", EmailUtils.formatDisplayGmail("bhandekunal16@gmail.com:token"))
        assertEquals("bhandekunal16@gmail.com", EmailUtils.formatDisplayGmail("bhandekunal16@gmail.com-485616296574"))
    }

    @Test
    fun `test case insensitive matching of gmail domain`() {
        assertEquals("bhandekunal16@gmail.com", EmailUtils.formatDisplayGmail("bhandekunal16@GMAIL.COM"))
        assertEquals("bhandekunal16@gmail.com", EmailUtils.formatDisplayGmail("bhandekunal16@Gmail.Com_extra"))
    }

    @Test
    fun `test whitespace trimming`() {
        assertEquals("bhandekunal16@gmail.com", EmailUtils.formatDisplayGmail("   bhandekunal16@gmail.com   "))
    }

    @Test
    fun `test null and blank inputs with fallback`() {
        assertEquals("", EmailUtils.formatDisplayGmail(null))
        assertEquals("", EmailUtils.formatDisplayGmail(""))
        assertEquals("", EmailUtils.formatDisplayGmail("   "))
        assertEquals("Guest User", EmailUtils.formatDisplayGmail(null, fallback = "Guest User"))
        assertEquals("Guest User", EmailUtils.formatDisplayGmail("", fallback = "Guest User"))
    }

    @Test
    fun `test prompt specific user email`() {
        assertEquals("roboticdev07@gmail.com", EmailUtils.formatDisplayGmail("roboticdev07@gmail.com"))
        assertEquals("roboticdev07@gmail.com", EmailUtils.formatDisplayGmail("roboticdev07@gmail.com_xyz"))
    }
}
