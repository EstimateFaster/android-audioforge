@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.estimatefaster.audioforge.feature.onboarding

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estimatefaster.audioforge.R
import com.estimatefaster.audioforge.ui.forge.ForgeCard
import com.estimatefaster.audioforge.ui.forge.ForgeIcon
import com.estimatefaster.audioforge.ui.forge.ForgeIcons
import com.estimatefaster.audioforge.ui.forge.ForgePrimaryAction
import com.estimatefaster.audioforge.ui.forge.ForgeSecondaryAction
import com.estimatefaster.audioforge.ui.forge.ForgeTopBar
import com.estimatefaster.audioforge.ui.theme.AudioForgeTheme
import com.estimatefaster.audioforge.ui.theme.spacing

/**
 * 首次引导页（P4-10 / 执行计划 §12.4 S01，UX v2 重设计）。
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
 * 无状态引导页：3 步（欢迎 / Key / 权限）。
 *
 * 布局对应 `docs/prototype/forge-framework.html` 的 Onboarding：
 * 顶栏品牌 + 跳过、分段步骤条、MaterialShapes 大 hero、Lucide 图标功能卡、
 * 唯一主操作底栏。所有按钮都是「Lucide 图标 + 文案」或纯图标操作。
 *
 * 状态机、字段校验与保存/跳过回调保持原样：本文案层不改变任何业务判断。
 */
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
            ForgeTopBar(
                title = stringResource(R.string.onboarding_title),
                subtitle = stringResource(
                    R.string.ux_v2_onboarding_topbar_subtitle,
                    stringResource(R.string.app_name),
                ),
                actions = {
                    ForgeSecondaryAction(
                        icon = ForgeIcons.ChevronRight,
                        label = stringResource(R.string.onboarding_skip),
                        onClick = onSkip,
                        enabled = state.canSkip,
                    )
                },
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
                // 保存失败可能发生在任意步骤；放在内容顶部，避免被长表单挤出屏幕。
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
    val railDescription = stringResource(
        R.string.ux_v2_onboarding_step_rail_description,
        step.number,
        OnboardingStep.entries.size,
    )
    val inactiveShape = MaterialShapes.Pill.toShape()
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 整条进度轨只暴露一句“步骤 x / y”，内部色条不单独进入读屏。
            .clearAndSetSemantics { contentDescription = railDescription },
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
                    .height(railHeight),
                shape = if (current) MaterialShapes.Cookie4Sided.toShape() else inactiveShape,
                color = railColor,
            ) {}
        }
    }
}

@Composable
private fun OnboardingHero(step: OnboardingStep) {
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    ForgeCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentPadding = PaddingValues(MaterialTheme.spacing.lg),
    ) {
        HeroArtwork(step = step)
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
            color = onContainer,
        )
        Text(
            text = stringResource(step.subtitleRes()),
            style = MaterialTheme.typography.bodyMedium,
            color = onContainer.copy(alpha = 0.82f),
        )
    }
}

/**
 * 大 hero：有机 MaterialShapes 主图标 + 4 个浮动 Lucide 图标。
 * 纯装饰，整体 `clearAndSetSemantics`；步骤与标题由外层文本提供。
 */
@Composable
private fun HeroArtwork(step: OnboardingStep) {
    val mainShape = MaterialShapes.Cookie9Sided.toShape(startAngle = 8)
    val glowShape = MaterialShapes.Cookie12Sided.toShape()
    val badgeShape = MaterialShapes.Cookie4Sided.toShape()
    val pillShape = MaterialShapes.Pill.toShape()
    val gradient = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.secondary,
        ),
    )
    val icons = step.floatIcons()
    val floatTints = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.primary,
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(212.dp)
            .clearAndSetSemantics { },
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(188.dp)
                .clip(glowShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(136.dp)
                .clip(mainShape)
                .background(gradient),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(step.heroIcon()),
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }

        FloatBadge(
            icon = icons[0],
            shape = badgeShape,
            tint = floatTints[0],
            rotation = -8f,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = 40.dp, y = 16.dp),
        )
        FloatBadge(
            icon = icons[1],
            shape = pillShape,
            tint = floatTints[1],
            rotation = 7f,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-40).dp, y = 42.dp),
        )
        FloatBadge(
            icon = icons[2],
            shape = badgeShape,
            tint = floatTints[2],
            rotation = 5f,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = 34.dp, y = (-20).dp),
        )
        FloatBadge(
            icon = icons[3],
            shape = pillShape,
            tint = floatTints[3],
            rotation = -6f,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = (-30).dp, y = (-12).dp),
        )
    }
}

@Composable
private fun FloatBadge(
    @DrawableRes icon: Int,
    shape: Shape,
    tint: Color,
    rotation: Float,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .size(44.dp)
            .rotate(rotation),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = tint,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(contentAlignment = Alignment.Center) {
            ForgeIcon(icon = icon, contentDescription = null, tint = tint)
        }
    }
}

@DrawableRes
private fun OnboardingStep.heroIcon(): Int = when (this) {
    OnboardingStep.Welcome -> ForgeIcons.Sparkles
    OnboardingStep.Keys -> ForgeIcons.Key
    OnboardingStep.Permissions -> ForgeIcons.Shield
}

