package com.estimatefaster.audioforge.core.network

import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

/**
 * 全 App 共享的 OkHttp 客户端工厂（P6-04，执行计划 §9.1 / §9.4）。
 *
 * 只负责「怎么建客户端」，不持有业务配置：
 * - 连接池固定 `maxIdle=5`、`keepAlive=5min`，LLM 与 TTS 共用同一个池与同一个
 *   [Dispatcher]（`maxRequests=32`、`maxRequestsPerHost=8`），并发上限是全局的，
 *   不会因为多建一个客户端而翻倍；
 * - 连接 / 读 / 写超时可按用途覆盖；单次调用总超时只在显式传入正数时设置
 *   （LLM 不设总超时，TTS 用 150s）；
 * - `retryOnConnectionFailure=true`：连接层瞬断交给 OkHttp 自己换路由；
 * - `followRedirects=false` / `followSslRedirects=false`：绝不跟随跳转，避免
 *   `Authorization` 头被带到另一个主机（Key 泄漏）；需要跳转的网关请直接配置
 *   最终地址；
 * - **不安装任何日志拦截器**，因此不会打印 / 记录 URL 查询串与请求头
 *   （`Authorization`、`model` 等）。排障只依赖调用方记录状态码与错误码。
 *
 * [defaultLlm] / [defaultTts] 是进程内懒加载单例；[create] 返回共享连接池与
 * Dispatcher、但超时独立的新客户端。业务侧默认走单例，只有用户把超时改得与
 * 默认值不同时才需要 [create]（见两个带超时参数的重载）。
 */
object HttpClients {

    /** 连接池空闲连接上限。 */
    const val CONNECTION_POOL_MAX_IDLE_CONNECTIONS: Int = 5

    /** 连接池空闲连接保活时长（分钟）。 */
    const val CONNECTION_POOL_KEEP_ALIVE_MINUTES: Long = 5L

    /** 全局同时在途请求上限。 */
    const val DISPATCHER_MAX_REQUESTS: Int = 32

    /** 单主机同时在途请求上限。 */
    const val DISPATCHER_MAX_REQUESTS_PER_HOST: Int = 8

    /** LLM 建连超时，与 [LlmConfig] 默认值一致。 */
    const val LLM_CONNECT_TIMEOUT_MS: Long = 10_000L

    /** LLM 读超时，与 [LlmConfig] 默认值一致。 */
    const val LLM_READ_TIMEOUT_MS: Long = 120_000L

    /** TTS 建连超时，与 FishTtsConfig 默认值一致。 */
    const val TTS_CONNECT_TIMEOUT_MS: Long = 10_000L

    /** TTS 读超时，与 FishTtsConfig 默认值一致。 */
    const val TTS_READ_TIMEOUT_MS: Long = 120_000L

    /** TTS 单次调用总超时，与 FishTtsConfig 默认值一致。 */
    const val TTS_CALL_TIMEOUT_MS: Long = 150_000L

    /** 写超时；请求体都是小 JSON，60s 足够且与 §9.1 对齐。 */
    const val WRITE_TIMEOUT_MS: Long = 60_000L

    /** 两个默认客户端共用的连接池。 */
    private val connectionPool: ConnectionPool by lazy {
        ConnectionPool(
            CONNECTION_POOL_MAX_IDLE_CONNECTIONS,
            CONNECTION_POOL_KEEP_ALIVE_MINUTES,
            TimeUnit.MINUTES,
        )
    }

    /** 两个默认客户端共用的 Dispatcher，保证并发上限全局生效。 */
    private val dispatcher: Dispatcher by lazy {
        Dispatcher().apply {
            maxRequests = DISPATCHER_MAX_REQUESTS
            maxRequestsPerHost = DISPATCHER_MAX_REQUESTS_PER_HOST
        }
    }

    private val llmClient: OkHttpClient by lazy {
        create(
            connectTimeoutMs = LLM_CONNECT_TIMEOUT_MS,
            readTimeoutMs = LLM_READ_TIMEOUT_MS,
        )
    }

    private val ttsClient: OkHttpClient by lazy {
        create(
            connectTimeoutMs = TTS_CONNECT_TIMEOUT_MS,
            readTimeoutMs = TTS_READ_TIMEOUT_MS,
            callTimeoutMs = TTS_CALL_TIMEOUT_MS,
        )
    }

    /**
     * LLM 默认客户端（懒加载单例）。
     *
     * 读超时较长：章节分析可能持续数十秒，但不超过 [LLM_READ_TIMEOUT_MS]；不设
     * callTimeout，避免与用户自定义的读超时叠加出更短的截止时间。
     */
    fun defaultLlm(): OkHttpClient = llmClient

    /**
     * 按 [LlmConfig] 的超时取 LLM 客户端。
     *
     * 与默认值相同时复用单例；用户改过超时时新建一个共享池 / Dispatcher 的
     * 客户端，保证用户配置生效且并发上限不被放大。
     */
    fun defaultLlm(connectTimeoutMs: Long, readTimeoutMs: Long): OkHttpClient =
        if (
            connectTimeoutMs == LLM_CONNECT_TIMEOUT_MS &&
            readTimeoutMs == LLM_READ_TIMEOUT_MS
        ) {
            llmClient
        } else {
            create(connectTimeoutMs = connectTimeoutMs, readTimeoutMs = readTimeoutMs)
        }

    /** TTS 默认客户端（懒加载单例）：10s 建连 / 120s 读 / 150s 总超时。 */
    fun defaultTts(): OkHttpClient = ttsClient

    /**
     * 按 FishTtsConfig 的超时取 TTS 客户端；与默认值相同时复用单例。
     *
     * 参数顺序与配置字段一致：建连、读、单次调用总超时。
     */
    fun defaultTts(
        connectTimeoutMs: Long,
        readTimeoutMs: Long,
        callTimeoutMs: Long,
    ): OkHttpClient =
        if (
            connectTimeoutMs == TTS_CONNECT_TIMEOUT_MS &&
            readTimeoutMs == TTS_READ_TIMEOUT_MS &&
            callTimeoutMs == TTS_CALL_TIMEOUT_MS
        ) {
            ttsClient
        } else {
            create(
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs,
                callTimeoutMs = callTimeoutMs,
            )
        }

    /**
     * 建一个客户端：连接池 / Dispatcher / 重试 / 跳转策略固定，仅超时可覆盖。
     *
     * @param connectTimeoutMs 建连超时，必须为正数。
     * @param readTimeoutMs 读超时，必须为正数。
     * @param callTimeoutMs 单次调用总超时；`0` 表示不设（OkHttp 默认）。
     * @param writeTimeoutMs 写超时。
     */
    fun create(
        connectTimeoutMs: Long = LLM_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Long = LLM_READ_TIMEOUT_MS,
        callTimeoutMs: Long = 0L,
        writeTimeoutMs: Long = WRITE_TIMEOUT_MS,
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(writeTimeoutMs, TimeUnit.MILLISECONDS)
            .connectionPool(connectionPool)
            .dispatcher(dispatcher)
            .retryOnConnectionFailure(true)
            .followRedirects(false)
            .followSslRedirects(false)

        if (callTimeoutMs > 0L) {
            builder.callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        }
        return builder.build()
    }
}
