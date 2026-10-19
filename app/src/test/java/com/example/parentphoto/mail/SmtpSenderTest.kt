package com.example.parentphoto.mail

import com.example.parentphoto.settings.Recipient
import com.example.parentphoto.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Properties
import java.util.concurrent.CountDownLatch
import javax.mail.Session
import javax.mail.internet.MimeMessage

@OptIn(ExperimentalCoroutinesApi::class)
class SmtpSenderTest {
    private class FakeSettings(var value: Settings, var password: String? = "pw") : SmtpSettingsSource {
        val flow = MutableStateFlow(value)
        override val settings: Flow<Settings> get() = flow
        override suspend fun readPassword(): String? = password
    }

    private class FakeLog : SendLogWriter {
        val lines = mutableListOf<Triple<SendLogResult, String?, String?>>()
        override suspend fun record(result: SendLogResult, recipientName: String?, code: String?) {
            lines.add(Triple(result, recipientName, code))
        }
    }

    private val file = File.createTempFile("photo", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    private val recipient = Recipient("r1", "太郎", "taro@example.com")
    private val base = Settings(smtpUser = "f@gmail.com", recipients = listOf(recipient))
    private val log = FakeLog()
    private val sessions = mutableListOf<List<Any>>()

    private val factory = SessionFactory { host, port, user, pw ->
        sessions.add(listOf(host, port, user, pw))
        Session.getInstance(Properties())
    }

    private fun source(item: SendableItem? = SendableItem("r1", file, 1_792_406_165_000L)) =
        object : SendSource {
            override suspend fun loadForeground(itemId: ItemId) = item
        }

    private fun sender(
        s: FakeSettings,
        offline: Boolean = false,
        src: SendSource = source(),
        transport: MailTransport = MailTransport { },
        composer: MailComposer = MailComposer(),
        timeoutMs: Long = 60_000,
    ) = SmtpSender(s, { offline }, composer, src, log, Dispatchers.IO, factory, transport, timeoutMs)

    @Test
    fun foregroundSuccess() = runBlocking {
        var sent: MimeMessage? = null
        val r = sender(FakeSettings(base), transport = { sent = it }).sendForeground(1)
        assertEquals(MailResult.Success, r)
        assertEquals("写真が届きました", sent!!.subject)
        assertEquals(listOf<Any>("smtp.gmail.com", 465, "f@gmail.com", "pw"), sessions[0])
        assertTrue(log.lines.isEmpty())
    }

    @Test
    fun offlineShortcutSkipsSessionAndCompose() = runBlocking {
        val r = sender(FakeSettings(base), offline = true).sendForeground(1)
        assertEquals(MailResult.Offline, r)
        assertTrue(sessions.isEmpty())
    }

    @Test
    fun recipientMissing() = runBlocking {
        assertEquals(
            MailResult.RecipientMissing,
            sender(FakeSettings(base.copy(recipients = emptyList()))).sendForeground(1),
        )
        assertEquals(MailResult.RecipientMissing, sender(FakeSettings(base), src = source(null)).sendForeground(1))
    }

    @Test
    fun missingPasswordIsAuthFailureWithoutConnecting() = runBlocking {
        val r = sender(FakeSettings(base, password = null)).sendForeground(1) as MailResult.Failure
        assertEquals(FailureKind.AUTH, r.kind)
        assertTrue(sessions.isEmpty())
    }

    @Test
    fun transportFailureIsClassified() = runBlocking {
        val r = sender(FakeSettings(base), transport = { throw java.net.UnknownHostException("h") })
            .sendForeground(1) as MailResult.Failure
        assertEquals(MailCode.NETWORK, r.code)
    }

    @Test
    fun missingPhotoFileIsClassifiedNotThrown() = runBlocking {
        val gone = SendableItem("r1", File("/nonexistent/x.jpg"), 0L)
        val r = sender(FakeSettings(base), src = source(gone), transport = { it.writeTo(java.io.OutputStream.nullOutputStream()) })
            .sendForeground(1)
        assertTrue(r is MailResult.Failure)
    }

    @Test
    fun timeoutYieldsTransientTimeout() = runBlocking {
        val latch = CountDownLatch(1)
        val r = sender(FakeSettings(base), transport = { latch.await() }, timeoutMs = 300).sendBatch(
            MailRequest("a@b.com", listOf(MailPhoto(file, 0L))),
        ) as MailResult.Failure
        latch.countDown()
        assertEquals(FailureKind.TRANSIENT, r.kind)
        assertEquals(MailCode.TIMEOUT, r.code)
    }

    @Test
    fun cancellationPropagates() = runBlocking {
        val latch = CountDownLatch(1)
        val job = async(Dispatchers.Default) {
            sender(FakeSettings(base), transport = { latch.await() }, timeoutMs = 10 * 60_000)
                .sendBatch(MailRequest("a@b.com", listOf(MailPhoto(file, 0L))))
        }
        kotlinx.coroutines.delay(100)
        job.cancelAndJoin()
        latch.countDown()
        assertTrue(job.isCancelled)
    }

    @Test
    fun settingsReadEveryCall() = runBlocking {
        val fs = FakeSettings(base)
        val s = sender(fs)
        s.sendForeground(1)
        fs.flow.value = base.copy(smtpHost = "other.example.com", smtpPort = 587)
        s.sendForeground(1)
        assertEquals("smtp.gmail.com", sessions[0][0])
        assertEquals("other.example.com", sessions[1][0])
        assertEquals(587, sessions[1][1])
    }

    @Test
    fun sendTestWritesLog() = runBlocking {
        assertEquals(MailResult.Success, sender(FakeSettings(base)).sendTest(recipient))
        assertEquals(Triple(SendLogResult.TEST_OK, "太郎", null as String?), log.lines[0])
        sender(FakeSettings(base), transport = { throw java.net.SocketTimeoutException() }).sendTest(recipient)
        assertEquals(Triple(SendLogResult.TEST_FAILED, "太郎", "TIMEOUT"), log.lines[1])
    }

    @Test
    fun sessionPropertiesByPort() {
        val p465 = DefaultSessionFactory.properties("h", 465)
        assertEquals("true", p465["mail.smtp.ssl.enable"])
        assertFalse(p465.containsKey("mail.smtp.starttls.enable"))
        val p587 = DefaultSessionFactory.properties("h", 587)
        assertEquals("true", p587["mail.smtp.starttls.required"])
        assertFalse(p587.containsKey("mail.smtp.ssl.enable"))
        assertEquals("15000", p587["mail.smtp.connectiontimeout"])
        assertEquals("30000", p587["mail.smtp.timeout"])
    }
}