private fun OnboardingStep.floatIcons(): List<Int> = when (this) {
    OnboardingStep.Welcome -> listOf(
        ForgeIcons.Users,
        ForgeIcons.Headphones,
        ForgeIcons.Shield,
        ForgeIcons.BookOpen,
    )

    OnboardingStep.Keys -> listOf(
        ForgeIcons.Lock,
        ForgeIcons.Shield,
        ForgeIcons.Zap,
        ForgeIcons.Globe,
    )

    OnboardingStep.Permissions -> listOf(
        ForgeIcons.Bell,
        ForgeIcons.Battery,
        ForgeIcons.Storage,
        ForgeIcons.Wifi,
    )
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
    OnboardingStep.Welcome -> R.string.ux_v2_onboarding_hero_tagline_welcome
    OnboardingStep.Keys -> R.string.ux_v2_onboarding_hero_tagline_keys
    OnboardingStep.Permissions -> R.string.ux_v2_onboarding_hero_tagline_permissions
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
        icon = ForgeIcons.Key,
        title = stringResource(R.string.onboarding_feature_byok_title),
        body = stringResource(R.string.onboarding_feature_byok_body),
    )
    FeatureCard(
        icon = ForgeIcons.CloudDownload,
        title = stringResource(R.string.onboarding_feature_sideload_title),
        body = stringResource(R.string.onboarding_feature_sideload_body),
    )
    FeatureCard(
        icon = ForgeIcons.Lock,
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
        icon = ForgeIcons.Bell,
        title = stringResource(R.string.onboarding_notifications_title),
        body = stringResource(R.string.onboarding_notifications_body),
    )
    FeatureCard(
        icon = ForgeIcons.Battery,
        title = stringResource(R.string.onboarding_battery_title),
        body = stringResource(R.string.onboarding_battery_body),
    )
    FeatureCard(
        icon = ForgeIcons.Storage,
        title = stringResource(R.string.ux_v2_onboarding_storage_title),
        body = stringResource(R.string.ux_v2_onboarding_storage_body),
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
private fun FeatureCard(
    @DrawableRes icon: Int,
    title: String,
    body: String,
) {
    ForgeCard {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    ForgeIcon(
                        icon = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xxs),
            ) {
                Text(title, style = MaterialTheme.typography.titleSmallEmphasized)
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
    val providerIcon = when (kind) {
        OnboardingKeyKind.Llm -> ForgeIcons.Key
        OnboardingKeyKind.Fish -> ForgeIcons.AudioLines
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

    ForgeCard {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    ForgeIcon(
                        icon = providerIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xxs),
            ) {
                Text(stringResource(titleRes), style = MaterialTheme.typography.titleMediumEmphasized)
                Text(
                    text = stringResource(subtitleRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

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
            ForgeSecondaryAction(
                icon = ForgeIcons.Zap,
                label = stringResource(R.string.onboarding_test_connection),
                onClick = { onTestKey(kind) },
                enabled = !state.saving && testState != OnboardingTestState.Testing,
            )
            TestStatus(state = testState)
        }
    }
}

@Composable
private fun TestStatus(state: OnboardingTestState) {
    val liveModifier = Modifier.semantics(mergeDescendants = true) {
        liveRegion = LiveRegionMode.Polite
    }
    when (state) {
        OnboardingTestState.Idle -> Unit

        OnboardingTestState.Testing -> Row(
            modifier = liveModifier,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LoadingIndicator(modifier = Modifier.size(16.dp))
            Text(
                text = stringResource(R.string.onboarding_testing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OnboardingTestState.Ok -> Row(
            modifier = liveModifier,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ForgeIcon(
                icon = ForgeIcons.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.onboarding_test_passed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        is OnboardingTestState.Failed -> {
            val message = if (state.fieldError != null) {
                stringResource(state.fieldError.messageRes())
            } else {
                stringResource(R.string.onboarding_test_failed)
            }
            Row(
                modifier = liveModifier,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ForgeIcon(
                    icon = ForgeIcons.AlertCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
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
    ForgeCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentPadding = PaddingValues(MaterialTheme.spacing.md),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ForgeIcon(
                icon = ForgeIcons.AlertCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
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
    val primaryLabel = when (state.step) {
        OnboardingStep.Welcome -> stringResource(R.string.ux_v2_onboarding_primary_start)
        OnboardingStep.Keys -> if (state.saving) {
            stringResource(R.string.action_saving)
        } else {
            stringResource(R.string.onboarding_action_save_and_continue)
        }

        OnboardingStep.Permissions -> if (state.saving) {
            stringResource(R.string.onboarding_action_finishing)
        } else {
            stringResource(R.string.ux_v2_onboarding_primary_finish)
        }
    }
    val primaryIcon = when (state.step) {
        OnboardingStep.Welcome -> ForgeIcons.ChevronRight
        OnboardingStep.Keys -> ForgeIcons.Check
        OnboardingStep.Permissions -> ForgeIcons.Check
    }
    val primaryAction = when (state.step) {
        OnboardingStep.Welcome -> onNext
        OnboardingStep.Keys -> onSaveAndContinue
        OnboardingStep.Permissions -> onFinish
    }
    val showBack = !state.step.isFirst
    val showLater = state.step == OnboardingStep.Keys

    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
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
                        ForgeSecondaryAction(
                            icon = ForgeIcons.Back,
                            label = stringResource(R.string.onboarding_action_back),
                            onClick = onBack,
                            enabled = state.canSkip,
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    if (showLater) {
                        // “稍后设置”只跳过 Key 填写，不结束引导；不会清空已输入内容。
                        ForgeSecondaryAction(
                            icon = ForgeIcons.Clock,
                            label = stringResource(R.string.onboarding_action_later),
                            onClick = onNext,
                            enabled = state.canAdvance,
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }

            ForgePrimaryAction(
                icon = primaryIcon,
                label = primaryLabel,
                onClick = primaryAction,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.canAdvance,
                loading = state.saving,
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
