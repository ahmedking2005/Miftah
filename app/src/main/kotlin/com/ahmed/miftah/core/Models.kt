package com.ahmed.miftah.core

import org.json.JSONArray
import org.json.JSONObject

/** A single chat message in the provider-neutral internal format. */
data class ChatMessage(
    val role: String, // system | user | assistant | tool
    val content: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null, // for role=tool
    val name: String? = null // tool name for role=tool
)

data class ToolCall(val id: String, val name: String, val argumentsJson: String)

data class ToolDef(
    val name: String,
    val description: String,
    val parametersJsonSchema: JSONObject,
    val readOnly: Boolean = true
) {
    fun toOpenAiJson(): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function", JSONObject()
                .put("name", name)
                .put("description", description)
                .put("parameters", parametersJsonSchema)
        )
}

data class Usage(val promptTokens: Int = 0, val completionTokens: Int = 0) {
    val total: Int get() = promptTokens + completionTokens
}

data class ChatResponse(
    val message: ChatMessage,
    val providerId: String,
    val modelId: String,
    val usage: Usage?
)

/** Failure of a single provider attempt; [rateLimited] triggers key cooldown + failover. */
class ProviderException(
    message: String,
    val httpCode: Int = 0,
    val rateLimited: Boolean = false,
    val retryable: Boolean = true
) : Exception(message)

fun List<ChatMessage>.toJsonArray(): JSONArray {
    val arr = JSONArray()
    forEach { arr.put(it.content) }
    return arr
}
