package com.example.parentphoto

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.example.parentphoto.notify.AttentionNotifier
import com.example.parentphoto.outbox.NoOpWorkScheduler
import com.example.parentphoto.outbox.OutboxDatabase
import com.example.parentphoto.outbox.OutboxFiles
import com.example.parentphoto.outbox.OutboxRepository
import com.example.parentphoto.settings.SettingsRepository

/**
 * アプリ全体の Application クラス。
 * 起動時に通知チャンネルを作成する。設定・未送信キューはここで1つだけ作る(手動DI、初回使用時に生成)。
 */
class ParentPhotoApp : Application() {

    val settingsRepository: SettingsRepository by lazy { SettingsRepository.create(this) }

    val outboxRepository: OutboxRepository by lazy {
        OutboxRepository(
            db = OutboxDatabase.create(this),
            files = OutboxFiles(filesDir),
            settings = settingsRepository.settings,
            // TODO(outbox-worker): OutboxScheduler に差し替える
            scheduler = NoOpWorkScheduler(),
            notifier = AttentionNotifier(this),
        )
    }

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
