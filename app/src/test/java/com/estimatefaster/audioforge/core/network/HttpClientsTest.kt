package com.estimatefaster.audioforge.core.network

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6-04：共享 OkHttp 客户端工厂的配置断言（执行计划 §9.1 / §9.4）。
 *
 * OkHttp 5 起 `ConnectionPool` 不再公开 `maxIdleConnections` / `keepAliveDuration`
 * getter，因此连接池参数用「构造常量 + 两个默认客户端共用同一个池」来断言；
 * Dispatcher 与超时仍可直接读客户端属性。
 *
 * 所有用例都只做本地对象断言，不发真实网络请求。
 */
class HttpClientsTest {

    @Test
    fun default_clients_are_lazy_singletons() {
        assertSame("defaultLlm 必须复用单例", HttpClients.defaultLlm(), HttpClients.defaultLlm())
        assertSame("defaultTts 必须复用单例", HttpClients.defaultTts(), HttpClients.defaultTts())
    }

    @Test
    fun default_clients_share_connection_pool_and_dispatcher() {
        val llm = HttpClients.defaultLlm()
        val tts = HttpClients.defaultTts()

        assertSame("LLM/TTS 必须共用连接池", llm.connectionPool, tts.connectionPool)
        assertSame("LLM/TTS 必须共用 Dispatcher", llm.dispatcher, tts.dispatcher)
    }

    @Test
    fun connection_pool_keeps_five_idle_connections_for_five_minutes() {
        assertEquals(5, HttpClients.CONNECTION_POOL_MAX_IDLE_CONNECTIONS)
        assertEquals(5L, HttpClients.CONNECTION_POOL_KEEP_ALIVE_MINUTES)

        // 参数只有真正接进 Builder 才有意义：默认客户端必须落在同一个池上。
        assertSame(
            HttpClients.defaultLlm().connectionPool,
            HttpClients.defaultTts().connectionPool,
        )
    }

    @Test
    fun dispatcher_caps_global_and_per_host_parallelism() {
        val dispatcher = HttpClients.defaultLlm().dispatcher

        assertEquals("全局并发上限", 32, dispatcher.maxRequests)
        assertEquals("单主机并发上限", 8, dispatcher.maxRequestsPerHost)
    }

    @Test
    fun llm_timeouts_match_config_defaults() {
        val llm = HttpClients.defaultLlm()

        assertEquals(10_000, llm.connectTimeoutMillis)
        assertEquals(120_000, llm.readTimeoutMillis)
        assertEquals(60_000, llm.writeTimeoutMillis)
        assertEquals("LLM 默认不设 callTimeout", 0, llm.callTimeoutMillis)
    }

    @Test
    fun tts_timeouts_match_config_defaults() {
        val tts = HttpClients.defaultTts()

        assertEquals(10_000, tts.connectTimeoutMillis)
        assertEquals(120_000, tts.readTimeoutMillis)
        assertEquals("TTS 单次调用总超时", 150_000, tts.callTimeoutMillis)
        assertEquals(60_000, tts.writeTimeoutMillis)
    }

    @Test
    fun create_applies_custom_timeouts() {
        val client = HttpClients.create(
            connectTimeoutMs = 1_234,
            readTimeoutMs = 5_678,
            callTimeoutMs = 9_012,
            writeTimeoutMs = 3_456,
        )

        assertEquals(1_234, client.connectTimeoutMillis)
        assertEquals(5_678, client.readTimeoutMillis)
        assertEquals(9_012, client.callTimeoutMillis)
        assertEquals(3_456, client.writeTimeoutMillis)
    }

    @Test
    fun custom_timeouts_reuse_shared_pool_and_dispatcher() {
        val custom = HttpClients.create(connectTimeoutMs = 1_000, readTimeoutMs = 2_000)

        assertNotSame("不同超时应派生新客户端", HttpClients.defaultLlm(), custom)
        assertSame(
            "派生客户端仍复用连接池",
            HttpClients.defaultLlm().connectionPool,
            custom.connectionPool,
        )
        assertSame(
            "派生客户端仍复用 Dispatcher",
            HttpClients.defaultLlm().dispatcher,
            custom.dispatcher,
        )
    }

    @Test
    fun config_driven_overloads_reuse_singletons_for_default_values() {
        assertSame(
            HttpClients.defaultLlm(),
            HttpClients.defaultLlm(
                connectTimeoutMs = HttpClients.LLM_CONNECT_TIMEOUT_MS,
                readTimeoutMs = HttpClients.LLM_READ_TIMEOUT_MS,
            ),
        )
        assertSame(
            HttpClients.defaultTts(),
            HttpClients.defaultTts(
                connectTimeoutMs = HttpClients.TTS_CONNECT_TIMEOUT_MS,
                readTimeoutMs = HttpClients.TTS_READ_TIMEOUT_MS,
                callTimeoutMs = HttpClients.TTS_CALL_TIMEOUT_MS,
            ),
        )
    }

    @Test
    fun config_driven_overloads_apply_custom_timeouts() {
        val llm = HttpClients.defaultLlm(connectTimeoutMs = 1_500, readTimeoutMs = 2_500)
        assertEquals(1_500, llm.connectTimeoutMillis)
        assertEquals(2_500, llm.readTimeoutMillis)

        val tts = HttpClients.defaultTts(
            connectTimeoutMs = 1_500,
            readTimeoutMs = 2_500,
            callTimeoutMs = 3_500,
        )
        assertEquals(1_500, tts.connectTimeoutMillis)
        assertEquals(2_500, tts.readTimeoutMillis)
        assertEquals(3_500, tts.callTimeoutMillis)
    }

    @Test
    fun redirects_are_disabled_and_connection_retry_is_enabled() {
        defaultClients().forEach { client ->
            assertFalse("不得跟随普通跳转，避免 Key 泄漏", client.followRedirects)
            assertFalse("不得跟随 SSL 跳转", client.followSslRedirects)
            assertTrue("连接层瞬断交给 OkHttp 换路由", client.retryOnConnectionFailure)
        }
    }

    @Test
    fun no_logging_or_header_recording_interceptors_are_installed() {
        defaultClients().forEach { client ->
            assertTrue("不得安装应用拦截器：${client.interceptors}", client.interceptors.isEmpty())
            assertTrue(
                "不得安装网络拦截器：${client.networkInterceptors}",
                client.networkInterceptors.isEmpty(),
            )
        }
    }

    private fun defaultClients(): List<OkHttpClient> = listOf(
        HttpClients.defaultLlm(),
        HttpClients.defaultTts(),
        HttpClients.create(),
    )
}
