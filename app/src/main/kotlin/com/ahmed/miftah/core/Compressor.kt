package com.ahmed.miftah.core

/**
 * Context compression (Feature 3): long conversations are summarized before
 * they are sent to a provider, because free-tier models have small context
 * windows. Compression affects ONLY the outgoing context — the visible chat
 * history is never rewritten.
 *
 * A "turn" is a user message plus everything that follows it (assistant
 * replies, tool calls, tool results) up to the next user message, so tool
 * sequences are never split. All but the last [KEEP_TURNS] turns are folded
 * into one summary system message produced by a single model call.
 */

/** Token estimation + turn splitting. Pure. */
object CompressionMath {
    /** Rough estimate: ~4 characters per token (system text + message bodies + tool args). */
    fun estimateTokens(messages: List<ChatMessage>): Int {
        var chars = 0
        for (m in messages) {
            chars += m.content.length
            for (tc in m.toolCalls) chars += tc.name.length + tc.argumentsJson.length
        }
        return chars / 4
    }

    /** Groups messages into turns; a leading non-user run forms its own first turn. */
    fun splitTurns(messages: List<ChatMessage>): List<List<ChatMessage>> {
        val turns = ArrayList<MutableList<ChatMessage>>()
        for (m in messages) {
            if (m.role == "user" || turns.isEmpty()) {
                turns.add(arrayListOf(m))
            } else {
                turns.last().add(m)
            }
        }
        return turns
    }

    /** (older turns to fold, kept turns) keeping the last [keep] turns intact. */
    fun splitKeepingLast(
        messages: List<ChatMessage>, keep: Int = ContextCompressor.KEEP_TURNS
    ): Pair<List<ChatMessage>, List<ChatMessage>> {
        val turns = splitTurns(messages)
        if (turns.size <= keep) return emptyList<ChatMessage>() to messages
        val older = turns.dropLast(keep).flatten()
        val kept = turns.takeLast(keep).flatten()
        return older to kept
    }

    /** Drops oldest turns until the estimate fits [capTokens] (always keeps at least one turn). */
    fun trimToFit(messages: List<ChatMessage>, capTokens: Int): Pair<List<ChatMessage>, Int> {
        val turns = splitTurns(messages).toMutableList()
        var dropped = 0
        while (turns.size > 1 && estimateTokens(turns.flatten()) > capTokens) {
            turns.removeAt(0)
            dropped++
        }
        return turns.flatten() to dropped
    }
}

/** What the compressor decided for one outgoing context. */
sealed class CompressionOutcome {
    /** Under the threshold — sent as-is. */
    object NotNeeded : CompressionOutcome()

    /** Older turns were folded into a summary. */
    object Compressed : CompressionOutcome()

    /** Summary failed, but the full context still fits the hard cap — sent whole. */
    object SentUncompressed : CompressionOutcome()

    /** Summary failed and the context exceeded the hard cap — oldest turns were dropped. */
    data class Trimmed(val droppedTurns: Int) : CompressionOutcome()
}

data class PreparedContext(
    /** Messages to actually send (summary system message first, when present). */
    val messages: List<ChatMessage>,
    val outcome: CompressionOutcome,
    /** The active summary text after this call (null when none). */
    val summary: String?,
    /** How many of the input conversation messages were folded into [summary] by this call. */
    val consumedCount: Int
)

/**
 * Prepares an outgoing conversation. The [summarize] callback performs the
 * single model call; it receives the prior summary (as a system message,
 * when one exists) followed by the turns to fold, and returns summary text.
 * Any exception from it triggers the honest fallback path.
 */
class ContextCompressor(
    private val summarize: suspend (List<ChatMessage>) -> String
) {
    suspend fun prepare(
        conversation: List<ChatMessage>,
        priorSummary: String? = null,
        capTokens: Int = DEFAULT_CAP_TOKENS,
        hardCapTokens: Int = DEFAULT_HARD_CAP_TOKENS
    ): PreparedContext {
        val priorMsg = priorSummary?.takeIf { it.isNotBlank() }?.let { summaryMessage(it) }
        val full = listOfNotNull(priorMsg) + conversation
        if (CompressionMath.estimateTokens(full) <= capTokens) {
            return PreparedContext(full, CompressionOutcome.NotNeeded, priorSummary, 0)
        }
        val (older, kept) = CompressionMath.splitKeepingLast(conversation)
        if (older.isNotEmpty()) {
            try {
                val input = listOfNotNull(priorMsg) + older
                val summary = summarize(input).trim()
                if (summary.isNotEmpty()) {
                    return PreparedContext(
                        messages = listOf(summaryMessage(summary)) + kept,
                        outcome = CompressionOutcome.Compressed,
                        summary = summary,
                        consumedCount = older.size
                    )
                }
            } catch (e: Exception) {
                // Fall through to the honest fallback below.
            }
        }
        // Summary unavailable/failed: send whole if it fits the hard cap…
        if (CompressionMath.estimateTokens(full) <= hardCapTokens) {
            return PreparedContext(full, CompressionOutcome.SentUncompressed, priorSummary, 0)
        }
        // …otherwise drop the oldest turns of the conversation to fit the cap.
        val (trimmed, dropped) = CompressionMath.trimToFit(conversation, capTokens)
        val messages = listOfNotNull(priorMsg) + trimmed
        return PreparedContext(messages, CompressionOutcome.Trimmed(dropped), priorSummary, 0)
    }

    companion object {
        /** Turns always kept verbatim at the tail of the context. */
        const val KEEP_TURNS = 6

        /**
         * Working cap: free-tier models in the catalog document small context
         * windows and the router picks the serving model per call, so the app
         * compresses conservatively once the estimate passes ~24k tokens.
         */
        const val DEFAULT_CAP_TOKENS = 24_000

        /** Above this, an uncompressed send is no longer attempted after a summary failure. */
        const val DEFAULT_HARD_CAP_TOKENS = 48_000

        const val SUMMARY_PREFIX = "[Summary of earlier conversation]\n"

        fun summaryMessage(summary: String): ChatMessage =
            ChatMessage("system", SUMMARY_PREFIX + summary)
    }
}
