package com.ahmed.miftah.server

import com.ahmed.miftah.core.ChatMessage
import com.ahmed.miftah.core.Providers
import com.ahmed.miftah.core.Router
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * OpenAI-compatible local server bound to 127.0.0.1 only.
 *  POST /v1/chat/completions — routes through the gateway Router (non-streaming;
 *      a "stream":true request is answered with a single-chunk SSE stream).
 *  GET  /v1/models — the fallback-chain models.
 * Every request must carry the app-generated unified key as a Bearer token.
 */
class LocalServer(
    port: Int,
    private val router: Router,
    private val unifiedKey: () -> String,
    private val chainModels: () -> List<Pair<String, String>>
) : NanoHTTPD("127.0.0.1", port) {

    override fun serve(session: IHTTPSession): Response {
        val auth = session.headers["authorization"] ?: ""
        if (auth != "Bearer ${unifiedKey()}") {
            return json(401, JSONObject().put("error", JSONObject().put("message", "Invalid API key")))
        }
        return try {
            when {
                session.uri == "/v1/models" && session.method == Method.GET -> handleModels()
                session.uri == "/v1/chat/completions" && session.method == Method.POST -> handleChat(session)
                else -> json(404, JSONObject().put("error", JSONObject().put("message", "Not found: ${session.uri}")))
            }
        } catch (e: Exception) {
            json(500, JSONObject().put("error", JSONObject().put("message", e.message ?: "Internal error")))
        }
    }

    private fun handleModels(): Response {
        val data = JSONArray()
        chainModels().forEach { (_, modelId) ->
            data.put(JSONObject().put("id", modelId).put("object", "model").put("owned_by", "miftah"))
        }
        return json(200, JSONObject().put("object", "list").put("data", data))
    }

    private fun handleChat(session: IHTTPSession): Response {
        val len = session.headers["content-length"]?.toIntOrNull() ?: 0
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = session.inputStream.read(buf, read, len - read)
            if (n < 0) break
            read += n
        }
        val body = JSONObject(String(buf, 0, read, Charsets.UTF_8))
        val messages = parseMessages(body.getJSONArray("messages"))
        val stream = body.optBoolean("stream", false)
        val resp = runBlocking {
            router.chat(messages = messages, tools = emptyList(), onToken = null, logKind = "server")
        }
        val out = JSONObject()
            .put("id", "chatcmpl-miftah")
            .put("object", "chat.completion")
            .put("created", System.currentTimeMillis() / 1000)
            .put("model", resp.modelId)
        val msg = JSONObject().put("role", "assistant").put("content", resp.message.content)
        out.put("choices", JSONArray().put(JSONObject().put("index", 0).put("message", msg).put("finish_reason", "stop")))
        resp.usage?.let {
            out.put(
                "usage", JSONObject()
                    .put("prompt_tokens", it.promptTokens)
                    .put("completion_tokens", it.completionTokens)
                    .put("total_tokens", it.total)
            )
        }
        if (!stream) return json(200, out)
        // Minimal SSE: one content chunk + finish + [DONE].
        val chunk = JSONObject()
            .put("id", "chatcmpl-miftah").put("object", "chat.completion.chunk")
            .put("created", System.currentTimeMillis() / 1000).put("model", resp.modelId)
            .put(
                "choices", JSONArray().put(
                    JSONObject().put("index", 0)
                        .put("delta", JSONObject().put("role", "assistant").put("content", resp.message.content))
                        .put("finish_reason", JSONObject.NULL)
                )
            )
        val finish = JSONObject()
            .put("id", "chatcmpl-miftah").put("object", "chat.completion.chunk")
            .put("created", System.currentTimeMillis() / 1000).put("model", resp.modelId)
            .put(
                "choices", JSONArray().put(
                    JSONObject().put("index", 0).put("delta", JSONObject()).put("finish_reason", "stop")
                )
            )
        val sse = "data: $chunk\n\ndata: $finish\n\ndata: [DONE]\n\n"
        return newChunkedResponse(Response.Status.OK, "text/event-stream", ByteArrayInputStream(sse.toByteArray(Charsets.UTF_8)))
    }

    private fun parseMessages(arr: JSONArray): List<ChatMessage> {
        val out = ArrayList<ChatMessage>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val role = o.optString("role", "user")
            val content = when (val c = o.opt("content")) {
                is String -> c
                is JSONArray -> {
                    val sb = StringBuilder()
                    for (j in 0 until c.length()) {
                        val part = c.getJSONObject(j)
                        if (part.optString("type") == "text") sb.append(part.optString("text"))
                    }
                    sb.toString()
                }
                else -> ""
            }
            out.add(ChatMessage(role, content))
        }
        return out
    }

    private fun json(code: Int, body: JSONObject): Response =
        newFixedLengthResponse(
            Response.Status.lookup(code) ?: Response.Status.INTERNAL_ERROR,
            "application/json", body.toString()
        )

    companion object {
        fun chainModelPairs(chain: List<com.ahmed.miftah.core.ChainEntry>): List<Pair<String, String>> =
            chain.map { it.providerId to it.modelId }.distinct()

        fun knownProviders(): List<String> = Providers.all.map { it.id }
    }
}
