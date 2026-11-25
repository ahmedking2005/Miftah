package com.ahmed.miftah.core

/** Minimal string KV abstraction so the tracker logic is JVM-testable. */
interface StringStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

class MapStringStore : StringStore {
    val map = HashMap<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String) {
        map[key] = value
    }
}

class PrefsStringStore(private val prefs: android.content.SharedPreferences) : StringStore {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}

/**
 * Per-key rate-limit tracking against a model's free-tier RPM/RPD defaults.
 * State per key: timestamps of uses inside the current minute window, a day
 * counter keyed by UTC day, and a cooldown-until timestamp set on 429/5xx.
 * All logic is clock-injected for unit tests.
 */
class RateTracker(
    private val store: StringStore,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private fun minuteKey(keyId: String) = "rt_min_$keyId"
    private fun dayKey(keyId: String) = "rt_day_$keyId"
    private fun coolKey(keyId: String) = "rt_cool_$keyId"

    private fun minuteUses(keyId: String, now: Long): MutableList<Long> {
        val raw = store.get(minuteKey(keyId)) ?: return ArrayList()
        return raw.split(',').filter { it.isNotBlank() }
            .mapNotNull { it.toLongOrNull() }
            .filter { now - it < 60_000L }
            .toMutableList()
    }

    private fun dayCount(keyId: String, now: Long): Int {
        val raw = store.get(dayKey(keyId)) ?: return 0
        val parts = raw.split(':')
        if (parts.size != 2) return 0
        val day = now / 86_400_000L
        return if (parts[0].toLongOrNull() == day) parts[1].toIntOrNull() ?: 0 else 0
    }

    fun cooldownUntil(keyId: String): Long = store.get(coolKey(keyId))?.toLongOrNull() ?: 0L

    fun isCoolingDown(keyId: String): Boolean = cooldownUntil(keyId) > nowMs()

    /** True when the key may be used now under the model's rpm/rpd limits. */
    fun canUse(keyId: String, rpm: Int, rpd: Int): Boolean {
        val now = nowMs()
        if (cooldownUntil(keyId) > now) return false
        if (rpm > 0 && minuteUses(keyId, now).size >= rpm) return false
        if (rpd > 0 && dayCount(keyId, now) >= rpd) return false
        return true
    }

    fun recordUse(keyId: String) {
        val now = nowMs()
        val uses = minuteUses(keyId, now)
        uses.add(now)
        store.put(minuteKey(keyId), uses.joinToString(","))
        val day = now / 86_400_000L
        store.put(dayKey(keyId), "$day:${dayCount(keyId, now) + 1}")
    }

    fun coolDown(keyId: String, seconds: Long = 60) {
        store.put(coolKey(keyId), (nowMs() + seconds * 1000L).toString())
    }

    fun usesToday(keyId: String): Int = dayCount(keyId, nowMs())
}
