package com.estimatefaster.audioforge.data.repository

import androidx.room.withTransaction
import com.estimatefaster.audioforge.core.settings.AppSettings
import com.estimatefaster.audioforge.data.db.AppDatabase
import com.estimatefaster.audioforge.data.db.CharacterTuningEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * `character_tuning.key` 的稳定 wire 值（执行计划 §7.1 / §12.4 S09）。
 *
 * 只允许这三个 key；其他 key 视为未知并忽略，避免历史的脏数据影响 UI。
 */
object TuningKeys {
    const val SPEED = "speed"
    const val VOLUME = "volume"
    const val PITCH = "pitch"

    val ALL: List<String> = listOf(SPEED, VOLUME, PITCH)
}

/**
 * 角色微调的合法范围（P5-06）。
 *
 * 注意：任务约束里音量是 `0..2`；全局设置的 `SettingsValidator` 目前仍用
 * `0.5..2.0` 校验 `AppSettings.defaultVolume`。两者是不同层（角色覆盖 vs 全局默认），
 * 本任务不改 `core/**`，因此这里按 P5-06 的 0..2 执行。
 */
object TuningRanges {
    val SPEED: ClosedFloatingPointRange<Double> = 0.5..2.0
    val VOLUME: ClosedFloatingPointRange<Double> = 0.0..2.0
    val PITCH: ClosedFloatingPointRange<Double> = -12.0..12.0
}

/**
 * 单个角色的微调投影。
 *
 * `null` 表示该 key 没有显式值（跟随全局默认），和数据库里“没有这一行”等价；
 * 三个字段都为 null 即整个角色都跟随全局，[isFollowingGlobal] 用于 UI 提示。
 */
data class CharacterTuning(
    val roleId: String,
    val speed: Double? = null,
    val volume: Double? = null,
    val pitch: Double? = null,
) {
    val isFollowingGlobal: Boolean
        get() = speed == null && volume == null && pitch == null
}

/** 全局默认 TTS 参数；主要给 UI 展示“跟随全局”时的数值。 */
data class TuningDefaults(
    val speed: Double = 1.0,
    val volume: Double = 1.0,
    val pitch: Double = 0.0,
) {
    companion object {
        val DEFAULT: TuningDefaults = from(AppSettings.DEFAULT)

        /** 从全局设置投影；`SettingsRepository.settings` 的当前值由调用方注入。 */
        fun from(settings: AppSettings): TuningDefaults = TuningDefaults(
            speed = settings.defaultSpeed,
            volume = settings.defaultVolume,
            pitch = settings.defaultPitch,
        )
    }
}

/**
 * 角色微调读写端口。
 *
 * 抽成接口是为了让 `TuningViewModel` 的纯 JVM 测试注入记录型 fake，
 * 不需要 Robolectric/Room；生产实现是 [TuningRepository]。
 */
interface TuningStore {

    /** 订阅整本书的微调（按 roleId 聚合）。 */
    fun observe(novelId: Long): Flow<List<CharacterTuning>>

    /** 读单个角色；没有微调行时返回 null。 */
    suspend fun get(novelId: Long, roleId: String): CharacterTuning?

    /**
     * 整体替换单个角色的微调。
     *
     * - `null` 表示清除该 key（跟随全局）；
     * - 三个 key 都会先删除再按非 null 值写入，所以“没传的 key”也会被清除；
     * - 越界/NaN/Infinity 抛 [IllegalArgumentException]，不写库。
     */
    suspend fun set(
        novelId: Long,
        roleId: String,
        speed: Double? = null,
        volume: Double? = null,
        pitch: Double? = null,
    )

    /** 清除单个角色的全部微调；返回是否真的删除了行。 */
    suspend fun resetRole(novelId: Long, roleId: String): Boolean

    /** 全局默认（来自 [AppSettings]，可注入）。 */
    suspend fun defaults(): TuningDefaults
}

/**
 * 角色微调仓库（P5-06 / 执行计划 §12.4 S09、§15 P5-06）。
 *
 * 数据流：
 * ```text
 * character_tuning(key/value) ──decode──> CharacterTuning(roleId, speed?, volume?, pitch?)
 * TuningStore.set ──validate──> deleteRole + upsertAll（单事务）
 * SettingsRepository.settings ──inject──> TuningRepository.defaults()
 * ```
 *
 * 关键语义：
 * - **范围校验在写库前**：speed 0.5..2.0、volume 0..2、pitch -12..12；
 * - **null = 清除**：`set` 是整体替换，UI 的“恢复默认”就是传三个 null；
 * - **读时容错**：坏值（非数字/NaN/越界/未知 key）视为未设置，绝不抛异常；
 * - **未知 roleId**：`character_tuning` 只对 `novels` 有外键，未知角色仍可写，
 *   由 UI 决定是否提示；仓库层不做角色存在性校验。
 *
 * @param db Room 数据库；`character_tuning` 表由 P2-01 建好，本任务不改 schema
 * @param settingsProvider 全局默认来源；生产环境注入
 *   `{ settingsRepository.settings.first() }`，测试可传固定 [AppSettings]
 */
