package com.estimatefaster.audioforge.data.repository

import androidx.room.withTransaction
import com.estimatefaster.audioforge.core.storage.AudioStorage
import com.estimatefaster.audioforge.data.db.AppDatabase
import com.estimatefaster.audioforge.data.db.ChapterEntity
import com.estimatefaster.audioforge.data.db.NovelEntity
import com.estimatefaster.audioforge.data.db.ProjectSummary
import java.io.IOException
import kotlinx.coroutines.flow.Flow

/**
 * 新建项目时的一章：标题 + 正文。
 *
 * `idx` 由仓库按列表顺序生成（0 基），调用方不直接指定，避免出现空洞/重复。
 */
data class ChapterInput(
    val title: String,
    val rawText: String,
)

/**
 * 项目仓库（执行计划 §7.7 / P2-02）。
 *
 * - `createProject` 把 novel + chapters 放在一个事务里；正文只在创建时写入。
 * - `delete` 先删 DB 行（外键级联 chapters/segments/characters/jobs），再清理
 *   `audio/<novelId>` 与导入源文件（§7.6）。
 * - 字符数在创建时按正文字符数一次算好；`NovelDao` 目前没有更新 charCount 的方法，
 *   后续如需重新统计必须补 DAO（见本任务报告）。
 */
class ProjectRepository(
    private val db: AppDatabase,
    private val audioStorage: AudioStorage,
) {

    private val novelDao = db.novelDao()
    private val chapterDao = db.chapterDao()

    fun observeProjects(): Flow<List<ProjectSummary>> = novelDao.observeProjects()

    suspend fun listProjects(): List<NovelEntity> = novelDao.listNovels()

    suspend fun getProject(id: Long): NovelEntity? = novelDao.getNovel(id)

    /** 导入前查重；调用方决定「打开已有 / 另存为副本」。 */
    suspend fun findByTitle(title: String): NovelEntity? = novelDao.findByTitle(title.trim())

    /**
     * 单事务创建项目。
     *
     * @param chapters 按顺序写入，`idx` 自动分配为 0、1、2…；标题必须非空。
     * @return 新项目 id
     * @throws IllegalArgumentException 标题/章节标题为空
     * @throws android.database.sqlite.SQLiteConstraintException 标题重复（唯一索引）
     */
    suspend fun createProject(
        title: String,
        sourceName: String,
        sourceRelPath: String,
        chapters: List<ChapterInput>,
        createdAt: Long = System.currentTimeMillis(),
    ): Long {
        val trimmedTitle = title.trim()
        require(trimmedTitle.isNotEmpty()) { "项目标题不能为空" }
        require(sourceName.isNotBlank()) { "源文件名不能为空" }
        chapters.forEachIndexed { index, chapter ->
            require(chapter.title.isNotBlank()) { "章节标题不能为空（idx=$index）" }
        }
        val charCount = totalCharCount(chapters)

        return db.withTransaction {
            val novelId = novelDao.insert(
                NovelEntity(
                    title = trimmedTitle,
                    sourceName = sourceName,
                    sourceRelPath = sourceRelPath,
                    charCount = charCount,
                    createdAt = createdAt,
                ),
            )
            if (chapters.isNotEmpty()) {
                chapterDao.insertAll(
                    chapters.mapIndexed { index, chapter ->
                        ChapterEntity(
                            novelId = novelId,
                            idx = index,
                            title = chapter.title.trim(),
                            rawText = chapter.rawText,
                            updatedAt = createdAt,
                        )
                    },
                )
            }
            novelId
        }
    }

    /** 重命名；项目不存在时返回 false，空标题抛 [IllegalArgumentException]。 */
    suspend fun rename(id: Long, title: String): Boolean {
        val trimmed = title.trim()
        require(trimmed.isNotEmpty()) { "项目标题不能为空" }
        if (novelDao.getNovel(id) == null) return false
        novelDao.rename(id, trimmed)
        return true
    }

    /** 记录最近打开时间（项目列表按它优先排序）。 */
    suspend fun updateLastOpened(
        id: Long,
        openedAt: Long = System.currentTimeMillis(),
    ): Boolean {
        if (novelDao.getNovel(id) == null) return false
        novelDao.updateLastOpenedAt(id, openedAt)
        return true
    }

    /**
     * 删除项目：DB 行 + 项目音频目录 + 导入源文件。
     *
     * DB 删除在事务里完成（外键级联），文件清理随后执行；源文件缺失不视为失败，
     * 但音频目录删除失败会抛 [IOException]（避免静默泄漏）。
     *
     * @return 项目不存在时返回 false
     */
    suspend fun delete(id: Long): Boolean {
        val novel = novelDao.getNovel(id) ?: return false
        db.withTransaction { novelDao.delete(id) }
        deleteProjectFiles(novel)
        return true
    }

    private fun deleteProjectFiles(novel: NovelEntity) {
        if (novel.sourceRelPath.isNotBlank()) {
            // 源文件可能已被清理任务/用户删除；缺失不影响项目删除。
            runCatching { audioStorage.deleteFile(novel.sourceRelPath) }
        }
        if (!audioStorage.deleteNovel(novel.id)) {
            throw IOException("删除项目音频目录失败：audio/${novel.id}")
        }
    }

    companion object {
        /** 项目字符数 = 各章正文的 Unicode code point 数（emoji 不会算成两个字符）。 */
        fun totalCharCount(chapters: List<ChapterInput>): Int =
            chapters.sumOf { chapter ->
                chapter.rawText.codePointCount(0, chapter.rawText.length)
            }
    }
}
