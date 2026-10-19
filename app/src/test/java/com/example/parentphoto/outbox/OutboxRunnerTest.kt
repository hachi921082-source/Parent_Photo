package com.example.parentphoto.outbox

import com.example.parentphoto.mail.FailureKind
import com.example.parentphoto.mail.ItemId
import com.example.parentphoto.mail.MailCode
import com.example.parentphoto.mail.MailRequest
import com.example.parentphoto.mail.MailResult
import com.example.parentphoto.settings.Recipient
import com.example.parentphoto.util.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** OutboxRunner(再送ワーカーの手順)の JVM テスト。未送信キューと送信は偽物。実メールは送らない。 */
class OutboxRunnerTest {

    private val now = 1_000_000_000_000L
    private val mb = 1024L * 1024

    /** OutboxRepository のワーカー用呼び出しを、メモリ上で同じ意味に真似る偽物。 */
    private class FakeOutbox(private val clock: () -> Long) : WorkerOutbox {
        val rows = linkedMapOf<ItemId, OutboxItemEntity>()
        val log = mutableListOf<String>()
        var notifyCount = 0
        // 起動時の復旧の完了(既定は完了済み)
        var recoveryGate: CompletableDeferred<Unit> = CompletableDeferred(Unit)

        fun add(id: String, recipientId: String, captured: Long, retryStart: Long, size: Long = 1000) {
            rows[id] = OutboxItemEntity(
                id = id, recipientId = recipientId, capturedAtMillis = captured, retryStartMillis = retryStart,
                attentionSinceMillis = null, state = ItemState.WAITING, phase = Phase.QUEUED,
                attentionReason = null, resendPending = false, fileSize = size,
            )
        }

        private fun isQueued(r: OutboxItemEntity) = r.state == ItemState.WAITING && r.phase == Phase.QUEUED

        private fun attention(ids: List<ItemId>, reason: AttentionReason): Int {
            var moved = 0
            for (id in ids) {
                val r = rows[id] ?: continue
                if (r.state != ItemState.WAITING) continue
                rows[id] = r.copy(state = ItemState.ATTENTION, phase = Phase.QUEUED, attentionReason = reason,
                    attentionSinceMillis = clock())
                moved++
            }
            if (moved > 0) notifyCount++
            return moved
        }

        override suspend fun awaitRecovery() = recoveryGate.await()

        override suspend fun expireOld(now: Long): Int =
            attention(rows.values.filter { isQueued(it) && it.retryStartMillis < now - Constants.RETRY_WINDOW_MS }
                .map { it.id }, AttentionReason.RETRY_EXPIRED)

        override suspend fun markAllQueuedAttention(reason: AttentionReason): Int =
            attention(rows.values.filter { isQueued(it) }.map { it.id }, reason)

        override suspend fun markRecipientMissing(ids: List<ItemId>) {
            attention(ids, AttentionReason.RECIPIENT_DELETED)
        }

        override suspend fun queuedSnapshot() = rows.values.filter { isQueued(it) }

        override suspend fun claimBatch(batch: Batch): List<OutboxItemEntity> =
            batch.items.mapNotNull { b ->
                rows[b.id]?.takeIf { isQueued(it) }?.copy(phase = Phase.SENDING_BG)?.also { rows[it.id] = it }
            }

        override suspend fun releaseBatch(ids: List<ItemId>) {
            for (id in ids) rows[id]?.takeIf { it.state == ItemState.WAITING }?.let { rows[id] = it.copy(phase = Phase.QUEUED) }
        }

        override suspend fun finishBatchSent(ids: List<ItemId>, recipientId: String) {
            ids.forEach { rows.remove(it) }
            log += "SENT $recipientId"
        }

        override suspend fun finishBatchTransient(ids: List<ItemId>, code: MailCode) {
            releaseBatch(ids)
            log += "FAILED $code"
        }

        override suspend fun finishBatchAttention(ids: List<ItemId>, reason: AttentionReason, code: MailCode) {
            attention(ids, reason)
            log += "FAILED $code"
        }
    }

    private val outbox = FakeOutbox { now }
    private var recipientList = listOf(Recipient("a", "母", "a@example.com"), Recipient("b", "妹", "b@example.com"))
    private var setupRequired = false
    private val sent = mutableListOf<MailRequest>()
    private var respond: suspend (MailRequest) -> MailResult = { MailResult.Success }

