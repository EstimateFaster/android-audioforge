package com.estimatefaster.audioforge.domain.jobs

import androidx.work.Constraints
import androidx.work.NetworkType
import com.estimatefaster.audioforge.core.settings.AppSettings

/** 当前网络类型（§11.2 约束）。`NONE` 表示不可用，`CELLULAR` 在仅 Wi-Fi 时会被 hold。 */
enum class NetworkKind {
    NONE,
    WIFI,
    CELLULAR,
}

/**
 * 设备热状态，映射 Android `PowerManager.THERMAL_STATUS_*`（§11.2 / §15 P3-06）。
 *
 * 这是纯 JVM 枚举：Android 适配层负责把 `getCurrentThermalStatus()` 映射进来，
 * 判定逻辑与测试因此都不需要 `Context` 或 Robolectric。
 */
enum class ThermalLevel {
    NONE,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
}

/**
 * 一次约束判定所需的设备快照（§15 P3-06）。
 *
 * [batteryPercent] 允许调用方传入任意 Int（例如电量未知时传 -1），[evaluate] 会先
 * 收敛到 0..100 再比较，避免脏值把阈值判断弄反。
 */
data class DeviceSnapshot(
    val network: NetworkKind,
    val charging: Boolean,
    val batteryPercent: Int,
    val thermal: ThermalLevel,
)

/**
 * 约束判定结果（§15 P3-06）。`reasons` / [holdMessage] 都是中文，UI 可直接展示。
 *
 * @property allow 是否允许继续生成；false 时调用方应暂停并由 WorkManager/前台提示等待
 * @property reduceConcurrency 是否因**过热**实际降低了并发；仅 [allow] 为 true 时有意义。
 *   TTS 并发上限属于正常归一化，不计入此标记
 * @property concurrency 实际使用的并发数；[allow] 为 false 时为 0（不要启动任何请求）
 * @property reasons 命中的全部原因。hold 时是全部 hold 原因（按网络 → 充电 → 电量 →
 *   热的固定顺序）；allow 时只有热降级原因，可能为空
 * @property holdMessage 第一条 hold 文案；[allow] 为 true 时为 null
 */
data class ConstraintDecision(
    val allow: Boolean,
    val reduceConcurrency: Boolean,
    val concurrency: Int,
    val reasons: List<String>,
    val holdMessage: String?,
)

/**
 * 判定当前设备是否允许继续跑批次，以及应该用多少并发（执行计划 §11.2 / §15 P3-06）。
 *
 * ## 规则（hold 优先于降并发）
 * 1. 网络：`NONE` → hold（TTS/LLM 都需要网络）；`wifi_only` 且 `CELLULAR` → hold。
 * 2. 充电：`charging_only` 且未充电 → hold。
 * 3. 电量：`batteryPercent < batteryPauseThreshold` → hold；**等于阈值允许**（只有低于才暂停）。
 * 4. 热（仅在 `thermalThrottle=true` 时降并发）：
 *    - `LIGHT` / `MODERATE`：并发减 1，下限 1；
 *    - `SEVERE`：并发减半，下限 1；
 *    - `CRITICAL`：hold。**无论 `thermalThrottle` 是否开启**——关键温度下系统随时会杀进程，
 *      暂停比继续更安全，且任务可恢复。
 * 5. 基础并发 = `params.concurrency`，上限为 `settings.ttsConcurrency`，下限 1；热降级
 *    作用在这个已经归一化的值上。
 *
 * 多个条件同时命中时：全部 hold 原因按上面的顺序累积进 [ConstraintDecision.reasons]，
 * [ConstraintDecision.holdMessage] 取第一条；此时不再报告热降级（并发为 0，降级无意义）。
 *
 * 纯函数，不读系统状态；Android 侧负责采集 [DeviceSnapshot] 并调用本函数。
 */
