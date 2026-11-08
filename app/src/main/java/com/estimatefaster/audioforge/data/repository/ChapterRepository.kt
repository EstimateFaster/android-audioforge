package com.estimatefaster.audioforge.data.repository

import com.estimatefaster.audioforge.data.db.AppDatabase
import com.estimatefaster.audioforge.data.db.ChapterBrief
import com.estimatefaster.audioforge.data.db.ChapterEntity
import kotlinx.coroutines.flow.Flow

/**
 * 章节仓库（P2-02）。
 *
 * 列表/进度走 [ChapterBrief] 投影，绝不读 `rawText`；正文只通过
 * [getChapter] / [getRawText] 在详情/解析时读取。
 *
 * 注意：`segCount`/`segDone` 由 [SegmentRepository] 在片段变更的同一事务里维护，
 * 本仓库的 [updateCounters] 只作为受控入口，不要让 UI 直接调。
 */
class ChapterRepository(private val db: AppDatabase) {

    private val chapterDao = db.chapterDao()

    fun observeBriefs(novelId: Long): Flow<List<ChapterBrief>> =
        chapterDao.observeBriefs(novelId)

    /**
     * 分页查询（Paging 3 / 手动分页）。[query] 为空串不过滤，只匹配章节标题；
     * [limit] 必须为正，[offset] 不能为负。
     */
    suspend fun pageBriefs(
        novelId: Long,
        query: String = "",
        limit: Int = DEFAULT_PAGE_SIZE,
        offset: Int = 0,
    ): List<ChapterBrief> {
        require(limit > 0) { "limit 必须为正数：$limit" }
        require(offset >= 0) { "offset 不能为负：$offset" }
        return chapterDao.pageBriefs(novelId, query, limit, offset)
    }

    suspend fun getChapter(id: Long): ChapterEntity? = chapterDao.getChapter(id)

    suspend fun getRawText(id: Long): String? = chapterDao.getRawText(id)

    suspend fun updateParseStatus(
        id: Long,
        status: String,
        error: String? = null,
        updatedAt: Long = System.currentTimeMillis(),
    ) {
        require(status.isNotBlank()) { "parseStatus 不能为空" }
        chapterDao.updateParseStatus(id, status, error, updatedAt)
    }

    suspend fun updateAudioStatus(
        id: Long,
        status: String,
        error: String? = null,
        updatedAt: Long = System.currentTimeMillis(),
    ) {
        require(status.isNotBlank()) { "audioStatus 不能为空" }
        chapterDao.updateAudioStatus(id, status, error, updatedAt)
    }

    suspend fun updateWarn(
        id: Long,
        warn: String?,
        updatedAt: Long = System.currentTimeMillis(),
    ) {
        chapterDao.updateWarn(id, warn, updatedAt)
    }

    suspend fun updateCounters(
        id: Long,
        segCount: Int,
        segDone: Int,
        updatedAt: Long = System.currentTimeMillis(),
    ) {
        require(segCount >= 0) { "segCount 不能为负：$segCount" }
        require(segDone in 0..segCount) { "segDone($segDone) 必须在 0..segCount($segCount) 内" }
        chapterDao.updateCounters(id, segCount, segDone, updatedAt)
    }

    companion object {
        const val DEFAULT_PAGE_SIZE: Int = 50
    }
}
