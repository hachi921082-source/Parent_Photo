package com.example.parentphoto.settings

import android.util.Patterns
import androidx.annotation.StringRes
import com.example.parentphoto.R
import com.example.parentphoto.util.Constants
import java.util.regex.Pattern

/** 検証エラー(ds 9.3)。文言は strings.xml にあり、画面側は messageRes で表示する。 */
enum class ValidationError(@StringRes val messageRes: Int) {
    NAME_EMPTY(R.string.err_name_empty),
    NAME_TOO_LONG(R.string.err_name_too_long),
    EMAIL_FORMAT(R.string.err_email_format),
    PASSWORD_LENGTH(R.string.err_password_length),
    HOST_EMPTY(R.string.err_host_empty),
    HOST_FORMAT(R.string.err_host_format),
    PORT_RANGE(R.string.err_port_range),
}

/** 設定値の検証と正規化(ds 9.3、ds 5)。エラーなしは null を返す。 */
object Validators {
    const val PASSWORD_LENGTH = 16

    /** メール形式の判定(trim後)。テストではJVM上でPatternsが使えないため差し替え可能にする。 */
    fun validateEmail(
        email: String,
        pattern: Pattern = Patterns.EMAIL_ADDRESS,
    ): ValidationError? =
        if (pattern.matcher(email.trim()).matches()) null else ValidationError.EMAIL_FORMAT

    fun validateRecipientName(name: String): ValidationError? {
        val t = name.trim()
        return when {
            t.isEmpty() -> ValidationError.NAME_EMPTY
            t.length > Constants.RECIPIENT_NAME_MAX -> ValidationError.NAME_TOO_LONG
            else -> null
        }
    }

    /** パスワード入力から空白(スペース・改行など)をすべて除く。検証・暗号化の前に必ず通す。 */
    fun normalizePassword(input: String): String = input.filterNot { it.isWhitespace() }

    fun validatePassword(input: String): ValidationError? =
        if (normalizePassword(input).length == PASSWORD_LENGTH) null else ValidationError.PASSWORD_LENGTH

    fun validateHost(host: String): ValidationError? {
        val t = host.trim()
        return when {
            t.isEmpty() -> ValidationError.HOST_EMPTY
            t.any { it.isWhitespace() } -> ValidationError.HOST_FORMAT
            else -> null
        }
    }

    fun isValidPort(port: Int): Boolean = port in 1..65535

    /** 文字列のポートを検証する。整数でない場合も範囲外と同じエラー。 */
    fun validatePort(input: String): ValidationError? {
        val p = input.trim().toIntOrNull()
        return if (p != null && isValidPort(p)) null else ValidationError.PORT_RANGE
    }

    /** 送信者名: 改行(CR/LF)を除いてからtrimする。空でもよい。 */
    fun sanitizeSenderName(name: String): String =
        name.filterNot { it == '\r' || it == '\n' }.trim()
}
