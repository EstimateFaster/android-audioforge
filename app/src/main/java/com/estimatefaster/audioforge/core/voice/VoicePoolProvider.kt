package com.estimatefaster.audioforge.core.voice

import android.content.res.AssetManager

/**
 * 进程级音色池缓存：`assets/voice_pool.json` 只读取并解析一次。
 *
 * 线程安全（双重检查 + `@Volatile`）；解析失败不写缓存。
 * 单元测试用 [clearForTest] 重置缓存。
 */
object VoicePoolProvider {

    const val ASSET_NAME: String = "voice_pool.json"

    @Volatile
    private var cached: VoicePool? = null

    /** 返回缓存的音色池；首次调用时读取并解析 asset。 */
    fun get(assets: AssetManager): VoicePool {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: load(assets).also { cached = it }
        }
    }

    fun clearForTest() {
        synchronized(this) {
            cached = null
        }
    }

    private fun load(assets: AssetManager): VoicePool {
        val raw = assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return VoicePool.parse(raw)
    }
}
