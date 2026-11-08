package com.estimatefaster.audioforge.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.estimatefaster.audioforge.core.settings.AppSettings
import com.estimatefaster.audioforge.core.settings.SecureKeyStore
import com.estimatefaster.audioforge.core.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 只暴露引导页需要的 Key 写入能力。
 *
 * 单独定义接口而不是直接依赖 [SecureKeyStore]，是为了让 ViewModel 的纯 JVM 测试
 * 注入记录型 fake；生产环境用 [OnboardingViewModel] 的次级构造器适配。
 */
fun interface OnboardingKeyStore {
    suspend fun saveKey(name: String, value: String)
}

/** 只暴露“原子读取 → 变换 → 写回”的设置能力（对应 [SettingsRepository.update]）。 */
fun interface OnboardingSettingsStore {
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

/**
 * “测试连接”探针。真实实现由外部注入，签名固定为
 * `suspend (key, baseUrl, model) -> Result<Unit>`。
 *
 * 注意（P4-10）：不要用会触发计费的对话 / 合成请求做连通性测试。
 * 默认的 [LocalKeyFormatTester] 只做本地格式校验，不发任何网络请求；后续接入
 * 真实网络探针时，应使用 `/models` 等不产生生成费用的端点。
 */
fun interface OnboardingConnectionTester {
    suspend fun test(key: String, baseUrl: String, model: String): Result<Unit>
}

/**
 * P4-10 默认探针：只检查 Key 的基本格式，不发起网络请求，也不产生任何费用。
 *
 * 长度下限取 8 是为了挡住明显误粘贴的空串/单字符；真正的鉴权错误仍由后续真实
 * 网络探针或实际生成任务反馈。失败原因不会直接展示给用户（UI 使用统一文案）。
 */
object LocalKeyFormatTester : OnboardingConnectionTester {
    private const val MIN_KEY_LENGTH = 8

    override suspend fun test(key: String, baseUrl: String, model: String): Result<Unit> {
        val trimmed = key.trim()
        return when {
            trimmed.length < MIN_KEY_LENGTH ->
                Result.failure<Unit>(IllegalArgumentException("api key is too short"))
            trimmed.any { it.isWhitespace() } ->
                Result.failure<Unit>(IllegalArgumentException("api key contains whitespace"))
            baseUrl.isBlank() ->
                Result.failure<Unit>(IllegalArgumentException("base url is blank"))
            model.isBlank() ->
                Result.failure<Unit>(IllegalArgumentException("model is blank"))
            else -> Result.success(Unit)
        }
    }
}

/**
 * 首次引导 ViewModel（P4-10 / 执行计划 §12.4 S01）。
 *
 * 流程：
 * 1. `Welcome` → 介绍 BYOK / 只侧载 / 数据本地；
 * 2. `Keys` → 配置 LLM 与 Fish 的 Key、Base URL、Model，可测试、保存或稍后设置；
 * 3. `Permissions` → 只解释通知与电池优化，不强制申请。
 *
 * 语义约定：
 * - **Key 可为空**。空 Key 可以继续/完成，进入应用后仍可浏览；开始生成时由业务侧
 *   拦截并提示补充 Key；
 * - [save] 只保存 Key 与 base/model，不结束引导；[saveAndContinue] 保存后进入第 3 步；
 * - [finish] 保存（含空的 Key 字段跳过）并写入 `onboarding_completed=true`；
 * - [skip] 只写 `onboarding_completed=true`，不保存用户刚输入的 Key；
 * - 保存失败时停留在当前步骤并给出 [OnboardingSaveError]；测试失败可再次调用
 *   [testKey] 重试。
 *
 * @param keyStore SecureKeyStore 适配层
 * @param settingsStore SettingsRepository 适配层
 * @param llmTester LLM Key 测试探针
 * @param fishTester Fish Key 测试探针
 * @param initialState 初始状态（测试/预览用）
 * @param externalScope 测试注入的协程作用域；为空时使用 [viewModelScope]
 * @param dispatcher 协程调度器；测试注入 `StandardTestDispatcher(testScheduler)`，
 *   保证 `advanceUntilIdle()` 能推进保存/测试协程
 */
class OnboardingViewModel(
    private val keyStore: OnboardingKeyStore,
    private val settingsStore: OnboardingSettingsStore,
    private val llmTester: OnboardingConnectionTester,
    private val fishTester: OnboardingConnectionTester = llmTester,
    initialState: OnboardingUiState = OnboardingUiState(),
    private val externalScope: CoroutineScope? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {

    /**
     * 生产入口：把 [SecureKeyStore] / [SettingsRepository] 适配成两个最小写接口。
     *
     * 默认 tester 只做本地格式校验；需要真实连通性校验时由调用方注入。
     */
    constructor(
        secureKeyStore: SecureKeyStore,
        settingsRepository: SettingsRepository,
        llmTester: OnboardingConnectionTester = LocalKeyFormatTester,
        fishTester: OnboardingConnectionTester = LocalKeyFormatTester,
        dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    ) : this(
        keyStore = object : OnboardingKeyStore {
            override suspend fun saveKey(name: String, value: String) {
                secureKeyStore.set(name, value)
            }
        },
        settingsStore = object : OnboardingSettingsStore {
            override suspend fun update(transform: (AppSettings) -> AppSettings) {
                settingsRepository.update(transform)
            }
        },
        llmTester = llmTester,
        fishTester = fishTester,
        dispatcher = dispatcher,
    )

    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    private val scope: CoroutineScope
        get() = externalScope ?: viewModelScope

    private var testJob: Job? = null

    // ------------------------------------------------------------------
    // 字段编辑
    // ------------------------------------------------------------------

    fun updateKey(kind: OnboardingKeyKind, value: String) {
        cancelTest()
        _state.update { it.withKey(kind, value) }
    }

    fun updateBaseUrl(kind: OnboardingKeyKind, value: String) {
        cancelTest()
        _state.update { it.withBaseUrl(kind, value) }
    }

    fun updateModel(kind: OnboardingKeyKind, value: String) {
        cancelTest()
        _state.update { it.withModel(kind, value) }
    }

    // ------------------------------------------------------------------
    // 步骤导航
    // ------------------------------------------------------------------

    /**
     * 前进一步；末步等价于 [finish]。
     *
     * Keys 步骤要求地址/模型合法（Key 可为空）；非法时留在原地并设置
     * [OnboardingSaveError.InvalidFields]，由 UI 展示字段错误。
     */
    fun goNext() {
        val current = _state.value
        if (current.saving || current.completed) return

        when (current.step) {
            OnboardingStep.Welcome -> _state.update {
                it.copy(step = OnboardingStep.Keys, saveError = null)
            }

            OnboardingStep.Keys -> if (current.keysValid) {
                _state.update {
                    it.copy(step = OnboardingStep.Permissions, saveError = null)
                }
            } else {
                _state.update { it.copy(saveError = OnboardingSaveError.InvalidFields) }
            }

            OnboardingStep.Permissions -> finish()
        }
    }

    /** 后退一步；首步或保存中不动作。 */
    fun goBack() {
        val current = _state.value
        if (current.saving || current.completed || current.step.isFirst) return
        _state.update {
            it.copy(step = current.step.previous(), saveError = null)
        }
    }

    // ------------------------------------------------------------------
    // 测试连接
    // ------------------------------------------------------------------

    /**
     * 测试 [kind] 对应的 Key。
     *
     * 先做本地校验（URL / model / Key 非空），再调用注入的 tester；失败后再次调用
     * 即重试。编辑任意字段会取消在途测试并把状态复位为 [OnboardingTestState.Idle]。
     */
    fun testKey(kind: OnboardingKeyKind) {
        val current = _state.value
        if (current.saving || current.completed) return

        // 同一时间只保留一个在途测试；切换 provider 或重试都先取消旧任务。
        cancelTest()

        val key = current.keyFor(kind).trim()
        val fieldError = current.urlErrorFor(kind)
            ?: current.modelErrorFor(kind)
            ?: if (key.isEmpty()) OnboardingFieldError.KeyBlank else null

        if (fieldError != null) {
            setTestState(kind, OnboardingTestState.Failed(fieldError))
            return
        }

        setTestState(kind, OnboardingTestState.Testing)

        val tester = when (kind) {
            OnboardingKeyKind.Llm -> llmTester
            OnboardingKeyKind.Fish -> fishTester
        }
        val baseUrl = current.baseUrlFor(kind).trim()
        val model = current.modelFor(kind).trim()

        testJob = scope.launch(dispatcher) {
            val result = try {
                tester.test(key, baseUrl, model)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure<Unit>(e)
            }

            // 编辑字段会取消旧任务；tester 若不可取消，也要避免旧结果覆盖新状态。
            if (!isActive) return@launch

            setTestState(
                kind,
                result.fold(
                    onSuccess = { OnboardingTestState.Ok },
                    onFailure = { OnboardingTestState.Failed() },
                ),
            )
        }
    }

    // ------------------------------------------------------------------
    // 保存 / 完成 / 跳过
    // ------------------------------------------------------------------

    /** 保存 Key 与 base/model，但不结束引导（“保存”按钮/自动化可单独调用）。 */
    fun save() {
        saveInternal()
    }

    /** 保存后进入第 3 步；失败时停留并展示错误。 */
    fun saveAndContinue() {
        saveInternal {
            _state.update { it.copy(step = OnboardingStep.Permissions) }
        }
    }

    /** 完成引导：保存输入并写入 `onboarding_completed=true`。无 Key 也允许完成。 */
    fun finish() {
        val current = _state.value
        if (current.saving || current.completed) return
        if (!current.keysValid) {
            _state.update { it.copy(saveError = OnboardingSaveError.InvalidFields) }
            return
        }

        cancelTest()
        persist(current, markCompleted = true) {
            _state.update { it.copy(completed = true, skipped = false) }
        }
    }

    /**
     * 跳过整个引导：只写 `onboarding_completed=true`，不保存用户刚输入的 Key。
     * 无 Key 也可进入应用；生成任务时由业务侧提示补充 Key。
     */
    fun skip() {
        val current = _state.value
        if (current.saving || current.completed) return

        cancelTest()
        _state.update { it.copy(saving = true, saveError = null) }
        scope.launch(dispatcher) {
            try {
                settingsStore.update { it.copy(onboardingCompleted = true) }
                _state.update {
                    it.copy(
                        saving = false,
                        saveError = null,
                        completed = true,
                        skipped = true,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(saving = false, saveError = e.toSaveError())
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private fun saveInternal(onSuccess: () -> Unit = {}) {
        val current = _state.value
        if (current.saving || current.completed) return
        if (!current.keysValid) {
            _state.update { it.copy(saveError = OnboardingSaveError.InvalidFields) }
            return
        }
        cancelTest()
        persist(current, markCompleted = false, onSuccess = onSuccess)
    }

    /**
     * 保存快照中的 Key 与 base/model。
     *
     * - 空白 Key 视为“稍后设置”，不调用 [OnboardingKeyStore]（避免把已有 Key 清空）；
     * - Key 先写、设置后写；任一步失败都停留并给出 [OnboardingSaveError]；
     * - [markCompleted] 为 true 时把 `onboardingCompleted` 置为 true。
     */
    private fun persist(
        snapshot: OnboardingUiState,
        markCompleted: Boolean,
        onSuccess: () -> Unit,
    ) {
        _state.update { it.copy(saving = true, saveError = null) }
        scope.launch(dispatcher) {
            try {
                val llmKey = snapshot.llmKey.trim()
                if (llmKey.isNotEmpty()) {
                    keyStore.saveKey(SecureKeyStore.LLM_API_KEY, llmKey)
                }

                val fishKey = snapshot.fishKey.trim()
                if (fishKey.isNotEmpty()) {
                    keyStore.saveKey(SecureKeyStore.FISH_API_KEY, fishKey)
                }

                val llmBaseUrl = snapshot.llmBaseUrl.trim()
                val llmModel = snapshot.llmModel.trim()
                val fishBaseUrl = snapshot.fishBaseUrl.trim()
                val fishModel = snapshot.fishModel.trim()

                settingsStore.update { current ->
                    current.copy(
                        llmBaseUrl = llmBaseUrl,
                        llmModel = llmModel,
                        fishBaseUrl = fishBaseUrl,
                        fishTtsModel = fishModel,
                        onboardingCompleted = current.onboardingCompleted || markCompleted,
                    )
                }

                _state.update { it.copy(saving = false, saveError = null) }
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(saving = false, saveError = e.toSaveError())
                }
            }
        }
    }

    private fun setTestState(kind: OnboardingKeyKind, value: OnboardingTestState) {
        _state.update { it.withTestState(kind, value) }
    }

    private fun cancelTest() {
        testJob?.cancel()
        testJob = null
        // 两个 provider 共用一条测试任务；取消时把仍在 Testing 的卡片复位，
        // 避免切换测试对象后另一张卡永久显示“正在测试”。
        _state.update { current ->
            val llmTest = if (current.llmTest == OnboardingTestState.Testing) {
                OnboardingTestState.Idle
            } else {
                current.llmTest
            }
            val fishTest = if (current.fishTest == OnboardingTestState.Testing) {
                OnboardingTestState.Idle
            } else {
                current.fishTest
            }
            if (llmTest == current.llmTest && fishTest == current.fishTest) {
                current
            } else {
                current.copy(llmTest = llmTest, fishTest = fishTest)
            }
        }
    }

    private fun Exception.toSaveError(): OnboardingSaveError =
        OnboardingSaveError.PersistFailed(message?.takeIf { it.isNotBlank() })
}
