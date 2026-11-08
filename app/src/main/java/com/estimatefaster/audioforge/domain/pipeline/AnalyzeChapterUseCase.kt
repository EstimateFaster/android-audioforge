package com.estimatefaster.audioforge.domain.pipeline

import androidx.room.withTransaction
import com.estimatefaster.audioforge.core.model.Emotion
import com.estimatefaster.audioforge.core.model.Gender
import com.estimatefaster.audioforge.core.model.Importance
import com.estimatefaster.audioforge.core.model.NARRATOR_ROLE_ID
import com.estimatefaster.audioforge.core.model.NewCharacter
import com.estimatefaster.audioforge.core.model.ParseStatus
import com.estimatefaster.audioforge.core.model.PipelineError
import com.estimatefaster.audioforge.core.model.ScriptSegment
import com.estimatefaster.audioforge.core.model.SegmentStatus
import com.estimatefaster.audioforge.core.model.SegmentType
import com.estimatefaster.audioforge.core.network.LlmClient
import com.estimatefaster.audioforge.core.script.CoverageChecker
import com.estimatefaster.audioforge.core.script.ScriptSanitizer
import com.estimatefaster.audioforge.core.voice.PromptRenderer
import com.estimatefaster.audioforge.core.voice.VoicePool
import com.estimatefaster.audioforge.data.db.AppDatabase
import com.estimatefaster.audioforge.data.db.ChapterEntity
import com.estimatefaster.audioforge.data.db.SegmentEntity
import com.estimatefaster.audioforge.data.repository.ChapterRepository
import com.estimatefaster.audioforge.data.repository.SegmentRepository
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * 解析单章为朗读剧本（执行计划 §8.7；长章节守卫见 §8.10）。
 *
 * 流程：
 * 1. `done && !force` → [AnalyzeStatus.Skipped]；否则 `parse_status=running`；
 * 2. 刷新角色快照，按 `registry / emotions / voice_pool / text` 渲染 script 提示词；
 * 3. 调用 LLM。`finish_reason=length` → `parse_status=error` + 明确文案，返回
 *    [AnalyzeStatus.Failed]，**不**降级为旁白；其它失败同样返回 Failed（网络错误
 *    已由 [LlmClient] 自身退避重试）；
 * 4. 剧本 JSON 解析失败/结构非法 → 整章旁白降级，返回 [AnalyzeStatus.Degraded]；
 * 5. `new_characters` 走 [CharacterRegistryAdapter.registerAll]（单事务）；
 * 6. dialogue 的 speaker 映射为 roleId，未识别 → narration + narrator；
 * 7. [ScriptSanitizer] 清洗（默认 120 字/段）；
 * 8. 覆盖率低于 [minCoverage] 且存在可修复缺失 → 追加 `repair` 提示词重试一次，
 *    取覆盖率更高的一版；第二次 JSON 非法则保留第一版；
 * 9. 单事务落库片段与章节状态/告警/计数。
 *
 * 长章节守卫（> [MAX_SINGLE_PASS_CHARS]，§8.10）：默认**不调用 LLM**，返回
 * [AnalyzeStatus.TooLong]，把字数/上限/建议动作写进 `parseWarn`，并把
 * `parse_status` 置回 `pending`——表示「等调用方确认后可以继续」，而不是失败。
 * 调用方确认后带 `allowLongChapter=true` 重跑即可继续单次解析，此时只把
 * 「可能截断」保留在告警里。v1 不做分块（v1.1 / P2.9 再实现）。
 *
 * 测试注入：[chat] 允许用 `suspend (String) -> String` 替换真实
 * [LlmClient.chat]（默认实现就是 `llmClient.chat(prompt)`），其余依赖
 * （sanitizer / coverage / 角色注册 / Room）都是真实实现。
 *
 * 注意：`registryAdapter` 构造时已绑定 novelId；[analyze] 传入的 novelId 只用于
 * 校验章节归属，避免把一章写到另一本小说的角色表上。
 */
