package com.example.parentphoto.ui.settings

import android.util.Patterns
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.example.parentphoto.settings.Settings
import com.example.parentphoto.settings.ValidationError
import com.example.parentphoto.settings.Validators
import com.example.parentphoto.util.Constants
import java.util.regex.Pattern

/** 送信者名・メール設定の入力欄。並びは画面の上から順(最初のエラー欄の判定に使う)。 */
enum class FormField { SENDER_NAME, SMTP_USER, PASSWORD, HOST, PORT }

/**
 * アプリパスワード欄の状態(ds 9.2)。
 * NEW: 未保存(空の伏せ字欄)。SAVED: 保存済み(●表示で編集不可、変更ボタン)。CHANGING: 変更を押した後(空の伏せ字欄とキャンセル)。
 */
enum class PasswordMode { NEW, SAVED, CHANGING }

/** パスワード以外の入力値。ポートは入力どおりの文字列で持つ。 */
data class SmtpFormValues(
    val senderName: String = "",
    val smtpUser: String = "",
    val host: String = Settings.DEFAULT_HOST,
    val port: String = Settings.DEFAULT_PORT.toString(),
)

/**
 * 送信者名とメール設定のフォーム状態(settings-screen PLAN-1 2)。
 * baseline は保存済みの値。values と比べて未保存の変更(dirty)を判定する。
 * password は入力中の平文で、保存済みの値は持たない(画面にも出さない)。
 */
data class SmtpFormState(
    val loaded: Boolean = false,
    val baseline: SmtpFormValues = SmtpFormValues(),
    val values: SmtpFormValues = SmtpFormValues(),
    val password: String = "",
    val passwordMode: PasswordMode = PasswordMode.NEW,
    val errors: Map<FormField, ValidationError> = emptyMap(),
) {
    /** 未保存の変更があるか。変更を押した後は空でも dirty、未保存で何か入力していれば dirty。 */
    val dirty: Boolean
        get() = loaded && (
            values != baseline ||
                passwordMode == PasswordMode.CHANGING ||
                (passwordMode == PasswordMode.NEW && password.isNotEmpty())
            )
}

/** フォームの純粋な処理(初期化・文字数制限・検証)。JVMテストのため Android の画面部品に依存しない。 */
object SmtpFormLogic {

    /** 保存済み設定からフォームを作る。passwordSaved は復号できるパスワードがあるか。 */
    fun fromSettings(settings: Settings, passwordSaved: Boolean): SmtpFormState {
        val v = SmtpFormValues(
            senderName = settings.senderName,
            smtpUser = settings.smtpUser,
            host = settings.smtpHost,
            port = settings.smtpPort.toString(),
        )
        return SmtpFormState(
            loaded = true,
            baseline = v,
            values = v,
            passwordMode = if (passwordSaved) PasswordMode.SAVED else PasswordMode.NEW,
        )
    }

    // 上限を超える入力は止める(超えた分は入らない、ds 9.3 / Q59)
    fun limitSenderName(input: String): String = input.take(Constants.SENDER_NAME_MAX)
    fun limitEmail(input: String): String = input.take(Constants.EMAIL_MAX)
    fun limitHost(input: String): String = input.take(Constants.HOST_MAX)

    /** 新しいパスワードを保存するか。保存済みのままなら null(既存の暗号文を保持)。 */
    fun newPasswordOrNull(state: SmtpFormState): String? =
        if (state.passwordMode == PasswordMode.SAVED) null else Validators.normalizePassword(state.password)

    /**
     * 保存ボタンでの検証(ds 9.3)。エラーのある欄だけを返す(画面順に並ぶ)。
     * 送信者名は検証しない。パスワードは保存済みのままなら検証しない。
     */
    fun validate(state: SmtpFormState, emailPattern: Pattern = Patterns.EMAIL_ADDRESS): Map<FormField, ValidationError> {
        val v = state.values
        val result = linkedMapOf<FormField, ValidationError>()
        Validators.validateEmail(v.smtpUser, emailPattern)?.let { result[FormField.SMTP_USER] = it }
        if (state.passwordMode != PasswordMode.SAVED) {
            Validators.validatePassword(state.password)?.let { result[FormField.PASSWORD] = it }
        }
        Validators.validateHost(v.host)?.let { result[FormField.HOST] = it }
        Validators.validatePort(v.port)?.let { result[FormField.PORT] = it }
        return result
    }

    /** 画面順で最初にエラーのある欄。 */
    fun firstError(errors: Map<FormField, ValidationError>): FormField? =
        FormField.entries.firstOrNull { it in errors }

    /** 保存後の値。保存時と同じ正規化(送信者名の改行除去とtrim、アドレス・ホストのtrim、ポートの数値化)をする。 */
    fun normalized(v: SmtpFormValues): SmtpFormValues = SmtpFormValues(
        senderName = Validators.sanitizeSenderName(v.senderName),
        smtpUser = v.smtpUser.trim(),
        host = v.host.trim(),
        port = v.port.trim().toInt().toString(),
    )

    /** IMEの「次へ」で移る欄。保存済みパスワード欄は編集できないので飛ばす。最後の欄は null。 */
    fun nextField(field: FormField, passwordMode: PasswordMode): FormField? {
        val order = FormField.entries.filter { it != FormField.PASSWORD || passwordMode != PasswordMode.SAVED }
        val i = order.indexOf(field)
        return if (i < 0) null else order.getOrNull(i + 1)
    }

    /**
     * 欄ごとのキーボード(ds 9.8)。送信者名・ホストは通常、アドレスはメール、パスワードはパスワード、ポートは数字。
     * 最後のポートだけ「完了」、それ以外は「次へ」。
     */
    fun keyboardOptions(field: FormField): KeyboardOptions = when (field) {
        FormField.SENDER_NAME -> KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next)
        FormField.SMTP_USER -> KeyboardOptions(
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next,
            autoCorrectEnabled = false,
        )
        FormField.PASSWORD -> KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Next,
            autoCorrectEnabled = false,
        )
        FormField.HOST -> KeyboardOptions(
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Next,
            autoCorrectEnabled = false,
        )
        FormField.PORT -> KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)
    }
}
