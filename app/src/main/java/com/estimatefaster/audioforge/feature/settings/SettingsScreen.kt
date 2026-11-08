package com.estimatefaster.audioforge.feature.settings

import android.content.res.Configuration
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.BuildConfig
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.core.settings.AppSettings
import com.estimatefaster.audioforge.core.settings.ThemeMode
import com.estimatefaster.audioforge.ui.components.ErrorState
import com.estimatefaster.audioforge.ui.components.SectionHeader
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme

/**
 * 全局设置页（P4-09 / 执行计划 §7.4、§12.4 S11）。
 *
 * 有状态入口：收集 [SettingsViewModel.state] 后委托给无状态版本。
 * 保存回调只通过 ViewModel 触达仓库，UI 不直接读写 DataStore。
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onOpenLicenses: () -> Unit = {},
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
        versionName = versionName,
    )
}

/**
 * 无状态设置页。
 *
 * - 分组：API / 生成 / 网络 / 设备 / 外观 / 存储 / 关于；
 * - 输入即时校验（[SettingsUiState.errors] / [SettingsUiState.formatErrors]）；
 * - 保存按钮只在“有改动且无错误”时可点，点击后由 ViewModel 调用仓库；
 * - Key 只展示末 4 位，设置/清除都走独立对话框，输入框使用密码样式、不回显已存值。
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    versionName: String = BuildConfig.VERSION_NAME,
) {
    // 用空串而不是可空枚举，避免 rememberSaveable 保存 null 的平台差异。
    var keyDialogTargetName by rememberSaveable { mutableStateOf("") }
    // Key 明文不能进 saved instance state（可能落盘），只用 remember 存活在当前组合里。
    var keyInput by remember { mutableStateOf("") }
    var clearTargetName by rememberSaveable { mutableStateOf("") }

    val keyDialogTarget = keyTargetByName(keyDialogTargetName)
    val clearTarget = keyTargetByName(clearTargetName)

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    TextButton(
                        onClick = onBack,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.action_back))
                    }
                },
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.settings_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (state.saved) {
                            Text(
                                text = stringResource(R.string.settings_saved),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                actions = {
                    TextButton(
                        onClick = onSave,
                        enabled = state.canSave,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.action_save))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingContent(modifier = Modifier.padding(padding))

            state.loadError != null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                ErrorState(
                    message = stringResource(
                        R.string.settings_load_error,
                        state.loadError,
                    ),
                    onRetry = onRetry,
                )
            }

            else -> SettingsContent(
                state = state,
                versionName = versionName,
                contentPadding = padding,
                onFieldChange = onFieldChange,
                onToggleChange = onToggleChange,
                onRequestSetKey = { target ->
                    clearTargetName = ""
                    keyInput = ""
                    keyDialogTargetName = target.name
                },
                onRequestClearKey = { target ->
                    keyDialogTargetName = ""
                    clearTargetName = target.name
                },
                onOpenLicenses = onOpenLicenses,
            )
        }
    }

    if (keyDialogTarget != null) {
        KeyInputDialog(
            target = keyDialogTarget,
            value = keyInput,
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
        )
    }

    if (clearTarget != null) {
        ClearKeyDialog(
            target = clearTarget,
            onDismiss = { clearTargetName = "" },
            onConfirm = {
                onClearKey(clearTarget)
                clearTargetName = ""
            },
        )
    }
}

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.heightIn(min = 12.dp))
        Text(
            text = stringResource(R.string.settings_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    onRequestSetKey: (SettingsKeyTarget) -> Unit,
    onRequestClearKey: (SettingsKeyTarget) -> Unit,
    onOpenLicenses: () -> Unit,
) {
    val enabled = !state.saving

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (state.saving || state.saved || state.saveError != null) {
            item(key = "save-status") {
                SaveStatus(state = state)
            }
        }
        if (state.isDirty && state.hasErrors) {
            item(key = "validation-summary") {
                Text(
                    text = stringResource(
                        R.string.settings_error_summary,
                        state.errors.size + state.formatErrors.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        item(key = "api") {
            ApiSection(
                state = state,
                enabled = enabled,
                onFieldChange = onFieldChange,
                onRequestSetKey = onRequestSetKey,
                onRequestClearKey = onRequestClearKey,
            )
        }
        item(key = "generation") {
            GenerationSection(
                state = state,
                enabled = enabled,
                onFieldChange = onFieldChange,
            )
        }
        item(key = "network") {
            NetworkSection(
                state = state,
                enabled = enabled,
                onToggleChange = onToggleChange,
            )
        }
        item(key = "device") {
            DeviceSection(
                state = state,
                enabled = enabled,
                onFieldChange = onFieldChange,
                onToggleChange = onToggleChange,
            )
        }
        item(key = "appearance") {
            AppearanceSection(
                state = state,
                enabled = enabled,
                onFieldChange = onFieldChange,
                onToggleChange = onToggleChange,
            )
        }
        item(key = "storage") {
            StorageSection(
                state = state,
                enabled = enabled,
                onFieldChange = onFieldChange,
            )
        }
        item(key = "about") {
            AboutSection(
                versionName = versionName,
                onOpenLicenses = onOpenLicenses,
            )
        }
    }
}

@Composable
private fun SaveStatus(state: SettingsUiState) {
    when {
        state.saving -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LinearProgressIndicator(modifier = Modifier.weight(1f))
            Text(
                text = stringResource(R.string.settings_saving),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.saveError != null -> Text(
            text = stringResource(R.string.settings_save_error, state.saveError),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        else -> Text(
            text = stringResource(R.string.settings_saved),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionHeader(title = title, subtitle = subtitle)
            content()
        }
    }
}

@Composable
private fun ApiSection(
    state: SettingsUiState,
    enabled: Boolean,
    onFieldChange: (SettingsField, String) -> Unit,
    onRequestSetKey: (SettingsKeyTarget) -> Unit,
    onRequestClearKey: (SettingsKeyTarget) -> Unit,
) {
    SettingsSection(
        title = stringResource(R.string.settings_section_api),
        subtitle = stringResource(R.string.settings_section_api_desc),
    ) {
        SectionLabel(text = stringResource(R.string.settings_api_llm))

        SettingsTextField(
            field = SettingsField.LLM_BASE_URL,
            state = state,
            label = stringResource(R.string.settings_field_llm_base_url),
            keyboardType = KeyboardType.Uri,
            enabled = enabled,
            onFieldChange = onFieldChange,
        )
        SettingsTextField(
            field = SettingsField.LLM_MODEL,
            state = state,
            label = stringResource(R.string.settings_field_llm_model),
            enabled = enabled,
            onFieldChange = onFieldChange,
        )
        ApiKeyRow(
            title = stringResource(R.string.settings_key_llm_title),
            key = state.keys.llm,
            busy = state.keyBusy,
            enabled = enabled,
            onSet = { onRequestSetKey(SettingsKeyTarget.LLM) },
            onClear = { onRequestClearKey(SettingsKeyTarget.LLM) },
        )

        HorizontalDivider()

        SectionLabel(text = stringResource(R.string.settings_api_fish))

        SettingsTextField(
            field = SettingsField.FISH_BASE_URL,
            state = state,
            label = stringResource(R.string.settings_field_fish_base_url),
            keyboardType = KeyboardType.Uri,
            enabled = enabled,
            onFieldChange = onFieldChange,
        )
        SettingsTextField(
            field = SettingsField.FISH_TTS_MODEL,
            state = state,
            label = stringResource(R.string.settings_field_fish_tts_model),
            enabled = enabled,
            onFieldChange = onFieldChange,
        )
        ApiKeyRow(
            title = stringResource(R.string.settings_key_fish_title),
            key = state.keys.fish,
            busy = state.keyBusy,
            enabled = enabled,
            onSet = { onRequestSetKey(SettingsKeyTarget.FISH) },
            onClear = { onRequestClearKey(SettingsKeyTarget.FISH) },
        )

        if (state.keyError != null) {
            Text(
                text = stringResource(R.string.settings_key_error, state.keyError),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun GenerationSection(
    state: SettingsUiState,
    enabled: Boolean,
    onFieldChange: (SettingsField, String) -> Unit,
) {
    SettingsSection(
        title = stringResource(R.string.settings_section_generation),
        subtitle = stringResource(R.string.settings_section_generation_desc),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsTextField(
                field = SettingsField.LLM_CONCURRENCY,
                state = state,
                label = stringResource(R.string.settings_field_llm_concurrency),
                hint = stringResource(R.string.settings_field_llm_concurrency_hint),
                keyboardType = KeyboardType.Number,
                enabled = enabled,
                onFieldChange = onFieldChange,
                modifier = Modifier.weight(1f),
            )
            SettingsTextField(
                field = SettingsField.TTS_CONCURRENCY,
                state = state,
                label = stringResource(R.string.settings_field_tts_concurrency),
                hint = stringResource(R.string.settings_field_tts_concurrency_hint),
                keyboardType = KeyboardType.Number,
                enabled = enabled,
                onFieldChange = onFieldChange,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsTextField(
                field = SettingsField.SEGMENT_MAX_CHARS,
                state = state,
                label = stringResource(R.string.settings_field_segment_max_chars),
                hint = stringResource(R.string.settings_field_segment_max_chars_hint),
                keyboardType = KeyboardType.Number,
                enabled = enabled,
                onFieldChange = onFieldChange,
                modifier = Modifier.weight(1f),
            )
            SettingsTextField(
                field = SettingsField.BATCH_SIZE,
                state = state,
                label = stringResource(R.string.settings_field_batch_size),
                hint = stringResource(R.string.settings_field_batch_size_hint),
                keyboardType = KeyboardType.Number,
                enabled = enabled,
                onFieldChange = onFieldChange,
                modifier = Modifier.weight(1f),
            )
        }
        SettingsTextField(
            field = SettingsField.MIN_COVERAGE,
            state = state,
            label = stringResource(R.string.settings_field_min_coverage),
            hint = stringResource(R.string.settings_field_min_coverage_hint),
            keyboardType = KeyboardType.Decimal,
            enabled = enabled,
            onFieldChange = onFieldChange,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsTextField(
                field = SettingsField.NARRATOR_SILENCE_MS,
                state = state,
                label = stringResource(R.string.settings_field_narrator_silence),
                hint = stringResource(R.string.settings_field_narrator_silence_hint),
                keyboardType = KeyboardType.Number,
                enabled = enabled,
                onFieldChange = onFieldChange,
                modifier = Modifier.weight(1f),
            )
            SettingsTextField(
                field = SettingsField.PARAGRAPH_SILENCE_MS,
                state = state,
                label = stringResource(R.string.settings_field_paragraph_silence),
                hint = stringResource(R.string.settings_field_paragraph_silence_hint),
                keyboardType = KeyboardType.Number,
                enabled = enabled,
                onFieldChange = onFieldChange,
                modifier = Modifier.weight(1f),
            )
        }
        SettingsTextField(
            field = SettingsField.DEFAULT_VOICE_CATEGORY,
            state = state,
            label = stringResource(R.string.settings_field_default_voice),
            hint = stringResource(R.string.settings_field_default_voice_hint),
            enabled = enabled,
            onFieldChange = onFieldChange,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsTextField(
                field = SettingsField.DEFAULT_SPEED,
                state = state,
                label = stringResource(R.string.settings_field_default_speed),
                hint = stringResource(R.string.settings_field_default_speed_hint),
                keyboardType = KeyboardType.Decimal,
                enabled = enabled,
                onFieldChange = onFieldChange,
                modifier = Modifier.weight(1f),
            )
            SettingsTextField(
                field = SettingsField.DEFAULT_VOLUME,
                state = state,
                label = stringResource(R.string.settings_field_default_volume),
                hint = stringResource(R.string.settings_field_default_volume_hint),
                keyboardType = KeyboardType.Decimal,
                enabled = enabled,
                onFieldChange = onFieldChange,
                modifier = Modifier.weight(1f),
            )
            SettingsTextField(
                field = SettingsField.DEFAULT_PITCH,
                state = state,
                label = stringResource(R.string.settings_field_default_pitch),
                hint = stringResource(R.string.settings_field_default_pitch_hint),
                keyboardType = KeyboardType.Text,
                enabled = enabled,
                onFieldChange = onFieldChange,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun NetworkSection(
    state: SettingsUiState,
    enabled: Boolean,
    onToggleChange: (SettingsField, Boolean) -> Unit,
) {
    SettingsSection(
        title = stringResource(R.string.settings_section_network),
        subtitle = stringResource(R.string.settings_section_network_desc),
    ) {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_switch_wifi_only),
            description = stringResource(R.string.settings_switch_wifi_only_desc),
            checked = state.draft.wifiOnly,
            enabled = enabled,
            onCheckedChange = { onToggleChange(SettingsField.WIFI_ONLY, it) },
        )
        SettingsSwitchRow(
            title = stringResource(R.string.settings_switch_charging_only),
            description = stringResource(R.string.settings_switch_charging_only_desc),
            checked = state.draft.chargingOnly,
            enabled = enabled,
            onCheckedChange = { onToggleChange(SettingsField.CHARGING_ONLY, it) },
        )
    }
}

@Composable
private fun DeviceSection(
    state: SettingsUiState,
    enabled: Boolean,
    onFieldChange: (SettingsField, String) -> Unit,
    onToggleChange: (SettingsField, Boolean) -> Unit,
) {
    SettingsSection(
        title = stringResource(R.string.settings_section_device),
        subtitle = stringResource(R.string.settings_section_device_desc),
    ) {
        SettingsTextField(
            field = SettingsField.BATTERY_PAUSE_THRESHOLD,
            state = state,
            label = stringResource(R.string.settings_field_battery_threshold),
            hint = stringResource(R.string.settings_field_battery_threshold_hint),
            keyboardType = KeyboardType.Number,
            enabled = enabled,
            onFieldChange = onFieldChange,
        )
        SettingsSwitchRow(
            title = stringResource(R.string.settings_switch_thermal),
            description = stringResource(R.string.settings_switch_thermal_desc),
            checked = state.draft.thermalThrottle,
            enabled = enabled,
            onCheckedChange = { onToggleChange(SettingsField.THERMAL_THROTTLE, it) },
        )
        SettingsSwitchRow(
            title = stringResource(R.string.settings_switch_notifications),
            description = stringResource(R.string.settings_switch_notifications_desc),
            checked = state.draft.notificationsEnabled,
            enabled = enabled,
            onCheckedChange = { onToggleChange(SettingsField.NOTIFICATIONS_ENABLED, it) },
        )
    }
}

@Composable
private fun AppearanceSection(
    state: SettingsUiState,
    enabled: Boolean,
    onFieldChange: (SettingsField, String) -> Unit,
    onToggleChange: (SettingsField, Boolean) -> Unit,
) {
    SettingsSection(
        title = stringResource(R.string.settings_section_appearance),
        subtitle = stringResource(R.string.settings_section_appearance_desc),
    ) {
        Text(
            text = stringResource(R.string.settings_theme_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = state.draft.themeMode == mode.wire,
                    onClick = { onFieldChange(SettingsField.THEME_MODE, mode.wire) },
                    enabled = enabled,
                    label = { Text(themeLabel(mode)) },
                )
            }
        }
        SettingsSwitchRow(
            title = stringResource(R.string.settings_switch_dynamic_color),
            description = stringResource(R.string.settings_switch_dynamic_color_desc),
            checked = state.draft.dynamicColor,
            enabled = enabled,
            onCheckedChange = { onToggleChange(SettingsField.DYNAMIC_COLOR, it) },
        )
    }
}

@Composable
private fun StorageSection(
    state: SettingsUiState,
    enabled: Boolean,
    onFieldChange: (SettingsField, String) -> Unit,
) {
    SettingsSection(
        title = stringResource(R.string.settings_section_storage),
        subtitle = stringResource(R.string.settings_section_storage_desc),
    ) {
        SettingsTextField(
            field = SettingsField.KEEP_SEGMENT_CACHE_DAYS,
            state = state,
            label = stringResource(R.string.settings_field_keep_days),
            hint = stringResource(R.string.settings_field_keep_days_hint),
            keyboardType = KeyboardType.Number,
            enabled = enabled,
            onFieldChange = onFieldChange,
        )
    }
}

@Composable
private fun AboutSection(
    versionName: String,
    onOpenLicenses: () -> Unit,
) {
    SettingsSection(
        title = stringResource(R.string.settings_section_about),
        subtitle = stringResource(R.string.settings_section_about_desc),
    ) {
        Text(
            text = stringResource(R.string.settings_about_version, versionName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = onOpenLicenses,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.settings_about_licenses))
        }
        Text(
            text = stringResource(R.string.settings_about_licenses_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SettingsTextField(
    field: SettingsField,
    state: SettingsUiState,
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
    onFieldChange: (SettingsField, String) -> Unit,
) {
    val errorText = fieldErrorText(state, field)
    val supportingText: (@Composable () -> Unit)? = when {
        errorText != null -> {
            { Text(text = errorText, color = MaterialTheme.colorScheme.error) }
        }

        hint != null -> {
            { Text(text = hint, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        else -> null
    }
    OutlinedTextField(
        value = state.draft.textOf(field),
        onValueChange = { value -> onFieldChange(field, value) },
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(label) },
        singleLine = true,
        isError = errorText != null,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        supportingText = supportingText,
    )
}

@Composable
private fun ApiKeyRow(
    title: String,
    key: SecureKeyUi,
    busy: Boolean,
    enabled: Boolean,
    onSet: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = when {
                    !key.configured -> stringResource(R.string.settings_key_unset)
                    key.last4.isNotBlank() -> stringResource(
                        R.string.settings_key_mask,
                        key.last4,
                    )
                    else -> stringResource(R.string.settings_key_masked_short)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (key.configured) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
        Spacer(Modifier.width(8.dp))
        TextButton(
            onClick = onSet,
            enabled = enabled && !busy,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(
                text = if (key.configured) {
                    stringResource(R.string.settings_key_change)
                } else {
                    stringResource(R.string.settings_key_set)
                },
            )
        }
        if (key.configured) {
            TextButton(
                onClick = onClear,
                enabled = enabled && !busy,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_key_clear),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
        )
    }
}

@Composable
private fun KeyInputDialog(
    target: SettingsKeyTarget,
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val title = keyTitle(target)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_key_dialog_title, title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
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
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = value.isNotBlank(),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun ClearKeyDialog(
    target: SettingsKeyTarget,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val title = keyTitle(target)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_key_clear_title)) },
        text = { Text(stringResource(R.string.settings_key_clear_message, title)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.settings_key_clear),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun keyTitle(target: SettingsKeyTarget): String = when (target) {
    SettingsKeyTarget.LLM -> stringResource(R.string.settings_key_llm_title)
    SettingsKeyTarget.FISH -> stringResource(R.string.settings_key_fish_title)
}

@Composable
private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.System -> stringResource(R.string.settings_theme_system)
    ThemeMode.Light -> stringResource(R.string.settings_theme_light)
    ThemeMode.Dark -> stringResource(R.string.settings_theme_dark)
}

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

private fun keyTargetByName(name: String): SettingsKeyTarget? =
    SettingsKeyTarget.entries.firstOrNull { it.name == name }

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
// Preview：浅色默认态 / 深色带字段错误。文案全部来自 strings.xml。
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
