package com.ahmed.miftah.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/** One chat call against one provider with one key. Implemented per provider kind. */
interface ChatEngine {
    suspend fun chat(
        provider: ProviderDef,
        apiKey: String,
        model: ModelDef,
        messages: List<ChatMessage>,
        tools: List<ToolDef>,
        onToken: ((String) -> Unit)?
    ): ChatResponse
}

fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(90, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

private fun httpError(code: Int, body: String): ProviderException {
    val snippet = body.take(300)
    return when {
        code == 429 -> ProviderException("HTTP 429 rate limited: $snippet", 429, rateLimited = true)
        code in 500..599 -> ProviderException("HTTP $code server error: $snippet", code, rateLimited = true)
        else -> ProviderException("HTTP $code: $snippet", code, rateLimited = false, retryable = false)
    }
}

/** OpenAI-compatible chat completions (Groq, Cerebras, Mistral, OpenRouter, GitHub Models). */
class OpenAiCompatClient(private val http: OkHttpClient = defaultHttpClient()) : ChatEngine {

    override suspend fun chat(
        provider: ProviderDef, apiKey: String, model: ModelDef,
        messages: List<ChatMessage>, tools: List<ToolDef>,
        onToken: ((String) -> Unit)?
    ): ChatResponse = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", model.id)
            .put("messages", messagesToOpenAi(messages))
        if (tools.isNotEmpty()) {
            val arr = JSONArray()
            tools.forEach { arr.put(it.toOpenAiJson()) }
            body.put("tools", arr)
        }
        val streaming = onToken != null
        if (streaming) body.put("stream", true)

        val req = Request.Builder()
            .url(provider.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw httpError(resp.code, resp.body?.string() ?: "")
                if (streaming) parseStream(resp.body!!.byteStream(), provider, model, onToken!!)
                else parseResponse(JSONObject(resp.body!!.string()), provider, model)
            }
        } catch (e: ProviderException) {
            throw e
        } catch (e: Exception) {
            throw ProviderException("Network error: ${e.message}", 0, rateLimited = true)
        }
    }

    private fun parseStream(
        stream: java.io.InputStream, provider: ProviderDef, model: ModelDef,
        onToken: (String) -> Unit
    ): ChatResponse {
        val content = StringBuilder()
        val toolCalls = LinkedHashMap<Int, Triple<StringBuilder, StringBuilder, StringBuilder>>() // id, name, args
        var usage: Usage? = null
        val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val l = line!!.trim()
            if (!l.startsWith("data:")) continue
            val data = l.removePrefix("data:").trim()
            if (data == "[DONE]") break
            val json = try { JSONObject(data) } catch (e: Exception) { continue }
            json.optJSONObject("usage")?.let {
                usage = Usage(it.optInt("prompt_tokens"), it.optInt("completion_tokens"))
            }
            val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: continue
            val delta = choice.optJSONObject("delta") ?: continue
            val text = delta.optString("content")
            if (text.isNotEmpty()) {
                content.append(text)
                onToken(text)
            }
            val tcs = delta.optJSONArray("tool_calls") ?: continue
            for (i in 0 until tcs.length()) {
                val tc = tcs.getJSONObject(i)
                val idx = tc.optInt("index", i)
                val triple = toolCalls.getOrPut(idx) {
                    Triple(StringBuilder(), StringBuilder(), StringBuilder())
                }
                triple.first.append(tc.optString("id"))
                val fn = tc.optJSONObject("function")
                if (fn != null) {
                    triple.second.append(fn.optString("name"))
                    triple.third.append(fn.optString("arguments"))
                }
            }
        }
        val calls = toolCalls.entries.sortedBy { it.key }.map {
            ToolCall(it.value.first.toString(), it.value.second.toString(), it.value.third.toString())
        }
        return ChatResponse(
            ChatMessage(role = "assistant", content = content.toString(), toolCalls = calls),
            provider.id, model.id, usage
        )
    }

    private fun parseResponse(json: JSONObject, provider: ProviderDef, model: ModelDef): ChatResponse {
        val choice = json.getJSONArray("choices").getJSONObject(0)
        val msg = choice.getJSONObject("message")
        val calls = ArrayList<ToolCall>()
        msg.optJSONArray("tool_calls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val tc = arr.getJSONObject(i)
                val fn = tc.getJSONObject("function")
                calls.add(ToolCall(tc.optString("id"), fn.optString("name"), fn.optString("arguments")))
            }
        }
        val usage = json.optJSONObject("usage")?.let {
            Usage(it.optInt("prompt_tokens"), it.optInt("completion_tokens"))
        }
        return ChatResponse(
            ChatMessage("assistant", msg.optString("content"), calls),
            provider.id, model.id, usage
        )
    }

    companion object {
        fun messagesToOpenAi(messages: List<ChatMessage>): JSONArray {
            val arr = JSONArray()
            for (m in messages) {
                val o = JSONObject().put("role", m.role)
                when {
                    m.role == "assistant" && m.toolCalls.isNotEmpty() -> {
                        if (m.content.isNotEmpty()) o.put("content", m.content)
                        val tcs = JSONArray()
                        for (tc in m.toolCalls) {
                            tcs.put(
                                JSONObject().put("id", tc.id).put("type", "function")
                                    .put("function", JSONObject().put("name", tc.name).put("arguments", tc.argumentsJson))
                            )
                        }
                        o.put("tool_calls", tcs)
                    }
                    m.role == "tool" -> {
                        o.put("content", m.content)
                        o.put("tool_call_id", m.toolCallId ?: "")
                        if (m.name != null) o.put("name", m.name)
                    }
                    else -> o.put("content", m.content)
                }
                arr.put(o)
            }
            return arr
        }
    }
}

