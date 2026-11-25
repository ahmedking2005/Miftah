package com.ahmed.miftah.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A named credential for an arbitrary REST service (Stage 2): the user saves
 * a service once (name + base URL + how its key is sent), and agent tools
 * reference it by id/name only. The API key is a secret: it is stored ONLY
 * as KeystoreCrypto ciphertext (see [ServiceStore.encode]) and is injected
 * into the outgoing request by the tool executor — it never enters model
 * context, tool arguments, or approval descriptions.
 */
data class ServiceCredential(
    val id: String,
    val name: String,
    val baseUrl: String,
    val headerName: String,
    val authPrefix: String,
    val apiKey: String
) {
    /** Full header value sent with requests, e.g. "Bearer abc123" or "abc123". */
    fun authHeaderValue(): String = authPrefix + apiKey

    /** Display-safe tail of the key ("••••c123"); never more than the last 4 chars. */
    fun keyTail(): String =
        if (apiKey.length <= 4) "••••" else "••••" + apiKey.takeLast(4)

    companion object {
        const val DEFAULT_HEADER = "Authorization"
        const val DEFAULT_PREFIX = "Bearer "
    }
}

/**
 * Pure (JVM-testable) storage codec + validation for [ServiceCredential].
 * Encryption is injected so the vault can pass KeystoreCrypto while tests
 * pass identity functions. On-disk shape: a JSON array; the key of each
 * entry lives in the "enc" field as ciphertext, everything else is plain
 * metadata (name/base URL/header are not secrets).
 */
object ServiceStore {

    fun encode(services: List<ServiceCredential>, encrypt: (String) -> String): String {
        val arr = JSONArray()
        for (s in services) {
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("base", s.baseUrl)
                    .put("header", s.headerName)
                    .put("prefix", s.authPrefix)
                    .put("enc", encrypt(s.apiKey))
            )
        }
        return arr.toString()
    }

    fun decode(raw: String?, decrypt: (String) -> String?): List<ServiceCredential> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = try { JSONArray(raw) } catch (e: Exception) { return emptyList() }
        val out = ArrayList<ServiceCredential>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val key = o.optString("enc").takeIf { it.isNotEmpty() }?.let { decrypt(it) } ?: continue
            val id = o.optString("id")
            val name = o.optString("name")
            if (id.isEmpty() || name.isEmpty()) continue
            out.add(
                ServiceCredential(
                    id = id,
                    name = name,
                    baseUrl = normalizeBaseUrl(o.optString("base")),
                    headerName = o.optString("header").ifBlank { ServiceCredential.DEFAULT_HEADER },
                    authPrefix = o.optString("prefix"),
                    apiKey = key
                )
            )
        }
        return out
    }

    /** Finds a service by exact id, or by name (case-insensitive). */
    fun find(services: List<ServiceCredential>, idOrName: String): ServiceCredential? {
        val q = idOrName.trim()
        if (q.isEmpty()) return null
        return services.firstOrNull { it.id == q }
            ?: services.firstOrNull { it.name.equals(q, ignoreCase = true) }
    }

    /** Trims whitespace and trailing slashes so paths can be joined predictably. */
    fun normalizeBaseUrl(raw: String): String = raw.trim().trimEnd('/')

    /**
     * Validates the user-facing fields of a new service. Returns a stable
     * error code ("name", "url", "header", "key") or null when valid; the UI
     * maps codes to localized strings.
     */
    fun validate(name: String, baseUrl: String, headerName: String, apiKey: String): String? {
        if (name.isBlank()) return "name"
        val url = normalizeBaseUrl(baseUrl)
        if (!url.startsWith("https://") && !url.startsWith("http://")) return "url"
        if (url.any { it.isWhitespace() }) return "url"
        if (headerName.isBlank() || headerName.any { it.isWhitespace() || it == ':' }) return "header"
        if (apiKey.isBlank()) return "key"
        return null
    }

    fun newService(
        name: String, baseUrl: String, headerName: String, authPrefix: String, apiKey: String
    ): ServiceCredential = ServiceCredential(
        id = UUID.randomUUID().toString(),
        name = name.trim(),
        baseUrl = normalizeBaseUrl(baseUrl),
        headerName = headerName.trim().ifBlank { ServiceCredential.DEFAULT_HEADER },
        authPrefix = authPrefix,
        apiKey = apiKey.trim()
    )
}
