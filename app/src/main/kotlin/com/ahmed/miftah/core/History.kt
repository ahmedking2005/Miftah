package com.ahmed.miftah.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Persistent chat sessions + agent memory notes (Feature: cross-session
 * memory, Hermes-style learning loop made phone-appropriate).
 *
 * Storage follows the house pattern: a pure JSON codec over a [StringStore]
 * (SharedPreferences on device, a map in JVM tests). Sessions hold the
 * conversation messages only — never the system prompt, never keys.
 */

data class ChatSession(
    val id: String,
    val createdMs: Long,
    val updatedMs: Long,
    val title: String,
    val messages: List<ChatMessage>
)

/** JSON codec for sessions and their messages (tool calls included). Pure. */
object SessionCodec {
    fun encodeMessage(m: ChatMessage): JSONObject {
        val o = JSONObject()
            .put("role", m.role)
            .put("content", m.content)
        if (m.toolCalls.isNotEmpty()) {
            val arr = JSONArray()
            m.toolCalls.forEach { tc ->
                arr.put(JSONObject().put("id", tc.id).put("name", tc.name).put("args", tc.argumentsJson))
            }
            o.put("calls", arr)
        }
        m.toolCallId?.let { o.put("callId", it) }
        m.name?.let { o.put("name", it) }
        return o
    }

    fun decodeMessage(o: JSONObject): ChatMessage {
        val calls = ArrayList<ToolCall>()
        o.optJSONArray("calls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                calls.add(ToolCall(c.optString("id"), c.optString("name"), c.optString("args")))
            }
        }
        return ChatMessage(
            role = o.optString("role"),
            content = o.optString("content"),
            toolCalls = calls,
            toolCallId = if (o.has("callId")) o.optString("callId") else null,
            name = if (o.has("name")) o.optString("name") else null
        )
    }

    fun encode(sessions: List<ChatSession>): String {
        val arr = JSONArray()
        sessions.forEach { s ->
            val msgs = JSONArray()
            s.messages.forEach { msgs.put(encodeMessage(it)) }
            arr.put(
                JSONObject()
                    .put("id", s.id).put("created", s.createdMs).put("updated", s.updatedMs)
                    .put("title", s.title).put("messages", msgs)
            )
        }
        return arr.toString()
    }

    fun decode(raw: String?): List<ChatSession> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = try { JSONArray(raw) } catch (e: Exception) { return emptyList() }
        val out = ArrayList<ChatSession>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val msgs = ArrayList<ChatMessage>()
            o.optJSONArray("messages")?.let { ma ->
                for (j in 0 until ma.length()) {
                    ma.optJSONObject(j)?.let { msgs.add(decodeMessage(it)) }
                }
            }
            out.add(
                ChatSession(
                    id = o.optString("id"),
                    createdMs = o.optLong("created"),
                    updatedMs = o.optLong("updated"),
                    title = o.optString("title"),
                    messages = msgs
                )
            )
        }
        return out
    }
}

/** Session title = the first user message, single-lined and truncated. Pure. */
object SessionTitles {
    const val MAX_TITLE_CHARS = 60

    fun titleFor(messages: List<ChatMessage>): String {
        val first = messages.firstOrNull { it.role == "user" }?.content
            ?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (first.isEmpty()) return "Conversation"
        return if (first.length <= MAX_TITLE_CHARS) first else first.take(MAX_TITLE_CHARS - 1) + "…"
    }
}

/** One search match: which session, which message role, and a snippet around the match. */
data class HistoryHit(
    val sessionId: String,
    val sessionTitle: String,
    val sessionUpdatedMs: Long,
    val role: String,
    val excerpt: String
)

/**
 * Full-text search over saved sessions. At this scale (tens of sessions) a
 * case-insensitive contains scan is honest and sufficient; excerpts are a
 * window around the first match. Pure.
 */
object HistorySearch {
    const val EXCERPT_RADIUS = 80

    fun excerptFor(content: String, query: String): String? {
        val idx = content.lowercase().indexOf(query.lowercase())
        if (idx < 0) return null
        val start = maxOf(0, idx - EXCERPT_RADIUS)
        val end = minOf(content.length, idx + query.length + EXCERPT_RADIUS)
        val snippet = content.substring(start, end).replace(Regex("\\s+"), " ").trim()
        return (if (start > 0) "…" else "") + snippet + (if (end < content.length) "…" else "")
    }

