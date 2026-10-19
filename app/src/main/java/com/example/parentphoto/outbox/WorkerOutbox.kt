package com.example.parentphoto.outbox

import com.example.parentphoto.mail.ItemId
import com.example.parentphoto.mail.MailCode

/**
 * 再送ワーカーが使う未送信キューの操作(outbox-queue のワーカー用呼び出し)。
 * 実体は OutboxRepository。JVM テストで偽物に差し替えるために分けている。
 * 状態の変更はすべてここを通し、ワーカー自身は DB を触らない。
 */
interface WorkerOutbox {
    suspend fun awaitRecovery()
    suspend fun expireOld(now: Long): Int
    suspend fun markAllQueuedAttention(reason: AttentionReason): Int
    suspend fun markRecipientMissing(ids: List<ItemId>)
    suspend fun queuedSnapshot(): List<OutboxItemEntity>
    suspend fun claimBatch(batch: Batch): List<OutboxItemEntity>
    suspend fun releaseBatch(ids: List<ItemId>)
    suspend fun finishBatchSent(ids: List<ItemId>, recipientId: String)
    suspend fun finishBatchTransient(ids: List<ItemId>, code: MailCode)
    suspend fun finishBatchAttention(ids: List<ItemId>, reason: AttentionReason, code: MailCode)
}

/** OutboxRepository をそのまま WorkerOutbox として使うための橋渡し(処理は足さない)。 */
fun OutboxRepository.asWorkerOutbox(): WorkerOutbox {
    val repo = this
    return object : WorkerOutbox {
        override suspend fun awaitRecovery() = repo.awaitRecovery()
        override suspend fun expireOld(now: Long) = repo.expireOld(now)
        override suspend fun markAllQueuedAttention(reason: AttentionReason) = repo.markAllQueuedAttention(reason)
        override suspend fun markRecipientMissing(ids: List<ItemId>) = repo.markRecipientMissing(ids)
        override suspend fun queuedSnapshot() = repo.queuedSnapshot()
        override suspend fun claimBatch(batch: Batch) = repo.claimBatch(batch)
        override suspend fun releaseBatch(ids: List<ItemId>) = repo.releaseBatch(ids)
        override suspend fun finishBatchSent(ids: List<ItemId>, recipientId: String) =
            repo.finishBatchSent(ids, recipientId)
        override suspend fun finishBatchTransient(ids: List<ItemId>, code: MailCode) =
            repo.finishBatchTransient(ids, code)
        override suspend fun finishBatchAttention(ids: List<ItemId>, reason: AttentionReason, code: MailCode) =
            repo.finishBatchAttention(ids, reason, code)
    }
}
