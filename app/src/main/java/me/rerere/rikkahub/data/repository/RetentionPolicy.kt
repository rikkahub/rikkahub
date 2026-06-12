package me.rerere.rikkahub.data.repository

/**
 * The pure retention decision shared by the task-run and work-item sweeps (SPEC.md M6 + the
 * Failure-modes "Unbounded board" row): a retained (terminal/deleted) row is kept while it is
 * EITHER recent (updated within [maxAgeMillis] of [now]) OR inside the newest
 * [keepNewestPerConversation] of its conversation. It is swept only when it is BOTH older than the
 * cutoff AND beyond that window — so neither rule alone can delete a row another rule would keep.
 *
 * Extracted as one pure function (not duplicated per repository) because both sweeps make the
 * identical decision over a `(conversationId, updatedAt)` projection; only the row type differs.
 *
 * @param rows the retained candidates, each carrying the conversation it belongs to and its recency.
 * @return the ids to delete — every row that falls outside BOTH the recency and newest-N windows.
 */
fun <T> selectExpiredForRetention(
    rows: List<T>,
    now: Long,
    maxAgeMillis: Long,
    keepNewestPerConversation: Int,
    conversationOf: (T) -> String,
    updatedAtOf: (T) -> Long,
    idOf: (T) -> String,
): List<String> {
    val cutoff = now - maxAgeMillis
    return rows
        .groupBy(conversationOf)
        .flatMap { (_, perConversation) ->
            // Newest first; the first [keepNewestPerConversation] are kept regardless of age.
            val ordered = perConversation.sortedByDescending(updatedAtOf)
            ordered.drop(keepNewestPerConversation.coerceAtLeast(0))
                .filter { updatedAtOf(it) < cutoff }
                .map(idOf)
        }
}
