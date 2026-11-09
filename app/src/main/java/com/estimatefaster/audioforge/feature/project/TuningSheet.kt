@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.estimatefaster.audioforge.feature.project

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.data.repository.TuningDefaults
import com.estimatefaster.audioforge.data.repository.TuningRanges
import com.estimatefaster.audioforge.ui.components.ErrorState
import com.estimatefaster.audioforge.ui.components.ExpressiveCard
import com.estimatefaster.audioforge.ui.components.ExpressiveLoading
import com.estimatefaster.audioforge.ui.components.ExpressivePrimaryButton
import com.estimatefaster.audioforge.ui.components.ExpressiveTextButton
import com.estimatefaster.audioforge.ui.components.SectionHeader
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme
import com.estimatefaster.audioforge.ui.theme.spacing

/**
 * 角色微调 Sheet（P5-06 / 执行计划 §12.4 S09、§15 P5-06）。
 *
 * 有状态入口：收集 [TuningViewModel.state]，把 [TuningSheetEvent.Saved] 转成
 * 宿主回调 `onSaved`；下滑/取消/返回交给 `onDismiss`。
 *
 * 集成提示：在人物页/项目页用
 * `if (showTuning) TuningSheet(viewModel = ..., onDismiss = { showTuning = false },
 * onSaved = { showTuning = false })` 即可。
 *
 * @param onDismiss 取消/下滑/返回时关闭 Sheet（不写库）
 * @param onSaved 保存成功后的回调（通常关闭 Sheet 并刷新角色卡）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TuningSheet(
    viewModel: TuningViewModel,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val currentOnSaved by rememberUpdatedState(onSaved)

    // 保存成功只发一次事件；关闭动作交给宿主，避免 Sheet 内部重复导航。
    LaunchedEffect(viewModel) {
        viewModel.events.collect {
            currentOnSaved()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        shape = MaterialTheme.shapes.extraExtraLarge,
    ) {
        TuningSheetContent(
            state = state,
            onSpeedChange = viewModel::setSpeed,
            onVolumeChange = viewModel::setVolume,
            onPitchChange = viewModel::setPitch,
            onReset = viewModel::resetToDefaults,
            onRetryLoad = viewModel::load,
            onSave = viewModel::save,
            onDismiss = onDismiss,
        )
    }
}

/**
 * 无状态 Sheet 内容；Preview 与 UI 测试直接渲染它。
 *
 * Expressive 布局：primaryContainer 头卡 → 参数卡（三个滑杆 + 范围/跟随全局）
 * → 校验/保存错误卡 → 唯一“保存”主按钮 + 取消。保存条件仍由
 * [TuningUiState.canSave] 决定。
 */
