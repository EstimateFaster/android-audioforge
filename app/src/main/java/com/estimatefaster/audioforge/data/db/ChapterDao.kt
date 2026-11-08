package com.estimatefaster.audioforge.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 章节列表投影：故意不含 `rawText`（§7.2）。变更字段时必须同步更新 SQL 与测试。
 */
data class ChapterBrief(
    val id: Long,
    val idx: Int,
    val title: String,
    val parseStatus: String,
    val audioStatus: String,
    val parseWarn: String?,
    val segCount: Int,
    val segDone: Int,
)

@Dao
interface ChapterDao {

    /** 项目详情页的章节列表；不读取正文，2000 章也不会把大字段拉进内存。 */
    @Query(
        """
        SELECT id, idx, title, parseStatus, audioStatus, parseWarn, segCount, segDone
        FROM chapters
        WHERE novelId = :novelId
        ORDER BY idx ASC
        """,
    )
    fun observeBriefs(novelId: Long): Flow<List<ChapterBrief>>

    /** Paging 3 / 手动分页用的投影查询；`query` 为空串表示不过滤。 */
    @Query(
        """
        SELECT id, idx, title, parseStatus, audioStatus, parseWarn, segCount, segDone
        FROM chapters
        WHERE novelId = :novelId
          AND (:query = '' OR title LIKE '%' || :query || '%')
        ORDER BY idx ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun pageBriefs(
        novelId: Long,
        query: String,
        limit: Int,
        offset: Int,
    ): List<ChapterBrief>

    /** 详情/解析用，含 `rawText`。 */
    @Query("SELECT * FROM chapters WHERE id = :id LIMIT 1")
    suspend fun getChapter(id: Long): ChapterEntity?

    @Query("SELECT rawText FROM chapters WHERE id = :id LIMIT 1")
    suspend fun getRawText(id: Long): String?

    @Insert
    suspend fun insert(chapter: ChapterEntity): Long

    @Insert
    suspend fun insertAll(chapters: List<ChapterEntity>): List<Long>

    /** 同时更新解析与音频状态；只改一边时用下面的专用方法。 */
    @Query(
        """
        UPDATE chapters
        SET parseStatus = :parseStatus,
            parseError = :parseError,
            audioStatus = :audioStatus,
            audioError = :audioError,
            updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateStatus(
        id: Long,
        parseStatus: String,
        parseError: String?,
        audioStatus: String,
        audioError: String?,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE chapters
        SET parseStatus = :status, parseError = :error, updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateParseStatus(
        id: Long,
        status: String,
        error: String?,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE chapters
        SET audioStatus = :status, audioError = :error, updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateAudioStatus(
        id: Long,
        status: String,
        error: String?,
        updatedAt: Long,
    )

    @Query("UPDATE chapters SET parseWarn = :warn, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateWarn(id: Long, warn: String?, updatedAt: Long)

    @Query(
        """
        UPDATE chapters
        SET segCount = :segCount, segDone = :segDone, updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateCounters(id: Long, segCount: Int, segDone: Int, updatedAt: Long)
}
