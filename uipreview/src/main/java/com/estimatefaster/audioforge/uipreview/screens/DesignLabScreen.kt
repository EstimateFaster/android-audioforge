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
import com.estimatefaster.audioforge.uipreview.design.GroupSurface
import com.estimatefaster.audioforge.uipreview.design.RowDivider
import com.estimatefaster.audioforge.uipreview.design.SecondaryButton
import com.estimatefaster.audioforge.uipreview.design.SegmentedChoice
import com.estimatefaster.audioforge.uipreview.design.SettingsGroup
import com.estimatefaster.audioforge.uipreview.design.SwitchRow
import com.estimatefaster.audioforge.uipreview.icons.Ms
import com.estimatefaster.audioforge.uipreview.lab.FontScaleOption
import com.estimatefaster.audioforge.uipreview.lab.LocalPreviewSettings
import com.estimatefaster.audioforge.uipreview.lab.ScreenPhase
import com.estimatefaster.audioforge.uipreview.lab.ThemeMode
import com.estimatefaster.audioforge.uipreview.nav.AppScaffold
import com.estimatefaster.audioforge.uipreview.nav.Navigator

private val labPages = listOf(
    "library" to "书架",
    "project" to "项目详情",
    "chapters" to "章节",
    "script" to "剧本",
    "player" to "播放器",
)

@Composable
fun DesignLabScreen(navigator: Navigator, player: PlayerState) {
    val settings = LocalPreviewSettings.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    AppScaffold(
        navigator = navigator,
        player = player,
        showNavBar = false,
        showMiniPlayer = false,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.lab_title),
                subtitle = stringResource(R.string.lab_subtitle),
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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        ) {
            item(key = "theme") {
                SettingsGroup(title = stringResource(R.string.lab_theme)) {
                    GroupSurface {
                        Column(modifier = Modifier.padding(16.dp)) {
                            SegmentedChoice(
                                options = listOf(
                                    ChoiceOption(stringResource(R.string.settings_theme_system), Ms.Contrast),
                                    ChoiceOption(stringResource(R.string.settings_theme_light), Ms.LightMode),
                                    ChoiceOption(stringResource(R.string.settings_theme_dark), Ms.DarkMode),
                                ),
                                selectedIndex = settings.themeMode.ordinal,
                                onSelect = { settings.themeMode = ThemeMode.entries[it] },
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "字号只放大文字与行高，图标与触控区保持 48dp。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        RowDivider()
                        SwitchRow(
                            icon = Ms.Palette,
                            title = stringResource(R.string.lab_dynamic),
                            supporting = stringResource(R.string.settings_dynamic_desc),
                            checked = settings.dynamicColor,
                            onCheckedChange = { settings.dynamicColor = it },
                        )
                        RowDivider()
                        SwitchRow(
                            icon = Ms.Straighten,
                            title = stringResource(R.string.lab_specs),
                            supporting = stringResource(R.string.lab_specs_desc),
                            checked = settings.showSpecs,
                            onCheckedChange = { settings.showSpecs = it },
                        )
                    }
                }
            }

            item(key = "states") {
                SettingsGroup(
                    title = stringResource(R.string.lab_states),
                    spec = "SegmentedButtonRow × 4",
                ) {
                    GroupSurface {
                        Column {
                            labPages.forEachIndexed { index, (key, label) ->
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.titleSmallEmphasized,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    SegmentedChoice(
                                        options = listOf(
                                            ChoiceOption(stringResource(R.string.lab_phase_normal)),
                                            ChoiceOption(stringResource(R.string.lab_phase_loading)),
                                            ChoiceOption(stringResource(R.string.lab_phase_empty)),
                                            ChoiceOption(stringResource(R.string.lab_phase_error)),
                                        ),
                                        selectedIndex = settings.phase(key).ordinal,
                                        onSelect = { settings.setPhase(key, ScreenPhase.entries[it]) },
                                    )
                                }
                                if (index != labPages.lastIndex) RowDivider()
                            }
                        }
                    }
                }
            }

            item(key = "reset") {
                SecondaryButton(
                    text = stringResource(R.string.lab_reset),
                    icon = Ms.RestartAlt,
                    onClick = { settings.reset() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                )
            }

            item(key = "note") {
                Text(
                    text = stringResource(R.string.lab_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}
