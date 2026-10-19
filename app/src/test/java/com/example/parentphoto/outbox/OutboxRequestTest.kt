package com.example.parentphoto.outbox

import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import com.example.parentphoto.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** 再送ワーカーの依頼の中身(PLAN-2 1)。回線の制約・15分からの指数バックオフ・タグ。 */
class OutboxRequestTest {
    private val request = buildOutboxRequest(OutboxWorker::class.java)

    @Test fun requiresConnectedNetwork() =
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)

    @Test fun exponentialBackoffFrom15Minutes() {
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(TimeUnit.MINUTES.toMillis(15), request.workSpec.backoffDelayDuration)
    }

    @Test fun noInitialDelayNotExpeditedAndTagged() {
        assertEquals(0L, request.workSpec.initialDelay)
        assertEquals(false, request.workSpec.expedited)
        assertTrue(Constants.WORK_TAG_OUTBOX in request.tags)
    }
}
