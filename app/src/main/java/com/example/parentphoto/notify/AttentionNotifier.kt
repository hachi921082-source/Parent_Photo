package com.example.parentphoto.notify

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.parentphoto.ParentPhotoApp
import com.example.parentphoto.R
import com.example.parentphoto.ui.MainActivity

/** 要確認通知の出し入れ(outbox-queue から呼ぶ。テストでは偽物に差し替える)。 */
interface AttentionAlerter {
    fun show(overflowDeleted: Boolean)
    fun cancel()
}

/**
 * 「要確認」になった写真の通知(ds 10.1)。
 * 固定ID 1001 で毎回置き換える(積み重ねない)。本文は最新のイベントの文言になる。
 * 通知権限が未許可でも例外にならない(通知が出ないだけ)。
 */
class AttentionNotifier(private val context: Context) : AttentionAlerter {

    /** overflowDeleted が true なら「古い写真を削除した」文言にする */
    override fun show(overflowDeleted: Boolean) {
        val text = context.getString(
            if (overflowDeleted) R.string.notif_attention_overflow else R.string.notif_attention,
        )
        val open = Intent(context, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        val pending = PendingIntent.getActivity(
            context, 0, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // タイトルなし。本文のみ(技術的な詳細は出さない)
        val notification = NotificationCompat.Builder(context, ParentPhotoApp.CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_stat_photo)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setContentIntent(pending)
            .build()
        try {
            manager().notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // 権限なしは何もしない(バッジのみ表示)
        }
    }

    /** 要確認が0件になったとき */
    override fun cancel() {
        manager().cancel(NOTIFICATION_ID)
    }

    private fun manager() = context.getSystemService(NotificationManager::class.java)

    companion object {
        const val NOTIFICATION_ID = 1001
    }
}