fun evaluate(
    params: BatchParams,
    settings: AppSettings,
    device: DeviceSnapshot,
): ConstraintDecision {
    val battery = device.batteryPercent.coerceIn(MIN_BATTERY, MAX_BATTERY)
    val threshold = settings.batteryPauseThreshold.coerceIn(MIN_BATTERY, MAX_BATTERY)

    // 顺序固定：网络 → 充电 → 电量 → 热；holdMessage 取第一条。
    val holdReasons = buildList {
        when (device.network) {
            NetworkKind.NONE -> add(HOLD_NO_NETWORK)
            NetworkKind.CELLULAR -> if (settings.wifiOnly) add(HOLD_CELLULAR)
            NetworkKind.WIFI -> Unit
        }
        if (settings.chargingOnly && !device.charging) add(HOLD_NOT_CHARGING)
        if (battery < threshold) {
            add("电量 $battery% 低于阈值 $threshold%，已暂停生成")
        }
        if (device.thermal == ThermalLevel.CRITICAL) add(HOLD_THERMAL_CRITICAL)
    }
    if (holdReasons.isNotEmpty()) {
        return ConstraintDecision(
            allow = false,
            reduceConcurrency = false,
            concurrency = 0,
            reasons = holdReasons,
            holdMessage = holdReasons.first(),
        )
    }

    var concurrency = params.concurrency
        .coerceAtLeast(1)
        .coerceAtMost(settings.ttsConcurrency.coerceAtLeast(1))
    val reduceReasons = mutableListOf<String>()
    if (settings.thermalThrottle) {
        val reduced = when (device.thermal) {
            ThermalLevel.LIGHT, ThermalLevel.MODERATE -> (concurrency - 1).coerceAtLeast(1)
            ThermalLevel.SEVERE -> (concurrency / 2).coerceAtLeast(1)
            ThermalLevel.NONE, ThermalLevel.CRITICAL -> concurrency
        }
        if (reduced < concurrency) {
            reduceReasons +=
                "设备温度${thermalLabel(device.thermal)}，并发 $concurrency → $reduced"
            concurrency = reduced
        }
    }

    return ConstraintDecision(
        allow = true,
        reduceConcurrency = reduceReasons.isNotEmpty(),
        concurrency = concurrency,
        reasons = reduceReasons,
        holdMessage = null,
    )
}

/**
 * 把设置映射成 WorkManager 约束（§11.2 / §15 P3-06）。
 *
 * - `wifi_only` → `NetworkType.UNMETERED`（Wi-Fi/不计量网络），否则 `CONNECTED`；
 * - `charging_only` → `setRequiresCharging(true)`。
 *
 * 电量阈值和热状态**不**做成 Constraint：WorkManager 的 `requiresBatteryNotLow`
 * 不可配置阈值，而且电池回充、热状态变化需要立即降并发而不是只「等约束满足」，
 * 因此由 [evaluate] 在 worker 内检查（§11.2 明确要求）。
 */
fun toWorkConstraints(settings: AppSettings): Constraints {
    val networkType = if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
    return Constraints.Builder()
        .setRequiredNetworkType(networkType)
        .setRequiresCharging(settings.chargingOnly)
        .build()
}

private fun thermalLabel(level: ThermalLevel): String = when (level) {
    ThermalLevel.NONE -> "正常"
    ThermalLevel.LIGHT -> "轻微偏高"
    ThermalLevel.MODERATE -> "中等偏高"
    ThermalLevel.SEVERE -> "严重过热"
    ThermalLevel.CRITICAL -> "关键过热"
}

private const val MIN_BATTERY = 0
private const val MAX_BATTERY = 100
private const val HOLD_NO_NETWORK = "网络不可用，已暂停生成"
private const val HOLD_CELLULAR = "已开启仅 Wi-Fi，当前为蜂窝网络，已暂停生成"
private const val HOLD_NOT_CHARGING = "已开启仅充电时生成，当前未充电，已暂停生成"
private const val HOLD_THERMAL_CRITICAL = "设备过热（关键），已暂停生成，降温后可继续"
