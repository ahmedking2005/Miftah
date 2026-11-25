package com.ahmed.miftah.agent

import com.ahmed.miftah.core.LogEntry
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
 * The approval gate every non-read-only tool must pass before execution.
 * Approval is BATCHED (Ahmed's rule, 2026-11-25): the gate is shown one
 * [ApprovalBundle] per grouped operation — never one prompt per item — and
 * returns a single decision covering exactly that bundle.
 */
interface ApprovalGate {
    suspend fun approveBundle(bundle: ApprovalBundle): ApprovalDecision
}

/** Default gate: rejects every bundle (safe default when no UI gate is wired). */
object DenyApprovalGate : ApprovalGate {
    override suspend fun approveBundle(bundle: ApprovalBundle): ApprovalDecision =
        ApprovalDecision.Rejected
}

/**
 * Tool registry. Invariants:
 * - A write (non-read-only) tool cannot be registered without an [ApprovalGate].
 * - Write tools never execute directly. Their calls are collected into an
 *   [ApprovalBundle]; one gate decision approves/rejects the whole bundle,
 *   optionally with user-excluded items dropped. Bundle-level decisions and
 *   exclusions are written to the audit log via [audit]; per-item execution
 *   results are returned to the caller (the agent loop audits them with the
 *   bundle id attached).
 */
class ToolRegistry(
    private val approvalGate: ApprovalGate? = null,
    private val audit: (LogEntry) -> Unit = {}
) {
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

    /** True when [name] is a registered non-read-only tool (its calls must go through a bundle). */
    fun isWriteTool(name: String): Boolean = tools[name]?.def?.readOnly == false

    /**
     * Execute one call. Read-only tools run immediately. A write tool called
     * on its own becomes a one-item bundle and still needs gate approval —
     * there is no path that executes a write tool without a bundle decision.
     */
    suspend fun execute(name: String, args: JSONObject): String {
        val tool = tools[name] ?: return "Error: unknown tool '$name'"
        if (tool.def.readOnly) return runExecutor(tool, args)
        if (approvalGate == null) return "Error: tool '$name' requires approval but no gate is configured"
        val execution = executeBundle(listOf(ApprovalItem.of(name, args)))
        return execution.results.single().result
    }

    /**
     * Resolve one bundle: ask the gate ONCE, then execute the approved,
     * non-excluded items in bundle order. Writes the bundle-level decision
     * and one entry per excluded item to the audit log. Returns a result for
     * EVERY item (unexecuted items get an explanatory error/excluded text).
     */
    suspend fun executeBundle(items: List<ApprovalItem>): BundleExecution {
        val bundle = ApprovalBundle.create(items)
        val gate = approvalGate
        val decision = if (gate == null) ApprovalDecision.Rejected else gate.approveBundle(bundle)
        val now = System.currentTimeMillis()
        return when (decision) {
            is ApprovalDecision.Approved -> {
                val excluded = decision.excludedIndexes.filter { it in items.indices }.toSet()
                audit(
                    LogEntry(
                        now, "approval", "", bundle.id, true,
                        "approved · ${items.size - excluded.size} of ${items.size} item(s) · " +
                            "${bundle.summary} · digest ${bundle.digest.take(8)}"
                    )
                )
                excluded.sorted().forEach { idx ->
                    val item = items[idx]
                    audit(
                        LogEntry(
                            now, "approval", "", bundle.id, true,
                            "excluded by user · ${item.toolName} · ${item.description} · bundle ${bundle.id}"
                        )
                    )
                }
                val results = items.mapIndexed { idx, item ->
                    if (idx in excluded) {
                        BundleItemResult(item, "Excluded by the user — not executed.", executed = false)
                    } else {
                        val tool = tools[item.toolName]
                        val result = if (tool == null) {
                            "Error: unknown tool '${item.toolName}'"
                        } else {
                            runExecutor(tool, item.args)
                        }
                        BundleItemResult(item, result, executed = true)
                    }
                }
                BundleExecution(bundle, decision, results)
            }
            ApprovalDecision.Rejected, ApprovalDecision.Dismissed -> {
                val word = if (decision == ApprovalDecision.Rejected) "rejected" else "dismissed"
                audit(
                    LogEntry(
                        now, "approval", "", bundle.id, false,
                        "$word · ${items.size} item(s) · ${bundle.summary} · digest ${bundle.digest.take(8)}"
                    )
                )
                val results = items.map { item ->
                    BundleItemResult(
                        item,
                        "Error: user did not approve '${item.toolName}' (grouped approval $word)",
                        executed = false
                    )
                }
                BundleExecution(bundle, decision, results)
            }
        }
    }

    private suspend fun runExecutor(tool: RegisteredTool, args: JSONObject): String =
        try {
            tool.executor.execute(args)
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
}
