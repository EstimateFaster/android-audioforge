@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.estimatefaster.audioforge.feature.project

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.data.repository.TuningDefaults
import com.estimatefaster.audioforge.data.repository.TuningRanges
import com.estimatefaster.audioforge.ui.forge.ForgeCard
import com.estimatefaster.audioforge.ui.forge.ForgeError
import com.estimatefaster.audioforge.ui.forge.ForgeIcon
import com.estimatefaster.audioforge.ui.forge.ForgeIcons
import com.estimatefaster.audioforge.ui.forge.ForgeLoading
import com.estimatefaster.audioforge.ui.forge.ForgePrimaryAction
import com.estimatefaster.audioforge.ui.forge.ForgeSecondaryAction
import com.estimatefaster.audioforge.ui.forge.ForgeSectionHeader
import com.estimatefaster.audioforge.ui.forge.ForgeSheetHeader
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
 * 结构对齐 Forge Framework 的调参 Sheet：
 * SheetHeader → 角色 hero → 三个滑杆（值 / 跟随全局 / 范围提示）→ 恢复默认
 * → 校验 / 保存错误 → 唯一主操作“保存参数”。
 *
 * 校验、脏标记、可保存与否仍由 [TuningUiState] 的派生属性决定；滑杆输入继续走
 * [snapTuningValue]，保存仍由 ViewModel 写仓库。文案全部来自资源文件。
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
            .padding(horizontal = MaterialTheme.spacing.lg)
            .padding(top = MaterialTheme.spacing.xs, bottom = MaterialTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.lg),
    ) {
        ForgeSheetHeader(
            icon = ForgeIcons.Sliders,
            title = stringResource(R.string.ux2_tuning_title),
            onClose = onDismiss,
            subtitle = stringResource(R.string.ux2_tuning_subtitle),
        )

        TuningRoleCard(state = state)

        when {
            state.loading -> ForgeLoading(
                label = stringResource(R.string.tuning_loading),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp),
            )

            loadError != null -> ForgeError(
                message = stringResource(R.string.tuning_load_error, loadError),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp),
                onRetry = onRetryLoad,
                retryLabel = stringResource(R.string.action_retry),
            )

            else -> {
                TuningSliders(
                    state = state,
                    onSpeedChange = onSpeedChange,
                    onVolumeChange = onVolumeChange,
                    onPitchChange = onPitchChange,
                )

                ForgeSecondaryAction(
                    icon = ForgeIcons.RotateCcw,
                    label = stringResource(R.string.tuning_action_reset),
                    onClick = onReset,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.canReset,
                )

                if (state.validationErrors.isNotEmpty()) {
                    TuningValidationCard(state = state)
                }
                if (saveError != null) {
                    TuningSaveErrorCard(message = saveError, onRetry = onSave)
                }

                ForgePrimaryAction(
                    icon = ForgeIcons.Check,
                    label = if (state.saving) {
                        stringResource(R.string.action_saving)
                    } else {
                        stringResource(R.string.ux2_tuning_save)
                    },
                    onClick = onSave,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.canSave,
                    loading = state.saving,
                )
                Text(
                    text = stringResource(R.string.ux2_tuning_save_hint),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun TuningRoleCard(state: TuningUiState) {
    ForgeCard(contentPadding = PaddingValues(MaterialTheme.spacing.md)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(56.dp),
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ForgeIcon(
                        icon = ForgeIcons.UserRound,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = state.displayName,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.tuning_voice,
                        state.voiceLabel ?: stringResource(R.string.tuning_voice_unassigned),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TuningStatusPill(state = state)
        }
        if (state.unknownRole) {
            Text(
                text = stringResource(R.string.tuning_role_unknown),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun TuningStatusPill(state: TuningUiState) {
    val visual = when {
        state.unknownRole -> TuningStatusVisual(
            icon = ForgeIcons.AlertCircle,
            labelRes = R.string.ux2_tuning_status_unknown,
            container = MaterialTheme.colorScheme.errorContainer,
            content = MaterialTheme.colorScheme.onErrorContainer,
        )

        state.isDirty -> TuningStatusVisual(
            icon = ForgeIcons.Edit,
            labelRes = R.string.ux2_tuning_status_dirty,
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
        )

        state.draft.followsGlobal -> TuningStatusVisual(
            icon = ForgeIcons.Globe,
            labelRes = R.string.ux2_tuning_status_global,
            container = MaterialTheme.colorScheme.surfaceContainerHigh,
            content = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        else -> TuningStatusVisual(
            icon = ForgeIcons.CheckCircle,
            labelRes = R.string.ux2_tuning_status_clean,
            container = MaterialTheme.colorScheme.secondaryContainer,
            content = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = visual.container,
        contentColor = visual.content,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = MaterialTheme.spacing.sm,
                vertical = MaterialTheme.spacing.xs,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
        ) {
            ForgeIcon(
                icon = visual.icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = visual.content,
            )
            Text(
                text = stringResource(visual.labelRes),
                style = MaterialTheme.typography.labelSmallEmphasized,
            )
        }
    }
}

private data class TuningStatusVisual(
    @DrawableRes val icon: Int,
    @StringRes val labelRes: Int,
    val container: Color,
    val content: Color,
)

@Composable
private fun TuningSliders(
    state: TuningUiState,
    onSpeedChange: (Double) -> Unit,
    onVolumeChange: (Double) -> Unit,
    onPitchChange: (Double) -> Unit,
) {
    val enabled = !state.saving
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
        ForgeSectionHeader(
            title = stringResource(R.string.ux2_tuning_section_title),
            subtitle = stringResource(R.string.ux2_tuning_section_hint),
        )
        ForgeCard(contentPadding = PaddingValues(MaterialTheme.spacing.md)) {
            TuningSliderRow(
                label = stringResource(R.string.tuning_speed_label),
                valueText = formatTuningValue(TuningField.Speed, state.speed),
                followsGlobal = state.followsGlobal(TuningField.Speed),
                lowLabel = stringResource(R.string.ux2_tuning_speed_low),
                highLabel = stringResource(R.string.ux2_tuning_speed_high),
                value = state.speed.toFloat(),
                valueRange = TuningRanges.SPEED.toFloatRange(),
                enabled = enabled,
                onValueChange = {
                    onSpeedChange(snapTuningValue(TuningField.Speed, it.toDouble()))
                },
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = MaterialTheme.spacing.sm))
            TuningSliderRow(
                label = stringResource(R.string.tuning_volume_label),
                valueText = formatTuningValue(TuningField.Volume, state.volume),
                followsGlobal = state.followsGlobal(TuningField.Volume),
                lowLabel = stringResource(R.string.ux2_tuning_volume_low),
                highLabel = stringResource(R.string.ux2_tuning_volume_high),
                value = state.volume.toFloat(),
                valueRange = TuningRanges.VOLUME.toFloatRange(),
                enabled = enabled,
                onValueChange = {
                    onVolumeChange(snapTuningValue(TuningField.Volume, it.toDouble()))
                },
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = MaterialTheme.spacing.sm))
            TuningSliderRow(
                label = stringResource(R.string.tuning_pitch_label),
                valueText = formatTuningValue(TuningField.Pitch, state.pitch),
                followsGlobal = state.followsGlobal(TuningField.Pitch),
                lowLabel = stringResource(R.string.ux2_tuning_pitch_low),
                highLabel = stringResource(R.string.ux2_tuning_pitch_high),
                value = state.pitch.toFloat(),
                valueRange = TuningRanges.PITCH.toFloatRange(),
                enabled = enabled,
                onValueChange = {
                    onPitchChange(snapTuningValue(TuningField.Pitch, it.toDouble()))
                },
            )
        }
    }
}

@Composable
private fun TuningSliderRow(
    label: String,
    valueText: String,
    followsGlobal: Boolean,
    lowLabel: String,
    highLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
) {
    val rangeText = stringResource(R.string.ux2_tuning_range_hint, lowLabel, highLabel)
    val sliderDescription = stringResource(
        R.string.tuning_slider_content_description,
        label,
        valueText,
        rangeText,
    )
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
            modifier = Modifier.semantics { contentDescription = sliderDescription },
            enabled = enabled,
            valueRange = valueRange,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = lowLabel,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (followsGlobal) {
                Text(
                    text = stringResource(R.string.tuning_follow_global),
                    style = MaterialTheme.typography.labelSmallEmphasized,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = highLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TuningValidationCard(state: TuningUiState) {
    ForgeCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            ForgeIcon(
                icon = ForgeIcons.AlertCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.tuning_validation_title),
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                state.validationErrors.forEach { field ->
                    Text(
                        text = validationMessage(field),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun TuningSaveErrorCard(
    message: String,
    onRetry: () -> Unit,
) {
    ForgeCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            ForgeIcon(
                icon = ForgeIcons.ErrorCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
            ) {
                Text(
                    text = stringResource(R.string.tuning_save_error_title),
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = stringResource(R.string.action_save_error, message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                ForgeSecondaryAction(
                    icon = ForgeIcons.Refresh,
                    label = stringResource(R.string.action_retry),
                    onClick = onRetry,
                )
            }
        }
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
// Preview：正常、未知角色（深色/200%）、越界校验。示例数据只在 Preview 区使用。
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
                    roleName = "沈砚",
                    voiceLabel = "【男主】沉稳磁性",
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
                    roleName = "苏晚",
                    voiceLabel = "【女主】温柔清亮",
                    defaults = TuningDefaults(),
                    initial = TuningDraft(),
                    draft = TuningDraft(speed = 2.4, volume = -0.5, pitch = 14.0),
                    loading = false,
                ),
            )
        }
    }
}
