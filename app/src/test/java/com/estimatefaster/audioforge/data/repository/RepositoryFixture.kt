package com.estimatefaster.audioforge.data.repository

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.estimatefaster.audioforge.core.storage.AudioStorage
import com.estimatefaster.audioforge.data.db.AppDatabase
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first

/**
 * 仓库测试脚手架：Robolectric 下的 in-memory Room + 临时目录 AudioStorage。
 *
 * 与 `RoomDaoTest` 一样，`inMemoryDatabaseBuilder` 不会走 `AppDatabase.create` 的
 * `onOpen`，所以这里同时注册回调并对写连接手动开一次 `PRAGMA foreign_keys=ON`。
 */
internal class RepositoryFixture {

    val db: AppDatabase
    val filesRoot: File = Files.createTempDirectory("audioforge-repo-").toFile()
    val audioStorage: AudioStorage
    val projects: ProjectRepository
    val chapters: ChapterRepository
    val characters: CharacterRepository
    val segments: SegmentRepository
    val jobs: JobRepository

    private var clock: Long = 1_000L

    init {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(database: SupportSQLiteDatabase) {
                    database.execSQL("PRAGMA foreign_keys=ON")
                }
            })
            .build()

        val writable = db.openHelper.writableDatabase
        writable.execSQL("PRAGMA foreign_keys=ON")
        writable.query("PRAGMA foreign_keys").use { cursor ->
            check(cursor.moveToFirst() && cursor.getInt(0) == 1) { "测试库必须启用外键" }
        }

        audioStorage = AudioStorage(filesRoot)
        projects = ProjectRepository(db, audioStorage)
        chapters = ChapterRepository(db)
        characters = CharacterRepository(db)
        segments = SegmentRepository(db)
        jobs = JobRepository(db)
    }

    /** 建一本空项目（无章节），用于测试只依赖 novels 的仓库。 */
    suspend fun createEmptyNovel(title: String = "测试项目"): Long =
        projects.createProject(
            title = title,
            sourceName = "$title.txt",
            sourceRelPath = "imports/$title.txt",
            chapters = emptyList(),
            createdAt = nextTimestamp(),
        )

    /** 建一本两章的项目，返回 (novelId, chapterIds[0], chapterIds[1])。 */
    suspend fun createTwoChapterNovel(title: String = "两章项目"): Triple<Long, Long, Long> {
        val novelId = projects.createProject(
            title = title,
            sourceName = "$title.txt",
            sourceRelPath = "imports/two-$title.txt",
            chapters = listOf(
                ChapterInput(title = "第一章", rawText = "第一章正文"),
                ChapterInput(title = "第二章", rawText = "第二章正文"),
            ),
            createdAt = nextTimestamp(),
        )
        val ids = chapters.observeBriefs(novelId).first().map { it.id }
        return Triple(novelId, ids[0], ids[1])
    }

    fun nextTimestamp(): Long = clock++

    fun countRows(table: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    fun close() {
        db.close()
        filesRoot.deleteRecursively()
    }
}
