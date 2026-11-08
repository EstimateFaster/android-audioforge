package com.estimatefaster.audioforge.domain.jobs

import androidx.work.NetworkType
import com.estimatefaster.audioforge.core.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3-06：约束判定矩阵（纯 JVM，无 Robolectric/WorkManager/设备依赖）。
 *
 * 覆盖网络/充电/电量阈值边界、热等级五档、组合原因累积与 hold 优先，以及
 * [toWorkConstraints] 的 network/charging 映射。
 */
class JobConstraintsTest {

    // ------------------------------------------------------------ 全部满足

    @Test
    fun all_constraints_satisfied_allows_with_requested_concurrency() {
        val decision = evaluate(
            params(concurrency = 4),
            settings(wifiOnly = true, chargingOnly = true, ttsConcurrency = 8),
            device(network = NetworkKind.WIFI, charging = true, battery = 80),
        )

        assertTrue(decision.allow)
        assertFalse(decision.reduceConcurrency)
        assertEquals(4, decision.concurrency)
        assertTrue(decision.reasons.isEmpty())
        assertNull(decision.holdMessage)
    }

    // ------------------------------------------------------------ 网络

    @Test
    fun wifi_only_holds_on_cellular() {
        val decision = evaluate(
            params(),
            settings(wifiOnly = true),
            device(network = NetworkKind.CELLULAR),
        )

        assertFalse(decision.allow)
        assertEquals(0, decision.concurrency)
        assertFalse(decision.reduceConcurrency)
        assertEquals(1, decision.reasons.size)
        assertEquals(decision.reasons.single(), decision.holdMessage)
        assertTrue(decision.holdMessage!!.contains("Wi-Fi"))
    }

    @Test
    fun wifi_only_allows_on_wifi() {
        val decision = evaluate(
            params(),
            settings(wifiOnly = true),
            device(network = NetworkKind.WIFI),
        )

        assertTrue(decision.allow)
        assertEquals(4, decision.concurrency)
        assertNull(decision.holdMessage)
    }

    @Test
    fun cellular_is_allowed_when_wifi_only_is_off() {
        val decision = evaluate(
            params(),
            settings(wifiOnly = false),
            device(network = NetworkKind.CELLULAR),
        )

        assertTrue(decision.allow)
        assertEquals(4, decision.concurrency)
        assertTrue(decision.reasons.isEmpty())
    }

    @Test
    fun no_network_holds_regardless_of_wifi_only() {
        val withWifiOnly = evaluate(
            params(),
            settings(wifiOnly = true),
            device(network = NetworkKind.NONE),
        )
        val withoutWifiOnly = evaluate(
            params(),
            settings(wifiOnly = false),
            device(network = NetworkKind.NONE),
        )

        assertFalse(withWifiOnly.allow)
        assertFalse(withoutWifiOnly.allow)
        assertNotNull(withWifiOnly.holdMessage)
        assertNotNull(withoutWifiOnly.holdMessage)
        assertEquals(0, withWifiOnly.concurrency)
        assertEquals(0, withoutWifiOnly.concurrency)
    }

    // ------------------------------------------------------------ 充电

    @Test
    fun charging_only_holds_when_not_charging() {
        val decision = evaluate(
            params(),
            settings(chargingOnly = true),
            device(charging = false),
        )

        assertFalse(decision.allow)
        assertEquals(0, decision.concurrency)
        assertNotNull(decision.holdMessage)
        assertTrue(decision.holdMessage!!.contains("充电"))
    }

    @Test
    fun charging_only_allows_when_charging() {
        val decision = evaluate(
            params(),
            settings(chargingOnly = true),
            device(charging = true),
        )

        assertTrue(decision.allow)
        assertEquals(4, decision.concurrency)
    }

    @Test
    fun unplugged_is_allowed_when_charging_only_is_off() {
        val decision = evaluate(
            params(),
            settings(chargingOnly = false),
            device(charging = false),
        )

        assertTrue(decision.allow)
        assertEquals(4, decision.concurrency)
    }

    // ------------------------------------------------------------ 电量阈值（等于允许、低于 hold）

    @Test
    fun battery_equal_to_threshold_allows() {
        val decision = evaluate(
            params(),
            settings(batteryThreshold = 15),
            device(battery = 15),
        )

        assertTrue(decision.allow)
        assertEquals(4, decision.concurrency)
        assertNull(decision.holdMessage)
    }

    @Test
    fun battery_below_threshold_holds() {
        val decision = evaluate(
            params(),
            settings(batteryThreshold = 15),
            device(battery = 14),
        )

        assertFalse(decision.allow)
        assertEquals(0, decision.concurrency)
        assertNotNull(decision.holdMessage)
        assertTrue(decision.holdMessage!!.contains("14%"))
        assertTrue(decision.holdMessage.contains("15%"))
    }

    @Test
    fun battery_above_threshold_allows() {
        val decision = evaluate(
            params(),
            settings(batteryThreshold = 15),
            device(battery = 16),
        )

        assertTrue(decision.allow)
    }

