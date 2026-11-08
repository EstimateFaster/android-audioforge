package com.estimatefaster.audioforge.feature.project

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.data.repository.TuningDefaults
import com.estimatefaster.audioforge.data.repository.TuningRanges
import com.estimatefaster.audioforge.ui.components.ErrorState
import com.estimatefaster.audioforge.ui.components.SectionHeader
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme

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
 * 布局：标题/角色/音色 → 三个滑杆 + 当前值 + 范围 + “跟随全局”提示 → 恢复默认
 * → 校验/保存错误 → 取消/保存。文案全部来自 `strings.xml`。
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
            .padding(horizontal = 24.dp)
            .padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TuningHeader(state)

        when {
            state.loading -> TuningLoading()
            loadError != null -> ErrorState(
                message = stringResource(R.string.tuning_load_error, loadError),
                onRetry = onRetryLoad,
            )

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

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = onDismiss,
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
                        Text(
                            text = if (state.saving) {
                                stringResource(R.string.action_saving)
                            } else {
                                stringResource(R.string.action_save)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TuningHeader(state: TuningUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.tuning_sheet_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.tuning_character, state.displayName),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(
                R.string.tuning_voice,
                state.voiceLabel ?: stringResource(R.string.tuning_voice_unassigned),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
private fun TuningLoading() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.tuning_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.titleMedium,
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
                text = rangeText,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (followsGlobal) {
                Text(
                    text = stringResource(R.string.tuning_follow_global),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun TuningValidationCard(state: TuningUiState) {
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
                text = stringResource(R.string.tuning_validation_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
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

@Composable
private fun TuningSaveErrorCard(message: String, onRetry: () -> Unit) {
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
                text = stringResource(R.string.tuning_save_error_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = stringResource(R.string.action_save_error, message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(
                onClick = onRetry,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.action_retry))
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
