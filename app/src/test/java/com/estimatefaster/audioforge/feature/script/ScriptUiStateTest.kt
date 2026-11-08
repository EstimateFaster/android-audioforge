package com.estimatefaster.audioforge.feature.script

import com.estimatefaster.audioforge.core.model.Emotion
import com.estimatefaster.audioforge.core.model.NARRATOR_ROLE_ID
import com.estimatefaster.audioforge.core.model.SegmentType
import com.estimatefaster.audioforge.data.db.CharacterEntity
import com.estimatefaster.audioforge.data.db.SegmentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P5-01：剧本页 UI 状态 + ViewModel 的纯 JVM 测试。
 *
 * 不依赖 Android/Robolectric：投影、筛选、播放高亮都是纯函数；
 * ViewModel 用 [StandardTestDispatcher] 替换 Main，注入 fake [ScriptDataSource]。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScriptUiStateTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------
    // 段落映射：对白 / 旁白 / 情绪 / 角色名 / 音频可用性
    // ------------------------------------------------------------------

    @Test
    fun dialogue_segment_maps_role_type_emotion_and_audio() {
        val segments = mapScriptSegments(
            entities = listOf(
                entity(
                    id = 1,
                    type = "dialogue",
                    roleId = "role_0001",
                    emotion = "happy",
                    text = "你好，顾青。",
                    status = "done",
                    audioRelPath = "audio/1/ch_001/seg_000001.mp3",
                ),
            ),
            characters = listOf(character("role_0001", "沈砚")),
        )

        val segment = segments.single()
        assertEquals(0, segment.index)
        assertEquals(1, segment.number)
        assertEquals(1L, segment.key)
        assertEquals(SegmentType.Dialogue, segment.type)
        assertEquals(Emotion.Happy, segment.emotion)
        assertEquals("沈砚", segment.roleName)
        assertEquals("你好，顾青。", segment.text)
        assertEquals("done", segment.status)
        assertTrue(segment.hasAudio)
        assertFalse(segment.isNarration)
        assertFalse(segment.hasError)
    }

    @Test
    fun narration_and_unknown_roles_have_blank_name_for_ui_fallback() {
        val segments = mapScriptSegments(
            entities = listOf(
                entity(id = 1, type = "narration", roleId = NARRATOR_ROLE_ID, text = "旁白一句"),
                entity(id = 2, type = "dialogue", roleId = "role_9999", text = "未知角色"),
            ),
            characters = listOf(character("role_0001", "沈砚")),
        )

        assertEquals(SegmentType.Narration, segments[0].type)
        assertTrue(segments[0].isNarration)
        assertEquals(NARRATOR_ROLE_ID, segments[0].roleId)
        assertEquals("", segments[0].roleName)

        assertEquals(SegmentType.Dialogue, segments[1].type)
        assertEquals("role_9999", segments[1].roleId)
        assertEquals("", segments[1].roleName)
    }

    @Test
    fun segment_emotion_falls_back_to_neutral_and_error_is_normalized() {
        val segments = mapScriptSegments(
            entities = listOf(
                entity(id = 1, emotion = "not-an-emotion"),
                entity(id = 2, status = "done", errorMsg = "  "),
                entity(id = 3, status = "error", errorMsg = " tts failed "),
            ),
            characters = emptyList(),
        )

        assertEquals(Emotion.Neutral, segments[0].emotion)
        assertFalse(segments[0].hasError)

        assertFalse("空白错误信息不算错误", segments[1].hasError)
        assertNull(segments[1].errorMessage)

        assertTrue(segments[2].hasError)
        assertEquals("tts failed", segments[2].errorMessage)
    }

    @Test
    fun segment_without_audio_path_has_audio_disabled() {
        val segments = mapScriptSegments(
            entities = listOf(
                entity(id = 1, audioRelPath = null),
                entity(id = 2, audioRelPath = "  "),
                entity(id = 3, audioRelPath = "audio/seg.mp3"),
            ),
            characters = emptyList(),
        )

        assertFalse(segments[0].hasAudio)
        assertFalse(segments[1].hasAudio)
        assertTrue(segments[2].hasAudio)
    }

    // ------------------------------------------------------------------
    // 角色 chips
    // ------------------------------------------------------------------

    @Test
    fun character_chips_include_known_roles_and_an_implicit_narrator() {
        val segments = mapScriptSegments(
            entities = listOf(
                entity(id = 1, type = "narration", roleId = NARRATOR_ROLE_ID),
                entity(id = 2, roleId = "role_0001"),
            ),
            characters = listOf(
                character("role_0001", "沈砚"),
                character("role_0002", "顾青"),
            ),
        )

        val chips = buildScriptCharacters(
            characters = listOf(
                character("role_0001", "沈砚"),
                character("role_0002", "顾青"),
            ),
            segments = segments,
        )

        assertEquals(
            listOf(NARRATOR_ROLE_ID, "role_0001", "role_0002"),
            chips.map { it.roleId },
        )
        assertEquals("", chips.first().name)
        assertTrue(chips.first().isNarrator)
        assertEquals(listOf("沈砚", "顾青"), chips.drop(1).map { it.name })
    }

    @Test
    fun character_chips_do_not_duplicate_a_registered_narrator() {
        val segments = mapScriptSegments(
            entities = listOf(entity(id = 1, type = "narration", roleId = NARRATOR_ROLE_ID)),
            characters = listOf(character(NARRATOR_ROLE_ID, "旁白")),
        )

        val chips = buildScriptCharacters(
            characters = listOf(character(NARRATOR_ROLE_ID, "旁白")),
            segments = segments,
        )

        assertEquals(1, chips.size)
        assertEquals("旁白", chips.single().name)
    }

    // ------------------------------------------------------------------
    // 角色 / 搜索 / 组合过滤与空结果
    // ------------------------------------------------------------------

    @Test
    fun filter_by_role_returns_only_that_role() {
        val segments = sampleSegments()

        val filtered = filterScriptSegments(segments, roleId = "role_0002", query = "")

        assertEquals(listOf(2, 3), filtered.map { it.index })
        assertTrue(filtered.all { it.roleId == "role_0002" })
    }

    @Test
    fun filter_by_narrator_role() {
        val segments = sampleSegments()

        val filtered = filterScriptSegments(segments, roleId = NARRATOR_ROLE_ID, query = "")

        assertEquals(listOf(0), filtered.map { it.index })
    }

    @Test
    fun filter_by_query_trims_and_ignores_case() {
        val segments = sampleSegments()

        assertEquals(
            listOf(1),
            filterScriptSegments(segments, roleId = null, query = "  HELLO ").map { it.index },
        )
        assertEquals(
            listOf(0, 2),
            filterScriptSegments(segments, roleId = null, query = "雨").map { it.index },
        )
        assertEquals(4, filterScriptSegments(segments, roleId = null, query = "   ").size)
    }

    @Test
    fun filter_combines_role_and_query() {
        val segments = sampleSegments()

        assertEquals(
            listOf(3),
            filterScriptSegments(segments, roleId = "role_0002", query = "雨").map { it.index },
        )
        assertTrue(
            filterScriptSegments(segments, roleId = "role_0001", query = "雨").isEmpty(),
        )
    }

    @Test
    fun filter_returns_empty_for_no_match() {
        val segments = sampleSegments()

        assertTrue(filterScriptSegments(segments, roleId = null, query = "不存在").isEmpty())
        assertTrue(filterScriptSegments(segments, roleId = "role_9999", query = "").isEmpty())
    }

    @Test
    fun blank_role_id_means_all() {
        val segments = sampleSegments()

        assertEquals(segments, filterScriptSegments(segments, roleId = "  ", query = ""))
        assertEquals(segments, filterScriptSegments(segments, roleId = null, query = ""))
    }

    // ------------------------------------------------------------------
    // 空态 / 加载 / 错误态
    // ------------------------------------------------------------------

    @Test
    fun default_state_is_initial_loading_and_not_empty() {
        val state = ScriptUiState()

        assertTrue(state.isInitialLoading)
        assertFalse(state.isEmpty)
        assertFalse(state.isFilteredEmpty)
        assertFalse(state.hasActiveFilters)
        assertNull(state.playingSegment)
    }

    @Test
    fun empty_state_without_segments_is_empty_not_filtered() {
        val state = ScriptUiState(loading = false, chapterTitle = "第一章")

        assertTrue(state.isEmpty)
        assertFalse(state.isFilteredEmpty)
        assertFalse(state.isInitialLoading)
        assertFalse(state.hasActiveFilters)
    }

    @Test
    fun filtered_empty_state_requires_segments_but_no_visible_rows() {
        val items = sampleSegments()

        val state = ScriptUiState(
            loading = false,
            segments = items,
            selectedRoleId = "role_0001",
            query = "雨",
        )

        assertFalse(state.isEmpty)
        assertTrue(state.isFilteredEmpty)
        assertTrue(state.hasActiveFilters)
        assertTrue(state.visibleSegments.isEmpty())
    }

    @Test
    fun whitespace_query_is_not_an_active_filter() {
        val state = ScriptUiState(loading = false, segments = sampleSegments(), query = "   ")

        assertFalse(state.hasActiveFilters)
        assertEquals(4, state.visibleSegments.size)
    }

    @Test
    fun error_state_is_neither_empty_nor_filtered_empty() {
        val state = ScriptUiState(
            loading = false,
            loadError = "database is locked",
            segments = sampleSegments(),
        )

        assertFalse(state.isInitialLoading)
        assertFalse(state.isEmpty)
        assertFalse(state.isFilteredEmpty)
    }

    // ------------------------------------------------------------------
    // 播放高亮 / 自动滚动目标
    // ------------------------------------------------------------------

    @Test
    fun playing_segment_is_resolved_by_chapter_index() {
        val state = ScriptUiState(
            loading = false,
            segments = sampleSegments(),
            playingSegmentIndex = 2,
        )

        assertEquals(2, state.playingSegment?.index)
        assertEquals(2, state.scrollTargetFor())
        assertTrue(state.isPlaying(sampleSegments()[2]))
        assertFalse(state.isPlaying(sampleSegments()[1]))
    }

    @Test
    fun scroll_target_is_the_visible_position_when_filtered() {
        val state = ScriptUiState(
            loading = false,
            segments = sampleSegments(),
            selectedRoleId = "role_0002",
            playingSegmentIndex = 3,
        )

        assertEquals(listOf(2, 3), state.visibleSegments.map { it.index })
        assertEquals(1, state.scrollTargetFor())
    }

    @Test
    fun scroll_target_is_null_when_playing_segment_is_filtered_out() {
        val state = ScriptUiState(
            loading = false,
            segments = sampleSegments(),
            selectedRoleId = "role_0002",
            playingSegmentIndex = 0,
        )

        assertEquals(null, state.playingSegment)
        assertNull(state.scrollTargetFor())
    }

    @Test
    fun scroll_target_is_null_for_invalid_or_negative_indexes() {
        val state = ScriptUiState(loading = false, segments = sampleSegments())

        assertNull(state.scrollTargetFor(null))
        assertNull(state.scrollTargetFor(-1))
        assertNull(state.scrollTargetFor(99))
        assertNull(ScriptUiState(loading = false).scrollTargetFor(0))
    }

    // ------------------------------------------------------------------
    // 2000 段稳定 key
    // ------------------------------------------------------------------

    @Test
    fun mapping_2000_segments_keeps_stable_unique_keys() {
        val entities = (1..2000).map { index -> entity(id = index.toLong()) }
        val characters = listOf(character("role_0001", "沈砚"))

        val first = mapScriptSegments(entities, characters)
        val second = mapScriptSegments(entities, characters)

        assertEquals(2000, first.size)
        assertEquals("key 必须唯一", 2000, first.map { it.key }.distinct().size)
        assertEquals(first, second)
        assertEquals(0, first.first().index)
        assertEquals(2000, first.last().number)
        assertEquals(1999, first.last().index)
    }

    @Test
    fun fallback_keys_are_unique_when_entities_have_no_id() {
        val items = mapScriptSegments(
            entities = (1..5).map { entity(id = 0) },
            characters = emptyList(),
        )

        assertEquals(listOf(-1L, -2L, -3L, -4L, -5L), items.map { it.key })
        assertEquals(5, items.map { it.key }.distinct().size)
    }

    // ------------------------------------------------------------------
    // ViewModel：订阅 / 筛选 / 播放 / 错误重试
    // ------------------------------------------------------------------

    @Test
    fun view_model_publishes_segments_characters_and_title() = runTest(dispatcher) {
        val source = FakeScriptDataSource(
            segments = MutableStateFlow(
                listOf(
                    entity(id = 1, roleId = "role_0001", text = "第一句"),
                    entity(id = 2, roleId = "role_0002", text = "第二句"),
                    entity(id = 3, roleId = "role_0002", text = "第三句"),
                ),
            ),
            characters = MutableStateFlow(
                listOf(character("role_0001", "沈砚"), character("role_0002", "顾青")),
            ),
        )
        val viewModel = viewModel(source)

        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.loading)
        assertNull(state.loadError)
        assertEquals("第三章 旧信", state.chapterTitle)
        assertEquals(3, state.segments.size)
        assertEquals(listOf("沈砚", "顾青"), state.characters.map { it.name })
        assertFalse(state.isEmpty)
    }

    @Test
    fun view_model_filters_by_role_and_query_then_clears() = runTest(dispatcher) {
        val source = FakeScriptDataSource(
            segments = MutableStateFlow(sampleEntities()),
            characters = MutableStateFlow(
                listOf(character("role_0001", "沈砚"), character("role_0002", "顾青")),
            ),
        )
        val viewModel = viewModel(source)
        advanceUntilIdle()

        viewModel.setRoleFilter("role_0002")
        assertEquals(listOf(2, 3), viewModel.state.value.visibleSegments.map { it.index })

        viewModel.setQuery("雨")
        assertEquals(listOf(2), viewModel.state.value.visibleSegments.map { it.index })

        assertTrue(viewModel.state.value.hasActiveFilters)
        viewModel.clearFilters()
        assertEquals("", viewModel.state.value.query)
        assertNull(viewModel.state.value.selectedRoleId)
        assertEquals(4, viewModel.state.value.visibleSegments.size)
    }

    @Test
    fun view_model_keeps_playing_index_aligned_with_filters() = runTest(dispatcher) {
        val source = FakeScriptDataSource(
            segments = MutableStateFlow(sampleEntities()),
            characters = MutableStateFlow(
                listOf(character("role_0001", "沈砚"), character("role_0002", "顾青")),
            ),
        )
        val viewModel = viewModel(source)
        advanceUntilIdle()

        viewModel.setPlayingSegmentIndex(3)
        assertEquals(3, viewModel.state.value.playingSegmentIndex)
        assertEquals(3, viewModel.state.value.scrollTargetFor())

        viewModel.setRoleFilter("role_0002")
        assertEquals(1, viewModel.state.value.scrollTargetFor())

        viewModel.setRoleFilter("role_0001")
        assertNull("播放段被筛掉时不强制滚动", viewModel.state.value.scrollTargetFor())

        viewModel.setPlayingSegmentIndex(99)
        assertNull("越界索引收敛为 null", viewModel.state.value.playingSegmentIndex)
    }

    @Test
    fun view_model_empty_source_is_empty_and_not_loading() = runTest(dispatcher) {
        val viewModel = viewModel(FakeScriptDataSource())

        advanceUntilIdle()

        assertTrue(viewModel.state.value.isEmpty)
        assertFalse(viewModel.state.value.loading)
        assertFalse(viewModel.state.value.isFilteredEmpty)
    }

    @Test
    fun view_model_reports_error_and_recovers_on_retry() = runTest(dispatcher) {
        val source = FakeScriptDataSource().apply { failSegments = true }
        val viewModel = viewModel(source)

        advanceUntilIdle()
        assertNotNull(viewModel.state.value.loadError)
        assertFalse(viewModel.state.value.loading)
        assertEquals(0, viewModel.state.value.segments.size)

        source.failSegments = false
        source.segments.value = sampleEntities()
        viewModel.retry()
        advanceUntilIdle()

        assertNull(viewModel.state.value.loadError)
        assertEquals(4, viewModel.state.value.segments.size)
        assertFalse(viewModel.state.value.isEmpty)
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private fun viewModel(source: ScriptDataSource): ScriptViewModel = ScriptViewModel(
        chapterId = 1L,
        novelId = 2L,
        source = source,
        chapterTitle = "第三章 旧信",
    )

    /** 0 旁白（雨） / 1 沈砚（Hello World） / 2 顾青（雨） / 3 顾青（无关键词）。 */
    private fun sampleEntities(): List<SegmentEntity> = listOf(
        entity(
            id = 1,
            type = "narration",
            roleId = NARRATOR_ROLE_ID,
            emotion = "neutral",
            text = "旧城的雨落了一夜。",
        ),
        entity(
            id = 2,
            type = "dialogue",
            roleId = "role_0001",
            emotion = "happy",
            text = "Hello World",
        ),
        entity(
            id = 3,
            type = "dialogue",
            roleId = "role_0002",
            emotion = "sad",
            text = "雨停了，他却还没有回来。",
        ),
        entity(
            id = 4,
            type = "dialogue",
            roleId = "role_0002",
            emotion = "calm",
            text = "第二句。",
        ),
    )

    private fun sampleSegments(): List<ScriptSegmentUi> = mapScriptSegments(
        entities = sampleEntities(),
        characters = listOf(character("role_0001", "沈砚"), character("role_0002", "顾青")),
    )

    private fun entity(
        id: Long,
        seq: Int = (id - 1).toInt().coerceAtLeast(0),
        type: String = "dialogue",
        roleId: String = "role_0001",
        emotion: String = "neutral",
        text: String = "第${id}句",
        status: String = "pending",
        audioRelPath: String? = null,
        errorMsg: String? = null,
    ): SegmentEntity = SegmentEntity(
        id = id,
        chapterId = 1L,
        seq = seq,
        type = type,
        roleId = roleId,
        emotion = emotion,
        text = text,
        status = status,
        audioRelPath = audioRelPath,
        errorMsg = errorMsg,
    )

    private fun character(roleId: String, name: String): CharacterEntity = CharacterEntity(
        novelId = 2L,
        roleId = roleId,
        name = name,
    )

    private class FakeScriptDataSource(
        val segments: MutableStateFlow<List<SegmentEntity>> = MutableStateFlow(emptyList()),
        val characters: MutableStateFlow<List<CharacterEntity>> = MutableStateFlow(emptyList()),
    ) : ScriptDataSource {
        var failSegments: Boolean = false

        override fun observeSegments(chapterId: Long): Flow<List<SegmentEntity>> =
            if (failSegments) {
                flow { throw IllegalStateException("database is locked") }
            } else {
                segments
            }

        override fun observeCharacters(novelId: Long): Flow<List<CharacterEntity>> = characters
    }
}
