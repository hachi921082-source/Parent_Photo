package com.example.parentphoto.mail

import android.util.Log

/** 送信結果のLogcat出力(ds 6.4)。パスワード・認証情報・本文は出さない。 */
object MailLog {
    private const val TAG = "ParentPhoto/Mail"

    fun success(photoCount: Int) {
        safe { Log.i(TAG, "sent $photoCount photos") }
    }

    fun failure(f: MailResult.Failure) {
        safe {
            Log.w(
                TAG,
                "failed kind=${f.kind} code=${f.code} exception=${f.exceptionClass} " +
                    "message=${f.message} smtpReply=${f.smtpReply}",
            )
        }
    }

    // JVMテストでは android.util.Log が使えないため握りつぶす
    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
        }
    }
}
