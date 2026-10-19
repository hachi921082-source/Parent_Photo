package com.example.parentphoto.spike

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.Random
import java.util.TimeZone
import javax.activation.DataHandler
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.util.ByteArrayDataSource

/**
 * SMTP送信スパイク用の単一画面。ボタン押下、または intent extra "autosend"(1=添付1枚, 2=添付2枚)で送信する。
 * 結果は画面と Logcat(タグ SpikeResult)に出す。パスワードは出力しない。
 */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { text = "ready"; textSize = 18f }
        val b1 = Button(this).apply { text = "send"; setOnClickListener { run(1) } }
        val b2 = Button(this).apply { text = "send (2 attachments)"; setOnClickListener { run(2) } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
            addView(b1); addView(b2); addView(status)
        })
        val auto = intent.getIntExtra("autosend", 0)
        if (auto > 0) run(auto)
    }

    private fun run(attachments: Int) {
        status.text = "sending..."
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    send(attachments)
                    "OK attachments=$attachments"
                } catch (t: Throwable) {
                    "FAIL attachments=$attachments ${t.javaClass.name}: ${t.message}"
                }
            }
            status.text = result
            Log.i("SpikeResult", "RESULT $result sdk=${android.os.Build.VERSION.SDK_INT}")
        }
    }

    // 約300KBのサンプルJPEGを実行時に生成する(ノイズ入りビットマップ)
    private fun sampleJpeg(seed: Long): ByteArray {
        val w = 1280
        val h = 960
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint()
        val r = Random(seed)
        c.drawColor(Color.rgb(r.nextInt(256), r.nextInt(256), r.nextInt(256)))
        for (i in 0 until 3000) {
            p.color = Color.rgb(r.nextInt(256), r.nextInt(256), r.nextInt(256))
            val x = r.nextInt(w)
            val y = r.nextInt(h)
            c.drawRect(x.toFloat(), y.toFloat(), (x + 4 + r.nextInt(40)).toFloat(), (y + 4 + r.nextInt(40)).toFloat(), p)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }

    private fun send(attachments: Int) {
        val props = Properties().apply {
            put("mail.smtp.host", "smtp.gmail.com")
            put("mail.smtp.port", "465")
            put("mail.smtp.ssl.enable", "true")
            put("mail.smtp.auth", "true")
            put("mail.smtp.ssl.checkserveridentity", "true")
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.timeout", "30000")
            put("mail.smtp.writetimeout", "30000")
        }
        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() =
                PasswordAuthentication(BuildConfig.SMTP_USER, BuildConfig.SMTP_PASSWORD)
        })
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Tokyo") }
        val now = Date()
        val stamp = fmt.format(now)
        val buildType = if (BuildConfig.DEBUG) "debug" else "release"
        val msg = MimeMessage(session).apply {
            setFrom(InternetAddress(BuildConfig.SMTP_USER, "スパイク送信", "UTF-8"))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(BuildConfig.SMTP_TO))
            setSubject("[SPIKE] 写真テスト $stamp ($buildType API${android.os.Build.VERSION.SDK_INT} 添付$attachments)", "UTF-8")
        }
        val mp = MimeMultipart("mixed")
        mp.addBodyPart(MimeBodyPart().apply {
            setText("SMTP送信スパイクです。JPEG添付が開けるか確認してください。\n日本語本文テスト。", "UTF-8")
        })
        for (i in 0 until attachments) {
            // 2枚目も同名で添付し、同名ファイルの扱いを確認する
            val bytes = sampleJpeg(1000L + i)
            mp.addBodyPart(MimeBodyPart().apply {
                dataHandler = DataHandler(ByteArrayDataSource(bytes, "image/jpeg"))
                fileName = "$stamp.jpg"
            })
            Log.i("SpikeResult", "attachment ${i + 1} bytes=${bytes.size}")
        }
        msg.setContent(mp)
        msg.sentDate = now
        // ネットワーク前にMIME直列化だけ行い、DataContentHandler/mailcapの解決をR8ビルドで検証する
        val dry = ByteArrayOutputStream()
        msg.writeTo(dry)
        Log.i("SpikeResult", "SERIALIZE_OK bytes=${dry.size()}")
        Transport.send(msg)
    }
}
