package com.estimatefaster.audioforge.feature.onboarding

import com.estimatefaster.audioforge.core.settings.AppSettings
import com.estimatefaster.audioforge.core.settings.SettingsValidator

/**
 * 引导页的三个步骤（执行计划 §12.4 S01 / P4-10）。
 *
 * 顺序即展示顺序；[next]/[previous] 在边界上返回自身，调用方不需要自己判断越界。
 */
enum class OnboardingStep {
    Welcome,
    Keys,
    Permissions,
    ;

    /** 从 1 开始的步骤序号，用于“第 1 / 3 步”文案。 */
    val number: Int
        get() = ordinal + 1

    val isFirst: Boolean
        get() = this == Welcome

    val isLast: Boolean
        get() = this == Permissions

    fun next(): OnboardingStep = entries.getOrElse(ordinal + 1) { this }

    fun previous(): OnboardingStep = entries.getOrElse(ordinal - 1) { this }
}

/** 需要配置的两类 Key；只用于字段路由，不参与持久化键名。 */
enum class OnboardingKeyKind {
    Llm,
    Fish,
}

/**
 * 字段级错误。
 *
 * ViewModel 不持有用户可见文案：UI 负责把枚举映射到 `strings.xml`，这样纯 JVM
 * 测试可以断言错误类型，而不是中文句子。
 */
enum class OnboardingFieldError {
    UrlBlank,
    UrlInvalid,
    ModelBlank,
    KeyBlank,
}

/** “测试连接”的状态机；失败后再次调用 `testKey` 即为重试。 */
sealed interface OnboardingTestState {
    data object Idle : OnboardingTestState
    data object Testing : OnboardingTestState
    data object Ok : OnboardingTestState

    /**
     * @param fieldError 本地字段校验失败（空 Key / 非法 URL / 空模型）；
     *   为空表示 tester 返回失败，UI 展示通用失败文案。
     */
    data class Failed(val fieldError: OnboardingFieldError? = null) : OnboardingTestState
}

/** 保存 / 跳过失败的原因；同样由 UI 映射文案。 */
sealed interface OnboardingSaveError {
    /** 地址或模型不合法，尚未写入任何存储。 */
    data object InvalidFields : OnboardingSaveError

    /** SecureKeyStore / SettingsRepository 写入失败。 */
    data class PersistFailed(val message: String? = null) : OnboardingSaveError
}

