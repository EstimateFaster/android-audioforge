package com.estimatefaster.audioforge.domain.pipeline

import com.estimatefaster.audioforge.core.model.PipelineError
import com.estimatefaster.audioforge.core.network.LlmClient
import com.estimatefaster.audioforge.core.network.LlmConfig
import com.estimatefaster.audioforge.core.voice.PromptRenderer
import com.estimatefaster.audioforge.core.voice.PromptTemplates
import com.estimatefaster.audioforge.core.voice.VoicePool
import com.estimatefaster.audioforge.data.repository.ChapterInput
import com.estimatefaster.audioforge.data.repository.RepositoryFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P2-11 的解析用例核心场景 + P2-19 长章节守卫（执行计划 §8.7 / §8.10）。
 *
 * 用 Robolectric + in-memory Room + 真实 sanitizer/coverage/角色适配器；
 * 只有 LLM 被替换成可注入的 `suspend (String) -> String`，因此测到的是
 * 「提示词 → JSON → 映射 → 清洗 → 覆盖率 → 落库」的完整链路。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AnalyzeChapterUseCaseTest {

    private lateinit var fixture: RepositoryFixture
    private lateinit var pool: VoicePool

    private val renderer = PromptRenderer(
        PromptTemplates(
            script = "SCRIPT\nregistry={registry}\nemotions={emotions}\n" +
                "pool={voice_pool}\ntext={text}",
            pass0 = "",
            repair = "\nREPAIR\n{missing}",
        ),
    )

    /** 真实 [LlmClient] 只作为构造依赖；所有调用都走 [AnalyzeChapterUseCase] 的注入槽。 */
    private val dummyClient: LlmClient by lazy {
        LlmClient(
            LlmConfig(
                baseUrl = "http://127.0.0.1:1",
                model = "test-model",
                apiKey = "test-key",
                maxRetries = 0,
            ),
        )
    }

    @Before
    fun setUp() {
        fixture = RepositoryFixture()
        pool = VoicePool.parse(readResource("voice-pool.json"))
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    // ------------------------------------------------------------ 正常路径

    @Test
    fun valid_json_persists_segments_and_registers_characters() = runTest {
        val rawText = "韩立说道：“你好。”"
        val ref = createChapter(rawText)
        val fake = FakeLlm(listOf({ VALID_RESPONSE }))

        val result = newUseCase(ref, fake).analyze(ref.novelId, ref.chapterId)

        assertEquals(AnalyzeStatus.Done, result.status)
        assertEquals(1.0, result.coverage ?: -1.0, 1e-9)
        assertEquals(1, result.segmentCount)
        assertEquals(0, result.unknownSpeakers)
        assertFalse(result.repaired)
        assertNull(result.warning)

        val chapter = requireNotNull(fixture.chapters.getChapter(ref.chapterId))
        assertEquals("done", chapter.parseStatus)
        assertNull(chapter.parseError)
        assertNull(chapter.parseWarn)
        assertEquals(1, chapter.segCount)
        assertEquals(0, chapter.segDone)

        val segments = fixture.segments.list(ref.chapterId)
        assertEquals(1, segments.size)
        assertEquals("dialogue", segments.single().type)
        assertEquals("role_0001", segments.single().roleId)
        assertEquals("happy", segments.single().emotion)
        assertEquals("你好。", segments.single().text)

        val characters = fixture.characters.list(ref.novelId)
        assertEquals(1, characters.size)
        assertEquals("韩立", characters.single().name)
        assertEquals("role_0001", characters.single().roleId)
        assertEquals("male_lead_steady", characters.single().category)

        assertEquals(1, fake.prompts.size)
        assertTrue(fake.prompts.single().contains(rawText))
        assertTrue(fake.prompts.single().contains("（角色表为空）"))
        assertTrue(fake.prompts.single().contains("emotions=neutral,happy,sad"))
    }

    // -------------------------------------------------------------- 降级

    @Test
    fun invalid_json_degrades_via_single_narration_and_records_bad_json_warn() = runTest {
        val rawText = "第一章正文。"
        val ref = createChapter(rawText)
        val fake = FakeLlm(listOf({ "这不是 JSON" }))

        val result = newUseCase(ref, fake).analyze(ref.novelId, ref.chapterId)

        assertEquals(AnalyzeStatus.Degraded, result.status)
        assertEquals(PipelineError.BAD_JSON, result.errorCode)
        assertEquals(1.0, result.coverage ?: -1.0, 1e-9)
        assertNotNull(result.warning)
        assertTrue(requireNotNull(result.warning).contains("BAD_JSON"))

        val chapter = requireNotNull(fixture.chapters.getChapter(ref.chapterId))
        assertEquals("done", chapter.parseStatus)
        assertNull(chapter.parseError)
        assertTrue(requireNotNull(chapter.parseWarn).contains("BAD_JSON"))

        val segments = fixture.segments.list(ref.chapterId)
        assertEquals(1, segments.size)
        assertEquals("narration", segments.single().type)
        assertEquals("narrator", segments.single().roleId)
        assertEquals(rawText, segments.single().text)
    }

    @Test
    fun truncated_marks_parse_error_and_writes_no_segments() = runTest {
        val ref = createChapter("韩立说道：“你好。”")
        val fake = FakeLlm(listOf({ throw PipelineError.Truncated() }))

        val result = newUseCase(ref, fake).analyze(ref.novelId, ref.chapterId)

        assertEquals(AnalyzeStatus.Failed, result.status)
        assertEquals(PipelineError.TRUNCATED, result.errorCode)
        assertTrue(requireNotNull(result.error).contains("截断"))
        assertTrue(requireNotNull(result.error).contains("过长"))

        val chapter = requireNotNull(fixture.chapters.getChapter(ref.chapterId))
        assertEquals("error", chapter.parseStatus)
        assertTrue(requireNotNull(chapter.parseError).contains("截断"))
        assertEquals(0, chapter.segCount)
        assertTrue(fixture.segments.list(ref.chapterId).isEmpty())

        assertEquals(1, fake.prompts.size)
    }

    // ------------------------------------------------------------ 角色映射

    @Test
    fun unknown_speaker_is_downgraded_to_narration() = runTest {
        val ref = createChapter("你好。")
        val response = """
            {
              "new_characters": [],
              "segments": [
                {"type": "dialogue", "speaker": "神秘人", "emotion": "neutral", "text": "你好。"}
              ]
            }
        """.trimIndent()
        val fake = FakeLlm(listOf({ response }))

        val result = newUseCase(ref, fake).analyze(ref.novelId, ref.chapterId)

        assertEquals(AnalyzeStatus.Done, result.status)
        assertEquals(1, result.unknownSpeakers)
        assertNotNull(result.warning)
        assertTrue(requireNotNull(result.warning).contains("未识别说话人 1 段"))

        val segment = fixture.segments.list(ref.chapterId).single()
        assertEquals("narration", segment.type)
        assertEquals("narrator", segment.roleId)
        assertEquals("你好。", segment.text)
    }

    @Test
    fun excluded_characters_are_not_registered() = runTest {
        val ref = createChapter("韩立说道：“你好。”")
        val response = """
            {
              "new_characters": [
                {"name": "韩立", "gender": "male", "voice": "male_lead_steady"},
                {"name": "阿甲", "gender": "male", "voice": "male_brother"}
              ],
              "segments": [
                {"type": "dialogue", "speaker": "韩立", "emotion": "neutral", "text": "你好。"}
              ]
            }
        """.trimIndent()
        val fake = FakeLlm(listOf({ response }))

        val result = newUseCase(ref, fake)
            .analyze(ref.novelId, ref.chapterId, excluded = setOf("阿甲"))

        assertEquals(AnalyzeStatus.Done, result.status)
        assertEquals(listOf("韩立"), fixture.characters.list(ref.novelId).map { it.name })
        assertEquals("role_0001", fixture.segments.list(ref.chapterId).single().roleId)
    }

    // -------------------------------------------------------- 覆盖率修复

    @Test
    fun low_coverage_triggers_one_repair_call_and_keeps_the_better_version() = runTest {
        val rawText = "第一句话。第二句话。第三句话。"
        val ref = createChapter(rawText)
        val first = """
            {
              "new_characters": [],
              "segments": [
                {"type": "narration", "speaker": "narrator", "emotion": "neutral", "text": "第一句话。"},
                {"type": "narration", "speaker": "narrator", "emotion": "neutral", "text": "第三句话。"}
              ]
            }
        """.trimIndent()
        val repaired = """
            {
              "new_characters": [],
              "segments": [
                {"type": "narration", "speaker": "narrator", "emotion": "neutral", "text": "第一句话。"},
                {"type": "narration", "speaker": "narrator", "emotion": "neutral", "text": "第二句话。"},
                {"type": "narration", "speaker": "narrator", "emotion": "neutral", "text": "第三句话。"}
              ]
            }
        """.trimIndent()
        val fake = FakeLlm(listOf({ first }, { repaired }))

        val result = newUseCase(ref, fake).analyze(ref.novelId, ref.chapterId)

        assertEquals(2, fake.prompts.size)
        assertTrue(fake.prompts[1].contains("REPAIR"))
        assertTrue(fake.prompts[1].contains("第二句话。"))

        assertEquals(AnalyzeStatus.Done, result.status)
        assertTrue(result.repaired)
        assertEquals(1.0, result.coverage ?: -1.0, 1e-9)
        assertTrue(requireNotNull(result.warning).contains("修复重试"))
        assertEquals(
            rawText,
            fixture.segments.list(ref.chapterId).joinToString("") { it.text },
        )
    }

    @Test
    fun invalid_repair_response_keeps_the_first_version() = runTest {
        val rawText = "第一句话。第二句话。第三句话。"
        val ref = createChapter(rawText)
        val first = """
            {
              "new_characters": [],
              "segments": [
                {"type": "narration", "speaker": "narrator", "emotion": "neutral", "text": "第一句话。"},
                {"type": "narration", "speaker": "narrator", "emotion": "neutral", "text": "第三句话。"}
              ]
            }
        """.trimIndent()
        val fake = FakeLlm(listOf({ first }, { "还是不是 JSON" }))

        val result = newUseCase(ref, fake).analyze(ref.novelId, ref.chapterId)

        assertEquals(2, fake.prompts.size)
        assertEquals(AnalyzeStatus.Done, result.status)
        assertFalse(result.repaired)
        assertTrue(requireNotNull(result.warning).contains("覆盖率"))
        assertTrue(requireNotNull(result.warning).contains("保留首次结果"))
        assertEquals(
            "第一句话。第三句话。",
            fixture.segments.list(ref.chapterId).joinToString("") { it.text },
        )
    }

    // ------------------------------------------------------------ 增量/重跑

    @Test
    fun done_chapter_is_skipped_unless_force_is_set() = runTest {
        val ref = createChapter("韩立说道：“你好。”")
        val fake = FakeLlm(listOf({ VALID_RESPONSE }, { VALID_RESPONSE }))
        val useCase = newUseCase(ref, fake)

        assertEquals(AnalyzeStatus.Done, useCase.analyze(ref.novelId, ref.chapterId).status)

        val skipped = useCase.analyze(ref.novelId, ref.chapterId)
        assertEquals(AnalyzeStatus.Skipped, skipped.status)
        assertEquals(1, skipped.segmentCount)
        assertEquals(1, fake.prompts.size)

        val rerun = useCase.analyze(ref.novelId, ref.chapterId, force = true)
        assertEquals(AnalyzeStatus.Done, rerun.status)
        assertEquals(2, fake.prompts.size)
        assertTrue("强制重跑应带上已注册角色", fake.prompts[1].contains("韩立(role_0001)"))
        assertEquals(
            "done",
            requireNotNull(fixture.chapters.getChapter(ref.chapterId)).parseStatus,
        )
    }

    // -------------------------------------------------------- 长章节守卫（P2-19）

    @Test
    fun long_chapter_over_limit_is_guarded_without_llm_call() = runTest {
        val rawText = longText(AnalyzeChapterUseCase.MAX_SINGLE_PASS_CHARS + 1)
        val ref = createChapter(rawText)
        val fake = FakeLlm(emptyList())

        val result = newUseCase(ref, fake).analyze(ref.novelId, ref.chapterId)

        assertEquals(AnalyzeStatus.TooLong, result.status)
        assertEquals(AnalyzeChapterUseCase.ERROR_CHAPTER_TOO_LONG, result.errorCode)
        assertNull("TooLong 是待确认的守卫，不是失败", result.error)
        assertNull(result.coverage)
        assertFalse(result.succeeded)
        assertTrue("守卫阶段不允许调用 LLM", fake.prompts.isEmpty())

        val warn = requireNotNull(result.warning)
        assertTrue("告警要带字数：$warn", warn.contains("12001 字"))
        assertTrue("告警要带上限：$warn", warn.contains("上限 12000 字"))
        assertTrue("告警要给出后续动作：$warn", warn.contains("建议手动分段或等待分块解析"))

        val chapter = requireNotNull(fixture.chapters.getChapter(ref.chapterId))
        assertEquals("pending", chapter.parseStatus)
        assertNull(chapter.parseError)
        assertEquals(warn, chapter.parseWarn)
        assertEquals(0, chapter.segCount)
        assertTrue("守卫阶段不写片段", fixture.segments.list(ref.chapterId).isEmpty())
    }

    @Test
    fun long_chapter_with_allow_flag_calls_llm_and_warns_about_truncation() = runTest {
        val rawText = longText(AnalyzeChapterUseCase.MAX_SINGLE_PASS_CHARS + 1)
        val ref = createChapter(rawText)
        val fake = FakeLlm(listOf({ narrationResponse(rawText) }))

        val result = newUseCase(ref, fake).analyze(
            ref.novelId,
            ref.chapterId,
            allowLongChapter = true,
        )

        assertEquals(AnalyzeStatus.Done, result.status)
        assertEquals(1, fake.prompts.size)

        val warn = requireNotNull(result.warning)
        assertTrue("放行后仍要提示可能截断：$warn", warn.contains("较长"))
        assertTrue("放行后仍要提示可能截断：$warn", warn.contains("截断"))
        assertEquals(warn, requireNotNull(fixture.chapters.getChapter(ref.chapterId)).parseWarn)

        val chapter = requireNotNull(fixture.chapters.getChapter(ref.chapterId))
        assertEquals("done", chapter.parseStatus)
        assertEquals(
            rawText,
            fixture.segments.list(ref.chapterId).joinToString("") { it.text },
        )
    }

    @Test
    fun chapter_exactly_at_limit_is_analyzed_normally() = runTest {
        val rawText = longText(AnalyzeChapterUseCase.MAX_SINGLE_PASS_CHARS)
        val ref = createChapter(rawText)
        val fake = FakeLlm(listOf({ narrationResponse(rawText) }))

        val result = newUseCase(ref, fake).analyze(ref.novelId, ref.chapterId)

        assertEquals(AnalyzeStatus.Done, result.status)
        assertEquals(1, fake.prompts.size)
        assertNull("正好 12000 字不应触发长章节告警", result.warning)
        assertEquals(
            rawText,
            fixture.segments.list(ref.chapterId).joinToString("") { it.text },
        )
    }

    @Test
    fun truncated_long_chapter_still_fails_with_truncation_message() = runTest {
        val rawText = longText(AnalyzeChapterUseCase.MAX_SINGLE_PASS_CHARS + 1)
        val ref = createChapter(rawText)
        val fake = FakeLlm(listOf({ throw PipelineError.Truncated() }))

        val result = newUseCase(ref, fake).analyze(
            ref.novelId,
            ref.chapterId,
            allowLongChapter = true,
        )

        assertEquals(AnalyzeStatus.Failed, result.status)
        assertEquals(PipelineError.TRUNCATED, result.errorCode)
        val message = requireNotNull(result.error)
        assertTrue("截断文案要说明过长：$message", message.contains("过长"))
        assertTrue("截断文案要说明截断：$message", message.contains("截断"))
        assertTrue("截断文案要给出后续动作：$message", message.contains("手动分段"))

        val chapter = requireNotNull(fixture.chapters.getChapter(ref.chapterId))
        assertEquals("error", chapter.parseStatus)
        assertEquals(message, chapter.parseError)
        assertTrue(fixture.segments.list(ref.chapterId).isEmpty())
    }

    // -------------------------------------------------------------- helpers

    private fun newUseCase(ref: ChapterRef, fake: FakeLlm): AnalyzeChapterUseCase =
        AnalyzeChapterUseCase(
            db = fixture.db,
            chapterRepository = fixture.chapters,
            segmentRepository = fixture.segments,
            llmClient = dummyClient,
            registryAdapter = CharacterRegistryAdapter(
                fixture.db,
                fixture.characters,
                pool,
                ref.novelId,
            ),
            voicePool = pool,
            promptRenderer = renderer,
            emotions = "neutral,happy,sad",
            dispatcher = Dispatchers.Unconfined,
            chat = fake::chat,
        )

    private suspend fun createChapter(rawText: String): ChapterRef {
        val novelId = fixture.projects.createProject(
            title = "分析测试",
            sourceName = "analyze.txt",
            sourceRelPath = "imports/analyze.txt",
            chapters = listOf(ChapterInput(title = "第一章", rawText = rawText)),
        )
        val chapterId = fixture.chapters.observeBriefs(novelId).first().single().id
        return ChapterRef(novelId, chapterId)
    }

    private fun readResource(name: String): String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream(name),
    ) { "缺少测试资源 $name" }
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }

    private data class ChapterRef(val novelId: Long, val chapterId: Long)

    /** 按顺序返回预设响应；用完再调会失败，用于断言「最多重试一次」。 */
    private class FakeLlm(responses: List<() -> String>) {

        private val queue = ArrayDeque(responses)

        val prompts = mutableListOf<String>()

        suspend fun chat(prompt: String): String {
            prompts += prompt
            val next = queue.removeFirstOrNull()
                ?: error("LLM 调用次数超过预期：${prompts.size}")
            return next()
        }
    }
}

