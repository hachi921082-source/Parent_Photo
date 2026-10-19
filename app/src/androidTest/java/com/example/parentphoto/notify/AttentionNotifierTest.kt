package com.example.parentphoto.notify

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.example.parentphoto.ParentPhotoApp
import com.example.parentphoto.R
import com.example.parentphoto.ui.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AttentionNotifierTest {
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val notifier = AttentionNotifier(ctx)

    private fun posted() = nm.activeNotifications.filter { it.id == AttentionNotifier.NOTIFICATION_ID }

    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT).toString()
    private fun Notification.bigText() = extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()

    @Before
    fun setUp() {
        // 通知権限を付与してから始める(API 33 以上で必要)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(ctx.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
        // アプリ起動時の監視(要確認0件で cancel)が投稿直後の通知を消さないよう、起動処理が落ち着くまで待つ
        runBlocking {
            val outbox = (ctx.applicationContext as ParentPhotoApp).outboxRepository
            outbox.awaitRecovery()
            outbox.attentionCount.first()
            delay(300)
        }
        notifier.cancel()
    }

    @After
    fun tearDown() = notifier.cancel()

    @Test
    fun 通常文言で投稿される() {
        notifier.show(false)
        val list = posted()
        assertEquals(1, list.size)
        val n = list[0].notification
        val text = ctx.getString(R.string.notif_attention)
        assertNull(n.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals(text, n.text())
        assertEquals(text, n.bigText())
        assertEquals("android.app.Notification\$BigTextStyle", n.extras.getString(Notification.EXTRA_TEMPLATE))
        assertTrue(n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(0, n.flags and Notification.FLAG_AUTO_CANCEL)
        assertEquals(ParentPhotoApp.CHANNEL_ATTENTION, n.channelId)
    }

    @Test
    fun 二度目の投稿は置き換わり積み重ならない() {
        notifier.show(false)
        notifier.show(true)
        val expected = ctx.getString(R.string.notif_attention_overflow)
        // 通知の反映は非同期なので、最新文言になるまで少し待つ
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline &&
            posted().singleOrNull()?.notification?.text() != expected
        ) Thread.sleep(50)
        val list = posted()
        assertEquals(1, list.size)
        assertEquals(expected, list[0].notification.text())
        assertEquals(expected, list[0].notification.bigText())
    }

    @Test
    fun チャンネル名はstringsから() {
        val ch = nm.getNotificationChannel(ParentPhotoApp.CHANNEL_ATTENTION)
        assertEquals("送信できていない写真", ch.name.toString())
        assertEquals(ctx.getString(R.string.channel_attention_name), ch.name.toString())
    }

    @Test
    fun タップ意図はMainActivity前面化でImmutable() {
        notifier.show(false)
        val pi = posted()[0].notification.contentIntent
        if (android.os.Build.VERSION.SDK_INT >= 31) assertTrue(pi.isImmutable)
        if (android.os.Build.VERSION.SDK_INT >= 31) assertTrue(pi.isActivity)
        // 同じ内容(フラグ込み)のPendingIntentが既に存在し、通知のものと同一であることで確認する
        val probe = Intent(ctx, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        val existing = PendingIntent.getActivity(
            ctx, 0, probe, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        )
        assertNotNull(existing)
        assertEquals(pi, existing)
    }

    @Test
    fun cancelで消える() {
        notifier.show(false)
        assertEquals(1, posted().size)
        notifier.cancel()
        assertTrue(posted().isEmpty())
    }
}
