package com.estimatefaster.audioforge.feature.onboarding

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.ui.components.SectionHeader
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme

/**
 * 首次引导页（P4-10 / 执行计划 §12.4 S01）。
 *
 * 有状态入口：从 [OnboardingViewModel.state] 收集状态后委托给无状态版本。
 * [onFinished] / [onSkipped] 只在状态机真正写完 `onboarding_completed` 后触发，
 * 避免“点了完成但设置没落盘”就离开引导。
 */
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    modifier: Modifier = Modifier,
    onFinished: () -> Unit = {},
    onSkipped: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.completed, state.skipped) {
        if (state.completed) {
            if (state.skipped) onSkipped() else onFinished()
        }
    }

    OnboardingScreen(
        state = state,
        modifier = modifier,
        onKeyChange = viewModel::updateKey,
        onBaseUrlChange = viewModel::updateBaseUrl,
        onModelChange = viewModel::updateModel,
        onTestKey = viewModel::testKey,
        onNext = viewModel::goNext,
        onBack = viewModel::goBack,
        onSaveAndContinue = viewModel::saveAndContinue,
        onFinish = viewModel::finish,
        onSkip = viewModel::skip,
    )
}

/**
 * 无状态引导页：3 步水平切换（欢迎 / Key / 权限说明）。
 *
 * 所有用户可见文案来自 `strings.xml`；第 3 步只解释通知与电池优化，不主动申请
 * 任何权限（申请入口留给真正需要它的生成/任务页）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    modifier: Modifier = Modifier,
    onKeyChange: (OnboardingKeyKind, String) -> Unit = { _, _ -> },
    onBaseUrlChange: (OnboardingKeyKind, String) -> Unit = { _, _ -> },
    onModelChange: (OnboardingKeyKind, String) -> Unit = { _, _ -> },
    onTestKey: (OnboardingKeyKind) -> Unit = {},
    onNext: () -> Unit = {},
    onBack: () -> Unit = {},
    onSaveAndContinue: () -> Unit = {},
    onFinish: () -> Unit = {},
    onSkip: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.onboarding_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                actions = {
                    TextButton(
                        onClick = onSkip,
                        enabled = state.canSkip,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.onboarding_skip))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            OnboardingBottomBar(
                state = state,
                onBack = onBack,
                onNext = onNext,
                onSaveAndContinue = onSaveAndContinue,
                onFinish = onFinish,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            StepProgress(step = state.step)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 16.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 保存失败可能发生在任意步骤；放在内容顶部，避免被长表单挤出屏幕。
                if (state.saveError != null) {
                    SaveErrorBanner(error = state.saveError)
                }

                when (state.step) {
                    OnboardingStep.Welcome -> WelcomeStep()
                    OnboardingStep.Keys -> KeysStep(
                        state = state,
                        onKeyChange = onKeyChange,
                        onBaseUrlChange = onBaseUrlChange,
                        onModelChange = onModelChange,
                        onTestKey = onTestKey,
                    )
                    OnboardingStep.Permissions -> PermissionsStep()
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 步骤内容
// ---------------------------------------------------------------------------

@Composable
private fun StepProgress(step: OnboardingStep) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(
                R.string.onboarding_step_indicator,
                step.number,
                OnboardingStep.entries.size,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OnboardingStep.entries.forEach { entry ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .background(
                            color = if (entry.ordinal <= step.ordinal) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            shape = CircleShape,
                        ),
                )
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
    StepTitle(
        title = stringResource(R.string.onboarding_welcome_title),
        subtitle = stringResource(R.string.onboarding_welcome_subtitle),
    )
    Text(
        text = stringResource(R.string.onboarding_welcome_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FeatureCard(
        title = stringResource(R.string.onboarding_feature_byok_title),
        body = stringResource(R.string.onboarding_feature_byok_body),
    )
    FeatureCard(
        title = stringResource(R.string.onboarding_feature_sideload_title),
        body = stringResource(R.string.onboarding_feature_sideload_body),
    )
    FeatureCard(
        title = stringResource(R.string.onboarding_feature_local_title),
        body = stringResource(R.string.onboarding_feature_local_body),
    )
}

@Composable
private fun KeysStep(
    state: OnboardingUiState,
    onKeyChange: (OnboardingKeyKind, String) -> Unit,
    onBaseUrlChange: (OnboardingKeyKind, String) -> Unit,
    onModelChange: (OnboardingKeyKind, String) -> Unit,
    onTestKey: (OnboardingKeyKind) -> Unit,
) {
    StepTitle(
        title = stringResource(R.string.onboarding_keys_title),
        subtitle = stringResource(R.string.onboarding_keys_subtitle),
    )

    ProviderKeyCard(
        kind = OnboardingKeyKind.Llm,
        state = state,
        onKeyChange = onKeyChange,
        onBaseUrlChange = onBaseUrlChange,
        onModelChange = onModelChange,
        onTestKey = onTestKey,
    )
    ProviderKeyCard(
        kind = OnboardingKeyKind.Fish,
        state = state,
        onKeyChange = onKeyChange,
        onBaseUrlChange = onBaseUrlChange,
        onModelChange = onModelChange,
        onTestKey = onTestKey,
    )

    Text(
        text = stringResource(R.string.onboarding_test_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = stringResource(R.string.onboarding_no_key_notice),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PermissionsStep() {
    StepTitle(
        title = stringResource(R.string.onboarding_permissions_title),
        subtitle = stringResource(R.string.onboarding_permissions_subtitle),
    )
    FeatureCard(
        title = stringResource(R.string.onboarding_notifications_title),
        body = stringResource(R.string.onboarding_notifications_body),
    )
    FeatureCard(
        title = stringResource(R.string.onboarding_battery_title),
        body = stringResource(R.string.onboarding_battery_body),
    )
    Text(
        text = stringResource(R.string.onboarding_permissions_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ---------------------------------------------------------------------------
// 可复用小块
// ---------------------------------------------------------------------------

@Composable
private fun StepTitle(title: String, subtitle: String) {
    Column(
        modifier = Modifier.semantics { heading() },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FeatureCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProviderKeyCard(
    kind: OnboardingKeyKind,
    state: OnboardingUiState,
    onKeyChange: (OnboardingKeyKind, String) -> Unit,
    onBaseUrlChange: (OnboardingKeyKind, String) -> Unit,
    onModelChange: (OnboardingKeyKind, String) -> Unit,
    onTestKey: (OnboardingKeyKind) -> Unit,
) {
    val titleRes = when (kind) {
        OnboardingKeyKind.Llm -> R.string.onboarding_llm_title
        OnboardingKeyKind.Fish -> R.string.onboarding_fish_title
    }
    val subtitleRes = when (kind) {
        OnboardingKeyKind.Llm -> R.string.onboarding_llm_subtitle
        OnboardingKeyKind.Fish -> R.string.onboarding_fish_subtitle
    }

    val urlError = state.urlErrorFor(kind)
    val modelError = state.modelErrorFor(kind)
    val testState = state.testStateFor(kind)

    val urlErrorText = if (urlError != null) stringResource(urlError.messageRes()) else null
    val modelErrorText = if (modelError != null) stringResource(modelError.messageRes()) else null
    val urlSupportingText: (@Composable () -> Unit)? = if (urlErrorText != null) {
        { Text(urlErrorText) }
    } else {
        null
    }
    val modelSupportingText: (@Composable () -> Unit)? = if (modelErrorText != null) {
        { Text(modelErrorText) }
    } else {
        null
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionHeader(
                title = stringResource(titleRes),
                subtitle = stringResource(subtitleRes),
            )

            OutlinedTextField(
                value = state.keyFor(kind),
                onValueChange = { value -> onKeyChange(kind, value) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.onboarding_api_key)) },
                placeholder = {
                    Text(stringResource(R.string.onboarding_api_key_placeholder))
                },
                singleLine = true,
                enabled = !state.saving,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )

            OutlinedTextField(
                value = state.baseUrlFor(kind),
                onValueChange = { value -> onBaseUrlChange(kind, value) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.onboarding_base_url)) },
                singleLine = true,
                enabled = !state.saving,
                isError = urlError != null,
                supportingText = urlSupportingText,
            )

            OutlinedTextField(
                value = state.modelFor(kind),
                onValueChange = { value -> onModelChange(kind, value) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.onboarding_model)) },
                singleLine = true,
                enabled = !state.saving,
                isError = modelError != null,
                supportingText = modelSupportingText,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { onTestKey(kind) },
                    enabled = !state.saving && testState != OnboardingTestState.Testing,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.onboarding_test_connection))
                }
                TestStatus(state = testState)
            }
        }
    }
}

@Composable
private fun TestStatus(state: OnboardingTestState) {
    when (state) {
        OnboardingTestState.Idle -> Unit

        OnboardingTestState.Testing -> Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
            Text(
                text = stringResource(R.string.onboarding_testing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OnboardingTestState.Ok -> Text(
            text = stringResource(R.string.onboarding_test_passed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )

        is OnboardingTestState.Failed -> {
            val message = if (state.fieldError != null) {
                stringResource(state.fieldError.messageRes())
            } else {
                stringResource(R.string.onboarding_test_failed)
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SaveErrorBanner(error: OnboardingSaveError) {
    val message = when (error) {
        OnboardingSaveError.InvalidFields ->
            stringResource(R.string.onboarding_error_fix_fields)
        is OnboardingSaveError.PersistFailed ->
            stringResource(R.string.onboarding_save_failed)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun OnboardingBottomBar(
    state: OnboardingUiState,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSaveAndContinue: () -> Unit,
    onFinish: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!state.step.isFirst) {
                    OutlinedButton(
                        onClick = onBack,
                        enabled = state.canSkip,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.onboarding_action_back))
                    }
                }
                Spacer(Modifier.weight(1f))

                when (state.step) {
                    OnboardingStep.Welcome -> Button(
                        onClick = onNext,
                        enabled = state.canAdvance,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.onboarding_action_start))
                    }

                    OnboardingStep.Keys -> Button(
                        onClick = onSaveAndContinue,
                        enabled = state.canAdvance,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(
                            text = if (state.saving) {
                                stringResource(R.string.onboarding_action_saving)
                            } else {
                                stringResource(R.string.onboarding_action_save_and_continue)
                            },
                        )
                    }

                    OnboardingStep.Permissions -> Button(
                        onClick = onFinish,
                        enabled = state.canFinish,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(
                            text = if (state.saving) {
                                stringResource(R.string.onboarding_action_finishing)
                            } else {
                                stringResource(R.string.onboarding_action_finish)
                            },
                        )
                    }
                }
            }

            // “稍后设置”只跳过 Key 填写，不结束引导；它不会清空已输入内容。
            if (state.step == OnboardingStep.Keys) {
                TextButton(
                    onClick = onNext,
                    enabled = state.canAdvance,
                    modifier = Modifier
                        .align(Alignment.End)
                        .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.onboarding_action_later))
                }
            }
        }
    }
}

/** 字段错误到文案的唯一映射点；ViewModel 不依赖 Android 资源。 */
@StringRes
private fun OnboardingFieldError.messageRes(): Int = when (this) {
    OnboardingFieldError.UrlBlank -> R.string.onboarding_error_url_blank
    OnboardingFieldError.UrlInvalid -> R.string.onboarding_error_url_invalid
    OnboardingFieldError.ModelBlank -> R.string.onboarding_error_model_blank
    OnboardingFieldError.KeyBlank -> R.string.onboarding_test_key_blank
}

