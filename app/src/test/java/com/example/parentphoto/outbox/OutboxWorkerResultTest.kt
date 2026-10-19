package com.example.parentphoto.outbox

import androidx.work.ListenableWorker.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** 再送ワーカーの結果の読み替え(success / retry、想定外の例外は retry、キャンセルはそのまま)。 */
class OutboxWorkerResultTest {

    @Test fun successMapsToSuccess() = runTest {
        assertEquals(Result.success(), OutboxWorker.toResult { RunOutcome.SUCCESS })
    }

    @Test fun retryMapsToRetry() = runTest {
        assertEquals(Result.retry(), OutboxWorker.toResult { RunOutcome.RETRY })
    }

    @Test fun unexpectedExceptionMapsToRetry() = runTest {
        assertEquals(Result.retry(), OutboxWorker.toResult { throw IllegalStateException("db broken") })
    }

    @Test fun cancellationPropagates() = runTest {
        try {
            OutboxWorker.toResult { throw CancellationException("stopped") }
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
    }
}
