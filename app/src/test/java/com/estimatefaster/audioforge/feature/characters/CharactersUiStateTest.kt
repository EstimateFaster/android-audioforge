package com.estimatefaster.audioforge.feature.characters

import com.estimatefaster.audioforge.core.model.Gender
import com.estimatefaster.audioforge.core.model.Importance
import com.estimatefaster.audioforge.data.db.CharacterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5-04：人物音色页 UI 状态的纯 JVM 测试。
 *
 * 不依赖 Android/Robolectric，也不启动协程：只验证搜索、筛选、实体映射、
 * 计数/空态/加载/错误，以及 2000 人时的稳定列表 key。
 */
class CharactersUiStateTest {

    // ------------------------------------------------------------------
    // 搜索：名字 / 别名 / trim / 大小写
    // ------------------------------------------------------------------

    @Test
    fun search_matches_name_with_trim_and_case_insensitive() {
        val state = buildState(
            listOf(
                character(1, "Alice", aliases = "[\"小爱\"]"),
                character(2, "Bob", aliases = "[\"小博\"]"),
            ),
            query = "  ALICE  ",
        )

        assertEquals(listOf(1L), state.visibleCharacters.map { it.id })
        assertEquals(2, state.totalCount)
        assertEquals(1, state.visibleCount)
        assertTrue(state.hasActiveFilters)
    }

    @Test
    fun search_matches_alias_and_trims_both_sides() {
        val chars = listOf(
            character(1, "林黛玉", aliases = "[\"黛玉\",\" 颦儿 \"]"),
            character(2, "贾宝玉", aliases = "[\"宝玉\"]"),
        )

        assertEquals(
            listOf(1L),
            buildState(chars, query = " 颦儿 ").visibleCharacters.map { it.id },
        )
        assertEquals(
            listOf(2L),
            buildState(chars, query = "宝玉").visibleCharacters.map { it.id },
        )
    }

    @Test
    fun whitespace_only_query_returns_all_without_active_filter() {
        val state = buildState(
            listOf(character(1, "A"), character(2, "B")),
            query = " \t\n ",
        )

        assertEquals(2, state.visibleCount)
        assertFalse(state.hasActiveFilters)
        assertEquals(listOf(1L, 2L), state.visibleCharacters.map { it.id })
    }

    @Test
    fun no_match_is_filtered_empty_but_not_book_empty() {
        val state = buildState(
            listOf(character(1, "Alice"), character(2, "Bob")),
            query = "不存在",
        )

        assertTrue(state.isFilteredEmpty)
        assertFalse(state.isEmpty)
        assertFalse(state.hasResults)
        assertEquals(0, state.visibleCount)
        assertEquals(2, state.totalCount)
    }

    // ------------------------------------------------------------------
    // 筛选：性别 / 重要性 / 组合 / 顺序
    // ------------------------------------------------------------------

    @Test
    fun gender_and_importance_filters_combine_with_and_semantics() {
        val chars = listOf(
            character(1, "男主", gender = "male", importance = "protagonist"),
            character(2, "女主", gender = "female", importance = "protagonist"),
            character(3, "男配", gender = "male", importance = "supporting"),
            character(4, "女配", gender = "female", importance = "minor"),
        )

        assertEquals(
            listOf(1L, 3L),
            buildState(chars, gender = Gender.Male).visibleCharacters.map { it.id },
        )
        assertEquals(
            listOf(1L, 2L),
            buildState(chars, importance = Importance.Protagonist)
                .visibleCharacters.map { it.id },
        )
        assertEquals(
            listOf(1L),
            buildState(chars, gender = Gender.Male, importance = Importance.Protagonist)
                .visibleCharacters.map { it.id },
        )
        assertEquals(
            listOf(4L),
            buildState(chars, gender = Gender.Female, importance = Importance.Minor)
                .visibleCharacters.map { it.id },
        )
    }

