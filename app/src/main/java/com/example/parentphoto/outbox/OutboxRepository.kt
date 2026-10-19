package com.example.parentphoto.outbox

import androidx.room.withTransaction
import com.example.parentphoto.mail.FailureKind
import com.example.parentphoto.mail.ItemId
import com.example.parentphoto.mail.MailCode
import com.example.parentphoto.mail.MailResult
import com.example.parentphoto.mail.SendLogResult
import com.example.parentphoto.mail.SendLogWriter
import com.example.parentphoto.mail.SendSource
import com.example.parentphoto.mail.SendableItem
import com.example.parentphoto.notify.AttentionAlerter
import com.example.parentphoto.settings.Settings
import com.example.parentphoto.util.Constants
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 未送信キューの唯一の書き込み窓口(outbox-queue PLAN.md 2)。
 * UI とワーカーは DAO を直接触らず、必ずここを通す。
 * 複数行の変更は1つのトランザクションで行い、ファイル削除はコミット後に行う(失敗しても起動時の掃除で消える)。
 *
 * PLAN-1 の範囲: 保存・件数・項目作成・前面送信からの呼び出し・送信ログ・ワーカー用の取り込み/完了処理。
 * 要確認の上限と通知・起動時の復旧・送り先削除は PLAN-2、再開・繰り上げ・今すぐ再送は PLAN-3 で追加する。
 */
