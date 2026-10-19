package com.example.parentphoto.outbox

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.example.parentphoto.mail.FailureKind
import com.example.parentphoto.mail.MailCode
import com.example.parentphoto.mail.MailResult
import com.example.parentphoto.mail.SendLogResult
import com.example.parentphoto.notify.AttentionAlerter
import com.example.parentphoto.settings.Recipient
import com.example.parentphoto.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

class OutboxRepositoryTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: OutboxDatabase
    private lateinit var root: File
    private lateinit var files: OutboxFiles
    private lateinit var preview: File
    private lateinit var repo: OutboxRepository
    private var now = 1_000_000L

    private val taro = Recipient("r1", "太郎", "taro@example.com")
    private val hanako = Recipient("r2", "花子👩", "hanako@example.com")
    private val settings = MutableStateFlow(Settings(recipients = listOf(taro, hanako)))

    private class FakeScheduler : WorkScheduler {
        var enqueued = 0
        var triggered = 0
        override fun enqueueDefault() { enqueued++ }
        override fun triggerNow() { triggered++ }
    }

    private class FakeAlerter : AttentionAlerter {
        val calls = mutableListOf<String>()
        override fun show(overflowDeleted: Boolean) { calls.add("show($overflowDeleted)") }
        override fun cancel() { calls.add("cancel") }
    }

    private val scheduler = FakeScheduler()
    private val alerter = FakeAlerter()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, OutboxDatabase::class.java).build()
        // 日本語を含むパスでも動くことを確認する
        root = File(ctx.cacheDir, "outbox_test_写真").apply { deleteRecursively(); mkdirs() }
        files = OutboxFiles(root)
        preview = File(root, "preview.jpg").apply { writeBytes(ByteArray(1234) { it.toByte() }) }
        repo = OutboxRepository(db, files, settings, scheduler, alerter, clock = { now })
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    private fun create(recipient: String = "r1", captured: Long = now) =
        runBlocking { repo.createItem(recipient, captured, preview) }

    private fun row(id: String) = runBlocking { db.outboxDao().get(id) }

    private fun logs() = runBlocking { repo.sendLog.first() }

    private fun failure(kind: FailureKind, code: MailCode) = MailResult.Failure(kind, code, null, null, null)

    @Test
    fun createItemCopiesPhotoAndInsertsCancelWindowRow() {
        val id = create(captured = 42L)
        val r = row(id)!!
        assertEquals(ItemState.WAITING, r.state)
        assertEquals(Phase.CANCEL_WINDOW, r.phase)
        assertEquals(42L, r.capturedAtMillis)
        assertEquals(now, r.retryStartMillis)
        assertEquals(1234L, r.fileSize)
        assertFalse(r.resendPending)
        assertTrue(files.file(id).readBytes().contentEquals(preview.readBytes()))
        assertFalse(files.tmpFile(id).exists())
        assertTrue(preview.exists()) // プレビューは残す
    }

    @Test
    fun countingFlowsIgnoreAttention() = runBlocking {
        val a = create(); create(); create()
        repo.moveToAttention(listOf(a), AttentionReason.AUTH)
        assertEquals(2, repo.activeCount.first())
        assertEquals(1, repo.attentionCount.first())
    }

    @Test
    fun createAtFourSucceedsAtFiveThrowsAndLeavesNoFile() = runBlocking {
        repeat(4) { create() }
        create() // 4件のときは作れる
        assertEquals(5, repo.activeCount.first())
        val before = files.listFiles().size
        try {
            create()
            fail("expected OutboxFullException")
        } catch (_: OutboxFullException) {
        }
        assertEquals(5, repo.activeCount.first())
        assertEquals(before, files.listFiles().size)
    }

    @Test
    fun attentionItemsDoNotBlockCreation() = runBlocking {
        val ids = (1..5).map { create() }
        repo.moveToAttention(listOf(ids[0]), AttentionReason.PERMANENT)
        create()
        assertEquals(5, repo.activeCount.first())
    }

    @Test
    fun copyFailureLeavesNoRowAndNoFile() = runBlocking {
        try {
            repo.createItem("r1", now, File(root, "missing.jpg"))
            fail("expected exception")
        } catch (_: Exception) {
        }
        assertEquals(0, repo.activeCount.first())
        assertTrue(files.listFiles().isEmpty())
    }

    @Test
    fun transactionRollbackLeavesNoRow() = runBlocking {
        val entity = OutboxItemEntity(
            "x", "r1", 1, 1, null, ItemState.WAITING, Phase.QUEUED, null, false, 1,
        )
        try {
            db.withTransaction {
                db.outboxDao().insert(entity)
                throw IllegalStateException("boom")
            }
        } catch (_: IllegalStateException) {
        }
        assertNull(db.outboxDao().get("x"))
    }

    @Test
    fun sendLogTrimsToFiftyNewestFirst() = runBlocking {
        repeat(55) { i ->
            now = 1000L + i
            repo.record(SendLogResult.SENT, "太郎", "c$i")
        }
        val list = logs()
        assertEquals(50, list.size)
        assertEquals("c54", list.first().code)
        assertEquals("c5", list.last().code)
        assertEquals(1054L, list.first().timeMillis)
        // テーブル自体も50行に削られている
        repo.record(SendLogResult.SENT, null, null)
        assertEquals(50, logs().size)
    }

    @Test
    fun nonAsciiAndNullNamesRoundTrip() = runBlocking {
        repo.record(SendLogResult.TEST_OK, "花子👩（テスト）", null)
        repo.record(SendLogResult.DELETED_MANUAL, null, null)
        val list = logs()
        assertNull(list[0].recipientName)
        assertEquals(SendLogResult.DELETED_MANUAL, list[0].result)
        assertEquals("花子👩（テスト）", list[1].recipientName)
    }

    @Test
    fun itemsOrderWaitingByCaptureThenAttentionBySince() = runBlocking {
        val w2 = create(captured = 200)
        val w1 = create(captured = 100)
        val a1 = create(captured = 50)
        val a0 = create(captured = 300)
        now = 5000; repo.moveToAttention(listOf(a0), AttentionReason.AUTH)
        now = 6000; repo.moveToAttention(listOf(a1), AttentionReason.PERMANENT)
        val list = repo.items.first()
        assertEquals(listOf(w1, w2, a0, a1), list.map { it.id })
        assertEquals("太郎", list[0].recipientName)
        assertEquals(AttentionReason.AUTH, list[2].reason)
        assertEquals(files.file(w1), list[0].file)
    }

    @Test
    fun itemsShowNullNameForDeletedRecipient() = runBlocking {
        create("gone")
        assertNull(repo.items.first().single().recipientName)
    }

    @Test
    fun claimBatchClaimsOnlyQueuedRowsOnce() = runBlocking {
        val a = create(captured = 1)
        val b = create(captured = 2)
        val c = create(captured = 3) // CANCEL_WINDOW のまま(ワーカーは触らない)
        repo.markQueued(a, MailResult.Offline)
        repo.markQueued(b, MailResult.Offline)
        val batches = BatchPlanner.plan(repo.queuedSnapshot())
        assertEquals(1, batches.size)
        val claimed = repo.claimBatch(batches[0])
        assertEquals(listOf(a, b), claimed.map { it.id })
        assertEquals(Phase.SENDING_BG, row(a)!!.phase)
        assertEquals(Phase.CANCEL_WINDOW, row(c)!!.phase)
        assertTrue(repo.claimBatch(batches[0]).isEmpty())
        // 戻すと再び取り込める
        repo.releaseBatch(listOf(a, b))
        assertEquals(Phase.QUEUED, row(a)!!.phase)
        assertEquals(2, repo.claimBatch(batches[0]).size)
    }

    @Test
    fun finishBatchSentDeletesRowsFilesAndLogsOnce() = runBlocking {
        val a = create("r2"); val b = create("r2")
        repo.finishBatchSent(listOf(a, b), "r2")
        assertNull(row(a)); assertNull(row(b))
        assertFalse(files.file(a).exists())
        val list = logs()
        assertEquals(1, list.size)
        assertEquals(SendLogResult.SENT, list[0].result)
        assertEquals("花子👩", list[0].recipientName)
    }

    @Test
    fun finishBatchTransientAndAttention() = runBlocking {
        val a = create(); val b = create()
        repo.claimBatch(Batch("r1", listOf(row(a)!!, row(b)!!))) // CANCEL_WINDOW なので取り込まれない
        db.outboxDao().updatePhaseAll(listOf(a, b), Phase.SENDING_BG)
        repo.finishBatchTransient(listOf(a, b), MailCode.TIMEOUT)
        assertEquals(Phase.QUEUED, row(a)!!.phase)
        repo.finishBatchAttention(listOf(a, b), AttentionReason.PERMANENT, MailCode.REJECTED)
        assertEquals(ItemState.ATTENTION, row(b)!!.state)
        assertEquals(listOf("REJECTED", "TIMEOUT"), logs().map { it.code })
        assertEquals("太郎", logs()[0].recipientName)
    }

    @Test
    fun markQueuedOfflineAndTransientQueueAndEnqueue() = runBlocking {
        val a = create(); val b = create()
        now += 10_000 // 前面失敗でも24時間の起点は変えない
        repo.markQueued(a, MailResult.Offline)
        repo.markQueued(b, failure(FailureKind.TRANSIENT, MailCode.NETWORK))
        assertEquals(Phase.QUEUED, row(a)!!.phase)
        assertEquals(Phase.QUEUED, row(b)!!.phase)
        assertEquals(ItemState.WAITING, row(b)!!.state)
        assertEquals(now - 10_000, row(b)!!.retryStartMillis)
        assertEquals(2, scheduler.enqueued)
        assertTrue(alerter.calls.isEmpty())
        val list = logs()
        assertEquals(SendLogResult.FAILED, list[0].result)
        assertEquals("NETWORK", list[0].code)
        assertEquals(SendLogResult.QUEUED_OFFLINE, list[1].result)
        assertNull(list[1].code)
    }

    @Test
    fun markQueuedAuthPermanentAndMissingGoToAttention() = runBlocking {
        val a = create(); val p = create(); val m = create()
        repo.markQueued(a, failure(FailureKind.AUTH, MailCode.AUTH))
        repo.markQueued(p, failure(FailureKind.PERMANENT, MailCode.REJECTED))
        repo.markQueued(m, MailResult.RecipientMissing)
        assertEquals(AttentionReason.AUTH, row(a)!!.attentionReason)
        assertEquals(AttentionReason.PERMANENT, row(p)!!.attentionReason)
        assertEquals(AttentionReason.RECIPIENT_DELETED, row(m)!!.attentionReason)
        assertEquals(ItemState.ATTENTION, row(m)!!.state)
        assertEquals(Phase.QUEUED, row(m)!!.phase)
        assertEquals(now, row(m)!!.attentionSinceMillis)
        assertEquals(0, scheduler.enqueued)
        // 送り先なしは FAILED を書かない
        assertEquals(listOf("REJECTED", "AUTH"), logs().map { it.code })
        assertEquals(0, repo.activeCount.first())
        assertEquals(3, repo.attentionCount.first())
    }

    @Test(expected = IllegalArgumentException::class)
    fun markQueuedWithSuccessIsRejected() = runBlocking {
        repo.markQueued(create(), MailResult.Success)
    }

    @Test
    fun setPhaseMarkSentDeleteItemAndLoadForeground() = runBlocking {
        val a = create(captured = 77); val b = create()
        repo.setPhase(a, Phase.SENDING_FG)
        assertEquals(Phase.SENDING_FG, row(a)!!.phase)
        val s = repo.loadForeground(a)!!
        assertEquals("r1", s.recipientId)
        assertEquals(77L, s.capturedAtMillis)
        assertEquals(files.file(a), s.file)
        repo.markSent(a)
        assertNull(row(a))
        assertFalse(files.file(a).exists())
        assertNull(repo.loadForeground(a))
        repo.deleteItem(b)
        assertNull(row(b))
        assertFalse(files.file(b).exists())
        // 取り消しはログに残さない
        assertEquals(listOf(SendLogResult.SENT), logs().map { it.result })
        // 消えた行・要確認の行には段階変更が効かない
        repo.setPhase(b, Phase.SENDING_FG)
        assertNull(row(b))
    }

    @Test
    fun deleteManualLogsAndCountForRecipient() = runBlocking {
        val a = create("r2"); create("r2"); create("r1")
        assertEquals(2, repo.countForRecipient("r2"))
        repo.deleteManual(a)
        assertEquals(1, repo.countForRecipient("r2"))
        assertFalse(files.file(a).exists())
        assertEquals(SendLogResult.DELETED_MANUAL, logs()[0].result)
        assertEquals("花子👩", logs()[0].recipientName)
    }
}
