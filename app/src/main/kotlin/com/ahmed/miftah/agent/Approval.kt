package com.ahmed.miftah.agent

import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/**
 * Batched approval model (Ahmed's rule, 2026-11-25): the user is asked ONCE
 * per grouped operation, never once per item. Uploading a 150-file project
 * produces a single [ApprovalBundle] with 150 items and a single decision —
 * not 150 prompts.
 *
 * Scope discipline: a decision covers exactly the bundle that was shown —
 * its item set is pinned by [ApprovalBundle.digest]. Actions the agent
 * generates after a bundle was approved form a NEW bundle that needs its
 * own approval. There are no standing or blanket approvals: a decision is
 * consumed by execution or dismissal of its bundle.
 */

/** One consequential action inside a bundle. [args] is kept in memory only, for execution after approval. */
data class ApprovalItem(
    val toolName: String,
    val description: String,
    val target: String,
    val argsDigest: String,
    val args: JSONObject
) {
    companion object {
        /** Keys that identify where an action lands, in priority order (used for the bundle destination). */
        private val TARGET_KEYS = listOf(
            "repo", "repository", "destination", "path", "file", "url",
            "bucket", "account", "id", "name", "title"
        )

        fun of(toolName: String, args: JSONObject): ApprovalItem {
            val target = TARGET_KEYS.firstNotNullOfOrNull { key ->
                args.optString(key).takeIf { it.isNotBlank() }
            } ?: ""
            val description = if (target.isNotBlank()) {
                "$toolName · $target"
            } else {
                val pairs = canonicalPairs(args)
                if (pairs.isEmpty()) toolName else "$toolName · $pairs"
            }
            return ApprovalItem(
                toolName = toolName,
                description = description.take(120),
                target = target,
                argsDigest = digestOf(canonical(args)),
                args = args
            )
        }

        /** Stable "k=v&k=v" rendering with sorted keys — the basis for digests. */
        fun canonical(args: JSONObject): String {
            val keys = ArrayList<String>()
            val it = args.keys()
            while (it.hasNext()) keys.add(it.next())
            keys.sort()
            return keys.joinToString("&") { k -> "$k=${args.opt(k)}" }
        }

        private fun canonicalPairs(args: JSONObject): String {
            val keys = ArrayList<String>()
            val it = args.keys()
            while (it.hasNext()) keys.add(it.next())
            keys.sort()
            return keys.take(3).joinToString(", ") { k -> "$k=${args.opt(k)}" }
        }

        fun digestOf(text: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * A group of consequential actions presented to the user as ONE approval
 * request: a human summary (action + count + destination), the full item
 * list for review/exclusion, and a [digest] pinning the exact item set the
 * decision applies to.
 */
data class ApprovalBundle(
    val id: String,
    val createdMs: Long,
    val items: List<ApprovalItem>
) {
    /** The single tool name when all items use one tool, else a generic mixed label. */
    val actionLabel: String =
        items.map { it.toolName }.distinct().singleOrNull() ?: "mixed actions"

    /** The common target of all items (e.g. one repo), or "" when items land in different places. */
    val destination: String =
        items.map { it.target }.distinct().singleOrNull() ?: ""

    /** Human one-liner, e.g. "github_upload_file · 150 items → ahmedking2005/Miftah". */
    val summary: String
        get() = buildString {
            append(actionLabel).append(" · ").append(items.size).append(" item(s)")
            if (destination.isNotBlank()) append(" → ").append(destination)
        }

    /** Digest of the exact item set; a decision is valid only for the bundle carrying this digest. */
    val digest: String = ApprovalItem.digestOf(
        items.joinToString("\n") { "${it.toolName}|${it.target}|${it.argsDigest}" }
    )

    companion object {
        fun create(items: List<ApprovalItem>, nowMs: Long = System.currentTimeMillis()): ApprovalBundle =
            ApprovalBundle(
                id = "b-" + UUID.randomUUID().toString().take(8),
                createdMs = nowMs,
                items = items
            )
    }
}

/** The user's single decision on a whole bundle. */
sealed interface ApprovalDecision {
    /** Approve the bundle; [excludedIndexes] are items the user unchecked — dropped, never executed. */
    data class Approved(val excludedIndexes: Set<Int> = emptySet()) : ApprovalDecision

    /** Reject the whole bundle: nothing executes. */
    object Rejected : ApprovalDecision

    /** Dialog closed without a decision: treated as not approved. */
    object Dismissed : ApprovalDecision
}

/** Outcome for one bundle item, in bundle order. */
data class BundleItemResult(
    val item: ApprovalItem,
    val result: String,
    val executed: Boolean
)

/** Full outcome of resolving one bundle: the decision plus a per-item result for every item. */
data class BundleExecution(
    val bundle: ApprovalBundle,
    val decision: ApprovalDecision,
    val results: List<BundleItemResult>
)
