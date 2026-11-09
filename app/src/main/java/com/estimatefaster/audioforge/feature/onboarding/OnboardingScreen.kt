@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.estimatefaster.audioforge.feature.onboarding

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.ui.components.ErrorBanner
import com.estimatefaster.audioforge.ui.components.ExpressiveCard
import com.estimatefaster.audioforge.ui.components.ExpressivePrimaryButton
import com.estimatefaster.audioforge.ui.components.ExpressiveTonalButton
import com.estimatefaster.audioforge.ui.components.StatusPill
import com.estimatefaster.audioforge.ui.components.StatusTone
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme
import com.estimatefaster.audioforge.ui.theme.spacing

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
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    TextButton(
                        onClick = onSkip,
                        enabled = state.canSkip,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.onboarding_skip),
                            style = MaterialTheme.typography.labelLarge,
                        )
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
                .padding(padding),
        ) {
            StepRail(
                step = state.step,
                modifier = Modifier.padding(
                    start = MaterialTheme.spacing.xl,
                    top = MaterialTheme.spacing.md,
                    end = MaterialTheme.spacing.xl,
                ),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = MaterialTheme.spacing.xl)
                    .padding(top = MaterialTheme.spacing.lg, bottom = MaterialTheme.spacing.xl),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.lg),
            ) {
                if (state.saveError != null) {
                    SaveErrorBanner(error = state.saveError)
                }

                OnboardingHero(step = state.step)

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
// 步骤进度与 hero
// ---------------------------------------------------------------------------

@Composable
private fun StepRail(
    step: OnboardingStep,
    modifier: Modifier = Modifier,
) {
    val inactiveShape = MaterialShapes.Pill.toShape()
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
    ) {
        OnboardingStep.entries.forEach { entry ->
            val current = entry == step
            val railHeight by animateDpAsState(
                targetValue = if (current) 10.dp else 6.dp,
                label = "onboardingStepRailHeight",
            )
            val railColor = if (entry.ordinal <= step.ordinal) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            }
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(railHeight)
                    // 步骤文字已由 hero 提供；色条只是视觉辅助，不单独进入读屏。
                    .clearAndSetSemantics { },
                shape = if (current) MaterialShapes.Cookie4Sided.toShape() else inactiveShape,
                color = railColor,
            ) {}
        }
    }
}

@Composable
private fun OnboardingHero(step: OnboardingStep) {
    val heroShape = MaterialShapes.Cookie9Sided.toShape(startAngle = 8)
    val badgeShape = MaterialShapes.Cookie6Sided.toShape()
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = heroShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = onContainer,
    ) {
        Row(
            modifier = Modifier.padding(MaterialTheme.spacing.xl),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.lg),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier.size(64.dp),
                shape = badgeShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = step.number.toString().padStart(length = 2, padChar = '0'),
                        style = MaterialTheme.typography.titleLargeEmphasized,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
            ) {
                Text(
                    text = stringResource(
                        R.string.onboarding_step_indicator,
                        step.number,
                        OnboardingStep.entries.size,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = onContainer.copy(alpha = 0.7f),
                )
                Text(
                    text = stringResource(step.taglineRes()),
                    style = MaterialTheme.typography.labelLarge,
                    color = onContainer.copy(alpha = 0.75f),
                )
                Text(
                    text = stringResource(step.titleRes()),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                )
                Text(
                    text = stringResource(step.subtitleRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = onContainer.copy(alpha = 0.82f),
                )
            }
        }
    }
}

@StringRes
private fun OnboardingStep.titleRes(): Int = when (this) {
    OnboardingStep.Welcome -> R.string.onboarding_welcome_title
    OnboardingStep.Keys -> R.string.onboarding_keys_title
    OnboardingStep.Permissions -> R.string.onboarding_permissions_title
}

@StringRes
private fun OnboardingStep.subtitleRes(): Int = when (this) {
    OnboardingStep.Welcome -> R.string.onboarding_welcome_subtitle
    OnboardingStep.Keys -> R.string.onboarding_keys_subtitle
    OnboardingStep.Permissions -> R.string.onboarding_permissions_subtitle
}

@StringRes
private fun OnboardingStep.taglineRes(): Int = when (this) {
    OnboardingStep.Welcome -> R.string.onboarding_hero_tagline_welcome
    OnboardingStep.Keys -> R.string.onboarding_hero_tagline_keys
    OnboardingStep.Permissions -> R.string.onboarding_hero_tagline_permissions
}

// ---------------------------------------------------------------------------
// 步骤内容
// ---------------------------------------------------------------------------

