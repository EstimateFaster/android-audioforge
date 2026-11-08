package com.estimatefaster.audioforge.data.repository

import androidx.room.withTransaction
import com.estimatefaster.audioforge.data.db.AppDatabase
import com.estimatefaster.audioforge.data.db.SegmentEntity
import kotlinx.coroutines.flow.Flow

/**
 * 片段仓库（P2-02）。
 *
 * 所有会改动片段集合/状态的方法都在 `db.withTransaction` 里完成，并在同一事务内
 * 同步 `chapters.segCount/segDone`（`NovelDao.observeProjects` 依赖这两个冗余列，
 * 不会 JOIN segments）。
 *
 * 已知 DAO 缺口（本任务不改 `data/db`）：
 * - 没有「按 ids 查 chapterId」的查询，[setStatus] 只能先逐条 [get]；
 * - 没有「按 role 查 chapterId」的查询，[resetByRole] 用章节分页 + 逐章扫描。
 * 数据量小（单章几百段），当前实现正确优先；后续补 DAO 可优化。
 */
class SegmentRepository(private val db: AppDatabase) {

    private val segmentDao = db.segmentDao()
    private val chapterDao = db.chapterDao()

    fun observeByChapter(chapterId: Long): Flow<List<SegmentEntity>> =
        segmentDao.observeByChapter(chapterId)

    suspend fun list(chapterId: Long): List<SegmentEntity> = segmentDao.list(chapterId)

    suspend fun get(id: Long): SegmentEntity? = segmentDao.get(id)

    /**
     * 单事务替换整章片段：先删旧片段，再按列表顺序写入新片段并重排 `seq`。
     *
     * 返回新片段 id（顺序与入参一致）。空列表等价于清空本章片段，计数归零。
     *
     * @throws IllegalArgumentException 章节不存在
     */
    suspend fun replace(
        chapterId: Long,
        segments: List<SegmentEntity>,
        updatedAt: Long = System.currentTimeMillis(),
    ): List<Long> {
        return db.withTransaction {
            requireNotNull(chapterDao.getChapter(chapterId)) { "章节不存在：$chapterId" }
            segmentDao.deleteByChapter(chapterId)

            val normalized = segments.mapIndexed { index, segment ->
                segment.copy(
                    id = 0,
                    chapterId = chapterId,
                    seq = index,
                    updatedAt = updatedAt,
                )
            }
            val ids: List<Long> = if (normalized.isEmpty()) {
                emptyList()
            } else {
                segmentDao.insertAll(normalized)
            }
            syncCounters(chapterId, updatedAt)
            ids
        }
    }

    /**
     * 批量改状态（一条 `UPDATE ... WHERE id IN (...)`），并同步所有受影响章节的
     * `segDone`。空 [ids] 直接返回。
     */
    suspend fun setStatus(
        ids: List<Long>,
        status: String,
        updatedAt: Long = System.currentTimeMillis(),
    ) {
        if (ids.isEmpty()) return
        require(status.isNotBlank()) { "status 不能为空" }

        db.withTransaction {
            val chapterIds = ids.mapNotNull { segmentDao.get(it)?.chapterId }.distinct()
            segmentDao.setStatus(ids, status, updatedAt)
            chapterIds.forEach { chapterId -> syncCounters(chapterId, updatedAt) }
        }
    }

    /**
     * 换音色后重置本书该角色的全部片段（状态/音频路径/哈希/错误），
     * 并把这些章节的 `audio_status` 置回 pending、同步计数。
     *
     * @return 受影响的章节数
     */
    suspend fun resetByRole(
        novelId: Long,
        roleId: String,
        updatedAt: Long = System.currentTimeMillis(),
    ): Int {
        require(novelId > 0) { "novelId 必须为正数：$novelId" }
        require(roleId.isNotBlank()) { "roleId 不能为空" }

        return db.withTransaction {
            val chapterIds = chapterDao.pageBriefs(novelId, "", Int.MAX_VALUE, 0).map { it.id }
            val affected = chapterIds.filter { chapterId ->
                segmentDao.list(chapterId).any { it.roleId == roleId }
            }
            if (affected.isEmpty()) return@withTransaction 0

            segmentDao.resetByRole(novelId, roleId, updatedAt)
            affected.forEach { chapterId ->
                syncCounters(chapterId, updatedAt)
                chapterDao.updateAudioStatus(chapterId, STATUS_PENDING, null, updatedAt)
            }
            affected.size
        }
    }

    /** 重置整章片段（重新渲染前用），并同步计数/章节音频状态。 */
    suspend fun resetByChapter(
        chapterId: Long,
        updatedAt: Long = System.currentTimeMillis(),
    ) {
        db.withTransaction {
            segmentDao.resetByChapter(chapterId, updatedAt)
            syncCounters(chapterId, updatedAt)
            chapterDao.updateAudioStatus(chapterId, STATUS_PENDING, null, updatedAt)
        }
    }

    suspend fun countDone(chapterId: Long): Int = segmentDao.countDone(chapterId)

    suspend fun count(chapterId: Long): Int = segmentDao.countByChapter(chapterId)

    private suspend fun syncCounters(chapterId: Long, updatedAt: Long) {
        val total = segmentDao.countByChapter(chapterId)
        val done = segmentDao.countDone(chapterId)
        chapterDao.updateCounters(chapterId, total, done, updatedAt)
    }

    private companion object {
        const val STATUS_PENDING = "pending"
    }
}
