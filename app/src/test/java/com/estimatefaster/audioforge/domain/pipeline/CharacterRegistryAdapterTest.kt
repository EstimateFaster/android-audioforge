package com.estimatefaster.audioforge.domain.pipeline

import com.estimatefaster.audioforge.core.model.Gender
import com.estimatefaster.audioforge.core.model.Importance
import com.estimatefaster.audioforge.core.model.NewCharacter
import com.estimatefaster.audioforge.core.voice.VoicePool
import com.estimatefaster.audioforge.data.db.CharacterEntity
import com.estimatefaster.audioforge.data.repository.RepositoryFixture
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P2-13：注册适配器的落库/复用/alias_of/excluded/重跑稳定/事务回滚。
 *
 * 用 Robolectric + in-memory Room（复用 P2-02 的 [RepositoryFixture]），
 * 角色表断言直接读 `CharacterRepository`，确保测的是 DB 而不是内存快照。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CharacterRegistryAdapterTest {

    private lateinit var fixture: RepositoryFixture
    private lateinit var pool: VoicePool

    @Before
    fun setUp() {
        fixture = RepositoryFixture()
        pool = VoicePool.parse(readResource("voice-pool.json"))
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun new_characters_get_sequential_role_ids_and_persist_metadata() = runTest {
        val novelId = fixture.createEmptyNovel("注册")
        val adapter = adapter(novelId)

        val registered = adapter.registerAll(
            listOf(
                NewCharacter(
                    name = "韩立",
                    gender = Gender.Male,
                    importance = Importance.Protagonist,
                    aliases = listOf("小立", " "),
                    note = "主角",
                    voice = "male_lead_steady",
                ),
                NewCharacter(name = "兄长", gender = Gender.Male, voice = "male_brother"),
                NewCharacter(name = "阿甲", gender = Gender.Male, importance = Importance.Minor),
            ),
            excluded = emptySet(),
        )

        assertEquals(
            listOf("role_0001", "role_0002", "role_0003"),
            registered.map { it.roleId },
        )
        assertEquals("male_lead_steady", registered[0].category)
        assertEquals(pool.voiceIdFor("male_lead_steady"), registered[0].voiceId)
        assertNull("普通类别没有建议语速", registered[0].speed)
        assertEquals("male_brother", registered[1].category)
        assertEquals(1.15, registered[1].speed ?: 0.0, 1e-9)
        assertEquals("male_young_support", registered[2].category)

        val rows = fixture.characters.list(novelId).associateBy { it.roleId }
        assertEquals(3, rows.size)

        val han = rows.getValue("role_0001")
        assertEquals("韩立", han.name)
        assertEquals("male", han.gender)
        assertEquals("protagonist", han.importance)
        assertEquals("主角", han.note)
        assertEquals("""["小立"]""", han.aliasesJson)
        assertEquals("male_lead_steady", han.category)
        assertEquals(pool.voiceIdFor("male_lead_steady"), han.voiceId)
        assertNull(han.speed)
        assertTrue(han.locked)

        val info = adapter.registry.all().first { it.roleId == "role_0001" }
        assertEquals(listOf("小立"), info.aliases)
        assertEquals(Gender.Male, info.gender)
        assertEquals(Importance.Protagonist, info.importance)
        assertEquals("主角", info.note)
    }

    @Test
    fun re_registering_same_name_keeps_role_and_voice_but_merges_aliases() = runTest {
        val novelId = fixture.createEmptyNovel("同名复用")
        val adapter = adapter(novelId)

        val first = adapter.registerAll(
            listOf(
                NewCharacter(
                    name = "韩立",
                    gender = Gender.Male,
                    importance = Importance.Protagonist,
                    aliases = listOf("小立"),
                    voice = "male_lead_steady",
                ),
            ),
            excluded = emptySet(),
        ).single()

        val second = adapter.registerAll(
            listOf(
                NewCharacter(
                    name = "韩立",
                    gender = Gender.Female,
                    importance = Importance.Minor,
                    aliases = listOf("韩天尊"),
                    voice = "female_young",
                ),
            ),
            excluded = emptySet(),
        ).single()

        assertEquals(first.roleId, second.roleId)
        assertEquals("role_0001", second.roleId)
        assertEquals("male_lead_steady", second.category)
        assertEquals(pool.voiceIdFor("male_lead_steady"), second.voiceId)
        assertEquals("已有角色性别不被覆盖", Gender.Male, second.gender)
        assertEquals(1, fixture.characters.list(novelId).size)

        val row = fixture.characters.list(novelId).single()
        assertEquals("male_lead_steady", row.category)
        assertEquals(pool.voiceIdFor("male_lead_steady"), row.voiceId)
        assertEquals("""["小立","韩天尊"]""", row.aliasesJson)
    }

    @Test
    fun registering_by_alias_reuses_role_and_never_overwrites_voice() = runTest {
        val novelId = fixture.createEmptyNovel("别名复用")
        val adapter = adapter(novelId)

        val first = adapter.registerAll(
            listOf(NewCharacter(name = "韩立", aliases = listOf("小立"), voice = "male_lead_steady")),
            excluded = emptySet(),
        ).single()

        val byAlias = adapter.registerAll(
            listOf(NewCharacter(name = "小立", voice = "female_young")),
            excluded = emptySet(),
        ).single()

        assertEquals(first.roleId, byAlias.roleId)
        assertEquals("male_lead_steady", byAlias.category)
        assertEquals(pool.voiceIdFor("male_lead_steady"), byAlias.voiceId)
        assertEquals(1, fixture.characters.list(novelId).size)
        assertEquals(first.roleId, adapter.registry.roleIdFor("小立"))
    }

    @Test
    fun alias_of_merges_into_existing_role_without_creating_a_new_one() = runTest {
        val novelId = fixture.createEmptyNovel("alias_of")
        val adapter = adapter(novelId)

        val target = adapter.registerAll(
            listOf(NewCharacter(name = "韩立", voice = "male_lead_steady")),
            excluded = emptySet(),
        ).single()

        val merged = adapter.registerAll(
            listOf(NewCharacter(name = "韩天尊", aliasOf = "韩立", aliases = listOf("老韩"))),
            excluded = emptySet(),
        ).single()

        assertEquals(target.roleId, merged.roleId)
        assertEquals("male_lead_steady", merged.category)
        assertTrue(merged.aliases.containsAll(listOf("韩天尊", "老韩")))
        assertEquals(1, fixture.characters.list(novelId).size)
        assertEquals(target.roleId, adapter.registry.roleIdFor("韩天尊"))

        // alias_of 目标不存在时按新角色处理，避免丢台词。
        val orphan = adapter.registerAll(
            listOf(NewCharacter(name = "厉飞雨", aliasOf = "查无此人")),
            excluded = emptySet(),
        ).single()
        assertEquals("role_0002", orphan.roleId)
        assertEquals(2, fixture.characters.list(novelId).size)
    }

    @Test
    fun excluded_characters_are_not_written_and_do_not_consume_role_ids() = runTest {
        val novelId = fixture.createEmptyNovel("排除")
        val adapter = adapter(novelId)

        val registered = adapter.registerAll(
            listOf(
                NewCharacter(name = "韩立", aliases = listOf("小立")),
                NewCharacter(name = "阿甲", aliases = listOf("甲哥")),
                NewCharacter(name = "韩天尊", aliasOf = "韩立"),
            ),
            excluded = setOf("小立", "甲哥", "韩天尊"),
        )

        assertTrue(registered.isEmpty())
        assertTrue(fixture.characters.list(novelId).isEmpty())

        // 被排除的角色不占用序号：第一次真正注册仍是 role_0001。
        val first = adapter.registerAll(
            listOf(NewCharacter(name = "厉飞雨")),
            excluded = emptySet(),
        ).single()
        assertEquals("role_0001", first.roleId)
    }

    @Test
    fun replaying_the_same_chapter_keeps_role_ids_and_voices_stable() = runTest {
        val novelId = fixture.createEmptyNovel("重跑")
        val adapter = adapter(novelId)
        val chapter = listOf(
            NewCharacter(name = "韩立", aliases = listOf("小立"), voice = "male_lead_steady"),
            NewCharacter(name = "阿甲", voice = "male_brother"),
        )

        val first = adapter.registerAll(chapter, excluded = emptySet())
        val firstRows = snapshot(novelId)

        val second = adapter.registerAll(chapter, excluded = emptySet())
        val secondRows = snapshot(novelId)

        assertEquals(first.map { it.roleId }, second.map { it.roleId })
        assertEquals(first.map { it.voiceId }, second.map { it.voiceId })
        assertEquals(firstRows, secondRows)
        assertEquals(2, secondRows.size)
    }

    @Test
    fun register_all_rolls_back_the_whole_batch_when_a_character_is_invalid() = runTest {
        val novelId = fixture.createEmptyNovel("事务")
        val adapter = adapter(novelId)

        @Suppress("UNCHECKED_CAST")
        val poisoned = listOf(
            NewCharacter(name = "韩立", voice = "male_lead_steady"),
            null,
        ) as List<NewCharacter>

        try {
            adapter.registerAll(poisoned, excluded = emptySet())
            fail("非法角色应让整批注册失败")
        } catch (_: NullPointerException) {
            // 期望：null 在 CharacterRegistry.register 的入口检查处抛出。
        }

        assertTrue(
            "事务回滚后第一条角色也不能落库",
            fixture.characters.list(novelId).isEmpty(),
        )
    }

    @Test
    fun refresh_exposes_existing_rows_to_the_registry() = runTest {
        val novelId = fixture.createEmptyNovel("刷新")
        fixture.characters.upsert(
            CharacterEntity(
                novelId = novelId,
                roleId = "role_0001",
                name = "韩立",
                aliasesJson = """["小立"]""",
                category = "male_lead_steady",
                voiceId = pool.voiceIdFor("male_lead_steady")!!,
            ),
        )

        val adapter = adapter(novelId)
        adapter.refresh()

        assertEquals("role_0001", adapter.registry.roleIdFor("小立"))
        assertEquals("narrator", adapter.registry.resolveSpeaker("查无此人"))
        assertTrue(adapter.registry.promptBlock().contains("韩立(role_0001)"))
    }

    @Test
    fun new_role_ids_continue_after_the_existing_max() = runTest {
        val novelId = fixture.createEmptyNovel("续号")
        fixture.characters.upsert(
            CharacterEntity(
                novelId = novelId,
                roleId = "role_0007",
                name = "旧角色",
                category = "male_lead_steady",
                voiceId = pool.voiceIdFor("male_lead_steady")!!,
            ),
        )

        val adapter = adapter(novelId)
        val created = adapter.registerAll(
            listOf(NewCharacter(name = "新角色")),
            excluded = emptySet(),
        ).single()

        assertEquals("role_0008", created.roleId)
    }

    // ---------------------------------------------------------------- helpers

    private fun adapter(novelId: Long): CharacterRegistryAdapter =
        CharacterRegistryAdapter(fixture.db, fixture.characters, pool, novelId)

    private suspend fun snapshot(novelId: Long): List<Triple<String, String, String>> =
        fixture.characters.list(novelId).map { Triple(it.roleId, it.category, it.voiceId) }

    private fun readResource(name: String): String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream(name),
    ) { "缺少测试资源 $name" }
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }
}
