package com.example.parentphoto.outbox

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.parentphoto.mail.SendLogResult
import com.example.parentphoto.notify.AttentionAlerter
import com.example.parentphoto.settings.Recipient
import com.example.parentphoto.settings.Settings
import com.example.parentphoto.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Collections

/** outbox-queue PLAN-2: 要確認への移行・上限超過・通知・起動時の復旧・送り先削除・期限切れ。 */
class OutboxAttentionRecoveryTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: OutboxDatabase
    private lateinit var root: File
    private lateinit var files: OutboxFiles
    private var now = 100_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings = MutableStateFlow(
        Settings(recipients = listOf(Recipient("r1", "太郎", "t@example.com"), Recipient("r2", "花子", "h@example.com"))),
    )

    private class FakeScheduler(val fail: Boolean = false) : WorkScheduler {
        @Volatile var enqueued = 0
        override fun enqueueDefault() {
            if (fail) throw IllegalStateException("boom")
            enqueued++
        }
        override fun triggerNow() {}
    }

    private class FakeAlerter : AttentionAlerter {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun show(overflowDeleted: Boolean) { calls.add("show($overflowDeleted)") }
        override fun cancel() { calls.add("cancel") }
    }

    private val alerter = FakeAlerter()
    private var scheduler = FakeScheduler()
    private lateinit var repo: OutboxRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, OutboxDatabase::class.java).build()
        root = File(ctx.cacheDir, "outbox_test2_写真").apply { deleteRecursively(); mkdirs() }
        files = OutboxFiles(root)
        repo = newRepo()
    }

    @After
    fun tearDown() {
        // 監視が閉じたDBに触れないよう、止まりきってから閉じる
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        db.close()
        root.deleteRecursively()
    }

    private fun newRepo() = OutboxRepository(db, files, settings, scheduler, alerter, scope, clock = { now })

    private fun seed(
        id: String,
        recipient: String = "r1",
        state: ItemState = ItemState.WAITING,
        phase: Phase = Phase.QUEUED,
        since: Long? = null,
        captured: Long = 1,
        retryStart: Long = now,
        reason: AttentionReason? = if (state == ItemState.ATTENTION) AttentionReason.AUTH else null,
        withFile: Boolean = true,
    ) = runBlocking {
        db.outboxDao().insert(
            OutboxItemEntity(id, recipient, captured, retryStart, since, state, phase, reason, false, 10),
        )
        if (withFile) {
            files.dir.mkdirs()
            files.file(id).writeBytes(ByteArray(10))
        }
    }

    private fun seedAttention(count: Int, sinceFrom: Long = 100) =
        (0 until count).map { i -> "old$i".also { seed(it, state = ItemState.ATTENTION, since = sinceFrom + i) } }

    private fun row(id: String) = runBlocking { db.outboxDao().get(id) }
    private fun logs() = runBlocking { repo.sendLog.first() }
    private fun attention() = runBlocking { repo.attentionCount.first() }

    private suspend fun waitUntil(cond: () -> Boolean) {
        withTimeout(5000) { while (!cond()) delay(20) }
    }

    // ---- 上限超過 ----

    @Test
    fun tenPlusOneDeletesOldestLogsAfterFailedAndNotifiesOnce() = runBlocking {
        val old = seedAttention(10)
        seed("new")
        val out = repo.moveToAttention(listOf("new"), AttentionReason.PERMANENT) {
            repo.record(SendLogResult.FAILED, "太郎", "REJECTED")
        }
        assertEquals(AttentionOutcome(1, 1), out)
        assertNull(row(old[0]))
        assertFalse(files.file(old[0]).exists())
        assertEquals(ItemState.ATTENTION, row("new")!!.state)
        assertEquals(10, attention())
        // 新しい順: DELETED_OVERFLOW が FAILED の後に書かれている
        assertEquals(listOf(SendLogResult.DELETED_OVERFLOW, SendLogResult.FAILED), logs().map { it.result })
        assertEquals("太郎", logs()[0].recipientName)
        assertEquals(listOf("show(true)"), alerter.calls)
    }

    @Test
    fun twelveAtOnceDeletesTwoAndNotifiesOnce() = runBlocking {
        (1..12).forEach { seed("q$it", captured = it.toLong()) }
        assertEquals(12, repo.markAllQueuedAttention())
        assertEquals(10, attention())
        // 同時に要確認になったので撮影の古い順に消える
        assertNull(row("q1")); assertNull(row("q2"))
        assertEquals(AttentionReason.AUTH, row("q3")!!.attentionReason)
        assertEquals(2, logs().count { it.result == SendLogResult.DELETED_OVERFLOW })
        assertEquals(listOf("show(true)"), alerter.calls)
    }

    @Test
    fun tieBreakByCaptureThenId() = runBlocking {
        seed("b", state = ItemState.ATTENTION, since = 100, captured = 50)
        seed("a", state = ItemState.ATTENTION, since = 100, captured = 50)
        seed("z", state = ItemState.ATTENTION, since = 100, captured = 40)
        (0 until 7).forEach { seed("o$it", state = ItemState.ATTENTION, since = 200) }
        seed("n1"); seed("n2")
        repo.moveToAttention(listOf("n1"), AttentionReason.AUTH)
        assertNull(row("z"))
        repo.moveToAttention(listOf("n2"), AttentionReason.AUTH)
        assertNull(row("a"))
        assertTrue(row("b") != null)
    }

    @Test
    fun withinCapNotifiesWithoutOverflow() = runBlocking {
        seedAttention(9)
        seed("new")
        assertEquals(AttentionOutcome(1, 0), repo.moveToAttention(listOf("new"), AttentionReason.AUTH))
        assertEquals(listOf("show(false)"), alerter.calls)
        assertTrue(logs().isEmpty())
    }

    @Test
    fun goneOrAttentionIdsAreSkippedWithoutNotification() = runBlocking {
        seed("att", state = ItemState.ATTENTION, since = 5, reason = AttentionReason.PERMANENT)
        val out = repo.moveToAttention(listOf("ghost", "att"), AttentionReason.AUTH)
        assertEquals(AttentionOutcome(0, 0), out)
        assertEquals(AttentionReason.PERMANENT, row("att")!!.attentionReason)
        assertEquals(5L, row("att")!!.attentionSinceMillis)
        assertTrue(alerter.calls.isEmpty())
    }

    // ---- 通知を消す監視 ----

    @Test
    fun cancelCollectorCancelsOnlyAtZero() = runBlocking {
        repo.startObservers()
        waitUntil { alerter.calls.contains("cancel") } // 起動直後の0
        alerter.calls.clear()
        seed("a"); seed("b")
        repo.markAllQueuedAttention()
        repo.deleteManual("a")
        delay(300)
        assertFalse(alerter.calls.contains("cancel"))
        repo.deleteManual("b")
        waitUntil { alerter.calls.contains("cancel") }
    }

    // ---- 起動時の復旧 ----

    @Test
    fun recoveryRequeuesInFlightAndCleansFiles() = runBlocking {
        seed("cw", phase = Phase.CANCEL_WINDOW)
        seed("fg", phase = Phase.SENDING_FG)
        seed("bg", phase = Phase.SENDING_BG)
        seed("att", state = ItemState.ATTENTION, since = 1)
        seed("nofile", state = ItemState.ATTENTION, since = 2, withFile = false)
        File(files.dir, "orphan.jpg").writeBytes(ByteArray(1))
        File(files.dir, "cw.jpg.tmp").writeBytes(ByteArray(1))
        File(files.dir, "junk.txt").writeBytes(ByteArray(1))
        repo.recover()
        listOf("cw", "fg", "bg").forEach {
            assertEquals(Phase.QUEUED, row(it)!!.phase)
            assertTrue(files.file(it).exists())
        }
        assertEquals(ItemState.ATTENTION, row("att")!!.state)
        assertNull(row("nofile"))
        assertEquals(setOf("cw.jpg", "fg.jpg", "bg.jpg", "att.jpg"), files.listFiles().map { it.name }.toSet())
        assertEquals(1, scheduler.enqueued)
    }

    @Test
    fun recoveryDoesNotEnqueueWithoutQueuedRows() = runBlocking {
        seed("att", state = ItemState.ATTENTION, since = 1)
        repo.recover()
        assertEquals(0, scheduler.enqueued)
        // 置き場が無くても落ちない
        files.dir.deleteRecursively()
        repo.recover()
        assertNull(row("att")) // ファイルの無い行は消える
    }

    @Test
    fun awaitRecoveryBlocksUntilDone() = runBlocking {
        assertFalse(repo.recoveryDone.value)
        assertNull(withTimeoutOrNull(200) { repo.awaitRecovery() })
        repo.startRecovery()
        withTimeout(5000) { repo.awaitRecovery() }
        assertTrue(repo.recoveryDone.value)
    }

    @Test
    fun recoveryDoneEvenWhenRecoveryThrows() = runBlocking {
        scheduler = FakeScheduler(fail = true)
        repo = newRepo()
        seed("q")
        repo.startRecovery()
        withTimeout(5000) { repo.awaitRecovery() }
        assertTrue(repo.recoveryDone.value)
    }

    // ---- 送り先の削除 ----

    @Test
    fun recipientDeletedMovesOnlyQueuedRowsOfThatRecipient() = runBlocking {
        seed("q1")
        seed("c1", phase = Phase.CANCEL_WINDOW)
        seed("s1", phase = Phase.SENDING_BG)
        seed("a1", state = ItemState.ATTENTION, since = 1, reason = AttentionReason.AUTH)
        seed("q2", recipient = "r2")
        repo.onRecipientDeleted("r1")
        assertEquals(AttentionReason.RECIPIENT_DELETED, row("q1")!!.attentionReason)
        assertEquals(Phase.CANCEL_WINDOW, row("c1")!!.phase)
        assertEquals(Phase.SENDING_BG, row("s1")!!.phase)
        assertEquals(AttentionReason.AUTH, row("a1")!!.attentionReason)
        assertEquals(ItemState.WAITING, row("q2")!!.state)
        assertEquals(4, repo.countForRecipient("r1"))
        assertEquals(listOf("show(false)"), alerter.calls)
        assertTrue(logs().isEmpty())
    }

    @Test
    fun markRecipientMissingWritesNoFailedLine() = runBlocking {
        seed("q1"); seed("q2")
        repo.markRecipientMissing(listOf("q1", "q2"))
        assertEquals(AttentionReason.RECIPIENT_DELETED, row("q2")!!.attentionReason)
        assertTrue(logs().isEmpty())
        assertEquals(listOf("show(false)"), alerter.calls)
    }

    // ---- 期限切れ ----

    @Test
    fun expireOldAtTwentyFourHourBoundary() = runBlocking {
        seed("exact", retryStart = now - Constants.RETRY_WINDOW_MS)
        seed("over", retryStart = now - Constants.RETRY_WINDOW_MS - 1)
        seed("cw", phase = Phase.CANCEL_WINDOW, retryStart = 0)
        seed("bg", phase = Phase.SENDING_BG, retryStart = 0)
        assertEquals(1, repo.expireOld(now))
        assertEquals(ItemState.WAITING, row("exact")!!.state)
        assertEquals(AttentionReason.RETRY_EXPIRED, row("over")!!.attentionReason)
        assertEquals(now, row("over")!!.attentionSinceMillis)
        assertTrue(files.file("over").exists())
        assertEquals(Phase.CANCEL_WINDOW, row("cw")!!.phase)
        assertEquals(Phase.SENDING_BG, row("bg")!!.phase)
        assertEquals(0, repo.expireOld(now))
        assertEquals(listOf("show(false)"), alerter.calls)
    }
}