private val VALID_RESPONSE = """
    {
      "new_characters": [
        {
          "name": "韩立",
          "gender": "male",
          "importance": "protagonist",
          "aliases": ["小立"],
          "note": "主角",
          "voice": "male_lead_steady",
          "alias_of": ""
        }
      ],
      "segments": [
        {"type": "narration", "speaker": "narrator", "emotion": "neutral", "text": "韩立说道："},
        {"type": "dialogue", "speaker": "韩立", "emotion": "happy", "text": "你好。"}
      ]
    }
""".trimIndent()

/**
 * 生成正好 [chars] 个字符的正文（无标点/空白，便于断言清洗前后逐字一致）。
 * 用 24 个不同汉字轮转，避免覆盖率 diff 在重复字符上退化。
 */
private fun longText(chars: Int): String {
    require(chars > 0) { "chars 必须为正数：$chars" }
    val unit = "天地玄黄宇宙洪荒日月盈昃辰宿列张寒来暑往秋收冬藏"
    return buildString(chars + unit.length) {
        while (length < chars) append(unit)
    }.take(chars)
}

/** 一段 narration 覆盖全部原文的合法响应，用于断言「无修复重试」的路径。 */
private fun narrationResponse(text: String): String = """
    {
      "new_characters": [],
      "segments": [
        {"type": "narration", "speaker": "narrator", "emotion": "neutral", "text": "$text"}
      ]
    }
""".trimIndent()
