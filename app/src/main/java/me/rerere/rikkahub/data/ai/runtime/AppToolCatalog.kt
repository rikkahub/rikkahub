package me.rerere.rikkahub.data.ai.runtime

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.runtime.contract.AssistantConfig
import me.rerere.ai.runtime.contract.ToolAssemblyContext
import me.rerere.ai.runtime.contract.ToolCatalog
import me.rerere.ai.runtime.contract.TurnMode
import me.rerere.ai.runtime.mcp.McpTool
import me.rerere.ai.runtime.subagent.filterToolsForSubagent
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.subagent.SPAWN_TOOL_NAME
import me.rerere.rikkahub.service.mapMcpTool
import kotlin.uuid.Uuid

/**
 * App-side [ToolCatalog] reproducing the security-relevant tool-assembly policy of
 * `ChatService.buildGenerationTools` (issue #243 slice 3, ChatService.kt §buildGenerationTools), so
 * the runtime can depend on the neutral [ToolCatalog] abstraction. ChatService is now wired onto
 * this catalog (issue #243 slice 10): the main-turn tool pool is assembled here, not in a private
 * ChatService.buildGenerationTools.
 *
 * The catalog reproduces the THREE invariants the policy tests pin, delegating to the already-shared
 * seams so no policy is duplicated:
 *  - MCP tools are selected off the TARGET assistant ([mcpToolsForAssistant], keyed on
 *    `ctx.targetAssistant`) and named `mcp__…` via [mapMcpTool] — the §C3 by-target invariant + the
 *    canonical prefix.
 *  - the spawn tool is added only on a Main turn with `includeSpawnTool` true ([spawnTool] non-null)
 *    — the structural recursion guard ([filterToolsForSubagent] additionally strips it on subagent
 *    pools, belt-and-suspenders).
 *  - `needsApproval` tools are stripped when `allowApprovalTools` is false (subagent pools), matching
 *    the spawn-site `.filterNot { it.needsApproval }`.
 *
 * The non-security base pool (local / search / workspace / skill / automation tools) is provided via
 * the injected [baseTools] seam — the portion ChatService rewires onto this catalog in slice 10.
 * Reproducing that closure state here would duplicate policy slice 10 must then delete, so it is
 * injected, not copied.
 *
 * The board tools (`task_create/get/list/update`, SPEC.md M3/T7) come from the [boardTools] seam.
 * They are added to the BASE pool for BOTH [TurnMode.Main] and [TurnMode.Subagent]: a spawned
 * subagent must coordinate over the parent conversation's shared board (spec assumption 5). They
 * ride the same recursion-guard + approval-strip policy as every other base tool — but neither
 * touches them: they carry `needsApproval = false` and are not named [SPAWN_TOOL_NAME], so the
 * approval strip and the recursion guard both pass them through. Adding them to `pool` (not after
 * the policy filters) keeps that invariant honest rather than special-casing the board tools past
 * the very guards that protect the subagent pool.
 */
class AppToolCatalog(
    private val baseTools: suspend (target: AssistantConfig, mode: TurnMode) -> List<Tool>,
    private val mcpToolsForAssistant: (target: AssistantConfig) -> List<Pair<Uuid, McpTool>>,
    private val mcpCall: suspend (serverId: Uuid, toolName: String, args: JsonObject) -> List<UIMessagePart>,
    private val spawnTool: (parentModelId: Uuid?) -> Tool?,
    private val boardTools: suspend () -> List<Tool> = { emptyList() },
    private val scheduleTools: suspend () -> List<Tool> = { emptyList() },
) : ToolCatalog {

    override suspend fun tools(ctx: ToolAssemblyContext): List<Tool> {
        val pool = buildList {
            addAll(baseTools(ctx.targetAssistant, ctx.mode))
            // Per-conversation board tools, available to the parent AND every spawned subagent so
            // they can coordinate over one shared board (decision #5). Conversation scope and owner
            // are bound inside the port (BoardPortAdapter), never visible to the tool.
            addAll(boardTools())
            // Per-conversation schedule tools (SPEC.md M4), alongside the board tools and riding the
            // same recursion-guard + approval-strip policy. `schedule_create`/`schedule_delete` carry
            // needsApproval=true, so the approval strip drops them from a subagent pool; conversation
            // scope and owner are bound inside the port (SchedulePortAdapter), never visible here.
            addAll(scheduleTools())
            mcpToolsForAssistant(ctx.targetAssistant).forEach { (serverId, tool) ->
                add(mapMcpTool(serverId, tool) { sid, name, args -> mcpCall(sid, name, args) })
            }
            if (ctx.mode == TurnMode.Main && ctx.includeSpawnTool) {
                // The spawn parent is the current (main/parent) assistant's model, exactly as the
                // production spawn site passes `parentModelId = assistant.chatModelId`
                // (ChatService.buildGenerationTools). An unpinned subagent must inherit it via
                // resolveSubagentModel, so it is the TARGET (= main) assistant here, not
                // ctx.parentModelId (which is the SUBAGENT-turn carrier, null on a main turn).
                spawnTool(ctx.targetAssistant.chatModelId)?.let { add(it) }
            }
        }
        // The spawn-strip (recursion guard) and the approval-strip are orthogonal policies, mirrored
        // independently. Production strips the spawn tool from EVERY subagent pool unconditionally
        // (SubagentRunner -> filterToolsForSubagent), keyed on the recursion-guard inputs, never on
        // approval stripping; approval-gated tools are dropped separately when allowApprovalTools is
        // false. Coupling them would let a base tool literally named `task` leak into a subagent pool
        // that happens to allow approval tools.
        // The guard keys on the turn MODE alone: production never filters the main pool, so a main
        // turn that merely omits the spawn tool (includeSpawnTool=false) must NOT strip a base tool
        // that happens to be named `task`.
        val recursionGuarded = if (ctx.mode == TurnMode.Subagent) {
            filterToolsForSubagent(pool, SPAWN_TOOL_NAME)
        } else {
            pool
        }
        return if (ctx.allowApprovalTools) {
            recursionGuarded
        } else {
            recursionGuarded.filterNot { it.needsApproval }
        }
    }
}
