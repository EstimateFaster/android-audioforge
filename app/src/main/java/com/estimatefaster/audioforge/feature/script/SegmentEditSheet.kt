@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.estimatefaster.audioforge.feature.script

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.core.model.Emotion
import com.estimatefaster.audioforge.core.model.NARRATOR_ROLE_ID
import com.estimatefaster.audioforge.core.model.SegmentType
import com.estimatefaster.audioforge.data.db.SegmentEntity
import com.estimatefaster.audioforge.ui.components.RoleAvatar
import com.estimatefaster.audioforge.ui.forge.ForgeCard
import com.estimatefaster.audioforge.ui.forge.ForgeChip
import com.estimatefaster.audioforge.ui.forge.ForgeIcon
import com.estimatefaster.audioforge.ui.forge.ForgeIcons
import com.estimatefaster.audioforge.ui.forge.ForgePrimaryAction
import com.estimatefaster.audioforge.ui.forge.ForgeSecondaryAction
import com.estimatefaster.audioforge.ui.forge.ForgeSegmented
import com.estimatefaster.audioforge.ui.forge.ForgeSheetHeader
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme
import com.estimatefaster.audioforge.ui.theme.spacing

/**
 * 片段编辑 Sheet（Forge Framework v1）。
 *
 * 有状态入口：收集 [SegmentEditViewModel.state]，把一次性事件转成调用方回调：
 * - `Saved` → `onSaved(updated)`；
 * - `Cancelled` → `onDismiss`；
 * - `RegenerateRequested` → `onRegenerate(segmentId)`（本 Sheet 不直接调用 TTS）。
 *
 * @param onDismiss 用户取消/下滑/返回时关闭 Sheet
 * @param onSaved 保存成功；`segment` 是实际写入的实体
 * @param onRegenerate “重新生成此句”回调；调用方负责入队
 */
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
 * 无状态 Sheet 内容；Preview 与 UI 测试直接渲染它。
 *
 * 布局对齐 prototype 的 SegmentEdit Sheet：固定 Sheet 头 + 角色 hero + 类型/角色/
 * 情绪/文本编辑区 + 固定底部操作（重置 / 重新生成 / 保存）。文案全部来自
 * `strings_ux_v2_script_characters.xml`。
 */
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
            .fillMaxHeight(0.92f)
            .imePadding()
            .navigationBarsPadding(),
    ) {
        ForgeSheetHeader(
            icon = ForgeIcons.Edit,
            title = stringResource(R.string.ux2_segment_title),
            onClose = onCancel,
            subtitle = stringResource(
                R.string.ux2_segment_header_subtitle,
                state.segmentNumber,
                stringResource(state.type.labelRes()),
            ),
            modifier = Modifier.padding(
                start = MaterialTheme.spacing.lg,
                end = MaterialTheme.spacing.sm,
                top = MaterialTheme.spacing.sm,
                bottom = MaterialTheme.spacing.xs,
            ),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = MaterialTheme.spacing.lg,
                    vertical = MaterialTheme.spacing.sm,
                ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.lg),
        ) {
            SegmentHero(state = state)
            TypeSection(
                state = state,
                onTypeChange = onTypeChange,
            )
            RoleSection(
                state = state,
                onRoleChange = onRoleChange,
            )
            EmotionSection(
                state = state,
                onEmotionChange = onEmotionChange,
            )
            TextSection(
                state = state,
                onTextChange = onTextChange,
            )
            Text(
                text = stringResource(R.string.ux2_segment_hint),
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
        }

        SegmentFooter(
            state = state,
            onReset = onReset,
            onRegenerate = onRegenerate,
            onSave = onSave,
        )
    }
}

@Composable
private fun SegmentHero(state: SegmentEditUiState) {
    val roleName = state.selectedRole?.let { option -> roleLabel(option) }
        ?: stringResource(R.string.ux2_script_unknown_role)

    ForgeCard(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
        ) {
            RoleAvatar(name = roleName, size = 48.dp, decorative = true)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.ux2_segment_hero_title, state.segmentNumber),
                    style = MaterialTheme.typography.titleSmallEmphasized,
                )
                Text(
                    text = stringResource(
                        R.string.ux2_segment_hero_subtitle,
                        stringResource(state.type.labelRes()),
                        stringResource(state.emotion.labelRes()),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SegmentCurrentTag()
        }
    }
}

