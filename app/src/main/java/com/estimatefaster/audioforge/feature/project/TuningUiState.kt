package com.estimatefaster.audioforge.feature.project

import com.estimatefaster.audioforge.data.repository.CharacterTuning
import com.estimatefaster.audioforge.data.repository.TuningDefaults
import com.estimatefaster.audioforge.data.repository.TuningRanges
import java.util.Locale
import kotlin.math.round

/** 三个可调参数；顺序与 §12.4 S09 的“语速/音量/音调”一致。 */
enum class TuningField {
    Speed,
    Volume,
    Pitch,
}

/** 滑杆的最小步进；只影响 UI 输入精度，不影响仓库层范围校验。 */
object TuningSteps {
    const val SPEED: Double = 0.05
    const val VOLUME: Double = 0.05
    const val PITCH: Double = 0.5
}

/**
 * 微调草稿。
 *
 * 每个字段都是可空的：`null` 表示“跟随全局默认”（保存时清除该 key），
 * 非 null 才是显式覆盖。这样 UI 不需要额外的“留空”开关，滑杆始终有数值可显示。
 */
data class TuningDraft(
    val speed: Double? = null,
    val volume: Double? = null,
    val pitch: Double? = null,
) {

    val followsGlobal: Boolean
        get() = speed == null && volume == null && pitch == null

    fun valueOf(field: TuningField): Double? = when (field) {
        TuningField.Speed -> speed
        TuningField.Volume -> volume
        TuningField.Pitch -> pitch
    }

    fun with(field: TuningField, value: Double?): TuningDraft = when (field) {
        TuningField.Speed -> copy(speed = value)
        TuningField.Volume -> copy(volume = value)
        TuningField.Pitch -> copy(pitch = value)
    }

    companion object {
        /** 仓库投影 → 草稿；null（没有微调行）得到“全部跟随全局”。 */
        fun from(tuning: CharacterTuning?): TuningDraft = TuningDraft(
            speed = tuning?.speed,
            volume = tuning?.volume,
            pitch = tuning?.pitch,
        )
    }
}

/**
 * 角色微调 Sheet 的不可变 UI 状态（P5-06 / 执行计划 §12.4 S09）。
 *
 * 所有派生值都是纯函数：滑杆边界、脏标记、保存禁用、重置都能直接用 JUnit 覆盖，
 * 不依赖 Android/Room/协程。
 *
 * 语义：
 * - [draft] 是用户当前编辑值（null = 跟随全局）；[initial] 是载入/上次保存后的值；
 * - [isDirty] 只比较两者，所以“恢复默认”在本来就没有微调时不会启用保存；
 * - [canSave] 同时要求“有改动 + 范围内 + 未在保存 + 没有加载错误”；
 * - [speed]/[volume]/[pitch] 是滑杆显示值：显式值优先，否则回退 [defaults]。
 *
 * @param unknownRole 角色表里找不到 roleId；UI 仍允许保存（表只对 novels 有外键）
 * @param loadError 载入失败原因；非 null 时禁用保存并显示重试
 * @param saveError 保存失败原因；草稿保留，用户可直接重试
 */
data class TuningUiState(
    val novelId: Long = 0,
    val roleId: String = "",
    val roleName: String = "",
    val voiceLabel: String? = null,
    val defaults: TuningDefaults = TuningDefaults.DEFAULT,
    val initial: TuningDraft = TuningDraft(),
    val draft: TuningDraft = TuningDraft(),
    val loading: Boolean = true,
    val loadError: String? = null,
    val saving: Boolean = false,
    val saveError: String? = null,
    val unknownRole: Boolean = false,
) {

    /** 滑杆显示值；显式值优先，否则跟随全局默认。 */
    val speed: Double get() = draft.speed ?: defaults.speed

    val volume: Double get() = draft.volume ?: defaults.volume

    val pitch: Double get() = draft.pitch ?: defaults.pitch

    /** 标题用展示名；VM 通常已回退到 roleId。 */
    val displayName: String
        get() = roleName.ifBlank { roleId }

    val isDirty: Boolean
        get() = draft != initial

    /** 必填范围内校验；空（跟随全局）不算错误。 */
    val validationErrors: Set<TuningField>
        get() = buildSet {
            draft.speed?.let { if (!it.isInRange(TuningRanges.SPEED)) add(TuningField.Speed) }
            draft.volume?.let { if (!it.isInRange(TuningRanges.VOLUME)) add(TuningField.Volume) }
            draft.pitch?.let { if (!it.isInRange(TuningRanges.PITCH)) add(TuningField.Pitch) }
        }

    val isValid: Boolean
        get() = validationErrors.isEmpty()

    val canSave: Boolean
        get() = !loading && loadError == null && !saving && isValid && isDirty

    /** 已经全部跟随全局时不再需要“恢复默认”。 */
    val canReset: Boolean
        get() = !loading && loadError == null && !saving && !draft.followsGlobal

    fun followsGlobal(field: TuningField): Boolean = draft.valueOf(field) == null

    /** 滑杆输入；同时清掉上一次保存错误，让重试状态干净。 */
    fun withValue(field: TuningField, value: Double?): TuningUiState =
        copy(draft = draft.with(field, value), saveError = null)

    /** “恢复默认”：三个字段都清成 null，保存时删除微调行、跟随全局。 */
    fun resetToDefaults(): TuningUiState =
        copy(draft = TuningDraft(), saveError = null)
}

/** `1.25` / `+3.0` 这类展示值；固定 Locale.US，避免小数点受系统语言影响。 */
fun formatTuningValue(field: TuningField, value: Double): String = when (field) {
    TuningField.Speed, TuningField.Volume -> String.format(Locale.US, "%.2f", value)
    TuningField.Pitch -> String.format(Locale.US, "%+.1f", value)
}

/**
 * 把滑杆的连续浮点值吸附到步进，避免库里出现 `0.30000000000000004`。
 *
 * 吸附后再按显示精度归一（速度/音量 2 位、音调 1 位），结果可直接写入数据库。
 */
fun snapTuningValue(field: TuningField, value: Double): Double {
    if (!value.isFinite()) return value
    val anchor = when (field) {
        TuningField.Speed -> TuningRanges.SPEED.start
        TuningField.Volume -> TuningRanges.VOLUME.start
        TuningField.Pitch -> TuningRanges.PITCH.start
    }
    val step = when (field) {
        TuningField.Speed -> TuningSteps.SPEED
        TuningField.Volume -> TuningSteps.VOLUME
        TuningField.Pitch -> TuningSteps.PITCH
    }
    val snapped = anchor + round((value - anchor) / step) * step
    return when (field) {
        TuningField.Speed, TuningField.Volume -> round(snapped * 100) / 100
        TuningField.Pitch -> round(snapped * 10) / 10
    }
}

private fun Double.isInRange(range: ClosedFloatingPointRange<Double>): Boolean =
    isFinite() && this in range
