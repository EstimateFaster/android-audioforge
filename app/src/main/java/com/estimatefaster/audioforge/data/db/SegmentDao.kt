package com.estimatefaster.audioforge.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SegmentDao {

    @Query("SELECT * FROM segments WHERE chapterId = :chapterId ORDER BY seq ASC")
    suspend fun list(chapterId: Long): List<SegmentEntity>

    @Query("SELECT * FROM segments WHERE chapterId = :chapterId ORDER BY seq ASC")
    fun observeByChapter(chapterId: Long): Flow<List<SegmentEntity>>

    @Query("SELECT * FROM segments WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): SegmentEntity?

    @Insert
    suspend fun insert(segment: SegmentEntity): Long

    @Insert
    suspend fun insertAll(segments: List<SegmentEntity>): List<Long>

    @Update
    suspend fun update(segment: SegmentEntity)

    /** 批量状态更新：一条 SQL `WHERE id IN (:ids)`，不要逐条写（附录 B）。 */
    @Query(
        """
        UPDATE segments
        SET status = :status, updatedAt = :updatedAt
        WHERE id IN (:ids)
        """,
    )
    suspend fun setStatus(ids: List<Long>, status: String, updatedAt: Long)

    /** 换音色后只重置该角色片段；范围限定在本书。 */
    @Query(
        """
        UPDATE segments
        SET status = 'pending',
            audioRelPath = NULL,
            durationMs = NULL,
            errorMsg = NULL,
            ttsKey = NULL,
            updatedAt = :updatedAt
        WHERE roleId = :roleId
          AND chapterId IN (SELECT id FROM chapters WHERE novelId = :novelId)
        """,
    )
    suspend fun resetByRole(novelId: Long, roleId: String, updatedAt: Long)

    @Query(
        """
        UPDATE segments
        SET status = 'pending',
            audioRelPath = NULL,
            durationMs = NULL,
            errorMsg = NULL,
            ttsKey = NULL,
            updatedAt = :updatedAt
        WHERE chapterId = :chapterId
        """,
    )
    suspend fun resetByChapter(chapterId: Long, updatedAt: Long)

    @Query("SELECT COUNT(*) FROM segments WHERE chapterId = :chapterId AND status = 'done'")
    suspend fun countDone(chapterId: Long): Int

    @Query("SELECT COUNT(*) FROM segments WHERE chapterId = :chapterId")
    suspend fun countByChapter(chapterId: Long): Int

    /** 重新解析时先在事务里清空旧片段，再 insertAll 新片段。 */
    @Query("DELETE FROM segments WHERE chapterId = :chapterId")
    suspend fun deleteByChapter(chapterId: Long)
}
