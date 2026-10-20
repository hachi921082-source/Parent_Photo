package com.example.parentphoto.outbox

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** outbox_item の読み書き。呼び出し元は OutboxRepository のみ(UI・ワーカーは直接触らない)。 */
@Dao
interface OutboxDao {
    // 待機中の件数(要確認は数えない、ds 2.4)
    @Query("SELECT COUNT(*) FROM outbox_item WHERE state = 'WAITING'")
    fun activeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox_item WHERE state = 'ATTENTION'")
    fun attentionCount(): Flow<Int>

    // トランザクション内の上限チェック用(Flowではなく1回読み)
    @Query("SELECT COUNT(*) FROM outbox_item WHERE state = 'WAITING'")
    suspend fun activeCountNow(): Int

    // 未送信一覧: 待機中を撮影順、続いて要確認を要確認になった順
    @Query(
        """
        SELECT * FROM outbox_item
        ORDER BY CASE WHEN state = 'WAITING' THEN 0 ELSE 1 END,
                 CASE WHEN state = 'WAITING' THEN capturedAtMillis ELSE attentionSinceMillis END,
                 id
        """,
    )
    fun observeAll(): Flow<List<OutboxItemEntity>>

    @Query("SELECT * FROM outbox_item WHERE id = :id")
    suspend fun get(id: String): OutboxItemEntity?

    @Query("SELECT * FROM outbox_item WHERE id IN (:ids)")
    suspend fun getAll(ids: List<String>): List<OutboxItemEntity>

    @Insert
    suspend fun insert(item: OutboxItemEntity)

    @Query("DELETE FROM outbox_item WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("DELETE FROM outbox_item WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<String>): Int

    // 待機中の行のみ更新する(消えた行・要確認の行は何もしない)
    @Query("UPDATE outbox_item SET phase = :phase WHERE id = :id AND state = 'WAITING'")
    suspend fun updatePhase(id: String, phase: Phase): Int

    @Query("UPDATE outbox_item SET phase = :phase WHERE id IN (:ids) AND state = 'WAITING'")
    suspend fun updatePhaseAll(ids: List<String>, phase: Phase): Int

    // 要確認への移行。まだ待機中の行だけを動かす(上限・通知は OutboxRepository.moveToAttention が担う)
    @Query(
        """
        UPDATE outbox_item
        SET state = 'ATTENTION', phase = 'QUEUED', attentionReason = :reason,
            attentionSinceMillis = :now, resendPending = 0
        WHERE id IN (:ids) AND state = 'WAITING'
        """,
    )
    suspend fun setAttention(ids: List<String>, reason: AttentionReason, now: Long): Int

    @Query("SELECT COUNT(*) FROM outbox_item WHERE state = 'ATTENTION'")
    suspend fun attentionCountNow(): Int

    // 上限超過で消す候補: 要確認になったのが古い順(同時なら撮影の古い順、次にid)
    @Query(
        """
        SELECT * FROM outbox_item WHERE state = 'ATTENTION'
        ORDER BY attentionSinceMillis, capturedAtMillis, id
        LIMIT :limit
        """,
    )
    suspend fun oldestAttention(limit: Int): List<OutboxItemEntity>

    // 起動時の復旧: 前のプロセスで処理中だった行を送信待ちに戻す(ds 7.6)
    @Query(
        """
        UPDATE outbox_item SET phase = 'QUEUED'
        WHERE state = 'WAITING' AND phase IN ('CANCEL_WINDOW', 'SENDING_FG', 'SENDING_BG')
        """,
    )
    suspend fun requeueInFlight(): Int

    // 画面が閉じられて止まった前面の流れの行だけを送信待ちにする(Q69)。消えた・送信待ち・要確認の行は動かさない
    @Query(
        """
        UPDATE outbox_item SET phase = 'QUEUED'
        WHERE id = :id AND state = 'WAITING' AND phase IN ('CANCEL_WINDOW', 'SENDING_FG')
        """,
    )
    suspend fun requeueForeground(id: String): Int

    @Query("SELECT id FROM outbox_item")
    suspend fun allIds(): List<String>