/**
 * 引导页的不可变 UI 状态。
 *
 * 设计要点：
 * - 字段默认值直接取 [AppSettings.DEFAULT]，与设置页/仓库使用同一套默认地址与模型；
 * - URL 校验复用 [SettingsValidator]，保证这里放行的值在
 *   `SettingsRepository.update` 时不会被二次校验拒绝；
 * - **Key 允许为空**：空 Key 表示“稍后设置”，可继续浏览；只有点击“测试连接”
 *   时才要求非空（由 [OnboardingTestState.Failed] 携带 `KeyBlank`）；
 * - 所有方法/派生属性都是纯函数，方便纯 JVM 测试。
 */
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.Welcome,

    // LLM
    val llmKey: String = "",
    val llmBaseUrl: String = AppSettings.DEFAULT.llmBaseUrl,
    val llmModel: String = AppSettings.DEFAULT.llmModel,

    // Fish Audio TTS
    val fishKey: String = "",
    val fishBaseUrl: String = AppSettings.DEFAULT.fishBaseUrl,
    val fishModel: String = AppSettings.DEFAULT.fishTtsModel,

    val llmTest: OnboardingTestState = OnboardingTestState.Idle,
    val fishTest: OnboardingTestState = OnboardingTestState.Idle,

    val saving: Boolean = false,
    val saveError: OnboardingSaveError? = null,

    /** 已成功写入 `onboarding_completed=true`。 */
    val completed: Boolean = false,

    /** [completed] 是由“跳过”触发的（导航层可用不同动画/埋点）。 */
    val skipped: Boolean = false,
) {

    val stepNumber: Int
        get() = step.number

    val stepCount: Int
        get() = OnboardingStep.entries.size

    // ------------------------------------------------------------------
    // 字段校验
    // ------------------------------------------------------------------

    val llmUrlError: OnboardingFieldError?
        get() = baseUrlError(llmBaseUrl, LLM_URL_FIELD)

    val llmModelError: OnboardingFieldError?
        get() = modelError(llmModel)

    val fishUrlError: OnboardingFieldError?
        get() = baseUrlError(fishBaseUrl, FISH_URL_FIELD)

    val fishModelError: OnboardingFieldError?
        get() = modelError(fishModel)

    /** 地址与模型合法；Key 是否填写不参与判断（允许稍后设置）。 */
    val keysValid: Boolean
        get() = llmUrlError == null &&
            llmModelError == null &&
            fishUrlError == null &&
            fishModelError == null

    /** 当前步骤的主按钮是否可用；末步等价于 [canFinish]。 */
    val canAdvance: Boolean
        get() = when (step) {
            OnboardingStep.Welcome -> !saving && !completed
            OnboardingStep.Keys -> keysValid && !saving && !completed
            OnboardingStep.Permissions -> canFinish
        }

    val canFinish: Boolean
        get() = keysValid && !saving && !completed

    val canSkip: Boolean
        get() = !saving && !completed

    // ------------------------------------------------------------------
    // 按 Key 类型路由
    // ------------------------------------------------------------------

    fun keyFor(kind: OnboardingKeyKind): String = when (kind) {
        OnboardingKeyKind.Llm -> llmKey
        OnboardingKeyKind.Fish -> fishKey
    }

    fun baseUrlFor(kind: OnboardingKeyKind): String = when (kind) {
        OnboardingKeyKind.Llm -> llmBaseUrl
        OnboardingKeyKind.Fish -> fishBaseUrl
    }

    fun modelFor(kind: OnboardingKeyKind): String = when (kind) {
        OnboardingKeyKind.Llm -> llmModel
        OnboardingKeyKind.Fish -> fishModel
    }

    fun urlErrorFor(kind: OnboardingKeyKind): OnboardingFieldError? = when (kind) {
        OnboardingKeyKind.Llm -> llmUrlError
        OnboardingKeyKind.Fish -> fishUrlError
    }

    fun modelErrorFor(kind: OnboardingKeyKind): OnboardingFieldError? = when (kind) {
        OnboardingKeyKind.Llm -> llmModelError
        OnboardingKeyKind.Fish -> fishModelError
    }

    fun testStateFor(kind: OnboardingKeyKind): OnboardingTestState = when (kind) {
        OnboardingKeyKind.Llm -> llmTest
        OnboardingKeyKind.Fish -> fishTest
    }

    // ------------------------------------------------------------------
    // 不可变更新：编辑字段时清掉旧的测试结果与保存错误
    // ------------------------------------------------------------------

    fun withKey(kind: OnboardingKeyKind, value: String): OnboardingUiState = when (kind) {
        OnboardingKeyKind.Llm -> copy(
            llmKey = value,
            llmTest = OnboardingTestState.Idle,
            saveError = null,
        )
        OnboardingKeyKind.Fish -> copy(
            fishKey = value,
            fishTest = OnboardingTestState.Idle,
            saveError = null,
        )
    }

    fun withBaseUrl(kind: OnboardingKeyKind, value: String): OnboardingUiState = when (kind) {
        OnboardingKeyKind.Llm -> copy(
            llmBaseUrl = value,
            llmTest = OnboardingTestState.Idle,
            saveError = null,
        )
        OnboardingKeyKind.Fish -> copy(
            fishBaseUrl = value,
            fishTest = OnboardingTestState.Idle,
            saveError = null,
        )
    }

    fun withModel(kind: OnboardingKeyKind, value: String): OnboardingUiState = when (kind) {
        OnboardingKeyKind.Llm -> copy(
            llmModel = value,
            llmTest = OnboardingTestState.Idle,
            saveError = null,
        )
        OnboardingKeyKind.Fish -> copy(
            fishModel = value,
            fishTest = OnboardingTestState.Idle,
            saveError = null,
        )
    }

    internal fun withTestState(
        kind: OnboardingKeyKind,
        value: OnboardingTestState,
    ): OnboardingUiState = when (kind) {
        OnboardingKeyKind.Llm -> copy(llmTest = value)
        OnboardingKeyKind.Fish -> copy(fishTest = value)
    }
}

/**
 * URL 校验与 `SettingsValidator` 保持同一套规则（http/https + host 非空）。
 *
 * 空值与非法值分开报错，UI 可以给出更具体的提示。
 */
private fun baseUrlError(raw: String, field: String): OnboardingFieldError? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return OnboardingFieldError.UrlBlank

    val settings = when (field) {
        LLM_URL_FIELD -> AppSettings(llmBaseUrl = trimmed)
        else -> AppSettings(fishBaseUrl = trimmed)
    }
    return if (SettingsValidator.validate(settings).errors.containsKey(field)) {
        OnboardingFieldError.UrlInvalid
    } else {
        null
    }
}

private fun modelError(raw: String): OnboardingFieldError? =
    if (raw.isBlank()) OnboardingFieldError.ModelBlank else null

private const val LLM_URL_FIELD = "llmBaseUrl"
private const val FISH_URL_FIELD = "fishBaseUrl"
