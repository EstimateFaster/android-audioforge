package com.estimatefaster.audioforge.feature.characters

import android.content.res.Configuration
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.ui.components.EmptyState
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme

/**
 * 音色选择 Sheet（P5-05 / 执行计划 §12.4 S09）。
 *
 * 有状态入口：收集 [VoicePickerViewModel.state]，把一次性事件转成宿主回调：
 * - `Assigned` → `onAssigned(roleId, category, voiceId)`，随后关闭 Sheet；
 * - `Cancelled` → `onDismiss`。
 *
 * 保存/失败重试/取消/锁定二次确认全部委托给 ViewModel；Composable 不访问数据库。
 *
 * @param onDismiss 用户取消/下滑/返回时关闭 Sheet
 * @param onAssigned 保存成功；宿主要据此刷新角色卡
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicePickerSheet(
    viewModel: VoicePickerViewModel,
    onDismiss: () -> Unit,
    onAssigned: (roleId: String, category: String, voiceId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnAssigned by rememberUpdatedState(onAssigned)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is VoicePickerEvent.Assigned -> {
                    currentOnAssigned(event.roleId, event.category, event.voiceId)
                    currentOnDismiss()
                }
                VoicePickerEvent.Cancelled -> currentOnDismiss()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = viewModel::cancel,
        modifier = modifier,
        sheetState = sheetState,
    ) {
        VoicePickerSheetContent(
            state = state,
            onQueryChange = viewModel::setQuery,
            onGenderFilterChange = viewModel::setGenderFilter,
            onSelectVoice = viewModel::selectVoice,
            onClearFilters = viewModel::clearFilters,
            onSave = viewModel::save,
            onRetry = viewModel::retry,
            onCancel = viewModel::cancel,
            onConfirmLockedSave = viewModel::confirmLockedSave,
            onDismissLockedConfirm = viewModel::dismissLockedConfirm,
        )
    }
}

/**
 * 无状态 Sheet 内容；Preview 与后续 UI 测试直接渲染它。
 *
 * 布局：标题 → 当前音色/锁定提示 → 搜索 → 档位 FilterChip → 按
 * “语言 · 性别”分组的音色列表 → 保存错误 → 取消/保存。
 * 搜索和档位只做内存过滤；locked 角色保存时弹二次确认。
 */
@Composable
fun VoicePickerSheetContent(
    state: VoicePickerUiState,
    modifier: Modifier = Modifier,
    onQueryChange: (String) -> Unit = {},
    onGenderFilterChange: (VoiceGenderGroup?) -> Unit = {},
    onSelectVoice: (String) -> Unit = {},
    onClearFilters: () -> Unit = {},
    onSave: () -> Unit = {},
    onRetry: () -> Unit = {},
    onCancel: () -> Unit = {},
    onConfirmLockedSave: () -> Unit = {},
    onDismissLockedConfirm: () -> Unit = {},
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(0.92f)
            .imePadding()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(
            start = 24.dp,
            end = 24.dp,
            top = 8.dp,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") {
            VoicePickerHeader(state = state, onCancel = onCancel)
        }
        item(key = "summary") {
            CurrentVoiceSummary(state = state)
        }

        if (state.hasNoVoices) {
            item(key = "empty") {
                EmptyState(
                    title = stringResource(R.string.voice_picker_empty_title),
                    description = stringResource(R.string.voice_picker_empty_description),
                    actionText = stringResource(R.string.action_dismiss),
                    onAction = onCancel,
                )
            }
        } else {
            item(key = "search") {
                VoiceSearchField(state = state, onQueryChange = onQueryChange)
            }
            item(key = "filters") {
                VoiceGroupFilterRow(
                    state = state,
                    onGenderFilterChange = onGenderFilterChange,
                )
            }

            if (state.isFilteredEmpty) {
                item(key = "filtered-empty") {
                    EmptyState(
                        title = stringResource(R.string.voice_picker_filtered_empty_title),
                        description = stringResource(
                            R.string.voice_picker_filtered_empty_description,
                        ),
                        actionText = stringResource(R.string.voice_picker_filtered_empty_action),
                        onAction = onClearFilters,
                    )
                }
            } else {
                state.visibleSections.forEach { section ->
                    item(key = "section:${section.key}") {
                        VoiceSectionHeader(section = section)
                    }
                    items(
                        items = section.voices,
                        key = { voice -> voice.category },
                        contentType = { _ -> "voice" },
                    ) { voice ->
                        VoiceRow(
                            voice = voice,
                            selected = voice.category == state.selectedCategory,
                            current = voice.category == state.currentCategory,
                            enabled = !state.saving,
                            onSelect = { onSelectVoice(voice.category) },
                        )
                    }
                }
            }
        }

        state.saveError?.let { message ->
            item(key = "save-error") {
                VoiceSaveError(
                    message = message,
                    enabled = !state.saving,
                    onRetry = onRetry,
                )
            }
        }

        item(key = "actions") {
            VoiceActions(
                state = state,
                onCancel = onCancel,
                onSave = onSave,
            )
        }
    }

    if (state.pendingLockedConfirm) {
        LockedVoiceConfirmDialog(
            saving = state.saving,
            onConfirm = onConfirmLockedSave,
            onDismiss = onDismissLockedConfirm,
        )
    }
}

