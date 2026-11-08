package com.estimatefaster.audioforge.core.script

import com.estimatefaster.audioforge.core.model.Gender
import com.estimatefaster.audioforge.core.model.Importance
import com.estimatefaster.audioforge.core.model.NewCharacter
import com.estimatefaster.audioforge.core.voice.VoicePool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** P1-07：5 个注册场景 + 音色锁定。 */
class CharacterRegistryTest {

    private lateinit var store: InMemoryCharacterStore
    private lateinit var pool: VoicePool
    private lateinit var registry: CharacterRegistry

    @Before
    fun setUp() {
        store = InMemoryCharacterStore()
        pool = VoicePool.parse(readResource("voice-pool.json"))
        registry = CharacterRegistry(store, pool)
    }

    @Test
    fun new_character_gets_sequential_role_id_and_llm_voice() {
        val first = registry.register(
            NewCharacter(
                name = "韩立",
                gender = Gender.Male,
                importance = Importance.Protagonist,
                aliases = listOf("小立"),
                note = "主角",
                voice = "male_lead_steady",
            ),
        )!!
        assertEquals("role_0001", first.roleId)
        assertEquals("male_lead_steady", first.category)
        assertEquals(pool.voiceIdFor("male_lead_steady"), first.voiceId)
        assertNull(first.speed)
        assertTrue(first.locked)

        val second = registry.register(NewCharacter(name = "阿甲", gender = Gender.Male))
        assertEquals("role_0002", second!!.roleId)
        assertEquals(2, store.all().size)
    }

    @Test
    fun invalid_or_empty_voice_falls_back_by_gender_and_importance() {
        val male = registry.register(
            NewCharacter(name = "甲", gender = Gender.Male, importance = Importance.Minor, voice = "nope"),
        )!!
        assertEquals("male_young_support", male.category)

        val female = registry.register(
            NewCharacter(
                name = "乙",
                gender = Gender.Female,
                importance = Importance.Protagonist,
                voice = "",
            ),
        )!!
        assertEquals("female_lead_gentle", female.category)

        val unknown = registry.register(
            NewCharacter(name = "丙", gender = Gender.Unknown, importance = Importance.Supporting),
        )!!
        assertEquals("male_gentle_young", unknown.category)
    }

    @Test
    fun re_registering_existing_name_never_overwrites_voice_but_merges_aliases() {
        val first = registry.register(
            NewCharacter(name = "韩立", gender = Gender.Male, voice = "male_lead_steady"),
        )!!
        val second = registry.register(
            NewCharacter(
                name = "韩立",
                gender = Gender.Female,
                voice = "female_young",
                aliases = listOf("韩天尊"),
            ),
        )!!

        assertEquals(first.roleId, second.roleId)
        assertEquals("male_lead_steady", second.category)
        assertEquals(first.voiceId, second.voiceId)
        assertEquals(first.gender, second.gender)
        assertTrue(second.aliases.contains("韩天尊"))
        assertEquals(1, store.all().size)
    }

    @Test
    fun lookup_by_alias_reuses_the_same_role() {
        val first = registry.register(
            NewCharacter(name = "韩立", aliases = listOf("小立"), voice = "male_lead_steady"),
        )!!
        val byAlias = registry.register(
            NewCharacter(name = "小立", voice = "male_brother"),
        )!!

        assertEquals(first.roleId, byAlias.roleId)
        assertEquals("male_lead_steady", byAlias.category)
        assertEquals(1, store.all().size)
        assertEquals(first.roleId, registry.roleIdFor("小立"))
    }

    @Test
    fun alias_of_merges_into_existing_character_without_new_role() {
        val target = registry.register(
            NewCharacter(name = "韩立", voice = "male_lead_steady"),
        )!!
        val merged = registry.register(
            NewCharacter(name = "韩天尊", aliasOf = "韩立", aliases = listOf("老韩")),
        )!!

        assertEquals(target.roleId, merged.roleId)
        assertEquals(1, store.all().size)
        assertTrue(merged.aliases.containsAll(listOf("韩天尊", "老韩")))
        assertEquals(target.category, merged.category)
        assertEquals(target.voiceId, merged.voiceId)
        assertEquals(target.roleId, registry.roleIdFor("韩天尊"))
    }

    @Test
    fun excluded_characters_are_not_registered() {
        assertNull(
            registry.register(
                NewCharacter(name = "韩立", voice = "male_lead_steady"),
                excluded = setOf("韩立"),
            ),
        )
        assertTrue(store.all().isEmpty())

        assertNull(
            registry.register(
                NewCharacter(name = "阿甲", aliases = listOf("甲哥")),
                excluded = setOf("甲哥"),
            ),
        )
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun register_all_skips_excluded_and_keeps_order() {
        val out = registry.registerAll(
            listOf(
                NewCharacter(name = "韩立"),
                NewCharacter(name = "韩立"),
                NewCharacter(name = "阿甲"),
            ),
            excluded = setOf("阿甲"),
        )
        assertEquals(2, out.size)
        assertEquals("role_0001", out[0].roleId)
        assertEquals(1, store.all().size)
    }

    @Test
    fun male_brother_keeps_recommended_speed() {
        val brother = registry.register(
            NewCharacter(name = "兄长", gender = Gender.Male, voice = "male_brother"),
        )!!
        assertEquals(1.15, brother.speed ?: 0.0, 1e-9)
    }

    @Test
    fun role_id_lookup_and_narrator_fallback() {
        registry.register(NewCharacter(name = "韩立", aliases = listOf("小立")))
        assertEquals("role_0001", registry.roleIdFor("韩立"))
        assertEquals("role_0001", registry.roleIdFor("小立"))
        assertEquals("narrator", registry.roleIdFor("narrator"))
        assertNull(registry.roleIdFor("unknown"))
        assertNull(registry.roleIdFor(""))
        assertEquals("narrator", registry.resolveSpeaker("unknown"))
        assertEquals("narrator", registry.resolveSpeaker("不存在的人"))
    }

    @Test
    fun prompt_block_format_matches_python() {
        assertEquals("（角色表为空）", registry.promptBlock())
        registry.register(
            NewCharacter(
                name = "韩立",
                gender = Gender.Male,
                importance = Importance.Protagonist,
                aliases = listOf("小立"),
                note = "主角",
                voice = "male_lead_steady",
            ),
        )
        assertEquals(
            "- 韩立(role_0001) 性别:male 重要度:protagonist 别名:小立 | 主角",
            registry.promptBlock(),
        )
    }

    private fun readResource(name: String): String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream(name),
    ) { "缺少测试资源 $name" }
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }
}
