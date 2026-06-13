package me.rerere.rikkahub.data.ai.task

import me.rerere.ai.runtime.board.WorkItemAction
import me.rerere.ai.runtime.board.WorkItemStatus
import me.rerere.ai.runtime.contract.BoardItemSnapshot
import me.rerere.ai.runtime.contract.BoardMutationResult
import me.rerere.ai.runtime.contract.TaskBoardPort
import me.rerere.ai.runtime.contract.WorkItemDraft
import me.rerere.ai.runtime.contract.WorkItemPatch
import me.rerere.rikkahub.data.repository.BoardActor
import me.rerere.rikkahub.data.repository.TaskBoardRepository
import kotlin.uuid.Uuid

/**
 * Binds the neutral [TaskBoardPort] to ONE conversation's board on the [TaskBoardRepository] —
 * the SAME repository path the board UI will use (maintainer decision #4), so legality is
 * enforced once, caller-agnostically, never tool-handler-only (SPEC.md M3/T7).
 *
 * The conversation scope and the acting [BoardActor] are closed over here, exactly as
 * [me.rerere.rikkahub.data.ai.runtime.AppToolCatalog] closes over the spawn tool's parent model:
 * tools built on the port (`buildBoardTools`) never see a conversation id or an owner handle, so a
 * board tool physically cannot reach across conversations or claim under the wrong owner. The
 * adapter is constructed per generation (like the catalog itself), so the binding is always the
 * current conversation.
 *
 * The actor is required for [WorkItemDraft]-free claim ownership: `task_update` with a `claim`
 * action takes ownership AS this actor; every other action ignores it (decision #4 — the board is
 * user-editable, so completing/releasing another owner's item is allowed). A `null`-actor binding
 * (e.g. a read-only context) still lists/gets/creates; a claim then rejects at the repository,
 * which is the single enforcement point.
 */
class BoardPortAdapter(
    private val repository: TaskBoardRepository,
    private val conversationId: Uuid,
    private val actor: BoardActor? = null,
    private val onClaimAccepted: ((Uuid) -> Unit)? = null,
) : TaskBoardPort {

    override suspend fun create(draft: WorkItemDraft): BoardMutationResult =
        repository.create(conversationId, draft)

    override suspend fun get(id: Uuid): BoardItemSnapshot? =
        repository.get(conversationId, id)

    override suspend fun list(statuses: Set<WorkItemStatus>?): List<BoardItemSnapshot> =
        repository.list(conversationId, statuses)

    override suspend fun update(patch: WorkItemPatch): BoardMutationResult {
        val result = repository.update(conversationId, patch, actor)
        // After the repository ACCEPTS a claim (the single enforcement point already decided
        // SingleOwnerClaim), record it against the owning handle so orphan recovery (M6) can
        // release every claim a dead handle holds. Only an accepted claim attaches — a rejected
        // claim or any non-claim action leaves the handle's tracked set untouched.
        if (patch.action == WorkItemAction.Claim && result is BoardMutationResult.Accepted) {
            onClaimAccepted?.invoke(result.snapshot.item.id)
        }
        return result
    }

    companion object {
        /**
         * Bind the board port to ONE spawned subagent's execution handle (SPEC.md M5/T11,
         * decision #5): the port targets the PARENT conversation's shared board, but every claim
         * is owned by [handle] (`ownerHandleId = handle.id`) and tracked back onto the
         * [registry] so the in-memory handle's `workItemIds` mirror the persisted claims. A
         * subagent may hold many claims; SingleOwnerClaim per item still holds, enforced in the
         * repository for every caller alike.
         */
        fun forHandle(
            repository: TaskBoardRepository,
            conversationId: Uuid,
            registry: ExecutionHandleRegistry,
            handle: ExecutionHandle,
            displayName: String? = null,
        ): BoardPortAdapter = BoardPortAdapter(
            repository = repository,
            conversationId = conversationId,
            actor = BoardActor(handleId = handle.id, displayName = displayName ?: handle.id),
            onClaimAccepted = { itemId -> registry.attachWorkItem(handle.id, itemId) },
        )
    }
}
