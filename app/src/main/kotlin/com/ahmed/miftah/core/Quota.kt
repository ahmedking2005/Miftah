package com.ahmed.miftah.core

/**
 * Combined-quota ("my balance") aggregation.
 *
 * HONESTY CONTRACT: every figure here is an ESTIMATE derived from the free-tier
 * limits documented in the in-app catalog — providers expose no live balance
 * API, so nothing here is a real balance. A limit the catalog does not document
 * is represented as null, displayed as "—", and excluded from every sum instead
 * of being guessed.
 */

/** Whether a provider's documented free-tier limits apply per API key or once per account/project. */
enum class QuotaScope { PER_KEY, PER_ACCOUNT }

/** Raw facts for one provider that has at least one key in the vault. */
data class ProviderQuotaInput(
    val providerId: String,
    val displayName: String,
    val keyCount: Int,
    val usedToday: Int,
    /** Documented daily request limit, already adjusted for key scope; null = not documented. */
    val dailyRequestLimit: Int?,
    /** Documented daily token limit, already adjusted for key scope; null = not documented. */
    val dailyTokenLimit: Long?,
    val scope: QuotaScope
)

data class ProviderQuota(
    val input: ProviderQuotaInput,
    /** Estimated requests remaining today; null when the limit is not documented. Never negative. */
    val remainingToday: Int?
)

data class QuotaTotals(
    /** Requests used today across all listed providers (tracked locally, exact). */
    val usedToday: Int,
    /** Sum of documented daily request limits over providers whose limit is known. */
    val knownDailyRequestLimit: Int,
    /** Sum of per-provider estimated remaining (known-limit providers only). Never negative. */
    val estimatedRemainingToday: Int,
    val providersWithKnownLimit: Int,
    val providersTotal: Int,
    /** Sum of documented daily token limits; null when no provider documents one. */
    val knownDailyTokenLimit: Long?
)

object QuotaAggregator {

    /** Remaining for one provider: limit − used, floored at 0; null when the limit is unknown. */
    fun remaining(usedToday: Int, dailyLimit: Int?): Int? =
        dailyLimit?.let { maxOf(0, it - usedToday) }

    fun aggregate(inputs: List<ProviderQuotaInput>): Pair<List<ProviderQuota>, QuotaTotals> {
        val quotas = inputs.map { ProviderQuota(it, remaining(it.usedToday, it.dailyRequestLimit)) }
        val known = inputs.filter { it.dailyRequestLimit != null }
        val tokenLimits = inputs.mapNotNull { it.dailyTokenLimit }
        val totals = QuotaTotals(
            usedToday = inputs.sumOf { it.usedToday },
            knownDailyRequestLimit = known.sumOf { it.dailyRequestLimit ?: 0 },
            estimatedRemainingToday = quotas.sumOf { it.remainingToday ?: 0 },
            providersWithKnownLimit = known.size,
            providersTotal = inputs.size,
            knownDailyTokenLimit = if (tokenLimits.isEmpty()) null else tokenLimits.sum()
        )
        return quotas to totals
    }
}

/**
 * Builds the aggregation input for one provider from catalog facts.
 *
 * A provider's daily request limit is the sum of its catalog models' documented
 * per-model daily limits (each model has its own documented daily bucket).
 * The key count multiplies that sum ONLY when the provider's documented tier
 * is per key; per-account/project tiers are counted once no matter how many
 * keys are stored. Models with rpd <= 0 count as undocumented; if no model
 * documents a limit the provider's limit is null (shown as "—").
 */
fun ProviderDef.quotaInput(keyCount: Int, usedToday: Int): ProviderQuotaInput {
    val keys = keyCount.coerceAtLeast(1)
    val documentedRequests = models.sumOf { if (it.rpd > 0) it.rpd else 0 }
    val requestLimit = when {
        documentedRequests <= 0 -> null
        quotaScope == QuotaScope.PER_KEY -> documentedRequests * keys
        else -> documentedRequests
    }
    val documentedTokens = models.mapNotNull { it.tpd }
    val tokenLimit = if (documentedTokens.isEmpty()) null else {
        val sum = documentedTokens.sumOf { it.toLong() }
        if (quotaScope == QuotaScope.PER_KEY) sum * keys else sum
    }
    return ProviderQuotaInput(id, displayName, keyCount, usedToday, requestLimit, tokenLimit, quotaScope)
}