    private fun runner(gate: Mutex = Mutex()) = OutboxRunner(
        outbox = outbox,
        sender = BatchSender { req -> sent += req; respond(req) },
        recipients = { recipientList },
        setupRequired = { setupRequired },
        photoFile = { File("/outbox/$it.jpg") },
        clock = { now },
        gate = gate,
    )

    private fun names(req: MailRequest) = req.photos.map { it.file.nameWithoutExtension }

    private fun transient() = MailResult.Failure(FailureKind.TRANSIENT, MailCode.NETWORK, null, null, null)

    @Test fun noItems_success_noSend() = runTest {
        assertEquals(RunOutcome.SUCCESS, runner().run())
        assertTrue(sent.isEmpty())
    }

    @Test fun twoRecipients_twoMails_oldestFirst() = runTest {
        outbox.add("b1", "b", captured = 100, retryStart = now)
        outbox.add("a1", "a", captured = 200, retryStart = now)
        outbox.add("b2", "b", captured = 300, retryStart = now)
        assertEquals(RunOutcome.SUCCESS, runner().run())
        assertEquals(2, sent.size)
        assertEquals("b@example.com", sent[0].toAddress)
        assertEquals(listOf("b1", "b2"), names(sent[0]))
        assertEquals(listOf("a1"), names(sent[1]))
        assertTrue(outbox.rows.isEmpty())
        assertEquals(listOf("SENT b", "SENT a"), outbox.log)
    }

    @Test fun fivePhotosNear15Mb_splitIntoAscendingMails() = runTest {
        for (i in 1..5) outbox.add("p$i", "a", captured = i * 10L, retryStart = now, size = 4 * mb)
        assertEquals(RunOutcome.SUCCESS, runner().run())
        assertEquals(listOf(listOf("p1", "p2", "p3"), listOf("p4", "p5")), sent.map { names(it) })
        sent.forEach { req -> assertEquals(req.photos.sortedBy { it.capturedAtMillis }, req.photos) }
    }

    @Test fun transient_staysQueued_retry_notRetriedInSameRun() = runTest {
        outbox.add("a1", "a", captured = 1, retryStart = now)
        respond = { transient() }
        assertEquals(RunOutcome.RETRY, runner().run())
        assertEquals(1, sent.size)
        assertEquals(Phase.QUEUED, outbox.rows["a1"]!!.phase)
        assertEquals(ItemState.WAITING, outbox.rows["a1"]!!.state)
        assertEquals(listOf("FAILED NETWORK"), outbox.log)
    }

    @Test fun auth_toAttentionAuth_withNotify_success() = runTest {
        outbox.add("a1", "a", captured = 1, retryStart = now)
        outbox.add("a2", "a", captured = 2, retryStart = now)
        respond = { MailResult.Failure(FailureKind.AUTH, MailCode.AUTH, null, null, 535) }
        assertEquals(RunOutcome.SUCCESS, runner().run())
        assertEquals(1, sent.size)
        assertTrue(outbox.rows.values.all { it.state == ItemState.ATTENTION && it.attentionReason == AttentionReason.AUTH })
        assertEquals(1, outbox.notifyCount)
        assertEquals(listOf("FAILED AUTH"), outbox.log)
    }

    @Test fun permanent_toAttentionPermanent() = runTest {
        outbox.add("a1", "a", captured = 1, retryStart = now)
        respond = { MailResult.Failure(FailureKind.PERMANENT, MailCode.REJECTED, null, null, 550) }
        assertEquals(RunOutcome.SUCCESS, runner().run())
        assertEquals(AttentionReason.PERMANENT, outbox.rows["a1"]!!.attentionReason)
    }

    @Test fun mixedResults_acrossRecipients() = runTest {
        outbox.add("a1", "a", captured = 1, retryStart = now)
        outbox.add("b1", "b", captured = 2, retryStart = now)
        respond = { req -> if (req.toAddress.startsWith("a")) transient() else MailResult.Success }
        assertEquals(RunOutcome.RETRY, runner().run())
        assertEquals(setOf("a1"), outbox.rows.keys)
        assertEquals(listOf("FAILED NETWORK", "SENT b"), outbox.log)
    }

