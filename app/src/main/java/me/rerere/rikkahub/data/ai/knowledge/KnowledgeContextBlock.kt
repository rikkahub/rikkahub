package me.rerere.rikkahub.data.ai.knowledge

/**
 * The origin of a knowledge context block — the single fact that lets the assembled prompt label
 * each block by source so insight (memory) can never masquerade as document evidence (invariant 4
 * of issue #141). Deliberately exactly three values: no `KnowledgeKind` (preference/fact/decision)
 * taxonomy — that is an interface-with-one-impl and an explicit non-goal of the design.
 *
 * Declaration order is the stable, deterministic tiebreak used by [KnowledgeContextAssembler] when
 * two candidate blocks carry the same [KnowledgeContextBlock.priority]; cross-source priority
 * (e.g. attachment > RAG on the message surface) is carried by the priority field the emitter
 * assigns, NOT by this ordinal.
 */
enum class KnowledgeSource {
    MEMORY,
    RAG,
    ATTACHMENT,
}

/**
 * Where a knowledge block is scoped — drives rendering *placement* (a separate step from selection):
 * MEMORY is assistant-scoped and renders in the system-prompt area (Phase 2); ATTACHMENT/RAG are
 * message-scoped and render near the last user message (Phase 1). The assembler itself is
 * scope-agnostic; it only selects and orders.
 */
enum class KnowledgeScope {
    GLOBAL,
    ASSISTANT,
    CONVERSATION,
    MESSAGE,
}

/**
 * One candidate unit of contextual knowledge to inject into the outgoing prompt (issue #141).
 *
 * This is the type the codebase previously lacked: because no value carried an injection's token
 * cost ([estimatedTokens]), nothing could sum or bound the combined cost of Memory + RAG +
 * Attachment, so each grew unbounded through its own ad-hoc prepend idiom. With this type plus the
 * pure [KnowledgeContextAssembler], the special cases (three prepend idioms, unbounded growth,
 * "memory must not look like evidence") collapse into one budgeted, source-labeled selection.
 *
 * @property source which subsystem produced the block; renders the source label.
 * @property scope addressing scope; drives placement, not selection.
 * @property title optional human/diagnostic label (e.g. an attachment file name); may be null.
 * @property content the already-rendered text to inject for this block.
 * @property priority selection precedence; higher wins. Equal priorities break by [source] ordinal.
 * @property estimatedTokens this block's conservative token cost, from the shared #193 estimator;
 *   the assembler sums these to enforce the budget.
 */
data class KnowledgeContextBlock(
    val source: KnowledgeSource,
    val scope: KnowledgeScope,
    val title: String?,
    val content: String,
    val priority: Int,
    val estimatedTokens: Int,
)
