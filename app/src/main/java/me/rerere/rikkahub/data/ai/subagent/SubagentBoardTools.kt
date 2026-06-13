package me.rerere.rikkahub.data.ai.subagent

import me.rerere.ai.core.Tool
import me.rerere.ai.runtime.board.buildBoardTools
import me.rerere.rikkahub.data.ai.task.BoardPortAdapter
import me.rerere.rikkahub.data.ai.task.ExecutionHandle
import me.rerere.rikkahub.data.ai.task.ExecutionHandleRegistry
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.TaskBoardRepository
import kotlin.uuid.Uuid

/**
 * The per-conversation board tools (`task_create/get/list/update`) a SPAWNED subagent receives so it
 * can coordinate over the parent conversation's ONE shared board (spec assumption 5 / decision #5).
 *
 * Why this exists separately from [me.rerere.rikkahub.data.ai.runtime.AppToolCatalog], which already
 * adds the board tools to a `TurnMode.Subagent` pool: the production spawn path never assembles the
 * subagent pool through the catalog. [buildSpawnTool]'s `buildSubagentTools` lambda builds it from
 * the sub's allowlist (local + skills + MCP) and feeds it straight to
 * [me.rerere.rikkahub.data.ai.task.TaskCoordinator.run]. So the catalog's `TurnMode.Subagent` arm is
 * dead for spawned children, and the only place that can actually reach a spawned subagent's pool is
 * that lambda. This is the seam it calls — extracted here as a pure, Android-free function so the
 * binding is unit-testable without `ChatService`.
 *
 * The board is bound to [conversationId] (the PARENT conversation) and every claim is owned by the
 * subagent's live execution [handle] via [BoardPortAdapter.forHandle] (review findings #1/#5): the
 * owner is the HANDLE id — the same identity orphan recovery releases by — so a dead handle's
 * claims are precisely identifiable, and accepted claims are mirrored onto the [registry] as the
 * handle's `workItemIds`. The subagent's display name is carried separately for the board UI.
 * Conversation scope and owner are closed over in the adapter; the tools never see either, so a
 * subagent physically cannot reach across conversations or claim under the wrong owner — every
 * invariant is still enforced once, in [me.rerere.rikkahub.data.repository.TaskBoardRepository]
 * (decision #4).
 */
fun subagentBoardTools(
    repository: TaskBoardRepository,
    conversationId: Uuid,
    registry: ExecutionHandleRegistry,
    handle: ExecutionHandle,
    sub: Assistant,
): List<Tool> = buildBoardTools(
    BoardPortAdapter.forHandle(
        repository = repository,
        conversationId = conversationId,
        registry = registry,
        handle = handle,
        displayName = sub.name,
    )
)
