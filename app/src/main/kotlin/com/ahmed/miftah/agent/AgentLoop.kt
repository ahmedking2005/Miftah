package com.ahmed.miftah.agent

import com.ahmed.miftah.core.ChatMessage
import com.ahmed.miftah.core.LogEntry
import com.ahmed.miftah.core.Router
import com.ahmed.miftah.core.ToolCall
import org.json.JSONObject

data class AgentEvent(
    val kind: String, // tool_call | tool_result | final
    val toolName: String = "",
    val text: String = "",
    val providerId: String = "",
    val modelId: String = ""
)

/**
 * The agent loop: the model may call tools; each call is executed by the
 * registry (read-only in v1), written to the audit log, shown to the user via
 * [onEvent], and fed back to the model. Stops at [maxSteps] tool rounds.
 * If the session skill restricts tools (allowed-tools), the offered tool set
 * is filtered accordingly.
 *
 * Write (non-read-only) calls are never executed one-by-one: all write calls
 * in a round are collected into ONE [ApprovalBundle] and resolved with a
 * single user decision (batched approval). Write calls from a later round
 * form a new bundle needing its own approval.
 */
class AgentLoop(
    private val router: Router,
    private val registry: ToolRegistry,
    private val audit: (LogEntry) -> Unit
) {
    suspend fun run(
        history: List<ChatMessage>,
        allowedTools: List<String>? = null,
        maxSteps: Int = 6,
        onEvent: (AgentEvent) -> Unit
    ): ChatMessage {
        val messages = history.toMutableList()
        val defs = registry.defs(allowedTools)
        var steps = 0
        while (true) {
            val resp = router.chat(
                messages = messages,
                tools = defs,
                onToken = null,
                logKind = "chat",
                preferredToolCapable = true
            )
            val msg = resp.message
            messages.add(msg)
            if (msg.toolCalls.isEmpty() || steps >= maxSteps) {
                onEvent(AgentEvent("final", text = msg.content, providerId = resp.providerId, modelId = resp.modelId))
                return msg
            }
            steps++
            // Pass 1: announce every call; run read-only tools immediately and
            // collect write calls — they are resolved as ONE approval bundle.
            val resultsByCallId = HashMap<String, String>()
            val writeCalls = ArrayList<ToolCall>()
            val writeArgs = HashMap<String, JSONObject>()
            for (call in msg.toolCalls) {
                onEvent(AgentEvent("tool_call", toolName = call.name, text = call.argumentsJson))
                val args = try { JSONObject(call.argumentsJson) } catch (e: Exception) { JSONObject() }
                if (registry.isWriteTool(call.name)) {
                    writeCalls.add(call)
                    writeArgs[call.id] = args
                } else {
                    resultsByCallId[call.id] = registry.execute(call.name, args)
                }
            }
            // Pass 2: one bundle, one user decision for all write calls in this round.
            var bundleId: String? = null
            if (writeCalls.isNotEmpty()) {
                val items = writeCalls.map { ApprovalItem.of(it.name, writeArgs[it.id] ?: JSONObject()) }
                val execution = registry.executeBundle(items)
                bundleId = execution.bundle.id
                execution.results.forEachIndexed { i, r -> resultsByCallId[writeCalls[i].id] = r.result }
            }
            // Pass 3: audit + report + feed back, in the model's original call order.
            for (call in msg.toolCalls) {
                val result = resultsByCallId[call.id] ?: "Error: no result"
                val detail = if (bundleId != null && registry.isWriteTool(call.name)) {
                    "bundle $bundleId · " + result.take(180)
                } else {
                    result.take(200)
                }
                audit(
                    LogEntry(
                        System.currentTimeMillis(), "tool", resp.providerId,
                        call.name, !result.startsWith("Error:"), detail
                    )
                )
                onEvent(AgentEvent("tool_result", toolName = call.name, text = result.take(500)))
                messages.add(
                    ChatMessage(role = "tool", content = result, toolCallId = call.id, name = call.name)
                )
            }
        }
    }
}
