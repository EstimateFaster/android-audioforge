package com.estimatefaster.audioforge.uipreview.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.estimatefaster.audioforge.uipreview.R
import com.estimatefaster.audioforge.uipreview.data.PlayerState
import com.estimatefaster.audioforge.uipreview.design.AppTopBar
import com.estimatefaster.audioforge.uipreview.design.ChoiceOption
import com.estimatefaster.audioforge.uipreview.design.GroupSurface
import com.estimatefaster.audioforge.uipreview.design.IconAction
import com.estimatefaster.audioforge.uipreview.design.NavRow
import com.estimatefaster.audioforge.uipreview.design.RowDivider
import com.estimatefaster.audioforge.uipreview.design.SegmentedChoice
import com.estimatefaster.audioforge.uipreview.design.SettingsGroup
import com.estimatefaster.audioforge.uipreview.design.SwitchRow
import com.estimatefaster.audioforge.uipreview.icons.Ms
import com.estimatefaster.audioforge.uipreview.lab.FontScaleOption
import com.estimatefaster.audioforge.uipreview.lab.LocalPreviewSettings
import com.estimatefaster.audioforge.uipreview.lab.ThemeMode
import com.estimatefaster.audioforge.uipreview.nav.AppScaffold
import com.estimatefaster.audioforge.uipreview.nav.Dest
import com.estimatefaster.audioforge.uipreview.nav.Navigator
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(navigator: Navigator, player: PlayerState) {
    val settings = LocalPreviewSettings.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var wifiOnly by remember { mutableStateOf(true) }
    var chargingOnly by remember { mutableStateOf(false) }
    var notify by remember { mutableStateOf(true) }

    fun previewNote(text: String) {
        scope.launch { snackbarHostState.showSnackbar(text) }
    }

    AppScaffold(
        navigator = navigator,
        player = player,
        showNavBar = true,
        showMiniPlayer = true,
        snackbarHostState = snackbarHostState,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.settings_title),
                subtitle = stringResource(R.string.settings_subtitle),
                scrollBehavior = scrollBehavior,
                actions = {
                    IconAction(
                        icon = Ms.Science,
                        description = stringResource(R.string.settings_lab),
                        onClick = { navigator.push(Dest.Lab) },
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        ) {
            item(key = "appearance") {
                SettingsGroup(
                    title = stringResource(R.string.settings_group_appearance),
                    spec = "SegmentedButtonRow · 40dp",
                ) {
                    GroupSurface {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.settings_theme),
                                style = MaterialTheme.typography.titleSmallEmphasized,
                            )
                            Spacer(Modifier.height(10.dp))
                            SegmentedChoice(
                                options = listOf(
                                    ChoiceOption(stringResource(R.string.settings_theme_system), Ms.Contrast),
                                    ChoiceOption(stringResource(R.string.settings_theme_light), Ms.LightMode),
                                    ChoiceOption(stringResource(R.string.settings_theme_dark), Ms.DarkMode),
                                ),
                                selectedIndex = settings.themeMode.ordinal,
                                onSelect = { settings.themeMode = ThemeMode.entries[it] },
                            )
                            Spacer(Modifier.height(14.dp))
                            Text(
                                text = stringResource(R.string.settings_font),
                                style = MaterialTheme.typography.titleSmallEmphasized,
                            )
                            Text(
                                text = stringResource(R.string.settings_font_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(10.dp))
                            SegmentedChoice(
                                options = listOf(
                                    ChoiceOption(stringResource(R.string.lab_font_100)),
                                    ChoiceOption(stringResource(R.string.lab_font_130)),
                                    ChoiceOption(stringResource(R.string.lab_font_200)),
                                ),
                                selectedIndex = settings.fontScale.ordinal,
                                onSelect = { settings.fontScale = FontScaleOption.entries[it] },
                            )
                        }
                        RowDivider(startIndent = 0.dp)
                        SwitchRow(
                            icon = Ms.Palette,
                            title = stringResource(R.string.settings_dynamic),
                            supporting = stringResource(R.string.settings_dynamic_desc),
                            checked = settings.dynamicColor,
                            onCheckedChange = { settings.dynamicColor = it },
                        )
                    }
                }
            }

            item(key = "model") {
                SettingsGroup(
                    title = stringResource(R.string.settings_group_model),
                    spec = "ListItem · 64dp + Key/StatusChip",
                ) {
                    GroupSurface {
                        NavRow(
                            icon = Ms.Memory,
                            title = stringResource(R.string.settings_llm),
                            supporting = stringResource(R.string.settings_llm_desc),
                            trailingText = "已配置",
                            onClick = { previewNote("预览构建：模型配置页在下一批交付") },
                        )
                        RowDivider()
                        NavRow(
                            icon = Ms.RecordVoiceOver,
                            title = stringResource(R.string.settings_tts),
                            supporting = stringResource(R.string.settings_tts_desc),
                            trailingText = "待配置",
                            onClick = { previewNote("预览构建：语音模型配置页在下一批交付") },
                        )
                        RowDivider()
                        NavRow(
                            icon = Ms.VpnKey,
                            title = "API Key",
                            supporting = "本机 Keystore 加密保存，可随时清除",
                            onClick = { previewNote("预览构建：Key 管理未接入") },
                        )
                    }
                }
            }

            item(key = "generation") {
                SettingsGroup(
                    title = stringResource(R.string.settings_group_generation),
                    spec = "ListItem · trailing value",
                ) {
                    GroupSurface {
                        NavRow(
                            icon = Ms.Speed,
                            title = stringResource(R.string.settings_concurrency),
                            supporting = "同时发起的请求数，过高容易被限流",
                            trailingText = "1 · 4",
                            onClick = { previewNote("预览构建：并发设置未接入") },
                        )
                        RowDivider()
                        NavRow(
                            icon = Ms.Segment,
                            title = stringResource(R.string.settings_segment),
                            supporting = "超过上限会按句切分，保证配音自然",
                            trailingText = "120 字",
                            onClick = { previewNote("预览构建：分段设置未接入") },
                        )
                        RowDivider()
                        NavRow(
                            icon = Ms.Timer,
                            title = stringResource(R.string.settings_pause),
                            supporting = "旁白与段落之间的静音长度",
                            trailingText = "350 / 700",
                            onClick = { previewNote("预览构建：停顿设置未接入") },
                        )
                    }
                }
            }

            item(key = "network") {
                SettingsGroup(
                    title = stringResource(R.string.settings_group_network),
                    spec = "Switch · 48dp touch target",
                ) {
                    GroupSurface {
                        SwitchRow(
                            icon = Ms.Wifi,
                            title = stringResource(R.string.settings_wifi),
                            supporting = stringResource(R.string.settings_wifi_desc),
                            checked = wifiOnly,
                            onCheckedChange = { wifiOnly = it },
                        )
                        RowDivider()
                        SwitchRow(
                            icon = Ms.BatteryChargingFull,
                            title = stringResource(R.string.settings_charging),
                            supporting = stringResource(R.string.settings_charging_desc),
                            checked = chargingOnly,
                            onCheckedChange = { chargingOnly = it },
                        )
                        RowDivider()
                        SwitchRow(
                            icon = Ms.Notifications,
                            title = stringResource(R.string.settings_notify),
                            supporting = stringResource(R.string.settings_notify_desc),
                            checked = notify,
                            onCheckedChange = { notify = it },
                        )
                    }
                }
            }

            item(key = "data") {
                SettingsGroup(title = stringResource(R.string.settings_group_data)) {
                    GroupSurface {
                        NavRow(
                            icon = Ms.Storage,
                            title = stringResource(R.string.settings_storage),
                            supporting = stringResource(R.string.settings_storage_desc),
                            onClick = { previewNote("预览构建：存储管理在下一批交付") },
                        )
                        RowDivider()
                        NavRow(
                            icon = Ms.Backup,
                            title = stringResource(R.string.settings_backup),
                            supporting = stringResource(R.string.settings_backup_desc),
                            onClick = { previewNote("预览构建：备份导出未接入") },
                        )
                    }
                }
            }

            item(key = "about") {
                SettingsGroup(title = stringResource(R.string.settings_group_about)) {
                    GroupSurface {
                        NavRow(
                            icon = Ms.Info,
                            title = stringResource(R.string.settings_about),
                            supporting = stringResource(R.string.settings_about_desc),
                            onClick = { previewNote("预览构建：关于页在下一批交付") },
                        )
                        RowDivider()
                        NavRow(
                            icon = Ms.Code,
                            title = stringResource(R.string.settings_licenses),
                            supporting = stringResource(R.string.settings_licenses_desc),
                            onClick = { previewNote("预览构建：许可页在下一批交付") },
                        )
                        RowDivider()
                        NavRow(
                            icon = Ms.Science,
                            title = stringResource(R.string.settings_lab),
                            supporting = stringResource(R.string.settings_lab_hint),
                            onClick = { navigator.push(Dest.Lab) },
                        )
                        RowDivider()
                        NavRow(
                            icon = Ms.Verified,
                            title = stringResource(R.string.settings_version),
                            supporting = stringResource(R.string.settings_version_value),
                            showChevron = false,
                            onClick = null,
                        )
                    }
                }
            }
        }
    }
}
