package com.example.parentphoto

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

/**
 * アプリ全体の Application クラス。
 * 起動時に通知チャンネルを作成する。Room / WorkManager の遅延初期化は後続の機能で追加する。
 */
class ParentPhotoApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createAttentionChannel()
    }

    // 送れなかった写真の通知用チャンネル。同じIDでの再作成は何も起きないので毎回呼んでよい
    private fun createAttentionChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ATTENTION,
            getString(R.string.channel_attention_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ATTENTION = "attention"
    }
}