@Composable
private fun WelcomeStep() {
    Text(
        text = stringResource(R.string.onboarding_welcome_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FeatureCard(
        index = 1,
        title = stringResource(R.string.onboarding_feature_byok_title),
        body = stringResource(R.string.onboarding_feature_byok_body),
    )
    FeatureCard(
        index = 2,
        title = stringResource(R.string.onboarding_feature_sideload_title),
        body = stringResource(R.string.onboarding_feature_sideload_body),
    )
    FeatureCard(
        index = 3,
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
    FeatureCard(
        index = 1,
        title = stringResource(R.string.onboarding_notifications_title),
        body = stringResource(R.string.onboarding_notifications_body),
    )
    FeatureCard(
        index = 2,
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
private fun FeatureCard(index: Int, title: String, body: String) {
    val badgeShape = MaterialShapes.Cookie4Sided.toShape()
    ExpressiveCard(contentPadding = PaddingValues(MaterialTheme.spacing.lg)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = badgeShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = index.toString(),
                        style = MaterialTheme.typography.titleMediumEmphasized,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xxs),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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

    ExpressiveCard(contentPadding = PaddingValues(MaterialTheme.spacing.lg)) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleMediumEmphasized,
        )
        Text(
            text = stringResource(subtitleRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = state.keyFor(kind),
            onValueChange = { value -> onKeyChange(kind, value) },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
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
            shape = MaterialTheme.shapes.large,
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
            shape = MaterialTheme.shapes.large,
            label = { Text(stringResource(R.string.onboarding_model)) },
            singleLine = true,
            enabled = !state.saving,
            isError = modelError != null,
            supportingText = modelSupportingText,
        )

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        ) {
            ExpressiveTonalButton(
                text = stringResource(R.string.onboarding_test_connection),
                onClick = { onTestKey(kind) },
                enabled = !state.saving && testState != OnboardingTestState.Testing,
            )
            TestStatus(state = testState)
        }
    }
}

@Composable
private fun TestStatus(state: OnboardingTestState) {
    val liveModifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    when (state) {
        OnboardingTestState.Idle -> Unit

        OnboardingTestState.Testing -> StatusPill(
            text = stringResource(R.string.onboarding_testing),
            tone = StatusTone.Running,
            modifier = liveModifier,
        )

        OnboardingTestState.Ok -> StatusPill(
            text = stringResource(R.string.onboarding_test_passed),
            tone = StatusTone.Success,
            modifier = liveModifier,
        )

        is OnboardingTestState.Failed -> {
            val message = if (state.fieldError != null) {
                stringResource(state.fieldError.messageRes())
            } else {
                stringResource(R.string.onboarding_test_failed)
            }
            StatusPill(
                text = message,
                tone = StatusTone.Danger,
                modifier = liveModifier,
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
    ErrorBanner(message = message)
}

@Composable
private fun OnboardingBottomBar(
    state: OnboardingUiState,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSaveAndContinue: () -> Unit,
    onFinish: () -> Unit,
) {
    val primaryText = when (state.step) {
        OnboardingStep.Welcome -> stringResource(R.string.onboarding_action_start)
        OnboardingStep.Keys -> if (state.saving) {
            stringResource(R.string.action_saving)
        } else {
            stringResource(R.string.onboarding_action_save_and_continue)
        }
        OnboardingStep.Permissions -> if (state.saving) {
            stringResource(R.string.onboarding_action_finishing)
        } else {
            stringResource(R.string.onboarding_action_finish)
        }
    }
    val primaryAction = when (state.step) {
        OnboardingStep.Welcome -> onNext
        OnboardingStep.Keys -> onSaveAndContinue
        OnboardingStep.Permissions -> onFinish
    }
    val showBack = !state.step.isFirst
    val showLater = state.step == OnboardingStep.Keys

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(MaterialTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        ) {
            if (showBack || showLater) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showBack) {
                        TextButton(
                            onClick = onBack,
                            enabled = state.canSkip,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.onboarding_action_back),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    if (showLater) {
                        TextButton(
                            onClick = onNext,
                            enabled = state.canAdvance,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.onboarding_action_later),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }

            ExpressivePrimaryButton(
                text = primaryText,
                onClick = primaryAction,
                enabled = state.canAdvance,
                modifier = Modifier.fillMaxWidth(),
            )
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
// Preview：欢迎 / Key 填写（含测试结果）/ 权限说明（深色 200%）
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
    name = "Onboarding · 权限（深色 200%）",
    showBackground = true,
    backgroundColor = 0xFF0F1220,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    fontScale = 2f,
)
@Composable
private fun OnboardingPermissionsPreview() {
    AudioForgeTheme(darkTheme = true, dynamicColor = false) {
        OnboardingScreen(state = OnboardingUiState(step = OnboardingStep.Permissions))
    }
}
