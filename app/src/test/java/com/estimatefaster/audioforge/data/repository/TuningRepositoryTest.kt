package com.estimatefaster.audioforge.data.repository

import com.estimatefaster.audioforge.core.settings.AppSettings
import com.estimatefaster.audioforge.data.db.CharacterTuningEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P5-06：角色微调仓库测试（Robolectric + in-memory Room）。
 *
 * 覆盖：
 * - 行 → [CharacterTuning] 的映射（按角色聚合、坏值/越界/未知 key 容错）；
 * - `set` 的范围校验、整体替换、null 清除、未知角色；
 * - `resetRole` 的返回值与作用域；
 * - `defaults` 从注入的 [AppSettings] 读取全局默认。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TuningRepositoryTest {

    private lateinit var fixture: RepositoryFixture
    private lateinit var repository: TuningRepository

    @Before
    fun setUp() {
        fixture = RepositoryFixture()
        repository = TuningRepository(fixture.db)
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    // ---------------------------------------------------------------- 映射

    @Test
    fun set_and_get_round_trip_all_keys() = runTest {
        val novelId = prepareNovel()

        repository.set(novelId, "role_0001", speed = 1.25, volume = 0.75, pitch = -3.5)

        val tuning = repository.get(novelId, "role_0001")!!
        assertEquals("role_0001", tuning.roleId)
        assertEquals(1.25, tuning.speed!!, 0.0)
        assertEquals(0.75, tuning.volume!!, 0.0)
        assertEquals(-3.5, tuning.pitch!!, 0.0)
        assertFalse(tuning.isFollowingGlobal)

        val rows = fixture.db.tuningDao().listByRole(novelId, "role_0001")
        assertEquals(listOf("pitch", "speed", "volume"), rows.map { it.key })
        assertEquals(listOf("-3.5", "1.25", "0.75"), rows.map { it.value })

        val observed = repository.observe(novelId).first()
        assertEquals(1, observed.size)
        assertEquals(tuning, observed.single())
    }

    @Test
    fun observe_groups_rows_by_role_and_ignores_invalid_values() = runTest {
        val novelId = prepareNovel()
        raw(novelId, "role_0001", "speed", "1.1")
        raw(novelId, "role_0001", "volume", "不是数字")
        raw(novelId, "role_0001", "pitch", "99")
        raw(novelId, "role_0001", "emotion", "happy")
        raw(novelId, "role_0002", "speed", "0.8")
        raw(novelId, "role_0002", "volume", "0.7")
        raw(novelId, "role_0002", "pitch", "-1.5")

        val observed = repository.observe(novelId).first()

        assertEquals(listOf("role_0001", "role_0002"), observed.map { it.roleId })
        val first = observed[0]
        assertEquals(1.1, first.speed!!, 0.0)
        assertNull("非数字应视为未设置", first.volume)
        assertNull("越界应视为未设置", first.pitch)
        val second = observed[1]
        assertEquals(0.8, second.speed!!, 0.0)
        assertEquals(0.7, second.volume!!, 0.0)
        assertEquals(-1.5, second.pitch!!, 0.0)
    }

    @Test
    fun get_returns_null_for_unknown_role_and_empty_novel() = runTest {
        val novelId = prepareNovel()

        assertNull(repository.get(novelId, "role_9999"))
        assertTrue(repository.observe(novelId).first().isEmpty())
    }

    // ---------------------------------------------------------------- 写入/清除

    @Test
    fun set_replaces_all_keys_and_null_clears_them() = runTest {
        val novelId = prepareNovel()
        repository.set(novelId, "role_0001", speed = 1.2, volume = 1.1, pitch = 2.0)

        repository.set(novelId, "role_0001", speed = null, volume = 1.1, pitch = null)

        val partial = repository.get(novelId, "role_0001")!!
        assertNull(partial.speed)
        assertEquals(1.1, partial.volume!!, 0.0)
        assertNull(partial.pitch)
        assertEquals(
            listOf("volume"),
            fixture.db.tuningDao().listByRole(novelId, "role_0001").map { it.key },
        )

        repository.set(novelId, "role_0001", speed = null, volume = null, pitch = null)

        assertNull("三个 null 应清除整行", repository.get(novelId, "role_0001"))
        assertTrue(fixture.db.tuningDao().listByRole(novelId, "role_0001").isEmpty())
    }

    @Test
    fun set_accepts_range_boundaries() = runTest {
        val novelId = prepareNovel()

        repository.set(novelId, "role_0001", speed = 0.5, volume = 0.0, pitch = -12.0)
        val low = repository.get(novelId, "role_0001")!!
        assertEquals(0.5, low.speed!!, 0.0)
        assertEquals(0.0, low.volume!!, 0.0)
        assertEquals(-12.0, low.pitch!!, 0.0)

        repository.set(novelId, "role_0001", speed = 2.0, volume = 2.0, pitch = 12.0)
        val high = repository.get(novelId, "role_0001")!!
        assertEquals(2.0, high.speed!!, 0.0)
        assertEquals(2.0, high.volume!!, 0.0)
        assertEquals(12.0, high.pitch!!, 0.0)
    }

    @Test
    fun set_rejects_out_of_range_values_without_touching_existing_rows() = runTest {
        val novelId = prepareNovel()
        repository.set(novelId, "role_0001", speed = 1.0)

        assertRejects { repository.set(novelId, "role_0001", speed = 0.49) }
        assertRejects { repository.set(novelId, "role_0001", speed = 2.01) }
        assertRejects { repository.set(novelId, "role_0001", speed = Double.NaN) }
        assertRejects { repository.set(novelId, "role_0001", speed = Double.POSITIVE_INFINITY) }
        assertRejects { repository.set(novelId, "role_0001", volume = -0.01) }
        assertRejects { repository.set(novelId, "role_0001", volume = 2.01) }
        assertRejects { repository.set(novelId, "role_0001", volume = Double.NaN) }
        assertRejects { repository.set(novelId, "role_0001", pitch = -12.1) }
        assertRejects { repository.set(novelId, "role_0001", pitch = 12.1) }
        assertRejects { repository.set(novelId, "role_0001", pitch = Double.NEGATIVE_INFINITY) }

        // 非法写入不能先删除旧行：基线必须原样保留。
        val tuning = repository.get(novelId, "role_0001")!!
        assertEquals(1.0, tuning.speed!!, 0.0)
        assertNull(tuning.volume)
        assertNull(tuning.pitch)
    }

    @Test
    fun set_rejects_blank_role_id() = runTest {
        val novelId = prepareNovel()
        assertRejects { repository.set(novelId, "   ", speed = 1.0) }
    }

    @Test
    fun set_allows_unknown_role_id() = runTest {
        val novelId = prepareNovel()

        repository.set(novelId, "role_unknown", speed = 1.3)

        val tuning = repository.get(novelId, "role_unknown")!!
        assertEquals(1.3, tuning.speed!!, 0.0)
    }

    @Test
    fun reset_role_removes_only_that_role() = runTest {
        val novelId = prepareNovel()
        repository.set(novelId, "role_0001", speed = 1.2, volume = 1.1, pitch = 2.0)
        repository.set(novelId, "role_0002", speed = 1.3)

        assertTrue(repository.resetRole(novelId, "role_0001"))

        assertNull(repository.get(novelId, "role_0001"))
        assertEquals(1.3, repository.get(novelId, "role_0002")!!.speed!!, 0.0)
        assertFalse("没有行时返回 false", repository.resetRole(novelId, "role_0001"))
    }

    // ---------------------------------------------------------------- 默认值

    @Test
    fun defaults_come_from_injected_settings() = runTest {
        val custom = TuningRepository(fixture.db) {
            AppSettings(
                defaultSpeed = 1.2,
                defaultVolume = 0.6,
                defaultPitch = 3.0,
            )
        }

        val defaults = custom.defaults()

        assertEquals(1.2, defaults.speed, 0.0)
        assertEquals(0.6, defaults.volume, 0.0)
        assertEquals(3.0, defaults.pitch, 0.0)
    }

    @Test
    fun defaults_fall_back_to_app_settings_default() = runTest {
        val defaults = repository.defaults()
        assertEquals(AppSettings.DEFAULT.defaultSpeed, defaults.speed, 0.0)
        assertEquals(AppSettings.DEFAULT.defaultVolume, defaults.volume, 0.0)
        assertEquals(AppSettings.DEFAULT.defaultPitch, defaults.pitch, 0.0)
    }

    // ---------------------------------------------------------------- 辅助

    private suspend fun prepareNovel(): Long = fixture.createEmptyNovel("微调书")

    private suspend fun raw(novelId: Long, roleId: String, key: String, value: String) {
        fixture.db.tuningDao().upsertAll(
            listOf(CharacterTuningEntity(novelId = novelId, roleId = roleId, key = key, value = value)),
        )
    }

    private suspend fun assertRejects(block: suspend () -> Unit) {
        try {
            block()
            fail("期望抛出 IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // 预期路径。
        }
    }
}