class AnalyzeChapterUseCase(
    private val db: AppDatabase,
    private val chapterRepository: ChapterRepository,
    private val segmentRepository: SegmentRepository,
    llmClient: LlmClient,
    private val registryAdapter: CharacterRegistryAdapter,
    private val voicePool: VoicePool,
    private val promptRenderer: PromptRenderer,
    private val emotions: String = Emotion.entries.joinToString(",") { it.wire },
    private val sanitizer: ScriptSanitizer = ScriptSanitizer(),
    private val coverageChecker: CoverageChecker = CoverageChecker(sanitizer),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val segmentMaxChars: Int = ScriptSanitizer.DEFAULT_SEGMENT_MAX_CHARS,
    private val minCoverage: Double = CoverageChecker.DEFAULT_MIN_COVERAGE,
    private val chat: suspend (String) -> String = { prompt -> llmClient.chat(prompt) },
) {

    /**
     * 解析一章。
     *
     * @param novelId 章节所属项目；与 `chapter.novelId` 不一致时抛
     *   [IllegalArgumentException]（编程错误，不写库）。
     * @param chapterId 要解析的章节。
     * @param force `true` 时忽略已有的 `parse_status=done` 重新解析。
     * @param excluded 设置里的「排除角色」名字/别名；命中者不注册，台词降级旁白。
     * @param allowLongChapter `true` 时放行超过 [MAX_SINGLE_PASS_CHARS] 的章节
     *   （用户在 UI 确认「章节较长，可能截断」后传入）。默认 `false` 时只做守卫，
     *   不调用 LLM、不写片段。
     */
    suspend fun analyze(
        novelId: Long,
        chapterId: Long,
        force: Boolean = false,
        excluded: Set<String> = emptySet(),
        allowLongChapter: Boolean = false,
    ): AnalyzeResult = withContext(dispatcher) {
        val chapter = chapterRepository.getChapter(chapterId)
            ?: return@withContext AnalyzeResult(
                status = AnalyzeStatus.Failed,
                chapterId = chapterId,
                error = "章节不存在：$chapterId",
                errorCode = ERROR_CHAPTER_NOT_FOUND,
            )

        require(chapter.novelId == novelId) {
            "章节 $chapterId 属于项目 ${chapter.novelId}，与传入的 $novelId 不一致"
        }

        if (chapter.parseStatus == ParseStatus.Done.wire && !force) {
            return@withContext AnalyzeResult(
                status = AnalyzeStatus.Skipped,
                chapterId = chapterId,
                segmentCount = chapter.segCount,
                warning = chapter.parseWarn,
            )
        }

        // §8.10 长章节守卫：先看原文长度，再决定是否进入 running / 调用 LLM。
        if (chapter.rawText.length > MAX_SINGLE_PASS_CHARS && !allowLongChapter) {
            return@withContext blockedByLength(chapter)
        }

        chapterRepository.updateParseStatus(chapterId, ParseStatus.Running.wire, null)

        try {
            analyzeRunning(chapter, excluded)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            markParseError(chapterId, e.message)
            throw e
        }
    }

    // -------------------------------------------------------------- 主流程

    private suspend fun analyzeRunning(
        chapter: ChapterEntity,
        excluded: Set<String>,
    ): AnalyzeResult {
        val chapterId = chapter.id
        val rawText = chapter.rawText
        val longChapter = rawText.length > MAX_SINGLE_PASS_CHARS

        registryAdapter.refresh()
        val prompt = promptRenderer.script(
            registry = registryAdapter.registry.promptBlock(),
            emotions = emotions,
            voicePool = voicePool.poolForLlm(),
            text = rawText,
        )

        val firstRaw = try {
            chat(prompt)
        } catch (e: PipelineError) {
            if (e is PipelineError.BadJson) {
                // 空 content / 响应体不是 JSON：与「剧本 JSON 非法」同样处理。
                return completeDegraded(
                    chapterId = chapterId,
                    rawText = rawText,
                    longChapter = longChapter,
                    degradedWarning = BAD_JSON_WARNING,
                    errorCode = PipelineError.BAD_JSON,
                )
            }
            return failChapter(chapterId, e)
        }

        val first = parseScript(firstRaw)
            ?: return completeDegraded(
                chapterId = chapterId,
                rawText = rawText,
                longChapter = longChapter,
                degradedWarning = BAD_JSON_WARNING,
                errorCode = PipelineError.BAD_JSON,
            )

        val firstMapped = materialize(first, excluded)
        if (firstMapped.segments.isEmpty()) {
            return completeDegraded(
                chapterId = chapterId,
                rawText = rawText,
                longChapter = longChapter,
                degradedWarning = EMPTY_SCRIPT_WARNING,
                errorCode = ERROR_EMPTY_SCRIPT,
            )
        }

        val firstCoverage = coverageChecker.check(rawText, firstMapped.segments)
        var chosen = firstMapped
        var chosenCoverage = firstCoverage
        var repaired = false
        var repairNote: String? = null

        if (firstCoverage.coverage < minCoverage) {
            val missing = firstCoverage.missing.take(CoverageChecker.MAX_REPAIR_FRAGMENTS)
            if (missing.isNotEmpty()) {
                val repairRaw = try {
                    chat(promptRenderer.repair(prompt, missing))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }

                if (repairRaw == null) {
                    repairNote = "修复重试失败，已保留首次结果"
                } else {
                    val second = parseScript(repairRaw)
                    if (second == null) {
                        repairNote = "修复重试输出不是合法 JSON，已保留首次结果"
                    } else {
                        val secondMapped = materialize(second, excluded)
                        val secondCoverage = coverageChecker.check(rawText, secondMapped.segments)
                        if (secondMapped.segments.isNotEmpty() &&
                            secondCoverage.coverage > firstCoverage.coverage
                        ) {
                            chosen = secondMapped
                            chosenCoverage = secondCoverage
                            repaired = true
                            repairNote = "修复重试将覆盖率从 " +
                                "${percent(firstCoverage.coverage)} 提升至 " +
                                percent(secondCoverage.coverage)
                        } else {
                            repairNote = "修复重试未提升覆盖率，已保留首次结果"
                        }
                    }
                }
            }
        }

        val warn = buildWarn(
            degradedWarning = null,
            coverage = chosenCoverage.coverage,
            unknownSpeakers = chosen.unknownSpeakers,
            rawLength = rawText.length,
            longChapter = longChapter,
            repairNote = repairNote,
        )
        persist(chapterId, chosen.segments, warn)

        return AnalyzeResult(
            status = AnalyzeStatus.Done,
            chapterId = chapterId,
            coverage = chosenCoverage.coverage,
            segmentCount = chosen.segments.size,
            warning = warn,
            unknownSpeakers = chosen.unknownSpeakers,
            repaired = repaired,
        )
    }

    /** 注册新角色 → 映射 speaker → 清洗。 */
    private suspend fun materialize(
        parsed: ParsedScript,
        excluded: Set<String>,
    ): MappedScript {
        if (parsed.newCharacters.isNotEmpty()) {
            registryAdapter.registerAll(parsed.newCharacters, excluded)
        }
        val registry = registryAdapter.registry
        var unknown = 0
        val mapped = ArrayList<ScriptSegment>(parsed.lines.size)

        for (line in parsed.lines) {
            when (line.type) {
                SegmentType.Dialogue -> {
                    val roleId = registry.roleIdFor(line.speaker)
                    if (roleId == null) {
                        // §8.7：说话人未识别 → 这段台词按旁白处理，不丢文本。
                        unknown++
                        mapped += ScriptSegment(
                            type = SegmentType.Narration,
                            speaker = NARRATOR_ROLE_ID,
                            emotion = line.emotion,
                            text = line.text,
                        )
                    } else {
                        mapped += ScriptSegment(
                            type = SegmentType.Dialogue,
                            speaker = roleId,
                            emotion = line.emotion,
                            text = line.text,
                        )
                    }
                }

                SegmentType.Narration -> mapped += ScriptSegment(
                    type = SegmentType.Narration,
                    speaker = NARRATOR_ROLE_ID,
                    emotion = line.emotion,
                    text = line.text,
                )
            }
        }

        return MappedScript(
            segments = sanitizer.sanitize(mapped, segmentMaxChars),
            unknownSpeakers = unknown,
        )
    }

    /** 整章旁白降级：先构造单个 narration，再走同一套清洗/覆盖率/落库。 */
    private suspend fun completeDegraded(
        chapterId: Long,
        rawText: String,
        longChapter: Boolean,
        degradedWarning: String,
        errorCode: String,
    ): AnalyzeResult {
        val fallback = listOf(
            ScriptSegment(
                type = SegmentType.Narration,
                speaker = NARRATOR_ROLE_ID,
                emotion = Emotion.Neutral,
                text = rawText,
            ),
        )
        val segments = sanitizer.sanitize(fallback, segmentMaxChars)
        val coverage = coverageChecker.check(rawText, segments)
        val warn = buildWarn(
            degradedWarning = degradedWarning,
            coverage = coverage.coverage,
            unknownSpeakers = 0,
            rawLength = rawText.length,
            longChapter = longChapter,
            repairNote = null,
        )
        persist(chapterId, segments, warn)

        return AnalyzeResult(
            status = AnalyzeStatus.Degraded,
            chapterId = chapterId,
            coverage = coverage.coverage,
            segmentCount = segments.size,
            warning = warn,
            errorCode = errorCode,
        )
    }

    /**
     * §8.10 长章节守卫：只记录原因，不调用 LLM、不写片段。
     *
     * `parse_status` 置为 `pending`（而不是 `error`）：章节本身没坏，只是等调用方
     * 确认「继续 / 取消」。用户确认后带 `allowLongChapter=true` 重跑本方法即可，
     * 因为 `pending` 不会被当作已完成跳过。
     */
    private suspend fun blockedByLength(chapter: ChapterEntity): AnalyzeResult {
        val warn = longChapterWarning(chapter.rawText.length)
        db.withTransaction {
            chapterRepository.updateParseStatus(chapter.id, ParseStatus.Pending.wire, null)
            chapterRepository.updateWarn(chapter.id, warn)
        }
        return AnalyzeResult(
            status = AnalyzeStatus.TooLong,
            chapterId = chapter.id,
            segmentCount = chapter.segCount,
            warning = warn,
            errorCode = ERROR_CHAPTER_TOO_LONG,
        )
    }

    /** 失败：只改章节状态，不动已有片段（重跑时可复用旧结果做对照）。 */
    private suspend fun failChapter(chapterId: Long, error: PipelineError): AnalyzeResult {
        val message = when {
            error is PipelineError.Truncated -> TRUNCATED_MESSAGE
            else -> error.userMessage
        }
        chapterRepository.updateParseStatus(chapterId, ParseStatus.Error.wire, message)
        return AnalyzeResult(
            status = AnalyzeStatus.Failed,
            chapterId = chapterId,
            error = message,
            errorCode = error.code,
        )
    }

    private suspend fun markParseError(chapterId: Long, message: String?) {
        val text = message?.trim()?.takeIf { it.isNotEmpty() } ?: GENERIC_ERROR_MESSAGE
        runCatching {
            chapterRepository.updateParseStatus(chapterId, ParseStatus.Error.wire, text)
        }
    }

    /**
     * 片段 + 章节状态在一个事务里提交。`SegmentRepository.replace` 内部已同步
     * `segCount/segDone`；这里再显式写一次计数，保证调用方看到的是本次结果。
     */
    private suspend fun persist(
        chapterId: Long,
        segments: List<ScriptSegment>,
        warn: String?,
    ) {
        val entities = segments.map { segment ->
            SegmentEntity(
                chapterId = chapterId,
                seq = 0,
                type = segment.type.wire,
                roleId = segment.speaker,
                emotion = segment.emotion.wire,
                text = segment.text,
                status = SegmentStatus.Pending.wire,
            )
        }
        db.withTransaction {
            segmentRepository.replace(chapterId, entities)
            chapterRepository.updateParseStatus(chapterId, ParseStatus.Done.wire, null)
            chapterRepository.updateWarn(chapterId, warn)
            chapterRepository.updateCounters(chapterId, segments.size, 0)
        }
    }

    // -------------------------------------------------------------- JSON

    private fun parseScript(raw: String): ParsedScript? {
        val payload = extractJson(raw) ?: return null
        val dto = try {
            json.decodeFromString(RawScript.serializer(), payload)
        } catch (_: SerializationException) {
            return null
        }

        val rawSegments = dto.segments.orEmpty()
        if (rawSegments.isEmpty()) return null

        val lines = rawSegments.mapNotNull { segment ->
            val text = segment.text?.trim().orEmpty()
            if (text.isEmpty()) {
                null
            } else {
                ParsedLine(
                    type = SegmentType.fromWire(segment.type.orEmpty()),
                    speaker = segment.speaker.orEmpty(),
                    emotion = Emotion.fromWire(segment.emotion.orEmpty()),
                    text = text,
                )
            }
        }
        if (lines.isEmpty()) return null

        val characters = dto.newCharacters.orEmpty().map { rawCharacter ->
            NewCharacter(
                name = rawCharacter.name.orEmpty(),
                gender = Gender.fromWire(rawCharacter.gender.orEmpty()),
                importance = Importance.fromWire(rawCharacter.importance.orEmpty()),
                aliases = rawCharacter.aliases.orEmpty()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() },
                note = rawCharacter.note.orEmpty(),
                voice = rawCharacter.voice.orEmpty(),
                aliasOf = rawCharacter.aliasOf.orEmpty(),
            )
        }
        return ParsedScript(characters, lines)
    }

    /**
     * 去掉 ```json 围栏；模型在 JSON 前后加了解释时，退化为取首尾花括号之间的内容。
     * 返回 `null` 表示连花括号都没有，直接按非法 JSON 降级。
     */
    private fun extractJson(raw: String): String? {
        var text = raw.trim()
        if (text.isEmpty()) return null

        if (text.startsWith("```")) {
            text = text.substring(3).trimStart()
            if (text.startsWith("json", ignoreCase = true)) {
                text = text.substring(4)
            }
            val fenceEnd = text.lastIndexOf("```")
            if (fenceEnd >= 0) text = text.substring(0, fenceEnd)
            text = text.trim()
        }

        if (text.startsWith("{") && text.endsWith("}")) return text

        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else null
    }

    // -------------------------------------------------------------- 文案

    private fun buildWarn(
        degradedWarning: String?,
        coverage: Double,
        unknownSpeakers: Int,
        rawLength: Int,
        longChapter: Boolean,
        repairNote: String?,
    ): String? {
        val parts = mutableListOf<String>()
        if (degradedWarning != null) parts += degradedWarning
        if (longChapter) {
            parts += "章节较长（$rawLength 字），已按单次解析继续，可能输出截断；" +
                "建议手动分段或等待分块解析"
        }
        if (coverage < minCoverage) {
            parts += "覆盖率 ${percent(coverage)}，低于阈值 ${percent(minCoverage)}"
        }
        if (unknownSpeakers > 0) {
            parts += "未识别说话人 $unknownSpeakers 段已降级为旁白"
        }
        if (repairNote != null) parts += repairNote
        return parts.takeIf { it.isNotEmpty() }?.joinToString("；")
    }

    private fun percent(value: Double): String =
        String.format(Locale.ROOT, "%.1f%%", value * 100.0)

    /** 守卫文案：字数、单次上限、后续动作三件事都要给调用方。 */
    private fun longChapterWarning(chars: Int): String =
        "章节较长（$chars 字），超过单次解析上限 $MAX_SINGLE_PASS_CHARS 字，" +
            "可能输出截断；建议手动分段或等待分块解析"

    companion object {
        /**
         * §8.10 / 附录 G：单次解析的正文上限。
         *
         * 超过时默认不调用 LLM（见 [analyze] 的 `allowLongChapter`），返回
         * [AnalyzeStatus.TooLong]；只有调用方确认后才继续单次解析。
         */
        const val MAX_SINGLE_PASS_CHARS: Int = 12_000

        /** P2-11 时期的旧名，等同 [MAX_SINGLE_PASS_CHARS]；新代码请用新名。 */
        @Deprecated("改用 MAX_SINGLE_PASS_CHARS")
        const val LONG_CHAPTER_CHARS: Int = MAX_SINGLE_PASS_CHARS

        /** §8.10 的截断文案；包含「分段」与「分块」两个后续动作。 */
        const val TRUNCATED_MESSAGE: String = "本章过长/输出截断，可手动分段或等待分块解析"

        /** 守卫状态码：正文超过 [MAX_SINGLE_PASS_CHARS] 且未放行（不是失败）。 */
        const val ERROR_CHAPTER_TOO_LONG: String = "CHAPTER_TOO_LONG"

        private const val BAD_JSON_WARNING =
            "BAD_JSON: 模型输出不是合法 JSON，已降级为整章旁白"
        private const val EMPTY_SCRIPT_WARNING =
            "EMPTY_SCRIPT: 清洗后没有可朗读片段，已降级为整章旁白"
        private const val ERROR_CHAPTER_NOT_FOUND = "CHAPTER_NOT_FOUND"
        private const val ERROR_EMPTY_SCRIPT = "EMPTY_SCRIPT"
        private const val GENERIC_ERROR_MESSAGE = "解析失败，请重试"

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
