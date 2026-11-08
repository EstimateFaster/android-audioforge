package com.estimatefaster.audioforge.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * LLM / Fish Audio API Key 的安全存取（执行计划 §7.5、§13.1、P2-04）。
 *
 * 数据流：
 * ```text
 * set(name, value) → codec.encrypt(value, name) → DataStore["secure_<name>"] = Base64(iv+密文)
 * get(name)        → DataStore["secure_<name>"] → codec.decrypt(密文, name)
 * ```
 *
 * 约定：
 * - DataStore 里**只有密文**，键名带 `secure_` 前缀，和 P2-03 的普通设置共用一个
 *   DataStore 时不会互相覆盖；[clearAll] 也只删这个前缀的键。
 * - AAD 永远是 key 名（`llm_api_key` / `fish_api_key`），不是带前缀的存储键名。
 * - [get] 遇到任何解密/IO 异常返回 `null`，绝不因 Key 损坏崩溃；[set]/[clear]
 *   的写入失败照常抛异常，让调用方提示用户。
 * - 本类不打印任何日志（明文/密文/Key 都不进 logcat），也不持有 [Context]。
 */
class SecureKeyStore(
    private val dataStore: DataStore<Preferences>,
    private val codec: KeyCodec,
) {

    /**
     * 加密保存 [name] 对应的 [value]。
     *
     * 空白 [value] 视为“清除该 Key”（等价 [clear]），避免 DataStore 里留下一个
     * 能解密成空串的伪 Key；写入失败（Keystore/IO）会抛异常。
     */
    suspend fun set(name: String, value: String) {
        require(name.isNotBlank()) { "密钥名不能为空" }
        if (value.isBlank()) {
            clear(name)
            return
        }
        // 先加密再写：加密失败时 DataStore 保持原值，不会出现“半写入”。
        val ciphertext = codec.encrypt(value, name)
        dataStore.edit { preferences ->
            preferences[storageKey(name)] = ciphertext
        }
    }

    /**
     * 读取并解密 [name]；没有存过、密文损坏、Keystore key 失效、DataStore IO 失败
     * 一律返回 `null`（§7.5：失败不崩溃）。
     */
    suspend fun get(name: String): String? {
        if (name.isBlank()) return null
        return try {
            decryptOrNull(dataStore.data.first()[storageKey(name)], name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** 删除 [name] 的密文；没存过也安全。 */
    suspend fun clear(name: String) {
        if (name.isBlank()) return
        dataStore.edit { preferences ->
            preferences.remove(storageKey(name))
        }
    }

    /** 删除所有安全 Key（只动 `secure_` 前缀，不碰同一 DataStore 里的普通设置）。 */
    suspend fun clearAll() {
        dataStore.edit { preferences ->
            preferences.asMap().keys
                .filter { it.name.startsWith(STORAGE_KEY_PREFIX) }
                .forEach { preferences.remove(it) }
        }
    }

    /**
     * [name] 是否存有**可解密**的密文。
     *
     * 与 [get] 的判定保持一致：密文损坏、Keystore key 换掉、AAD 不匹配都视为没有 Key，
     * 避免 UI 显示“已配置”但所有请求都失败。解密在 `Dispatchers.IO` 上执行，
     * 不会阻塞主线程；DataStore IO 失败时发射 `false` 并结束，不向 UI 抛异常。
     */
    fun hasKey(name: String): Flow<Boolean> {
        if (name.isBlank()) return flowOf(false)
        return dataStore.data
            .map { preferences -> decryptOrNull(preferences[storageKey(name)], name) != null }
            .catch { emit(false) }
            .flowOn(Dispatchers.IO)
    }

    /** 解密失败一律返回 `null`；`decrypt` 契约如此，这里再兜一层假实现的异常。 */
    private fun decryptOrNull(ciphertext: String?, name: String): String? {
        if (ciphertext.isNullOrEmpty()) return null
        return try {
            codec.decrypt(ciphertext, name)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        /** LLM API Key 名，同时作为 AAD（执行计划 §7.5）。 */
        const val LLM_API_KEY: String = "llm_api_key"

        /** Fish Audio API Key 名，同时作为 AAD（执行计划 §7.5）。 */
        const val FISH_API_KEY: String = "fish_api_key"

        private const val STORAGE_KEY_PREFIX = "secure_"

        /**
         * 生产入口：注入调用方创建的 [DataStore]（通常带 `preferencesDataStore` 委托）。
         *
         * [context] 只为保证调用方传的是应用级 Context；当前实现无状态、不保留它，
         * 避免 Activity Context 泄漏。Keystore 本身不需要 Context。
         */
        @Suppress("UNUSED_PARAMETER")
        fun create(context: Context, dataStore: DataStore<Preferences>): SecureKeyStore =
            SecureKeyStore(dataStore, KeystoreKeyCodec())

        private fun storageKey(name: String): Preferences.Key<String> =
            stringPreferencesKey(STORAGE_KEY_PREFIX + name)
    }
}