@Composable
private fun SegmentCurrentTag() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ForgeIcon(
            icon = ForgeIcons.AudioLines,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.ux2_segment_current),
            style = MaterialTheme.typography.labelSmallEmphasized,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}

@Composable
private fun TypeSection(
    state: SegmentEditUiState,
    onTypeChange: (SegmentType) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
        SectionLabel(text = stringResource(R.string.ux2_segment_section_type))
        ForgeSegmented(
            options = listOf(
                ForgeIcons.UserRound to stringResource(R.string.ux2_script_type_dialogue),
                ForgeIcons.BookOpen to stringResource(R.string.ux2_script_type_narration),
            ),
            selectedIndex = if (state.type == SegmentType.Dialogue) 0 else 1,
            onSelect = { index ->
                if (!state.saving) {
                    onTypeChange(
                        if (index == 0) SegmentType.Dialogue else SegmentType.Narration,
                    )
                }
            },
        )
    }
}

@Composable
private fun RoleSection(
    state: SegmentEditUiState,
    onRoleChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
        SectionLabel(text = stringResource(R.string.ux2_segment_section_role))
        if (state.isNarration) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = MaterialTheme.spacing.sm,
                        vertical = MaterialTheme.spacing.xxs,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xxs),
                ) {
                    ForgeIcon(
                        icon = ForgeIcons.BookOpen,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.ux2_script_type_narration),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = stringResource(R.string.ux2_segment_role_locked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val options = state.roleOptions.filterNot { option -> option.isNarrator }
            if (options.isEmpty()) {
                Text(
                    text = stringResource(R.string.ux2_segment_role_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
                ) {
                    options.forEach { option ->
                        ForgeChip(
                            label = roleLabel(option),
                            selected = option.roleId == state.roleId.trim(),
                            onClick = {
                                if (!state.saving) onRoleChange(option.roleId)
                            },
                            icon = ForgeIcons.UserRound,
                        )
                    }
                }
            }
            if (state.selectedRole == null) {
                Text(
                    text = stringResource(R.string.ux2_segment_unknown_role_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun EmotionSection(
    state: SegmentEditUiState,
    onEmotionChange: (Emotion) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
        SectionLabel(text = stringResource(R.string.ux2_segment_section_emotion))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        ) {
            Emotion.entries.forEach { emotion ->
                ForgeChip(
                    label = stringResource(emotion.labelRes()),
                    selected = emotion == state.emotion,
                    onClick = { if (!state.saving) onEmotionChange(emotion) },
                    icon = ForgeIcons.Sparkles,
                )
            }
        }
    }
}

@Composable
private fun TextSection(
    state: SegmentEditUiState,
    onTextChange: (String) -> Unit,
) {
    val textError = state.validationErrors.any { error ->
        error == SegmentEditError.EmptyText || error == SegmentEditError.TextTooLong
    }
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
        SectionLabel(text = stringResource(R.string.ux2_segment_section_text))
        OutlinedTextField(
            value = state.text,
            onValueChange = onTextChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.saving,
            placeholder = {
                Text(stringResource(R.string.ux2_segment_text_placeholder))
            },
            minLines = 4,
            maxLines = 10,
            isError = textError,
            shape = MaterialTheme.shapes.large,
            supportingText = {
                Text(
                    text = stringResource(
                        R.string.ux2_segment_text_hint,
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
}

@Composable
private fun ValidationCard(errors: List<SegmentEditError>) {
    ForgeCard(
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentPadding = PaddingValues(MaterialTheme.spacing.lg),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        ) {
            ForgeIcon(
                icon = ForgeIcons.AlertCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = stringResource(R.string.ux2_segment_validation_title),
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                },
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        errors.distinct().forEach { error ->
            Text(
                text = error.message(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun SaveErrorCard(
    message: String,
    enabled: Boolean,
    onRetry: () -> Unit,
) {
    ForgeCard(
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentPadding = PaddingValues(MaterialTheme.spacing.lg),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        ) {
            ForgeIcon(
                icon = ForgeIcons.AlertCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = stringResource(R.string.ux2_segment_save_error, message),
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        ForgeSecondaryAction(
            icon = ForgeIcons.Refresh,
            label = stringResource(R.string.ux2_segment_retry),
            onClick = onRetry,
            enabled = enabled,
        )
    }
}

@Composable
private fun SegmentFooter(
    state: SegmentEditUiState,
    onReset: () -> Unit,
    onRegenerate: () -> Unit,
    onSave: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = MaterialTheme.spacing.lg,
                end = MaterialTheme.spacing.lg,
                top = MaterialTheme.spacing.sm,
                bottom = MaterialTheme.spacing.lg,
            ),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
            ForgeSecondaryAction(
                icon = ForgeIcons.RotateCcw,
                label = stringResource(R.string.ux2_segment_reset),
                onClick = onReset,
                modifier = Modifier.weight(1f),
                enabled = state.isDirty && !state.saving,
            )
            ForgeSecondaryAction(
                icon = ForgeIcons.Refresh,
                label = stringResource(R.string.ux2_segment_regenerate),
                onClick = onRegenerate,
                modifier = Modifier.weight(1f),
                enabled = !state.saving,
            )
        }
        ForgePrimaryAction(
            icon = ForgeIcons.Check,
            label = stringResource(
                if (state.saving) R.string.ux2_segment_saving else R.string.ux2_segment_save,
            ),
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.canSave,
            loading = state.saving,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMediumEmphasized,
    )
}

@Composable
private fun roleLabel(option: SegmentRoleOption): String = when {
    option.isNarrator -> stringResource(R.string.ux2_script_type_narration)
    option.name.isNotBlank() -> option.name
    else -> stringResource(R.string.ux2_script_unknown_role)
}

@Composable
private fun SegmentEditError.message(): String = when (this) {
    SegmentEditError.EmptyText -> stringResource(R.string.ux2_segment_error_empty)
    SegmentEditError.TextTooLong -> stringResource(
        R.string.ux2_segment_error_too_long,
        SegmentEditUiState.MAX_TEXT_LENGTH,
    )
    SegmentEditError.NarrationRequiresNarrator ->
        stringResource(R.string.ux2_segment_error_narration_role)
    SegmentEditError.DialogueRequiresRole ->
        stringResource(R.string.ux2_segment_error_dialogue_role)
    SegmentEditError.UnknownRole -> stringResource(R.string.ux2_segment_error_unknown_role)
}

@StringRes
private fun SegmentType.labelRes(): Int = when (this) {
    SegmentType.Dialogue -> R.string.ux2_script_type_dialogue
    SegmentType.Narration -> R.string.ux2_script_type_narration
}

@StringRes
private fun Emotion.labelRes(): Int = when (this) {
    Emotion.Neutral -> R.string.ux2_emotion_neutral
    Emotion.Happy -> R.string.ux2_emotion_happy
    Emotion.Sad -> R.string.ux2_emotion_sad
    Emotion.Angry -> R.string.ux2_emotion_angry
    Emotion.Anxious -> R.string.ux2_emotion_anxious
    Emotion.Excited -> R.string.ux2_emotion_excited
    Emotion.Surprised -> R.string.ux2_emotion_surprised
    Emotion.SoftTone -> R.string.ux2_emotion_soft_tone
    Emotion.Serious -> R.string.ux2_emotion_serious
    Emotion.Calm -> R.string.ux2_emotion_calm
    Emotion.Whispering -> R.string.ux2_emotion_whispering
}

// ---------------------------------------------------------------------------
// Preview：对白正常态 / 校验错误态。演示文本只是固定夹具。
// ---------------------------------------------------------------------------

private fun previewRoles(): List<SegmentRoleOption> = listOf(
    SegmentRoleOption(roleId = NARRATOR_ROLE_ID, name = ""),
    SegmentRoleOption(roleId = "role_0001", name = "沈砚"),
    SegmentRoleOption(roleId = "role_0002", name = "顾青"),
)

@Preview(name = "SegmentEdit Forge", showBackground = true, backgroundColor = 0xFFF7F9F8)
@Composable
private fun SegmentEditForgePreview() {
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

@Preview(name = "SegmentEdit Validation", showBackground = true, backgroundColor = 0xFFF7F9F8)
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
