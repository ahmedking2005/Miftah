package com.ahmed.miftah.agent

import com.ahmed.miftah.core.ChatMessage
import com.ahmed.miftah.core.LogEntry
import com.ahmed.miftah.core.Router
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
            for (call in msg.toolCalls) {
                onEvent(AgentEvent("tool_call", toolName = call.name, text = call.argumentsJson))
                val args = try { JSONObject(call.argumentsJson) } catch (e: Exception) { JSONObject() }
                val result = registry.execute(call.name, args)
                audit(
                    LogEntry(
                        System.currentTimeMillis(), "tool", resp.providerId,
                        call.name, !result.startsWith("Error:"), result.take(200)
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
