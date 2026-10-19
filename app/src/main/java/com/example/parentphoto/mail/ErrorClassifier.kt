package com.example.parentphoto.mail

import com.sun.mail.smtp.SMTPSendFailedException
import java.io.IOException
import java.net.SocketTimeoutException
import javax.mail.AuthenticationFailedException
import javax.mail.MessagingException
import javax.mail.internet.AddressException

/** 送信例外を ds 6.3 の分類に落とす。純粋関数。 */
object ErrorClassifier {
    private const val MAX_DEPTH = 10
    private val replyRegex = Regex("""(?:^|\s)([245]\d\d)[ -]""")

    fun classify(t: Throwable): MailResult.Failure {
        val chain = collect(t)

        fun failure(kind: FailureKind, code: MailCode, reply: Int? = null) = MailResult.Failure(
            kind, code, t.javaClass.name, t.message, reply,
        )

        // 1. 認証失敗
        if (chain.any { it is AuthenticationFailedException }) {
            return failure(FailureKind.AUTH, MailCode.AUTH, replyOf(chain))
        }
        // 2. SMTP応答コード
        val reply = replyOf(chain)
        if (reply != null) {
            when {
                reply == 530 || reply == 534 || reply == 535 -> return failure(FailureKind.AUTH, MailCode.AUTH, reply)
                reply in 400..499 -> return failure(FailureKind.TRANSIENT, MailCode.NETWORK, reply)
                reply == 550 || reply == 553 -> return failure(FailureKind.PERMANENT, MailCode.REJECTED, reply)
                reply in 500..599 -> return failure(FailureKind.PERMANENT, MailCode.OTHER, reply)
            }
        }
        if (chain.any { it is AddressException }) {
            return failure(FailureKind.PERMANENT, MailCode.REJECTED)
        }
        // 3. タイムアウト
        if (chain.any { it is SocketTimeoutException || it is java.util.concurrent.TimeoutException }) {
            return failure(FailureKind.TRANSIENT, MailCode.TIMEOUT)
        }
        // 4. 回線系(Exception reading response も含む)
        if (chain.any { it is IOException } ||
            chain.any { it is MessagingException && it.message?.startsWith("Exception reading response") == true }
        ) {
            return failure(FailureKind.TRANSIENT, MailCode.NETWORK)
        }
        if (chain.any { it is com.sun.mail.util.MailConnectException }) {
            return failure(FailureKind.TRANSIENT, MailCode.NETWORK)
        }
        // 5. 不明は一時失敗として再送に回す
        return failure(FailureKind.TRANSIENT, MailCode.OTHER)
    }

    /** 原因の連鎖を集める(深さ制限・循環対策つき)。 */
    private fun collect(t: Throwable): List<Throwable> {
        val result = ArrayList<Throwable>()
        val seen = java.util.IdentityHashMap<Throwable, Boolean>()
        var queue = listOf(t)
        var depth = 0
        while (queue.isNotEmpty() && depth < MAX_DEPTH) {
            val next = ArrayList<Throwable>()
            for (e in queue) {
                if (seen.put(e, true) != null) continue
                result.add(e)
                e.cause?.let { next.add(it) }
                if (e is MessagingException) e.nextException?.let { next.add(it) }
            }
            queue = next
            depth++
        }
        return result
    }

    /** 型付き例外、なければメッセージ文中の応答コードを取り出す。 */
    private fun replyOf(chain: List<Throwable>): Int? {
        chain.firstNotNullOfOrNull { (it as? SMTPSendFailedException)?.returnCode }?.let { return it }
        for (e in chain) {
            if (e !is MessagingException) continue
            val m = e.message ?: continue
            replyRegex.find(m)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        }
        return null
    }
}
