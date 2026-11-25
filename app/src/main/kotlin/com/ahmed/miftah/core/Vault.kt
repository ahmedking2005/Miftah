package com.ahmed.miftah.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class StoredKey(val id: String, val providerId: String, val label: String, val secret: String)

/**
 * Encrypted vault for provider API keys and tool credentials.
 * Secrets are stored ONLY as KeystoreCrypto ciphertext in SharedPreferences.
 * Nothing here ever returns a secret to the model layer: agent tool executors
 * and provider clients read secrets directly from the vault.
 */
class Vault(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("miftah_vault", Context.MODE_PRIVATE)

    // ---------- provider keys ----------
    fun keysFor(providerId: String): List<StoredKey> {
        val raw = prefs.getString("keys_$providerId", null) ?: return emptyList()
        val arr = JSONArray(raw)
        val out = ArrayList<StoredKey>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val secret = KeystoreCrypto.decrypt(o.getString("enc")) ?: continue
            out.add(StoredKey(o.getString("id"), providerId, o.optString("label"), secret))
        }
        return out
    }

    fun addKey(providerId: String, label: String, secret: String): StoredKey {
        val key = StoredKey(UUID.randomUUID().toString(), providerId, label, secret.trim())
        val current = readRaw(providerId)
        current.put(
            JSONObject()
                .put("id", key.id)
                .put("label", key.label)
                .put("enc", KeystoreCrypto.encrypt(key.secret))
        )
        prefs.edit().putString("keys_$providerId", current.toString()).apply()
        return key
    }

    fun removeKey(providerId: String, keyId: String) {
        val current = readRaw(providerId)
        val kept = JSONArray()
        for (i in 0 until current.length()) {
            val o = current.getJSONObject(i)
            if (o.getString("id") != keyId) kept.put(o)
        }
        prefs.edit().putString("keys_$providerId", kept.toString()).apply()
    }

    fun keyById(providerId: String, keyId: String): StoredKey? =
        keysFor(providerId).firstOrNull { it.id == keyId }

    fun hasAnyKey(): Boolean = Providers.all.any { keysFor(it.id).isNotEmpty() }

    private fun readRaw(providerId: String): JSONArray {
        val raw = prefs.getString("keys_$providerId", null) ?: return JSONArray()
        return JSONArray(raw)
    }

    // ---------- generic service credentials (tool credentials, unified key) ----------
    fun putCredential(name: String, value: String) {
        prefs.edit().putString("cred_$name", KeystoreCrypto.encrypt(value)).apply()
    }

    fun getCredential(name: String): String? =
        prefs.getString("cred_$name", null)?.let { KeystoreCrypto.decrypt(it) }

    fun removeCredential(name: String) {
        prefs.edit().remove("cred_$name").apply()
    }
}
