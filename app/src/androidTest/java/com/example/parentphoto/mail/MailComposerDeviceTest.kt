package com.example.parentphoto.mail

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.parentphoto.settings.Settings
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties
import javax.mail.Session

/** 実機/エミュレータ上で、2枚添付のメッセージが multipart/mixed としてシリアライズできることを確認する(送信はしない)。 */
@RunWith(AndroidJUnit4::class)
class MailComposerDeviceTest {
    @Test
    fun writesTwoAttachments() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val f = File(dir, "mail_test.jpg").apply { writeBytes(ByteArray(2048) { it.toByte() }) }
        val msg = MailComposer().compose(
            Session.getInstance(Properties()),
            Settings(senderName = "お父さん", smtpUser = "f@example.com"),
            MailRequest("a@example.com", listOf(MailPhoto(f, 1_000_000_000_000L), MailPhoto(f, 1_000_000_060_000L))),
        )
        val out = ByteArrayOutputStream()
        msg.writeTo(out)
        val raw = out.toString("ISO-8859-1")
        assertTrue(raw.contains("multipart/mixed"))
        assertTrue(raw.contains("image/jpeg"))
        assertTrue(raw.contains("Content-Transfer-Encoding: base64"))
        f.delete()
    }
}
