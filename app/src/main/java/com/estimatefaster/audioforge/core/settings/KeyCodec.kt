package com.estimatefaster.audioforge.core.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API Key 的对称加解密抽象（执行计划 §7.5）。
 *
 * 生产实现是 [KeystoreKeyCodec]；单元测试注入假实现（反转 + Base64）即可在不碰
 * Android Keystore 的前提下验证 [SecureKeyStore] 的行为。
 *
 * 约定：
 * - [encrypt] 失败时抛异常：调用方需要知道“这次没存进去”；
 * - [decrypt] 失败返回 `null`：密文损坏、Keystore key 被换掉、AAD 不匹配都不应让 App 崩溃；
 * - [aad] 是绑定密文的附加认证数据，本应用固定传 key 名（`llm_api_key` / `fish_api_key`），
 *   同一段密文被挪到别的 key 名下也解不开；
 * - 实现不得打印明文、密文或 AAD。
 */
interface KeyCodec {

    /** 加密 [plaintext]，并用 [aad] 绑定；失败抛异常。 */
    fun encrypt(plaintext: String, aad: String): String

    /** 解密 [ciphertext]；任何失败（含 [aad] 不匹配）都返回 `null`，不抛异常。 */
    fun decrypt(ciphertext: String, aad: String): String?
}

/**
 * Android Keystore 支持的 AES-256-GCM 实现（执行计划 §7.5）。
 *
 * - key：`AndroidKeyStore` 里的 alias [DEFAULT_KEY_ALIAS]，AES-256、GCM、NoPadding；
 *   key 由 Keystore 生成且不可导出，`setRandomizedEncryptionRequired(true)` 下每次
 *   加密都由 provider 生成随机 12-byte IV。
 * - 落盘格式：`Base64(iv + ciphertext)`，`ciphertext` 含 128-bit GCM tag。
 * - AAD 由调用方传入，这里只做 UTF-8 编码。
 *
 * 该类无状态、不持有 [android.content.Context]、不写任何日志；key 只会生成一次，
 * 并发首次调用用 [KEY_LOCK] 串行化，避免生成两把 key 导致先写入的密文解不开。
 */
class KeystoreKeyCodec(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : KeyCodec {

    override fun encrypt(plaintext: String, aad: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        val iv = checkNotNull(cipher.iv) { "AES-GCM 未返回 IV" }
        check(iv.size == IV_BYTES) { "AES-GCM 的 IV 必须是 $IV_BYTES 字节，实际 ${iv.size}" }
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val payload = ByteArray(iv.size + encrypted.size)
        iv.copyInto(payload, destinationOffset = 0)
        encrypted.copyInto(payload, destinationOffset = iv.size)
        return Base64.getEncoder().encodeToString(payload)
    }

    override fun decrypt(ciphertext: String, aad: String): String? {
        val payload = try {
            Base64.getDecoder().decode(ciphertext)
        } catch (e: IllegalArgumentException) {
            return null
        }
        // IV + 至少一个 GCM tag（空明文也要 16 字节 tag）。
        if (payload.size < IV_BYTES + GCM_TAG_BYTES) return null

        val iv = payload.copyOfRange(0, IV_BYTES)
        val body = payload.copyOfRange(IV_BYTES, payload.size)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (e: Exception) {
            // 不同 OEM 的 JCA provider 抛的运行时异常并不统一（AEADBadTagException、
            // KeyPermanentlyInvalidatedException、ProviderException…），一律按解密失败处理。
            null
        }
    }

    /** 取 alias 对应的 key；不存在则生成一次。 */
    private fun secretKey(): SecretKey = synchronized(KEY_LOCK) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey
        existing ?: generateKey()
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        /** 执行计划 §7.5 规定的 alias；不要随意改，改了旧密文就解不开。 */
        const val DEFAULT_KEY_ALIAS: String = "audioforge_api_keys_v1"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256

        /** GCM 标准 IV 长度；密文格式按 12 字节固定解析。 */
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val GCM_TAG_BYTES = TAG_BITS / 8

        private val KEY_LOCK = Any()
    }
}
