package com.example.parentphoto.mail

import java.io.File

/** 送信キュー項目のid。outbox_item.id はUUID文字列(outbox-queue PLAN-1)。 */
typealias ItemId = String

/** 添付1枚分。 */
data class MailPhoto(val file: File, val capturedAtMillis: Long)

/** 1通分の送信依頼。photos は撮影日時の昇順で1枚以上。 */
data class MailRequest(val toAddress: String, val photos: List<MailPhoto>)

/** 前面送信の対象(Roomに依存しないための橋渡し)。 */
data class SendableItem(val recipientId: String, val file: File, val capturedAtMillis: Long)

/** outbox-queue が実装する。行が無ければ null。 */
interface SendSource {
    suspend fun loadForeground(itemId: ItemId): SendableItem?
}

enum class SendLogResult {
    SENT, FAILED, QUEUED_OFFLINE, DELETED_MANUAL, DELETED_OVERFLOW, RESEND_REQUESTED, TEST_OK, TEST_FAILED
}

/** outbox-queue が実装する送信ログ書き込み。recipientName が null なら削除済み宛先。 */
interface SendLogWriter {
    suspend fun record(result: SendLogResult, recipientName: String?, code: String?)
}

/** 回線の有無の確認(テストで差し替える)。 */
fun interface ConnectivityChecker {
    fun isClearlyOffline(): Boolean
}
