package com.example.parentphoto.mail

/** 失敗コード(ds 6.3 / 6.4)。Logcatと送信ログに残す。 */
enum class MailCode { NETWORK, TIMEOUT, AUTH, REJECTED, OTHER }

/** 失敗の分類(ds 6.3)。再送の扱いが変わる。 */
enum class FailureKind { TRANSIENT, AUTH, PERMANENT }

/** 1回の送信試行の結果。 */
sealed interface MailResult {
    data object Success : MailResult

    /** 前面送信のみ。回線が明らかに無く、試行していない(ds 6.2)。 */
    data object Offline : MailResult

    /** 試行開始時に送り先idが見つからなかった。メール失敗ではない。 */
    data object RecipientMissing : MailResult

    data class Failure(
        val kind: FailureKind,
        val code: MailCode,
        val exceptionClass: String?,
        val message: String?,
        val smtpReply: Int?,
    ) : MailResult
}
