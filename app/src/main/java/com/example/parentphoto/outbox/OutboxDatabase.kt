package com.example.parentphoto.outbox

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * 未送信キューのデータベース outbox.db(ds 7.1)。
 * 破壊的マイグレーションは使わない(未送信写真の情報が消えるため)。版を上げるときは Migration を書く。
 */
@Database(
    entities = [OutboxItemEntity::class, SendLogEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(OutboxConverters::class)
abstract class OutboxDatabase : RoomDatabase() {
    abstract fun outboxDao(): OutboxDao
    abstract fun sendLogDao(): SendLogDao

    companion object {
        const val NAME = "outbox.db"

        fun create(context: Context): OutboxDatabase =
            Room.databaseBuilder(context.applicationContext, OutboxDatabase::class.java, NAME).build()
    }
}
