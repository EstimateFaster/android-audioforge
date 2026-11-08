package com.estimatefaster.audioforge.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P5-06：`character_tuning` 的 DAO 级测试（Robolectric + in-memory Room）。
 *
 * 只验证 DAO/SQL 本身：插入、REPLACE 覆盖、按角色/项目过滤、删除、Flow 触发。
 * key 的合法值/范围校验与 `CharacterTuning` 映射在 `TuningRepositoryTest` 里验证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TuningDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: TuningDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(database: SupportSQLiteDatabase) {
                    database.execSQL("PRAGMA foreign_keys=ON")
                }
            })
            .build()
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys=ON")
        dao = db.tuningDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------------------------------------------------------------- 写入/读取

    @Test
    fun upsert_inserts_all_keys_and_lists_them_sorted_by_key() = runTest {
        val novelId = novel("微调书")

        dao.upsertAll(
            listOf(
                row(novelId, "role_0001", "speed", "1.1"),
                row(novelId, "role_0001", "volume", "0.8"),
                row(novelId, "role_0001", "pitch", "-2.5"),
            ),
        )

        val rows = dao.listByRole(novelId, "role_0001")
        assertEquals(3, rows.size)
        assertEquals(listOf("pitch", "speed", "volume"), rows.map { it.key })
        assertEquals(listOf("-2.5", "1.1", "0.8"), rows.map { it.value })
        assertEquals(3, dao.observeByNovel(novelId).first().size)
    }

    @Test
    fun upsert_replaces_value_for_same_primary_key() = runTest {
        val novelId = novel("覆盖书")
        dao.upsertAll(listOf(row(novelId, "role_0001", "speed", "1.0")))

        dao.upsertAll(listOf(row(novelId, "role_0001", "speed", "1.5")))

        val rows = dao.listByRole(novelId, "role_0001")
        assertEquals(1, rows.size)
        assertEquals("1.5", rows.single().value)
        assertEquals(1, dao.observeByNovel(novelId).first().size)
    }

    @Test
    fun upsert_empty_list_is_noop() = runTest {
        val novelId = novel("空写书")
        dao.upsertAll(emptyList())
        assertTrue(dao.listByRole(novelId, "role_0001").isEmpty())
        assertTrue(dao.observeByNovel(novelId).first().isEmpty())
    }

    @Test
    fun observe_emits_rows_after_write() = runTest {
        val novelId = novel("订阅书")
        assertTrue(dao.observeByNovel(novelId).first().isEmpty())

        dao.upsertAll(listOf(row(novelId, "role_0001", "volume", "1.2")))

        val rows = dao.observeByNovel(novelId).first()
        assertEquals(1, rows.size)
        assertEquals("1.2", rows.single().value)
    }

    // ---------------------------------------------------------------- 过滤

    @Test
    fun list_by_role_is_scoped_to_novel_and_role() = runTest {
        val novelA = novel("A 书")
        val novelB = novel("B 书")
        dao.upsertAll(
            listOf(
                row(novelA, "role_0001", "speed", "1.1"),
                row(novelA, "role_0002", "speed", "0.9"),
                row(novelB, "role_0001", "speed", "1.3"),
            ),
        )

        val rows = dao.listByRole(novelA, "role_0001")
        assertEquals(1, rows.size)
        assertEquals("1.1", rows.single().value)
        assertEquals(2, dao.observeByNovel(novelA).first().size)
        assertEquals(1, dao.observeByNovel(novelB).first().size)
    }

    // ---------------------------------------------------------------- 删除

    @Test
    fun delete_role_removes_only_target_role_and_returns_row_count() = runTest {
        val novelId = novel("删除角色书")
        dao.upsertAll(
            listOf(
                row(novelId, "role_0001", "speed", "1.1"),
                row(novelId, "role_0001", "volume", "0.9"),
                row(novelId, "role_0002", "pitch", "1.0"),
            ),
        )

        assertEquals(2, dao.deleteRole(novelId, "role_0001"))

        assertTrue(dao.listByRole(novelId, "role_0001").isEmpty())
        assertEquals(1, dao.listByRole(novelId, "role_0002").size)
        assertEquals(0, dao.deleteRole(novelId, "role_0001"))
    }

    @Test
    fun delete_novel_removes_all_rows_of_that_novel() = runTest {
        val novelA = novel("删除 A")
        val novelB = novel("删除 B")
        dao.upsertAll(
            listOf(
                row(novelA, "role_0001", "speed", "1.1"),
                row(novelA, "role_0002", "pitch", "1.0"),
                row(novelB, "role_0001", "speed", "1.2"),
            ),
        )

        assertEquals(2, dao.deleteNovel(novelA))

        assertTrue(dao.observeByNovel(novelA).first().isEmpty())
        assertEquals(1, dao.observeByNovel(novelB).first().size)
    }

    // ---------------------------------------------------------------- 辅助

    private suspend fun novel(title: String): Long = db.novelDao().insert(
        NovelEntity(
            title = title,
            sourceName = "$title.txt",
            sourceRelPath = "imports/$title.txt",
            charCount = 0,
            createdAt = 1_000L,
        ),
    )

    private fun row(
        novelId: Long,
        roleId: String,
        key: String,
        value: String,
    ): CharacterTuningEntity = CharacterTuningEntity(
        novelId = novelId,
        roleId = roleId,
        key = key,
        value = value,
    )
}