@Composable
private fun VoicePickerHeader(
    state: VoicePickerUiState,
    onCancel: () -> Unit,
) {
    val roleName = state.roleName.ifBlank { state.roleId }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.voice_picker_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(
                    R.string.voice_picker_subtitle,
                    roleName,
                    state.totalVoiceCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = onCancel,
            enabled = !state.saving,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}

@Composable
private fun CurrentVoiceSummary(state: VoicePickerUiState) {
    val rawCurrentName = state.currentDisplayName
    val currentName = if (rawCurrentName.isBlank()) {
        stringResource(R.string.characters_voice_unassigned)
    } else {
        rawCurrentName
    }
    val rawCurrentCategory = state.currentCategory
    val currentCategory = if (rawCurrentCategory.isBlank()) {
        stringResource(R.string.characters_voice_unassigned)
    } else {
        rawCurrentCategory
    }
    val rawCurrentVoiceId = state.currentDisplayVoiceId
    val currentVoiceId = if (rawCurrentVoiceId.isBlank()) {
        stringResource(R.string.voice_picker_unknown_voice_id)
    } else {
        rawCurrentVoiceId
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.voice_picker_current, currentName),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.locked) {
                    Spacer(Modifier.width(8.dp))
                    VoiceTag(
                        text = stringResource(R.string.characters_locked),
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        content = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Text(
                text = stringResource(R.string.characters_category, currentCategory),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.voice_picker_voice_id, currentVoiceId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.locked) {
                Text(
                    text = stringResource(R.string.voice_picker_locked_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun VoiceSearchField(
    state: VoicePickerUiState,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = state.query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.saving,
        label = { Text(stringResource(R.string.voice_picker_search_label)) },
        placeholder = { Text(stringResource(R.string.voice_picker_search_placeholder)) },
        singleLine = true,
        trailingIcon = {
            if (state.query.isNotEmpty()) {
                TextButton(
                    onClick = { onQueryChange("") },
                    enabled = !state.saving,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.voice_picker_search_clear))
                }
            }
        },
    )
}

@Composable
private fun VoiceGroupFilterRow(
    state: VoicePickerUiState,
    onGenderFilterChange: (VoiceGenderGroup?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.voice_picker_group_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = state.genderFilter == null,
                onClick = { onGenderFilterChange(null) },
                enabled = !state.saving,
                label = { Text(stringResource(R.string.voice_picker_group_all)) },
            )
            state.availableGroups.forEach { group ->
                FilterChip(
                    selected = state.genderFilter == group,
                    onClick = { onGenderFilterChange(group) },
                    enabled = !state.saving,
                    label = { Text(group.label()) },
                )
            }
        }
    }
}

@Composable
private fun VoiceSectionHeader(section: VoiceSectionUi) {
    Text(
        text = stringResource(
            R.string.voice_picker_section_header,
            localeLabel(section.locale),
            section.gender.label(),
        ),
        modifier = Modifier
            .padding(top = 8.dp)
            .semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun VoiceRow(
    voice: VoiceCategoryUi,
    selected: Boolean,
    current: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    val rawLabel = voice.displayLabel
    val label = if (rawLabel.isBlank()) {
        stringResource(R.string.voice_picker_unknown_label)
    } else {
        rawLabel
    }
    val rawVoiceId = voice.displayVoiceId
    val voiceId = if (rawVoiceId.isBlank()) {
        stringResource(R.string.voice_picker_unknown_voice_id)
    } else {
        rawVoiceId
    }
    val note = voice.note?.trim().orEmpty()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelect,
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                enabled = enabled,
            )
            Spacer(Modifier.width(4.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (current) {
                        Spacer(Modifier.width(6.dp))
                        VoiceTag(
                            text = stringResource(R.string.voice_picker_current_badge),
                            container = MaterialTheme.colorScheme.primaryContainer,
                            content = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Text(
                    text = voice.category,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.voice_picker_voice_id, voiceId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (note.isNotEmpty()) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                voice.speed?.let { speed ->
                    Text(
                        text = stringResource(R.string.voice_picker_speed, speed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceSaveError(
    message: String,
    enabled: Boolean,
    onRetry: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.action_save_error, message),
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(
                onClick = onRetry,
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

@Composable
private fun VoiceActions(
    state: VoicePickerUiState,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.hasSelection && state.isSameAsCurrent) {
            Text(
                text = stringResource(R.string.voice_picker_same_as_current),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onCancel,
                enabled = !state.saving,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.action_cancel))
            }
            Button(
                onClick = onSave,
                enabled = state.canSave,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            ) {
                if (state.saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_saving))
                } else {
                    Text(
                        text = if (state.saveError != null) {
                            stringResource(R.string.action_retry)
                        } else {
                            stringResource(R.string.voice_picker_save)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LockedVoiceConfirmDialog(
    saving: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.voice_picker_locked_confirm_title)) },
        text = { Text(stringResource(R.string.voice_picker_locked_confirm_message)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !saving,
            ) {
                Text(stringResource(R.string.voice_picker_locked_confirm_action))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !saving,
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun VoiceTag(
    text: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
) {
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = container,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = content,
            maxLines = 1,
        )
    }
}

@Composable
private fun localeLabel(locale: String): String = when (locale.trim().lowercase()) {
    "zh", "zh-cn", "zh_cn" -> stringResource(R.string.voice_picker_locale_zh_cn)
    else -> locale
}

@Composable
private fun VoiceGenderGroup.label(): String = stringResource(
    when (this) {
        VoiceGenderGroup.Narrator -> R.string.voice_picker_group_narrator
        VoiceGenderGroup.Male -> R.string.voice_picker_group_male
        VoiceGenderGroup.Female -> R.string.voice_picker_group_female
        VoiceGenderGroup.Other -> R.string.voice_picker_group_other
    },
)

// ---------------------------------------------------------------------------
// Preview：正常（未锁定）/ 已锁定 / 搜索无结果 / 深色 200%。
// 预览数据只是固定夹具；所有 UI 文案来自 strings.xml。
// ---------------------------------------------------------------------------

private fun previewVoices(): List<VoiceCategoryUi> = listOf(
    VoiceCategoryUi(
        category = "narrator_male",
        label = "【旁白】沉稳大气播音腔",
        voiceId = "59cb5986671546eaa6ca8ae6f29f6d22",
    ),
    VoiceCategoryUi(
        category = "male_lead_steady",
        label = "【男主】青年·沉稳口语",
        voiceId = "voice-male-lead-steady",
    ),
    VoiceCategoryUi(
        category = "male_brother",
        label = "【男配】浑厚郑重·兄长/挚友型",
        voiceId = "voice-male-brother",
        note = "样音语速偏慢，已默认按 1.15 倍速合成",
        speed = 1.15,
    ),
    VoiceCategoryUi(
        category = "male_villain",
        label = "【反派】中年·阴狠威压",
        voiceId = "voice-male-villain",
    ),
    VoiceCategoryUi(
        category = "female_lead_gentle",
        label = "【女主】清冷温柔",
        voiceId = "voice-female-lead",
    ),
    VoiceCategoryUi(
        category = "female_young",
        label = "【女配/女主】青年女性·傲娇知性",
        voiceId = "voice-female-young",
    ),
    VoiceCategoryUi(
        category = "female_child_1",
        label = "【女童】明亮清脆",
        voiceId = "voice-female-child",
    ),
    VoiceCategoryUi(
        category = "male_elder",
        label = "【老年】苍劲·不限身份",
        voiceId = "voice-male-elder",
    ),
)

private fun previewState(
    locked: Boolean = false,
    query: String = "",
    selectedCategory: String? = "male_brother",
    pendingLockedConfirm: Boolean = false,
): VoicePickerUiState = buildVoicePickerUiState(
    roleId = "role_0001",
    roleName = "沈砚",
    currentCategory = "male_lead_steady",
    currentVoiceId = "voice-male-lead-steady",
    locked = locked,
    voices = previewVoices(),
    query = query,
    selectedCategory = selectedCategory,
).copy(pendingLockedConfirm = pendingLockedConfirm)

@Preview(name = "VoicePicker Normal", showBackground = true, backgroundColor = 0xFFF7F8FC)
@Composable
private fun VoicePickerNormalPreview() {
    AudioForgeTheme(dynamicColor = false) {
        Surface {
            VoicePickerSheetContent(state = previewState())
        }
    }
}

@Preview(name = "VoicePicker Locked", showBackground = true, backgroundColor = 0xFFF7F8FC)
@Composable
private fun VoicePickerLockedPreview() {
    AudioForgeTheme(dynamicColor = false) {
        Surface {
            VoicePickerSheetContent(state = previewState(locked = true))
        }
    }
}

@Preview(name = "VoicePicker Locked Confirm", showBackground = true, backgroundColor = 0xFFF7F8FC)
@Composable
private fun VoicePickerLockedConfirmPreview() {
    AudioForgeTheme(dynamicColor = false) {
        Surface {
            VoicePickerSheetContent(
                state = previewState(locked = true, pendingLockedConfirm = true),
            )
        }
    }
}

@Preview(
    name = "VoicePicker No Results",
    showBackground = true,
    backgroundColor = 0xFFF7F8FC,
)
@Composable
private fun VoicePickerNoResultsPreview() {
    AudioForgeTheme(dynamicColor = false) {
        Surface {
            VoicePickerSheetContent(
                state = previewState(
                    query = "不存在的声音",
                    selectedCategory = null,
                ),
            )
        }
    }
}

@Preview(
    name = "VoicePicker Dark 200%",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    fontScale = 2f,
)
@Composable
private fun VoicePickerDarkPreview() {
    AudioForgeTheme(darkTheme = true, dynamicColor = false) {
        Surface {
            VoicePickerSheetContent(state = previewState())
        }
    }
}
