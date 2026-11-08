package com.estimatefaster.audioforge.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 角色微调 DAO（P5-06 / 执行计划 §12.4 S09、§15 P5-06）。
 *
 * 表结构由 P2-01 的 [CharacterTuningEntity] 定义（`character_tuning`，
 * 主键 `novelId + roleId + key`，对 `novels` 级联删除）；本任务只补 DAO，
 * **不新增/修改任何列**，`AppDatabase.version` 保持 1。
 *
 * 约定：
 * - `key` 只允许 `speed` / `volume` / `pitch`，由 `TuningRepository` 保证；
 * - DAO 只做行级读写，不做范围校验/聚合，仓库层负责映射为 `CharacterTuning`；
 * - 所有写操作按 `(novelId, roleId)` 整体替换，避免部分 key 残留。
 */
@Dao
interface TuningDao {

    /** 订阅整本书的微调行；Flow 在写入后由 Room invalidation trigger 触发重查。 */
    @Query(
        """
        SELECT * FROM character_tuning
        WHERE novelId = :novelId
        ORDER BY roleId ASC, key ASC
        """,
    )
    fun observeByNovel(novelId: Long): Flow<List<CharacterTuningEntity>>

    /** 读单个角色的三行；没有微调时返回空列表。 */
    @Query(
        """
        SELECT * FROM character_tuning
        WHERE novelId = :novelId AND roleId = :roleId
        ORDER BY key ASC
        """,
    )
    suspend fun listByRole(novelId: Long, roleId: String): List<CharacterTuningEntity>

    /**
     * 写入/覆盖微调行。
     *
     * 复合主键冲突时用 [OnConflictStrategy.REPLACE] 覆盖 `value`；
     * 传空列表是 no-op（Room 生成的 `@Insert` 会直接返回）。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tuning: List<CharacterTuningEntity>)

    /** 删除单个角色的全部微调（恢复全局默认）；返回删除行数。 */
    @Query("DELETE FROM character_tuning WHERE novelId = :novelId AND roleId = :roleId")
    suspend fun deleteRole(novelId: Long, roleId: String): Int

    /**
     * 删除整本书的微调；正常删除项目靠 `novels` 外键级联，本方法用于
     * 清理孤儿或“重置项目设置”，返回删除行数。
     */
    @Query("DELETE FROM character_tuning WHERE novelId = :novelId")
    suspend fun deleteNovel(novelId: Long): Int
}
