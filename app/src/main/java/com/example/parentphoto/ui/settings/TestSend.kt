package com.example.parentphoto.ui.settings

import androidx.annotation.StringRes
import com.example.parentphoto.R
import com.example.parentphoto.mail.MailCode
import com.example.parentphoto.mail.MailResult
import com.example.parentphoto.settings.Recipient

/** テスト送信の窓口(ds 9.5)。本物は SmtpSender.sendTest(60秒の上限と TEST_OK / TEST_FAILED の記録を含む)。 */
fun interface TestMailSender {
    suspend fun sendTest(recipient: Recipient): MailResult
}

/** テスト送信の結果ダイアログの文言。arg は「送信に失敗しました（{code}）」の code。 */
data class TestSendResult(@StringRes val messageRes: Int, val arg: String? = null)

/** テスト送信の進み具合。Sending の間は閉じられない進行ダイアログ、Done は OK でだけ閉じる結果ダイアログ。 */
sealed interface TestSendPhase {
    data object Idle : TestSendPhase
    data object Sending : TestSendPhase
    data class Done(val result: TestSendResult) : TestSendPhase
}

/** テスト送信の純粋な処理(案内の優先順位・結果の文言・送り先の選び方)。 */
object TestSendLogic {

    /**
     * ボタンを押せないときの案内(ds 9.5、上から先に当てはまったもの)。null なら押せる。
     * 1. 送り先なし 2. 保存済みのメール設定が不正・未設定(ds 8.3) 3. フォームに未保存の変更あり。
     */
    @StringRes
    fun hint(recipientCount: Int, savedSetupValid: Boolean?, formDirty: Boolean): Int? = when {
        recipientCount == 0 -> R.string.hint_no_recipients
        // 保存済み設定の読み込み前(null)は案内を出さない(開いた直後に一瞬出るのを避ける)
        savedSetupValid == null -> null
        !savedSetupValid -> R.string.hint_save_mail_first
        formDirty -> R.string.hint_save_first
        else -> null
    }

    /** テスト送信を押せるか。案内が出ない状態で、保存済み設定が読み込み済みで有効なときだけ。 */
    fun canSend(recipientCount: Int, savedSetupValid: Boolean?, formDirty: Boolean): Boolean =
        recipientCount > 0 && savedSetupValid == true && hint(recipientCount, savedSetupValid, formDirty) == null

    /** 選んだ送り先。未選択や削除済みなら残っている先頭。送り先がなければ null。 */
    fun selected(recipients: List<Recipient>, selectedId: String?): Recipient? =
        recipients.firstOrNull { it.id == selectedId } ?: recipients.firstOrNull()

    /** 結果ダイアログの文言(ds 9.5)。その他は送信ログと同じコード文字列を添える。 */
    fun resultOf(result: MailResult): TestSendResult = when (result) {
        MailResult.Success -> TestSendResult(R.string.test_result_ok)
        is MailResult.Failure -> when (result.code) {
            MailCode.AUTH -> TestSendResult(R.string.test_result_auth)
            MailCode.NETWORK, MailCode.TIMEOUT -> TestSendResult(R.string.test_result_network)
            MailCode.REJECTED -> TestSendResult(R.string.test_result_rejected)
            MailCode.OTHER -> TestSendResult(R.string.test_result_other, MailCode.OTHER.name)
        }
        // テスト送信では起きない(回線判定・送り先の読み込みをしない)。念のためその他として出す
        MailResult.Offline, MailResult.RecipientMissing -> TestSendResult(R.string.test_result_other, MailCode.OTHER.name)
    }
}
