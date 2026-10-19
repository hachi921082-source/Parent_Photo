package com.example.parentphoto.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

class ValidatorsTest {
    // JVM上ではandroid.util.Patternsが使えないため、簡易パターンを差し替える
    private val pat: Pattern = Pattern.compile("[^@ ]+@[^@ ]+[.][^@ ]+")

    @Test fun email_trimmedAndChecked() {
        assertNull(Validators.validateEmail("  a@b.com  ", pat))
        assertEquals(ValidationError.EMAIL_FORMAT, Validators.validateEmail("a@b", pat))
        assertEquals(ValidationError.EMAIL_FORMAT, Validators.validateEmail("", pat))
    }

    @Test fun recipientName() {
        assertEquals(ValidationError.NAME_EMPTY, Validators.validateRecipientName("  "))
        assertNull(Validators.validateRecipientName(" 一二三四五六七八九十 "))
        assertEquals(ValidationError.NAME_TOO_LONG, Validators.validateRecipientName("一二三四五六七八九十一"))
    }

    @Test fun password_whitespaceStripped() {
        val raw = "abcd efgh\nijkl\tmnop "
        assertEquals("abcdefghijklmnop", Validators.normalizePassword(raw))
        assertNull(Validators.validatePassword(raw))
        assertEquals(ValidationError.PASSWORD_LENGTH, Validators.validatePassword("abcdefghijklmno"))
        assertEquals(ValidationError.PASSWORD_LENGTH, Validators.validatePassword("abcdefghijklmnopq"))
        assertEquals(ValidationError.PASSWORD_LENGTH, Validators.validatePassword(""))
    }

    @Test fun host() {
        assertEquals(ValidationError.HOST_EMPTY, Validators.validateHost("  "))
        assertEquals(ValidationError.HOST_FORMAT, Validators.validateHost("smtp .gmail.com"))
        assertNull(Validators.validateHost(" smtp.gmail.com "))
    }

    @Test fun port() {
        assertNull(Validators.validatePort("465"))
        assertNull(Validators.validatePort("1"))
        assertNull(Validators.validatePort("65535"))
        assertEquals(ValidationError.PORT_RANGE, Validators.validatePort("0"))
        assertEquals(ValidationError.PORT_RANGE, Validators.validatePort("65536"))
        assertEquals(ValidationError.PORT_RANGE, Validators.validatePort("abc"))
        assertEquals(ValidationError.PORT_RANGE, Validators.validatePort(""))
        assertTrue(Validators.isValidPort(465))
        assertFalse(Validators.isValidPort(-1))
    }

    @Test fun senderName_sanitized() {
        assertEquals("お父さん", Validators.sanitizeSenderName("  お父\r\nさん \n"))
        assertEquals("", Validators.sanitizeSenderName(" \r\n "))
    }
}
