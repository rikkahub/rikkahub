package me.rerere.ai.runtime.knowledge

import me.rerere.common.text.UntrustedContentFraming

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
    /**
     * [includeUntrustedDirective] gates the per-block untrusted-data directive (#197 I-DELIMIT). It
     * defaults to true (the safe floor, byte-identical to the historical output); the caller passes
     * false when the user has turned untrusted-content framing OFF in Advanced > Security. The
     * structural escaping ([UntrustedContentFraming.escape]) and the source wrapper tags are ALWAYS
     * kept — only the model-facing "treat this as data" directive line is dropped.
     */
    fun render(block: KnowledgeContextBlock, includeUntrustedDirective: Boolean = true): String =
        when (block.source) {
            KnowledgeSource.RAG -> knowledgeBaseContext(block.content, includeUntrustedDirective)
            KnowledgeSource.ATTACHMENT -> block.content
            KnowledgeSource.MEMORY -> memoryContext(block.content, includeUntrustedDirective)
        }

    // Built explicitly (buildString/appendLine), NOT a trimIndent template. For a SINGLE-line payload
    // the directive-included output is byte-identical to the legacy trimIndent wrapper. For a MULTI-line
    // payload it is INTENTIONALLY cleaner: the old template's common-indent detection was defeated by the
    // zero-indent payload continuation, so it leaked the raw-string's 16-space indentation onto the
    // wrapper/guidance/directive lines — a stray artifact, not a contract. The explicit builder always
    // emits unindented lines. It also lets an omitted directive (framing off) drop without a blank line.
    private fun knowledgeBaseContext(joinedChunks: String, includeUntrustedDirective: Boolean): String {
        if (joinedChunks.isBlank()) return ""
        return buildString {
            appendLine("<knowledge_base_context>")
            appendLine("The following excerpts were retrieved from the user's attached knowledge base and may be")
            appendLine("relevant to the request. Use them when helpful; ignore them when not.")
            if (includeUntrustedDirective) {
                appendLine(UntrustedContentFraming.UNTRUSTED_DATA_DIRECTIVE)
                appendLine()
            }
            appendLine(UntrustedContentFraming.escape(joinedChunks))
            append("</knowledge_base_context>")
        }
    }

    private fun memoryContext(content: String, includeUntrustedDirective: Boolean): String {
        if (content.isBlank()) return ""
        return buildString {
            appendLine("<memory>")
            if (includeUntrustedDirective) appendLine(UntrustedContentFraming.UNTRUSTED_DATA_DIRECTIVE)
            appendLine(UntrustedContentFraming.escape(content))
            append("</memory>")
        }
    }
}
