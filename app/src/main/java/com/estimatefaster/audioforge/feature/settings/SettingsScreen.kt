@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.estimatefaster.audioforge.feature.settings

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.BuildConfig
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.core.settings.AppSettings
import com.estimatefaster.audioforge.core.settings.ThemeMode
import com.estimatefaster.audioforge.ui.components.StatusPill
import com.estimatefaster.audioforge.ui.components.StatusTone
import com.estimatefaster.audioforge.ui.forge.ForgeCard
import com.estimatefaster.audioforge.ui.forge.ForgeError
import com.estimatefaster.audioforge.ui.forge.ForgeIcon
import com.estimatefaster.audioforge.ui.forge.ForgeIcons
import com.estimatefaster.audioforge.ui.forge.ForgeLoading
import com.estimatefaster.audioforge.ui.forge.ForgePrimaryAction
import com.estimatefaster.audioforge.ui.forge.ForgeSecondaryAction
import com.estimatefaster.audioforge.ui.forge.ForgeSegmented
import com.estimatefaster.audioforge.ui.forge.ForgeTopBar
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme
import com.estimatefaster.audioforge.ui.theme.spacing

/**
 * 全局设置页有状态入口（P4-09 / §7.4、§12.4 S11）。
 *
 * 公开签名保持不变：收集 [SettingsViewModel.state] 后委托给无状态版本；
 * 保存/Key 写入只通过 ViewModel 触达仓库，UI 不直接读写 DataStore。
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onOpenLicenses: () -> Unit = {},
    onOpenStorage: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onRestartOnboarding: () -> Unit = {},
    versionName: String = BuildConfig.VERSION_NAME,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state,
        modifier = modifier,
        onBack = onBack,
        onFieldChange = { field, value -> viewModel.onFieldChange(field, value) },
        onToggleChange = { field, value -> viewModel.onFieldChange(field, value) },
        onSave = viewModel::save,
        onRetry = viewModel::retry,
        onSetKey = viewModel::setKey,
        onClearKey = viewModel::clearKey,
        onOpenLicenses = onOpenLicenses,
        onOpenStorage = onOpenStorage,
        onOpenAbout = onOpenAbout,
        onRestartOnboarding = onRestartOnboarding,
        versionName = versionName,
    )
}

/**
 * 无状态设置页。
 *
 * 布局严格按 `docs/prototype/forge-framework.html` 的 Settings 重写：
 * TopBar → API 状态 hero → 7 组卡片（API / 生成 / 网络 / 设备 / 外观 / 存储 / 关于）
 * → 唯一主按钮「保存设置」。
 *
 * 行为口径与旧版一致：
 * - 每行都有 leading Lucide 图标；所有操作都是 icon+text 或 icon-only；
 * - 文本/数字字段点整行打开编辑弹窗，编辑即时写回草稿（保存仍受 [SettingsUiState.canSave] 约束）；
 * - 校验错误显示在对应行/弹窗，保存按钮只在“有改动且无错误”时可点；
 * - Key 只展示末 4 位，设置/清除都走独立对话框，输入框密码样式且不回显已存值。
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onFieldChange: (SettingsField, String) -> Unit = { _, _ -> },
    onToggleChange: (SettingsField, Boolean) -> Unit = { _, _ -> },
    onSave: () -> Unit = {},
    onRetry: () -> Unit = {},
    onSetKey: (SettingsKeyTarget, String) -> Unit = { _, _ -> },
    onClearKey: (SettingsKeyTarget) -> Unit = {},
    onOpenLicenses: () -> Unit = {},
    onOpenStorage: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onRestartOnboarding: () -> Unit = {},
    versionName: String = BuildConfig.VERSION_NAME,
) {
    // 用空串而不是可空枚举，避免 rememberSaveable 保存 null 的平台差异。
    var keyDialogTargetName by rememberSaveable { mutableStateOf("") }
    // Key 明文不能进 saved instance state（可能落盘），只用 remember 存活在当前组合里。
    var keyInput by remember { mutableStateOf("") }
    var clearTargetName by rememberSaveable { mutableStateOf("") }
    // 文本字段编辑：只记录字段名与打开时的原始值；取消时把草稿恢复回去。
    var editFieldName by rememberSaveable { mutableStateOf("") }
    var editOriginalValue by rememberSaveable { mutableStateOf("") }

    val keyDialogTarget = keyTargetByName(keyDialogTargetName)
    val clearTarget = keyTargetByName(clearTargetName)
    val editField = fieldByName(editFieldName)
    val loadError = state.loadError

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ForgeTopBar(
                title = stringResource(R.string.settings_title),
                subtitle = stringResource(R.string.ux_settings_subtitle),
                navigationIcon = ForgeIcons.Back,
                navigationDescription = stringResource(R.string.action_back),
                onNavigation = onBack,
            )
        },
    ) { padding ->
        val contentModifier = Modifier.padding(padding)
        when {
            state.loading -> ForgeLoading(
                label = stringResource(R.string.settings_loading),
                modifier = contentModifier,
            )

            loadError != null -> ForgeError(
                message = stringResource(R.string.settings_load_error, loadError),
                modifier = contentModifier,
                onRetry = onRetry,
                retryLabel = stringResource(R.string.action_retry),
            )

            else -> SettingsContent(
                state = state,
                versionName = versionName,
                contentPadding = padding,
                onFieldChange = onFieldChange,
                onToggleChange = onToggleChange,
                onSave = onSave,
                onOpenLicenses = onOpenLicenses,
                onOpenStorage = onOpenStorage,
                onOpenAbout = onOpenAbout,
                onRestartOnboarding = onRestartOnboarding,
                onOpenField = { field ->
                    editOriginalValue = state.draft.textOf(field)
                    editFieldName = field.name
                },
                onOpenKey = { target ->
                    clearTargetName = ""
                    keyInput = ""
                    keyDialogTargetName = target.name
                },
            )
        }
    }

    if (keyDialogTarget != null) {
        KeyInputDialog(
            target = keyDialogTarget,
            value = keyInput,
            configured = state.keys.forTarget(keyDialogTarget).configured,
            busy = state.keyBusy,
            onValueChange = { keyInput = it },
            onDismiss = {
                keyDialogTargetName = ""
                keyInput = ""
            },
            onConfirm = {
                onSetKey(keyDialogTarget, keyInput)
                keyDialogTargetName = ""
                keyInput = ""
            },
            onRequestClear = {
                keyDialogTargetName = ""
                keyInput = ""
                clearTargetName = keyDialogTarget.name
            },
        )
    }

    if (clearTarget != null) {
        ClearKeyDialog(
            target = clearTarget,
            busy = state.keyBusy,
            onDismiss = { clearTargetName = "" },
            onConfirm = {
                onClearKey(clearTarget)
                clearTargetName = ""
            },
        )
    }

    if (editField != null) {
        FieldEditDialog(
            title = stringResource(
                R.string.ux_settings_edit_title,
                fieldTitle(editField),
            ),
            value = state.draft.textOf(editField),
            hint = fieldHint(editField),
            errorText = fieldErrorText(state, editField),
            enabled = !state.saving,
            keyboardType = fieldKeyboardType(editField),
            onValueChange = { value -> onFieldChange(editField, value) },
            onDismiss = {
                onFieldChange(editField, editOriginalValue)
                editFieldName = ""
            },
            onConfirm = { editFieldName = "" },
        )
    }
}

@Composable
private fun SettingsContent(
    state: SettingsUiState,
    versionName: String,
    contentPadding: PaddingValues,
    onFieldChange: (SettingsField, String) -> Unit,
    onToggleChange: (SettingsField, Boolean) -> Unit,
    onSave: () -> Unit,
    onOpenLicenses: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenAbout: () -> Unit,
    onRestartOnboarding: () -> Unit,
    onOpenField: (SettingsField) -> Unit,
    onOpenKey: (SettingsKeyTarget) -> Unit,
) {
    val enabled = !state.saving

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(
            horizontal = MaterialTheme.spacing.lg,
            vertical = MaterialTheme.spacing.lg,
        ),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.lg),
    ) {
        item(key = "hero") {
            SettingsHero(state = state)
        }

        if (state.isDirty && state.hasErrors) {
            item(key = "validation-summary") {
                ValidationSummary(state = state)
            }
        }

        val keyError = state.keyError
        if (keyError != null) {
            item(key = "key-error") {
                InlineErrorCard(
                    message = stringResource(R.string.settings_key_error, keyError),
                )
            }
        }

        item(key = "group-api") {
            ApiGroup(
                state = state,
                enabled = enabled,
                onOpenField = onOpenField,
                onOpenKey = onOpenKey,
            )
        }
        item(key = "group-generation") {
            GenerationGroup(
                state = state,
                enabled = enabled,
                onOpenField = onOpenField,
            )
        }
        item(key = "group-network") {
            NetworkGroup(
                state = state,
                enabled = enabled,
                onToggleChange = onToggleChange,
            )
        }
        item(key = "group-device") {
            DeviceGroup(
                state = state,
                enabled = enabled,
                onToggleChange = onToggleChange,
                onOpenField = onOpenField,
            )
        }
        item(key = "group-appearance") {
            AppearanceGroup(
                state = state,
                enabled = enabled,
                onFieldChange = onFieldChange,
                onToggleChange = onToggleChange,
            )
        }
        item(key = "group-storage") {
            StorageGroup(
                state = state,
                enabled = enabled,
                onOpenField = onOpenField,
                onOpenStorage = onOpenStorage,
            )
        }
        item(key = "group-about") {
            AboutGroup(
                versionName = versionName,
                onOpenLicenses = onOpenLicenses,
                onOpenAbout = onOpenAbout,
                onRestartOnboarding = onRestartOnboarding,
            )
        }

        item(key = "save") {
            SaveFooter(state = state, onSave = onSave)
        }
    }
}

@Composable
private fun SettingsHero(state: SettingsUiState) {
    val ready = state.keys.llm.configured && state.keys.fish.configured
    ForgeCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
        ) {
            Surface(
                modifier = Modifier.size(46.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    ForgeIcon(
                        icon = ForgeIcons.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xxs),
            ) {
                Text(
                    text = stringResource(
                        if (ready) {
                            R.string.ux_settings_hero_ready_title
                        } else {
                            R.string.ux_settings_hero_missing_title
                        },
                    ),
                    style = MaterialTheme.typography.titleSmallEmphasized,
                )
                Text(
                    text = stringResource(
                        if (ready) {
                            R.string.ux_settings_hero_ready_desc
                        } else {
                            R.string.ux_settings_hero_missing_desc
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusPill(
                text = stringResource(
                    if (ready) {
                        R.string.ux_settings_hero_ready_status
                    } else {
                        R.string.ux_settings_hero_missing_status
                    },
                ),
                tone = if (ready) StatusTone.Success else StatusTone.Warning,
            )
        }
    }
}

@Composable
private fun InlineErrorCard(message: String) {
    ForgeCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
        ) {
            ForgeIcon(
                icon = ForgeIcons.AlertCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = message,
                modifier = Modifier
                    .weight(1f)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun ValidationSummary(state: SettingsUiState) {
    val count = state.errors.size + state.formatErrors.size
    ForgeCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
        ) {
            ForgeIcon(
                icon = ForgeIcons.AlertCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = stringResource(R.string.settings_error_summary, count),
                modifier = Modifier
                    .weight(1f)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun ApiGroup(
    state: SettingsUiState,
    enabled: Boolean,
    onOpenField: (SettingsField) -> Unit,
    onOpenKey: (SettingsKeyTarget) -> Unit,
) {
    val emptyValue = stringResource(R.string.ux_settings_value_empty)
    SettingsGroup(
        icon = ForgeIcons.Key,
        title = stringResource(R.string.settings_section_api),
        hint = stringResource(R.string.ux_settings_group_api_hint),
    ) {
        SettingsEditRow(
            state = state,
            field = SettingsField.LLM_BASE_URL,
            icon = ForgeIcons.Globe,
            title = stringResource(R.string.settings_field_llm_base_url),
            description = stringResource(R.string.ux_settings_desc_llm_base_url),
            value = state.draft.llmBaseUrl.ifBlank { emptyValue },
            enabled = enabled,
            onOpen = { onOpenField(SettingsField.LLM_BASE_URL) },
        )
        SettingsDivider()
        SettingsEditRow(
            state = state,
            field = SettingsField.LLM_MODEL,
            icon = ForgeIcons.Sparkles,
            title = stringResource(R.string.settings_field_llm_model),
            description = stringResource(R.string.ux_settings_desc_llm_model),
            value = state.draft.llmModel.ifBlank { emptyValue },
            enabled = enabled,
            onOpen = { onOpenField(SettingsField.LLM_MODEL) },
        )
        SettingsDivider()
        SettingsKeyRow(
            key = state.keys.llm,
            title = stringResource(R.string.settings_key_llm_title),
            enabled = enabled,
            onClick = { onOpenKey(SettingsKeyTarget.LLM) },
        )
        SettingsDivider()
        SettingsEditRow(
            state = state,
            field = SettingsField.FISH_BASE_URL,
            icon = ForgeIcons.Globe,
            title = stringResource(R.string.settings_field_fish_base_url),
            description = stringResource(R.string.ux_settings_desc_fish_base_url),
            value = state.draft.fishBaseUrl.ifBlank { emptyValue },
            enabled = enabled,
            onOpen = { onOpenField(SettingsField.FISH_BASE_URL) },
        )
        SettingsDivider()
        SettingsEditRow(
            state = state,
            field = SettingsField.FISH_TTS_MODEL,
            icon = ForgeIcons.AudioLines,
            title = stringResource(R.string.settings_field_fish_tts_model),
            description = stringResource(R.string.ux_settings_desc_fish_model),
            value = state.draft.fishTtsModel.ifBlank { emptyValue },
            enabled = enabled,
            onOpen = { onOpenField(SettingsField.FISH_TTS_MODEL) },
        )
        SettingsDivider()
        SettingsKeyRow(
            key = state.keys.fish,
            title = stringResource(R.string.settings_key_fish_title),
            enabled = enabled,
            onClick = { onOpenKey(SettingsKeyTarget.FISH) },
        )
    }
}

@Composable
private fun GenerationGroup(
    state: SettingsUiState,
    enabled: Boolean,
    onOpenField: (SettingsField) -> Unit,
) {
    val emptyValue = stringResource(R.string.ux_settings_value_empty)
    SettingsGroup(
        icon = ForgeIcons.Sliders,
        title = stringResource(R.string.settings_section_generation),
        hint = stringResource(R.string.ux_settings_group_generation_hint),
    ) {
        SettingsEditRow(
            state = state,
            field = SettingsField.LLM_CONCURRENCY,
            icon = ForgeIcons.Sliders,
            title = stringResource(R.string.settings_field_llm_concurrency),
            description = stringResource(R.string.ux_settings_desc_llm_concurrency),
            value = state.draft.llmConcurrency.ifBlank { emptyValue },
            enabled = enabled,
            onOpen = { onOpenField(SettingsField.LLM_CONCURRENCY) },
        )
        SettingsDivider()
        SettingsEditRow(
            state = state,
            field = SettingsField.TTS_CONCURRENCY,
            icon = ForgeIcons.AudioLines,
            title = stringResource(R.string.settings_field_tts_concurrency),
            description = stringResource(R.string.ux_settings_desc_tts_concurrency),
            value = state.draft.ttsConcurrency.ifBlank { emptyValue },
            enabled = enabled,
            onOpen = { onOpenField(SettingsField.TTS_CONCURRENCY) },
        )
        SettingsDivider()
        SettingsEditRow(
            state = state,
            field = SettingsField.SEGMENT_MAX_CHARS,
            icon = ForgeIcons.FileText,
            title = stringResource(R.string.settings_field_segment_max_chars),
            description = stringResource(R.string.ux_settings_desc_segment_max),
            value = stringResource(R.string.ux_settings_value_chars, state.draft.segmentMaxChars),
            enabled = enabled,
            onOpen = { onOpenField(SettingsField.SEGMENT_MAX_CHARS) },
        )
        SettingsDivider()
        SettingsEditRow(
            state = state,
            field = SettingsField.MIN_COVERAGE,
            icon = ForgeIcons.Gauge,
            title = stringResource(R.string.settings_field_min_coverage),
            description = stringResource(R.string.ux_settings_desc_min_coverage),
            value = state.draft.minCoverage.ifBlank { emptyValue },
            enabled = enabled,
            onOpen = { onOpenField(SettingsField.MIN_COVERAGE) },
        )
        SettingsDivider()
        SettingsEditRow(
            state = state,
            field = SettingsField.BATCH_SIZE,
            icon = ForgeIcons.List,
            title = stringResource(R.string.settings_field_batch_size),
            description = stringResource(R.string.ux_settings_desc_batch_size),
            value = stringResource(R.string.ux_settings_value_chapters, state.draft.batchSize),
            enabled = enabled,
            onOpen = { onOpenField(SettingsField.BATCH_SIZE) },
        )
        SettingsDivider()
        SettingsEditRow(
            state = state,
            field = SettingsField.NARRATOR_SILENCE_MS,
            icon = ForgeIcons.Timer,
            title = stringResource(R.string.settings_field_narrator_silence),
            description = stringResource(R.string.ux_settings_desc_narrator_silence),
            value = stringResource(R.string.ux_settings_value_ms, state.draft.narratorSilenceMs),
            enabled = enabled,
            onOpen = { onOpenField(SettingsField.NARRATOR_SILENCE_MS) },
        )
        SettingsDivider()
        SettingsEditRow(
            state = state,
            field = SettingsField.PARAGRAPH_SILENCE_MS,
            icon = ForgeIcons.Timer,
            title = stringResource(R.string.settings_field_paragraph_silence),
            description = stringResource(R.string.ux_settings_desc_paragraph_silence),
            value = stringResource(R.string.ux_settings_value_ms, state.draft.paragraphSilenceMs),
            enabled = enabled,
            onOpen = { onOpenField(SettingsField.PARAGRAPH_SILENCE_MS) },
        )
        SettingsDivider()
        SettingsEditRow(
            state = state,
            field = SettingsField.DEFAULT_VOICE_CATEGORY,
            icon = ForgeIcons.Mic,
            title = stringResource(R.string.settings_field_default_voice),
            description = stringResource(R.string.ux_settings_desc_default_voice),
            value = state.draft.defaultVoiceCategory.ifBlank { emptyValue },
            enabled = enabled,
            onOpen = { onOpenField(SettingsField.DEFAULT_VOICE_CATEGORY) },
        )
        SettingsDivider()
        SettingsEditRow(
            state = state,
            field = SettingsField.DEFAULT_SPEED,
            icon = ForgeIcons.Gauge,
            title = stringResource(R.string.settings_field_default_speed),
            description = stringResource(R.string.ux_settings_desc_default_speed),
            value = state.draft.defaultSpeed.ifBlank { emptyValue },
            enabled = enabled,
            onOpen = { onOpenField(SettingsField.DEFAULT_SPEED) },
        )
        SettingsDivider()
        SettingsEditRow(
            state = state,
            field = SettingsField.DEFAULT_VOLUME,
            icon = ForgeIcons.Volume,
            title = stringResource(R.string.settings_field_default_volume),
            description = stringResource(R.string.ux_settings_desc_default_volume),
            value = state.draft.defaultVolume.ifBlank { emptyValue },
            enabled = enabled,
            onOpen = { onOpenField(SettingsField.DEFAULT_VOLUME) },
        )
        SettingsDivider()
        SettingsEditRow(
            state = state,
            field = SettingsField.DEFAULT_PITCH,
            icon = ForgeIcons.Music,
            title = stringResource(R.string.settings_field_default_pitch),
            description = stringResource(R.string.ux_settings_desc_default_pitch),
            value = state.draft.defaultPitch.ifBlank { emptyValue },
            enabled = enabled,
            onOpen = { onOpenField(SettingsField.DEFAULT_PITCH) },
        )
    }
}

@Composable
private fun NetworkGroup(
    state: SettingsUiState,
    enabled: Boolean,
    onToggleChange: (SettingsField, Boolean) -> Unit,
) {
    SettingsGroup(
        icon = ForgeIcons.Wifi,
        title = stringResource(R.string.settings_section_network),
        hint = stringResource(R.string.ux_settings_group_network_hint),
    ) {
        SettingsSwitchRow(
            icon = ForgeIcons.Wifi,
            title = stringResource(R.string.settings_switch_wifi_only),
            subtitle = stringResource(R.string.ux_settings_desc_wifi),
            checked = state.draft.wifiOnly,
            enabled = enabled,
            onCheckedChange = { onToggleChange(SettingsField.WIFI_ONLY, it) },
        )
        SettingsDivider()
        SettingsSwitchRow(
            icon = ForgeIcons.Battery,
            title = stringResource(R.string.settings_switch_charging_only),
            subtitle = stringResource(R.string.ux_settings_desc_charging),
            checked = state.draft.chargingOnly,
            enabled = enabled,
            onCheckedChange = { onToggleChange(SettingsField.CHARGING_ONLY, it) },
        )
    }
}

@Composable
private fun DeviceGroup(
    state: SettingsUiState,
    enabled: Boolean,
    onToggleChange: (SettingsField, Boolean) -> Unit,
    onOpenField: (SettingsField) -> Unit,
) {
    SettingsGroup(
        icon = ForgeIcons.Smartphone,
        title = stringResource(R.string.settings_section_device),
        hint = stringResource(R.string.ux_settings_group_device_hint),
    ) {
        SettingsSwitchRow(
            icon = ForgeIcons.Bell,
            title = stringResource(R.string.settings_switch_notifications),
            subtitle = stringResource(R.string.ux_settings_desc_notifications),
            checked = state.draft.notificationsEnabled,
            enabled = enabled,
            onCheckedChange = { onToggleChange(SettingsField.NOTIFICATIONS_ENABLED, it) },
        )
        SettingsDivider()
        SettingsSwitchRow(
            icon = ForgeIcons.Gauge,
            title = stringResource(R.string.settings_switch_thermal),
            subtitle = stringResource(R.string.ux_settings_desc_thermal),
            checked = state.draft.thermalThrottle,
            enabled = enabled,
            onCheckedChange = { onToggleChange(SettingsField.THERMAL_THROTTLE, it) },
        )
        SettingsDivider()
        SettingsEditRow(
            state = state,
            field = SettingsField.BATTERY_PAUSE_THRESHOLD,
            icon = ForgeIcons.Battery,
            title = stringResource(R.string.settings_field_battery_threshold),
            description = stringResource(R.string.ux_settings_desc_battery_threshold),
            value = stringResource(
                R.string.ux_settings_value_percent,
                state.draft.batteryPauseThreshold,
            ),
            enabled = enabled,
            onOpen = { onOpenField(SettingsField.BATTERY_PAUSE_THRESHOLD) },
        )
    }
}

@Composable
private fun AppearanceGroup(
    state: SettingsUiState,
    enabled: Boolean,
    onFieldChange: (SettingsField, String) -> Unit,
    onToggleChange: (SettingsField, Boolean) -> Unit,
) {
    SettingsGroup(
        icon = ForgeIcons.Palette,
        title = stringResource(R.string.settings_section_appearance),
        hint = stringResource(R.string.ux_settings_group_appearance_hint),
    ) {
        ThemeRow(state = state, enabled = enabled, onFieldChange = onFieldChange)
        SettingsDivider()
        SettingsSwitchRow(
            icon = ForgeIcons.Palette,
            title = stringResource(R.string.settings_switch_dynamic_color),
            subtitle = stringResource(R.string.ux_settings_desc_dynamic_color),
            checked = state.draft.dynamicColor,
            enabled = enabled,
            onCheckedChange = { onToggleChange(SettingsField.DYNAMIC_COLOR, it) },
        )
    }
}

@Composable
private fun ThemeRow(
    state: SettingsUiState,
    enabled: Boolean,
    onFieldChange: (SettingsField, String) -> Unit,
) {
    val options = listOf(
        ForgeIcons.Smartphone to stringResource(R.string.settings_theme_system),
        ForgeIcons.Sun to stringResource(R.string.settings_theme_light),
        ForgeIcons.Moon to stringResource(R.string.settings_theme_dark),
    )
    val themeError = fieldErrorText(state, SettingsField.THEME_MODE)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = MaterialTheme.spacing.lg,
                vertical = MaterialTheme.spacing.md,
            ),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
        ) {
            SettingsLeadingIcon(ForgeIcons.Palette)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_theme_title),
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    modifier = Modifier.semantics { heading() },
                )
            }
        }
        ForgeSegmented(
            options = options,
            selectedIndex = ThemeMode.entries.indexOfFirst {
                it.wire == state.draft.themeMode
            },
            onSelect = { index ->
                if (enabled) {
                    onFieldChange(SettingsField.THEME_MODE, ThemeMode.entries[index].wire)
                }
            },
        )
        if (themeError != null) {
            Text(
                text = themeError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun StorageGroup(
    state: SettingsUiState,
    enabled: Boolean,
    onOpenField: (SettingsField) -> Unit,
    onOpenStorage: () -> Unit,
) {
    SettingsGroup(
        icon = ForgeIcons.Storage,
        title = stringResource(R.string.settings_section_storage),
        hint = stringResource(R.string.ux_settings_group_storage_hint),
    ) {
        SettingsRow(
            icon = ForgeIcons.Storage,
            title = stringResource(R.string.settings_action_open_storage),
            subtitle = stringResource(R.string.ux_settings_desc_storage_open),
            enabled = enabled,
            showChevron = true,
            onClick = onOpenStorage,
        )
        SettingsDivider()
        SettingsEditRow(
            state = state,
            field = SettingsField.KEEP_SEGMENT_CACHE_DAYS,
            icon = ForgeIcons.Clock,
            title = stringResource(R.string.settings_field_keep_days),
            description = stringResource(R.string.ux_settings_desc_retention),
            value = stringResource(
                R.string.ux_settings_value_days,
                state.draft.keepSegmentCacheDays,
            ),
            enabled = enabled,
            onOpen = { onOpenField(SettingsField.KEEP_SEGMENT_CACHE_DAYS) },
        )
    }
}

@Composable
private fun AboutGroup(
    versionName: String,
    onOpenLicenses: () -> Unit,
    onOpenAbout: () -> Unit,
    onRestartOnboarding: () -> Unit,
) {
    SettingsGroup(
        icon = ForgeIcons.Info,
        title = stringResource(R.string.settings_section_about),
        hint = stringResource(R.string.ux_settings_group_about_hint),
    ) {
        SettingsRow(
            icon = ForgeIcons.Info,
            title = stringResource(R.string.ux_settings_version_title),
            subtitle = stringResource(R.string.ux_settings_version_build),
            trailingText = versionName,
        )
        SettingsDivider()
        SettingsRow(
            icon = ForgeIcons.Shield,
            title = stringResource(R.string.settings_about_licenses),
            subtitle = stringResource(R.string.settings_about_licenses_desc),
            showChevron = true,
            onClick = onOpenLicenses,
        )
        SettingsDivider()
        SettingsRow(
            icon = ForgeIcons.Refresh,
            title = stringResource(R.string.settings_action_restart_onboarding),
            subtitle = stringResource(R.string.ux_settings_desc_restart_onboarding),
            showChevron = true,
            onClick = onRestartOnboarding,
        )
        SettingsDivider()
        SettingsRow(
            icon = ForgeIcons.Info,
            title = stringResource(R.string.settings_action_open_about),
            subtitle = stringResource(R.string.ux_settings_desc_about_open),
            showChevron = true,
            onClick = onOpenAbout,
        )
    }
}

@Composable
private fun SaveFooter(state: SettingsUiState, onSave: () -> Unit) {
    val saveError = state.saveError
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
        when {
            state.saving -> StatusLine(
                icon = ForgeIcons.Loader,
                text = stringResource(R.string.action_saving),
            )

            saveError != null -> SaveErrorCard(
                message = stringResource(R.string.action_save_error, saveError),
                onRetry = onSave,
            )

            state.saved -> StatusLine(
                icon = ForgeIcons.Check,
                text = stringResource(R.string.settings_saved),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        ForgePrimaryAction(
            icon = ForgeIcons.Check,
            label = stringResource(R.string.ux_settings_save),
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.canSave,
            loading = state.saving,
        )
        Text(
            text = stringResource(
                if (state.saved) {
                    R.string.ux_settings_saved_hint
                } else {
                    R.string.ux_settings_save_hint
                },
            ),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StatusLine(
    @DrawableRes icon: Int,
    text: String,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
    ) {
        ForgeIcon(icon = icon, contentDescription = null, tint = tint)
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = tint,
        )
    }
}

@Composable
private fun SaveErrorCard(message: String, onRetry: () -> Unit) {
    ForgeCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
        ) {
            ForgeIcon(
                icon = ForgeIcons.AlertCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = message,
                modifier = Modifier
                    .weight(1f)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        ForgeSecondaryAction(
            icon = ForgeIcons.Refresh,
            label = stringResource(R.string.action_retry),
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
            danger = true,
        )
    }
}

@Composable
private fun SettingsGroup(
    @DrawableRes icon: Int,
    title: String,
    hint: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
        ) {
            ForgeIcon(
                icon = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = title,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
                style = MaterialTheme.typography.labelLargeEmphasized,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ForgeCard(contentPadding = PaddingValues(0.dp)) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingsRow(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    trailingText: String? = null,
    enabled: Boolean = true,
    subtitleIsError: Boolean = false,
    showChevron: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val clickModifier = if (onClick != null) {
        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(clickModifier)
            .heightIn(min = 64.dp)
            .padding(
                horizontal = MaterialTheme.spacing.lg,
                vertical = MaterialTheme.spacing.md,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
    ) {
        SettingsLeadingIcon(icon)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xxs),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmallEmphasized,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (subtitleIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailingText != null) {
            Text(
                text = trailingText,
                modifier = Modifier.widthIn(max = 150.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
        }
        if (showChevron) {
            ForgeIcon(
                icon = ForgeIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsEditRow(
    state: SettingsUiState,
    field: SettingsField,
    @DrawableRes icon: Int,
    title: String,
    description: String,
    value: String,
    enabled: Boolean,
    onOpen: () -> Unit,
) {
    val error = fieldErrorText(state, field)
    SettingsRow(
        icon = icon,
        title = title,
        subtitle = error ?: description,
        trailingText = value,
        enabled = enabled,
        subtitleIsError = error != null,
        showChevron = true,
        onClick = onOpen,
    )
}

@Composable
private fun SettingsKeyRow(
    key: SecureKeyUi,
    title: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    SettingsRow(
        icon = ForgeIcons.Key,
        title = title,
        subtitle = keySubtitle(key),
        enabled = enabled,
        showChevron = true,
        onClick = onClick,
    )
}

@Composable
private fun SettingsSwitchRow(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .heightIn(min = 64.dp)
            .padding(
                horizontal = MaterialTheme.spacing.lg,
                vertical = MaterialTheme.spacing.md,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
    ) {
        SettingsLeadingIcon(icon)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xxs),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmallEmphasized,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(MaterialTheme.spacing.xs))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
        )
    }
}

@Composable
private fun SettingsLeadingIcon(@DrawableRes icon: Int) {
    Surface(
        modifier = Modifier.size(40.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            ForgeIcon(
                icon = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.lg),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun FieldEditDialog(
    title: String,
    value: String,
    hint: String?,
    errorText: String?,
    enabled: Boolean,
    keyboardType: KeyboardType,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val supportingText: (@Composable () -> Unit)? = when {
        errorText != null -> {
            {
                Text(
                    text = errorText,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        hint != null -> {
            {
                Text(
                    text = hint,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            ForgeIcon(
                icon = ForgeIcons.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLargeEmphasized,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            if (errorText != null) {
                                liveRegion = LiveRegionMode.Polite
                            }
                        },
                    enabled = enabled,
                    singleLine = true,
                    isError = errorText != null,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    supportingText = supportingText,
                )
            }
        },
        confirmButton = {
            ForgePrimaryAction(
                icon = ForgeIcons.Check,
                label = stringResource(R.string.action_save),
                onClick = onConfirm,
                enabled = enabled,
            )
        },
        dismissButton = {
            ForgeSecondaryAction(
                icon = ForgeIcons.Close,
                label = stringResource(R.string.action_cancel),
                onClick = onDismiss,
            )
        },
    )
}

@Composable
private fun KeyInputDialog(
    target: SettingsKeyTarget,
    value: String,
    configured: Boolean,
    busy: Boolean,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onRequestClear: () -> Unit,
) {
    val title = keyTitle(target)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            ForgeIcon(
                icon = ForgeIcons.Key,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                text = stringResource(R.string.settings_key_dialog_title, title),
                style = MaterialTheme.typography.titleLargeEmphasized,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    label = { Text(stringResource(R.string.settings_key_dialog_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Text(
                    text = stringResource(R.string.settings_key_dialog_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (configured) {
                    ForgeSecondaryAction(
                        icon = ForgeIcons.Trash,
                        label = stringResource(R.string.settings_key_clear),
                        onClick = onRequestClear,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                        danger = true,
                    )
                }
            }
        },
        confirmButton = {
            ForgePrimaryAction(
                icon = ForgeIcons.Check,
                label = stringResource(
                    if (configured) {
                        R.string.settings_key_change
                    } else {
                        R.string.settings_key_set
                    },
                ),
                onClick = onConfirm,
                enabled = value.isNotBlank() && !busy,
                loading = busy,
            )
        },
        dismissButton = {
            ForgeSecondaryAction(
                icon = ForgeIcons.Close,
                label = stringResource(R.string.action_cancel),
                onClick = onDismiss,
            )
        },
    )
}

@Composable
private fun ClearKeyDialog(
    target: SettingsKeyTarget,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val title = keyTitle(target)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            ForgeIcon(
                icon = ForgeIcons.Trash,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = {
            Text(
                text = stringResource(R.string.settings_key_clear_title),
                style = MaterialTheme.typography.titleLargeEmphasized,
            )
        },
        text = {
            Text(
                text = stringResource(R.string.settings_key_clear_message, title),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            ForgeSecondaryAction(
                icon = ForgeIcons.Trash,
                label = stringResource(R.string.settings_key_clear),
                onClick = onConfirm,
                enabled = !busy,
                danger = true,
            )
        },
        dismissButton = {
            ForgeSecondaryAction(
                icon = ForgeIcons.Close,
                label = stringResource(R.string.action_cancel),
                onClick = onDismiss,
            )
        },
    )
}

@Composable
private fun keySubtitle(key: SecureKeyUi): String = when {
    !key.configured -> stringResource(R.string.settings_key_unset)
    key.last4.isNotBlank() -> stringResource(
        R.string.ux_settings_key_ready,
        stringResource(R.string.settings_key_mask, key.last4),
    )

    else -> stringResource(R.string.settings_key_masked_short)
}

@Composable
private fun keyTitle(target: SettingsKeyTarget): String = when (target) {
    SettingsKeyTarget.LLM -> stringResource(R.string.settings_key_llm_title)
    SettingsKeyTarget.FISH -> stringResource(R.string.settings_key_fish_title)
}

@Composable
private fun fieldTitle(field: SettingsField): String = stringResource(
    when (field) {
        SettingsField.LLM_BASE_URL -> R.string.settings_field_llm_base_url
        SettingsField.LLM_MODEL -> R.string.settings_field_llm_model
        SettingsField.LLM_CONCURRENCY -> R.string.settings_field_llm_concurrency
        SettingsField.FISH_BASE_URL -> R.string.settings_field_fish_base_url
        SettingsField.FISH_TTS_MODEL -> R.string.settings_field_fish_tts_model
        SettingsField.TTS_CONCURRENCY -> R.string.settings_field_tts_concurrency
        SettingsField.SEGMENT_MAX_CHARS -> R.string.settings_field_segment_max_chars
        SettingsField.NARRATOR_SILENCE_MS -> R.string.settings_field_narrator_silence
        SettingsField.PARAGRAPH_SILENCE_MS -> R.string.settings_field_paragraph_silence
        SettingsField.DEFAULT_VOICE_CATEGORY -> R.string.settings_field_default_voice
        SettingsField.DEFAULT_SPEED -> R.string.settings_field_default_speed
        SettingsField.DEFAULT_VOLUME -> R.string.settings_field_default_volume
        SettingsField.DEFAULT_PITCH -> R.string.settings_field_default_pitch
        SettingsField.MIN_COVERAGE -> R.string.settings_field_min_coverage
        SettingsField.BATCH_SIZE -> R.string.settings_field_batch_size
        SettingsField.BATTERY_PAUSE_THRESHOLD -> R.string.settings_field_battery_threshold
        SettingsField.KEEP_SEGMENT_CACHE_DAYS -> R.string.settings_field_keep_days
        SettingsField.THEME_MODE -> R.string.settings_theme_title
        SettingsField.WIFI_ONLY,
        SettingsField.CHARGING_ONLY,
        SettingsField.THERMAL_THROTTLE,
        SettingsField.NOTIFICATIONS_ENABLED,
        SettingsField.DYNAMIC_COLOR -> R.string.settings_theme_title
    },
)

@Composable
private fun fieldHint(field: SettingsField): String? = when (field) {
    SettingsField.LLM_CONCURRENCY -> stringResource(R.string.settings_field_llm_concurrency_hint)
    SettingsField.TTS_CONCURRENCY -> stringResource(R.string.settings_field_tts_concurrency_hint)
    SettingsField.SEGMENT_MAX_CHARS -> stringResource(R.string.settings_field_segment_max_chars_hint)
    SettingsField.MIN_COVERAGE -> stringResource(R.string.settings_field_min_coverage_hint)
    SettingsField.BATCH_SIZE -> stringResource(R.string.settings_field_batch_size_hint)
    SettingsField.NARRATOR_SILENCE_MS -> stringResource(R.string.settings_field_narrator_silence_hint)
    SettingsField.PARAGRAPH_SILENCE_MS -> stringResource(R.string.settings_field_paragraph_silence_hint)
    SettingsField.DEFAULT_VOICE_CATEGORY -> stringResource(R.string.settings_field_default_voice_hint)
    SettingsField.DEFAULT_SPEED -> stringResource(R.string.settings_field_default_speed_hint)
    SettingsField.DEFAULT_VOLUME -> stringResource(R.string.settings_field_default_volume_hint)
    SettingsField.DEFAULT_PITCH -> stringResource(R.string.settings_field_default_pitch_hint)
    SettingsField.BATTERY_PAUSE_THRESHOLD -> stringResource(
        R.string.settings_field_battery_threshold_hint,
    )

    SettingsField.KEEP_SEGMENT_CACHE_DAYS -> stringResource(R.string.settings_field_keep_days_hint)
    else -> null
}

private fun fieldKeyboardType(field: SettingsField): KeyboardType = when (field) {
    SettingsField.LLM_BASE_URL,
    SettingsField.FISH_BASE_URL -> KeyboardType.Uri

    SettingsField.LLM_CONCURRENCY,
    SettingsField.TTS_CONCURRENCY,
    SettingsField.SEGMENT_MAX_CHARS,
    SettingsField.BATCH_SIZE,
    SettingsField.NARRATOR_SILENCE_MS,
    SettingsField.PARAGRAPH_SILENCE_MS,
    SettingsField.BATTERY_PAUSE_THRESHOLD,
    SettingsField.KEEP_SEGMENT_CACHE_DAYS -> KeyboardType.Number

    SettingsField.MIN_COVERAGE,
    SettingsField.DEFAULT_SPEED,
    SettingsField.DEFAULT_VOLUME,
    SettingsField.DEFAULT_PITCH -> KeyboardType.Decimal

    else -> KeyboardType.Text
}

private fun keyTargetByName(name: String): SettingsKeyTarget? =
    SettingsKeyTarget.entries.firstOrNull { it.name == name }

private fun fieldByName(name: String): SettingsField? =
    SettingsField.entries.firstOrNull { it.name == name }

@Composable
private fun fieldErrorText(state: SettingsUiState, field: SettingsField): String? {
    val formatError = state.formatError(field)
    if (formatError != null) return formatErrorLabel(formatError)
    return state.error(field)
}

@Composable
private fun formatErrorLabel(error: FieldFormatError): String = when (error) {
    FieldFormatError.Missing -> stringResource(R.string.settings_error_required)
    FieldFormatError.NotAnInteger -> stringResource(R.string.settings_error_integer)
    FieldFormatError.NotANumber -> stringResource(R.string.settings_error_number)
    FieldFormatError.InvalidOption -> stringResource(R.string.settings_error_option)
}

/** 草稿的字符串视图；只读 UI 使用，写路径统一走 [SettingsDraft.withField]。 */
private fun SettingsDraft.textOf(field: SettingsField): String = when (field) {
    SettingsField.LLM_BASE_URL -> llmBaseUrl
    SettingsField.LLM_MODEL -> llmModel
    SettingsField.LLM_CONCURRENCY -> llmConcurrency
    SettingsField.FISH_BASE_URL -> fishBaseUrl
    SettingsField.FISH_TTS_MODEL -> fishTtsModel
    SettingsField.TTS_CONCURRENCY -> ttsConcurrency
    SettingsField.SEGMENT_MAX_CHARS -> segmentMaxChars
    SettingsField.NARRATOR_SILENCE_MS -> narratorSilenceMs
    SettingsField.PARAGRAPH_SILENCE_MS -> paragraphSilenceMs
    SettingsField.DEFAULT_VOICE_CATEGORY -> defaultVoiceCategory
    SettingsField.DEFAULT_SPEED -> defaultSpeed
    SettingsField.DEFAULT_VOLUME -> defaultVolume
    SettingsField.DEFAULT_PITCH -> defaultPitch
    SettingsField.MIN_COVERAGE -> minCoverage
    SettingsField.BATCH_SIZE -> batchSize
    SettingsField.BATTERY_PAUSE_THRESHOLD -> batteryPauseThreshold
    SettingsField.KEEP_SEGMENT_CACHE_DAYS -> keepSegmentCacheDays
    SettingsField.THEME_MODE -> themeMode
    SettingsField.WIFI_ONLY,
    SettingsField.CHARGING_ONLY,
    SettingsField.THERMAL_THROTTLE,
    SettingsField.NOTIFICATIONS_ENABLED,
    SettingsField.DYNAMIC_COLOR -> ""
}

