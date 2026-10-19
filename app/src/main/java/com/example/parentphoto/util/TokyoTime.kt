package com.example.parentphoto.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 時刻の整形。端末のタイムゾーンに関係なく常に Asia/Tokyo で表示する。入力はエポックミリ秒。 */
object TokyoTime {
    private val zone: ZoneId = ZoneId.of("Asia/Tokyo")

    private val minuteFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(zone)
    private val secondFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss").withZone(zone)
    private val fileNameFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(zone)

    /** 例: 2026/10/19 19:36 */
    fun formatMinute(epochMillis: Long): String = minuteFormatter.format(Instant.ofEpochMilli(epochMillis))

    /** 例: 2026/10/19 19:36:05 */
    fun formatSecond(epochMillis: Long): String = secondFormatter.format(Instant.ofEpochMilli(epochMillis))

    /** ファイル名用。例: 20261019_193605 */
    fun formatFileName(epochMillis: Long): String = fileNameFormatter.format(Instant.ofEpochMilli(epochMillis))
}
