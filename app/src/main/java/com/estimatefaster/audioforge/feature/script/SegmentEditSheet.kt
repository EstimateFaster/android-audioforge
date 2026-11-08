package com.estimatefaster.audioforge.feature.script

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.core.model.Emotion
import com.estimatefaster.audioforge.core.model.NARRATOR_ROLE_ID
import com.estimatefaster.audioforge.core.model.SegmentType
import com.estimatefaster.audioforge.data.db.SegmentEntity
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme

/**
 * 片段编辑 Sheet（P5-02 / 执行计划 §12.4 S07）。
 *
 * 有状态入口：收集 [SegmentEditViewModel.state]，把一次性事件转成调用方回调：
 * - `Saved` → `onSaved(updated)`（调用方决定是否关闭 Sheet / 提示已保存）；
 * - `Cancelled` → `onDismiss`；
 * - `RegenerateRequested` → `onRegenerate(segmentId)`（本 Sheet 不直接调用 TTS）。
 *
 * 保存、失败重试、取消、重置都委托给 ViewModel；Composable 不访问数据库。
 *
 * @param onDismiss 用户取消/下滑/返回时关闭 Sheet
 * @param onSaved 保存成功；`segment` 是实际写入的实体
 * @param onRegenerate “重新生成此句”回调；调用方负责入队
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentEditSheet(
    viewModel: SegmentEditViewModel,
    onDismiss: () -> Unit,
    onSaved: (SegmentEntity) -> Unit,
    modifier: Modifier = Modifier,
    onRegenerate: (Long) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnSaved by rememberUpdatedState(onSaved)
    val currentOnRegenerate by rememberUpdatedState(onRegenerate)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SegmentEditEvent.Saved -> currentOnSaved(event.segment)
                SegmentEditEvent.Cancelled -> currentOnDismiss()
                is SegmentEditEvent.RegenerateRequested ->
                    currentOnRegenerate(event.segmentId)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = viewModel::cancel,
        modifier = modifier,
        sheetState = sheetState,
    ) {
        SegmentEditSheetContent(
            state = state,
            onTypeChange = viewModel::setType,
            onRoleChange = viewModel::setRoleId,
            onEmotionChange = viewModel::setEmotion,
            onTextChange = viewModel::setText,
            onReset = viewModel::reset,
            onSave = viewModel::save,
            onRetry = viewModel::retry,
            onCancel = viewModel::cancel,
            onRegenerate = viewModel::requestRegenerate,
        )
    }
}

/**
 * 无状态 Sheet 内容；Preview 与后续 UI 测试直接渲染它。
 *
 * 布局：标题 + 重置 → 类型 → 角色 → 情绪 → 文本/计数 → 保存提示 → 校验错误 →
 * 保存错误 → 保存/重新生成/取消。内容整体可滚动并加 `imePadding()`，键盘弹出时
 * 不会盖住文本或底部按钮。所有用户可见文案来自 `strings.xml`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentEditSheetContent(
    state: SegmentEditUiState,
    modifier: Modifier = Modifier,
    onTypeChange: (SegmentType) -> Unit = {},
    onRoleChange: (String) -> Unit = {},
    onEmotionChange: (Emotion) -> Unit = {},
    onTextChange: (String) -> Unit = {},
    onReset: () -> Unit = {},
    onSave: () -> Unit = {},
    onRetry: () -> Unit = {},
    onCancel: () -> Unit = {},
    onRegenerate: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SegmentEditHeader(state = state, onReset = onReset)

        TypeSelector(
            type = state.type,
            enabled = !state.saving,
            onTypeChange = onTypeChange,
        )
        RoleSelector(
            state = state,
            onRoleChange = onRoleChange,
        )
        EmotionSelector(
            emotion = state.emotion,
            enabled = !state.saving,
            onEmotionChange = onEmotionChange,
        )
        TextEditor(
            state = state,
            onTextChange = onTextChange,
        )

        Text(
            text = stringResource(R.string.segment_edit_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.validationErrors.isNotEmpty()) {
            ValidationCard(errors = state.validationErrors)
        }
        state.saveError?.let { message ->
            SaveErrorCard(
                message = message,
                enabled = !state.saving,
                onRetry = onRetry,
            )
        }

        SaveButton(
            saving = state.saving,
            enabled = state.canSave,
            onSave = onSave,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onRegenerate,
                enabled = !state.saving,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.segment_edit_regenerate))
            }
            TextButton(
                onClick = onCancel,
                enabled = !state.saving,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.segment_edit_cancel))
            }
        }
    }
}

@Composable
private fun SegmentEditHeader(
    state: SegmentEditUiState,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.segment_edit_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(
                    R.string.segment_edit_subtitle,
                    state.segmentNumber,
                    stringResource(state.type.labelRes()),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = onReset,
            enabled = state.isDirty && !state.saving,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.segment_edit_reset))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeSelector(
    type: SegmentType,
    enabled: Boolean,
    onTypeChange: (SegmentType) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(text = stringResource(R.string.segment_edit_type_label))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            EDITABLE_TYPES.forEachIndexed { index, candidate ->
                SegmentedButton(
                    selected = type == candidate,
                    onClick = { onTypeChange(candidate) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = EDITABLE_TYPES.size,
                    ),
                    enabled = enabled,
                    label = { Text(stringResource(candidate.labelRes())) },
                )
            }
        }
    }
}

/**
 * 角色下拉。
 *
 * 对白列出本书角色（可搜索）；旁白只有旁白且不可展开。当前 roleId 不在候选里时
 * 显示“未知角色”，同时校验卡片会提示重选。
 */
