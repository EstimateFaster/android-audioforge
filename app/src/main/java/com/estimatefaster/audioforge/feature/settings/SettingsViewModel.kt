package com.estimatefaster.audioforge.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.estimatefaster.audioforge.core.settings.AppSettings
import com.estimatefaster.audioforge.core.settings.SecureKeyStore
import com.estimatefaster.audioforge.core.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 设置读写端口：只暴露设置页需要的两个能力，便于 JVM 测试注入 fake。
 *
 * 生产实现见 [SettingsStore.from]（包装 [SettingsRepository]）。
 */
interface SettingsStore {
    val settings: Flow<AppSettings>

    /** 校验并整体写入；非法时抛异常，持久化值保持不变。 */
    suspend fun update(settings: AppSettings)

    companion object {
        fun from(repository: SettingsRepository): SettingsStore = object : SettingsStore {
            override val settings: Flow<AppSettings> = repository.settings

            override suspend fun update(settings: AppSettings) {
                repository.update(settings)
            }
        }
    }
}

/** API Key 读写端口；生产实现包装 [SecureKeyStore]。 */
interface ApiKeyStore {
    suspend fun get(name: String): String?

    suspend fun set(name: String, value: String)

    suspend fun clear(name: String)

    companion object {
        fun from(keyStore: SecureKeyStore): ApiKeyStore = object : ApiKeyStore {
            override suspend fun get(name: String): String? = keyStore.get(name)

            override suspend fun set(name: String, value: String) {
                keyStore.set(name, value)
            }

            override suspend fun clear(name: String) {
                keyStore.clear(name)
            }
        }
    }
}

/**
 * 全局设置页 ViewModel（P4-09 / 执行计划 §7.4、§12.4 S11）。
 *
 * 数据流：
 * ```text
 * SettingsRepository.settings ─┐
 * 用户编辑（SettingsDraft）  ─┼→ SettingsUiState（派生 errors / canSave）
 * SecureKeyStore.get → 末 4 位 ┘
 * ```
 *
 * Key 的明文只在 [loadKeys]/[setKey] 的局部变量里出现一次，立即折算成
 * [SecureKeyUi]（仅末 4 位）后丢弃；本类不打印任何日志。
 *
 * @param store 设置读写端口
 * @param apiKeys API Key 读写端口
 */
class SettingsViewModel(
    private val store: SettingsStore,
    private val apiKeys: ApiKeyStore,
) : ViewModel() {

    /** 生产环境入口：直接注入 `AppContainer` 的仓库与 KeyStore。 */
    constructor(
        repository: SettingsRepository,
        secureKeyStore: SecureKeyStore,
    ) : this(
        store = SettingsStore.from(repository),
        apiKeys = ApiKeyStore.from(secureKeyStore),
    )

    private val _state = MutableStateFlow(SettingsUiState(loading = true))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private var settingsJob: Job? = null

    init {
        observeSettings()
        viewModelScope.launch { loadKeys() }
    }

    /**
     * 编辑一个文本/数字字段。
     *
     * 保存过程中忽略输入，避免保存成功后 [markSaved] 用持久值覆盖用户刚输入的内容。
     */
    fun onFieldChange(field: SettingsField, value: String) {
        val current = _state.value
        if (current.saving) return
        _state.update { it.withDraft(it.draft.withField(field, value)) }
    }

    /** 编辑一个开关字段；对非布尔字段是 no-op。 */
    fun onFieldChange(field: SettingsField, value: Boolean) {
        val current = _state.value
        if (current.saving) return
        _state.update { it.withDraft(it.draft.withToggle(field, value)) }
    }

    /**
     * 保存：校验 → [SettingsStore.update] → 更新基线。
     *
     * [SettingsUiState.canSave] 为 false（无改动/有错误/保存中）时直接返回，
     * 不会触碰 DataStore；仓库再次校验失败时保留草稿并显示 [SettingsUiState.saveError]。
     */
    fun save() {
        val current = _state.value
        if (!current.canSave) return

        val target = current.draft.toSettings(current.settings)
        _state.update { it.markSaving() }
        viewModelScope.launch {
            try {
                store.update(target)
                _state.update { it.markSaved(target) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.markSaveFailed(e.toMessage()) }
            }
        }
    }

    /** 写入（覆盖）一个 API Key；空白值等价于 [clearKey]。 */
    fun setKey(target: SettingsKeyTarget, value: String) {
        if (value.isBlank()) {
            clearKey(target)
            return
        }
        if (_state.value.keyBusy) return

        _state.update { it.withKeyBusy(true) }
        viewModelScope.launch {
            try {
                apiKeys.set(target.keyName, value.trim())
                loadKeys()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.withKeyError(e.toMessage()) }
            }
        }
    }

    /** 清除一个 API Key；没存过也安全。 */
    fun clearKey(target: SettingsKeyTarget) {
        if (_state.value.keyBusy) return

        _state.update { it.withKeyBusy(true) }
        viewModelScope.launch {
            try {
                apiKeys.clear(target.keyName)
                loadKeys()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.withKeyError(e.toMessage()) }
            }
        }
    }

    /** 数据流错误后重新订阅设置并刷新 Key 状态。 */
    fun retry() {
        _state.update { it.copy(loading = true, loadError = null) }
        observeSettings()
        viewModelScope.launch { loadKeys() }
    }

    private fun observeSettings() {
        settingsJob?.cancel()
        settingsJob = viewModelScope.launch {
            // catch 放在收集端：一次读取失败只结束本轮订阅，retry() 可以重新开始。
            store.settings
                .catch { throwable -> _state.update { it.withLoadError(throwable.toMessage()) } }
                .collect { settings -> _state.update { it.withSettings(settings) } }
        }
    }

    /**
     * 读取两个 Key 并只保留末 4 位。
     *
     * 明文存在于局部变量 `raw`，构造 [SecureKeyUi] 后立即失去引用；
     * 不写状态、不写日志、不拼进错误信息。
     */
    private suspend fun loadKeys() {
        val llm = SecureKeyUi.from(apiKeys.get(SettingsKeyTarget.LLM.keyName))
        val fish = SecureKeyUi.from(apiKeys.get(SettingsKeyTarget.FISH.keyName))
        _state.update { it.withKeys(SecureKeysUi(llm = llm, fish = fish)) }
    }

    private fun Throwable.toMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
}
