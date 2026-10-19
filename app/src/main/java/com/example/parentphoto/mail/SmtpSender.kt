package com.example.parentphoto.mail

import com.example.parentphoto.settings.Recipient
import com.example.parentphoto.settings.Settings
import com.example.parentphoto.util.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.MimeMessage

/** 送信が読む設定の窓口。SettingsRepository が実装し、テストでは差し替える。 */
interface SmtpSettingsSource {
    val settings: kotlinx.coroutines.flow.Flow<Settings>
    suspend fun readPassword(): String?
}

/** 実際の送信(テストで差し替える)。ブロッキング。 */
fun interface MailTransport {
    fun send(message: MimeMessage)
}

object DefaultMailTransport : MailTransport {
    override fun send(message: MimeMessage) = Transport.send(message)
}

/**
 * 送信の窓口(ds 6.1, 6.2, 6.4)。設定とパスワードは送信のたびに読む。
 * SENT/FAILED/QUEUED_OFFLINE の送信ログは呼び出し側が書く。sendTest のみ自分で書く。
 */
class SmtpSender(
    private val settings: SmtpSettingsSource,
    private val connectivity: ConnectivityChecker,
    private val composer: MailComposer,
    private val source: SendSource,
    private val sendLog: SendLogWriter,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val sessionFactory: SessionFactory = DefaultSessionFactory,
    private val transport: MailTransport = DefaultMailTransport,
    private val timeoutMs: Long = Constants.FG_SEND_TIMEOUT_MS,
) {
    /** 前面送信。回線が明らかに無ければ試行せず Offline。 */
    suspend fun sendForeground(itemId: ItemId): MailResult {
        val item = source.loadForeground(itemId) ?: return MailResult.RecipientMissing
        val s = settings.settings.first()
        val recipient = s.recipients.firstOrNull { it.id == item.recipientId }
            ?: return MailResult.RecipientMissing
        if (connectivity.isClearlyOffline()) return MailResult.Offline
        val request = MailRequest(recipient.email, listOf(MailPhoto(item.file, item.capturedAtMillis)))
        return attempt(1) { session, st -> composer.compose(session, st, request) }
    }

    /** 再送ワーカー用。回線判定はWorkManagerの制約に任せる。 */
    suspend fun sendBatch(request: MailRequest): MailResult =
        attempt(request.photos.size) { session, st -> composer.compose(session, st, request) }

    /** テスト送信。TEST_OK / TEST_FAILED を自分で記録する。 */
    suspend fun sendTest(recipient: Recipient): MailResult {
        val result = attempt(0) { session, st -> composer.composeTest(session, st, recipient.email) }
        when (result) {
            is MailResult.Success -> sendLog.record(SendLogResult.TEST_OK, recipient.name, null)
            is MailResult.Failure -> sendLog.record(SendLogResult.TEST_FAILED, recipient.name, result.code.name)
            else -> Unit
        }
        return result
    }

    /** 設定読み出し・組み立て・送信を60秒の上限つきで1回行う。 */
    private suspend fun attempt(
        photoCount: Int,
        build: (Session, Settings) -> MimeMessage,
    ): MailResult {
        try {
            val s = settings.settings.first()
            val password = settings.readPassword()
            if (password == null || s.smtpUser.isBlank() || s.smtpHost.isBlank()) {
                return fail(MailResult.Failure(FailureKind.AUTH, MailCode.AUTH, null, "setup invalid", null))
            }
            // Transport.send は中断できないため、別coroutineで走らせ待ち側だけ打ち切る
            val job = kotlinx.coroutines.CoroutineScope(ioDispatcher).async {
                val session = sessionFactory.create(s.smtpHost, s.smtpPort, s.smtpUser, password)
                transport.send(build(session, s))
            }
            try {
                withTimeout(timeoutMs) { job.await() }
            } catch (e: TimeoutCancellationException) {
                // 放棄したスレッドはソケットのタイムアウトで自然に終わる(重複送信は許容、D14)
                return fail(
                    MailResult.Failure(FailureKind.TRANSIENT, MailCode.TIMEOUT, e.javaClass.name, e.message, null),
                )
            } catch (e: CancellationException) {
                job.cancel()
                throw e
            }
            MailLog.success(photoCount)
            return MailResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return fail(ErrorClassifier.classify(e))
        }
    }

    private fun fail(f: MailResult.Failure): MailResult.Failure {
        MailLog.failure(f)
        return f
    }
}