    @Test
    fun battery_boundary_uses_settings_threshold_and_strict_comparison() {
        val at = evaluate(
            params(),
            settings(batteryThreshold = 50),
            device(battery = 50),
        )
        val below = evaluate(
            params(),
            settings(batteryThreshold = 50),
            device(battery = 49),
        )

        assertTrue(at.allow)
        assertFalse(below.allow)
    }

    @Test
    fun zero_threshold_never_holds_on_battery() {
        val decision = evaluate(
            params(),
            settings(batteryThreshold = 0),
            device(battery = 0),
        )

        assertTrue(decision.allow)
    }

    @Test
    fun out_of_range_battery_is_clamped() {
        val negative = evaluate(
            params(),
            settings(batteryThreshold = 15),
            device(battery = -5),
        )
        val tooHigh = evaluate(
            params(),
            settings(batteryThreshold = 15),
            device(battery = 150),
        )

        assertFalse(negative.allow)
        assertTrue(negative.holdMessage!!.contains("0%"))
        assertTrue(tooHigh.allow)
    }

    // ------------------------------------------------------------ 热等级（五档 + 下限）

    @Test
    fun thermal_none_allows_at_full_concurrency() {
        val decision = evaluate(
            params(concurrency = 4),
            settings(ttsConcurrency = 8),
            device(thermal = ThermalLevel.NONE),
        )

        assertTrue(decision.allow)
        assertFalse(decision.reduceConcurrency)
        assertEquals(4, decision.concurrency)
        assertTrue(decision.reasons.isEmpty())
    }

    @Test
    fun thermal_light_reduces_by_one() {
        val decision = evaluate(
            params(concurrency = 4),
            settings(ttsConcurrency = 8),
            device(thermal = ThermalLevel.LIGHT),
        )

        assertTrue(decision.allow)
        assertTrue(decision.reduceConcurrency)
        assertEquals(3, decision.concurrency)
        assertEquals(1, decision.reasons.size)
        assertNull(decision.holdMessage)
    }

    @Test
    fun thermal_moderate_reduces_by_one() {
        val decision = evaluate(
            params(concurrency = 4),
            settings(ttsConcurrency = 8),
            device(thermal = ThermalLevel.MODERATE),
        )

        assertTrue(decision.allow)
        assertTrue(decision.reduceConcurrency)
        assertEquals(3, decision.concurrency)
        assertTrue(decision.reasons.isNotEmpty())
    }

    @Test
    fun thermal_severe_halves_concurrency() {
        val decision = evaluate(
            params(concurrency = 4),
            settings(ttsConcurrency = 8),
            device(thermal = ThermalLevel.SEVERE),
        )

        assertTrue(decision.allow)
        assertTrue(decision.reduceConcurrency)
        assertEquals(2, decision.concurrency)
        assertTrue(decision.reasons.isNotEmpty())
    }

    @Test
    fun thermal_critical_holds() {
        val decision = evaluate(
            params(concurrency = 4),
            settings(ttsConcurrency = 8),
            device(thermal = ThermalLevel.CRITICAL),
        )

        assertFalse(decision.allow)
        assertFalse(decision.reduceConcurrency)
        assertEquals(0, decision.concurrency)
        assertNotNull(decision.holdMessage)
        assertTrue(decision.holdMessage!!.contains("过热"))
    }

    @Test
    fun thermal_reduction_floors_at_one() {
        val light = evaluate(
            params(concurrency = 2),
            settings(ttsConcurrency = 8),
            device(thermal = ThermalLevel.LIGHT),
        )
        val severe = evaluate(
            params(concurrency = 2),
            settings(ttsConcurrency = 8),
            device(thermal = ThermalLevel.SEVERE),
        )
        val alreadyOne = evaluate(
            params(concurrency = 1),
            settings(ttsConcurrency = 8),
            device(thermal = ThermalLevel.SEVERE),
        )

        assertEquals(1, light.concurrency)
        assertTrue(light.reduceConcurrency)
        assertEquals(1, severe.concurrency)
        assertTrue(severe.reduceConcurrency)
        // 已在最低并发：允许继续，但没有任何可降的，不算降级。
        assertTrue(alreadyOne.allow)
        assertEquals(1, alreadyOne.concurrency)
        assertFalse(alreadyOne.reduceConcurrency)
        assertTrue(alreadyOne.reasons.isEmpty())
    }

    @Test
    fun thermal_throttle_disabled_keeps_concurrency() {
        listOf(ThermalLevel.LIGHT, ThermalLevel.MODERATE, ThermalLevel.SEVERE).forEach { level ->
            val decision = evaluate(
                params(concurrency = 4),
                settings(ttsConcurrency = 8, thermalThrottle = false),
                device(thermal = level),
            )

            assertTrue("$level 应允许", decision.allow)
            assertFalse("$level 不应降并发", decision.reduceConcurrency)
            assertEquals(4, decision.concurrency)
            assertTrue(decision.reasons.isEmpty())
        }
    }

