package com.example.parentphoto.outbox

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.parentphoto.mail.SendLogResult
import com.example.parentphoto.notify.AttentionAlerter
import com.example.parentphoto.settings.Recipient
import com.example.parentphoto.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Collections

/** outbox-queue PLAN-3: 再開・今すぐ再送・空き枠への繰り上げ、および createItem の復旧待ち。 */
class OutboxResumePromotionTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: OutboxDatabase
    private lateinit var root: File
    private lateinit var files: OutboxFiles
    private lateinit var repo: OutboxRepository
    private var now = 500_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings = MutableStateFlow(Settings(recipients = listOf(Recipient("r1", "太郎", "t@example.com"))))

    private inner class FakeScheduler : WorkScheduler {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        // triggerNow の時点での行の状態(DBを先に書いているかの確認用)
        val statesAtTrigger: MutableList<Map<String, ItemState>> = Collections.synchronizedList(mutableListOf())
        override fun enqueueDefault() { calls.add("enqueue") }
        override fun triggerNow() {
            statesAtTrigger.add(runBlocking { db.outboxDao().queuedItems().associate { it.id to it.state } })
            calls.add("trigger")
        }
    }

    private class FakeAlerter : AttentionAlerter {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun show(overflowDeleted: Boolean) { calls.add("show") }
        override fun cancel() { calls.add("cancel") }
    }

    private lateinit var scheduler: FakeScheduler
    private val alerter = FakeAlerter()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, OutboxDatabase::class.java).build()
        root = File(ctx.cacheDir, "outbox_test3_写真").apply { deleteRecursively(); mkdirs() }
        files = OutboxFiles(root)
        scheduler = FakeScheduler()
        repo = OutboxRepository(db, files, settings, scheduler, alerter, scope, clock = { now })
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        root.deleteRecursively()
    }

    private fun seed(
        id: String,
        state: ItemState = ItemState.WAITING,
        phase: Phase = Phase.QUEUED,
        reason: AttentionReason? = if (state == ItemState.ATTENTION) AttentionReason.AUTH else null,
        since: Long? = if (state == ItemState.ATTENTION) 10 else null,
        pending: Boolean = false,
        retryStart: Long = 1,
        captured: Long = 1,
    ) = runBlocking {
        db.outboxDao().insert(OutboxItemEntity(id, "r1", captured, retryStart, since, state, phase, reason, pending, 10))
        files.dir.mkdirs()
        files.file(id).writeBytes(ByteArray(10))
    }

    private fun seedActive(n: Int) = (1..n).map { "w$it".also { id -> seed(id) } }
    private fun row(id: String) = runBlocking { db.outboxDao().get(id) }
    private fun logs() = runBlocking { repo.sendLog.first() }

    private suspend fun waitUntil(cond: () -> Boolean) {
        withTimeout(5000) { while (!cond()) delay(20) }
    }

    private fun assertResumed(id: String) {
        val r = row(id)!!
        assertEquals(ItemState.WAITING, r.state)
        assertEquals(Phase.QUEUED, r.phase)
        assertEquals(now, r.retryStartMillis)
        assertNull(r.attentionSinceMillis)
        assertNull(r.attentionReason)
        assertFalse(r.resendPending)
    }

    // ---- 枠の規則 ----

    @Test
    fun activeFourWithoutPreviewStartsImmediately() = runBlocking {
        seedActive(4)
        seed("x", state = ItemState.ATTENTION)
        assertEquals(ResendOutcome.Started, repo.resendNow("x"))
        assertResumed("x")
        assertEquals(listOf("trigger"), scheduler.calls)
        assertEquals(listOf(SendLogResult.RESEND_REQUESTED), logs().map { it.result })
        assertEquals("太郎", logs()[0].recipientName)
    }

    @Test
    fun activeFourWithPreviewWaits() = runBlocking {
        seedActive(4)
        seed("x", state = ItemState.ATTENTION)
        repo.setPreviewExists(true)
        assertEquals(ResendOutcome.Waiting, repo.resendNow("x"))
        assertEquals(ItemState.ATTENTION, row("x")!!.state)
        assertTrue(row("x")!!.resendPending)
        assertEquals(1, repo.attentionCount.first()) // 枠待ちも要確認として数える
        assertEquals(4, repo.activeCount.first())
        assertTrue(scheduler.calls.isEmpty())
        assertEquals(1, logs().size)
    }

    @Test
    fun activeFiveWaitsThenPromotedWhenSlotFrees() = runBlocking {
        val active = seedActive(5)
        seed("x", state = ItemState.ATTENTION)
        repo.startObservers()
        assertEquals(ResendOutcome.Waiting, repo.resendNow("x"))
        repo.deleteManual(active[0]) // 枠が空く(呼び出しなしで繰り上がる)
        waitUntil { row("x")?.state == ItemState.WAITING }
        assertResumed("x")
        assertEquals(5, repo.activeCount.first())
    }

    @Test
    fun leavingPreviewPromotesPendingItem() = runBlocking {
        seedActive(4)
        seed("x", state = ItemState.ATTENTION)
        repo.setPreviewExists(true)
        repo.startObservers()
        assertEquals(ResendOutcome.Waiting, repo.resendNow("x"))
        repo.setPreviewExists(false) // やり直す等でプレビューを離れた
        waitUntil { row("x")?.state == ItemState.WAITING }
    }

    @Test
    fun oldestPendingFirstOneSlotOneItem() = runBlocking {
        seedActive(4)
        seed("c", state = ItemState.ATTENTION, since = 30, pending = true)
        seed("a", state = ItemState.ATTENTION, since = 10, pending = true)
        seed("b", state = ItemState.ATTENTION, since = 20, pending = true)
        assertEquals(1, repo.promote())
        assertEquals(ItemState.WAITING, row("a")!!.state)
        assertTrue(row("b")!!.resendPending)
        assertTrue(row("c")!!.resendPending)
        assertEquals(0, repo.promote())
        assertEquals(listOf("trigger"), scheduler.calls)
    }

    // ---- 今すぐ再送 ----

    @Test
    fun resendNowPerReasonAndState() = runBlocking {
        seed("auth", state = ItemState.ATTENTION, reason = AttentionReason.AUTH)
        seed("perm", state = ItemState.ATTENTION, reason = AttentionReason.PERMANENT)
        seed("exp", state = ItemState.ATTENTION, reason = AttentionReason.RETRY_EXPIRED)
        seed("gone", state = ItemState.ATTENTION, reason = AttentionReason.RECIPIENT_DELETED)
        listOf("auth", "perm", "exp").forEach {
            assertEquals(ResendOutcome.Started, repo.resendNow(it))
            assertResumed(it)
        }
        // 処理中の行は待機中として枠を使うので、再開の確認の後に入れる
        seed("cw", phase = Phase.CANCEL_WINDOW)
        seed("fg", phase = Phase.SENDING_FG)
        seed("bg", phase = Phase.SENDING_BG)
        listOf("gone", "cw", "fg", "bg", "missing").forEach {
            assertEquals(it, ResendOutcome.NotAllowed, repo.resendNow(it))
        }
        assertEquals(AttentionReason.RECIPIENT_DELETED, row("gone")!!.attentionReason)
        assertFalse(row("gone")!!.resendPending)
        // ログは許可されたタップの分だけ
        assertEquals(3, logs().count { it.result == SendLogResult.RESEND_REQUESTED })
        assertEquals(3, logs().size)
    }

    @Test
    fun resendNowOnQueuedItemRestartsWindowAndTriggers() = runBlocking {
        seed("q", retryStart = 7)
        assertEquals(ResendOutcome.Started, repo.resendNow("q"))
        assertEquals(now, row("q")!!.retryStartMillis)
        assertEquals(Phase.QUEUED, row("q")!!.phase)
        assertEquals(listOf("trigger"), scheduler.calls)
        assertEquals(listOf(SendLogResult.RESEND_REQUESTED), logs().map { it.result })
    }

    // ---- 設定保存・テスト成功後の再開 ----

    @Test
    fun resumeAfterSetupFixOnlyResumableReasons() = runBlocking {
        seed("auth", state = ItemState.ATTENTION, reason = AttentionReason.AUTH)
        seed("perm", state = ItemState.ATTENTION, reason = AttentionReason.PERMANENT)
        seed("exp", state = ItemState.ATTENTION, reason = AttentionReason.RETRY_EXPIRED)
        seed("gone", state = ItemState.ATTENTION, reason = AttentionReason.RECIPIENT_DELETED)
        repo.resumeAfterSetupFix()
        listOf("auth", "perm", "exp").forEach { assertResumed(it) }
        assertEquals(ItemState.ATTENTION, row("gone")!!.state)
        assertFalse(row("gone")!!.resendPending)
        assertEquals(listOf("trigger", "enqueue"), scheduler.calls)
        assertTrue(logs().isEmpty()) // RESEND_REQUESTED は手動タップのみ
    }

    @Test
    fun resumeAfterSetupFixLeavesOverflowPendingAndCountsAsAttention() = runBlocking {
        seedActive(4)
        seed("a", state = ItemState.ATTENTION, since = 10)
        seed("b", state = ItemState.ATTENTION, since = 20)
        repo.resumeAfterSetupFix()
        assertResumed("a")
        assertTrue(row("b")!!.resendPending)
        assertEquals(1, repo.attentionCount.first())
        assertEquals(5, repo.activeCount.first())
    }

    @Test
    fun resumeAfterSetupFixNoOpAndEnqueueWhenQueuedExists() = runBlocking {
        repo.resumeAfterSetupFix()
        assertTrue(scheduler.calls.isEmpty())
        seed("q")
        repo.resumeAfterSetupFix()
        assertEquals(listOf("enqueue"), scheduler.calls)
    }

    @Test
    fun notificationCancelledWhenLastAttentionPromoted() = runBlocking {
        seed("a", state = ItemState.ATTENTION)
        repo.startObservers()
        delay(300)
        alerter.calls.clear()
        repo.resumeAfterSetupFix()
        waitUntil { alerter.calls.contains("cancel") }
    }

    @Test
    fun dbIsUpdatedBeforeTriggerNow() = runBlocking {
        seed("x", state = ItemState.ATTENTION)
        repo.resendNow("x")
        assertEquals(listOf(mapOf("x" to ItemState.WAITING)), scheduler.statesAtTrigger)
    }

    // ---- 競合 ----

    @Test
    fun concurrentCreateAndPromoteNeverExceedFive() = runBlocking {
        val preview = File(root, "preview.jpg").apply { writeBytes(ByteArray(10)) }
        repo.startRecovery().join()
        seedActive(3)
        (1..4).forEach { seed("p$it", state = ItemState.ATTENTION, since = it.toLong(), pending = true) }
        val jobs = (1..4).map {
            async(Dispatchers.IO) {
                try { repo.createItem("r1", now, preview) } catch (_: OutboxFullException) { null }
            }
        } + (1..4).map { async(Dispatchers.IO) { repo.promote() } }
        jobs.awaitAll()
        assertEquals(5, repo.activeCount.first())
        // 作成に失敗した分のファイルは残らない(5件の行 = 5件のファイル + 残りの要確認)
        val rows = db.outboxDao().allIds().toSet()
        assertEquals(rows, files.listFiles().map { it.name.removeSuffix(".jpg") }.toSet())
    }

    // ---- createItem は起動時の復旧を待つ ----

    @Test
    fun createItemWaitsForRecovery() = runBlocking {
        val preview = File(root, "preview.jpg").apply { writeBytes(ByteArray(10)) }
        val pending = async(Dispatchers.IO) { repo.createItem("r1", now, preview) }
        delay(300)
        assertFalse(pending.isCompleted)
        assertTrue(files.listFiles().isEmpty()) // 復旧前はコピーもしない
        repo.startRecovery()
        val id = withTimeout(5000) { pending.await() }
        assertTrue(files.file(id).exists())
        assertEquals(Phase.CANCEL_WINDOW, row(id)!!.phase)
    }

    @Test
    fun recoveryPromotesPendingLeftAcrossRestart() = runBlocking {
        seed("x", state = ItemState.ATTENTION, pending = true)
        repo.recover()
        assertResumed("x")
        assertTrue(scheduler.calls.contains("trigger"))
    }
}
