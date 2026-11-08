package com.estimatefaster.audioforge.data.repository

import com.estimatefaster.audioforge.data.db.CharacterEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** P2-02：角色 upsert 的音色锁定、别名合并、name/alias 精确查找与删除。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CharacterRepositoryTest {

    private lateinit var fixture: RepositoryFixture
    private var novelId: Long = 0

    @Before
    fun setUp() {
        fixture = RepositoryFixture()
    }

    private suspend fun createNovel() {
        novelId = fixture.createEmptyNovel("角色书")
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun upsert_inserts_new_character_and_normalizes_aliases() = runTest {
        createNovel()
        val saved = fixture.characters.upsert(
            character(
                roleId = "role_0001",
                name = "张三",
                aliasesJson = """[" 小三 ","小三",""]""",
                category = "male_lead_steady",
                voiceId = "voice-1",
                speed = 1.1,
            ),
        )

        assertTrue(saved.id > 0)
        val loaded = fixture.characters.getByRoleId(novelId, "role_0001")!!
        assertEquals("张三", loaded.name)
        assertEquals("male_lead_steady", loaded.category)
        assertEquals("voice-1", loaded.voiceId)
        assertEquals(1.1, loaded.speed!!, 0.0)
        assertEquals("""["小三"]""", loaded.aliasesJson)
        assertEquals(1, fixture.characters.list(novelId).size)
        assertEquals(1, fixture.characters.observeByNovel(novelId).first().size)
    }

    @Test
    fun upsert_keeps_locked_voice_fields_and_merges_aliases() = runTest {
        createNovel()
        val first = fixture.characters.upsert(
            character(
                roleId = "role_0001",
                name = "张三",
                aliasesJson = """["小三"]""",
                category = "male_lead_steady",
                voiceId = "voice-1",
                speed = 1.1,
            ),
        )

        val second = fixture.characters.upsert(
            character(
                roleId = "role_0001",
                name = "张三",
                aliasesJson = """["阿三"]""",
                category = "female_young",
                voiceId = "voice-2",
                speed = 2.0,
            ),
        )

        assertEquals(first.id, second.id)
        val loaded = fixture.characters.getByRoleId(novelId, "role_0001")!!
        assertEquals("音色类别被锁定", "male_lead_steady", loaded.category)
        assertEquals("voice_id 被锁定", "voice-1", loaded.voiceId)
        assertEquals("语速被锁定", 1.1, loaded.speed!!, 0.0)
        assertEquals("""["小三","阿三"]""", loaded.aliasesJson)
        assertEquals(1, fixture.characters.list(novelId).size)
    }

    @Test
    fun upsert_by_alias_reuses_existing_role_and_never_touches_voice() = runTest {
        createNovel()
        val first = fixture.characters.upsert(
            character(
                roleId = "role_0001",
                name = "张三",
                aliasesJson = """["小三"]""",
                category = "male_lead_steady",
                voiceId = "voice-1",
                speed = 1.1,
            ),
        )

        val merged = fixture.characters.upsert(
            character(
                roleId = "role_0002",
                name = "小三",
                aliasesJson = """["三哥"]""",
                category = "male_young_support",
                voiceId = "voice-2",
                speed = 0.9,
            ),
        )

        assertEquals(first.id, merged.id)
        assertEquals("role_0001", merged.roleId)
        assertEquals("张三", merged.name)
        val loaded = fixture.characters.getByRoleId(novelId, "role_0001")!!
        assertEquals("male_lead_steady", loaded.category)
        assertEquals("voice-1", loaded.voiceId)
        assertEquals("""["小三","三哥"]""", loaded.aliasesJson)
        assertNull(fixture.characters.getByRoleId(novelId, "role_0002"))
        assertEquals(1, fixture.characters.list(novelId).size)
    }

    @Test
    fun get_by_name_or_alias_is_exact_and_rejects_like_wildcard_false_positives() = runTest {
        createNovel()
        val first = fixture.characters.upsert(
            character(roleId = "role_0001", name = "甲", aliasesJson = """["aXb"]"""),
        )
        val second = fixture.characters.upsert(
            character(roleId = "role_0002", name = "乙", aliasesJson = """["a%b"]"""),
        )

        assertEquals(first.id, fixture.characters.getByNameOrAlias(novelId, "甲")?.id)
        assertEquals(first.id, fixture.characters.getByNameOrAlias(novelId, "aXb")?.id)
        // LIKE 会先误命中 role_0001 的 "aXb"，复核后必须回退到真正的 "a%b"。
        assertEquals(second.id, fixture.characters.getByNameOrAlias(novelId, "a%b")?.id)
        assertNull(fixture.characters.getByNameOrAlias(novelId, "a_b"))
        assertNull(fixture.characters.getByNameOrAlias(novelId, "  "))
    }

    @Test
    fun delete_removes_only_the_target_character() = runTest {
        createNovel()
        fixture.characters.upsert(
            character(roleId = "role_0001", name = "张三", aliasesJson = """["小三"]"""),
        )
        fixture.characters.upsert(
            character(roleId = "role_0002", name = "李四", aliasesJson = """["小李"]"""),
        )

        assertTrue(fixture.characters.delete(novelId, "role_0001"))
        assertNull(fixture.characters.getByRoleId(novelId, "role_0001"))
        assertEquals(listOf("role_0002"), fixture.characters.list(novelId).map { it.roleId })
        assertFalse("重复删除返回 false", fixture.characters.delete(novelId, "role_0001"))
    }

    private fun character(
        roleId: String,
        name: String,
        aliasesJson: String = "[]",
        category: String = "male_lead_steady",
        voiceId: String = "voice-1",
        speed: Double? = 1.1,
    ): CharacterEntity = CharacterEntity(
        novelId = novelId,
        roleId = roleId,
        name = name,
        category = category,
        voiceId = voiceId,
        speed = speed,
        aliasesJson = aliasesJson,
    )
}
