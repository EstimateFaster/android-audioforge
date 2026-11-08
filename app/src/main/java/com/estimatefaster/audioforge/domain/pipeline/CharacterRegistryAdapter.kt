package com.estimatefaster.audioforge.domain.pipeline

import androidx.room.withTransaction
import com.estimatefaster.audioforge.core.model.CharacterInfo
import com.estimatefaster.audioforge.core.model.Gender
import com.estimatefaster.audioforge.core.model.Importance
import com.estimatefaster.audioforge.core.model.NewCharacter
import com.estimatefaster.audioforge.core.model.roleIdFor
import com.estimatefaster.audioforge.core.script.CharacterRegistry
import com.estimatefaster.audioforge.core.script.CharacterStore
import com.estimatefaster.audioforge.core.voice.VoicePool
import com.estimatefaster.audioforge.data.db.AppDatabase
import com.estimatefaster.audioforge.data.db.CharacterEntity
import com.estimatefaster.audioforge.data.repository.CharacterRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * P2-13：把 [CharacterStore] 接到 [CharacterRepository] / Room（执行计划 §8.6 / §15 P2-13）。
 *
 * 为什么不是「每次调用都直接查库」：
 * - [CharacterStore] 是**同步**接口（P1 的 `InMemoryCharacterStore` 也如此），而
 *   [CharacterRepository] 的方法全部是 `suspend`；
 * - 在 `db.withTransaction` 里对同步接口做 `runBlocking` 桥接会占住 Room 的事务线程，
 *   嵌套 suspend 调用有死锁风险。
 *
 * 因此本适配器在内存里维护一份「当前事务快照」：
 * 1. [registerAll] / [refresh] 用 `repository.list(novelId)` 整表载入快照；
 * 2. [find] / [all] / [allocateRoleId] 只读快照，保持同步；
 * 3. [insert] / [updateAliases] 先改快照并登记待写，[registerAll] 每注册完一个角色就
 *    flush 一次；flush 仍在外层事务内，失败会整批回滚。
 *
 * 音色锁定以 [CharacterRepository.upsert] 为准（命中已有角色只合并别名，绝不覆盖
 * category / voiceId / speed）；选角与 roleId 分配以 [CharacterRegistry] 为准，
 * 两边规则一致，不会双重覆盖。
 *
 * 注意：
 * - [novelId] 是 data 层所有角色查询/写入的键。任务描述里的三参构造无法定位
 *   [CharacterEntity.novelId]（非空且外键指向 novels），所以这里显式要求传入。
 * - [db] 与 [repository] 必须由**同一个** [AppDatabase] 构造，否则 `repository.upsert`
 *   的嵌套事务不在外层事务里，回滚语义会被破坏。
 * - 本类持有可变快照，**不是线程安全的**；[registerAll] 用 [Mutex] 串行化，
 *   同一实例不要在多个协程里并发使用。跨实例并发由 Room 事务与
 *   `(novelId, roleId)` 唯一索引兜底。
 * - 不要绕过 [registerAll] 直接调用 [registry] 的写方法：同步接口不会立即落库，
 *   只会改内存快照。读方法（`roleIdFor` / `promptBlock` / `all`）请先 [refresh]。
 */
