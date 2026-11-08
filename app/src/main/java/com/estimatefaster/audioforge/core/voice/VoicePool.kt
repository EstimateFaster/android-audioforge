package com.estimatefaster.audioforge.core.voice

import com.estimatefaster.audioforge.core.model.Gender
import com.estimatefaster.audioforge.core.model.Importance
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
 */
class VoicePool private constructor(
    val narratorCategory: String,
    private val categories: Map<String, VoiceCategory>,
) {

    val size: Int get() = categories.size

    val keys: Set<String> get() = categories.keys

    fun get(category: String): VoiceCategory? = categories[category]

    fun valid(category: String?): Boolean = category != null && categories.containsKey(category)

    fun voiceIdFor(category: String): String? = categories[category]?.voiceId

    fun speedFor(category: String): Double? = categories[category]?.speed

    /** `- key: label`，不含 note；默认排除旁白。 */
    fun poolForLlm(includeNarrator: Boolean = false): String =
        categories.entries
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
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(rawJson: String): VoicePool {
            val file = json.decodeFromString<VoicePoolFile>(rawJson)
            require(file.pool.isNotEmpty()) { "音色池为空" }
            require(file.pool.containsKey(file.narratorCategory)) {
                "音色池缺少旁白类别 ${file.narratorCategory}"
            }
            return VoicePool(file.narratorCategory, file.pool)
        }
    }
}
