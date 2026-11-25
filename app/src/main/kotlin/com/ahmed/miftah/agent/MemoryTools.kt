package com.ahmed.miftah.agent

import com.ahmed.miftah.core.MemoryStore
import com.ahmed.miftah.core.SessionStore
import com.ahmed.miftah.core.ToolDef
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun schema(vararg props: Pair<String, String>, required: List<String> = emptyList()): JSONObject {
    val p = JSONObject()
    props.forEach { (name, desc) -> p.put(name, JSONObject().put("type", "string").put("description", desc)) }
    val o = JSONObject().put("type", "object").put("properties", p)
    if (required.isNotEmpty()) o.put("required", JSONArray(required))
    return o
}

val saveMemoryTool = ToolDef(
    name = "save_memory",
    description = "Save ONE durable fact or preference about the user to long-term memory " +
        "(e.g. their name, city, ongoing projects, how they like answers). " +
        "SIDE EFFECT: writes to the user's memory — the exact note text is shown to the user " +
        "for approval first, as a single-item approval. Keep notes short, factual, and worth " +
        "remembering across conversations; never save secrets, keys, or one-off details.",
    parametersJsonSchema = schema(
        "note" to "The exact memory note text to save (one short factual sentence)",
        required = listOf("note")
    ),
    readOnly = false
)

val searchHistoryTool = ToolDef(
    name = "search_history",
    description = "Search the user's past saved conversations (on this device) by keyword. " +
        "Returns the matching session titles with short excerpts, so you can recall what was " +
        "discussed before. Read-only; searches message text only.",
    parametersJsonSchema = schema(
        "query" to "Keyword or phrase to search for in past conversations",
        required = listOf("query")
    ),
    readOnly = true
)

/**
 * Registers the memory tools. [save_memory] is a write tool, so the registry
 * routes it through the batched approval gate as a one-item bundle whose
 * description is the exact note text. Stores are injected so JVM tests can
 * back them with a map store.
 */
fun registerMemoryTools(registry: ToolRegistry, memoryStore: MemoryStore, sessionStore: SessionStore) {
    registry.register(
        saveMemoryTool,
        approvalDescriber = { args -> "save_memory · " + args.optString("note").trim() }
    ) { args ->
        val note = args.optString("note").trim()
        if (note.isEmpty()) return@register "Error: note text is empty — nothing saved."
        memoryStore.add(note)
        "Saved to long-term memory: $note"
    }

    registry.register(searchHistoryTool) { args ->
        val query = args.optString("query").trim()
        if (query.isEmpty()) return@register "Error: empty search query."
        val hits = sessionStore.search(query)
        if (hits.isEmpty()) return@register "No past conversations match '$query'."
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        hits.joinToString("\n\n") { hit ->
            "Session: ${hit.sessionTitle} (${fmt.format(Date(hit.sessionUpdatedMs))})\n${hit.role}: ${hit.excerpt}"
        }
    }
}
