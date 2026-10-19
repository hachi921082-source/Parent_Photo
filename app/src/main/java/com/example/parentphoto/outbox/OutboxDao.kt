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

    // 要確認への移行(上限・通知は PLAN-2 の moveToAttention が担う)
    @Query(
        """
        UPDATE outbox_item
        SET state = 'ATTENTION', phase = 'QUEUED', attentionReason = :reason,
            attentionSinceMillis = :now, resendPending = 0
        WHERE id IN (:ids)
        """,
    )
    suspend fun setAttention(ids: List<String>, reason: AttentionReason, now: Long): Int

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
