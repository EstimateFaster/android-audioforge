package com.estimatefaster.audioforge.feature.characters

import com.estimatefaster.audioforge.core.model.Gender
import com.estimatefaster.audioforge.core.model.Importance
import com.estimatefaster.audioforge.data.db.CharacterEntity
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val characterAliasesJson = Json { ignoreUnknownKeys = true }

/**
 * 角色卡片的不可变投影。
 *
 * 映射在 ViewModel 收到 Room Flow 时一次完成：枚举解析、别名 JSON 解码、
 * 音色池 label 查询都在这里做，`LazyColumn` 的 item 内不再查库/解析。
 *
 * [key] 是列表稳定 key：roleId 在同一本书里由数据库唯一索引保证唯一；
 * 缺少 roleId 的脏数据退化为 Room 主键，仍能保证同一列表内稳定。
 * [hasVoice] 只表示数据库里是否已有音色类别；[voiceLabel] 为 null 时
 * 可能只是类别未知，UI 先退回类别 ID，再退回“未分配音色”。
 */
data class CharacterUi(
    val id: Long,
    val roleId: String,
    val name: String,
    val aliases: List<String>,
    val gender: Gender,
    val importance: Importance,
    val category: String,
    val voiceLabel: String?,
    val locked: Boolean,
    val note: String = "",
) {
    val key: String
        get() = if (roleId.isNotEmpty()) roleId else "character:$id"

    val hasVoice: Boolean
        get() = category.isNotEmpty()

    /** 名字为空时退回 roleId，避免卡片标题完全空白。 */
    val displayName: String
        get() = name.ifEmpty { roleId }

    /** 名字或任一别名包含 [query]（大小写不敏感）；[query] 由调用方先 trim。 */
    fun matches(query: String): Boolean =
        name.contains(query, ignoreCase = true) ||
            aliases.any { alias -> alias.contains(query, ignoreCase = true) }

    companion object {
        /**
         * [CharacterEntity] → [CharacterUi]。
         *
         * @param voiceLabelFor 类别 → 音色池展示名；返回 null 表示类别为空或未知
         */
        fun from(
            entity: CharacterEntity,
            voiceLabelFor: (String) -> String? = { null },
        ): CharacterUi {
            val category = entity.category.trim()
            val voiceLabel = if (category.isEmpty()) {
                null
            } else {
                voiceLabelFor(category)?.trim()?.takeIf { it.isNotEmpty() }
            }
            return CharacterUi(
                id = entity.id,
                roleId = entity.roleId.trim(),
                name = entity.name.trim(),
                aliases = decodeCharacterAliases(entity.aliasesJson),
                gender = Gender.fromWire(entity.gender),
                importance = Importance.fromWire(entity.importance),
                category = category,
                voiceLabel = voiceLabel,
                locked = entity.locked,
                note = entity.note.trim(),
            )
        }
    }
}

/**
 * 人物音色页的不可变状态。
 *
 * 搜索/筛选项原样保存，[visibleCharacters] 在构造时一次过滤好；
 * 搜索只做内存过滤，不重新订阅 Room Flow。
 *
 * 计数语义：
 * - [totalCount]：本书全部角色；
 * - [usedCount]：已分配音色类别（`category` 非空）的角色，对应“已用角色数”；
 * - [visibleCount]：当前搜索/筛选命中的角色数。
 */
data class CharactersUiState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val allCharacters: List<CharacterUi> = emptyList(),
    val query: String = "",
    val genderFilter: Gender? = null,
    val importanceFilter: Importance? = null,
) {
    /** 当前搜索/筛选后的角色；顺序与 [allCharacters]（DB id ASC）一致。 */
    val visibleCharacters: List<CharacterUi> =
        filterCharacters(allCharacters, query, genderFilter, importanceFilter)

    val totalCount: Int
        get() = allCharacters.size

    val usedCount: Int
        get() = allCharacters.count { it.hasVoice }

    val visibleCount: Int
        get() = visibleCharacters.size

    val hasActiveFilters: Boolean
        get() = query.isNotBlank() || genderFilter != null || importanceFilter != null

    /** 首屏加载：还没有任何数据、也没有错误。 */
    val isInitialLoading: Boolean
        get() = loading && loadError == null && allCharacters.isEmpty()

    /** 本书还没有角色（未解析或解析结果为空）。 */
    val isEmpty: Boolean
        get() = !loading && loadError == null && allCharacters.isEmpty()

    /** 有角色，但当前搜索/筛选没有命中。 */
    val isFilteredEmpty: Boolean
        get() = !loading && loadError == null && allCharacters.isNotEmpty() && visibleCharacters.isEmpty()

    val hasResults: Boolean
        get() = !loading && loadError == null && visibleCharacters.isNotEmpty()
}

/**
 * 搜索 + 筛选（AND 组合）：
 * - [query] 先 trim；为空时不参与过滤；
 * - 名字或别名包含关键词即命中，大小写不敏感（中文不受影响）；
 * - 性别/重要性为 null 表示“全部”。
 *
 * 纯函数，不依赖 Android/协程；顺序保持入参顺序。
 */
fun filterCharacters(
    characters: List<CharacterUi>,
    query: String,
    genderFilter: Gender? = null,
    importanceFilter: Importance? = null,
): List<CharacterUi> {
    val normalized = query.trim()
    if (normalized.isEmpty() && genderFilter == null && importanceFilter == null) {
        return characters
    }
    return characters.filter { character ->
        (genderFilter == null || character.gender == genderFilter) &&
            (importanceFilter == null || character.importance == importanceFilter) &&
            (normalized.isEmpty() || character.matches(normalized))
    }
}

/**
 * `characters.aliasesJson` → 去空白、去空值、去重的别名列表。
 *
 * 与 [com.estimatefaster.audioforge.data.repository.CharacterRepository] 的容错保持一致：
 * 空白 / 非 JSON / 非字符串数组都退回空列表，一条脏别名不能让整页进入错误态。
 */
fun decodeCharacterAliases(raw: String?): List<String> {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return emptyList()
    val parsed = try {
        characterAliasesJson.decodeFromString(ListSerializer(String.serializer()), text)
    } catch (_: SerializationException) {
        return emptyList()
    } catch (_: IllegalArgumentException) {
        return emptyList()
    }
    return parsed
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()
}

/**
 * 一次数据快照 → UI 状态（纯函数，供 ViewModel/测试/Preview 共用）。
 *
 * @param entities Room 角色实体，顺序即 DB 的 id ASC
 * @param voiceLabelFor 音色池类别 → 展示名
 * @param loading 是否仍在首屏加载
 * @param loadError 加载失败原因（非空时优先展示错误态）
 */
fun buildCharactersUiState(
    entities: List<CharacterEntity>,
    voiceLabelFor: (String) -> String? = { null },
    query: String = "",
    genderFilter: Gender? = null,
    importanceFilter: Importance? = null,
    loading: Boolean = false,
    loadError: String? = null,
): CharactersUiState = CharactersUiState(
    loading = loading,
    loadError = loadError,
    allCharacters = entities.map { entity -> CharacterUi.from(entity, voiceLabelFor) },
    query = query,
    genderFilter = genderFilter,
    importanceFilter = importanceFilter,
)