    @Test fun expired_movedNotSent_exactly24hStillSent() = runTest {
        outbox.add("old", "a", captured = 1, retryStart = now - Constants.RETRY_WINDOW_MS - 1)
        outbox.add("edge", "a", captured = 2, retryStart = now - Constants.RETRY_WINDOW_MS)
        assertEquals(RunOutcome.SUCCESS, runner().run())
        assertEquals(listOf(listOf("edge")), sent.map { names(it) })
        assertEquals(AttentionReason.RETRY_EXPIRED, outbox.rows["old"]!!.attentionReason)
    }

    @Test fun setupRequired_allToAuth_oneNotify_success_noSend() = runTest {
        outbox.add("a1", "a", captured = 1, retryStart = now)
        outbox.add("b1", "b", captured = 2, retryStart = now)
        setupRequired = true
        assertEquals(RunOutcome.SUCCESS, runner().run())
        assertTrue(sent.isEmpty())
        assertTrue(outbox.rows.values.all { it.attentionReason == AttentionReason.AUTH })
        assertEquals(1, outbox.notifyCount)
        assertTrue(outbox.log.isEmpty())
    }

    @Test fun missingRecipient_toRecipientDeleted_othersSent() = runTest {
        outbox.add("x1", "gone", captured = 1, retryStart = now)
        outbox.add("a1", "a", captured = 2, retryStart = now)
        assertEquals(RunOutcome.SUCCESS, runner().run())
        assertEquals(AttentionReason.RECIPIENT_DELETED, outbox.rows["x1"]!!.attentionReason)
        assertEquals(listOf("SENT a"), outbox.log)
    }

    @Test fun editedRecipientAddress_usedAtNextMail() = runTest {
        outbox.add("a1", "a", captured = 1, retryStart = now)
        outbox.add("b1", "b", captured = 2, retryStart = now)
        respond = {
            // 1通目の送信中に送り先 b のアドレスが編集された
            recipientList = recipientList.map { if (it.id == "b") it.copy(email = "new-b@example.com") else it }
            MailResult.Success
        }
        runner().run()
        assertEquals(listOf("a@example.com", "new-b@example.com"), sent.map { it.toAddress })
    }

    @Test fun cancellationDuringSend_itemsQueuedAgain_noLog_exceptionPropagates() = runTest {
        outbox.add("a1", "a", captured = 1, retryStart = now)
        outbox.add("a2", "a", captured = 2, retryStart = now)
        respond = { awaitCancellation() }
        val run = async { runner().run() }
        runCurrent()
        assertEquals(Phase.SENDING_BG, outbox.rows["a1"]!!.phase)
        run.cancel()
        try {
            run.await()
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertTrue(outbox.rows.values.all { it.state == ItemState.WAITING && it.phase == Phase.QUEUED })
        assertTrue(outbox.log.isEmpty())
    }

    @Test fun itemAddedDuringRun_pickedUpByRequery() = runTest {
        outbox.add("a1", "a", captured = 1, retryStart = now)
        var first = true
        respond = {
            if (first) {
                first = false
                outbox.add("a2", "a", captured = 5, retryStart = now)
            }
            MailResult.Success
        }
        assertEquals(RunOutcome.SUCCESS, runner().run())
        assertEquals(listOf(listOf("a1"), listOf("a2")), sent.map { names(it) })
        assertTrue(outbox.rows.isEmpty())
    }

    @Test fun twoConcurrentRuns_noItemSentTwice() = runTest {
        outbox.add("a1", "a", captured = 1, retryStart = now)
        outbox.add("b1", "b", captured = 2, retryStart = now)
        respond = { delay(1000); MailResult.Success }
        val gate = Mutex()
        val r1 = async { runner(gate).run() }
        val r2 = async { runner(gate).run() }
        assertEquals(RunOutcome.SUCCESS, r1.await())
        assertEquals(RunOutcome.SUCCESS, r2.await())
        val all = sent.flatMap { names(it) }
        assertEquals(all.toSet().size, all.size)
        assertEquals(setOf("a1", "b1"), all.toSet())
    }

    @Test fun runWaitsForRecovery() = runTest {
        outbox.add("a1", "a", captured = 1, retryStart = now)
        outbox.recoveryGate = CompletableDeferred()
        val run = launch { runner().run() }
        runCurrent()
        assertTrue(sent.isEmpty())
        assertEquals(Phase.QUEUED, outbox.rows["a1"]!!.phase)
        assertFalse(run.isCompleted)
        outbox.recoveryGate.complete(Unit)
        run.join()
        assertEquals(1, sent.size)
    }
}
