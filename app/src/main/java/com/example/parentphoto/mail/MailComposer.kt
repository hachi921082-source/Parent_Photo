package com.example.parentphoto.mail

import com.example.parentphoto.settings.Settings
import com.example.parentphoto.settings.Validators
import com.example.parentphoto.util.TokyoTime
import java.io.File
import java.util.Date
import javax.activation.DataHandler
import javax.activation.FileDataSource
import javax.mail.Message
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

/** 添付の Content-Type を image/jpeg に固定する FileDataSource。 */
internal class JpegFileDataSource(file: File) : FileDataSource(file) {
    override fun getContentType(): String = "image/jpeg"
}

/**
 * MIMEメッセージの組み立て(ds 5)。接続はしない。
 * パスワードは扱わない。
 */
class MailComposer {

    fun compose(session: Session, settings: Settings, request: MailRequest): MimeMessage {
        require(request.photos.isNotEmpty()) { "photos is empty" }
        val msg = baseMessage(session, settings, request.toAddress)
        val n = request.photos.size
        msg.setSubject(if (n == 1) SUBJECT else "${SUBJECT}（${n}枚）", "UTF-8")

        val sender = Validators.sanitizeSenderName(settings.senderName).ifEmpty { "家族" }
        val lines = buildList {
            add("${sender}さんから写真が届きました。")
            request.photos.forEach { add("撮影日時: ${TokyoTime.formatMinute(it.capturedAtMillis)}") }
        }
        val textPart = MimeBodyPart().apply { setText(lines.joinToString("\n"), "UTF-8") }

        val multipart = MimeMultipart("mixed")
        multipart.addBodyPart(textPart)
        val used = HashMap<String, Int>()
        for (photo in request.photos) {
            val part = MimeBodyPart()
            part.dataHandler = DataHandler(JpegFileDataSource(photo.file))
            part.setHeader("Content-Transfer-Encoding", "base64")
            part.fileName = uniqueName(TokyoTime.formatFileName(photo.capturedAtMillis), used)
            multipart.addBodyPart(part)
        }
        msg.setContent(multipart)
        return msg
    }

    /** テスト送信メール。添付なしの単一 text/plain。 */
    fun composeTest(session: Session, settings: Settings, toAddress: String): MimeMessage {
        val msg = baseMessage(session, settings, toAddress)
        msg.setSubject("テスト送信", "UTF-8")
        msg.setText("これはテスト送信です。", "UTF-8")
        return msg
    }

    private fun baseMessage(session: Session, settings: Settings, toAddress: String): MimeMessage {
        val msg = MimeMessage(session)
        val name = Validators.sanitizeSenderName(settings.senderName)
        msg.setFrom(
            if (name.isEmpty()) InternetAddress(settings.smtpUser) else InternetAddress(settings.smtpUser, name, "UTF-8"),
        )
        msg.setRecipient(Message.RecipientType.TO, InternetAddress(toAddress))
        msg.sentDate = Date()
        return msg
    }

    /** 同名は _2, _3 を拡張子の前に付ける。 */
    private fun uniqueName(base: String, used: MutableMap<String, Int>): String {
        val count = (used[base] ?: 0) + 1
        used[base] = count
        return if (count == 1) "$base.jpg" else "${base}_$count.jpg"
    }

    private companion object {
        const val SUBJECT = "写真が届きました"
    }
}
