package com.estimatefaster.audioforge.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P2-04：`SecureKeyStore` 的纯 JVM 测试。
 *
 * 用临时文件起一个真正的 Preferences DataStore，再注入假的 [KeyCodec]
 * （反转 + Base64，并把 AAD 写进密文），因此不需要 Android Keystore / 模拟器
 * 就能验证：读写/清除、AAD 隔离、密文损坏返回 null、DataStore 不落明文、hasKey Flow。
 */
class SecureKeyStoreTest {

    private lateinit var tempDir: File
    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var codec: RecordingKeyCodec
    private lateinit var store: SecureKeyStore

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("secure-keystore-").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(tempDir, DATASTORE_FILE) }
        codec = RecordingKeyCodec()
        store = SecureKeyStore(dataStore, codec)
    }

    @After
    fun tearDown() {
        scope.cancel()
        tempDir.deleteRecursively()
    }

    @Test
    fun set_then_get_roundtrip() = runBlocking {
        store.set(SecureKeyStore.LLM_API_KEY, LLM_SECRET)

        assertEquals(LLM_SECRET, store.get(SecureKeyStore.LLM_API_KEY))
        // AAD 必须用 key 名（§7.5），不是带 secure_ 前缀的存储键名。
        assertEquals(listOf(SecureKeyStore.LLM_API_KEY), codec.encryptAads)
        assertEquals(listOf(SecureKeyStore.LLM_API_KEY), codec.decryptAads)
    }

    @Test
    fun llm_and_fish_keys_use_their_own_aad_and_stay_isolated() = runBlocking {
        store.set(SecureKeyStore.LLM_API_KEY, LLM_SECRET)
        store.set(SecureKeyStore.FISH_API_KEY, FISH_SECRET)

        assertEquals(LLM_SECRET, store.get(SecureKeyStore.LLM_API_KEY))
        assertEquals(FISH_SECRET, store.get(SecureKeyStore.FISH_API_KEY))
        assertEquals(listOf(SecureKeyStore.LLM_API_KEY, SecureKeyStore.FISH_API_KEY), codec.encryptAads)

        // 同一段密文换 AAD 就解不开：llm 的密文不能当 fish 的 Key 用。
        val llmCiphertext = codec.encrypt(LLM_SECRET, SecureKeyStore.LLM_API_KEY)
        assertNull(codec.decrypt(llmCiphertext, SecureKeyStore.FISH_API_KEY))
        assertEquals(LLM_SECRET, codec.decrypt(llmCiphertext, SecureKeyStore.LLM_API_KEY))
    }

    @Test
    fun get_missing_key_returns_null() = runBlocking {
        assertNull(store.get(SecureKeyStore.LLM_API_KEY))
        assertNull(store.get("unknown_key"))
    }

    @Test
    fun get_returns_null_for_corrupted_ciphertext() = runBlocking {
        // 按存储布局直接写入无法解密的密文，模拟落盘损坏 / 旧 Keystore key 遗留的密文。
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey(STORAGE_KEY_LLM)] = "not a valid ciphertext"
        }

        assertNull(store.get(SecureKeyStore.LLM_API_KEY))
        // 解不开的密文不算“有可用 Key”，hasKey 与 get 的判定保持一致。
        assertFalse(store.hasKey(SecureKeyStore.LLM_API_KEY).first())
    }

    @Test
    fun get_returns_null_when_codec_throws() = runBlocking {
        val exploding = object : KeyCodec {
            override fun encrypt(plaintext: String, aad: String): String = "ciphertext"

            override fun decrypt(ciphertext: String, aad: String): String? =
                throw IllegalStateException("keystore 不可用")
        }
        val failingStore = SecureKeyStore(dataStore, exploding)

        failingStore.set(SecureKeyStore.LLM_API_KEY, LLM_SECRET)

        assertNull(failingStore.get(SecureKeyStore.LLM_API_KEY))
    }

    @Test
    fun get_returns_null_when_datastore_file_is_corrupt() = runBlocking {
        val corruptFile = File(tempDir, "corrupt.preferences_pb")
        corruptFile.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03))

        val corruptStore = SecureKeyStore(
            PreferenceDataStoreFactory.create(scope = scope) { corruptFile },
            RecordingKeyCodec(),
        )

        assertNull(corruptStore.get(SecureKeyStore.LLM_API_KEY))
        assertFalse(corruptStore.hasKey(SecureKeyStore.LLM_API_KEY).first())
    }

    @Test
    fun datastore_stores_only_ciphertext() = runBlocking {
        store.set(SecureKeyStore.LLM_API_KEY, LLM_SECRET)
        store.set(SecureKeyStore.FISH_API_KEY, FISH_SECRET)

        val storedLlm = dataStore.data.first()[stringPreferencesKey(STORAGE_KEY_LLM)]
        assertNotNull(storedLlm)
        assertNotEquals(LLM_SECRET, storedLlm)
        assertFalse(storedLlm.orEmpty().contains(LLM_SECRET))

        // 整个 DataStore 文件（protobuf）里也不能出现明文。
        val fileBytes = File(tempDir, DATASTORE_FILE).readBytes()
        val fileText = String(fileBytes, Charsets.UTF_8)
        assertFalse("DataStore 文件里不能出现 llm 明文", fileText.contains(LLM_SECRET))
        assertFalse("DataStore 文件里不能出现 fish 明文", fileText.contains(FISH_SECRET))
    }

    @Test
    fun clear_removes_only_the_named_key() = runBlocking {
        store.set(SecureKeyStore.LLM_API_KEY, LLM_SECRET)
        store.set(SecureKeyStore.FISH_API_KEY, FISH_SECRET)

        store.clear(SecureKeyStore.LLM_API_KEY)

        assertNull(store.get(SecureKeyStore.LLM_API_KEY))
        assertFalse(store.hasKey(SecureKeyStore.LLM_API_KEY).first())
        assertEquals(FISH_SECRET, store.get(SecureKeyStore.FISH_API_KEY))
        assertTrue(store.hasKey(SecureKeyStore.FISH_API_KEY).first())
    }

    @Test
    fun clear_unknown_key_is_safe() = runBlocking {
        store.clear("never_saved")

        assertNull(store.get("never_saved"))
    }

    @Test
    fun clearAll_removes_secure_keys_but_keeps_other_preferences() = runBlocking {
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("theme_mode")] = "dark"
        }
        store.set(SecureKeyStore.LLM_API_KEY, LLM_SECRET)
        store.set(SecureKeyStore.FISH_API_KEY, FISH_SECRET)

        store.clearAll()

        assertNull(store.get(SecureKeyStore.LLM_API_KEY))
        assertNull(store.get(SecureKeyStore.FISH_API_KEY))
        assertFalse(store.hasKey(SecureKeyStore.LLM_API_KEY).first())
        assertEquals(
            "同一 DataStore 的普通设置不能被 clearAll 清掉",
            "dark",
            dataStore.data.first()[stringPreferencesKey("theme_mode")],
        )
    }

    @Test
    fun hasKey_flow_tracks_set_and_clear() = runBlocking {
        assertFalse(store.hasKey(SecureKeyStore.LLM_API_KEY).first())
        assertFalse(store.hasKey("").first())

        store.set(SecureKeyStore.LLM_API_KEY, LLM_SECRET)
        assertTrue(store.hasKey(SecureKeyStore.LLM_API_KEY).first())

        store.clear(SecureKeyStore.LLM_API_KEY)
        assertFalse(store.hasKey(SecureKeyStore.LLM_API_KEY).first())
    }

    @Test
    fun set_blank_value_clears_the_key() = runBlocking {
        store.set(SecureKeyStore.LLM_API_KEY, LLM_SECRET)

        store.set(SecureKeyStore.LLM_API_KEY, "   ")

        assertNull(store.get(SecureKeyStore.LLM_API_KEY))
        assertFalse(store.hasKey(SecureKeyStore.LLM_API_KEY).first())
    }

    @Test
    fun production_constants_match_plan() {
        assertEquals("llm_api_key", SecureKeyStore.LLM_API_KEY)
        assertEquals("fish_api_key", SecureKeyStore.FISH_API_KEY)
        assertEquals("audioforge_api_keys_v1", KeystoreKeyCodec.DEFAULT_KEY_ALIAS)
    }

    /**
     * 可记录的假 codec：密文 = `Base64("<aad>\0<明文反转>")`。
     *
     * - 解密时校验 AAD，模拟 GCM 的 AAD 认证；
     * - 记录每次调用的 AAD，验证 [SecureKeyStore] 传入的是 key 名；
     * - 输出里不含明文（明文被反转），可用来断言 DataStore 不落明文。
     */
    private class RecordingKeyCodec : KeyCodec {

        val encryptAads = mutableListOf<String>()
        val decryptAads = mutableListOf<String>()

        override fun encrypt(plaintext: String, aad: String): String {
            encryptAads += aad
            val body = "$aad\u0000${plaintext.reversed()}"
            return Base64.getEncoder().encodeToString(body.toByteArray(Charsets.UTF_8))
        }

        override fun decrypt(ciphertext: String, aad: String): String? {
            decryptAads += aad
            val decoded = try {
                String(Base64.getDecoder().decode(ciphertext), Charsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                return null
            }
            val separator = decoded.indexOf('\u0000')
            if (separator < 0) return null
            if (decoded.substring(0, separator) != aad) return null
            return decoded.substring(separator + 1).reversed()
        }
    }

    private companion object {
        const val DATASTORE_FILE = "api_keys.preferences_pb"

        /** 不真实存在、只在本测试里用的假 Key；故意用中文验证 UTF-8 往返。 */
        const val LLM_SECRET = "sk-llm-测试密钥-9f8e7d6c"
        const val FISH_SECRET = "fish-测试密钥-0123456789ab"

        /** 存储键名 = `secure_` + key 名，测试直接按布局写坏数据。 */
        const val STORAGE_KEY_LLM = "secure_llm_api_key"
    }
}
