package com.example.parentphoto.outbox

/**
 * 再送ワーカーの起動口(ds 7.4)。実装は outbox-worker の OutboxScheduler。
 * enqueueDefault: 一意ワーク outbox-send を KEEP で登録する。
 * triggerNow: 実行中でなければ REPLACE、実行中なら後続ワークを登録する。
 */
interface WorkScheduler {
    fun enqueueDefault()
    fun triggerNow()
}

/**
 * 仮実装。outbox-worker が OutboxScheduler に置き換えるまでの間、呼ばれたことを Logcat に残すだけ。
 * TODO(outbox-worker): ParentPhotoApp で OutboxScheduler に差し替える。
 */
class NoOpWorkScheduler : WorkScheduler {
    override fun enqueueDefault() {
        OutboxLog.d("enqueueDefault (worker not installed)")
    }

    override fun triggerNow() {
        OutboxLog.d("triggerNow (worker not installed)")
    }
}
