package com.estimatefaster.audioforge.core.script

import com.estimatefaster.audioforge.core.model.CharacterInfo
import com.estimatefaster.audioforge.core.model.NARRATOR_ROLE_ID
import com.estimatefaster.audioforge.core.model.NewCharacter
import com.estimatefaster.audioforge.core.model.roleIdFor
import com.estimatefaster.audioforge.core.voice.VoicePool

/**
 * 角色持久化接口。事务由 data 层实现方负责（P2-13 的 Room 实现会把
 * `registerAll` 包在 `withTransaction` 里）；本接口只做单条读写。
 */
interface CharacterStore {
    fun find(nameOrAlias: String): CharacterInfo?
    fun all(): List<CharacterInfo>
    fun insert(character: CharacterInfo)
    fun updateAliases(roleId: String, aliases: List<String>)
    fun allocateRoleId(): String
}

/** 内存实现：P1 测试与不带数据库的预览使用。 */
class InMemoryCharacterStore : CharacterStore {

    private val characters = LinkedHashMap<String, CharacterInfo>()
    private val index = HashMap<String, String>()
    private var nextRoleNumber = 1

    override fun find(nameOrAlias: String): CharacterInfo? =
        index[nameOrAlias]?.let { characters[it] }

    override fun all(): List<CharacterInfo> = characters.values.toList()

    override fun insert(character: CharacterInfo) {
        characters[character.roleId] = character
        index[character.name] = character.roleId
        for (alias in character.aliases) index[alias] = character.roleId
    }

    override fun updateAliases(roleId: String, aliases: List<String>) {
        val current = characters[roleId] ?: return
        characters[roleId] = current.copy(aliases = aliases)
        val stale = index.filterValues { it == roleId }.keys.toList()
        for (key in stale) index.remove(key)
        index[current.name] = roleId
        for (alias in aliases) index[alias] = roleId
    }

    override fun allocateRoleId(): String = roleIdFor(nextRoleNumber++)
}

/**
 * 角色注册（执行计划 §8.6）：
 * - 已存在（name 或 alias）→ 复用 role_id，只合并别名，**绝不改音色**；
 * - `alias_of` → 并入已有角色，不新建；目标不存在时按新角色处理（避免丢台词）；
 * - 新角色按 `role_0001` 递增；合法 LLM 音色优先，否则按性别/重要度兜底；
 * - `excluded` 命中 → 不注册，由调用方把台词降级为旁白。
 */
class CharacterRegistry(
    private val store: CharacterStore,
    private val voicePool: VoicePool,
) {

    fun register(info: NewCharacter, excluded: Set<String> = emptySet()): CharacterInfo? {
        val name = info.name.trim()
        if (name.isEmpty()) return null
        if (isExcluded(info, excluded)) return null

        val aliasOf = info.aliasOf.trim()
        if (aliasOf.isNotEmpty()) {
            val target = store.find(aliasOf)
            if (target != null) {
                return mergeAliases(target, aliasesToMerge(info, target.name))
            }
        }

        val existing = store.find(name)
        if (existing != null) {
            return mergeAliases(existing, aliasesToMerge(info, existing.name))
        }

        val category = if (voicePool.valid(info.voice)) {
            info.voice
        } else {
            voicePool.fallbackCategory(info.gender, info.importance)
        }
        val voiceId = voicePool.voiceIdFor(category) ?: return null
        val character = CharacterInfo(
            roleId = store.allocateRoleId(),
            name = name,
            gender = info.gender,
            importance = info.importance,
            aliases = aliasesToMerge(info, name),
            note = info.note.trim(),
            category = category,
            voiceId = voiceId,
            speed = voicePool.speedFor(category),
        )
        store.insert(character)
        return character
    }

    fun registerAll(
        characters: List<NewCharacter>,
        excluded: Set<String> = emptySet(),
    ): List<CharacterInfo> = characters.mapNotNull { register(it, excluded) }

    /** 名字/别名 → role_id；旁白固定 `narrator`，未知返回 null。 */
    fun roleIdFor(nameOrAlias: String): String? = when (val name = nameOrAlias.trim()) {
        NARRATOR_ROLE_ID, "narrator" -> NARRATOR_ROLE_ID
        "" -> null
        else -> store.find(name)?.roleId
    }

    /** 未解析到角色时降级旁白（执行计划 §8.7）。 */
    fun resolveSpeaker(nameOrAlias: String): String =
        roleIdFor(nameOrAlias) ?: NARRATOR_ROLE_ID

    fun promptBlock(): String {
        val all = store.all()
        if (all.isEmpty()) return "（角色表为空）"
        return all.joinToString("\n") { character ->
            val aliases = character.aliases.joinToString(",").ifEmpty { "无" }
            "- ${character.name}(${character.roleId})" +
                " 性别:${character.gender.wire}" +
                " 重要度:${character.importance.wire}" +
                " 别名:$aliases | ${character.note}"
        }
    }

    private fun mergeAliases(target: CharacterInfo, newAliases: List<String>): CharacterInfo {
        val merged = (target.aliases + newAliases).distinct()
        if (merged == target.aliases) return target
        store.updateAliases(target.roleId, merged)
        return target.copy(aliases = merged)
    }

    private fun aliasesToMerge(info: NewCharacter, canonicalName: String): List<String> =
        (info.aliases + info.name)
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != canonicalName }
            .distinct()

    private fun isExcluded(info: NewCharacter, excluded: Set<String>): Boolean {
        if (excluded.isEmpty()) return false
        if (info.name.trim() in excluded) return true
        if (info.aliasOf.trim() in excluded) return true
        return info.aliases.any { it.trim() in excluded }
    }
}
