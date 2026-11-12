package com.estimatefaster.audioforge.uipreview.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import com.estimatefaster.audioforge.uipreview.design.PrimaryButton
import com.estimatefaster.audioforge.uipreview.design.SecondaryButton
import com.estimatefaster.audioforge.uipreview.icons.Ms
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
                .padding(horizontal = 24.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepRail(step = step, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { navigator.selectTab(com.estimatefaster.audioforge.uipreview.nav.Dest.Library) },
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
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "onboarding-step",
            ) { current ->
                Column(
                    modifier = Modifier
                        .weight(1f)
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
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (step > 0) {
                    SecondaryButton(
                        text = "上一步",
                        icon = Ms.ChevronLeft,
                        onClick = { step -= 1 },
                    )
                }
                Spacer(Modifier.weight(1f))
                PrimaryButton(
                    text = when (step) {
                        0 -> "开始配置"
                        1 -> "保存并继续"
                        else -> "开始使用"
                    },
                    icon = if (step == 2) Ms.Check else Ms.ArrowForward,
                    onClick = {
                        if (step < 2) {
                            step += 1
                        } else {
                            navigator.selectTab(com.estimatefaster.audioforge.uipreview.nav.Dest.Library)
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
    Text("连接文本模型", style = MaterialTheme.typography.headlineSmallEmphasized)
    Text(
        text = "用一个兼容 OpenAI 协议的地址即可；Key 只保存在本机 Keystore。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
    )
    OutlinedTextField(
        value = baseUrl,
        onValueChange = onBaseUrl,
        label = { Text("Base URL") },
        singleLine = true,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = model,
        onValueChange = onModel,
        label = { Text("模型 ID") },
        singleLine = true,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = key,
        onValueChange = onKey,
        label = { Text("API Key") },
        placeholder = { Text("sk-…") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(20.dp))
    Text(
        text = "语音模型（Fish Audio）可以在设置里随时修改；这里先跳过也可以。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StepPermissions() {
    Text("还需要三项权限", style = MaterialTheme.typography.headlineSmallEmphasized)
    Text(
        text = "都只用于本机任务，不会上传内容。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
    )
    PermissionCard(
        icon = Ms.Notifications,
        title = "通知",
        body = "后台生成结束时提醒你；关闭后仍可回到任务页查看。",
    )
    PermissionCard(
        icon = Ms.Folder,
        title = "文件访问",
        body = "读取你选择的 TXT，写入导出的 MP3；不会扫描其他文件。",
    )
    PermissionCard(
        icon = Ms.BatteryChargingFull,
        title = "后台运行",
        body = "长任务需要前台服务，任务期间通知栏会出现进度。",
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
