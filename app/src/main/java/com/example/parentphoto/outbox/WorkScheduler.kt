package com.example.parentphoto.outbox

/**
 * 再送ワーカーの起動口(ds 7.4)。実装は OutboxScheduler(テストでは偽物)。
 * enqueueDefault: 一意ワーク outbox-send を KEEP で登録する。
 * triggerNow: 実行中でなければ REPLACE、実行中なら後続ワークを登録する。
 */
interface WorkScheduler {
    fun enqueueDefault()
    fun triggerNow()
}
