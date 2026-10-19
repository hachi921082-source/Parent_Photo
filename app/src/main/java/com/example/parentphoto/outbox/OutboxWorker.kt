package com.example.parentphoto.outbox

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.parentphoto.ParentPhotoApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * 未送信の写真を送る再送ワーカー(ds 7.4)。手順の本体は OutboxRunner にあり、ここは WorkManager との橋渡しだけ。
 * 依存はアプリの Application から取る(独自の WorkerFactory は使わない)。
 * WorkManager が起こしたプロセスでも ParentPhotoApp.onCreate で復旧が始まり、OutboxRunner がその完了を待つ。
 *
 * キャンセル(REPLACE・回線喪失)は例外としてそのまま伝わり、取り込んだ行は送信待ちに戻る(PLAN-1 3)。
 * 登録(一意ワーク名・回線の制約・15分からの指数バックオフ)は OutboxScheduler が行う。
 */
class OutboxWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as ParentPhotoApp
        val settingsRepo = app.settingsRepository
        val files = OutboxFiles(app.filesDir)
        val runner = OutboxRunner(
            outbox = app.outboxRepository.asWorkerOutbox(),
            sender = BatchSender { app.smtpSender.sendBatch(it) },
            recipients = { settingsRepo.settings.first().recipients },
            setupRequired = { settingsRepo.isSetupRequired() },
            photoFile = { files.file(it) },
        )
        return toResult { runner.run() }
    }

    companion object {
        /**
         * 実行結果を WorkManager の Result に読み替える。
         * 想定外の例外(DB の失敗など)はワーカーを失敗扱いにせず retry にする(写真を待機中のまま残し、バックオフ後に再実行)。
         * Logcat には例外のクラス名だけを出す(メッセージにアドレス等が含まれ得るため)。キャンセルはそのまま伝える。
         */
        internal suspend fun toResult(run: suspend () -> RunOutcome): Result =
            try {
                when (run()) {
                    RunOutcome.SUCCESS -> Result.success()
                    RunOutcome.RETRY -> Result.retry()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                WorkerLog.i("run failed: ${e.javaClass.name}")
                Result.retry()
            }
    }
}
