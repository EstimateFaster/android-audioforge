package com.estimatefaster.audioforge.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CharacterDao {

    /** 角色页订阅；数据量小，直接返回实体。 */
    @Query("SELECT * FROM characters WHERE novelId = :novelId ORDER BY id ASC")
    fun observeByNovel(novelId: Long): Flow<List<CharacterEntity>>

    /** 角色注册（§8.6）需要一次性拿到全部角色做 name/alias 匹配。 */
    @Query("SELECT * FROM characters WHERE novelId = :novelId ORDER BY id ASC")
    suspend fun list(novelId: Long): List<CharacterEntity>

    @Query("SELECT * FROM characters WHERE novelId = :novelId AND roleId = :roleId LIMIT 1")
    suspend fun getByRoleId(novelId: Long, roleId: String): CharacterEntity?

    /**
     * 先按真名精确匹配，再回退到 `aliasesJson` 里的引号包裹别名。
     * JSON 由仓库层保证是字符串数组（如 `["小明","阿明"]`）；LIKE 只做粗筛，
     * 调用方仍需解析 `aliasesJson` 复核，避免 `%`/`_` 通配符误命中。
     */
    @Query(
        """
        SELECT * FROM characters
        WHERE novelId = :novelId
          AND (name = :name OR aliasesJson LIKE '%"' || :name || '"%')
        ORDER BY CASE WHEN name = :name THEN 0 ELSE 1 END, id ASC
        LIMIT 1
        """,
    )
    suspend fun getByNameOrAlias(novelId: Long, name: String): CharacterEntity?

    @Insert
    suspend fun insert(character: CharacterEntity): Long

    @Insert
    suspend fun insertAll(characters: List<CharacterEntity>): List<Long>

    @Query(
        """
        UPDATE characters
        SET voiceId = :voiceId, category = :category, speed = :speed
        WHERE id = :id
        """,
    )
    suspend fun updateVoice(id: Long, voiceId: String, category: String, speed: Double?)

    @Query("UPDATE characters SET aliasesJson = :aliasesJson WHERE id = :id")
    suspend fun updateAliases(id: Long, aliasesJson: String)
}
