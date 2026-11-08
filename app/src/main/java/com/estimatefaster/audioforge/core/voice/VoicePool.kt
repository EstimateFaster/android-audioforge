package com.estimatefaster.audioforge.core.voice

import com.estimatefaster.audioforge.core.model.Gender
import com.estimatefaster.audioforge.core.model.Importance
import java.util.Collections
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
data class VoiceCategory(
    @SerialName("voice_id") val voiceId: String,
    val label: String,
    /** 只给 UI 看，绝不喂给 LLM。 */
    val note: String? = null,
    /** 建议语速；仅 `male_brother` 目前有值。 */
    val speed: Double? = null,
)

@Serializable
private data class VoicePoolFile(
    @SerialName("narrator_category") val narratorCategory: String,
    val pool: Map<String, VoiceCategory>,
)

/**
 * 63 个音色类别是锁定契约（执行计划 §8.3）：
 * key 不改名、voice_id 不改值；旁白默认不参与 LLM 选角；note 不进提示词。
 *
 * 所有集合查询都返回只读快照，调用方无法修改内部状态。
 */
class VoicePool private constructor(
    val narratorCategory: String,
    categories: Map<String, VoiceCategory>,
) {

    /** 只读快照；保留 JSON 中的插入顺序。 */
    private val byCategory: Map<String, VoiceCategory> =
        Collections.unmodifiableMap(LinkedHashMap(categories))

    private val allCategories: List<VoiceCategory> =
        Collections.unmodifiableList(ArrayList(byCategory.values))

    val size: Int get() = byCategory.size

    val keys: Set<String> get() = byCategory.keys

    fun get(category: String): VoiceCategory? = byCategory[category]

    fun valid(category: String?): Boolean = category != null && byCategory.containsKey(category)

    fun voiceIdFor(category: String): String? = byCategory[category]?.voiceId

    fun speedFor(category: String): Double? = byCategory[category]?.speed

    /** 展示名；未知类别返回 null。 */
    fun labelFor(category: String): String? = byCategory[category]?.label

    /** UI 备注；未知类别或没有 note 返回 null。绝不喂给 LLM。 */
    fun noteFor(category: String): String? = byCategory[category]?.note

    /** 全部类别（key → 类别）的只读快照。 */
    fun categories(): Map<String, VoiceCategory> = byCategory

    /** 全部类别的只读快照，顺序与 [keys] 一致。 */
    fun all(): List<VoiceCategory> = allCategories

    /** `- key: label`，不含 note；默认排除旁白。 */
    fun poolForLlm(includeNarrator: Boolean = false): String =
        byCategory.entries
            .asSequence()
            .filter { includeNarrator || it.key != narratorCategory }
            .joinToString("\n") { (key, value) -> "- $key: ${value.label}" }

    /** 与 Python 一致的兜底映射；Unknown 按男性处理。 */
    fun fallbackCategory(gender: Gender, importance: Importance): String = when (gender) {
        Gender.Female -> when (importance) {
            Importance.Protagonist -> "female_lead_gentle"
            Importance.Supporting -> "female_young"
            Importance.Minor -> "female_mature"
        }

        Gender.Male, Gender.Unknown -> when (importance) {
            Importance.Protagonist -> "male_lead_steady"
            Importance.Supporting -> "male_gentle_young"
            Importance.Minor -> "male_young_support"
        }
    }

    companion object {
        /** 锁定契约：类别数不得少于 63（执行计划 §8.3）。 */
        const val MIN_CATEGORIES: Int = 63

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * 解析 `voice_pool.json` 并校验契约：
         * 类别数 ≥ [MIN_CATEGORIES]、`narrator_category` 存在、每个 `voice_id` 非空。
         *
         * @throws IllegalArgumentException 校验失败；错误信息标明具体类别。
         */
        fun parse(rawJson: String): VoicePool {
            val file = try {
                json.decodeFromString<VoicePoolFile>(rawJson)
            } catch (e: SerializationException) {
                throw IllegalArgumentException("音色池 JSON 解析失败：${e.message}", e)
            }

            require(file.pool.size >= MIN_CATEGORIES) {
                "音色池类别数不足：实际 ${file.pool.size}，要求至少 $MIN_CATEGORIES"
            }
            require(file.pool.containsKey(file.narratorCategory)) {
                "音色池缺少 narrator_category 指向的类别：${file.narratorCategory}"
            }
            val blankVoiceIdKeys = file.pool.filterValues { it.voiceId.isBlank() }.keys.sorted()
            require(blankVoiceIdKeys.isEmpty()) {
                "音色池存在空 voice_id 的类别：${blankVoiceIdKeys.joinToString()}"
            }
            return VoicePool(file.narratorCategory, file.pool)
        }
    }
}
