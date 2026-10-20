package com.example.parentphoto.ui.settings

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.example.parentphoto.settings.Recipient
import com.example.parentphoto.settings.ValidationError
import com.example.parentphoto.settings.Validators
import com.example.parentphoto.util.Constants
import java.util.regex.Pattern

/** 送り先の保存先の窓口(即時保存、ds 9.1 1)。本物は SettingsRepository。 */
interface RecipientStore {
    /** 末尾に追加する。上限(6件)なら追加せず null。 */
    suspend fun addRecipient(name: String, email: String): Recipient?

    /** 並び位置と id を保って更新する。該当がなければ false。 */
    suspend fun updateRecipient(id: String, name: String, email: String): Boolean

    suspend fun deleteRecipient(id: String)
}

/** 送り先削除に関わる未送信キューの窓口(ds 7.5)。本物は OutboxRepository。 */
interface RecipientOutbox {
    /** 確認ダイアログに出す、その送り先の未送信(待機中と要確認)の枚数。 */
    suspend fun countForRecipient(recipientId: String): Int

    /** 送り先を消した後に呼ぶ。待機中の写真を要確認(宛先削除)へ移す。 */
    suspend fun onRecipientDeleted(recipientId: String)
}

/** 送り先ダイアログの入力欄(画面順)。 */
enum class RecipientField { NAME, EMAIL }

/**
 * 送り先の追加・編集ダイアログの状態(ds 9.1 1)。editId が null なら追加。
 * 外側タップ・戻るでは保存せずに閉じる(確認なし)。
 */
data class RecipientDialogState(
    val editId: String? = null,
    val name: String = "",
    val email: String = "",
    val errors: Map<RecipientField, ValidationError> = emptyMap(),
)

/** 送り先削除の確認ダイアログの状態。queuedCount はその送り先の未送信の枚数。 */
data class DeleteConfirmState(val recipientId: String, val queuedCount: Int)

/** 送り先ダイアログの純粋な処理(検証・文字数上限・キーボード)。 */
object RecipientFormLogic {

    fun forAdd() = RecipientDialogState()

    fun forEdit(r: Recipient) = RecipientDialogState(editId = r.id, name = r.name, email = r.email)

    // メールアドレスは上限で入力を止める。表示名は止めずに保存時の検証でエラーにする(ds 9.3)
    fun limitEmail(input: String): String = input.take(Constants.EMAIL_MAX)

    /**
     * 保存ボタンでの検証(ds 9.3)。表示名は trim 後に空でなく10文字以内、アドレスは trim 後の形式。重複は見ない。
     * 文字数は UTF-16 の単位で数える(絵文字は2文字分、HANDOFF の既知事項)。
     */
    fun validate(d: RecipientDialogState, emailPattern: Pattern): Map<RecipientField, ValidationError> {
        val result = linkedMapOf<RecipientField, ValidationError>()
        Validators.validateRecipientName(d.name)?.let { result[RecipientField.NAME] = it }
        Validators.validateEmail(d.email, emailPattern)?.let { result[RecipientField.EMAIL] = it }
        return result
    }

    fun firstError(errors: Map<RecipientField, ValidationError>): RecipientField? =
        RecipientField.entries.firstOrNull { it in errors }

    /** 表示名は通常のキーボードで「次へ」、アドレスはメールのキーボードで「完了」。 */
    fun keyboardOptions(field: RecipientField): KeyboardOptions = when (field) {
        RecipientField.NAME -> KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next)
        RecipientField.EMAIL -> KeyboardOptions(
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Done,
            autoCorrectEnabled = false,
        )
    }
}