@Composable
fun TuningSheetContent(
    state: TuningUiState,
    modifier: Modifier = Modifier,
    onSpeedChange: (Double) -> Unit = {},
    onVolumeChange: (Double) -> Unit = {},
    onPitchChange: (Double) -> Unit = {},
    onReset: () -> Unit = {},
    onRetryLoad: () -> Unit = {},
    onSave: () -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    val loadError = state.loadError
    val saveError = state.saveError

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MaterialTheme.spacing.xl)
            .padding(
                top = MaterialTheme.spacing.sm,
                bottom = MaterialTheme.spacing.xl,
            ),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.lg),
    ) {
        TuningHeader(state)

        when {
            state.loading -> ExpressiveLoading(
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.tuning_loading),
            )

            loadError != null -> {
                ErrorState(
                    message = stringResource(R.string.tuning_load_error, loadError),
                    onRetry = onRetryLoad,
                )
                ExpressiveTextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            else -> {
                TuningSliders(
                    state = state,
                    onSpeedChange = onSpeedChange,
                    onVolumeChange = onVolumeChange,
                    onPitchChange = onPitchChange,
                    onReset = onReset,
                )

                if (state.validationErrors.isNotEmpty()) {
                    TuningValidationCard(state)
                }
                if (saveError != null) {
                    TuningSaveErrorCard(message = saveError, onRetry = onSave)
                }

                if (!state.isDirty && state.isValid) {
                    Text(
                        text = stringResource(R.string.ux_tuning_no_changes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.ux_tuning_save_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ExpressivePrimaryButton(
                    text = if (state.saving) {
                        stringResource(R.string.action_saving)
                    } else {
                        stringResource(R.string.action_save)
                    },
                    onClick = onSave,
                    enabled = state.canSave,
                    modifier = Modifier.fillMaxWidth(),
                )
                ExpressiveTextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun TuningHeader(state: TuningUiState) {
    val content = MaterialTheme.colorScheme.onPrimaryContainer
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = content,
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
        ) {
            Text(
                text = stringResource(R.string.ux_tuning_header_label),
                style = MaterialTheme.typography.labelLargeEmphasized,
                color = content.copy(alpha = 0.8f),
            )
            Text(
                text = state.displayName,
                style = MaterialTheme.typography.headlineSmallEmphasized,
            )
            Text(
                text = stringResource(
                    R.string.tuning_voice,
                    state.voiceLabel ?: stringResource(R.string.tuning_voice_unassigned),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = content.copy(alpha = 0.8f),
            )
            if (state.unknownRole) {
                Text(
                    text = stringResource(R.string.tuning_role_unknown),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun TuningSliders(
    state: TuningUiState,
    onSpeedChange: (Double) -> Unit,
    onVolumeChange: (Double) -> Unit,
    onPitchChange: (Double) -> Unit,
    onReset: () -> Unit,
) {
    val enabled = !state.saving
    ExpressiveCard {
        SectionHeader(
            title = stringResource(R.string.tuning_section_title),
            subtitle = stringResource(R.string.tuning_section_subtitle),
            actionText = if (state.canReset) stringResource(R.string.tuning_action_reset) else null,
            onAction = if (state.canReset) onReset else null,
        )
        TuningSlider(
            label = stringResource(R.string.tuning_speed_label),
            valueText = formatTuningValue(TuningField.Speed, state.speed),
            rangeText = stringResource(R.string.tuning_speed_range),
            followsGlobal = state.followsGlobal(TuningField.Speed),
            value = state.speed.toFloat(),
            valueRange = TuningRanges.SPEED.toFloatRange(),
            enabled = enabled,
            onValueChange = {
                onSpeedChange(snapTuningValue(TuningField.Speed, it.toDouble()))
            },
        )
        TuningSlider(
            label = stringResource(R.string.tuning_volume_label),
            valueText = formatTuningValue(TuningField.Volume, state.volume),
            rangeText = stringResource(R.string.tuning_volume_range),
            followsGlobal = state.followsGlobal(TuningField.Volume),
            value = state.volume.toFloat(),
            valueRange = TuningRanges.VOLUME.toFloatRange(),
            enabled = enabled,
            onValueChange = {
                onVolumeChange(snapTuningValue(TuningField.Volume, it.toDouble()))
            },
        )
        TuningSlider(
            label = stringResource(R.string.tuning_pitch_label),
            valueText = formatTuningValue(TuningField.Pitch, state.pitch),
            rangeText = stringResource(R.string.tuning_pitch_range),
            followsGlobal = state.followsGlobal(TuningField.Pitch),
            value = state.pitch.toFloat(),
            valueRange = TuningRanges.PITCH.toFloatRange(),
            enabled = enabled,
            onValueChange = {
                onPitchChange(snapTuningValue(TuningField.Pitch, it.toDouble()))
            },
        )
        Text(
            text = stringResource(R.string.tuning_section_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TuningSlider(
    label: String,
    valueText: String,
    rangeText: String,
    followsGlobal: Boolean,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
) {
    val description = stringResource(
        R.string.tuning_slider_content_description,
        label,
        valueText,
        rangeText,
    )
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmallEmphasized,
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            modifier = Modifier.semantics { contentDescription = description },
            enabled = enabled,
            valueRange = valueRange,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.ux_tuning_range_label, rangeText),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (followsGlobal) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Text(
                        text = stringResource(R.string.tuning_follow_global),
                        modifier = Modifier.padding(
                            horizontal = MaterialTheme.spacing.sm,
                            vertical = MaterialTheme.spacing.xxs,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun TuningValidationCard(state: TuningUiState) {
    NoticeCard(
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Text(
            text = stringResource(R.string.tuning_validation_title),
            style = MaterialTheme.typography.titleSmallEmphasized,
        )
        state.validationErrors.forEach { field ->
            Text(
                text = validationMessage(field),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TuningSaveErrorCard(message: String, onRetry: () -> Unit) {
    NoticeCard(
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Text(
            text = stringResource(R.string.tuning_save_error_title),
            style = MaterialTheme.typography.titleSmallEmphasized,
        )
        Text(
            text = stringResource(R.string.action_save_error, message),
            style = MaterialTheme.typography.bodySmall,
        )
        ExpressiveTextButton(
            text = stringResource(R.string.action_retry),
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun NoticeCard(
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
            content = content,
        )
    }
}

@Composable
private fun validationMessage(field: TuningField): String = when (field) {
    TuningField.Speed -> stringResource(R.string.tuning_validation_speed)
    TuningField.Volume -> stringResource(R.string.tuning_validation_volume)
    TuningField.Pitch -> stringResource(R.string.tuning_validation_pitch)
}

private fun ClosedFloatingPointRange<Double>.toFloatRange(): ClosedFloatingPointRange<Float> =
    start.toFloat()..endInclusive.toFloat()

// ---------------------------------------------------------------------------
// Preview：正常、未知角色（深色/200%）、越界校验。
// ---------------------------------------------------------------------------

@Preview(name = "Tuning Sheet", showBackground = true, backgroundColor = 0xFFF7F8FC)
@Composable
private fun TuningSheetPreview() {
    val defaults = TuningDefaults(speed = 1.0, volume = 1.0, pitch = 0.0)
    AudioForgeTheme(dynamicColor = false) {
        Surface {
            TuningSheetContent(
                state = TuningUiState(
                    novelId = 1L,
                    roleId = "role_0001",
                    roleName = "林黛玉",
                    voiceLabel = "【女主】温柔清亮",
                    defaults = defaults,
                    initial = TuningDraft(speed = 1.25, pitch = 2.0),
                    draft = TuningDraft(speed = 1.25, pitch = 2.0),
                    loading = false,
                ),
            )
        }
    }
}

@Preview(
    name = "Tuning Unknown Role Dark 200%",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    fontScale = 2f,
)
@Composable
private fun TuningUnknownRolePreview() {
    AudioForgeTheme(darkTheme = true, dynamicColor = false) {
        Surface {
            TuningSheetContent(
                state = TuningUiState(
                    novelId = 1L,
                    roleId = "role_0099",
                    roleName = "role_0099",
                    voiceLabel = null,
                    defaults = TuningDefaults(speed = 1.0, volume = 1.0, pitch = 0.0),
                    loading = false,
                    unknownRole = true,
                ),
            )
        }
    }
}

@Preview(name = "Tuning Validation Error", showBackground = true, backgroundColor = 0xFFF7F8FC)
@Composable
private fun TuningValidationPreview() {
    AudioForgeTheme(dynamicColor = false) {
        Surface {
            TuningSheetContent(
                state = TuningUiState(
                    novelId = 1L,
                    roleId = "role_0002",
                    roleName = "贾宝玉",
                    voiceLabel = "【男主】沉稳磁性",
                    defaults = TuningDefaults(),
                    initial = TuningDraft(),
                    draft = TuningDraft(speed = 2.4, volume = -0.5, pitch = 14.0),
                    loading = false,
                ),
            )
        }
    }
}
