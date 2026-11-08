package com.estimatefaster.audioforge.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * P0-05 的最小探针表：只用于验证 Room/KSP 代码生成与运行时读写。
 *
 * 正式的 Novel/Chapter/Character/Segment 等实体在 P2-01 落地；届时删除本表并升
 * `AppDatabase.version`，v1 不做迁移（见执行计划 §7.3）。
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
