package com.estimatefaster.audioforge

import com.estimatefaster.audioforge.core.model.Emotion
import com.estimatefaster.audioforge.core.model.NARRATOR_ROLE_ID
import com.estimatefaster.audioforge.core.model.ScriptSegment
import com.estimatefaster.audioforge.core.model.SegmentType
import com.estimatefaster.audioforge.core.voice.VoicePool
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * P1-12：Golden fixtures 与测试脚手架的公共入口。
 *
 * 所有 fixture 都从 test classpath 读取（`app/src/test/resources`），不依赖
 * Android Context、Robolectric 或网络。测试资源命名约定：
 * - 原始/共享 fixture：`porting-vectors.json`、`voice-pool.json`、`sample_novel.txt`
 * - 冻结的 golden 快照：`golden/` 下的 JSON 文件
 */
object TestFixtures {

    const val GOLDEN_DIR = "golden/"

    /** 对话 fixture 没有 speaker 时的稳定占位角色。 */
    const val DEFAULT_DIALOGUE_SPEAKER = "role_0001"

    val json: Json = Json { ignoreUnknownKeys = true }

    /** 读 test classpath 下的 UTF-8 文本资源；缺失时给出明确错误。 */
    fun readText(resource: String): String {
        val stream = TestFixtures::class.java.classLoader?.getResourceAsStream(resource)
            ?: TestFixtures::class.java.getResourceAsStream("/$resource")
            ?: error("缺少测试资源：$resource")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    fun readJson(resource: String): JsonObject =
        json.parseToJsonElement(readText(resource)).jsonObject

    fun goldenText(file: String): String = readText(GOLDEN_DIR + file)

    fun goldenJson(file: String): JsonObject = readJson(GOLDEN_DIR + file)

    fun goldenArray(file: String, key: String): JsonArray =
        goldenJson(file).getValue(key).jsonArray

    /** 读取 golden 音色池（默认）或任意 classpath 音色池文件。 */
    fun loadVoicePool(resource: String = GOLDEN_DIR + "voice_pool.json"): VoicePool =
        VoicePool.parse(readText(resource))

    /** 构造带默认 speaker/emotion 的片段，避免各测试重复写四参数构造。 */
    fun segment(
        type: SegmentType,
        text: String,
        speaker: String = if (type == SegmentType.Dialogue) {
            DEFAULT_DIALOGUE_SPEAKER
        } else {
            NARRATOR_ROLE_ID
        },
        emotion: Emotion = Emotion.Neutral,
    ): ScriptSegment = ScriptSegment(type, speaker, emotion, text)

    fun narration(
        text: String,
        speaker: String = NARRATOR_ROLE_ID,
        emotion: Emotion = Emotion.Neutral,
    ): ScriptSegment = ScriptSegment(SegmentType.Narration, speaker, emotion, text)

    fun dialogue(
        text: String,
        speaker: String = DEFAULT_DIALOGUE_SPEAKER,
        emotion: Emotion = Emotion.Neutral,
    ): ScriptSegment = ScriptSegment(SegmentType.Dialogue, speaker, emotion, text)

    /** 把 fixture 里的 `segments` 数组解析成领域模型。 */
    fun segments(array: JsonArray): List<ScriptSegment> = array.map { element ->
        val item = element.jsonObject
        val type = SegmentType.fromWire(item.getValue("type").jsonPrimitive.content)
        val speaker = item["speaker"]?.jsonPrimitive?.contentOrNull
            ?: if (type == SegmentType.Dialogue) DEFAULT_DIALOGUE_SPEAKER else NARRATOR_ROLE_ID
        val emotion = item["emotion"]?.jsonPrimitive?.contentOrNull
            ?.let { Emotion.fromWire(it) }
            ?: Emotion.Neutral
        segment(type, item.getValue("text").jsonPrimitive.content, speaker, emotion)
    }

    fun strings(array: JsonArray): List<String> = array.map { it.jsonPrimitive.content }

    /** 章节 golden 用 SHA-256 锁定完整正文，避免只校验长度。 */
    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xFF
                append(HEX_DIGITS[value ushr 4])
                append(HEX_DIGITS[value and 0x0F])
            }
        }
    }

    private const val HEX_DIGITS = "0123456789abcdef"
}
