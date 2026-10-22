package com.example.parentphoto.util

/** アプリ全体で共有する定数(ds 1 で確定済みの値)。 */
object Constants {
    // 送信画面・取り消し猶予
    const val UNDO_SECONDS = 3
    const val SENT_SCREEN_MS = 2000L
    const val QUEUED_NOTICE_MS = 2000L
    const val FG_SEND_TIMEOUT_MS = 60_000L
    const val CAPTURE_FAILURE_MESSAGE_MS = 3000L
    // Preview 中に満杯で送り先を拒否した時の案内の表示時間(Q65)
    const val OUTBOX_FULL_NOTICE_MS = 3000L
    // 「未送信 N件」バッジのタップで回線が無い時の案内の表示時間(badge-tap-retry)
    const val OFFLINE_NOTICE_MS = 3000L

    // SMTPタイムアウト(ミリ秒)
    const val SMTP_CONNECT_TIMEOUT_MS = 15_000
    const val SMTP_READ_TIMEOUT_MS = 30_000
    const val SMTP_WRITE_TIMEOUT_MS = 30_000

    // 未送信キューの上限・再送
    const val ACTIVE_CAP = 5
    const val ATTENTION_CAP = 10
    const val RETRY_WINDOW_MS = 24L * 60 * 60 * 1000
    const val BACKOFF_INITIAL_MIN = 15L
    const val BATCH_MAX_BYTES = 15L * 1024 * 1024
    const val SEND_LOG_MAX = 50

    // 再送ワーカーの一意ワーク名とタグ(outbox-worker PLAN-2 1)。実行中に再開されたときは後続ワークを使う
    const val WORK_OUTBOX_SEND = "outbox-send"
    const val WORK_OUTBOX_SEND_NEXT = "outbox-send-next"
    const val WORK_TAG_OUTBOX = "outbox"

    // 設定画面
    const val LONG_PRESS_MS = 3000L
    const val RECIPIENT_CAP = 6
    const val RECIPIENT_NAME_MAX = 10
    const val SENDER_NAME_MAX = 20
    const val HOST_MAX = 253
    const val EMAIL_MAX = 254
}
