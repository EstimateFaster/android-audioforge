package com.estimatefaster.audioforge.uipreview.screens

import androidx.compose.foundation.background
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.estimatefaster.audioforge.uipreview.data.PlayerState
import com.estimatefaster.audioforge.uipreview.design.AppTopBar
import com.estimatefaster.audioforge.uipreview.design.ListRow
import com.estimatefaster.audioforge.uipreview.design.RowDivider
import com.estimatefaster.audioforge.uipreview.design.SecondaryButton
import com.estimatefaster.audioforge.uipreview.design.SheetHeader
import com.estimatefaster.audioforge.uipreview.icons.Ms
import com.estimatefaster.audioforge.uipreview.nav.AppScaffold
import com.estimatefaster.audioforge.uipreview.nav.Navigator
import kotlinx.coroutines.launch

/** 运行检查明细：版本、状态都收进 Sheet，主页只留一行入口。 */
private val runtimeChecks = listOf(
    Triple("Room", "2.8.5 · schema 1 · 8 张表", Ms.Database),
    Triple("FFmpegKit", "8.1.9 · 音频拼接与响度", Ms.GraphicEq),
    Triple("WorkManager", "2.12.0 · dataSync 前台服务", Ms.Sync),
    Triple("存储", "10.6 GB 可用 · 1,284 个文件", Ms.Storage),
)

private val openSourceLicenses = listOf(
    "Jetpack Compose" to "AndroidX · Apache-2.0",
    "Material 3" to "AndroidX · Apache-2.0",
    "Kotlin & Coroutines" to "JetBrains · Apache-2.0",
    "Material Symbols" to "Google · Apache-2.0",
)

@Composable
fun AboutScreen(navigator: Navigator, player: PlayerState) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showDiagnostics by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }

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
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(Ms.AutoStories),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("AudioForge", style = MaterialTheme.typography.headlineSmallEmphasized)
                    Text(
                        text = "把小说变成多人演播的有声书。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            item(key = "checks") {
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    ListRow(
                        title = "运行检查",
                        subtitle = "8 项检查全部正常",
                        icon = Ms.CheckCircle,
                        onClick = { showDiagnostics = true },
                    )
                    RowDivider()
                    ListRow(
                        title = "开源许可",
                        subtitle = "4 个第三方组件 · Apache-2.0",
                        icon = Ms.Code,
                        onClick = { showLicenses = true },
                    )
                }
            }
        }
    }

    if (showDiagnostics) {
        DiagnosticsSheet(
            onCopy = {
                showDiagnostics = false
                scope.launch { snackbarHostState.showSnackbar("诊断信息已复制") }
            },
            onDismiss = { showDiagnostics = false },
        )
    }
    if (showLicenses) {
        LicensesSheet(onDismiss = { showLicenses = false })
    }
}

@Composable
private fun DiagnosticsSheet(onCopy: () -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
        ) {
            SheetHeader(
                icon = Ms.CheckCircle,
                title = "运行检查",
                subtitle = "8 项检查全部正常",
                onClose = onDismiss,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(4.dp))
            runtimeChecks.forEach { (title, subtitle, icon) ->
                ListRow(
                    title = title,
                    subtitle = subtitle,
                    icon = icon,
                    trailingText = "正常",
                    showChevron = false,
                )
                RowDivider()
            }
            SecondaryButton(
                text = "复制诊断信息",
                icon = Ms.ContentCopy,
                onClick = onCopy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 20.dp),
            )
        }
    }
}

@Composable
private fun LicensesSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
        ) {
            SheetHeader(
                icon = Ms.Code,
                title = "开源许可",
                subtitle = "第三方组件与许可",
                onClose = onDismiss,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(4.dp))
            openSourceLicenses.forEach { (title, subtitle) ->
                ListRow(
                    title = title,
                    subtitle = subtitle,
                    showChevron = false,
                )
                RowDivider()
            }
        }
    }
}
