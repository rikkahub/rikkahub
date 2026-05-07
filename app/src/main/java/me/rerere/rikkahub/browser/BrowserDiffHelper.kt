package me.rerere.rikkahub.browser

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Token-cost optimisation pass — line-level text diff for the browser's
 * "diff-after-action" path.
 *
 * State-changing browser tools (click / type / submit / select / press_key) used to
 * return a tiny envelope but the LLM would then issue a follow-up [browser_get_text]
 * call that re-read the entire ~8000-char page after every step. Most actions only
 * mutate <10% of the visible text. Sending only the delta cuts per-step payload by
 * roughly an order of magnitude on multi-step browse sessions.
 *
 * The algorithm is intentionally simple — set membership over `\n`-split lines:
 *  - `added`   = lines present in `after` but absent from `before`
 *  - `removed` = lines present in `before` but absent from `after`
 *
 * Conservative choice over a Myers / patience diff. The output the model consumes is
 * "what's new on the page" not "what bytes shifted"; line-set is sufficient and runs
 * in O(N) where N is the line count (versus O(N*M) for Myers). We document this
 * inline so a future audit knows it's deliberate.
 *
 * Truncation per side is fixed at [MAX_CHARS_PER_SIDE] (2000) — total envelope
 * caps at ~4000 chars + JSON overhead, well under the spec's 4000-char total.
 *
 * Pure Kotlin, zero Android dependencies — the helper is JVM-unit-testable.
 */
internal object BrowserDiffHelper {

    /** Per-side cap. Total envelope payload is ≤ 2 * MAX_CHARS_PER_SIDE + JSON keys. */
    const val MAX_CHARS_PER_SIDE = 2000

    /**
     * Compute the diff envelope for an action that transitioned the page from
     * [before] to [after]. Returns a JSON object suitable for embedding under a
     * `"diff"` key in a tool's success envelope.
     *
     * - Identical inputs (no visible effect) → `{ "unchanged": true }`. The caller
     *   short-circuits this into the success envelope so the LLM can tell the click
     *   landed but didn't change anything visible (e.g. a noop button).
     * - Distinct inputs → `{ added, removed, added_chars, removed_chars, truncated }`.
     *   `truncated` is true iff EITHER side hit the per-side cap.
     */
    fun computeDiff(before: String, after: String): JsonObject {
        if (before == after) {
            return buildJsonObject { put("unchanged", true) }
        }

        // LinkedHashSet to preserve insertion order — both for determinism in tests
        // and so the LLM reads the diff in document order rather than hash order.
        val beforeLines = LinkedHashSet(before.split('\n'))
        val afterLines = LinkedHashSet(after.split('\n'))

        val addedLines = afterLines.filterNot { it in beforeLines }
        val removedLines = beforeLines.filterNot { it in afterLines }

        val rawAdded = addedLines.joinToString("\n").trim()
        val rawRemoved = removedLines.joinToString("\n").trim()

        // Both sides empty → identical-after-trim (e.g. only-whitespace lines moved
        // around). Surface as unchanged to keep the envelope monotonic — the LLM
        // should treat "no meaningful textual delta" the same way regardless of
        // which exact branch produced it.
        if (rawAdded.isEmpty() && rawRemoved.isEmpty()) {
            return buildJsonObject { put("unchanged", true) }
        }

        val (added, addedTruncated) = truncate(rawAdded)
        val (removed, removedTruncated) = truncate(rawRemoved)

        return buildJsonObject {
            put("added", added)
            put("removed", removed)
            put("added_chars", added.length)
            put("removed_chars", removed.length)
            put("truncated", addedTruncated || removedTruncated)
        }
    }

    /**
     * Clamp a string to [MAX_CHARS_PER_SIDE]. Returns the (possibly truncated)
     * string + whether truncation actually happened so the caller can roll up a
     * single boolean across both sides without re-comparing lengths.
     */
    private fun truncate(s: String): Pair<String, Boolean> =
        if (s.length <= MAX_CHARS_PER_SIDE) s to false
        else s.substring(0, MAX_CHARS_PER_SIDE) to true
}
