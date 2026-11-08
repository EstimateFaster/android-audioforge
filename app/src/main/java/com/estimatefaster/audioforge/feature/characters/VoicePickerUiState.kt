package com.estimatefaster.audioforge.feature.characters

import com.estimatefaster.audioforge.core.voice.VoicePool

/** 音色池当前只有中文音色；保留 locale 字段方便以后按语言分组。 */
const val DEFAULT_VOICE_LOCALE: String = "zh-CN"

/**
 * 音色按“语言 + 性别”分组展示（执行计划 §12.4 S09）。
 *
 * VoicePool 的锁定契约里没有性别字段，因此从稳定 category key 的前缀推导：
 * `narrator_*` → 旁白、`male_*` → 男声、`female_*` → 女声，其余归到 [Other]。
 * key 是 63 类锁定契约，比 label 里的方括号更可靠。
 */
enum class VoiceGenderGroup {
    Narrator,
    Male,
    Female,
    Other,
}

/**
 * 一个音色类别的不可变投影。
 *
 * [category] 是 VoicePool 的稳定 key，也是落库的 `characters.category`；
 * [voiceId] 是 Fish Audio 的 voice id。两者都不是展示文案，不能翻译。
 */
data class VoiceCategoryUi(
    val category: String,
    val label: String,
    val voiceId: String,
    val note: String? = null,
    val speed: Double? = null,
    val locale: String = DEFAULT_VOICE_LOCALE,
    val gender: VoiceGenderGroup = voiceGenderGroupFor(category),
) {

    /** label 为空时退回 category，避免列表出现一整行空白。 */
    val displayLabel: String
        get() = label.trim().ifEmpty { category.trim() }

    val displayVoiceId: String
        get() = voiceId.trim()

    /** 搜索同时匹配类别 key、label、voice id、语言和 note；大小写不敏感。 */
    fun matches(query: String): Boolean {
        val normalized = query.trim()
        if (normalized.isEmpty()) return true
        return category.contains(normalized, ignoreCase = true) ||
            label.contains(normalized, ignoreCase = true) ||
            voiceId.contains(normalized, ignoreCase = true) ||
            locale.contains(normalized, ignoreCase = true) ||
            note.orEmpty().contains(normalized, ignoreCase = true)
    }
}

/** 一个分组标题下的音色；分组顺序按音色池首次出现的顺序。 */
data class VoiceSectionUi(
    val locale: String,
    val gender: VoiceGenderGroup,
    val voices: List<VoiceCategoryUi>,
) {
    val key: String
        get() = "$locale:${gender.name}"
}

/**
 * 音色选择 Sheet 的不可变状态。
 *
 * [currentCategory] / [currentVoiceId] 是打开 Sheet 时数据库里的值，
 * [selectedCategory] 是用户尚未保存的草稿。保存成功后 ViewModel 会把
 * `current*` 同步为新值，因此 Sheet 保持打开时也不会再是“有修改”状态。
 *
 * [locked] 只影响保存流程（二次确认），不阻止浏览/搜索。
 */
data class VoicePickerUiState(
    val roleId: String,
    val roleName: String,
    val currentCategory: String,
    val currentVoiceId: String,
    val locked: Boolean,
    val voices: List<VoiceCategoryUi> = emptyList(),
    val query: String = "",
    val genderFilter: VoiceGenderGroup? = null,
    val selectedCategory: String? = null,
    val saving: Boolean = false,
    val saveError: String? = null,
    val pendingLockedConfirm: Boolean = false,
    val assignedCategory: String? = null,
) {

    /** 去掉首尾空白后的搜索词；空白搜索不参与过滤。 */
    val normalizedQuery: String
        get() = query.trim()

    /** 搜索 + 档位过滤后的音色；顺序与 [voices] 一致。 */
    val visibleVoices: List<VoiceCategoryUi> =
        filterVoiceCategories(voices, normalizedQuery, genderFilter)

    /** 按 locale/性别分组的可见音色；空列表时没有分组。 */
    val visibleSections: List<VoiceSectionUi> = buildVoiceSections(visibleVoices)

    /** 当前音色池里出现的档位，顺序固定为旁白/男声/女声/其他。 */
    val availableGroups: List<VoiceGenderGroup> =
        VoiceGenderGroup.entries.filter { group -> voices.any { it.gender == group } }

    val totalVoiceCount: Int
        get() = voices.size

    val visibleCount: Int
        get() = visibleVoices.size

    val hasActiveFilters: Boolean
        get() = normalizedQuery.isNotEmpty() || genderFilter != null

    val hasNoVoices: Boolean
        get() = voices.isEmpty()

    /** 音色池非空，但当前搜索/档位没有命中。 */
    val isFilteredEmpty: Boolean
        get() = voices.isNotEmpty() && visibleVoices.isEmpty()

    val selectedVoice: VoiceCategoryUi?
        get() {
            val selected = selectedCategory?.trim().orEmpty()
            if (selected.isEmpty()) return null
            return voices.firstOrNull { it.category == selected }
        }

    val currentVoice: VoiceCategoryUi?
        get() {
            val current = currentCategory.trim()
            if (current.isEmpty()) return null
            return voices.firstOrNull { it.category == current }
        }

    /** 当前音色展示名；类别未知时退回 category 原文，UI 再决定是否显示“未分配”。 */
    val currentDisplayName: String
        get() = currentVoice?.displayLabel?.takeIf { it.isNotEmpty() } ?: currentCategory.trim()

    /** 当前 voice id；类别未知时退回数据库里保存的值。 */
    val currentDisplayVoiceId: String
        get() = currentVoice?.displayVoiceId?.takeIf { it.isNotEmpty() } ?: currentVoiceId.trim()

    val hasSelection: Boolean
        get() = selectedVoice != null

    /** 可写入的选择必须有非空 category 与 voiceId（挡住脏数据）。 */
    val hasWritableSelection: Boolean
        get() = selectedVoice?.let { voice ->
            voice.category.isNotBlank() && voice.voiceId.isNotBlank()
        } == true

    /** 选中的就是当前音色：没有写入的必要。 */
    val isSameAsCurrent: Boolean
        get() = selectedVoice?.category == currentCategory.trim()

    /** 保存按钮可用：有可写选择、确有修改、且没有在途保存。 */
    val canSave: Boolean
        get() = hasWritableSelection && !isSameAsCurrent && !saving

    /** locked 角色在保存前必须二次确认。 */
    val requiresLockedConfirm: Boolean
        get() = locked && canSave

    val assigned: Boolean
        get() = assignedCategory != null
}