@Composable
private fun RoleSelector(
    state: SegmentEditUiState,
    onRoleChange: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val options = state.roleOptions
    val filtered = if (query.isBlank()) {
        options
    } else {
        options.filter { option ->
            option.name.contains(query, ignoreCase = true) ||
                option.roleId.contains(query, ignoreCase = true)
        }
    }
    val selected = options.firstOrNull { it.roleId == state.roleId.trim() }
    val display = selected?.let { roleLabel(it) }
        ?: stringResource(R.string.script_role_unknown)
    val canOpen = !state.saving && !state.isNarration && options.isNotEmpty()

    // 切到旁白后不会再需要角色菜单，顺手关掉并清空搜索。
    LaunchedEffect(state.type) {
        if (state.isNarration) {
            expanded = false
            query = ""
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(text = stringResource(R.string.segment_edit_role_label))
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = canOpen,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(
                    text = display,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Start,
                )
                Text(text = stringResource(R.string.segment_edit_dropdown_indicator))
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = {
                    expanded = false
                    query = ""
                },
                modifier = Modifier.widthIn(min = 260.dp),
            ) {
                if (options.size > 4) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        placeholder = {
                            Text(stringResource(R.string.segment_edit_role_search_placeholder))
                        },
                        singleLine = true,
                    )
                }
                if (filtered.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.segment_edit_role_no_match)) },
                        onClick = {},
                        enabled = false,
                    )
                } else {
                    filtered.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(roleLabel(option)) },
                            onClick = {
                                onRoleChange(option.roleId)
                                expanded = false
                                query = ""
                            },
                            enabled = !state.saving,
                            trailingIcon = {
                                if (option.roleId == state.roleId) {
                                    Text(
                                        text = stringResource(R.string.segment_edit_selected_indicator),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
        if (state.isNarration) {
            Text(
                text = stringResource(R.string.segment_edit_role_locked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 情绪下拉：11 种，显示中文标签 + wire（wire 是落库/API 稳定值，不能翻译）。 */
@Composable
private fun EmotionSelector(
    emotion: Emotion,
    enabled: Boolean,
    onEmotionChange: (Emotion) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(text = stringResource(R.string.segment_edit_emotion_label))
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(
                    text = emotionLabel(emotion),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Start,
                )
                Text(text = stringResource(R.string.segment_edit_dropdown_indicator))
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.widthIn(min = 260.dp),
            ) {
                Emotion.entries.forEach { candidate ->
                    DropdownMenuItem(
                        text = { Text(emotionLabel(candidate)) },
                        onClick = {
                            onEmotionChange(candidate)
                            expanded = false
                        },
                        enabled = enabled,
                        trailingIcon = {
                            if (candidate == emotion) {
                                Text(
                                    text = stringResource(R.string.segment_edit_selected_indicator),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TextEditor(
    state: SegmentEditUiState,
    onTextChange: (String) -> Unit,
) {
    val textError = state.validationErrors.any {
        it == SegmentEditError.EmptyText || it == SegmentEditError.TextTooLong
    }
    OutlinedTextField(
        value = state.text,
        onValueChange = onTextChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.saving,
        label = { Text(stringResource(R.string.segment_edit_text_label)) },
        placeholder = { Text(stringResource(R.string.segment_edit_text_placeholder)) },
        minLines = 4,
        maxLines = 8,
        isError = textError,
        supportingText = {
            Text(
                text = stringResource(
                    R.string.segment_edit_text_counter,
                    state.characterCount,
                    SegmentEditUiState.MAX_TEXT_LENGTH,
                ),
                color = if (textError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
    )
}

@Composable
private fun ValidationCard(errors: List<SegmentEditError>) {
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
                text = stringResource(R.string.segment_edit_validation_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            errors.distinct().forEach { error ->
                Text(
                    text = error.message(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun SaveErrorCard(
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
                text = stringResource(R.string.segment_edit_save_error, message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(
                onClick = onRetry,
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.segment_edit_retry))
            }
        }
    }
}

@Composable
private fun SaveButton(
    saving: Boolean,
    enabled: Boolean,
    onSave: () -> Unit,
) {
    Button(
        onClick = onSave,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        if (saving) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.segment_edit_saving))
        } else {
            Text(stringResource(R.string.segment_edit_save))
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun roleLabel(option: SegmentRoleOption): String = when {
    option.isNarrator -> stringResource(R.string.script_type_narration)
    option.name.isNotBlank() -> option.name
    else -> stringResource(R.string.script_role_unknown)
}

@Composable
private fun emotionLabel(emotion: Emotion): String = stringResource(
    R.string.segment_edit_emotion_option,
    stringResource(emotion.labelRes()),
    emotion.wire,
)

@Composable
private fun SegmentEditError.message(): String = when (this) {
    SegmentEditError.EmptyText -> stringResource(R.string.segment_edit_error_empty_text)
    SegmentEditError.TextTooLong -> stringResource(
        R.string.segment_edit_error_too_long,
        SegmentEditUiState.MAX_TEXT_LENGTH,
    )
    SegmentEditError.NarrationRequiresNarrator ->
        stringResource(R.string.segment_edit_error_narration_role)
    SegmentEditError.DialogueRequiresRole ->
        stringResource(R.string.segment_edit_error_dialogue_role)
    SegmentEditError.UnknownRole -> stringResource(R.string.segment_edit_error_unknown_role)
}

@StringRes
private fun SegmentType.labelRes(): Int = when (this) {
    SegmentType.Dialogue -> R.string.script_type_dialogue
    SegmentType.Narration -> R.string.script_type_narration
}

@StringRes
private fun Emotion.labelRes(): Int = when (this) {
    Emotion.Neutral -> R.string.script_emotion_neutral
    Emotion.Happy -> R.string.script_emotion_happy
    Emotion.Sad -> R.string.script_emotion_sad
    Emotion.Angry -> R.string.script_emotion_angry
    Emotion.Anxious -> R.string.script_emotion_anxious
    Emotion.Excited -> R.string.script_emotion_excited
    Emotion.Surprised -> R.string.script_emotion_surprised
    Emotion.SoftTone -> R.string.script_emotion_soft_tone
    Emotion.Serious -> R.string.script_emotion_serious
    Emotion.Calm -> R.string.script_emotion_calm
    Emotion.Whispering -> R.string.script_emotion_whispering
}

/** 可编辑类型顺序与 §12.4 S07 一致：对白在前、旁白在后。 */
private val EDITABLE_TYPES: List<SegmentType> = listOf(
    SegmentType.Dialogue,
    SegmentType.Narration,
)

// ---------------------------------------------------------------------------
// Preview：对白正常态 / 旁白态 / 校验错误态 / 保存失败可重试态。
// 演示文本只是预览数据；所有 UI 文案来自 strings.xml。
// ---------------------------------------------------------------------------

private fun previewRoles(): List<SegmentRoleOption> = listOf(
    SegmentRoleOption(roleId = NARRATOR_ROLE_ID, name = ""),
    SegmentRoleOption(roleId = "role_0001", name = "沈砚"),
    SegmentRoleOption(roleId = "role_0002", name = "顾青"),
)

@Preview(name = "SegmentEdit Dialogue", showBackground = true, backgroundColor = 0xFFF7F8FC)
@Composable
private fun SegmentEditDialoguePreview() {
    AudioForgeTheme(dynamicColor = false) {
        Surface {
            SegmentEditSheetContent(
                state = SegmentEditUiState(
                    segmentId = 7L,
                    segmentNumber = 3,
                    type = SegmentType.Dialogue,
                    roleId = "role_0001",
                    emotion = Emotion.Serious,
                    text = "这封信，你从哪里得来的？",
                    roles = previewRoles(),
                ),
            )
        }
    }
}

@Preview(name = "SegmentEdit Narration", showBackground = true, backgroundColor = 0xFFF7F8FC)
@Composable
private fun SegmentEditNarrationPreview() {
    AudioForgeTheme(dynamicColor = false) {
        Surface {
            SegmentEditSheetContent(
                state = SegmentEditUiState(
                    segmentId = 8L,
                    segmentNumber = 4,
                    type = SegmentType.Narration,
                    roleId = NARRATOR_ROLE_ID,
                    emotion = Emotion.SoftTone,
                    text = "山雨欲来，旧城的灯火在风里一盏盏暗下去。",
                    roles = previewRoles(),
                ),
            )
        }
    }
}

@Preview(
    name = "SegmentEdit Validation Error",
    showBackground = true,
    backgroundColor = 0xFFF7F8FC,
)
@Composable
private fun SegmentEditValidationPreview() {
    AudioForgeTheme(dynamicColor = false) {
        Surface {
            SegmentEditSheetContent(
                state = SegmentEditUiState(
                    segmentId = 9L,
                    segmentNumber = 5,
                    type = SegmentType.Dialogue,
                    roleId = "role_9999",
                    emotion = Emotion.Neutral,
                    text = "",
                    roles = previewRoles(),
                ),
            )
        }
    }
}

@Preview(name = "SegmentEdit Save Error", showBackground = true, backgroundColor = 0xFFF7F8FC)
@Composable
private fun SegmentEditSaveErrorPreview() {
    AudioForgeTheme(dynamicColor = false) {
        Surface {
            SegmentEditSheetContent(
                state = SegmentEditUiState(
                    segmentId = 10L,
                    segmentNumber = 6,
                    type = SegmentType.Dialogue,
                    roleId = "role_0002",
                    emotion = Emotion.Sad,
                    text = "雨停了，他却还没有回来。",
                    roles = previewRoles(),
                    saveError = "database is locked",
                ),
            )
        }
    }
}
