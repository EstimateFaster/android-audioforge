package com.estimatefaster.audioforge.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room 实体，字段/表名/索引/外键严格对齐执行计划 §7.1 与附录 E 的 DDL。
 *
 * 约定：
 * - 表名/列名用 camelCase；`rawText` 只在 [ChapterEntity]，其他列表查询禁止 `SELECT *`；
 * - 所有子表对 novels/chapters 使用 `ON DELETE CASCADE`，删除项目必须靠外键清干净；
 * - 时间戳是 epoch millis；`audioRelPath` 只存相对路径（§7.6）。
 */

@Entity(
    tableName = "novels",
    indices = [Index(value = ["title"], unique = true)],
)
data class NovelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val sourceName: String,
    val sourceRelPath: String,
    val charCount: Int,
    val createdAt: Long,
    val lastOpenedAt: Long? = null,
)

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = NovelEntity::class,
            parentColumns = ["id"],
            childColumns = ["novelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["novelId", "idx"], unique = true),
        Index(value = ["novelId", "audioStatus"]),
        Index(value = ["novelId", "parseStatus"]),
    ],
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val novelId: Long,
    val idx: Int,
    val title: String,
    /** 章节正文；只有详情/解析需要读，列表用 ChapterBrief 投影。 */
    val rawText: String,
    val parseStatus: String = "pending",
    val parseError: String? = null,
    val audioStatus: String = "pending",
    val audioError: String? = null,
    val parseWarn: String? = null,
    val segCount: Int = 0,
    val segDone: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "characters",
    foreignKeys = [
        ForeignKey(
            entity = NovelEntity::class,
            parentColumns = ["id"],
            childColumns = ["novelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["novelId", "roleId"], unique = true),
        Index(value = ["novelId", "name"]),
    ],
)
data class CharacterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val novelId: Long,
    /** role_0001 / narrator。 */
    val roleId: String,
    val name: String,
    val gender: String = "unknown",
    val importance: String = "supporting",
    val aliasesJson: String = "[]",
    val note: String = "",
    val category: String = "",
    val voiceId: String = "",
    val speed: Double? = null,
    val locked: Boolean = true,
)

@Entity(
    tableName = "segments",
    foreignKeys = [
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["chapterId", "seq"], unique = true),
        Index(value = ["roleId"]),
        Index(value = ["status"]),
        Index(value = ["chapterId", "status"]),
    ],
)
data class SegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chapterId: Long,
    val seq: Int,
    /** narration / dialogue。 */
    val type: String,
    /** narrator / role_XXXX。 */
    val roleId: String,
    val emotion: String,
    val text: String,
    val status: String = "pending",
    /** audio/<nid>/segments/ch_001/seg_000001.mp3，相对 filesDir。 */
    val audioRelPath: String? = null,
    val durationMs: Int? = null,
    val errorMsg: String? = null,
    val ttsKey: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "character_tuning",
    primaryKeys = ["novelId", "roleId", "key"],
    foreignKeys = [
        ForeignKey(
            entity = NovelEntity::class,
            parentColumns = ["id"],
            childColumns = ["novelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class CharacterTuningEntity(
    val novelId: Long,
    val roleId: String,
    /** speed / volume / pitch。 */
    val key: String,
    val value: String,
)

@Entity(
    tableName = "novel_meta",
    foreignKeys = [
        ForeignKey(
            entity = NovelEntity::class,
            parentColumns = ["id"],
            childColumns = ["novelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class NovelMetaEntity(
    @PrimaryKey val novelId: Long,
    val lastGeneratedChapter: Int = 0,
)

@Entity(
    tableName = "jobs",
    foreignKeys = [
        ForeignKey(
            entity = NovelEntity::class,
            parentColumns = ["id"],
            childColumns = ["novelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["novelId", "status"])],
)
data class JobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val novelId: Long,
    /** full / parse / render。 */
    val mode: String,
    /** queued / running / done / error / cancelled。 */
    val status: String,
    /** 本次批次的 chapterId JSON。 */
    val requestedIdsJson: String,
    val currentChapterId: Long? = null,
    val currentTitle: String = "",
    val startedAt: Long,
    val finishedAt: Long? = null,
    val lastError: String? = null,
    val statsJson: String = "{}",
    val stopRequested: Boolean = false,
)

/**
 * P0-05 的最小探针表：只用于验证 Room/KSP 代码生成与运行时读写。
 *
 * P2-01 保留它（P0 debug 卡片仍引用 `ProbeDao`），P2-02 再移除并升 version。
 */
@Entity(tableName = "probe")
data class ProbeEntity(
    @PrimaryKey val id: Long = PROBE_ID,
    val note: String,
    val counter: Int,
    val updatedAt: Long,
) {
    companion object {
        const val PROBE_ID = 1L
    }
}
