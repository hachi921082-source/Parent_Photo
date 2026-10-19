package com.example.parentphoto.outbox

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.parentphoto.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 再送ワーカーの登録(ds 7.4、outbox-worker PLAN-2 2)。Room には触らない(状態の書き込みは呼び出し側が先に済ませる)。
 * enqueueDefault: outbox-send を KEEP で登録(実行中・バックオフ待ちのものは乱さない)。
 * triggerNow: outbox-send が実行中でなければ REPLACE(バックオフ待ちを解いてすぐ動かす)、
 *   実行中なら REPLACE せず後続の outbox-send-next を KEEP で登録する(実行中の送信を取り消さない)。
 * 判定と登録の間に実行が始まって REPLACE で取り消されても、取り込んだ行は送信待ちに戻り、置き換えた実行が拾う。
 */
class OutboxScheduler(
    private val wm: WorkManager,
    // アプリ全体のスコープ(triggerNow の状態確認を裏で行う)
    private val scope: CoroutineScope,
    // テストでは代わりのワーカーを使う
    private val workerClass: Class<out ListenableWorker> = OutboxWorker::class.java,
) : WorkScheduler {

    override fun enqueueDefault() {
        try {
            wm.enqueueUniqueWork(Constants.WORK_OUTBOX_SEND, ExistingWorkPolicy.KEEP, request())
            WorkerLog.i("enqueueDefault")
        } catch (e: Exception) {
            // 呼び出し側(未送信キューの書き込み直後)を失敗させない。次のきっかけ(起動時の復旧など)で登録し直される
            WorkerLog.i("enqueueDefault failed: ${e.javaClass.simpleName}")
        }
    }

    override fun triggerNow() {
        triggerNowJob()
    }

    /** triggerNow の本体。テストで完了を待てるよう Job を返す。 */
    internal fun triggerNowJob(): Job = scope.launch {
        try {
            triggerNowNow()
        } catch (e: Exception) {
            // 登録に失敗しても次のきっかけ(起動時の復旧など)で登録し直される
            WorkerLog.i("triggerNow failed: ${e.javaClass.simpleName}")
        }
    }

    internal suspend fun triggerNowNow() {
        val running = withContext(Dispatchers.IO) {
            wm.getWorkInfosForUniqueWork(Constants.WORK_OUTBOX_SEND).get()
        }.any { it.state == WorkInfo.State.RUNNING }
        if (running) {
            wm.enqueueUniqueWork(Constants.WORK_OUTBOX_SEND_NEXT, ExistingWorkPolicy.KEEP, request())
        } else {
            wm.enqueueUniqueWork(Constants.WORK_OUTBOX_SEND, ExistingWorkPolicy.REPLACE, request())
        }
        WorkerLog.i("triggerNow running=$running")
    }

    /** 1回分の依頼。回線あり、15分からの指数バックオフ(上限は WorkManager が約5時間に抑える)。 */
    internal fun request(): OneTimeWorkRequest = buildOutboxRequest(workerClass)
}

/** 再送ワーカーの依頼を作る(両方の一意ワークで同じ制約・バックオフ)。 */
internal fun buildOutboxRequest(workerClass: Class<out ListenableWorker>): OneTimeWorkRequest =
    OneTimeWorkRequest.Builder(workerClass)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Constants.BACKOFF_INITIAL_MIN, TimeUnit.MINUTES)
        .addTag(Constants.WORK_TAG_OUTBOX)
        .build()
