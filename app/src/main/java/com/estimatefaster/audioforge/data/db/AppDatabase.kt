package com.estimatefaster.audioforge.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 正式数据库：P2-01 的 7 张业务表 + P0 的 probe 表（P2-02 再移除 probe）。
 *
 * 约定（执行计划 §7.3）：
 * - WAL 日志模式；
 * - 打开连接后显式开 `foreign_keys` 与 `busy_timeout`；
 * - `exportSchema = true`，schema 输出到 `app/schemas`（提交入库）；
 * - version 仍为 1：v1 不做迁移。旧 probe-only 库与新表结构同 version 不兼容，
 *   `fallbackToDestructiveMigration()` 只覆盖版本变化，开发期仍需清数据/重装。
 */
@Database(
    entities = [
        NovelEntity::class,
        ChapterEntity::class,
        CharacterEntity::class,
        SegmentEntity::class,
        CharacterTuningEntity::class,
        NovelMetaEntity::class,
        JobEntity::class,
        ProbeEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun novelDao(): NovelDao

    abstract fun chapterDao(): ChapterDao

    abstract fun characterDao(): CharacterDao

    /** P5-06 角色微调；复用 P2-01 的 `character_tuning` 表，version 不变。 */
    abstract fun tuningDao(): TuningDao

    abstract fun segmentDao(): SegmentDao

    abstract fun jobDao(): JobDao

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
                .fallbackToDestructiveMigration()
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
