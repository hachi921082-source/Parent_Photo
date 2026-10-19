package com.example.parentphoto.outbox

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.parentphoto.mail.FailureKind
import com.example.parentphoto.mail.MailCode
import com.example.parentphoto.mail.MailRequest
import com.example.parentphoto.mail.MailResult
import com.example.parentphoto.mail.SendLogResult
import com.example.parentphoto.notify.AttentionAlerter
import com.example.parentphoto.settings.Recipient
import com.example.parentphoto.settings.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * OutboxRunner を本物の OutboxRepository(メモリ上の Room)とつないだ結合テスト。
 * 送信は偽物で、実メールは送らない。
 */
class OutboxRunnerDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: OutboxDatabase
    private lateinit var root: File
    private lateinit var files: OutboxFiles
    private lateinit var repo: OutboxRepository
    private val now = 1_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val taro = Recipient("r1", "太郎", "taro@example.com")
    private val settings = MutableStateFlow(Settings(recipients = listOf(taro)))

    private object NoScheduler : WorkScheduler {
        override fun enqueueDefault() = Unit
        override fun triggerNow() = Unit
    }

    private object NoAlerter : AttentionAlerter {
        override fun show(overflowDeleted: Boolean) = Unit
        override fun cancel() = Unit
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, OutboxDatabase::class.java).build()
        // 日本語を含むパスでも動くことを確認する
        root = File(ctx.cacheDir, "runner_test_写真").apply { deleteRecursively(); mkdirs() }
        files = OutboxFiles(root)
        repo = OutboxRepository(db, files, settings, NoScheduler, NoAlerter, scope, clock = { now })
        runBlocking { repo.startRecovery().join() }
    }

    @After
    fun tearDown() {
        // 監視が閉じたDBに触れないよう、止まりきってから閉じる
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        db.close()
        root.deleteRecursively()
    }

    // 前面送信に失敗して送信待ちになった項目を作る
    private fun queued(captured: Long): String = runBlocking {
        val preview = File(root, "preview.jpg").apply { writeBytes(ByteArray(100) { it.toByte() }) }
        val id = repo.createItem(taro.id, captured, preview)
        repo.setPhase(id, Phase.QUEUED)
        id
    }

    private fun runner(send: suspend (MailRequest) -> MailResult) = OutboxRunner(
        outbox = repo.asWorkerOutbox(),
        sender = BatchSender { send(it) },
        recipients = { settings.first().recipients },
        setupRequired = { false },
        photoFile = { files.file(it) },
        clock = { now },
        gate = Mutex(),
    )

    @Test
    fun successDeletesRowsAndFilesAndLogsSentOnce() = runBlocking {
        val a = queued(1)
        val b = queued(2)
        var filesExisted = false
        val outcome = runner { req ->
            filesExisted = req.photos.all { it.file.exists() }
            MailResult.Success
        }.run()
        assertEquals(RunOutcome.SUCCESS, outcome)
        assertTrue(filesExisted)
        assertNull(db.outboxDao().get(a))
        assertNull(db.outboxDao().get(b))
        assertFalse(files.file(a).exists())
        val log = repo.sendLog.first()
        assertEquals(1, log.size)
        assertEquals(SendLogResult.SENT, log[0].result)
        assertEquals("太郎", log[0].recipientName)
    }

    @Test
    fun transientKeepsQueuedAndAsksRetry() = runBlocking {
        val a = queued(1)
        val outcome = runner { MailResult.Failure(FailureKind.TRANSIENT, MailCode.TIMEOUT, null, null, null) }.run()
        assertEquals(RunOutcome.RETRY, outcome)
        val r = db.outboxDao().get(a)!!
        assertEquals(ItemState.WAITING, r.state)
        assertEquals(Phase.QUEUED, r.phase)
        assertEquals(SendLogResult.FAILED, repo.sendLog.first().single().result)
    }

    @Test
    fun cancellationDuringSendRestoresQueuedWithoutLog() = runBlocking {
        val a = queued(1)
        val sending = CompletableDeferred<Unit>()
        val run = async(Dispatchers.IO) {
            runner {
                sending.complete(Unit)
                awaitCancellation()
            }.run()
        }
        sending.await()
        assertEquals(Phase.SENDING_BG, db.outboxDao().get(a)!!.phase)
        run.cancelAndJoin()
        assertEquals(Phase.QUEUED, db.outboxDao().get(a)!!.phase)
        assertTrue(files.file(a).exists())
        assertTrue(repo.sendLog.first().isEmpty())
    }
}
