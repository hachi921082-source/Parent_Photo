package com.example.parentphoto.outbox

import org.junit.Assert.assertEquals
import org.junit.Test

class BatchPlannerTest {
    private val mb = 1024L * 1024
    private val max = 15 * mb

    private fun item(id: String, recipient: String, captured: Long, size: Long) = OutboxItemEntity(
        id = id, recipientId = recipient, capturedAtMillis = captured, retryStartMillis = 0,
        attentionSinceMillis = null, state = ItemState.WAITING, phase = Phase.QUEUED,
        attentionReason = null, resendPending = false, fileSize = size,
    )

    private fun ids(batches: List<Batch>) = batches.map { b -> b.items.map { it.id } }

    @Test
    fun groupsPerRecipientInCaptureOrder() {
        val items = listOf(
            item("c", "r1", 300, mb), item("a", "r1", 100, mb), item("b", "r1", 200, mb),
        )
        val out = BatchPlanner.plan(items, max)
        assertEquals(listOf(listOf("a", "b", "c")), ids(out))
        assertEquals("r1", out[0].recipientId)
    }

    @Test
    fun sameCaptureTimeOrderedById() {
        val out = BatchPlanner.plan(listOf(item("y", "r1", 100, 1), item("x", "r1", 100, 1)), max)
        assertEquals(listOf(listOf("x", "y")), ids(out))
    }

    @Test
    fun exactlyMaxStaysInOneBatch() {
        val out = BatchPlanner.plan(listOf(item("a", "r1", 1, 10 * mb), item("b", "r1", 2, 5 * mb)), max)
        assertEquals(listOf(listOf("a", "b")), ids(out))
    }

    @Test
    fun oneByteOverSplits() {
        val out = BatchPlanner.plan(listOf(item("a", "r1", 1, 10 * mb), item("b", "r1", 2, 5 * mb + 1)), max)
        assertEquals(listOf(listOf("a"), listOf("b")), ids(out))
    }

    @Test
    fun oversizeSingleItemFormsItsOwnBatch() {
        val out = BatchPlanner.plan(
            listOf(item("a", "r1", 1, mb), item("big", "r1", 2, 20 * mb), item("c", "r1", 3, mb)), max,
        )
        assertEquals(listOf(listOf("a"), listOf("big"), listOf("c")), ids(out))
    }

    @Test
    fun multipleRecipientsOrderedByOldestFirstItem() {
        val items = listOf(
            item("r2-old", "r2", 50, mb), item("r1-a", "r1", 100, mb),
            item("r2-new", "r2", 400, mb), item("r1-b", "r1", 200, 14 * mb + 1),
        )
        // r1: [r1-a] [r1-b](1MB + 14MB+1 > 15MB で分割)、r2: [r2-old, r2-new]
        val out = BatchPlanner.plan(items, max)
        assertEquals(listOf(listOf("r2-old", "r2-new"), listOf("r1-a"), listOf("r1-b")), ids(out))
        assertEquals(listOf("r2", "r1", "r1"), out.map { it.recipientId })
    }

    @Test
    fun emptyInputGivesNoBatches() {
        assertEquals(emptyList<Batch>(), BatchPlanner.plan(emptyList(), max))
    }
}