    @Test
    fun null_filters_mean_all_and_preserve_repository_order() {
        val state = buildState(
            listOf(
                character(3, "C", gender = "female", importance = "minor"),
                character(1, "A", gender = "male", importance = "protagonist"),
                character(2, "B", gender = "unknown", importance = "supporting"),
            ),
        )

        assertEquals(listOf(3L, 1L, 2L), state.allCharacters.map { it.id })
        assertEquals(listOf(3L, 1L, 2L), state.visibleCharacters.map { it.id })
        assertFalse(state.hasActiveFilters)
    }

    @Test
    fun unknown_wire_values_fall_back_to_unknown_and_supporting() {
        val state = buildState(
            listOf(character(1, "X", gender = "robot", importance = "boss")),
        )
        val row = state.visibleCharacters.single()

        assertEquals(Gender.Unknown, row.gender)
        assertEquals(Importance.Supporting, row.importance)
    }

    // ------------------------------------------------------------------
    // 映射：音色名 / category / locked / 别名 / note
    // ------------------------------------------------------------------

    @Test
    fun mapping_reads_voice_label_category_locked_aliases_and_note() {
        val state = buildState(
            listOf(
                character(
                    id = 7,
                    name = "  林黛玉  ",
                    gender = "female",
                    importance = "protagonist",
                    aliases = "[\"黛玉\",\" 颦儿 \",\"黛玉\",\"\"]",
                    category = "female_lead_gentle",
                    locked = true,
                    note = "  体弱多病  ",
                ),
            ),
        )
        val row = state.allCharacters.single()

        assertEquals("林黛玉", row.name)
        assertEquals(listOf("黛玉", "颦儿"), row.aliases)
        assertEquals(Gender.Female, row.gender)
        assertEquals(Importance.Protagonist, row.importance)
        assertEquals("female_lead_gentle", row.category)
        assertEquals("温柔清亮", row.voiceLabel)
        assertTrue(row.hasVoice)
        assertTrue(row.locked)
        assertEquals("体弱多病", row.note)
        assertEquals("role_0007", row.key)
        assertEquals("林黛玉", row.displayName)
    }

    @Test
    fun unknown_or_blank_category_has_no_voice_label() {
        val unknown = buildState(
            listOf(character(1, "A", category = "mystery_voice")),
        ).allCharacters.single()
        assertNull(unknown.voiceLabel)
        assertEquals("mystery_voice", unknown.category)
        assertTrue("已有类别但音色池不认识，仍算已分配", unknown.hasVoice)

        val blank = buildState(
            listOf(character(2, "B", category = "   ")),
        ).allCharacters.single()
        assertNull(blank.voiceLabel)
        assertEquals("", blank.category)
        assertFalse(blank.hasVoice)
    }

    @Test
    fun malformed_alias_json_never_throws() {
        val rows = buildState(
            listOf(
                character(1, "A", aliases = "not-json"),
                character(2, "B", aliases = ""),
                character(3, "C", aliases = "{}"),
                character(4, "D", aliases = "[\" \", \"\"]"),
            ),
        ).allCharacters

        assertTrue(rows[0].aliases.isEmpty())
        assertTrue(rows[1].aliases.isEmpty())
        assertTrue(rows[2].aliases.isEmpty())
        assertTrue(rows[3].aliases.isEmpty())
    }

    // ------------------------------------------------------------------
    // 计数 / 空态 / 加载 / 错误
    // ------------------------------------------------------------------

    @Test
    fun counts_track_total_used_and_visible_separately() {
        val state = buildState(
            listOf(
                character(1, "A", category = "female_lead_gentle"),
                character(2, "B", category = "male_lead_steady"),
                character(3, "C"),
                character(4, "D"),
            ),
            query = "A",
        )

        assertEquals(4, state.totalCount)
        assertEquals(2, state.usedCount)
        assertEquals(1, state.visibleCount)
    }

    @Test
    fun initial_loading_needs_no_data_and_no_error() {
        val loading = CharactersUiState()
        assertTrue(loading.isInitialLoading)
        assertFalse(loading.isEmpty)
        assertFalse(loading.hasResults)
        assertEquals(0, loading.totalCount)

        // 已有缓存数据时刷新不再显示整页 loading。
        val refreshing = buildState(listOf(character(1, "A")), loading = true)
        assertFalse(refreshing.isInitialLoading)
        assertFalse(refreshing.isEmpty)
    }