class TuningRepository(
    private val db: AppDatabase,
    private val settingsProvider: suspend () -> AppSettings = { AppSettings.DEFAULT },
) : TuningStore {

    private val dao = db.tuningDao()

    override fun observe(novelId: Long): Flow<List<CharacterTuning>> =
        dao.observeByNovel(novelId).map(::decodeAll)

    override suspend fun get(novelId: Long, roleId: String): CharacterTuning? {
        val rows = dao.listByRole(novelId, roleId)
        return if (rows.isEmpty()) null else decode(roleId, rows)
    }

    override suspend fun set(
        novelId: Long,
        roleId: String,
        speed: Double?,
        volume: Double?,
        pitch: Double?,
    ) {
        require(novelId > 0) { "novelId 必须为正数：$novelId" }
        require(roleId.isNotBlank()) { "roleId 不能为空" }
        requireInRange(TuningKeys.SPEED, speed, TuningRanges.SPEED)
        requireInRange(TuningKeys.VOLUME, volume, TuningRanges.VOLUME)
        requireInRange(TuningKeys.PITCH, pitch, TuningRanges.PITCH)

        val rows = listOfNotNull(
            speed?.let { entity(novelId, roleId, TuningKeys.SPEED, it) },
            volume?.let { entity(novelId, roleId, TuningKeys.VOLUME, it) },
            pitch?.let { entity(novelId, roleId, TuningKeys.PITCH, it) },
        )
        db.withTransaction {
            // 整体替换：先清掉旧 key，再写当前非 null 的三行（全 null 即纯清除）。
            dao.deleteRole(novelId, roleId)
            if (rows.isNotEmpty()) dao.upsertAll(rows)
        }
    }

    override suspend fun resetRole(novelId: Long, roleId: String): Boolean {
        require(novelId > 0) { "novelId 必须为正数：$novelId" }
        require(roleId.isNotBlank()) { "roleId 不能为空" }
        return dao.deleteRole(novelId, roleId) > 0
    }

    override suspend fun defaults(): TuningDefaults = TuningDefaults.from(settingsProvider())

    // ------------------------------------------------------------------
    // 编码/解码
    // ------------------------------------------------------------------

    private fun entity(
        novelId: Long,
        roleId: String,
        key: String,
        value: Double,
    ): CharacterTuningEntity = CharacterTuningEntity(
        novelId = novelId,
        roleId = roleId,
        key = key,
        value = value.toString(),
    )

    private fun decodeAll(rows: List<CharacterTuningEntity>): List<CharacterTuning> =
        rows.groupBy { it.roleId }
            .map { (roleId, roleRows) -> decode(roleId, roleRows) }
            .sortedBy { it.roleId }

    /**
     * 把同一角色的若干行合并为 [CharacterTuning]。
     *
     * 坏值/未知 key 静默忽略（视为跟随全局），与 `SettingsValidator` 的容错一致；
     * 同一 key 出现多行理论上被复合主键阻止，这里仍以最后一行覆盖作为防御。
     */
    private fun decode(roleId: String, rows: List<CharacterTuningEntity>): CharacterTuning {
        var speed: Double? = null
        var volume: Double? = null
        var pitch: Double? = null
        rows.forEach { row ->
            val value = parseValue(row.key, row.value) ?: return@forEach
            when (row.key) {
                TuningKeys.SPEED -> speed = value
                TuningKeys.VOLUME -> volume = value
                TuningKeys.PITCH -> pitch = value
            }
        }
        return CharacterTuning(
            roleId = roleId,
            speed = speed,
            volume = volume,
            pitch = pitch,
        )
    }

    private fun parseValue(key: String, raw: String): Double? {
        val range = rangeOf(key) ?: return null
        return raw.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it in range }
    }

    private fun rangeOf(key: String): ClosedFloatingPointRange<Double>? = when (key) {
        TuningKeys.SPEED -> TuningRanges.SPEED
        TuningKeys.VOLUME -> TuningRanges.VOLUME
        TuningKeys.PITCH -> TuningRanges.PITCH
        else -> null
    }

    private fun requireInRange(
        key: String,
        value: Double?,
        range: ClosedFloatingPointRange<Double>,
    ) {
        if (value == null) return
        require(value.isFinite() && value in range) {
            "$key 超出范围 ${range.start}..${range.endInclusive}：$value"
        }
    }
}
