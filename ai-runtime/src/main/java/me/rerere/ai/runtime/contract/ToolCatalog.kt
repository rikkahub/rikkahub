package me.rerere.ai.runtime.contract

import me.rerere.ai.core.Tool
import kotlin.uuid.Uuid

/**
 * Whether the assembled pool is for the main agent turn or a spawned subagent turn (issue #243 §D).
 * The distinction is the recursion guard + approval-strip carrier: a [Subagent] turn never receives
 * the spawn tool and never receives approval-gated tools.
 */
enum class TurnMode { Main, Subagent }

/**
 * The neutral INPUTS to tool-pool assembly (issue #243 §D). The runtime hands the catalog this
 * security-aware context; it carries the policy decision *inputs* but names no concrete tool source,
 * so the runtime main code references ONLY [ToolCatalog] / [ToolAssemblyContext] — never a search /
 * MCP / spawn / skill identifier (the §E P6 token invariant).
 *
 * @param mode main vs subagent turn (the recursion + approval-strip discriminator).
 * @param targetAssistant the assistant whose allowlist the pool is built from. For a subagent turn
 *   this is the SUB assistant, so MCP selection keys off the target — a subagent never inherits the
 *   parent's MCP servers (the §C3 by-target-assistant invariant).
 * @param parentModelId the spawning (parent) model id for a [TurnMode.Subagent] turn, else null.
 *   It is the model the subagent should inherit when it pins none. On a [TurnMode.Main] turn the
 *   spawn tool's parent model is NOT this field (it is null then) but [targetAssistant]'s own
 *   `chatModelId` — the current/parent assistant's model — so an unpinned subagent inherits it.
 * @param allowApprovalTools when false, approval-gated tools are stripped from the pool (subagent
 *   pools drop `needsApproval` tools — §D).
 * @param includeSpawnTool when false, the subagent-spawn tool is absent (the structural recursion
 *   guard: a subagent pool sets this false so it can never spawn).
 */
data class ToolAssemblyContext(
    val mode: TurnMode,
    val targetAssistant: AssistantConfig,
    val parentModelId: Uuid?,
    val allowApprovalTools: Boolean,
    val includeSpawnTool: Boolean,
)

/**
 * Neutral port assembling the per-turn tool pool (issue #243 §D). The runtime depends ONLY on this
 * abstraction; the concrete app catalog (local/search/workspace/skill/MCP/spawn assembly) is injected
 * at the composition root.
 */
interface ToolCatalog {
    suspend fun tools(ctx: ToolAssemblyContext): List<Tool>
}
