package me.rerere.ai.runtime.memory

import me.rerere.ai.runtime.contract.RecalledMemory
import java.security.MessageDigest
import kotlin.math.max

/**
 * Neutral memory recall VALUE helpers (issue #243 slice 8/10): the koog-free units of the Memory v2
 * relevance recall (issue #210) — age render, content-hash freshness key, the pure recency ranking,
 * and the recall tuning constants. The embedder-bound `EmbeddingMemoryRecaller` STAYS in `:app` (it
 * references the koog embedding library, which is not on the `:ai`/`:common` classpath) and imports
 * these.
 *
 * All pure JVM (`MessageDigest`, `kotlin.math`) so they are headless-unit-testable without Android.
 */

private const val MS_PER_DAY = 24L * 60L * 60L * 1000L

/** Default candidate count fed into the prompt (issue #210 §13 Q1). */
const val MEMORY_RECALL_K = 5

/**
 * Relevance floor for cosine-selected memories (issue #210 §13 Q2). Same value as
 * `KnowledgeBase.DEFAULT_MIN_SCORE` today, but a SEPARATE constant so the two can diverge later
 * (memories are shorter than RAG chunks ⇒ a different similarity distribution).
 */
const val MEMORY_MIN_SCORE = 0.5

/**
 * Whole-day delta between [referenceMs] (the memory's `updatedAt`) and [nowMs], clamped at >= 0 so a
 * clock skew that puts a memory in the "future" renders 0 rather than a negative count. [nowMs] is
 * injected (never read from `System.currentTimeMillis` inside) so the function is deterministic and
 * unit-testable (property P7).
 */
fun memoryAgeDays(referenceMs: Long, nowMs: Long): Long =
    max(0L, (nowMs - referenceMs) / MS_PER_DAY)

/**
 * Renders the age of a memory as a coarse human-readable label for the recall prompt — a port of the
 * Claude-Code `memoryAge.ts` helper (issue #210 §6). English literals match `buildMemoryPrompt`'s
 * existing English; i18n is out of scope for this slice.
 */
fun memoryAgeLabel(referenceMs: Long, nowMs: Long): String =
    when (val days = memoryAgeDays(referenceMs, nowMs)) {
        0L -> "today"
        1L -> "yesterday"
        else -> "$days days ago"
    }

/**
 * Stable content hash for a memory's embedded text — one half of the vector freshness key
 * (issue #210 §4). A stored vector is only usable for ranking when this hash of the memory's CURRENT
 * content matches the hash recorded when the vector was produced; otherwise the content was edited
 * after embedding and the vector is stale.
 *
 * SHA-256 hex: deterministic (same input ⇒ same output across runs/processes — properties P9/P11
 * depend on it) and collision-safe enough for a freshness gate. Pure JVM (`MessageDigest`) so it is
 * unit-testable headlessly without Android.
 */
fun memoryContentHash(content: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(content.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}

/**
 * Pure recency ranking core (no IO) — most-recently updated first, then take [k]. Ties on `updatedAt`
 * are broken by descending id (the more recently inserted row) so the order is deterministic.
 * [k] <= 0 ⇒ empty list. Used both as the always-available fallback recaller's core and as the
 * cosine recall's recency tail.
 */
fun rankByRecency(memories: List<RecalledMemory>, k: Int): List<RecalledMemory> {
    if (k <= 0) return emptyList()
    return memories
        .sortedWith(compareByDescending<RecalledMemory> { it.updatedAt }.thenByDescending { it.id })
        .take(k)
}