/**
 * category key → 性别档位。
 *
 * 未知前缀（历史脏数据/未来新增 key）归到 [VoiceGenderGroup.Other]，不抛异常。
 */
fun voiceGenderGroupFor(category: String): VoiceGenderGroup {
    val key = category.trim().lowercase()
    return when {
        key.startsWith("narrator") -> VoiceGenderGroup.Narrator
        key.startsWith("male") -> VoiceGenderGroup.Male
        key.startsWith("female") -> VoiceGenderGroup.Female
        else -> VoiceGenderGroup.Other
    }
}

/**
 * 搜索 + 档位过滤（AND 组合）；纯函数，保持入参顺序。
 *
 * @param query 首尾空白会被忽略；空串表示不按关键词过滤
 * @param genderFilter null 表示全部档位
 */
fun filterVoiceCategories(
    voices: List<VoiceCategoryUi>,
    query: String,
    genderFilter: VoiceGenderGroup? = null,
): List<VoiceCategoryUi> {
    val normalized = query.trim()
    if (normalized.isEmpty() && genderFilter == null) return voices
    return voices.filter { voice ->
        (genderFilter == null || voice.gender == genderFilter) &&
            (normalized.isEmpty() || voice.matches(normalized))
    }
}

/**
 * 按 locale + 性别分组；组顺序按音色首次出现的顺序，组内保持原顺序。
 */
fun buildVoiceSections(voices: List<VoiceCategoryUi>): List<VoiceSectionUi> {
    if (voices.isEmpty()) return emptyList()
    val grouped = LinkedHashMap<String, MutableList<VoiceCategoryUi>>()
    voices.forEach { voice ->
        val locale = voice.locale.trim().ifEmpty { DEFAULT_VOICE_LOCALE }
        grouped.getOrPut("$locale:${voice.gender.name}") { mutableListOf() }.add(voice)
    }
    return grouped.map { (_, group) ->
        val first = group.first()
        VoiceSectionUi(
            locale = first.locale.trim().ifEmpty { DEFAULT_VOICE_LOCALE },
            gender = first.gender,
            voices = group,
        )
    }
}

/**
 * VoicePool → UI 列表。
 *
 * VoicePool 的 `keys` 保留 JSON 插入顺序，因此 63 类的顺序与资产文件一致。
 * 空 label/voiceId 的脏数据不在这里丢弃，交给 [VoiceCategoryUi.displayLabel]
 * 和 UI 兜底；VoicePool.parse 本身已保证 voice_id 非空。
 */
fun voiceCategoriesFrom(pool: VoicePool): List<VoiceCategoryUi> =
    pool.keys.mapNotNull { category ->
        pool.get(category)?.let { voice ->
            VoiceCategoryUi(
                category = category,
                label = voice.label,
                voiceId = voice.voiceId,
                note = voice.note,
                speed = voice.speed,
            )
        }
    }

/**
 * 构造初始状态；当前 category 有效时默认选中它，便于用户看到“当前”勾选。
 *
 * 纯函数，Preview/测试/ViewModel 共用；未知当前类别不会让选中项变成非法值。
 */
fun buildVoicePickerUiState(
    roleId: String,
    roleName: String,
    currentCategory: String,
    currentVoiceId: String,
    locked: Boolean,
    voices: List<VoiceCategoryUi>,
    query: String = "",
    genderFilter: VoiceGenderGroup? = null,
    selectedCategory: String? = currentCategory.trim().takeIf { current ->
        current.isNotEmpty() && voices.any { it.category == current }
    },
): VoicePickerUiState = VoicePickerUiState(
    roleId = roleId.trim(),
    roleName = roleName.trim(),
    currentCategory = currentCategory.trim(),
    currentVoiceId = currentVoiceId.trim(),
    locked = locked,
    voices = voices,
    query = query,
    genderFilter = genderFilter,
    selectedCategory = selectedCategory?.trim()?.takeIf { it.isNotEmpty() },
)