// ---------------------------------------------------------------------------
// Preview：浅色默认态 / 深色带字段错误。文案来自 strings.xml 与本页新文案文件。
// ---------------------------------------------------------------------------

private fun previewState(): SettingsUiState = SettingsUiState.from(
    settings = AppSettings.DEFAULT,
    keys = SecureKeysUi(
        llm = SecureKeyUi.from("sk-preview-12345678"),
        fish = SecureKeyUi.UNSET,
    ),
)

@Preview(
    name = "SettingsScreen Light",
    showBackground = true,
    backgroundColor = 0xFFF7F8FC,
)
@Composable
private fun SettingsScreenLightPreview() {
    AudioForgeTheme(darkTheme = false, dynamicColor = false) {
        SettingsScreen(
            state = previewState(),
            versionName = "0.1.0",
        )
    }
}

@Preview(
    name = "SettingsScreen Dark Errors",
    showBackground = true,
    backgroundColor = 0xFF0F1220,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    fontScale = 1.3f,
)
@Composable
private fun SettingsScreenDarkPreview() {
    val base = previewState()
    val invalid = base.withDraft(
        base.draft.copy(
            segmentMaxChars = "500",
            minCoverage = "abc",
            ttsConcurrency = "",
            llmBaseUrl = "ftp://invalid.example",
        ),
    )
    AudioForgeTheme(darkTheme = true, dynamicColor = false) {
        SettingsScreen(
            state = invalid,
            versionName = "0.1.0",
        )
    }
}
