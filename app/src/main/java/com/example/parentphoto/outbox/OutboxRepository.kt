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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 未送信キューの唯一の書き込み窓口(outbox-queue PLAN.md 2)。
 * UI とワーカーは DAO を直接触らず、必ずここを通す。
 * 複数行の変更は1つのトランザクションで行い、ファイル削除はコミット後に行う(失敗しても起動時の掃除で消える)。
 *
 * PLAN-1: 保存・件数・項目作成・前面送信からの呼び出し・送信ログ・ワーカー用の取り込み/完了処理。
 * PLAN-2: 要確認への移行と上限(10件)・通知・起動時の復旧・送り先削除・期限切れ。
 * 再開・繰り上げ・今すぐ再送は PLAN-3 で追加する。
 *
 * 重複送信について(ds 7.7, D14): 届いたか不明な失敗(DATA後のタイムアウト等)もそのまま再送する。
 * 家族に同じ写真が2通届くことは受け入れ済みであり、重複排除や固定 Message-ID は入れない。
 */
class OutboxRepository(
    private val db: OutboxDatabase,
    private val files: OutboxFiles,
    private val settings: Flow<Settings>,
    private val scheduler: WorkScheduler,
    private val notifier: AttentionAlerter,
    // アプリ全体のスコープ(監視と起動時の復旧を動かす。ワーカーだけが動くプロセスでも働く)
    private val appScope: CoroutineScope,
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

    private val recoveryDoneState = MutableStateFlow(false)

    /** 起動時の復旧が終わったら true。画面は終わるまで Loading、ワーカーは awaitRecovery() で待つ。 */
    val recoveryDone: StateFlow<Boolean> = recoveryDoneState.asStateFlow()

    suspend fun awaitRecovery() {
        recoveryDone.first { it }
    }

    // ---- アプリ起動時(ParentPhotoApp.onCreate から1回だけ呼ぶ) ----

    /** 要確認が0件になったら通知を消す監視を始める(ds 10.1)。起動直後の0での cancel は無害。 */
    fun startObservers(): Job = appScope.launch {
        attentionCount.collect { if (it == 0) notifier.cancel() }
        // TODO(PLAN-3): 枠が空いたときの繰り上げ監視を別の launch で加える
    }

    /** 起動時の復旧を始める。失敗しても recoveryDone は true にする(Loading で止まらないため。次回起動で再試行)。 */
    fun startRecovery(): Job = appScope.launch(ioDispatcher) {
        try {
            recover()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OutboxLog.w("recovery failed: ${e.javaClass.name}")
        } finally {
            recoveryDoneState.value = true
        }
    }

    /**
     * 起動時の復旧(ds 7.6)。プロセス開始時点では処理中の行の持ち主はいないので送信待ちに戻す
     * (取り消し猶予中に落ちた写真も送る)。行の無いファイル(.tmp を含む)とファイルの無い行を消す。
     */
    internal suspend fun recover() {
        val requeued = db.withTransaction { dao.requeueInFlight() }
        val rowIds = dao.allIds().toSet()
        val (orphanFiles, missing) = withContext(ioDispatcher) {
            var deletedFiles = 0
            for (f in files.listFiles()) {
                val name = f.name
                val keep = name.endsWith(JPG) && name.removeSuffix(JPG) in rowIds
                if (!keep && f.delete()) deletedFiles++
            }
            deletedFiles to rowIds.filter { !files.file(it).exists() }
        }
        if (missing.isNotEmpty()) db.withTransaction { dao.deleteAll(missing) }
        val queued = dao.queuedIds().isNotEmpty()
        if (queued) scheduler.enqueueDefault()
        OutboxLog.d("recovered requeued=$requeued orphanFiles=$orphanFiles missingRows=${missing.size} queued=$queued")
        // TODO(PLAN-3): ここで promote() を1回呼ぶ(再起動をまたいだ resendPending の項目を進める)
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
     * Room を先に書き、その後にワーカー登録・通知をする。24時間の起点(retryStartMillis)は変えない。
     * 届いたか不明な失敗も TRANSIENT として再送する(重複排除はしない、ds 7.7)。
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
                FailureKind.AUTH -> moveToAttention(listOf(itemId), AttentionReason.AUTH) {
                    record(SendLogResult.FAILED, nameOf(row.recipientId), result.code.name)
                }
                FailureKind.PERMANENT -> moveToAttention(listOf(itemId), AttentionReason.PERMANENT) {
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

    /**
     * 未送信一覧の「削除」: 行とファイルを消し、DELETED_MANUAL を記録する(ds 7.8)。
     * 猶予中・送信中の行は消さない(画面でもボタンは無効)。最後の要確認なら監視が通知を消す。
     */
    suspend fun deleteManual(itemId: ItemId) {
        val row = db.withTransaction {
            val r = dao.get(itemId)
            if (r == null || (r.state == ItemState.WAITING && r.phase != Phase.QUEUED)) {
                null
            } else {
                dao.delete(itemId)
                r
            }
        }
        if (row == null) {
            OutboxLog.d("deleteManual ignored id=$itemId")
            return
        }
        withContext(ioDispatcher) { files.delete(itemId) }
        record(SendLogResult.DELETED_MANUAL, nameOf(row.recipientId), null)
        OutboxLog.d("deleted manually id=$itemId")
    }

    /**
     * 送り先を削除した後に呼ぶ(SettingsRepository.deleteRecipient の成功後、ds 7.5)。
     * その送り先の送信待ちの行を要確認(RECIPIENT_DELETED)にする。猶予中・送信中の行はそのまま終わらせる。
     * 先に送り先を消しても、送り先なしは全経路で扱えるので途中で落ちても写真は失われない。
     */
    suspend fun onRecipientDeleted(recipientId: String) {
        moveToAttention(AttentionReason.RECIPIENT_DELETED, select = { dao.queuedIdsForRecipient(recipientId) })
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

    // ---- ワーカー用(outbox-worker のみが使う。最初に awaitRecovery() を呼ぶこと) ----

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

    /** 1通の一時的な失敗: 送信待ちに戻し、FAILED を1行記録する(重複の可能性があっても再送する、ds 7.7)。 */
    suspend fun finishBatchTransient(ids: List<ItemId>, code: MailCode) {
        val recipientId = dao.getAll(ids).firstOrNull()?.recipientId
        dao.updatePhaseAll(ids, Phase.QUEUED)
        record(SendLogResult.FAILED, recipientId?.let { nameOf(it) }, code.name)
        OutboxLog.d("batch transient ${ids.size} code=$code")
    }

    /** 1通の認証・恒久的な失敗: 要確認にし、FAILED を1行記録する。 */
    suspend fun finishBatchAttention(ids: List<ItemId>, reason: AttentionReason, code: MailCode) {
        val recipientId = dao.getAll(ids).firstOrNull()?.recipientId
        moveToAttention(ids, reason) {
            record(SendLogResult.FAILED, recipientId?.let { nameOf(it) }, code.name)
        }
    }

    /** 24時間を過ぎた送信待ちを要確認(RETRY_EXPIRED)にする(ds 7.4 手順2)。写真は残す。件数を返す。 */
    suspend fun expireOld(now: Long = clock()): Int =
        moveToAttention(AttentionReason.RETRY_EXPIRED, select = {
            dao.expiredQueuedIds(now - Constants.RETRY_WINDOW_MS)
        }).moved

    /** 送信時に設定が不足している(SetupRequired)とき、送信待ちを全部要確認にする(ds 7.4 手順3)。通知は1回。 */
    suspend fun markAllQueuedAttention(reason: AttentionReason = AttentionReason.AUTH): Int =
        moveToAttention(reason, select = { dao.queuedIds() }).moved

    /** 送り先が見つからない1通分を要確認(RECIPIENT_DELETED)にする。メール失敗ではないので FAILED は書かない。 */
    suspend fun markRecipientMissing(ids: List<ItemId>) {
        moveToAttention(ids, AttentionReason.RECIPIENT_DELETED)
    }

    // ---- 共通処理 ----

    /** 指定した行のうち、まだ待機中のものを要確認へ移す。 */
    internal suspend fun moveToAttention(
        ids: List<ItemId>,
        reason: AttentionReason,
        onCommitted: suspend () -> Unit = {},
    ): AttentionOutcome = moveToAttention(reason, select = { ids }, onCommitted = onCommitted)

    /**
     * 要確認へ移す(ds 7.5 / 7.8 / 10.1)。select はトランザクション内で対象idを選ぶ。
     * 1. 待機中の行を要確認にし、要確認が10件を超えたら古い順に削除する(1トランザクション)。
     * 2. コミット後に onCommitted(呼び出し元の FAILED ログ)を書き、削除したファイルを消して DELETED_OVERFLOW を記録する
     *    (ログが「FAILED → DELETED_OVERFLOW」の順になる)。
     * 3. 1件でも移したら通知を1回だけ出す(上限削除があればその文言)。
     */
    private suspend fun moveToAttention(
        reason: AttentionReason,
        select: suspend () -> List<ItemId>,
        onCommitted: suspend () -> Unit = {},
    ): AttentionOutcome {
        val now = clock()
        val (moved, deleted) = db.withTransaction {
            val ids = select()
            val moved = if (ids.isEmpty()) 0 else dao.setAttention(ids, reason, now)
            val over = dao.attentionCountNow() - Constants.ATTENTION_CAP
            val deleted = if (moved > 0 && over > 0) dao.oldestAttention(over) else emptyList()
            if (deleted.isNotEmpty()) dao.deleteAll(deleted.map { it.id })
            moved to deleted
        }
        onCommitted()
        if (deleted.isNotEmpty()) {
            withContext(ioDispatcher) { deleted.forEach { files.delete(it.id) } }
            for (row in deleted) record(SendLogResult.DELETED_OVERFLOW, nameOf(row.recipientId), null)
        }
        if (moved > 0) notifier.show(overflowDeleted = deleted.isNotEmpty())
        OutboxLog.d("attention moved=$moved reason=$reason overflowDeleted=${deleted.size}")
        return AttentionOutcome(moved, deleted.size)
    }

    /** 行を消してからファイルを消す。 */
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

    private companion object {
        const val JPG = ".jpg"
    }
}
