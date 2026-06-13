package me.rerere.rikkahub.ui.pages.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.runtime.contract.ScheduleDraft
import me.rerere.ai.runtime.contract.ScheduleMutationResult
import me.rerere.ai.runtime.contract.ScheduleOwner
import me.rerere.ai.runtime.contract.ScheduleSnapshot
import me.rerere.rikkahub.data.repository.TaskScheduleRepository
import kotlin.uuid.Uuid

/**
 * The schedule screen's view model (SPEC.md M5 / task T10). It lists, creates, and deletes a
 * conversation's schedules by WRITING through the SAME [TaskScheduleRepository] the schedule tools
 * use — every legality gate (target spawnable, caps, minimum interval, prompt bound, conversation
 * scoping) is enforced once in the repository, so a UI create is judged identically to a tool create
 * (no UI-only validation path).
 *
 * UI creates are owned by [ScheduleOwner.USER], which the repository's per-owner cap isolates from
 * the agent quota (spec assumption 4).
 *
 * Conversation binding (spec assumption 5): a schedule's parent conversation is MANDATORY and must
 * never be [me.rerere.rikkahub.data.ai.task.TaskCoordinator]'s `Uuid.random()` default. When the
 * screen is opened without a bound conversation, the first create [ensureConversation]s a real
 * conversation bound to [targetAssistantId] (titled "Scheduled task" at the binding seam) and uses
 * its id; once bound, the id is reused for every subsequent create/list/delete. The
 * [ensureConversation] seam is injected (DIP) so the Android-bound conversation repository stays off
 * this view model's JVM-testable surface, mirroring how the schedule tools' port is closed over a
 * conversation id at the composition root.
 */
class ScheduleVM(
    private val targetAssistantId: Uuid,
    initialConversationId: Uuid?,
    private val repository: TaskScheduleRepository,
    private val ensureConversation: suspend (assistantId: Uuid) -> Uuid,
    private val rollbackConversation: suspend (id: Uuid) -> Unit,
) : ViewModel() {

    private val _conversationId = MutableStateFlow(initialConversationId)

    /** The bound parent conversation, or null until the first create materializes one. */
    val conversationId: StateFlow<Uuid?> = _conversationId.asStateFlow()

    private val _schedules = MutableStateFlow<List<ScheduleSnapshot>>(emptyList())

    /** The bound conversation's schedules, in presentation order (next-fire ascending). */
    val schedules: StateFlow<List<ScheduleSnapshot>> = _schedules.asStateFlow()

    // Serializes createSchedule so the "is the screen unbound? then materialize ONE parent" decision and
    // its commit are one critical section. Without it, two fire-and-forget creates from an unbound screen
    // both observe _conversationId == null across the ensureConversation suspension, each materialize a
    // conversation, and a sibling rejection's rollback can unbind a parent another create already bound.
    private val createMutex = Mutex()

    /**
     * Resolve the bound conversation, creating one bound to [targetAssistantId] up front if none is
     * bound yet. The created id is cached so a screen that started conversation-less binds exactly
     * one conversation across all later mutations — never a fresh (or random) parent per create.
     */
    private suspend fun requireConversationId(): Uuid =
        _conversationId.value ?: ensureConversation(targetAssistantId).also { _conversationId.value = it }

    /**
     * Create a schedule through the shared repository path, binding (or creating) the parent first.
     * The screen's [targetAssistantId] is stamped onto the draft here — the UI never chooses a target
     * other than the assistant the screen is bound to, so a UI create can never aim at a foreign or
     * unset assistant id.
     */
    suspend fun createSchedule(draft: ScheduleDraft): ScheduleMutationResult = createMutex.withLock {
        val wasUnbound = _conversationId.value == null
        val conversationId = requireConversationId()
        val result = repository.create(
            conversationId,
            ScheduleOwner.USER,
            draft.copy(targetAssistantId = targetAssistantId),
        )
        when (result) {
            is ScheduleMutationResult.Accepted -> refresh(conversationId)
            // A create this very call materialized must leave NO orphan when the repository rejects it:
            // roll the just-created conversation back and unbind it. wasUnbound (not initialConversationId)
            // gates this so a pre-bound parent is never deleted. Serialized by createMutex, wasUnbound is
            // accurate — a concurrent sibling cannot have bound (or be about to bind) a parent we'd unbind.
            is ScheduleMutationResult.Rejected -> if (wasUnbound) {
                rollbackConversation(conversationId)
                _conversationId.value = null
            }
        }
        result
    }

    /** List the bound conversation's schedules; an unbound screen has nothing to list yet. */
    suspend fun listSchedules(): List<ScheduleSnapshot> {
        val conversationId = _conversationId.value ?: return emptyList()
        return repository.list(conversationId).also { _schedules.value = it }
    }

    /** Delete a schedule through the shared repository path (scoped to the bound conversation). */
    suspend fun deleteSchedule(id: Uuid): ScheduleMutationResult {
        val conversationId = _conversationId.value
            ?: return ScheduleMutationResult.Rejected("no conversation bound")
        val result = repository.delete(conversationId, id)
        if (result is ScheduleMutationResult.Accepted) refresh(conversationId)
        return result
    }

    private suspend fun refresh(conversationId: Uuid) {
        _schedules.value = repository.list(conversationId)
    }

    // --- fire-and-forget UI entry points --------------------------------------------------------

    fun create(draft: ScheduleDraft, onResult: (ScheduleMutationResult) -> Unit = {}) {
        viewModelScope.launch { onResult(createSchedule(draft)) }
    }

    fun delete(id: Uuid, onResult: (ScheduleMutationResult) -> Unit = {}) {
        viewModelScope.launch { onResult(deleteSchedule(id)) }
    }

    fun load() {
        viewModelScope.launch { listSchedules() }
    }
}