// ---------------------------------------------------------------------------
// Preview：欢迎 / Key 填写（含测试结果）/ 权限说明（深色）
// ---------------------------------------------------------------------------

private fun previewKeysState(): OnboardingUiState = OnboardingUiState(
    step = OnboardingStep.Keys,
    llmKey = "sk-preview-123456",
    fishKey = "fish-preview-123456",
    llmTest = OnboardingTestState.Ok,
    fishTest = OnboardingTestState.Failed(),
)

@Preview(
    name = "Onboarding · 欢迎",
    showBackground = true,
    backgroundColor = 0xFFF7F8FC,
)
@Composable
private fun OnboardingWelcomePreview() {
    AudioForgeTheme(dynamicColor = false) {
        OnboardingScreen(state = OnboardingUiState())
    }
}

@Preview(
    name = "Onboarding · API Key",
    showBackground = true,
    backgroundColor = 0xFFF7F8FC,
)
@Composable
private fun OnboardingKeysPreview() {
    AudioForgeTheme(dynamicColor = false) {
        OnboardingScreen(state = previewKeysState())
    }
}

@Preview(
    name = "Onboarding · 权限（深色）",
    showBackground = true,
    backgroundColor = 0xFF0F1220,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun OnboardingPermissionsPreview() {
    AudioForgeTheme(darkTheme = true, dynamicColor = false) {
        OnboardingScreen(state = OnboardingUiState(step = OnboardingStep.Permissions))
    }
}
