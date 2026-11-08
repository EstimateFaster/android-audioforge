package com.estimatefaster.audioforge.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Phase 0 只包含探针表；P2-01 会把正式实体/DAO 加进来并升 version。
 *
 * 约定（执行计划 §7.3）：
 * - WAL 日志模式；
 * - 打开连接后显式开 `foreign_keys` 与 `busy_timeout`；
 * - `exportSchema = true`，schema 输出到 `app/schemas`（提交入库）。
 */
@Database(
    entities = [ProbeEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun probeDao(): ProbeDao

    companion object {
        const val DB_NAME = "audioforge.db"

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DB_NAME,
            )
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.execSQL("PRAGMA foreign_keys=ON")
                        // busy_timeout 会返回一行结果，Android 的 execSQL 不接受，必须用 query。
                        db.query("PRAGMA busy_timeout=5000").close()
                    }
                })
                .build()
    }
}
