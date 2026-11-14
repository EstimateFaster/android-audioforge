package com.estimatefaster.audioforge.uipreview.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.estimatefaster.audioforge.uipreview.data.PlayerState
import com.estimatefaster.audioforge.uipreview.design.AppTopBar
import com.estimatefaster.audioforge.uipreview.design.ListRow
import com.estimatefaster.audioforge.uipreview.design.RowDivider
import com.estimatefaster.audioforge.uipreview.design.SecondaryButton
import com.estimatefaster.audioforge.uipreview.design.SectionLabel
import com.estimatefaster.audioforge.uipreview.design.StatusChip
import com.estimatefaster.audioforge.uipreview.design.Tone
import com.estimatefaster.audioforge.uipreview.icons.Ms
import com.estimatefaster.audioforge.uipreview.nav.AppScaffold
import com.estimatefaster.audioforge.uipreview.nav.Navigator
import com.estimatefaster.audioforge.uipreview.sheets.EditValueSheet
import kotlinx.coroutines.launch

private data class EditTarget(
    val title: String,
    val subtitle: String,
    val value: String,
    val password: Boolean = false,
)

private fun hostOf(url: String): String =
    url.removePrefix("https://").removePrefix("http://").substringBefore("/")

@Composable
fun ModelConfigScreen(navigator: Navigator, player: PlayerState) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var llmBase by rememberSaveable { mutableStateOf("https://dashscope.aliyuncs.com/compatible-mode/v1") }
    var llmModel by rememberSaveable { mutableStateOf("qwen3.7-flash") }
    var llmKey by rememberSaveable { mutableStateOf("sk-demo-7f2a") }
    var ttsBase by rememberSaveable { mutableStateOf("https://api.fish.audio") }
    var ttsModel by rememberSaveable { mutableStateOf("s2.1-pro-free") }
    var ttsKey by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<EditTarget?>(null) }

    AppScaffold(
        navigator = navigator,
        player = player,
        showNavBar = false,
        showMiniPlayer = false,
        snackbarHostState = snackbarHostState,
        topBar = {
            AppTopBar(
                title = "模型与密钥",
                subtitle = "BYOK · 只保存在本机",
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
            item(key = "llm-label") { SectionLabel(text = "文本模型", spec = "ListItem · 64dp") }
            item(key = "llm") {
                Column {
                    ListRow(
                        title = "接口地址",
                        subtitle = hostOf(llmBase),
                        icon = Ms.Language,
                        onClick = {
                            editing = EditTarget("接口地址", "文本模型的接口地址", llmBase)
                        },
                    )
                    RowDivider()
                    ListRow(
                        title = "模型 ID",
                        subtitle = llmModel,
                        icon = Ms.SmartToy,
                        onClick = { editing = EditTarget("模型 ID", "例如 qwen3.7-flash", llmModel) },
                    )
                    RowDivider()
                    ListRow(
                        title = "API Key",
                        subtitle = if (llmKey.isBlank()) "未设置" else "已设置 · ••••${llmKey.takeLast(4)}",
                        icon = Ms.VpnKey,
                        onClick = { editing = EditTarget("LLM API Key", "只写入本机 Keystore", llmKey, password = true) },
                    )
                }
            }
            item(key = "tts-label") { SectionLabel(text = "语音模型") }
            item(key = "tts") {
                Column {
                    ListRow(
                        title = "接口地址",
                        subtitle = hostOf(ttsBase),
                        icon = Ms.Language,
                        onClick = { editing = EditTarget("接口地址", "语音服务的接口地址", ttsBase) },
                    )
                    RowDivider()
                    ListRow(
                        title = "模型 ID",
                        subtitle = ttsModel,
                        icon = Ms.RecordVoiceOver,
                        onClick = { editing = EditTarget("TTS 模型", "例如 s2.1-pro-free", ttsModel) },
                    )
                    RowDivider()
                    ListRow(
                        title = "API Key",
                        subtitle = if (ttsKey.isBlank()) "未设置" else "已设置",
                        icon = Ms.VpnKey,
                        trailingContent = {
                            StatusChip(text = "待配置", tone = Tone.Warn)
                        },
                        onClick = { editing = EditTarget("Fish Audio API Key", "只写入本机 Keystore", ttsKey, password = true) },
                    )
                }
            }
            item(key = "test") {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) {
                    Text(
                        text = "测试只校验地址与 Key 的格式，不会真的发起请求。Key 只写入本机 Keystore，不会随备份导出。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    SecondaryButton(
                        text = "测试连接",
                        icon = Ms.Bolt,
                        onClick = {
                            scope.launch { snackbarHostState.showSnackbar("预览构建：连接测试不会真的发起请求") }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    editing?.let { target ->
        EditValueSheet(
            title = target.title,
            subtitle = target.subtitle,
            initial = target.value,
            password = target.password,
            icon = if (target.password) Ms.VpnKey else Ms.Edit,
            onDismiss = { editing = null },
            onSave = { value ->
                when (target.title) {
                    "Base URL" -> llmBase = value
                    "模型 ID" -> llmModel = value
                    "LLM API Key" -> llmKey = value
                    "Fish Base URL" -> ttsBase = value
                    "TTS 模型" -> ttsModel = value
                    "Fish Audio API Key" -> ttsKey = value
                }
                editing = null
                scope.launch { snackbarHostState.showSnackbar("已保存 ${target.title}") }
            },
        )
    }
}
