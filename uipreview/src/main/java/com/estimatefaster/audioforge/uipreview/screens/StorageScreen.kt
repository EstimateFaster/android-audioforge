package com.estimatefaster.audioforge.uipreview.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.estimatefaster.audioforge.uipreview.R
import com.estimatefaster.audioforge.uipreview.data.FakeData
import com.estimatefaster.audioforge.uipreview.data.PlayerState
import com.estimatefaster.audioforge.uipreview.design.AppTopBar
import com.estimatefaster.audioforge.uipreview.design.ChoiceOption
import com.estimatefaster.audioforge.uipreview.design.ConfirmDialog
import com.estimatefaster.audioforge.uipreview.design.IconAction
import com.estimatefaster.audioforge.uipreview.design.ListRow
import com.estimatefaster.audioforge.uipreview.design.RowDivider
import com.estimatefaster.audioforge.uipreview.design.SectionLabel
import com.estimatefaster.audioforge.uipreview.design.SegmentedChoice
import com.estimatefaster.audioforge.uipreview.icons.Ms
import com.estimatefaster.audioforge.uipreview.nav.AppScaffold
import com.estimatefaster.audioforge.uipreview.nav.Navigator
import kotlinx.coroutines.launch

@Composable
fun StorageScreen(navigator: Navigator, player: PlayerState) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var retention by rememberSaveable { mutableStateOf(1) }
    var showCleanup by remember { mutableStateOf<String?>(null) }

    AppScaffold(
        navigator = navigator,
        player = player,
        showNavBar = false,
        showMiniPlayer = true,
        snackbarHostState = snackbarHostState,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.settings_storage),
                subtitle = "已用 ${FakeData.storage.usedLabel} · ${FakeData.storage.fileCount} 个文件",
                scrollBehavior = scrollBehavior,
                onBack = { navigator.pop() },
                actions = {
                    IconAction(
                        icon = Ms.Refresh,
                        description = stringResource(R.string.action_refresh),
                        onClick = { scope.launch { snackbarHostState.showSnackbar("已重新扫描存储") } },
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
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item(key = "hero") {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text(
                        text = FakeData.storage.usedLabel,
                        style = MaterialTheme.typography.displaySmallEmphasized,
                    )
                    Text(
                        text = "音频 ${FakeData.storage.audioLabel} · 导入 ${FakeData.storage.importLabel} · 导出 ${FakeData.storage.exportLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    PartitionBar()
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "可用空间 12.4 GB · 全部文件在本机",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item(key = "projects-label") {
                SectionLabel(text = "项目占用")
            }
            item(key = "projects") {
                Column {
                    FakeData.books.forEachIndexed { index, book ->
                        ListRow(
                            title = book.title,
                            subtitle = if (book.segmentCount == 0) {
                                "${book.chapters.size} 章 · 尚未解析"
                            } else {
                                "${book.chapters.size} 章 · ${book.segmentCount} 段"
                            },
                            trailingText = listOf("1.2 GB", "420 MB", "96 MB", "84 MB")[index % 4],
                            showChevron = false,
                        )
                        RowDivider()
                    }
                }
            }
            item(key = "retention-label") {
                SectionLabel(text = "片段缓存保留期", trailing = "自动清理")
            }
            item(key = "retention") {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    SegmentedChoice(
                        options = listOf(
                            ChoiceOption("7 天"),
                            ChoiceOption("30 天"),
                            ChoiceOption("90 天"),
                        ),
                        selectedIndex = retention,
                        onSelect = { retention = it },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "超过保留期的片段缓存会被删除，章节与音频不受影响。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item(key = "cleanup-label") {
                SectionLabel(text = "清理")
            }
            item(key = "cleanup") {
                Column {
                    ListRow(
                        icon = Ms.DeleteSweep,
                        title = "清理孤儿文件",
                        subtitle = "导入残留与导出临时文件 · 约 24 MB",
                        onClick = { showCleanup = "orphan" },
                        showChevron = false,
                    )
                    RowDivider()
                    ListRow(
                        icon = Ms.Timer,
                        title = "清理过期缓存",
                        subtitle = "超过 ${listOf(7, 30, 90)[retention]} 天的片段缓存 · 约 180 MB",
                        onClick = { showCleanup = "expired" },
                        showChevron = false,
                    )
                }
            }
        }
    }

    showCleanup?.let { kind ->
        ConfirmDialog(
            title = if (kind == "orphan") "清理孤儿文件" else "清理过期缓存",
            body = "只删除不再被引用的临时文件，不会影响任何章节与已导出音频。",
            confirmText = "清理",
            icon = Ms.DeleteSweep,
            danger = true,
            onConfirm = {
                showCleanup = null
                scope.launch { snackbarHostState.showSnackbar("预览构建：已清理，释放约 204 MB") }
            },
            onDismiss = { showCleanup = null },
        )
    }
}

@Composable
private fun PartitionBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(
            modifier = Modifier
                .weight(0.78f)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(2.dp))
        Box(
            modifier = Modifier
                .weight(0.14f)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.secondary),
        )
        Spacer(Modifier.width(2.dp))
        Box(
            modifier = Modifier
                .weight(0.08f)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.tertiary),
        )
    }
}
