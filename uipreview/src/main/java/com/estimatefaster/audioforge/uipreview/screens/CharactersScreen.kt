package com.estimatefaster.audioforge.uipreview.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.estimatefaster.audioforge.uipreview.data.Character
import com.estimatefaster.audioforge.uipreview.data.FakeData
import com.estimatefaster.audioforge.uipreview.data.PlayerState
import com.estimatefaster.audioforge.uipreview.design.AppTopBar
import com.estimatefaster.audioforge.uipreview.design.ConfirmDialog
import com.estimatefaster.audioforge.uipreview.design.ListRow
import com.estimatefaster.audioforge.uipreview.design.RowDivider
import com.estimatefaster.audioforge.uipreview.icons.Ms
import com.estimatefaster.audioforge.uipreview.nav.AppScaffold
import com.estimatefaster.audioforge.uipreview.nav.Navigator
import com.estimatefaster.audioforge.uipreview.nav.RoleAvatar
import com.estimatefaster.audioforge.uipreview.sheets.TuningSheet
import com.estimatefaster.audioforge.uipreview.sheets.VoicePickerSheet
import kotlinx.coroutines.launch

@Composable
fun CharactersScreen(navigator: Navigator, player: PlayerState, bookId: Long) {
    val book = remember(bookId) { FakeData.book(bookId) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var voiceTarget by remember { mutableStateOf<Character?>(null) }
    var tuningTarget by remember { mutableStateOf<Character?>(null) }
    var confirmTarget by remember { mutableStateOf<Character?>(null) }
    var pendingVoice by remember { mutableStateOf("") }
    // 本地覆盖层：预览里换音色后列表立即反映（不改 FakeData）
    val voices = remember { mutableStateMapOf<String, String>() }

    AppScaffold(
        navigator = navigator,
        player = player,
        showNavBar = false,
        showMiniPlayer = false,
        snackbarHostState = snackbarHostState,
        topBar = {
            AppTopBar(
                title = "人物与音色",
                subtitle = "${book.characters.size} 个角色 · ${book.characters.count { it.voice != null }} 个已配音",
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
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
        ) {
            items(book.characters, key = { it.name }) { character ->
                Column {
                    ListRow(
                        title = character.name,
                        subtitle = buildString {
                            append(voices[character.name] ?: character.voice ?: "未分配音色")
                            append(" · ")
                            append(character.importance)
                            if (character.locked) append(" · 已锁定")
                        },
                        onClick = { voiceTarget = character },
                        leading = { RoleAvatar(name = character.name, size = 40) },
                        showChevron = false,
                        trailingContent = {
                            IconButton(
                                onClick = { tuningTarget = character },
                                modifier = Modifier.padding(0.dp),
                            ) {
                                Icon(
                                    painter = painterResource(Ms.Tune),
                                    contentDescription = "调参",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                    RowDivider()
                }
            }
            item {
                Text(
                    text = "锁定角色更换音色需要二次确认；调参支持语速、音量与音调。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
        }
    }

    voiceTarget?.let { character ->
        VoicePickerSheet(
            character = character,
            onDismiss = { voiceTarget = null },
            onSave = { voice ->
                if (character.locked) {
                    pendingVoice = voice
                    confirmTarget = character
                    voiceTarget = null
                } else {
                    voices[character.name] = voice
                    voiceTarget = null
                    scope.launch { snackbarHostState.showSnackbar("已为 ${character.name} 选择 $voice") }
                }
            },
        )
    }
    confirmTarget?.let { character ->
        ConfirmDialog(
            title = "更换已锁定角色的音色？",
            body = "《${book.title}》里 ${character.name} 已经生成过音频，换音色后需要重新合成相关段落。",
            confirmText = "仍然更换",
            icon = Ms.Lock,
            danger = true,
            onConfirm = {
                voices[character.name] = pendingVoice
                confirmTarget = null
                scope.launch {
                    snackbarHostState.showSnackbar("已为 ${character.name} 更换音色，相关段落待重新合成")
                }
            },
            onDismiss = { confirmTarget = null },
        )
    }
    tuningTarget?.let { character ->
        TuningSheet(
            character = character,
            onDismiss = { tuningTarget = null },
            onSave = {
                tuningTarget = null
                scope.launch { snackbarHostState.showSnackbar("已保存 ${character.name} 的参数") }
            },
        )
    }
}
