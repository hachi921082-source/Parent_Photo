package com.example.parentphoto.outbox

import android.content.Context
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.parentphoto.util.Constants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * OutboxScheduler の登録の仕方(ds 7.4 Triggers、PLAN-2 5)をテスト用 WorkManager で確かめる。
 * 本物の OutboxWorker の代わりに ProbeWorker を使う(実メールは送らない)。
 */
class OutboxSchedulerTest {
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var wm: WorkManager
    private lateinit var scheduler: OutboxScheduler
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 振る舞いを切り替えられる代わりのワーカー。 */
    class ProbeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result = when (mode) {
            Mode.RETRY -> Result.retry()
            Mode.BLOCK -> {
                started.complete(Unit)
                release.await()
                Result.success()
            }
        }

        enum class Mode { RETRY, BLOCK }

        companion object {
            @Volatile var mode = Mode.RETRY
            @Volatile var started = CompletableDeferred<Unit>()
            @Volatile var release = CompletableDeferred<Unit>()
        }
    }

    @Before
    fun setUp() {
        ProbeWorker.mode = ProbeWorker.Mode.RETRY
        ProbeWorker.started = CompletableDeferred()
        ProbeWorker.release = CompletableDeferred()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx, config)
        wm = WorkManager.getInstance(ctx)
        scheduler = OutboxScheduler(wm, scope, ProbeWorker::class.java)
    }

    @After
    fun tearDown() {
        ProbeWorker.release.complete(Unit)
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        wm.cancelAllWork().result.get()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private fun infos(name: String): List<WorkInfo> = wm.getWorkInfosForUniqueWork(name).get()

    private fun single(name: String): WorkInfo = infos(name).single()

    private fun driver() = WorkManagerTestInitHelper.getTestDriver(ctx)!!

    // ワーカーは別スレッドで動くので、状態が変わるまで待つ
    private fun awaitState(id: UUID, state: WorkInfo.State, attempts: Int? = null) = runBlocking {
        withTimeout(10_000) {
            while (true) {
                val info = wm.getWorkInfoById(id).get()
                if (info != null && info.state == state && (attempts == null || info.runAttemptCount == attempts)) break
                delay(20)
            }
        }
    }

    @Test
    fun enqueueDefaultTwiceKeepsOneWork() {
        scheduler.enqueueDefault()
        val first = single(Constants.WORK_OUTBOX_SEND).id
        scheduler.enqueueDefault()
        assertEquals(first, single(Constants.WORK_OUTBOX_SEND).id)
    }

    @Test
    fun requestHasConnectedConstraintAndTag() {
        scheduler.enqueueDefault()
        val info = single(Constants.WORK_OUTBOX_SEND)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertTrue(Constants.WORK_TAG_OUTBOX in info.tags)
        // 回線の制約が満たされるまでは動かない
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
    }

    @Test
    fun triggerNowWithoutWorkEnqueues() = runBlocking {
        scheduler.triggerNowJob().join()
        assertEquals(WorkInfo.State.ENQUEUED, single(Constants.WORK_OUTBOX_SEND).state)
        assertTrue(infos(Constants.WORK_OUTBOX_SEND_NEXT).isEmpty())
    }

    @Test
    fun retryIsRescheduledAndTriggerNowReplacesBackoff() = runBlocking {
        scheduler.enqueueDefault()
        val id = single(Constants.WORK_OUTBOX_SEND).id
        driver().setAllConstraintsMet(id)
        // retry を返すと WorkManager がバックオフ付きで登録し直す
        awaitState(id, WorkInfo.State.ENQUEUED, attempts = 1)
        scheduler.triggerNowJob().join()
        val replaced = single(Constants.WORK_OUTBOX_SEND)
        // REPLACE で新しい依頼になり、バックオフ(試行回数)がリセットされる
        assertNotEquals(id, replaced.id)
        assertEquals(0, replaced.runAttemptCount)
        // 置き換えられた古い依頼は取り消されて消える(残っていても CANCELLED)
        val old = wm.getWorkInfoById(id).get()
        assertTrue(old == null || old.state == WorkInfo.State.CANCELLED)
        assertTrue(infos(Constants.WORK_OUTBOX_SEND_NEXT).isEmpty())
    }

    @Test
    fun triggerNowWhileRunningEnqueuesFollowUpWithoutReplacing() = runBlocking {
        ProbeWorker.mode = ProbeWorker.Mode.BLOCK
        scheduler.enqueueDefault()
        val id = single(Constants.WORK_OUTBOX_SEND).id
        driver().setAllConstraintsMet(id)
        withTimeout(10_000) { ProbeWorker.started.await() }
        awaitState(id, WorkInfo.State.RUNNING)
        scheduler.triggerNowJob().join()
        // 実行中の送信は取り消さない
        val current = single(Constants.WORK_OUTBOX_SEND)
        assertEquals(id, current.id)
        assertEquals(WorkInfo.State.RUNNING, current.state)
        val next = single(Constants.WORK_OUTBOX_SEND_NEXT)
        assertEquals(WorkInfo.State.ENQUEUED, next.state)
        assertEquals(NetworkType.CONNECTED, next.constraints.requiredNetworkType)
        // もう一度押しても後続は1つのまま(KEEP)
        scheduler.triggerNowJob().join()
        assertEquals(next.id, single(Constants.WORK_OUTBOX_SEND_NEXT).id)
        ProbeWorker.release.complete(Unit)
        awaitState(id, WorkInfo.State.SUCCEEDED)
    }
}
