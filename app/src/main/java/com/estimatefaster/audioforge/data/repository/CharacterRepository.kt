package com.estimatefaster.audioforge.data.repository

import androidx.room.withTransaction
import com.estimatefaster.audioforge.data.db.AppDatabase
import com.estimatefaster.audioforge.data.db.CharacterEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 角色仓库（P2-02 / §8.6）。
 *
 * 关键语义：
 * - **音色锁定**：[upsert] 命中已有角色（同 roleId，或同 name/alias）时只合并别名，
 *   绝不覆盖 `category` / `voiceId` / `speed`；
 * - **别名合并**：别名以 JSON 字符串数组存库，比较/合并前会解析并去空白、去重；
 * - [getByNameOrAlias] 先用 DAO 的 LIKE 粗筛，再用解析后的别名精确复核，避免
 *   `%`/`_` 通配符误命中（DAO 的 LIKE 只是索引优化）。
 */
class CharacterRepository(private val db: AppDatabase) {

    private val characterDao = db.characterDao()

    private val json = Json { ignoreUnknownKeys = true }

    fun observeByNovel(novelId: Long): Flow<List<CharacterEntity>> =
        characterDao.observeByNovel(novelId)

    suspend fun list(novelId: Long): List<CharacterEntity> = characterDao.list(novelId)

    suspend fun getByRoleId(novelId: Long, roleId: String): CharacterEntity? =
        characterDao.getByRoleId(novelId, roleId)

    /**
     * 按真名或别名精确查找；空名字返回 null。
     *
     * DAO 的 `LIMIT 1` + LIKE 可能先命中通配符假阳性，因此命中后要复核；
     * 复核失败时回退到全书扫描，保证真正匹配的行不会被遮住。角色数量很小。
     */
    suspend fun getByNameOrAlias(novelId: Long, name: String): CharacterEntity? {
        val query = name.trim()
        if (query.isEmpty()) return null

        val hit = characterDao.getByNameOrAlias(novelId, query)
        if (hit != null && hit.matches(query)) return hit
        return characterDao.list(novelId).firstOrNull { it.matches(query) }
    }

    /**
     * 插入或合并且已存在的角色。
     *
     * - 先按 `roleId` 命中，再按 `name`/别名命中；
     * - 命中后只合并别名（候选名的变体也会成为别名），已有 `name` 与音色三字段保持不动；
     * - 未命中时插入候选，`id` 由 DB 生成，别名去掉真名并规范化。
     */
    suspend fun upsert(candidate: CharacterEntity): CharacterEntity {
        require(candidate.novelId > 0) { "novelId 必须为正数：${candidate.novelId}" }
        require(candidate.roleId.isNotBlank()) { "roleId 不能为空" }
        require(candidate.name.isNotBlank()) { "角色名不能为空" }

        return db.withTransaction {
            val existing = characterDao.getByRoleId(candidate.novelId, candidate.roleId)
                ?: getByNameOrAlias(candidate.novelId, candidate.name)

            if (existing != null) {
                val currentAliases = decodeAliases(existing.aliasesJson)
                val mergedAliases = mergeAliases(existing, candidate)
                if (mergedAliases != currentAliases) {
                    characterDao.updateAliases(existing.id, encodeAliases(mergedAliases))
                }
                existing.copy(aliasesJson = encodeAliases(mergedAliases))
            } else {
                val newName = candidate.name.trim()
                val aliases = (decodeAliases(candidate.aliasesJson) + newName)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && it != newName }
                    .distinct()
                val entity = candidate.copy(
                    id = 0,
                    name = newName,
                    aliasesJson = encodeAliases(aliases),
                )
                val id = characterDao.insert(entity)
                entity.copy(id = id)
            }
        }
    }

    /**
     * 删除角色。`CharacterDao` 目前没有 `@Delete`/delete 查询（P2-01 缺口），
     * 这里用参数化 SQL 完成；删除仍会触发 Room 的 invalidation trigger，Flow 可收到变更。
     *
     * 片段里残留的 `roleId` 不做改写（删除角色后的台词归属由上层用例决定）。
     *
     * @return 角色不存在时返回 false
     */
    suspend fun delete(novelId: Long, roleId: String): Boolean = db.withTransaction {
        val existing = characterDao.getByRoleId(novelId, roleId)
            ?: return@withTransaction false
        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM characters WHERE id = ?",
            arrayOf(existing.id),
        )
        true
    }

    private fun mergeAliases(existing: CharacterEntity, candidate: CharacterEntity): List<String> =
        (decodeAliases(existing.aliasesJson) +
            decodeAliases(candidate.aliasesJson) +
            candidate.name)
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != existing.name }
            .distinct()

    private fun CharacterEntity.matches(name: String): Boolean =
        this.name == name || decodeAliases(aliasesJson).any { it.trim() == name }

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
