package com.example.parentphoto.outbox

import com.example.parentphoto.mail.ItemId
import com.example.parentphoto.mail.SendLogResult
import java.io.File

/** 項目の大分類(ds 7.2)。WAITING = 待機中(件数に数える)、ATTENTION = 要確認(数えない)。 */
enum class ItemState { WAITING, ATTENTION }

/** 待機中の項目の段階(ds 2.4)。ATTENTION の行では QUEUED として保存する。 */
enum class Phase { CANCEL_WINDOW, SENDING_FG, SENDING_BG, QUEUED }

/** 要確認になった理由(ds 7.5)。 */
enum class AttentionReason { AUTH, PERMANENT, RETRY_EXPIRED, RECIPIENT_DELETED }

/** 未送信一覧の1行。recipientName が null なら削除された宛先。 */
data class OutboxItemView(
    val id: ItemId,
    val recipientId: String,
    val recipientName: String?,
    val capturedAtMillis: Long,
    val state: ItemState,
    val phase: Phase,
    val reason: AttentionReason?,
    val file: File,
)

/** 送信ログの1行。recipientName が null なら削除された宛先(テスト送信では送り先名)。 */
data class SendLogEntry(
    val id: Long,
    val timeMillis: Long,
    val recipientName: String?,
    val result: SendLogResult,
    val code: String?,
)

/** 待機中が上限(5件)に達しているため新しい項目を作れない(ds 2.4)。 */
class OutboxFullException : IllegalStateException("outbox active cap reached")

/** 要確認への移行1回分の結果。moved = 要確認にした件数、overflowDeleted = 上限超過で自動削除した件数。 */
data class AttentionOutcome(val moved: Int, val overflowDeleted: Int)

/** 今すぐ再送の結果(ds 7.5)。Started = 待機中になった、Waiting = 枠待ち、NotAllowed = 再送できない項目。 */
enum class ResendOutcome { Started, Waiting, NotAllowed }
