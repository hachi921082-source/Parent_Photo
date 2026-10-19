package com.example.parentphoto.mail

import com.example.parentphoto.util.Constants
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.PasswordAuthentication
import javax.mail.Session

/** SMTPセッションの生成(ds 6.1)。送信ごとに新規作成し、状態を共有しない。 */
fun interface SessionFactory {
    fun create(host: String, port: Int, user: String, password: String): Session
}

object DefaultSessionFactory : SessionFactory {
    /** ポートに応じたSMTPプロパティ。テストから検証できるよう分離。 */
    internal fun properties(host: String, port: Int): Properties = Properties().apply {
        put("mail.smtp.host", host)
        put("mail.smtp.port", port.toString())
        put("mail.smtp.auth", "true")
        put("mail.smtp.connectiontimeout", Constants.SMTP_CONNECT_TIMEOUT_MS.toString())
        put("mail.smtp.timeout", Constants.SMTP_READ_TIMEOUT_MS.toString())
        put("mail.smtp.writetimeout", Constants.SMTP_WRITE_TIMEOUT_MS.toString())
        put("mail.smtp.ssl.checkserveridentity", "true")
        if (port == 465) {
            put("mail.smtp.ssl.enable", "true")
        } else {
            // 平文では送らない
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.starttls.required", "true")
        }
    }

    override fun create(host: String, port: Int, user: String, password: String): Session =
        Session.getInstance(
            properties(host, port),
            object : Authenticator() {
                override fun getPasswordAuthentication() = PasswordAuthentication(user, password)
            },
        )
}
