package me.rerere.rikkahub.ui.pages.chat.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.runtime.board.WorkItem
import me.rerere.ai.runtime.board.WorkItemAction
import me.rerere.ai.runtime.board.WorkItemStatus
import me.rerere.ai.runtime.contract.BoardMutationResult
import me.rerere.ai.runtime.contract.WorkItemDraft
import me.rerere.ai.runtime.contract.WorkItemPatch
import me.rerere.rikkahub.data.db.dao.WorkItemDAO
import me.rerere.rikkahub.data.repository.BoardActor
import me.rerere.rikkahub.data.repository.TaskBoardRepository
import kotlin.uuid.Uuid

/**
 * The chat-side board panel's view model (SPEC.md M5, maintainer decision #4). It reads the
 * per-conversation board live from Room via [WorkItemDAO.listByConversationFlow] (a `Flow`, so
 * board mutations from spawned subagents and from board tools appear here without a manual
 * refresh), and it WRITES through [TaskBoardRepository] — the SAME repository the board tools
 * call. There is NO UI-only validation: create / edit / status-change / delete all flow through
 * the repository's transactional, invariant-enforcing path, so a UI edit is judged identically to
 * a tool edit (legal transitions, cycle rejection, single-owner claims).
 *
 * `Deleted` items are filtered out of the panel: a delete is a tombstone the repository keeps so
 * dependents unblock and history is auditable, but the user's board shows only live work.
 */
class BoardViewModel(
    id: String,
    private val dao: WorkItemDAO,
    private val repository: TaskBoardRepository,
) : ViewModel() {

    private val conversationId: Uuid = Uuid.parse(id)

    /** This session's actor for claims — the user editing the board, distinct from any subagent. */
    private val userActor = BoardActor(handleId = "user:$id", displayName = "You")

    /**
     * Live, deleted-filtered board. Corrupt rows are dropped (the repository surfaces them on a
     * direct read; the always-on panel must not crash on one bad row), and the order mirrors the
     * DAO's `created_at ASC`.
     */
    val items: StateFlow<List<WorkItem>> = dao.listByConversationFlow(id)
        .map { rows ->
            rows.mapNotNull { it.toWorkItem() }
                .filter { it.status != WorkItemStatus.Deleted }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Create a work item through the shared repository path (decision #4). */
    fun create(subject: String, description: String = "", onResult: (BoardMutationResult) -> Unit = {}) {
        if (subject.isBlank()) return
        viewModelScope.launch {
            onResult(repository.create(conversationId, WorkItemDraft(subject = subject.trim(), description = description.trim())))
        }
    }

    /** Edit an item's text fields through the shared repository path. */
    fun edit(id: Uuid, subject: String? = null, description: String? = null, onResult: (BoardMutationResult) -> Unit = {}) {
        viewModelScope.launch {
            onResult(
                repository.update(
                    conversationId,
                    WorkItemPatch(id = id, subject = subject?.trim(), description = description?.trim()),
                    actor = userActor,
                )
            )
        }
    }

    /**
     * Move an item to [target]. The UI never sends a raw target status — [boardActionFor] maps the
     * gesture to the explicit [WorkItemAction] the repository's validator judges, exactly like the
     * board tools. A non-canonical jump maps to null and is a no-op (the panel offers no such
     * gesture; this is the belt-and-suspenders guard).
     */
    fun changeStatus(item: WorkItem, target: WorkItemStatus, onResult: (BoardMutationResult) -> Unit = {}) {
        val action = boardActionFor(item.status, target) ?: return
        viewModelScope.launch {
            onResult(
                repository.update(
                    conversationId,
                    WorkItemPatch(id = item.id, action = action),
                    actor = userActor,
                )
            )
        }
    }

    /** Delete an item through the shared repository path; its dependents unblock atomically. */
    fun delete(id: Uuid, onResult: (BoardMutationResult) -> Unit = {}) {
        viewModelScope.launch {
            onResult(
                repository.update(
                    conversationId,
                    WorkItemPatch(id = id, action = WorkItemAction.Delete),
                    actor = userActor,
                )
            )
        }
    }
}

/**
 * Map a "move this item from [from] to [target]" gesture to the single canonical [WorkItemAction],
 * or null when no single action realizes the jump. This is the ONE place the panel's status
 * gestures are translated into the explicit intents the `WorkItemTransitionValidator` judges, so
 * the UI and the board tools provably share the same legality path (maintainer decision #4: no
 * UI-only validation). A null result means the panel attempts nothing — it never pushes an action
 * the repository would only reject.
 */
fun boardActionFor(from: WorkItemStatus, target: WorkItemStatus): WorkItemAction? {
    if (from == target) return null
    if (target == WorkItemStatus.Deleted) return WorkItemAction.Delete
    return when (from to target) {
        WorkItemStatus.Pending to WorkItemStatus.InProgress -> WorkItemAction.Claim
        WorkItemStatus.InProgress to WorkItemStatus.Completed -> WorkItemAction.Complete
        WorkItemStatus.InProgress to WorkItemStatus.Pending -> WorkItemAction.Release
        WorkItemStatus.Completed to WorkItemStatus.Pending -> WorkItemAction.Reopen
        else -> null
    }
}
