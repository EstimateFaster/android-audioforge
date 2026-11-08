package com.estimatefaster.audioforge.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 项目列表用一行聚合结果，避免逐项目子查询（§7.2 / 附录 B）。
 *
 * 章节/片段计数来自 `chapters` 的冗余列 `segCount/segDone`，不 JOIN segments；
 * 仓库层在改片段状态时必须在同一事务里维护这些列。
 */
data class ProjectSummary(
    val id: Long,
    val title: String,
    val sourceName: String,
    val charCount: Int,
    val createdAt: Long,
    val lastOpenedAt: Long?,
    val chapterCount: Int,
    val chapterDoneCount: Int,
    val segmentCount: Int,
    val segmentDoneCount: Int,
)

@Dao
interface NovelDao {

    /**
     * 一次 GROUP BY 拿全项目卡数据：章节总数、音频完成章节数、片段总数/完成数。
     * 排序与项目库一致：最近打开优先，其次创建时间。
     */
    @Query(
        """
        SELECT n.id AS id,
               n.title AS title,
               n.sourceName AS sourceName,
               n.charCount AS charCount,
               n.createdAt AS createdAt,
               n.lastOpenedAt AS lastOpenedAt,
               COUNT(c.id) AS chapterCount,
               COALESCE(SUM(CASE WHEN c.audioStatus = 'done' THEN 1 ELSE 0 END), 0) AS chapterDoneCount,
               COALESCE(SUM(c.segCount), 0) AS segmentCount,
               COALESCE(SUM(c.segDone), 0) AS segmentDoneCount
        FROM novels n
        LEFT JOIN chapters c ON c.novelId = n.id
        GROUP BY n.id
        ORDER BY COALESCE(n.lastOpenedAt, n.createdAt) DESC, n.id DESC
        """,
    )
    fun observeProjects(): Flow<List<ProjectSummary>>

    @Query("SELECT * FROM novels ORDER BY COALESCE(lastOpenedAt, createdAt) DESC, id DESC")
    fun observeNovels(): Flow<List<NovelEntity>>

    @Query("SELECT * FROM novels ORDER BY COALESCE(lastOpenedAt, createdAt) DESC, id DESC")
    suspend fun listNovels(): List<NovelEntity>

    @Query("SELECT * FROM novels WHERE id = :id LIMIT 1")
    suspend fun getNovel(id: Long): NovelEntity?

    @Query("SELECT * FROM novels WHERE title = :title LIMIT 1")
    suspend fun findByTitle(title: String): NovelEntity?

    @Insert
    suspend fun insert(novel: NovelEntity): Long

    @Query("UPDATE novels SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("UPDATE novels SET lastOpenedAt = :openedAt WHERE id = :id")
    suspend fun updateLastOpenedAt(id: Long, openedAt: Long)

    @Query("DELETE FROM novels WHERE id = :id")
    suspend fun delete(id: Long)
}
