package com.example.parentphoto.outbox

import android.util.Log

/** 未送信キューの状態遷移のLogcat出力。項目idなどのidのみを出し、アドレス・パスワードは出さない。 */
object OutboxLog {
    const val TAG = "ParentPhoto/Outbox"

    fun d(message: String) {
        safe { Log.d(TAG, message) }
    }

    fun w(message: String) {
        safe { Log.w(TAG, message) }
    }

    // JVMテストでは android.util.Log が使えないため握りつぶす
    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
        }
    }
}
