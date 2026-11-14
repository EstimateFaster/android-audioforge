package com.estimatefaster.audioforge.uipreview.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.estimatefaster.audioforge.uipreview.data.PlayerState
import com.estimatefaster.audioforge.uipreview.design.AppTopBar
import com.estimatefaster.audioforge.uipreview.design.RowDivider
import com.estimatefaster.audioforge.uipreview.design.SectionLabel
import com.estimatefaster.audioforge.uipreview.design.SwitchRow
import com.estimatefaster.audioforge.uipreview.icons.Ms
import com.estimatefaster.audioforge.uipreview.nav.AppScaffold
import com.estimatefaster.audioforge.uipreview.nav.Navigator
import kotlin.math.roundToInt

@Composable
fun NetworkScreen(navigator: Navigator, player: PlayerState) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var wifiOnly by rememberSaveable { mutableStateOf(true) }
    var chargingOnly by rememberSaveable { mutableStateOf(false) }
    var notify by rememberSaveable { mutableStateOf(true) }
    var thermal by rememberSaveable { mutableStateOf(true) }
    var batteryThreshold by rememberSaveable { mutableStateOf(15f) }

    AppScaffold(
        navigator = navigator,
        player = player,
        showNavBar = false,
        showMiniPlayer = false,
        topBar = {
            AppTopBar(
                title = "网络与设备",
                subtitle = "长任务的运行条件",
                scrollBehavior = scrollBehavior,
                onBack = { navigator.pop() },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item(key = "network") {
                SectionLabel(text = "网络")
                SwitchRow(
                    icon = Ms.Wifi,
                    title = "仅 Wi-Fi 下生成",
                    supporting = "移动网络下暂停队列，避免流量",
                    checked = wifiOnly,
                    onCheckedChange = { wifiOnly = it },
                )
                RowDivider()
            }
            item(key = "power") {
                SectionLabel(text = "电量与性能")
                SwitchRow(
                    icon = Ms.BatteryChargingFull,
                    title = "仅充电时生成",
                    supporting = "长任务建议插着电跑",
                    checked = chargingOnly,
                    onCheckedChange = { chargingOnly = it },
                )
                RowDivider()
                SwitchRow(
                    icon = Ms.Thermostat,
                    title = "过热时自动降级",
                    supporting = "降低并发，避免设备发烫",
                    checked = thermal,
                    onCheckedChange = { thermal = it },
                )
                RowDivider()
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("低于此电量暂停", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "${batteryThreshold.roundToInt()}%",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Slider(
                        value = batteryThreshold,
                        onValueChange = { batteryThreshold = (it / 5).roundToInt() * 5f },
                        valueRange = 0f..50f,
                        steps = 9,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item(key = "notify") {
                SectionLabel(text = "提醒")
                SwitchRow(
                    icon = Ms.Notifications,
                    title = "完成时通知",
                    supporting = "后台生成结束时提醒一次",
                    checked = notify,
                    onCheckedChange = { notify = it },
                )
            }
        }
    }
}