    /** Newest sessions first; at most one hit per session (its first matching message). */
    fun search(sessions: List<ChatSession>, query: String, maxResults: Int = 5): List<HistoryHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val hits = ArrayList<HistoryHit>()
        for (session in sessions.sortedByDescending { it.updatedMs }) {
            for (m in session.messages) {
                val excerpt = excerptFor(m.content, q) ?: continue
                hits.add(HistoryHit(session.id, session.title, session.updatedMs, m.role, excerpt))
                break
            }
            if (hits.size >= maxResults) break
        }
        return hits
    }
}

/** Persisted chat sessions (newest activity last in storage; capped). */
class SessionStore(private val store: StringStore) {
    fun all(): List<ChatSession> = SessionCodec.decode(store.get(KEY))

    fun byId(id: String): ChatSession? = all().firstOrNull { it.id == id }

    /** Inserts or replaces a session, then enforces the cap (oldest activity dropped). */
    fun upsert(session: ChatSession) {
        val sessions = all().filterNot { it.id == session.id }.toMutableList()
        sessions.add(session)
        val capped = sessions.sortedBy { it.updatedMs }.takeLast(MAX_SESSIONS)
        store.put(KEY, SessionCodec.encode(capped))
    }

    fun delete(id: String) {
        store.put(KEY, SessionCodec.encode(all().filterNot { it.id == id }))
    }

    fun search(query: String): List<HistoryHit> = HistorySearch.search(all(), query)

    companion object {
        const val KEY = "sessions"
        const val MAX_SESSIONS = 50
    }
}

// ---------------- Agent memory notes ----------------

data class MemoryNote(val id: String, val createdMs: Long, val text: String)

/** JSON codec for memory notes. Pure. */
object MemoryCodec {
    fun encode(notes: List<MemoryNote>): String {
        val arr = JSONArray()
        notes.forEach { n ->
            arr.put(JSONObject().put("id", n.id).put("created", n.createdMs).put("text", n.text))
        }
        return arr.toString()
    }

    fun decode(raw: String?): List<MemoryNote> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = try { JSONArray(raw) } catch (e: Exception) { return emptyList() }
        val out = ArrayList<MemoryNote>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(MemoryNote(o.optString("id"), o.optLong("created"), o.optString("text")))
        }
        return out
    }
}

/**
 * Builds the "what you remember about the user" system-context section.
 * Caps keep it from crowding out the conversation: at most [MAX_NOTES]
 * notes and [MAX_CHARS] characters, most recent first. Pure.
 */
object MemoryContext {
    const val MAX_NOTES = 20
    const val MAX_CHARS = 2000
    const val HEADER = "What you remember about the user (long-term memory, saved with the user's approval):"

    fun section(notes: List<MemoryNote>): String {
        if (notes.isEmpty()) return ""
        val recent = notes.sortedByDescending { it.createdMs }.take(MAX_NOTES)
        val sb = StringBuilder("\n\n").append(HEADER)
        var used = sb.length
        var included = 0
        for (note in recent) {
            val line = "\n- " + note.text.trim()
            if (included > 0 && used + line.length > MAX_CHARS) break
            if (included == 0 && used + line.length > MAX_CHARS) {
                // Always include at least the newest note, truncated to budget.
                sb.append(line.take(maxOf(0, MAX_CHARS - used)))
                included++
                break
            }
            sb.append(line)
            used += line.length
            included++
        }
        return sb.toString()
    }
}

/** The user's long-term memory notes, in save order (oldest first). */
class MemoryStore(private val store: StringStore) {
    fun notes(): List<MemoryNote> = MemoryCodec.decode(store.get(KEY))

    fun add(text: String, nowMs: Long = System.currentTimeMillis()): MemoryNote {
        val note = MemoryNote(UUID.randomUUID().toString(), nowMs, text.trim())
        store.put(KEY, MemoryCodec.encode(notes() + note))
        return note
    }

    fun update(id: String, text: String) {
        val updated = notes().map { if (it.id == id) it.copy(text = text.trim()) else it }
        store.put(KEY, MemoryCodec.encode(updated))
    }

    fun delete(id: String) {
        store.put(KEY, MemoryCodec.encode(notes().filterNot { it.id == id }))
    }

    companion object {
        const val KEY = "memory_notes"
    }
}
