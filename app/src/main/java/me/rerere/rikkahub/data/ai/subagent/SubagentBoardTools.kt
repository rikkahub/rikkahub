package me.rerere.rikkahub.data.ai.subagent

import me.rerere.ai.core.Tool
import me.rerere.ai.runtime.board.buildBoardTools
import me.rerere.rikkahub.data.ai.task.BoardPortAdapter
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.BoardActor
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
 * The board is bound to [conversationId] (the PARENT conversation) and owned by a per-subagent
 * [BoardActor], so a `task_update claim` takes ownership AS this subagent rather than rejecting for a
 * missing owner (decision #4: a claim requires a non-null actor). Conversation scope and owner are
 * closed over in the [BoardPortAdapter]; the tools never see either, so a subagent physically cannot
 * reach across conversations or claim under the wrong owner — every invariant is still enforced once,
 * in [TaskBoardRepository] (decision #4).
 */
fun subagentBoardTools(
    repository: TaskBoardRepository,
    conversationId: Uuid,
    sub: Assistant,
): List<Tool> = buildBoardTools(
    BoardPortAdapter(
        repository = repository,
        conversationId = conversationId,
        actor = BoardActor(
            handleId = "subagent:$conversationId:${sub.id}",
            displayName = sub.name,
        ),
    )
)
