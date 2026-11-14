package com.estimatefaster.audioforge.uipreview.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.estimatefaster.audioforge.uipreview.data.PlayerState
import com.estimatefaster.audioforge.uipreview.design.IconBubble
import com.estimatefaster.audioforge.uipreview.design.ListRow
import com.estimatefaster.audioforge.uipreview.design.PrimaryButton
import com.estimatefaster.audioforge.uipreview.design.RowDivider
import com.estimatefaster.audioforge.uipreview.design.SecondaryButton
import com.estimatefaster.audioforge.uipreview.design.SwitchRow
import com.estimatefaster.audioforge.uipreview.icons.Ms
import com.estimatefaster.audioforge.uipreview.nav.Dest
import com.estimatefaster.audioforge.uipreview.sheets.EditValueSheet
import com.estimatefaster.audioforge.uipreview.nav.Navigator

@Composable
fun OnboardingScreen(navigator: Navigator, player: PlayerState) {
    var step by rememberSaveable { mutableStateOf(0) }
    var baseUrl by rememberSaveable { mutableStateOf("https://dashscope.aliyuncs.com/compatible-mode/v1") }
    var model by rememberSaveable { mutableStateOf("qwen3.7-flash") }
    var key by rememberSaveable { mutableStateOf("") }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .imePadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepRail(step = step, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = {
                        if (navigator.canGoBack) navigator.pop() else navigator.selectTab(Dest.Library)
                    },
                ) {
                    Icon(
                        painter = painterResource(Ms.SkipNext),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("跳过", style = MaterialTheme.typography.labelLarge)
                }
            }
            AnimatedContent(
                targetState = step,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                transitionSpec = {
                    val forward = targetState > initialState
                    val direction = if (forward) 1 else -1
                    (
                        slideInHorizontally(tween(durationMillis = 260)) { direction * it / 4 } +
                            fadeIn(tween(durationMillis = 260, delayMillis = 60))
                        ) togetherWith (
                        slideOutHorizontally(tween(durationMillis = 160)) { -direction * it / 6 } +
                            fadeOut(tween(durationMillis = 160))
                        )
                },
                label = "onboarding-step",
            ) { current ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Spacer(Modifier.height(24.dp))
                    when (current) {
                        0 -> StepWelcome()
                        1 -> StepKeys(
                            baseUrl = baseUrl,
                            onBaseUrl = { baseUrl = it },
                            model = model,
                            onModel = { model = it },
                            key = key,
                            onKey = { key = it },
                        )
                        else -> StepPermissions()
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (step > 0) {
                    TextButton(onClick = { step -= 1 }) {
                        Icon(
                            painter = painterResource(Ms.ChevronLeft),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("上一步", style = MaterialTheme.typography.labelLarge)
                    }
                }
                Spacer(Modifier.width(12.dp))
                PrimaryButton(
                    modifier = Modifier.weight(1f),
                    text = when (step) {
                        0 -> "开始配置"
                        1 -> "保存并继续"
                        else -> "开始使用"
                    },
                    icon = if (step == 2) Ms.Check else Ms.ArrowForward,
                    onClick = {
                        if (step < 2) {
                            step += 1
                        } else if (navigator.canGoBack) {
                            navigator.pop()
                        } else {
                            navigator.selectTab(Dest.Library)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun StepRail(step: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            Box(
                modifier = Modifier
                    .size(width = if (index == step) 28.dp else 10.dp, height = 10.dp)
                    .clip(CircleShape)
                    .background(
                        if (index <= step) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                    ),
            )
        }
    }
}

@Composable
private fun StepWelcome() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(Ms.AutoStories),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp),
            )
        }
        Spacer(Modifier.height(28.dp))
        Text(
            text = "让文字开口",
            style = MaterialTheme.typography.headlineMediumEmphasized,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "把一本 TXT 小说，变成多人演播的有声书。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        FeatureRow(Ms.Key, "Key 由你提供", "直连模型服务，不经过任何中转")
        FeatureRow(Ms.CloudOff, "只在设备上运行", "解析、合成、拼接都在本机完成")
        FeatureRow(Ms.Headphones, "多人演播", "自动识别角色，逐个分配音色")
    }
}

@Composable
private fun FeatureRow(icon: Int, title: String, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBubble(icon = icon, size = 40.dp, iconSize = 20.dp)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmallEmphasized)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StepKeys(
    baseUrl: String,
    onBaseUrl: (String) -> Unit,
    model: String,
    onModel: (String) -> Unit,
    key: String,
    onKey: (String) -> Unit,
) {
    var editing by remember { mutableStateOf<String?>(null) }

    Text("连接文本模型", style = MaterialTheme.typography.headlineSmallEmphasized)
    Text(
        text = "填一个兼容 OpenAI 协议的地址即可；Key 只保存在本机。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
    Spacer(Modifier.height(20.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        ListRow(
            title = "协议地址",
            subtitle = baseUrl,
            icon = Ms.Link,
            onClick = { editing = "base" },
        )
        RowDivider()
        ListRow(
            title = "模型 ID",
            subtitle = model,
            icon = Ms.Memory,
            onClick = { editing = "model" },
        )
        RowDivider()
        ListRow(
            title = "API Key",
            subtitle = if (key.isBlank()) "未填写，可以稍后在设置里补" else "已保存（仅本机）",
            icon = Ms.Key,
            onClick = { editing = "key" },
        )
    }
    Spacer(Modifier.height(16.dp))
    Text(
        text = "语音模型（Fish Audio）可以在设置里随时修改。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    when (editing) {
        "base" -> EditValueSheet(
            title = "协议地址",
            subtitle = "兼容 OpenAI 协议的接口地址",
            initial = baseUrl,
            onDismiss = { editing = null },
            onSave = { onBaseUrl(it); editing = null },
            icon = Ms.Link,
            placeholder = "https://…",
        )

        "model" -> EditValueSheet(
            title = "模型 ID",
            subtitle = "文本模型的名称",
            initial = model,
            onDismiss = { editing = null },
            onSave = { onModel(it); editing = null },
            icon = Ms.Memory,
            placeholder = "qwen…",
        )

        "key" -> EditValueSheet(
            title = "API Key",
            subtitle = "只保存在本机 Keystore",
            initial = key,
            onDismiss = { editing = null },
            onSave = { onKey(it); editing = null },
            icon = Ms.Key,
            password = true,
            placeholder = "sk-…",
        )
    }
}

@Composable
private fun StepPermissions() {
    var notify by remember { mutableStateOf(true) }
    var files by remember { mutableStateOf(false) }
    var background by remember { mutableStateOf(true) }

    Text("还需要三项权限", style = MaterialTheme.typography.headlineSmallEmphasized)
    Text(
        text = "都只用于本机任务，不会上传内容；可以随时在设置里改。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
    )
    SwitchRow(
        icon = Ms.Notifications,
        title = "通知",
        supporting = "后台生成结束时提醒你",
        checked = notify,
        onCheckedChange = { notify = it },
    )
    RowDivider()
    SwitchRow(
        icon = Ms.Folder,
        title = "文件访问",
        supporting = "读取你选择的 TXT，写入导出的 MP3",
        checked = files,
        onCheckedChange = { files = it },
    )
    RowDivider()
    SwitchRow(
        icon = Ms.BatteryChargingFull,
        title = "后台运行",
        supporting = "长任务用前台服务，通知栏会显示进度",
        checked = background,
        onCheckedChange = { background = it },
    )
}

@Composable
private fun PermissionCard(icon: Int, title: String, body: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        IconBubble(icon = icon, size = 40.dp, iconSize = 20.dp)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmallEmphasized)
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 19.sp,
            )
        }
    }
}
