package com.ahmed.miftah.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class LogEntry(
    val timeMs: Long,
    val kind: String, // chat | server | tool | approval (bundle decisions; modelId holds the bundle id) | keytest (provider + result only, never the secret)
    val providerId: String,
    val modelId: String,
    val success: Boolean,
    val detail: String,
    val tokens: Int = 0
) {
    fun toJson(): JSONObject = JSONObject()
        .put("t", timeMs).put("k", kind).put("p", providerId).put("m", modelId)
        .put("s", success).put("d", detail).put("tok", tokens)

    companion object {
        fun fromJson(o: JSONObject) = LogEntry(
            o.optLong("t"), o.optString("k"), o.optString("p"), o.optString("m"),
            o.optBoolean("s"), o.optString("d"), o.optInt("tok")
        )
    }
}

/** Persisted request + agent-tool audit log (newest last, capped). */
class RequestLog(context: Context) {
    private val prefs = context.getSharedPreferences("miftah_log", Context.MODE_PRIVATE)

    @Synchronized
    fun add(entry: LogEntry) {
        val entries = all().toMutableList()
        entries.add(entry)
        while (entries.size > MAX) entries.removeAt(0)
        val arr = JSONArray()
        entries.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("entries", arr.toString()).apply()
    }

    fun all(): List<LogEntry> {
        val raw = prefs.getString("entries", null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { LogEntry.fromJson(arr.getJSONObject(it)) }
    }

    fun clear() {
        prefs.edit().remove("entries").apply()
    }

    companion object {
        const val MAX = 500
    }
}
