package com.example.parentphoto.outbox

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.example.parentphoto.mail.SendLogResult

/**
 * 未送信キューの1項目(ds 7.1)。写真本体は filesDir/outbox/{id}.jpg。
 * 送り先のメールアドレスは保持しない(送信時に recipientId から引く)。
 */
@Entity(
    tableName = "outbox_item",
    indices = [Index("state"), Index("recipientId")],
)
data class OutboxItemEntity(
    @PrimaryKey val id: String,
    val recipientId: String,
    val capturedAtMillis: Long,
    // 24時間の再送期間の起点
    val retryStartMillis: Long,
    val attentionSinceMillis: Long?,
    val state: ItemState,
    val phase: Phase,
    val attentionReason: AttentionReason?,
    // 要確認の行でのみ true になる(枠が空くのを待っている)
    val resendPending: Boolean,
    // 作成時の file.length()。まとめ送りの合計サイズ計算に使う
    val fileSize: Long,
)

/** 送信ログの1行(ds 6.4)。recipientName は書き込み時点の表示名。 */
@Entity(tableName = "send_log")
data class SendLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMillis: Long,
    val recipientName: String?,
    val result: SendLogResult,
    val code: String?,
)

/** enum を名前の文字列で保存する(序数は並び替えで壊れるため使わない)。 */
class OutboxConverters {
    @TypeConverter fun stateToText(v: ItemState): String = v.name
    @TypeConverter fun textToState(v: String): ItemState = ItemState.valueOf(v)

    @TypeConverter fun phaseToText(v: Phase): String = v.name
    @TypeConverter fun textToPhase(v: String): Phase = Phase.valueOf(v)

    @TypeConverter fun reasonToText(v: AttentionReason?): String? = v?.name
    @TypeConverter fun textToReason(v: String?): AttentionReason? = v?.let { AttentionReason.valueOf(it) }

    @TypeConverter fun resultToText(v: SendLogResult): String = v.name
    @TypeConverter fun textToResult(v: String): SendLogResult = SendLogResult.valueOf(v)
}
