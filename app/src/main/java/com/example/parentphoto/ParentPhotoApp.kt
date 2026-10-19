package com.example.parentphoto

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import com.example.parentphoto.mail.ConnectivityChecker
import com.example.parentphoto.mail.MailComposer
import com.example.parentphoto.mail.SmtpSender
import com.example.parentphoto.notify.AttentionNotifier
import com.example.parentphoto.outbox.NoOpWorkScheduler
import com.example.parentphoto.outbox.OutboxDatabase
import com.example.parentphoto.outbox.OutboxFiles
import com.example.parentphoto.outbox.OutboxRepository
import com.example.parentphoto.settings.SettingsRepository
import com.example.parentphoto.util.Connectivity
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * アプリ全体の Application クラス。
 * 起動時に通知チャンネルを作成し、未送信キューの監視と復旧を始める。
 * 設定・未送信キューはここで1つだけ作る(手動DI)。WorkManager が起こしたプロセスでも同じ流れになる。
 */
class ParentPhotoApp : Application() {

    // プロセスと同じ寿命のスコープ。1つの処理が失敗しても他を止めず、取りこぼした例外もログに残すだけでアプリを落とさない
    val appScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            Log.e(TAG_APP, "uncaught in appScope: ${e.javaClass.name}")
        },
    )

    val settingsRepository: SettingsRepository by lazy { SettingsRepository.create(this) }

    val outboxRepository: OutboxRepository by lazy {
        OutboxRepository(
            db = OutboxDatabase.create(this),
            files = OutboxFiles(filesDir),
            settings = settingsRepository.settings,
            // TODO(outbox-worker): OutboxScheduler に差し替える
            scheduler = NoOpWorkScheduler(),
            notifier = AttentionNotifier(this),
            appScope = appScope,
        )
    }

    // 送信の窓口。前面送信と再送ワーカーで同じものを使う(送信元・送信ログは未送信キュー)
    val smtpSender: SmtpSender by lazy {
        SmtpSender(
            settings = settingsRepository,
            connectivity = ConnectivityChecker { Connectivity.isClearlyOffline(this) },
            composer = MailComposer(),
            source = outboxRepository,
            sendLog = outboxRepository,
        )
    }

    override fun onCreate() {
        super.onCreate()
        createAttentionChannel()
        // 要確認0件で通知を消す監視と、起動時の復旧(ds 7.6)。画面とワーカーは recoveryDone を待つ
        outboxRepository.startObservers()
        outboxRepository.startRecovery()
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
        private const val TAG_APP = "ParentPhoto/App"
    }
}
