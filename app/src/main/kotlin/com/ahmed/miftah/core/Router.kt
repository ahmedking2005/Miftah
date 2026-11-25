package com.ahmed.miftah.core

/** Supplies API keys per provider; the vault implements this on device, fakes in tests. */
interface KeySource {
    fun keysFor(providerId: String): List<StoredKey>
}

/**
 * The gateway router: walks the user-ordered fallback chain of (provider,
 * model) entries; for each entry tries its provider's keys that are under
 * their rate limits and not cooling down; on 429/5xx/timeout cools the key
 * down and fails over. Every attempt is written to the request log.
 */
class Router(
    private val engine: ChatEngine,
    private val keySource: KeySource,
    private val tracker: RateTracker,
    private val chainProvider: () -> List<ChainEntry>,
    private val log: (LogEntry) -> Unit = {}
) {
    suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<ToolDef> = emptyList(),
        onToken: ((String) -> Unit)? = null,
        logKind: String = "chat",
        preferredToolCapable: Boolean = false
    ): ChatResponse {
        var chain = chainProvider()
        if (preferredToolCapable) {
            chain = chain.sortedByDescending { e ->
                Providers.model(e.providerId, e.modelId)?.toolCapable == true
            }
        }
        if (chain.isEmpty()) throw ProviderException("Fallback chain is empty", retryable = false)
        var lastError: Exception? = null
        for (entry in chain) {
            val provider = Providers.byId(entry.providerId) ?: continue
            val model = Providers.model(entry.providerId, entry.modelId) ?: continue
            val keys = keySource.keysFor(provider.id)
            if (keys.isEmpty()) continue
            for (key in keys) {
                if (!tracker.canUse(key.id, model.rpm, model.rpd)) continue
                tracker.recordUse(key.id)
                try {
                    val resp = engine.chat(provider, key.secret, model, messages, tools, onToken)
                    log(
                        LogEntry(
                            System.currentTimeMillis(), logKind, provider.id, model.id,
                            true, "key …${key.id.takeLast(4)}", resp.usage?.total ?: 0
                        )
                    )
                    return resp
                } catch (e: ProviderException) {
                    lastError = e
                    if (e.rateLimited) tracker.coolDown(key.id)
                    log(
                        LogEntry(
                            System.currentTimeMillis(), logKind, provider.id, model.id,
                            false, (e.message ?: "error").take(200)
                        )
                    )
                    // Non-retryable client errors (bad key/model): still fail over to next key/entry.
                }
            }
        }
        throw lastError ?: ProviderException("No usable provider key (all rate-limited, cooling down, or missing)")
    }
}
