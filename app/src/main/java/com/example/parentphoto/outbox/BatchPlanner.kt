package com.example.parentphoto.outbox

import com.example.parentphoto.util.Constants

/** 1通にまとめて送る項目(同じ送り先、撮影日時の昇順)。 */
data class Batch(val recipientId: String, val items: List<OutboxItemEntity>)

/**
 * 再送のまとめ方(ds 5 Batching)。純粋関数。
 * 送り先ごとに撮影順に並べ、ファイルサイズの合計が maxBytes を超える手前で次の1通に分ける。
 * 1枚で maxBytes を超える写真も単独で1通にする。
 */
object BatchPlanner {
    fun plan(items: List<OutboxItemEntity>, maxBytes: Long = Constants.BATCH_MAX_BYTES): List<Batch> {
        val byCapture = compareBy<OutboxItemEntity>({ it.capturedAtMillis }, { it.id })
        val batches = mutableListOf<Batch>()
        for ((recipientId, group) in items.groupBy { it.recipientId }) {
            var current = mutableListOf<OutboxItemEntity>()
            var sum = 0L
            for (item in group.sortedWith(byCapture)) {
                // 足すと上限を超えるなら、ここまでで1通にする(ちょうど上限は同じ1通に入れる)
                if (current.isNotEmpty() && sum + item.fileSize > maxBytes) {
                    batches.add(Batch(recipientId, current))
                    current = mutableListOf()
                    sum = 0L
                }
                current.add(item)
                sum += item.fileSize
            }
            if (current.isNotEmpty()) batches.add(Batch(recipientId, current))
        }
        // 各通の先頭(最も古い写真)の撮影順で並べる
        return batches.sortedWith(compareBy({ it.items.first().capturedAtMillis }, { it.items.first().id }))
    }
}