class CharacterRegistryAdapter(
    private val db: AppDatabase,
    private val repository: CharacterRepository,
    private val voicePool: VoicePool,
    private val novelId: Long,
) : CharacterStore {

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    /** roleId → 角色快照，顺序与 DB 的 `ORDER BY id ASC` 一致。 */
    private val characters = linkedMapOf<String, CharacterInfo>()

    /** 真名索引；同名时最小 id 优先（与 `CharacterDao.getByNameOrAlias` 的排序一致）。 */
    private val byName = hashMapOf<String, String>()

    /** 别名索引；真名索引未命中时才查。 */
    private val byAlias = hashMapOf<String, String>()

    /** roleId → 待 upsert 的实体；同一个角色在 flush 前多次更新只保留最后一次。 */
    private val pending = linkedMapOf<String, CharacterEntity>()

    private var nextRoleNumber = 1

    init {
        require(novelId > 0) { "novelId 必须为正数：$novelId" }
    }

    /** 注册器；读方法需先 [refresh]，写注册请走 [registerAll]。 */
    val registry: CharacterRegistry = CharacterRegistry(this, voicePool)

    /**
     * 从仓库重新载入整本小说的角色快照。
     *
     * 解析前要拿 `registry.promptBlock()` / `roleIdFor()` 时调用；[registerAll] 内部
     * 也会在事务开头自动调用一次，所以注册后的读不需要再 refresh。
     */
    suspend fun refresh() {
        mutex.withLock {
            reload(repository.list(novelId))
        }
    }

    /**
     * 在一个 Room 事务里注册整批新角色（§8.6）。
     *
     * 逐条调用 [CharacterRegistry.register]；每注册完一个就把该角色 flush 到仓库，
     * 因此中途异常（非法输入、DB 约束失败）会由外层 `withTransaction` 整批回滚，
     * 不会留下半批角色。返回顺序与输入一致，`excluded` / 空名字返回的 null 会被跳过。
     */
    suspend fun registerAll(
        newCharacters: List<NewCharacter>,
        excluded: Set<String>,
    ): List<CharacterInfo> = mutex.withLock {
        db.withTransaction {
            reload(repository.list(novelId))

            val registered = ArrayList<CharacterInfo>(newCharacters.size)
            for (info in newCharacters) {
                val result = registry.register(info, excluded)
                flushPending()
                if (result != null) registered += result
            }
            registered
        }
    }

    // ------------------------------------------------------------ CharacterStore

    /** 先真名、后别名，均按快照精确匹配；空串返回 null。 */
    override fun find(nameOrAlias: String): CharacterInfo? {
        val query = nameOrAlias.trim()
        if (query.isEmpty()) return null
        val roleId = byName[query] ?: byAlias[query] ?: return null
        return characters[roleId]
    }

    override fun all(): List<CharacterInfo> = characters.values.toList()

    override fun insert(character: CharacterInfo) {
        val stored = character.copy(
            aliases = normalizeAliases(character.aliases, character.name),
        )
        characters[stored.roleId] = stored
        parseRoleNumber(stored.roleId)?.let { number ->
            if (number >= nextRoleNumber) nextRoleNumber = number + 1
        }
        rebuildIndexes()
        pending[stored.roleId] = stored.toEntity()
    }

    override fun updateAliases(roleId: String, aliases: List<String>) {
        val current = characters[roleId] ?: return
        val normalized = normalizeAliases(aliases, current.name)
        if (normalized == current.aliases) return

        val updated = current.copy(aliases = normalized)
        characters[roleId] = updated
        rebuildIndexes()
        pending[roleId] = updated.toEntity()
    }

    /** 从已有序号的最大值 +1；未注册过角色时从 `role_0001` 开始。 */
    override fun allocateRoleId(): String {
        val roleId = roleIdFor(nextRoleNumber)
        nextRoleNumber += 1
        return roleId
    }

    // ---------------------------------------------------------------- internals

    private suspend fun flushPending() {
        if (pending.isEmpty()) return
        val writes = pending.values.toList()
        // 全部成功后再清空；异常时事务回滚，下一次 registerAll 会重新载入快照。
        for (entity in writes) repository.upsert(entity)
        pending.clear()
    }

    private fun reload(entities: List<CharacterEntity>) {
        characters.clear()
        pending.clear()
        for (entity in entities) {
            val info = entity.toCharacterInfo()
            // (novelId, roleId) 有唯一索引；重复时保留 id 更小的一条。
            if (!characters.containsKey(info.roleId)) characters[info.roleId] = info
        }
        rebuildIndexes()
        nextRoleNumber = (characters.keys.mapNotNull { parseRoleNumber(it) }.maxOrNull() ?: 0) + 1
    }

    private fun rebuildIndexes() {
        byName.clear()
        byAlias.clear()
        for ((roleId, info) in characters) {
            // 真名优先：别名索引不覆盖已存在的真名/别名。
            if (!byName.containsKey(info.name)) byName[info.name] = roleId
            for (alias in info.aliases) {
                if (!byAlias.containsKey(alias)) byAlias[alias] = roleId
            }
        }
    }

    private fun normalizeAliases(aliases: List<String>, canonicalName: String): List<String> =
        aliases.map { it.trim() }
            .filter { it.isNotEmpty() && it != canonicalName }
            .distinct()

    private fun CharacterInfo.toEntity(): CharacterEntity = CharacterEntity(
        novelId = novelId,
        roleId = roleId,
        name = name,
        gender = gender.wire,
        importance = importance.wire,
        aliasesJson = encodeAliases(aliases),
        note = note,
        category = category,
        voiceId = voiceId,
        speed = speed,
        locked = locked,
    )

    private fun CharacterEntity.toCharacterInfo(): CharacterInfo = CharacterInfo(
        roleId = roleId,
        name = name,
        gender = Gender.fromWire(gender),
        importance = Importance.fromWire(importance),
        aliases = normalizeAliases(decodeAliases(aliasesJson), name),
        note = note,
        category = category,
        voiceId = voiceId,
        speed = speed,
        locked = locked,
    )

    private fun parseRoleNumber(roleId: String): Int? =
        ROLE_ID_PATTERN.matchEntire(roleId)?.groupValues?.get(1)?.toIntOrNull()

    private fun encodeAliases(aliases: List<String>): String =
        json.encodeToString(ListSerializer(String.serializer()), aliases)

    private fun decodeAliases(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return try {
            json.decodeFromString(ListSerializer(String.serializer()), raw)
        } catch (_: SerializationException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }
}

/** `role_0001` 形式；`narrator` / `unknown` 不参与序号分配。 */
private val ROLE_ID_PATTERN = Regex("^role_(\\d+)$")
