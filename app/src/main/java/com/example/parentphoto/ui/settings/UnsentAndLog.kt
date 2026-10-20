package com.example.parentphoto.ui.settings

import androidx.annotation.StringRes
import com.example.parentphoto.R
import com.example.parentphoto.mail.ItemId
import com.example.parentphoto.mail.SendLogResult
import com.example.parentphoto.outbox.AttentionReason
import com.example.parentphoto.outbox.ItemState
import com.example.parentphoto.outbox.OutboxItemView
import com.example.parentphoto.outbox.Phase
import com.example.parentphoto.outbox.ResendOutcome
import com.example.parentphoto.outbox.SendLogEntry
import kotlinx.coroutines.flow.Flow

/** 未送信一覧と送信ログの窓口(ds 9.6 / 9.7)。本物は OutboxRepository。 */
interface UnsentOutbox {
    /** 待機中を撮影順、続いて要確認を要確認になった順(DAO が並べる)。 */
    val items: Flow<List<OutboxItemView>>

    /** 新しい順に最大50件。 */
    val sendLog: Flow<List<SendLogEntry>>

    suspend fun resendNow(itemId: ItemId): ResendOutcome

    suspend fun deleteManual(itemId: ItemId)
}

/** 未送信一覧の2つの群。並びは窓口の順をそのまま保つ。 */
data class UnsentGroups(val waiting: List<OutboxItemView>, val attention: List<OutboxItemView>) {
    val isEmpty: Boolean get() = waiting.isEmpty() && attention.isEmpty()
}

/** 行のボタンの状態。画像が読めないときは画面側で再送も押せなくする。 */
data class RowButtons(val resend: Boolean, val delete: Boolean)

/** 未送信一覧・送信ログの純粋な処理(群分け・ボタンの可否・文言の対応)。 */
object UnsentLogic {

    fun group(items: List<OutboxItemView>): UnsentGroups = UnsentGroups(
        waiting = items.filter { it.state == ItemState.WAITING },
        attention = items.filter { it.state == ItemState.ATTENTION },
    )

    /**
     * ds 9.6 / 7.5: 待機中(送信待ち)は再送・削除とも可。猶予中・送信中は両方不可(待機中と表示)。
     * 要確認は削除可、再送は宛先削除以外で可。
     */
    fun buttons(item: OutboxItemView): RowButtons = when (item.state) {
        ItemState.WAITING ->
            if (item.phase == Phase.QUEUED) RowButtons(resend = true, delete = true)
            else RowButtons(resend = false, delete = false)
        ItemState.ATTENTION -> RowButtons(resend = item.reason != AttentionReason.RECIPIENT_DELETED, delete = true)
    }

    @StringRes
    fun stateLabel(item: OutboxItemView): Int =
        if (item.state == ItemState.WAITING) R.string.state_waiting else R.string.state_attention

    @StringRes
    fun reasonLabel(reason: AttentionReason?): Int? = when (reason) {
        AttentionReason.AUTH -> R.string.reason_auth
        AttentionReason.PERMANENT -> R.string.reason_permanent
        AttentionReason.RETRY_EXPIRED -> R.string.reason_retry_expired
        AttentionReason.RECIPIENT_DELETED -> R.string.reason_recipient_deleted
        null -> null
    }

    /** 送信ログの結果の文言(ds 9.7 の対応表)。 */
    @StringRes
    fun resultLabel(result: SendLogResult): Int = when (result) {
        SendLogResult.SENT -> R.string.log_sent
        SendLogResult.FAILED -> R.string.log_failed
        SendLogResult.QUEUED_OFFLINE -> R.string.log_queued_offline
        SendLogResult.DELETED_MANUAL -> R.string.log_deleted_manual
        SendLogResult.DELETED_OVERFLOW -> R.string.log_deleted_overflow
        SendLogResult.RESEND_REQUESTED -> R.string.log_resend_requested
        SendLogResult.TEST_OK -> R.string.log_test_ok
        SendLogResult.TEST_FAILED -> R.string.log_test_failed
    }

    /** 短いコードは失敗とテスト失敗のときだけ出す。それ以外は空。 */
    fun codeShown(entry: SendLogEntry): String =
        if (entry.result == SendLogResult.FAILED || entry.result == SendLogResult.TEST_FAILED) entry.code.orEmpty()
        else ""
}
