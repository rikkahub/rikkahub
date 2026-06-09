package me.rerere.rikkahub.data.ai.knowledge

/**
 * Materializes a selected [KnowledgeContextBlock] into the source-labeled text injected into the
 * prompt (issue #141). Source labeling is what makes invariant 4 — "memory insight must not
 * masquerade as document evidence" — free: each source gets a visually distinct wrapper.
 *
 * Pure over the block (no Android Context), so it is JVM unit-testable. The wrapper text below is
 * MODEL-FACING prompt English (it instructs the model how to treat the excerpts), not a localized UI
 * label, so it stays a Kotlin const here mirroring the literal that previously lived inline in
 * `KnowledgeRetrievalTransformer.buildContextBlock` — preserving the on-wire RAG text exactly so a
 * generous-budget assembly reproduces today's output (no regression, PBT P7).
 *
 * Placement (system-prompt vs near-last-user-message) is a separate, scope-driven concern handled by
 * the transformer; this renderer only produces the labeled text.
 */
object KnowledgeContextRenderer {

    /**
     * Per-source rendering. The [KnowledgeContextBlock.content] contract differs by source by design:
     *  - RAG: `content` is the raw retrieved chunk text (chunks already joined); this renderer wraps
     *    it in the `<knowledge_base_context>` block, owning the wrapper as the single source of truth.
     *  - ATTACHMENT: `content` is ALREADY the fully-rendered `DocumentPromptRenderer` output (its
     *    `## user sent a file:` header IS the attachment label), so it is emitted verbatim — wrapping
     *    it again would change the on-wire text vs the old `DocumentAsPromptTransformer`.
     *  - MEMORY: `content` is the raw memory text, wrapped in `<memory>`. Defined now for Phase 2;
     *    no Phase 1 emitter produces MEMORY blocks yet.
     */
    fun render(block: KnowledgeContextBlock): String = when (block.source) {
        KnowledgeSource.RAG -> knowledgeBaseContext(block.content)
        KnowledgeSource.ATTACHMENT -> block.content
        KnowledgeSource.MEMORY -> memoryContext(block.content)
    }

    // Exact reproduction of the legacy KnowledgeRetrievalTransformer.buildContextBlock wrapper so the
    // generous-budget path is byte-identical to today's RAG injection.
    private fun knowledgeBaseContext(joinedChunks: String): String =
        """
            <knowledge_base_context>
            The following excerpts were retrieved from the user's attached knowledge base and may be
            relevant to the request. Use them when helpful; ignore them when not.

            $joinedChunks
            </knowledge_base_context>
        """.trimIndent()

    private fun memoryContext(content: String): String =
        """
            <memory>
            $content
            </memory>
        """.trimIndent()
}
