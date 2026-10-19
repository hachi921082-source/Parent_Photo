package com.example.parentphoto.outbox

import android.util.Log
import com.example.parentphoto.mail.FailureKind
import com.example.parentphoto.mail.ItemId
import com.example.parentphoto.mail.MailPhoto
import com.example.parentphoto.mail.MailRequest
import com.example.parentphoto.mail.MailResult
import com.example.parentphoto.settings.Recipient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** 再送1通分の送信(実体は SmtpSender.sendBatch。テストで差し替える)。 */
fun interface BatchSender {
    suspend fun sendBatch(request: MailRequest): MailResult
}

/** 1回のワーカー実行の結果。WorkManager の Result に読み替える。 */
enum class RunOutcome { SUCCESS, RETRY }

/**
 * 再送ワーカー1回分の手順(ds 7.4 Run 1-6、outbox-worker PLAN-1 2)。
 * WorkManager に依存しないように分けてあり、OutboxWorker は薄い包みだけを持つ。
 * 行の状態はすべて WorkerOutbox(OutboxRepository)経由で変え、ここでは DB を触らない。
 *
 * 1通送るたびに一覧を取り直すので、実行中に再開された項目も同じ実行で拾う。
 * この実行で一時的に失敗した項目は、同じ実行の中では再送しない(次は WorkManager の再試行)。
 */
class OutboxRunner(
    private val outbox: WorkerOutbox,
    private val sender: BatchSender,
    // 送り先一覧(毎回読み直す。送り先の編集は次の1通から効く)
    private val recipients: suspend () -> List<Recipient>,
    // 送信に必要な設定が欠けているか(ds 8.3)
    private val setupRequired: suspend () -> Boolean,
    private val photoFile: (ItemId) -> File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val gate: Mutex = OutboxWorkerGate.mutex,
) {
    suspend fun run(): RunOutcome {
        // 起動時の復旧が終わるまで行に触らない(これから自分が持つ SENDING_BG を戻されないように)
        outbox.awaitRecovery()
        return gate.withLock { runLocked() }
    }

    private suspend fun runLocked(): RunOutcome {
        WorkerLog.i("run start")
        // この実行で一時的に失敗した項目(同じ実行では再送しない)
        val failedThisRun = mutableSetOf<ItemId>()
        // 取り込めなかった・状態が変わった項目(無限ループ防止)
        val skippedThisRun = mutableSetOf<ItemId>()
        while (true) {
            // 手順2: 24時間を過ぎたものを要確認へ(毎周やっても軽い)
            outbox.expireOld(clock())
            // 手順3: 設定が欠けていれば残りを全部 要確認(AUTH) にして終わる。通知はリポジトリが1回出す
            if (setupRequired()) {
                val moved = outbox.markAllQueuedAttention(AttentionReason.AUTH)
                WorkerLog.i("setup required: moved=$moved")
                return RunOutcome.SUCCESS
            }
            val queued = outbox.queuedSnapshot()
                .filter { it.id !in failedThisRun && it.id !in skippedThisRun }
            // 手順1 / 取り直しで空なら終わり
            if (queued.isEmpty()) break
            // 1周で1通だけ送り、送ったら一覧を取り直す(ds 5 の15MB区切り)
            val batch = BatchPlanner.plan(queued).first()
            val batchIds = batch.items.map { it.id }
            // 手順4: 送り先が見つからなければ 要確認(RECIPIENT_DELETED)。メール失敗ではないので FAILED は書かない
            val recipient = recipients().firstOrNull { it.id == batch.recipientId }
            if (recipient == null) {
                outbox.markRecipientMissing(batchIds)
                skippedThisRun += batchIds
                WorkerLog.i("recipient missing: ${batchIds.size}")
                continue
            }
            sendOne(batch, batchIds, recipient, failedThisRun, skippedThisRun)
        }
        // 手順6: 一時的な失敗の項目がまだ送信待ちなら WorkManager に再試行させる
        val stillQueued = outbox.queuedSnapshot().any { it.id in failedThisRun }
        val outcome = if (stillQueued) RunOutcome.RETRY else RunOutcome.SUCCESS
        WorkerLog.i("run end: $outcome")
        return outcome
    }

    /** 1通分: 取り込み -> 送信 -> 結果の反映。キャンセル時は取り込んだ行を送信待ちに戻して例外を伝える。 */
    private suspend fun sendOne(
        batch: Batch,
        batchIds: List<ItemId>,
        recipient: Recipient,
        failedThisRun: MutableSet<ItemId>,
        skippedThisRun: MutableSet<ItemId>,
    ) {
        // 取り込みの戻り値を受け取る前にキャンセルされても戻せるよう、最初はまとめた全idを戻す対象にする
        // (戻すのは待機中の行の段階だけ。この実行が鍵を持つ間、これらの行を SENDING_BG にするのは自分だけ)
        var toRelease: List<ItemId> = batchIds
        val claimed: List<OutboxItemEntity>
        val result: MailResult
        try {
            claimed = outbox.claimBatch(batch)
            toRelease = claimed.map { it.id }
            if (claimed.isEmpty()) {
                // 他で状態が変わった。この実行では扱わない
                skippedThisRun += batchIds
                return
            }
            currentCoroutineContext().ensureActive()
            WorkerLog.i("send batch size=${claimed.size}")
            result = sender.sendBatch(
                MailRequest(recipient.email, claimed.map { MailPhoto(photoFile(it.id), it.capturedAtMillis) }),
            )
        } catch (e: Throwable) {
            // キャンセル(REPLACE・回線喪失を含む)では送信待ちに戻すだけ。ログ行は書かない
            withContext(NonCancellable) { if (toRelease.isNotEmpty()) outbox.releaseBatch(toRelease) }
            WorkerLog.i("released ${toRelease.size} (${e.javaClass.simpleName})")
            throw e
        }
        val ids = claimed.map { it.id }
        // 結果の反映は途中で止めない(行を中途半端な状態にしない)
        withContext(NonCancellable) {
            when (result) {
                is MailResult.Success -> outbox.finishBatchSent(ids, recipient.id)
                is MailResult.Failure -> when (result.kind) {
                    FailureKind.TRANSIENT -> {
                        outbox.finishBatchTransient(ids, result.code)
                        failedThisRun += ids
                    }
                    FailureKind.AUTH -> outbox.finishBatchAttention(ids, AttentionReason.AUTH, result.code)
                    FailureKind.PERMANENT -> outbox.finishBatchAttention(ids, AttentionReason.PERMANENT, result.code)
                }
                // sendBatch からは返らない。念のため送信待ちに戻し、この実行では再送しない
                MailResult.Offline, MailResult.RecipientMissing -> {
                    outbox.releaseBatch(ids)
                    failedThisRun += ids
                }
            }
        }
        val detail = if (result is MailResult.Failure) "${result.kind} ${result.code}" else result.javaClass.simpleName
        WorkerLog.i("batch result: $detail")
    }
}

/** ワーカーの Logcat 出力(PLAN-1 2)。件数と結果の種類だけを出し、アドレス・パスワードは出さない。 */
internal object WorkerLog {
    const val TAG = "ParentPhoto/Worker"

    fun i(message: String) {
        // JVMテストでは android.util.Log が使えないため握りつぶす
        try {
            Log.i(TAG, message)
        } catch (_: Throwable) {
        }
    }
}