class OutboxRepository(
    private val db: OutboxDatabase,
    private val files: OutboxFiles,
    private val settings: Flow<Settings>,
    private val scheduler: WorkScheduler,
    // TODO(PLAN-2): 要確認への移行時の show / 0件になったときの cancel で使う
    @Suppress("unused") private val notifier: AttentionAlerter,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SendSource, SendLogWriter {

    private val dao = db.outboxDao()
    private val logDao = db.sendLogDao()

    // ---- 監視 ----

    /** 待機中の件数(要確認は含まない、ds 2.4)。 */
    val activeCount: Flow<Int> = dao.activeCount().distinctUntilChanged()

    val attentionCount: Flow<Int> = dao.attentionCount().distinctUntilChanged()

    /** 未送信一覧。送り先名は設定から引き、見つからなければ null(削除された宛先)。 */
    val items: Flow<List<OutboxItemView>> = combine(dao.observeAll(), settings) { rows, s ->
        val names = s.recipients.associate { it.id to it.name }
        rows.map { it.toView(names[it.recipientId]) }
    }

    /** 送信ログ。新しい順に最大50件。 */
    val sendLog: Flow<List<SendLogEntry>> = logDao.observeNewest(Constants.SEND_LOG_MAX).map { rows ->
        rows.map { SendLogEntry(it.id, it.timeMillis, it.recipientName, it.result, it.code) }
    }

    private val previewExistsState = MutableStateFlow(false)

    /** プレビュー中の写真が枠を1つ予約しているか。繰り上げ(PLAN-3)だけが読む。 */
    val previewExists: StateFlow<Boolean> = previewExistsState.asStateFlow()

    /**
     * MainViewModel が公開する。Preview 中は true。取り消す時は deleteItem の前に true にし、
     * createItem の後に false にする(項目そのものが枠を持つため)。
     */
    fun setPreviewExists(exists: Boolean) {
        previewExistsState.value = exists
    }

    // ---- 父親側の流れ ----

    /**
     * 送り先ボタンを押したときに項目を作る(ds 7.3)。写真を outbox/{id}.jpg にコピーしてから行を入れる。
     * 待機中が5件以上なら OutboxFullException(ファイルは残さない)。preview ファイルは触らない。
     */
    suspend fun createItem(recipientId: String, capturedAtMillis: Long, previewFile: File): ItemId =
        withContext(ioDispatcher) {
            val id = UUID.randomUUID().toString()
            val file = files.importCopy(id, previewFile)
            try {
                db.withTransaction {
                    // 画面側の確認が漏れても6件目は作らない
                    if (dao.activeCountNow() >= Constants.ACTIVE_CAP) throw OutboxFullException()
                    dao.insert(
                        OutboxItemEntity(
                            id = id,
                            recipientId = recipientId,
                            capturedAtMillis = capturedAtMillis,
                            retryStartMillis = clock(),
                            attentionSinceMillis = null,
                            state = ItemState.WAITING,
                            phase = Phase.CANCEL_WINDOW,
                            attentionReason = null,
                            resendPending = false,
                            fileSize = file.length(),
                        ),
                    )
                }
            } catch (e: Throwable) {
                files.delete(id)
                throw e
            }
            OutboxLog.d("created id=$id")
            id
        }

    /** 取り消す(猶予中): 行とファイルを消す。ログには残さない。 */
    suspend fun deleteItem(itemId: ItemId) {
        deleteRowsAndFiles(listOf(itemId))
        OutboxLog.d("undo id=$itemId")
    }

    /** 待機中の行の段階だけを変える(行が無い・要確認なら何もしない)。猶予終了時の SENDING_FG に使う。 */
    suspend fun setPhase(itemId: ItemId, phase: Phase) {
        val n = dao.updatePhase(itemId, phase)
        OutboxLog.d("phase id=$itemId -> $phase updated=$n")
    }

    /** 前面送信の成功: 行とファイルを消し、SENT を記録する。 */
    suspend fun markSent(itemId: ItemId) {
        val row = dao.get(itemId)
        deleteRowsAndFiles(listOf(itemId))
        record(SendLogResult.SENT, row?.let { nameOf(it.recipientId) }, null)
        OutboxLog.d("sent id=$itemId")
    }

    /**
     * 前面送信の失敗・回線なし(ds 2.3 / 6.3)。結果に応じて再送待ちか要確認にする。
     * Room を先に書き、その後にワーカー登録をする。24時間の起点(retryStartMillis)は変えない。
     */
    suspend fun markQueued(itemId: ItemId, result: MailResult) {
        require(result !is MailResult.Success) { "markQueued called with Success" }
        val row = dao.get(itemId)
        if (row == null) {
            OutboxLog.w("markQueued: row missing id=$itemId")
            return
        }
        when (result) {
            MailResult.Offline -> queueForRetry(row, SendLogResult.QUEUED_OFFLINE, null)
            is MailResult.Failure -> when (result.kind) {
                FailureKind.TRANSIENT -> queueForRetry(row, SendLogResult.FAILED, result.code.name)
                FailureKind.AUTH -> {
                    moveToAttention(listOf(itemId), AttentionReason.AUTH)
                    record(SendLogResult.FAILED, nameOf(row.recipientId), result.code.name)
                }
                FailureKind.PERMANENT -> {
                    moveToAttention(listOf(itemId), AttentionReason.PERMANENT)
                    record(SendLogResult.FAILED, nameOf(row.recipientId), result.code.name)
                }
            }
            // メール失敗ではないので FAILED は書かない
            MailResult.RecipientMissing -> moveToAttention(listOf(itemId), AttentionReason.RECIPIENT_DELETED)
            MailResult.Success -> Unit // require で除外済み
        }
    }

    private suspend fun queueForRetry(row: OutboxItemEntity, log: SendLogResult, code: String?) {
        val n = dao.updatePhase(row.id, Phase.QUEUED)
        if (n == 0) {
            OutboxLog.w("queue: not waiting id=${row.id}")
            return
        }
        record(log, nameOf(row.recipientId), code)
        scheduler.enqueueDefault()
        OutboxLog.d("queued id=${row.id} log=$log code=$code")
    }

    // ---- 隠し設定 ----

    /** 送り先削除の確認ダイアログ用。待機中と要確認の合計。 */
    suspend fun countForRecipient(recipientId: String): Int = dao.countForRecipient(recipientId)

    /** 未送信一覧の「削除」: 行とファイルを消し、DELETED_MANUAL を記録する。 */
    suspend fun deleteManual(itemId: ItemId) {
        val row = dao.get(itemId) ?: return
        deleteRowsAndFiles(listOf(itemId))
        record(SendLogResult.DELETED_MANUAL, nameOf(row.recipientId), null)
        OutboxLog.d("deleted manually id=$itemId")
        // TODO(PLAN-2): 要確認が0件になったら通知を消す
    }

    // ---- 前面送信・送信ログ(mail-composition-smtp 2.3) ----

    override suspend fun loadForeground(itemId: ItemId): SendableItem? =
        dao.get(itemId)?.let { SendableItem(it.recipientId, files.file(it.id), it.capturedAtMillis) }

    /** 送信ログを1行書き、新しい50件だけを残す(同じトランザクション)。 */
    override suspend fun record(result: SendLogResult, recipientName: String?, code: String?) {
        db.withTransaction {
            logDao.insert(
                SendLogEntity(timeMillis = clock(), recipientName = recipientName, result = result, code = code),
            )
            logDao.trim(Constants.SEND_LOG_MAX)
        }
    }

    // ---- ワーカー用(outbox-worker のみが使う) ----

    /** 待機中・送信待ちの行の一覧。 */
    suspend fun queuedSnapshot(): List<OutboxItemEntity> = dao.queuedItems()

    /**
     * まだ待機中・送信待ちの行だけを SENDING_BG にして返す(1トランザクション)。
     * 空なら他で状態が変わった。2つのワーカーが同じ写真を送らないための取り込み。
     */
    suspend fun claimBatch(batch: Batch): List<OutboxItemEntity> {
        val ids = batch.items.map { it.id }
        val claimed = db.withTransaction {
            val rows = dao.queuedAmong(ids)
            if (rows.isNotEmpty()) dao.updatePhaseAll(rows.map { it.id }, Phase.SENDING_BG)
            rows
        }
        val byId = claimed.associateBy { it.id }
        // まとめた順(撮影順)を保つ
        return ids.mapNotNull { byId[it]?.copy(phase = Phase.SENDING_BG) }
            .also { OutboxLog.d("claimed ${it.size}/${ids.size}") }
    }

    /** 取り込みを戻す(キャンセル・onStopped)。ログには残さない。 */
    suspend fun releaseBatch(ids: List<ItemId>) {
        dao.updatePhaseAll(ids, Phase.QUEUED)
        OutboxLog.d("released ${ids.size}")
    }

    /** 1通の送信成功: 行とファイルを消し、SENT を1行記録する。 */
    suspend fun finishBatchSent(ids: List<ItemId>, recipientId: String) {
        deleteRowsAndFiles(ids)
        record(SendLogResult.SENT, nameOf(recipientId), null)
        OutboxLog.d("batch sent ${ids.size}")
    }

    /** 1通の一時的な失敗: 送信待ちに戻し、FAILED を1行記録する。 */
    suspend fun finishBatchTransient(ids: List<ItemId>, code: MailCode) {
        val recipientId = dao.getAll(ids).firstOrNull()?.recipientId
        dao.updatePhaseAll(ids, Phase.QUEUED)
        record(SendLogResult.FAILED, recipientId?.let { nameOf(it) }, code.name)
        OutboxLog.d("batch transient ${ids.size} code=$code")
    }

    /** 1通の認証・恒久的な失敗: 要確認にし、FAILED を1行記録する。 */
    suspend fun finishBatchAttention(ids: List<ItemId>, reason: AttentionReason, code: MailCode) {
        val recipientId = dao.getAll(ids).firstOrNull()?.recipientId
        moveToAttention(ids, reason)
        record(SendLogResult.FAILED, recipientId?.let { nameOf(it) }, code.name)
    }

    // ---- 共通処理 ----

    /**
     * 要確認へ移す。
     * TODO(PLAN-2): 要確認の上限(10件)と古い順の自動削除、通知(show)をここに加える。
     * PLAN-1 では状態の書き換えだけを行う仮の実装。
     */
    internal suspend fun moveToAttention(ids: List<ItemId>, reason: AttentionReason) {
        db.withTransaction { dao.setAttention(ids, reason, clock()) }
        OutboxLog.d("attention ${ids.size} reason=$reason")
    }

    /** 行を消してからファイルを消す(PLAN-2 の手動削除・上限削除もこれを使う)。 */
    internal suspend fun deleteRowsAndFiles(ids: List<ItemId>) {
        if (ids.isEmpty()) return
        db.withTransaction { dao.deleteAll(ids) }
        withContext(ioDispatcher) { ids.forEach { files.delete(it) } }
    }

    private suspend fun nameOf(recipientId: String): String? =
        settings.first().recipients.firstOrNull { it.id == recipientId }?.name

    private fun OutboxItemEntity.toView(recipientName: String?) = OutboxItemView(
        id = id,
        recipientId = recipientId,
        recipientName = recipientName,
        capturedAtMillis = capturedAtMillis,
        state = state,
        phase = phase,
        reason = attentionReason,
        file = files.file(id),
    )
}
