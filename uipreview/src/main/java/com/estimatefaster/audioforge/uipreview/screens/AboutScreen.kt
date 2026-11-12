package com.estimatefaster.audioforge.uipreview.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.estimatefaster.audioforge.uipreview.R
import com.estimatefaster.audioforge.uipreview.data.PlayerState
import com.estimatefaster.audioforge.uipreview.design.AppTopBar
import com.estimatefaster.audioforge.uipreview.design.ListRow
import com.estimatefaster.audioforge.uipreview.design.RowDivider
import com.estimatefaster.audioforge.uipreview.design.SecondaryButton
import com.estimatefaster.audioforge.uipreview.design.SectionLabel
import com.estimatefaster.audioforge.uipreview.icons.Ms
import com.estimatefaster.audioforge.uipreview.nav.AppScaffold
import com.estimatefaster.audioforge.uipreview.nav.Navigator
import kotlinx.coroutines.launch

@Composable
fun AboutScreen(navigator: Navigator, player: PlayerState) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    AppScaffold(
        navigator = navigator,
        player = player,
        showNavBar = false,
        showMiniPlayer = false,
        snackbarHostState = snackbarHostState,
        topBar = {
            AppTopBar(
                title = "关于",
                subtitle = "0.1.0-ui (1) · debug",
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
            item(key = "hero") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(MaterialTheme.shapes.extraLarge)
                            .clip(CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(Ms.AutoStories),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("AudioForge", style = MaterialTheme.typography.headlineSmallEmphasized)
                    Text(
                        text = "把小说变成多人演播的有声书。全部数据留在本机。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            item(key = "diag-label") {
                SectionLabel(text = "诊断", trailing = "刚刚")
            }
            item(key = "diag") {
                Column {
                    ListRow(
                        title = "Room",
                        subtitle = "2.8.5 · schema 1 · 8 张表",
                        icon = Ms.Database,
                        showChevron = false,
                        trailingText = "正常",
                    )
                    RowDivider()
                    ListRow(
                        title = "FFmpegKit",
                        subtitle = "8.1.9 · 音频拼接与响度",
                        icon = Ms.GraphicEq,
                        showChevron = false,
                        trailingText = "正常",
                    )
                    RowDivider()
                    ListRow(
                        title = "WorkManager",
                        subtitle = "2.12.0 · dataSync 前台服务",
                        icon = Ms.Sync,
                        showChevron = false,
                        trailingText = "正常",
                    )
                    RowDivider()
                    ListRow(
                        title = "存储",
                        subtitle = "可用 12.4 GB · 1,284 个文件",
                        icon = Ms.Storage,
                        showChevron = false,
                        trailingText = "正常",
                    )
                }
            }
            item(key = "license-label") {
                SectionLabel(text = "开源许可")
            }
            item(key = "license") {
                Column {
                    listOf(
                        "Jetpack Compose" to "AndroidX · Apache-2.0",
                        "Material 3" to "AndroidX · Apache-2.0",
                        "Kotlin & Coroutines" to "JetBrains · Apache-2.0",
                        "Material Symbols" to "Google · Apache-2.0",
                    ).forEach { (title, subtitle) ->
                        ListRow(
                            title = title,
                            subtitle = subtitle,
                            icon = Ms.Code,
                            showChevron = false,
                        )
                        RowDivider()
                    }
                }
            }
            item(key = "actions") {
                SecondaryButton(
                    text = "复制诊断信息",
                    icon = Ms.ContentCopy,
                    onClick = { scope.launch { snackbarHostState.showSnackbar("诊断信息已复制") } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                )
            }
        }
    }
}
