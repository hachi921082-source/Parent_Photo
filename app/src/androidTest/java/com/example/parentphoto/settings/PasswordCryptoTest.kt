package com.example.parentphoto.settings

import android.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PasswordCryptoTest {
    private val crypto = PasswordCrypto("parent_photo_test_alias")

    @After
    fun tearDown() {
        runCatching { crypto.deleteKey() }
    }

    @Test fun roundTrip() {
        val enc = crypto.encrypt("abcdefghijklmnop")
        assertEquals("abcdefghijklmnop", crypto.decrypt(enc))
    }

    @Test fun randomIv_differentCipherEachTime() {
        assertNotEquals(crypto.encrypt("abcdefghijklmnop"), crypto.encrypt("abcdefghijklmnop"))
    }

    @Test fun layout_ivPlusCiphertextPlusTag() {
        val raw = Base64.decode(crypto.encrypt("abcdefghijklmnop"), Base64.NO_WRAP)
        assertEquals(12 + 16 + 16, raw.size)
    }

    @Test fun corrupted_returnsNull() {
        val raw = Base64.decode(crypto.encrypt("abcdefghijklmnop"), Base64.NO_WRAP)
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 1).toByte()
        assertNull(crypto.decrypt(Base64.encodeToString(raw, Base64.NO_WRAP)))
        assertNull(crypto.decrypt("not base64 !!"))
        assertNull(crypto.decrypt(""))
    }

    @Test fun keyLost_returnsNull() {
        val enc = crypto.encrypt("abcdefghijklmnop")
        crypto.deleteKey()
        assertNull(crypto.decrypt(enc))
    }
}
