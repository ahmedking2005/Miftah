package com.ahmed.miftah.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ChainEntry(val providerId: String, val modelId: String) {
    fun toJson(): JSONObject = JSONObject().put("p", providerId).put("m", modelId)
    companion object {
        fun fromJson(o: JSONObject) = ChainEntry(o.getString("p"), o.getString("m"))
    }
}

/** Non-secret app settings: fallback chain, server port, unified key (encrypted via vault). */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("miftah_settings", Context.MODE_PRIVATE)

    var chain: List<ChainEntry>
        get() {
            val raw = prefs.getString("chain", null) ?: return emptyList()
            val arr = JSONArray(raw)
            return (0 until arr.length()).map { ChainEntry.fromJson(arr.getJSONObject(it)) }
        }
        set(value) {
            val arr = JSONArray()
            value.forEach { arr.put(it.toJson()) }
            prefs.edit().putString("chain", arr.toString()).apply()
        }

    var serverPort: Int
        get() = prefs.getInt("server_port", 8417)
        set(value) {
            prefs.edit().putInt("server_port", value).apply()
        }

    /** Returns the unified local-server key, generating and storing it (encrypted) on first use. */
    fun unifiedKey(vault: Vault): String {
        vault.getCredential(CRED_UNIFIED)?.let { return it }
        val key = "miftah-" + UUID.randomUUID().toString().replace("-", "")
        vault.putCredential(CRED_UNIFIED, key)
        return key
    }

    fun regenerateUnifiedKey(vault: Vault): String {
        val key = "miftah-" + UUID.randomUUID().toString().replace("-", "")
        vault.putCredential(CRED_UNIFIED, key)
        return key
    }

    companion object {
        const val CRED_UNIFIED = "unified_key"
        const val CRED_GITHUB_TOKEN = "github_token"
    }
}