    @Test
    fun empty_state_is_distinct_from_filtered_empty() {
        val empty = CharactersUiState(loading = false)
        assertTrue(empty.isEmpty)
        assertFalse(empty.isFilteredEmpty)
        assertFalse(empty.isInitialLoading)
        assertFalse(empty.hasResults)

        val filtered = buildState(listOf(character(1, "A")), query = "B")
        assertFalse(filtered.isEmpty)
        assertTrue(filtered.isFilteredEmpty)
    }

    @Test
    fun error_state_wins_over_empty_and_is_not_initial_loading() {
        val error = CharactersUiState(
            loading = false,
            loadError = "timeout",
        )

        assertEquals("timeout", error.loadError)
        assertFalse(error.isInitialLoading)
        assertFalse(error.isEmpty)
        assertFalse(error.isFilteredEmpty)
        assertFalse(error.hasResults)
    }

    // ------------------------------------------------------------------
    // 2000 人：key 稳定且唯一
    // ------------------------------------------------------------------

    @Test
    fun two_thousand_characters_have_stable_unique_keys() {
        val entities = (1..2000).map { index ->
            character(
                id = index.toLong(),
                name = "角色$index",
                gender = if (index % 2 == 0) "female" else "male",
                importance = when {
                    index % 5 == 0 -> "minor"
                    index % 2 == 0 -> "supporting"
                    else -> "protagonist"
                },
            )
        }

        val first = buildState(entities)
        val second = buildState(entities)

        assertEquals(2000, first.totalCount)
        assertEquals(2000, first.visibleCount)
        assertEquals((1L..2000L).toList(), first.allCharacters.map { it.id })

        val keys = first.allCharacters.map { it.key }
        assertEquals("2000 个角色必须有 2000 个唯一 key", 2000, keys.toSet().size)
        assertEquals("同一快照重建后 key 必须稳定", keys, second.allCharacters.map { it.key })

        val filtered = buildState(entities, query = "角色1999")
        assertEquals(1, filtered.visibleCount)
        assertEquals("role_1999", filtered.visibleCharacters.single().key)
        assertEquals(
            first.allCharacters.first { it.id == 1999L }.key,
            filtered.visibleCharacters.single().key,
        )

        val male = buildState(entities, gender = Gender.Male)
        assertEquals(1000, male.visibleCount)
        assertEquals(male.allCharacters.count { it.gender == Gender.Male }, male.visibleCount)
    }

    // ------------------------------------------------------------------
    // 测试夹具
    // ------------------------------------------------------------------

    private fun buildState(
        entities: List<CharacterEntity>,
        query: String = "",
        gender: Gender? = null,
        importance: Importance? = null,
        loading: Boolean = false,
        loadError: String? = null,
    ): CharactersUiState = buildCharactersUiState(
        entities = entities,
        voiceLabelFor = { category -> voiceLabels[category] },
        query = query,
        genderFilter = gender,
        importanceFilter = importance,
        loading = loading,
        loadError = loadError,
    )

    private fun character(
        id: Long,
        name: String,
        gender: String = "unknown",
        importance: String = "supporting",
        aliases: String = "[]",
        category: String = "",
        locked: Boolean = true,
        note: String = "",
    ): CharacterEntity = CharacterEntity(
        id = id,
        novelId = 1L,
        roleId = "role_" + id.toString().padStart(4, '0'),
        name = name,
        gender = gender,
        importance = importance,
        aliasesJson = aliases,
        note = note,
        category = category,
        voiceId = if (category.isBlank()) "" else "voice-$category",
        locked = locked,
    )

    private companion object {
        private val voiceLabels = mapOf(
            "female_lead_gentle" to "温柔清亮",
            "male_lead_steady" to "沉稳磁性",
            "male_gentle_young" to "青年柔和",
        )
    }
}
