package com.ahmed.miftah.agent

import com.ahmed.miftah.core.ToolDef
import org.json.JSONObject

/** Executes one tool call. Returns result text fed back to the model. */
fun interface ToolExecutor {
    suspend fun execute(args: JSONObject): String
}

data class RegisteredTool(
    val def: ToolDef,
    val executor: ToolExecutor
)

/**
 * Gate every non-read-only tool must pass before execution. v1 registers NO
 * write tools, and the registry refuses to register one without an approval
 * gate attached (enforced in [ToolRegistry.register]).
 */
interface ApprovalGate {
    suspend fun approve(toolName: String, args: JSONObject): Boolean
}

/** Default gate: denies everything that needs approval (v1 has no write tools). */
object DenyApprovalGate : ApprovalGate {
    override suspend fun approve(toolName: String, args: JSONObject): Boolean = false
}

/**
 * Tool registry. Invariant for v1: only read-only tools can be registered
 * unless an ApprovalGate is supplied at registration time — a future write
 * tool cannot be added without wiring it through the approval prompt.
 */
class ToolRegistry(private val approvalGate: ApprovalGate? = null) {
    private val tools = LinkedHashMap<String, RegisteredTool>()

    fun register(def: ToolDef, executor: ToolExecutor) {
        require(def.readOnly || approvalGate != null) {
            "Write tool '${def.name}' cannot be registered without an ApprovalGate"
        }
        tools[def.name] = RegisteredTool(def, executor)
    }

    fun defs(allowedTools: List<String>? = null): List<ToolDef> {
        val all = tools.values.map { it.def }
        if (allowedTools.isNullOrEmpty()) return all
        return all.filter { it.name in allowedTools }
    }

    fun get(name: String): RegisteredTool? = tools[name]

    suspend fun execute(name: String, args: JSONObject): String {
        val tool = tools[name] ?: return "Error: unknown tool '$name'"
        if (!tool.def.readOnly) {
            val gate = approvalGate ?: return "Error: tool '$name' requires approval but no gate is configured"
            if (!gate.approve(name, args)) return "Error: user did not approve tool '$name'"
        }
        return try {
            tool.executor.execute(args)
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}
