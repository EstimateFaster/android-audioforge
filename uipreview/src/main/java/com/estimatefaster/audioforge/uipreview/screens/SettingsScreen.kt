package com.estimatefaster.audioforge.uipreview.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.estimatefaster.audioforge.uipreview.R
import com.estimatefaster.audioforge.uipreview.data.PlayerState
import com.estimatefaster.audioforge.uipreview.design.AppTopBar
import com.estimatefaster.audioforge.uipreview.design.ChoiceOption
import com.estimatefaster.audioforge.uipreview.design.ListRow
import com.estimatefaster.audioforge.uipreview.design.RowDivider
import com.estimatefaster.audioforge.uipreview.design.SectionLabel
import com.estimatefaster.audioforge.uipreview.design.SegmentedChoice
import com.estimatefaster.audioforge.uipreview.design.SwitchRow
import com.estimatefaster.audioforge.uipreview.icons.Ms
import com.estimatefaster.audioforge.uipreview.lab.FontScaleOption
import com.estimatefaster.audioforge.uipreview.lab.LocalPreviewSettings
import com.estimatefaster.audioforge.uipreview.lab.ThemeMode
import com.estimatefaster.audioforge.uipreview.nav.AppScaffold
import com.estimatefaster.audioforge.uipreview.nav.Dest
import com.estimatefaster.audioforge.uipreview.nav.Navigator

@Composable
fun SettingsScreen(navigator: Navigator, player: PlayerState) {
    val settings = LocalPreviewSettings.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    AppScaffold(
        navigator = navigator,
        player = player,
        showNavBar = true,
        showMiniPlayer = true,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.settings_title),
                subtitle = "本地优先 · 0.1.0-ui",
                scrollBehavior = scrollBehavior,
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
            item(key = "theme") {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.settings_theme),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    SegmentedChoice(
                        options = listOf(
                            ChoiceOption(stringResource(R.string.settings_theme_system)),
                            ChoiceOption(stringResource(R.string.settings_theme_light)),
                            ChoiceOption(stringResource(R.string.settings_theme_dark)),
                        ),
                        selectedIndex = settings.themeMode.ordinal,
                        onSelect = { settings.themeMode = ThemeMode.entries[it] },
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.settings_font),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
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
            }
            item(key = "dynamic") {
                SwitchRow(
                    icon = Ms.Palette,
                    title = stringResource(R.string.settings_dynamic),
                    supporting = stringResource(R.string.settings_dynamic_desc),
                    checked = settings.dynamicColor,
                    onCheckedChange = { settings.dynamicColor = it },
                )
            }
            item(key = "config-label") {
                SectionLabel(text = "配置", modifier = Modifier.padding(top = 8.dp))
            }
            item(key = "model") {
                ListRow(
                    icon = Ms.VpnKey,
                    title = stringResource(R.string.settings_llm),
                    subtitle = "文本 qwen3.7-flash · 语音待配置",
                    onClick = { navigator.push(Dest.ModelConfig) },
                )
                RowDivider()
            }
            item(key = "generation") {
                ListRow(
                    icon = Ms.Tune,
                    title = stringResource(R.string.settings_group_generation),
                    subtitle = "并发 1 / 4 · 分段 120 字 · 停顿 350 ms",
                    onClick = { navigator.push(Dest.Generation) },
                )
                RowDivider()
            }
            item(key = "network") {
                ListRow(
                    icon = Ms.Wifi,
                    title = stringResource(R.string.settings_group_network),
                    subtitle = "仅 Wi-Fi · 仅充电 · 完成通知",
                    onClick = { navigator.push(Dest.Network) },
                )
                RowDivider()
            }
            item(key = "storage") {
                ListRow(
                    icon = Ms.Storage,
                    title = stringResource(R.string.settings_storage),
                    subtitle = "已用 1.8 GB · 1,284 个文件",
                    onClick = { navigator.push(Dest.Storage) },
                )
                RowDivider()
            }
            item(key = "about") {
                ListRow(
                    icon = Ms.Info,
                    title = stringResource(R.string.settings_about),
                    subtitle = "版本 0.1.0-ui · 许可与诊断",
                    onClick = { navigator.push(Dest.About) },
                )
            }
            item(key = "preview-label") {
                SectionLabel(text = "预览工具", modifier = Modifier.padding(top = 8.dp))
            }
            item(key = "lab") {
                ListRow(
                    icon = Ms.Science,
                    title = stringResource(R.string.settings_lab),
                    subtitle = stringResource(R.string.settings_lab_desc),
                    onClick = { navigator.push(Dest.Lab) },
                )
                RowDivider()
            }
            item(key = "onboarding") {
                ListRow(
                    icon = Ms.RestartAlt,
                    title = "重新观看引导",
                    subtitle = "预览三步首次设置",
                    onClick = { navigator.push(Dest.Onboarding) },
                )
            }
        }
    }
}
