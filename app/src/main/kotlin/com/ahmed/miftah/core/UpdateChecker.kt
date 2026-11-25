package com.ahmed.miftah.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Parsed contents of the version.json the build workflow attaches to every release. */
data class ReleaseVersion(val versionCode: Int, val versionName: String, val apkUrl: String)

sealed class UpdateCheckResult {
    data class UpdateAvailable(val release: ReleaseVersion) : UpdateCheckResult()
    object UpToDate : UpdateCheckResult()
    data class Failed(val reason: String) : UpdateCheckResult()
}

/**
 * In-app update check (Ahmed's standing rule for his apps): the release
 * workflow publishes version.json next to the APK, and
 * releases/latest/download/ always resolves to the newest release's copy.
 * Pure parsing/comparison lives here for JVM tests; the network call is a
 * single lightweight GET — no background service, no notifications.
 */
object UpdateChecker {
    const val VERSION_JSON_URL =
        "https://github.com/ahmedking2005/Miftah/releases/latest/download/version.json"
    const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    /** Parses version.json; null when the payload is missing/invalid (treated as a failed check). */
    fun parseVersionJson(text: String): ReleaseVersion? {
        val o = try { JSONObject(text) } catch (e: Exception) { return null }
        val code = o.optInt("versionCode", -1)
        if (code < 0) return null
        val apkUrl = o.optString("apkUrl")
        if (apkUrl.isEmpty()) return null
        return ReleaseVersion(code, o.optString("versionName"), apkUrl)
    }

    fun evaluate(currentVersionCode: Int, release: ReleaseVersion?): UpdateCheckResult = when {
        release == null -> UpdateCheckResult.Failed("unreadable version data")
        release.versionCode > currentVersionCode -> UpdateCheckResult.UpdateAvailable(release)
        else -> UpdateCheckResult.UpToDate
    }

    /** The automatic check runs at most once per day; manual checks ignore this. */
    fun shouldAutoCheck(lastCheckMs: Long, nowMs: Long): Boolean =
        nowMs - lastCheckMs >= CHECK_INTERVAL_MS

    suspend fun check(
        currentVersionCode: Int,
        http: OkHttpClient = defaultHttpClient()
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(VERSION_JSON_URL).get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) UpdateCheckResult.Failed("HTTP ${resp.code}")
                else evaluate(currentVersionCode, parseVersionJson(resp.body?.string() ?: ""))
            }
        } catch (e: Exception) {
            UpdateCheckResult.Failed(e.message ?: "network error")
        }
    }
}