    @Test
    fun thermal_critical_holds_even_when_throttle_is_disabled() {
        val decision = evaluate(
            params(concurrency = 4),
            settings(ttsConcurrency = 8, thermalThrottle = false),
            device(thermal = ThermalLevel.CRITICAL),
        )

        assertFalse(decision.allow)
        assertEquals(0, decision.concurrency)
        assertNotNull(decision.holdMessage)
    }

    // ------------------------------------------------------------ TTS 并发上限

    @Test
    fun allow_caps_concurrency_to_tts_setting() {
        val decision = evaluate(
            params(concurrency = 8),
            settings(ttsConcurrency = 4),
            device(),
        )

        assertTrue(decision.allow)
        assertEquals(4, decision.concurrency)
        // 上限是正常归一化，不算「降级」。
        assertFalse(decision.reduceConcurrency)
    }

    @Test
    fun thermal_reduction_applies_after_tts_cap() {
        val decision = evaluate(
            params(concurrency = 8),
            settings(ttsConcurrency = 4),
            device(thermal = ThermalLevel.SEVERE),
        )

        assertTrue(decision.allow)
        assertEquals(2, decision.concurrency)
        assertTrue(decision.reduceConcurrency)
    }

    // ------------------------------------------------------------ 组合：原因累积、hold 优先

    @Test
    fun reasons_accumulate_in_fixed_order_and_hold_message_is_first() {
        val decision = evaluate(
            params(concurrency = 4),
            settings(
                wifiOnly = true,
                chargingOnly = true,
                batteryThreshold = 50,
                ttsConcurrency = 8,
            ),
            device(
                network = NetworkKind.CELLULAR,
                charging = false,
                battery = 10,
                thermal = ThermalLevel.CRITICAL,
            ),
        )

        assertFalse(decision.allow)
        assertEquals(0, decision.concurrency)
        assertFalse(decision.reduceConcurrency)
        assertEquals(4, decision.reasons.size)
        assertEquals(decision.reasons.first(), decision.holdMessage)
        // 顺序：网络 → 充电 → 电量 → 热。
        assertTrue(decision.reasons[0].contains("Wi-Fi"))
        assertTrue(decision.reasons[1].contains("充电"))
        assertTrue(decision.reasons[2].contains("电量"))
        assertTrue(decision.reasons[3].contains("过热"))
        assertTrue(decision.reasons.all { it.isNotBlank() })
    }

    @Test
    fun hold_takes_priority_over_thermal_reduction() {
        val decision = evaluate(
            params(concurrency = 4),
            settings(wifiOnly = true, ttsConcurrency = 8),
            device(network = NetworkKind.CELLULAR, thermal = ThermalLevel.LIGHT),
        )

        assertFalse(decision.allow)
        assertEquals(0, decision.concurrency)
        assertFalse(decision.reduceConcurrency)
        assertEquals(1, decision.reasons.size)
        assertEquals(decision.reasons.single(), decision.holdMessage)
    }

    @Test
    fun single_hold_reason_is_reported_verbatim_as_hold_message() {
        val decision = evaluate(
            params(),
            settings(batteryThreshold = 30),
            device(battery = 29),
        )

        assertFalse(decision.allow)
        assertEquals(listOf(decision.holdMessage), decision.reasons)
        assertTrue(decision.holdMessage!!.contains("29%"))
    }

    // ------------------------------------------------------------ WorkManager 约束映射

    @Test
    fun to_work_constraints_maps_wifi_only_to_unmetered() {
        val constraints = toWorkConstraints(AppSettings.DEFAULT)

        assertEquals(NetworkType.UNMETERED, constraints.requiredNetworkType)
        assertFalse(constraints.requiresCharging())
        assertFalse(constraints.requiresBatteryNotLow())
        assertFalse(constraints.requiresStorageNotLow())
        assertFalse(constraints.requiresDeviceIdle())
    }

    @Test
    fun to_work_constraints_maps_charging_only_and_mobile_network() {
        val constraints = toWorkConstraints(
            settings(wifiOnly = false, chargingOnly = true),
        )

        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertTrue(constraints.requiresCharging())
    }

    // ------------------------------------------------------------ 测试夹具

    private fun settings(
        wifiOnly: Boolean = false,
        chargingOnly: Boolean = false,
        batteryThreshold: Int = 15,
        thermalThrottle: Boolean = true,
        ttsConcurrency: Int = 8,
    ): AppSettings = AppSettings(
        ttsConcurrency = ttsConcurrency,
        wifiOnly = wifiOnly,
        chargingOnly = chargingOnly,
        batteryPauseThreshold = batteryThreshold,
        thermalThrottle = thermalThrottle,
    )

    private fun params(concurrency: Int = 4): BatchParams = BatchParams(
        novelId = 1L,
        chapterIds = listOf(1L, 2L),
        mode = GenerationMode.Full.wire,
        concurrency = concurrency,
    )

    private fun device(
        network: NetworkKind = NetworkKind.WIFI,
        charging: Boolean = true,
        battery: Int = 80,
        thermal: ThermalLevel = ThermalLevel.NONE,
    ): DeviceSnapshot = DeviceSnapshot(
        network = network,
        charging = charging,
        batteryPercent = battery,
        thermal = thermal,
    )
}
