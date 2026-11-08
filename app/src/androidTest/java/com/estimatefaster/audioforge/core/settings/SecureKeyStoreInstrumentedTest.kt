package com.estimatefaster.audioforge.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P2-04 的真机/模拟器 smoke：真实 [KeystoreKeyCodec] + 临时 DataStore。
 *
 * 验证 AndroidKeyStore 上的 AES-256-GCM 往返、随机 IV、错误 AAD 解密失败、
 * clear 后读不到、以及 DataStore 文件不含明文。测试用独立的 key alias 并在
 * [tearDown] 删除，不会碰 App 的 `audioforge_api_keys_v1`。
 */
@RunWith(AndroidJUnit4::class)
class SecureKeyStoreInstrumentedTest {

    private lateinit var tempDir: File
    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var codec: KeystoreKeyCodec
    private lateinit var store: SecureKeyStore

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(context.cacheDir, "secure-keystore-${UUID.randomUUID()}").apply { mkdirs() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(tempDir, DATASTORE_FILE) }
        codec = KeystoreKeyCodec(TEST_ALIAS)
        store = SecureKeyStore(dataStore, codec)
    }

    @After
    fun tearDown() = runBlocking {
        scope.cancel()
        tempDir.deleteRecursively()
        deleteTestAlias()
    }

    @Test
    fun set_then_get_roundtrip() = runBlocking {
        store.set(SecureKeyStore.LLM_API_KEY, SECRET)

        assertEquals(SECRET, store.get(SecureKeyStore.LLM_API_KEY))
        assertTrue(store.hasKey(SecureKeyStore.LLM_API_KEY).first())
    }

    @Test
    fun clear_makes_get_return_null() = runBlocking {
        store.set(SecureKeyStore.FISH_API_KEY, SECRET)

        store.clear(SecureKeyStore.FISH_API_KEY)

        assertNull(store.get(SecureKeyStore.FISH_API_KEY))
        assertFalse(store.hasKey(SecureKeyStore.FISH_API_KEY).first())
    }

    @Test
    fun clearAll_removes_both_api_keys() = runBlocking {
        store.set(SecureKeyStore.LLM_API_KEY, SECRET)
        store.set(SecureKeyStore.FISH_API_KEY, SECRET)

        store.clearAll()

        assertNull(store.get(SecureKeyStore.LLM_API_KEY))
        assertNull(store.get(SecureKeyStore.FISH_API_KEY))
    }

    @Test
    fun decrypt_with_wrong_aad_returns_null() {
        val ciphertext = codec.encrypt(SECRET, SecureKeyStore.LLM_API_KEY)

        assertNull(codec.decrypt(ciphertext, SecureKeyStore.FISH_API_KEY))
        assertEquals(SECRET, codec.decrypt(ciphertext, SecureKeyStore.LLM_API_KEY))
    }

    @Test
    fun encrypt_uses_random_iv_so_ciphertexts_differ() {
        val first = codec.encrypt(SECRET, SecureKeyStore.LLM_API_KEY)
        val second = codec.encrypt(SECRET, SecureKeyStore.LLM_API_KEY)

        assertNotEquals(first, second)
        assertEquals(SECRET, codec.decrypt(first, SecureKeyStore.LLM_API_KEY))
        assertEquals(SECRET, codec.decrypt(second, SecureKeyStore.LLM_API_KEY))
    }

    @Test
    fun datastore_file_contains_no_plaintext() = runBlocking {
        store.set(SecureKeyStore.LLM_API_KEY, SECRET)

        val fileText = String(File(tempDir, DATASTORE_FILE).readBytes(), Charsets.UTF_8)

        assertFalse("DataStore 文件里不能出现明文", fileText.contains(SECRET))
    }

    private fun deleteTestAlias() {
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(TEST_ALIAS)) keyStore.deleteEntry(TEST_ALIAS)
        }
    }

    private companion object {
        const val DATASTORE_FILE = "api_keys.preferences_pb"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** 测试专用 alias：不污染 App 的生产 key。 */
        const val TEST_ALIAS = "audioforge_api_keys_instrumented_test"

        /** 假 Key，仅用于验证 UTF-8 往返。 */
        const val SECRET = "sk-设备测试-keystore-0f9e8d7c"
    }
}