    @Query("SELECT id FROM outbox_item WHERE state = 'WAITING' AND phase = 'QUEUED'")
    suspend fun queuedIds(): List<String>

    @Query("SELECT id FROM outbox_item WHERE recipientId = :recipientId AND state = 'WAITING' AND phase = 'QUEUED'")
    suspend fun queuedIdsForRecipient(recipientId: String): List<String>

    // 24時間を過ぎた送信待ち(retryStartMillis < threshold。ちょうど24時間は対象外)
    @Query("SELECT id FROM outbox_item WHERE state = 'WAITING' AND phase = 'QUEUED' AND retryStartMillis < :threshold")
    suspend fun expiredQueuedIds(threshold: Long): List<String>

    @Query("SELECT * FROM outbox_item WHERE state = 'WAITING' AND phase = 'QUEUED'")
    suspend fun queuedItems(): List<OutboxItemEntity>

    // ワーカーの取り込み対象(まだ待機中・送信待ちの行だけ)
    @Query("SELECT * FROM outbox_item WHERE id IN (:ids) AND state = 'WAITING' AND phase = 'QUEUED'")
    suspend fun queuedAmong(ids: List<String>): List<OutboxItemEntity>

    @Query(
        """
        SELECT * FROM outbox_item
        WHERE state = 'ATTENTION' AND resendPending = 1
        ORDER BY attentionSinceMillis, capturedAtMillis, id
        """,
    )
    suspend fun pendingResend(): List<OutboxItemEntity>

    // 枠待ち(今すぐ再送・設定保存の後、枠が空くのを待つ要確認)の件数。繰り上げの監視に使う
    @Query("SELECT COUNT(*) FROM outbox_item WHERE state = 'ATTENTION' AND resendPending = 1")
    fun pendingResendCount(): Flow<Int>

    // 今すぐ再送: 宛先削除以外の要確認を枠待ちにする
    @Query(
        """
        UPDATE outbox_item SET resendPending = 1
        WHERE id = :id AND state = 'ATTENTION' AND attentionReason != 'RECIPIENT_DELETED'
        """,
    )
    suspend fun setResendPending(id: String): Int

    // 設定保存・テスト成功: 再開できる理由の要確認をまとめて枠待ちにする(宛先削除は除く)
    @Query(
        """
        UPDATE outbox_item SET resendPending = 1
        WHERE state = 'ATTENTION' AND attentionReason IN ('AUTH', 'PERMANENT', 'RETRY_EXPIRED')
        """,
    )
    suspend fun markResumablePending(): Int

    // 繰り上げ: 要確認 -> 待機中・送信待ち。24時間の起点を今にする
    @Query(
        """
        UPDATE outbox_item
        SET state = 'WAITING', phase = 'QUEUED', retryStartMillis = :now,
            attentionSinceMillis = NULL, attentionReason = NULL, resendPending = 0
        WHERE id = :id AND state = 'ATTENTION'
        """,
    )
    suspend fun promoteRow(id: String, now: Long): Int

    // 待機中・送信待ちの今すぐ再送: 24時間の起点だけを今にする
    @Query("UPDATE outbox_item SET retryStartMillis = :now WHERE id = :id AND state = 'WAITING' AND phase = 'QUEUED'")
    suspend fun restartWindow(id: String, now: Long): Int

    // 送り先削除の確認ダイアログ用(状態を問わない)
    @Query("SELECT COUNT(*) FROM outbox_item WHERE recipientId = :recipientId")
    suspend fun countForRecipient(recipientId: String): Int
}

/** send_log の読み書き。 */
@Dao
interface SendLogDao {
    @Insert
    suspend fun insert(entry: SendLogEntity): Long

    @Query("SELECT * FROM send_log ORDER BY id DESC LIMIT :max")
    fun observeNewest(max: Int): Flow<List<SendLogEntity>>

    // 新しい順に max 件だけ残す
    @Query("DELETE FROM send_log WHERE id NOT IN (SELECT id FROM send_log ORDER BY id DESC LIMIT :max)")
    suspend fun trim(max: Int): Int
}
