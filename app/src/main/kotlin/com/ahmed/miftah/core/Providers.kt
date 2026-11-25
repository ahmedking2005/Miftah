package com.ahmed.miftah.core

enum class ProviderKind { OPENAI_COMPAT, GEMINI }

data class ModelDef(
    val id: String,
    val displayName: String,
    /** Free-tier tracking defaults (requests per minute / per day, per key). Providers may change these. */
    val rpm: Int,
    val rpd: Int,
    val toolCapable: Boolean = true,
    /**
     * Documented free-tier tokens-per-day for this model, or null when the
     * provider does not document one in a form the catalog can stand behind.
     * Null means "unknown" — quota displays show "—" and exclude it from sums
     * rather than guessing. (Currently undocumented for every catalog model:
     * providers publish token limits inconsistently per model/plan.)
     */
    val tpd: Int? = null
)

data class ProviderDef(
    val id: String,
    val displayName: String,
    val kind: ProviderKind,
    val baseUrl: String, // OpenAI-compat base (…/v1) or Gemini base (…/v1beta)
    val keyHint: String,
    val keyUrl: String,
    val models: List<ModelDef>,
    /**
     * Scope of the documented free-tier limits. Every provider in this catalog
     * documents its free tier per account / organization / project (Gemini:
     * per project; Groq & Cerebras & Mistral: per organization; OpenRouter &
     * GitHub Models: per account), so the default is PER_ACCOUNT: several keys
     * in the vault do NOT multiply the documented quota. The combined-balance
     * aggregation honours this and only multiplies for a future provider whose
     * documented tier is genuinely per key.
     */
    val quotaScope: QuotaScope = QuotaScope.PER_ACCOUNT
)

/**
 * In-code provider/model catalog for v1. Free tiers change often; this list is
 * curated per release and the UI says so. Limits are the documented free-tier
 * defaults used ONLY for local rate tracking/failover decisions.
 */
object Providers {
    val all: List<ProviderDef> = listOf(
        ProviderDef(
            id = "gemini",
            displayName = "Google Gemini",
            kind = ProviderKind.GEMINI,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            keyHint = "AIza… (Google AI Studio key)",
            keyUrl = "https://aistudio.google.com/apikey",
            models = listOf(
                ModelDef("gemini-2.5-flash", "Gemini 2.5 Flash", rpm = 10, rpd = 250),
                ModelDef("gemini-2.5-flash-lite", "Gemini 2.5 Flash-Lite", rpm = 15, rpd = 1000),
                ModelDef("gemini-2.0-flash", "Gemini 2.0 Flash", rpm = 15, rpd = 1500)
            )
        ),
        ProviderDef(
            id = "groq",
            displayName = "Groq",
            kind = ProviderKind.OPENAI_COMPAT,
            baseUrl = "https://api.groq.com/openai/v1",
            keyHint = "gsk_…",
            keyUrl = "https://console.groq.com/keys",
            models = listOf(
                ModelDef("llama-3.3-70b-versatile", "Llama 3.3 70B Versatile", rpm = 30, rpd = 1000),
                ModelDef("llama-3.1-8b-instant", "Llama 3.1 8B Instant", rpm = 30, rpd = 14400),
                ModelDef("openai/gpt-oss-120b", "GPT-OSS 120B", rpm = 30, rpd = 1000),
                ModelDef("qwen/qwen3-32b", "Qwen3 32B", rpm = 60, rpd = 1000)
            )
        ),
        ProviderDef(
            id = "cerebras",
            displayName = "Cerebras",
            kind = ProviderKind.OPENAI_COMPAT,
            baseUrl = "https://api.cerebras.ai/v1",
            keyHint = "csk-…",
            keyUrl = "https://cloud.cerebras.ai",
            models = listOf(
                ModelDef("llama-3.3-70b", "Llama 3.3 70B", rpm = 30, rpd = 14400),
                ModelDef("qwen-3-235b-a22b-instruct-2507", "Qwen3 235B Instruct", rpm = 30, rpd = 14400),
                ModelDef("gpt-oss-120b", "GPT-OSS 120B", rpm = 30, rpd = 14400)
            )
        ),
        ProviderDef(
            id = "mistral",
            displayName = "Mistral",
            kind = ProviderKind.OPENAI_COMPAT,
            baseUrl = "https://api.mistral.ai/v1",
            keyHint = "Mistral API key",
            keyUrl = "https://console.mistral.ai/api-keys",
            models = listOf(
                ModelDef("mistral-small-latest", "Mistral Small (latest)", rpm = 60, rpd = 10000),
                ModelDef("magistral-small-latest", "Magistral Small (latest)", rpm = 60, rpd = 10000, toolCapable = true),
                ModelDef("open-mistral-nemo", "Mistral Nemo", rpm = 60, rpd = 10000)
            )
        ),
        ProviderDef(
            id = "openrouter",
            displayName = "OpenRouter",
            kind = ProviderKind.OPENAI_COMPAT,
            baseUrl = "https://openrouter.ai/api/v1",
            keyHint = "sk-or-…",
            keyUrl = "https://openrouter.ai/keys",
            models = listOf(
                ModelDef("meta-llama/llama-3.3-70b-instruct:free", "Llama 3.3 70B (free)", rpm = 20, rpd = 50),
                ModelDef("deepseek/deepseek-chat-v3.1:free", "DeepSeek Chat V3.1 (free)", rpm = 20, rpd = 50),
                ModelDef("qwen/qwen3-235b-a22b:free", "Qwen3 235B (free)", rpm = 20, rpd = 50),
                ModelDef("google/gemini-2.0-flash-exp:free", "Gemini 2.0 Flash Exp (free)", rpm = 20, rpd = 50)
            )
        ),
        ProviderDef(
            id = "github",
            displayName = "GitHub Models",
            kind = ProviderKind.OPENAI_COMPAT,
            baseUrl = "https://models.github.ai/inference",
            keyHint = "github_pat_… or ghp_… (GitHub token)",
            keyUrl = "https://github.com/settings/tokens",
            models = listOf(
                ModelDef("openai/gpt-4.1-mini", "GPT-4.1 mini", rpm = 15, rpd = 150),
                ModelDef("openai/gpt-4o-mini", "GPT-4o mini", rpm = 15, rpd = 150),
                ModelDef("meta/llama-3.3-70b-instruct", "Llama 3.3 70B Instruct", rpm = 15, rpd = 150)
            )
        )
    )

    fun byId(id: String): ProviderDef? = all.firstOrNull { it.id == id }
    fun model(providerId: String, modelId: String): ModelDef? =
        byId(providerId)?.models?.firstOrNull { it.id == modelId }
}
