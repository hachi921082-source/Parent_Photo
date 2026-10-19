package com.example.parentphoto.mail

import com.example.parentphoto.settings.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties
import java.util.TimeZone
import javax.mail.Session
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

class MailComposerTest {
    private val composer = MailComposer()
    private val session: Session = Session.getInstance(Properties())
    private lateinit var tz: TimeZone
    private lateinit var jpeg: File

    // 2026-10-19 19:36:05 JST
    private val t1 = 1_792_406_165_000L

    @Before
    fun setUp() {
        tz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        jpeg = File.createTempFile("photo", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(tz)
        jpeg.delete()
    }

    private fun settings(name: String = "お父さん") =
        Settings(senderName = name, smtpUser = "father@gmail.com")

    private fun parse(m: MimeMessage): MimeMessage {
        val out = ByteArrayOutputStream()
        m.writeTo(out)
        return MimeMessage(session, ByteArrayInputStream(out.toByteArray()))
    }

    private fun photos(vararg t: Long) = t.map { MailPhoto(jpeg, it) }

    @Test
    fun subjectSingleAndMultiple() {
        val one = parse(composer.compose(session, settings(), MailRequest("a@b.com", photos(t1))))
        assertEquals("写真が届きました", one.subject)
        val two = parse(composer.compose(session, settings(), MailRequest("a@b.com", photos(t1, t1 + 60_000))))
        assertEquals("写真が届きました（2枚）", two.subject)
    }

    @Test
    fun fromAndTo() {
        val m = composer.compose(session, settings("お父さん\r\n"), MailRequest("a@b.com", photos(t1)))
        val raw = ByteArrayOutputStream().also { m.writeTo(it) }.toString("ISO-8859-1")
        assertTrue(raw.contains("From: =?UTF-8?"))
        val p = parse(m)
        assertEquals("お父さん", (p.from[0] as javax.mail.internet.InternetAddress).personal)
        assertEquals("father@gmail.com", (p.from[0] as javax.mail.internet.InternetAddress).address)
        assertEquals("a@b.com", p.getHeader("To")[0])
        assertEquals(null, p.getHeader("Cc"))
        assertEquals(null, p.getHeader("Reply-To"))
    }

    @Test
    fun emptySenderNameUsesAddressOnlyInFromAndKazokuInBody() {
        val m = composer.compose(session, settings(" \r\n "), MailRequest("a@b.com", photos(t1)))
        val p = parse(m)
        assertEquals(null, (p.from[0] as javax.mail.internet.InternetAddress).personal)
        val body = (p.content as MimeMultipart).getBodyPart(0).content as String
        assertTrue(body.startsWith("家族さんから写真が届きました。"))
    }

    @Test
    fun bodyLinesInOrderWithTokyoTime() {
        val m = composer.compose(session, settings(), MailRequest("a@b.com", photos(t1, t1 + 120_000)))
        val body = (parse(m).content as MimeMultipart).getBodyPart(0).content as String
        assertEquals(
            "お父さんさんから写真が届きました。\n撮影日時: 2026/10/19 19:36\n撮影日時: 2026/10/19 19:38",
            body.trimEnd(),
        )
    }

    @Test
    fun attachmentsNamesTypeAndEncoding() {
        val m = composer.compose(session, settings(), MailRequest("a@b.com", photos(t1, t1, t1 + 1000, t1)))
        val mp = parse(m).content as MimeMultipart
        assertEquals(5, mp.count)
        assertTrue(mp.contentType.startsWith("multipart/mixed"))
        val names = (1..4).map { mp.getBodyPart(it).fileName }
        assertEquals(
            listOf("20261019_193605.jpg", "20261019_193605_2.jpg", "20261019_193606.jpg", "20261019_193605_3.jpg"),
            names,
        )
        for (i in 1..4) {
            val part = mp.getBodyPart(i)
            assertTrue(part.contentType.startsWith("image/jpeg"))
            assertEquals("base64", part.getHeader("Content-Transfer-Encoding")[0])
        }
    }

    @Test
    fun testMailHasNoAttachment() {
        val p = parse(composer.composeTest(session, settings(), "a@b.com"))
        assertEquals("テスト送信", p.subject)
        assertTrue(p.contentType.startsWith("text/plain"))
        assertFalse(p.isMimeType("multipart/*"))
        assertEquals("これはテスト送信です。", (p.content as String).trim())
    }
}
