package com.example.parentphoto.outbox

import kotlinx.coroutines.sync.Mutex

/**
 * 再送ワーカーの実行を1プロセスで1つずつにする鍵(outbox-worker PLAN-1 4)。
 * outbox-send と後続の outbox-send-next が同時に起きても、送信処理の本体は順番に走る。
 * 後から入った方は前の実行が終わってから一覧を取り直すので、同じ写真を2回送らない。
 */
object OutboxWorkerGate {
    val mutex = Mutex()
}