/** Google Gemini native generateContent API. */
class GeminiClient(private val http: OkHttpClient = defaultHttpClient()) : ChatEngine {

    override suspend fun chat(
        provider: ProviderDef, apiKey: String, model: ModelDef,
        messages: List<ChatMessage>, tools: List<ToolDef>,
        onToken: ((String) -> Unit)?
    ): ChatResponse = withContext(Dispatchers.IO) {
        val streaming = onToken != null
        val action = if (streaming) "streamGenerateContent?alt=sse" else "generateContent"
        val url = provider.baseUrl.trimEnd('/') + "/models/${model.id}:$action"
        val body = buildRequest(messages, tools)
        val req = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw httpError(resp.code, resp.body?.string() ?: "")
                if (streaming) parseStream(resp.body!!.byteStream(), provider, model, onToken!!)
                else parseChunk(JSONObject(resp.body!!.string()), provider, model, StringBuilder(), ArrayList())
            }
        } catch (e: ProviderException) {
            throw e
        } catch (e: Exception) {
            throw ProviderException("Network error: ${e.message}", 0, rateLimited = true)
        }
    }

    private fun parseStream(
        stream: java.io.InputStream, provider: ProviderDef, model: ModelDef,
        onToken: (String) -> Unit
    ): ChatResponse {
        val content = StringBuilder()
        val calls = ArrayList<ToolCall>()
        var usage: Usage? = null
        val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val l = line!!.trim()
            if (!l.startsWith("data:")) continue
            val data = l.removePrefix("data:").trim()
            if (data.isEmpty()) continue
            val json = try { JSONObject(data) } catch (e: Exception) { continue }
            val before = content.length
            val r = parseChunk(json, provider, model, content, calls)
            if (content.length > before) onToken(content.substring(before))
            if (r.usage != null) usage = r.usage
        }
        return ChatResponse(ChatMessage("assistant", content.toString(), calls), provider.id, model.id, usage)
    }

    private fun parseChunk(
        json: JSONObject, provider: ProviderDef, model: ModelDef,
        content: StringBuilder, calls: MutableList<ToolCall>
    ): ChatResponse {
        val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
        val parts = candidate?.optJSONObject("content")?.optJSONArray("parts")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.has("text")) content.append(part.getString("text"))
                part.optJSONObject("functionCall")?.let { fc ->
                    calls.add(
                        ToolCall(
                            id = "call_" + calls.size + "_" + System.nanoTime(),
                            name = fc.getString("name"),
                            argumentsJson = fc.optJSONObject("args")?.toString() ?: "{}"
                        )
                    )
                }
            }
        }
        val usage = json.optJSONObject("usageMetadata")?.let {
            Usage(it.optInt("promptTokenCount"), it.optInt("candidatesTokenCount"))
        }
        return ChatResponse(ChatMessage("assistant", content.toString(), calls.toList()), provider.id, model.id, usage)
    }

    companion object {
        fun buildRequest(messages: List<ChatMessage>, tools: List<ToolDef>): JSONObject {
            val body = JSONObject()
            val system = messages.filter { it.role == "system" }.joinToString("\n") { it.content }
            if (system.isNotEmpty()) {
                body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            }
            val contents = JSONArray()
            for (m in messages) {
                if (m.role == "system") continue
                val c = JSONObject()
                when (m.role) {
                    "user" -> {
                        c.put("role", "user")
                        c.put("parts", JSONArray().put(JSONObject().put("text", m.content)))
                    }
                    "assistant" -> {
                        c.put("role", "model")
                        val parts = JSONArray()
                        if (m.content.isNotEmpty()) parts.put(JSONObject().put("text", m.content))
                        for (tc in m.toolCalls) {
                            val args = try { JSONObject(tc.argumentsJson) } catch (e: Exception) { JSONObject() }
                            parts.put(JSONObject().put("functionCall", JSONObject().put("name", tc.name).put("args", args)))
                        }
                        if (parts.length() == 0) parts.put(JSONObject().put("text", ""))
                        c.put("parts", parts)
                    }
                    "tool" -> {
                        c.put("role", "user")
                        val resp = try { JSONObject(m.content) } catch (e: Exception) {
                            JSONObject().put("result", m.content)
                        }
                        val fr = JSONObject().put("name", m.name ?: "").put("response", resp)
                        c.put("parts", JSONArray().put(JSONObject().put("functionResponse", fr)))
                    }
                }
                contents.put(c)
            }
            body.put("contents", contents)
            if (tools.isNotEmpty()) {
                val decls = JSONArray()
                tools.forEach {
                    decls.put(
                        JSONObject().put("name", it.name).put("description", it.description)
                            .put("parameters", it.parametersJsonSchema)
                    )
                }
                body.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decls)))
            }
            return body
        }
    }
}

