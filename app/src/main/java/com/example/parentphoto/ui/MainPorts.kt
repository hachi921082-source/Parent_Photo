package com.example.parentphoto.ui

import com.example.parentphoto.mail.ItemId
import com.example.parentphoto.mail.MailResult
import com.example.parentphoto.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.io.File

/*
 * MainViewModel が使う外部への窓口。
 * 実体(SettingsRepository / OutboxRepository / CameraController / CameraPermission)は
 * Android や Room に依存するため、JVM テストでは偽物に差し替える。本番の実装は MainViewModelFactory.kt。
 */

/** 設定の読み取り(ds 8.1 / 8.3) */
interface SendFlowSettings {
    /** 最初の発行が Loading 終了の条件の1つ */
    val settings: Flow<Settings>

    /** SetupRequired の判定。パスワードの復号を含むので suspend */
    suspend fun isSetupRequired(settings: Settings): Boolean
}

/** 未送信キューのうち画面が使う部分(outbox-queue PLAN.md 2) */
interface SendFlowOutbox {
    /** 待機中の件数(要確認を含まない)。OutboxFull とバッジ「未送信 N件」 */
    val activeCount: Flow<Int>

    /** 要確認の件数。バッジ「送れていません M件」 */
    val attentionCount: Flow<Int>

    /** 起動時の復旧が終わったら true。終わるまで Loading */
    val recoveryDone: Flow<Boolean>

    /** プレビュー中の写真が待機枠を1つ予約しているか(ds 2.4)。繰り上げの判定に使われる */
    fun setPreviewExists(exists: Boolean)

    /**
     * 送り先タップで項目を作る(段階 CANCEL_WINDOW、写真は outbox にコピー、ds 7.3)。
     * 待機中が5件以上なら OutboxFullException
     */
    suspend fun createItem(recipientId: String, capturedAtMillis: Long, previewFile: File): ItemId

    /** 取り消す: 行と outbox のファイルを消す(cache の写真は残す) */
    suspend fun deleteItem(itemId: ItemId)

    /** 猶予が終わり前面送信を始める(段階 SENDING_FG) */
    suspend fun startForegroundSend(itemId: ItemId)

    /** 前面送信(回線が明らかに無ければ試行せず Offline、ds 6.2)。待つ上限は呼び出し側の 60 秒 */
    suspend fun sendForeground(itemId: ItemId): MailResult

    /** 成功: 行とファイルを消して SENT を記録する */
    suspend fun markSent(itemId: ItemId)

    /** 失敗・回線なし・時間切れ: 再送待ちか要確認にし、ワーカーを登録する */
    suspend fun markQueued(itemId: ItemId, result: MailResult)

    /**
     * 画面が閉じられた時に猶予中・送信中だった項目を、アプリ全体のスコープで送信待ちにしてすぐ再送させる(Q69)。
     * 画面のスコープは終わるので、呼んだら待たずに戻る
     */
    fun requeueAbandoned(itemId: ItemId)
}

/** 回線の状態(ds 3.4 の接続表示)。判定できないときは true(オンライン)に倒す */
interface ConnectivitySource {
    /** 通信できる回線があるとき true。購読の最初に今の状態を流す */
    val online: Flow<Boolean>
}

/** 常にオンライン(テストや判定手段が無い場合の既定) */
object AlwaysOnline : ConnectivitySource {
    override val online: Flow<Boolean> = flowOf(true)
}

/** 撮影(ds 4.2 / 4.3)。成功時は縮小済みの preview.jpg を返す */
interface PhotoCapturer {
    suspend fun capture(): Result<File>

    /** cache/preview.jpg と cache/capture_raw.jpg を消す。無くても失敗しない */
    fun deleteFiles()
}

/** カメラ権限(ds 2.2 / 4.4)。要求済みフラグはコールドスタート1回分 */
interface CameraPermissionGate {
    fun isGranted(): Boolean

    /** 未許可で、このコールドスタートでまだ要求していないとき true */
    fun shouldRequest(): Boolean

    /** システムダイアログを出す直前に呼ぶ */
    fun markRequested()
}
