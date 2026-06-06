package me.rerere.rikkahub.data.rag

import kotlin.uuid.Uuid

/**
 * Explicit state for the one multi-step async workflow in the knowledge-base feature: ingesting a
 * single document (resolve -> copy -> extract -> chunk -> embed -> persist) with progress and an
 * all-or-nothing rollback on failure. This replaces the old `Map<Uuid, Float>` progress map plus a
 * transient [Event] pair, which split a single operation's status across two flows and could leave
 * the UI showing progress with no terminal outcome (issue #105).
 *
 * Feature-local by design: per the architecture proposal there is no shared `OperationState` base
 * and no global operation store.
 */
sealed interface RagIngestState {
    data object Idle : RagIngestState

    data class Running(
        val kbId: Uuid,
        val fileName: String,
        val stage: Stage,
        val progress: Float,
    ) : RagIngestState

    data class Success(
        val kbId: Uuid,
        val fileName: String,
        val chunkCount: Int,
    ) : RagIngestState

    /**
     * Terminal failure. [message] is already a safe user-facing string — raw parser/exception text
     * is mapped away at the VM boundary, never surfaced verbatim. [fileName] may be null when the
     * failure happens before a display name is resolved.
     */
    data class Error(
        val kbId: Uuid,
        val fileName: String?,
        val message: String,
    ) : RagIngestState

    enum class Stage { Resolving, Copying, Extracting, Chunking, Embedding, Persisting }
}