/** Why a key test failed, in terms the UI can state honestly. */
enum class KeyTestFailure { INVALID_KEY, RATE_LIMITED, SERVER_ERROR, NETWORK, OTHER }

sealed class KeyTestResult {
    data class Success(val modelCount: Int, val sampleModels: List<String>) : KeyTestResult()
    data class Failure(val kind: KeyTestFailure, val httpCode: Int, val detail: String) : KeyTestResult()
}

/** Pure classification/parsing for key tests — JVM-testable, no I/O. */
object KeyTestClassifier {
    /** Maps an HTTP status to a failure kind; null means the key was accepted (2xx). */
    fun classifyHttp(code: Int): KeyTestFailure? = when {
        code in 200..299 -> null
        code == 401 || code == 403 -> KeyTestFailure.INVALID_KEY
        code == 429 -> KeyTestFailure.RATE_LIMITED
        code in 500..599 -> KeyTestFailure.SERVER_ERROR
        else -> KeyTestFailure.OTHER
    }

    /**
     * Extracts model ids from a provider "list models" response body.
     * OpenAI-compatible shape: {"data":[{"id":…}]}. Gemini shape:
     * {"models":[{"name":"models/…"}]} (prefix stripped). Unparseable → empty.
     */
    fun parseModelIds(provider: ProviderDef, body: String): List<String> {
        val root = try { JSONObject(body) } catch (e: Exception) { return emptyList() }
        val out = ArrayList<String>()
        when (provider.kind) {
            ProviderKind.GEMINI -> {
                val arr = root.optJSONArray("models") ?: return emptyList()
                for (i in 0 until arr.length()) {
                    val name = arr.optJSONObject(i)?.optString("name").orEmpty()
                    if (name.isNotEmpty()) out.add(name.removePrefix("models/"))
                }
            }
            ProviderKind.OPENAI_COMPAT -> {
                val arr = root.optJSONArray("data") ?: return emptyList()
                for (i in 0 until arr.length()) {
                    val id = arr.optJSONObject(i)?.optString("id").orEmpty()
                    if (id.isNotEmpty()) out.add(id)
                }
            }
        }
        return out
    }
}

/**
 * Validates one stored key with the cheapest real authenticated call the
 * provider offers: GET {base}/models, using the same base URL and auth header
 * as the chat clients. The key is used in memory only — never logged, never
 * persisted anywhere new, and a failed test never marks or deletes the key.
 */
class KeyTester(private val http: OkHttpClient = defaultHttpClient()) {
    suspend fun test(provider: ProviderDef, apiKey: String): KeyTestResult =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(provider.baseUrl.trimEnd('/') + "/models")
                .get()
            when (provider.kind) {
                ProviderKind.GEMINI -> builder.header("x-goog-api-key", apiKey)
                ProviderKind.OPENAI_COMPAT -> builder.header("Authorization", "Bearer $apiKey")
            }
            try {
                http.newCall(builder.build()).execute().use { resp ->
                    val bodyText = resp.body?.string() ?: ""
                    val failure = KeyTestClassifier.classifyHttp(resp.code)
                    if (failure == null) {
                        val ids = KeyTestClassifier.parseModelIds(provider, bodyText)
                        KeyTestResult.Success(ids.size, ids.take(3))
                    } else {
                        KeyTestResult.Failure(failure, resp.code, bodyText.take(200))
                    }
                }
            } catch (e: Exception) {
                KeyTestResult.Failure(KeyTestFailure.NETWORK, 0, e.message ?: "network error")
            }
        }
}

/** Routes to the right client implementation by provider kind. */
class DispatchingEngine(
    private val openAi: ChatEngine = OpenAiCompatClient(),
    private val gemini: ChatEngine = GeminiClient()
) : ChatEngine {
    override suspend fun chat(
        provider: ProviderDef, apiKey: String, model: ModelDef,
        messages: List<ChatMessage>, tools: List<ToolDef>,
        onToken: ((String) -> Unit)?
    ): ChatResponse = when (provider.kind) {
        ProviderKind.GEMINI -> gemini.chat(provider, apiKey, model, messages, tools, onToken)
        ProviderKind.OPENAI_COMPAT -> openAi.chat(provider, apiKey, model, messages, tools, onToken)
    }
}
