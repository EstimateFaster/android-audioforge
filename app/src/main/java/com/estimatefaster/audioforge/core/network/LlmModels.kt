package com.estimatefaster.audioforge.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * LLM 连接参数（执行计划 §9.2）。
 *
 * 默认值与设置页的初值一致：连接 10s / 读 120s / 最多重试 3 次。
 * [maxRetries] 指**首次请求之后**的重试次数，因此最多发出 `1 + maxRetries` 个请求。
 */
data class LlmConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val connectTimeoutMs: Int = 10_000,
    val readTimeoutMs: Int = 120_000,
    val maxRetries: Int = 3,
)

/**
 * P2-07：OpenAI 兼容 Chat Completions 的线上 DTO（执行计划 §9.2）。
 *
 * 只建模本任务需要的字段，其余字段由 `Json { ignoreUnknownKeys = true }` 忽略。
 * [ChatCompletionRequest.responseFormat] 默认 `null`，配合 `encodeDefaults = false`
 * 序列化时整字段省略，因此「带 / 不带 response_format」两种请求复用同一个模型。
 */
@Serializable
internal data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double,
    @SerialName("response_format") val responseFormat: ResponseFormat? = null,
)

/** 请求消息。P2-07 只发一条 `role=user` 的 prompt。 */
@Serializable
internal data class ChatMessage(
    val role: String,
    val content: String,
)

/** `{"type":"json_object"}`；DashScope 兼容模式与 OpenAI 同名。 */
@Serializable
internal data class ResponseFormat(
    val type: String = JSON_OBJECT,
) {
    companion object {
        const val JSON_OBJECT: String = "json_object"
    }
}

/** 只关心 `choices[0]`；`error` / `usage` 等字段一律忽略。 */
@Serializable
internal data class ChatCompletionResponse(
    val choices: List<ChatChoice> = emptyList(),
)

@Serializable
internal data class ChatChoice(
    val message: ChatResponseMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

/** 响应里的 content 可能为 `null`（工具调用 / 空回复），由 LlmClient 统一判成 BadJson。 */
@Serializable
internal data class ChatResponseMessage(
    val role: String? = null,
    val content: String? = null,
)
