package me.rerere.rikkahub.ui.pages.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.runtime.contract.ScheduleDraft
import me.rerere.ai.runtime.contract.ScheduleMutationResult
import me.rerere.ai.runtime.contract.ScheduleOwner
import me.rerere.ai.runtime.contract.ScheduleSnapshot
import me.rerere.rikkahub.data.db.entity.TaskRunEntity
import me.rerere.rikkahub.data.db.entity.TaskRunEventSummary
import me.rerere.rikkahub.data.db.entity.TaskRunStateTag
import me.rerere.rikkahub.data.repository.TaskRunRepository
import me.rerere.rikkahub.data.repository.TaskScheduleRepository
import kotlin.uuid.Uuid

/**
 * One row in the "Runs" tab — a flattened, UI-facing projection of a [TaskRunEntity] for the bound
 * conversation. Carries only what the monitor renders so no Room entity leaks into the composable.
 */
data class TaskRunRow(
    val id: String,
    val state: TaskRunStateTag,
    val prompt: String,
    val createdAt: Long,
    val updatedAt: Long,
    val finalResult: String?,
    val finalError: String?,
    // Detail-view fields (run-detail sheet): the summary-only progress events and the budget counters.
    // The full child transcript is intentionally not persisted (TaskRunEntity is SUMMARY-ONLY), so the
    // "full outcome" surfaced is the final result/error in full + these events + usage.
    val events: List<TaskRunEventSummary>,
    val usageSteps: Int,
    val usageTokens: Long,
    val usageElapsedMs: Long,
)

/**
 * The schedule screen's four distinct states (SPEC.md M6 / task T12 / SC6). Modeled as a sealed type so
 * "loading" can never be mistaken for "empty": each state is its own case, not a flag-and-nullable-list
 * combination the screen would have to disambiguate. [Error] is reserved for an UNEXPECTED exception while
 * loading — every domain failure (over-length prompt, cap breach, bad zone) comes back from the repository
 * as a [ScheduleMutationResult.Rejected], not a throw, so [Error] never carries a domain rejection.
 */
sealed interface ScheduleUiState {
    /** The list has not resolved yet — the screen shows a spinner, not the empty-state CTA. */
    data object Loading : ScheduleUiState

    /** The bound conversation has no schedules — the screen shows the empty-state CTA. */
    data object Empty : ScheduleUiState

    /** An unexpected fault while listing — the screen shows [message], never a misleading empty state. */
    data class Error(val message: String) : ScheduleUiState

    /** The bound conversation's schedules, in presentation order (next-fire ascending). */
    data class Content(val schedules: List<ScheduleSnapshot>) : ScheduleUiState
}

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
    private val taskRuns: TaskRunRepository,
    private val ensureConversation: suspend (assistantId: Uuid) -> Uuid,
    private val rollbackConversation: suspend (id: Uuid) -> Unit,
) : ViewModel() {

    private val _conversationId = MutableStateFlow(initialConversationId)

    /** The bound parent conversation, or null until the first create materializes one. */
    val conversationId: StateFlow<Uuid?> = _conversationId.asStateFlow()

    /**
     * The "Runs" tab feed: the bound conversation's task runs, newest-first, live-updating. Follows
     * [_conversationId] so a screen that binds its conversation on first create starts streaming runs
     * without a reload. Empty while unbound (nothing has fired yet). Read-only — the firing/lifecycle
     * truth lives in the task runtime; this only observes the persisted rows.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val runs: StateFlow<List<TaskRunRow>> = _conversationId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else taskRuns.observeByConversation(id)
                .map { list -> list.sortedByDescending { it.createdAt }.map { it.toRow() } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun TaskRunEntity.toRow(): TaskRunRow = TaskRunRow(
        id = id,
        state = TaskRunStateTag.fromPersistedOrNull(latestState) ?: TaskRunStateTag.CREATED,
        prompt = prompt,
        createdAt = createdAt,
        updatedAt = updatedAt,
        finalResult = finalResult,
        finalError = finalError,
        // A corrupt blob decodes to null; treat it as no events rather than failing the whole row.
        events = decodeEventSummaries() ?: emptyList(),
        usageSteps = usageSteps,
        usageTokens = usageTokens,
        usageElapsedMs = usageElapsedMs,
    )

    // The screen's four-state view (SPEC.md M6 / task T12). A sealed UiState — not a nullable list or a
    // separate isLoading boolean — makes "is it loading or genuinely empty?" structurally unanswerable
    // ambiguity impossible: the type carries the answer. Init is Loading (the screen shows a spinner, not
    // the empty-state CTA) until load() resolves to Empty/Content; Error is reserved for an UNEXPECTED
    // throw, since every domain failure comes back as a ScheduleMutationResult.Rejected, never an exception.
    private val _uiState = MutableStateFlow<ScheduleUiState>(ScheduleUiState.Loading)

    /** The screen's load/empty/error/populated view, in presentation order (next-fire ascending). */
    val uiState: StateFlow<ScheduleUiState> = _uiState.asStateFlow()

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
     * The parent conversation is still created from the screen assistant id for this screen when needed.
     * The schedule's delegation target now comes from the draft (user picker), so this method never
     * rewrites [ScheduleDraft.targetAssistantId] anymore.
     */
    suspend fun createSchedule(draft: ScheduleDraft): ScheduleMutationResult = createMutex.withLock {
        val wasUnbound = _conversationId.value == null
        val conversationId = requireConversationId()
        val result = repository.create(conversationId, ScheduleOwner.USER, draft)
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

    /**
     * List the bound conversation's schedules and publish the result as a [ScheduleUiState] (SPEC.md M6).
     * An unbound screen has nothing to list yet, so it resolves to [ScheduleUiState.Empty]. An UNEXPECTED
     * exception from the repository (a fault, not a domain rejection — those never throw) resolves to
     * [ScheduleUiState.Error] so the screen shows an error instead of a misleading empty state or a crash.
     */
    suspend fun listSchedules(): List<ScheduleSnapshot> {
        _uiState.value = ScheduleUiState.Loading
        val conversationId = _conversationId.value ?: run {
            _uiState.value = ScheduleUiState.Empty
            return emptyList()
        }
        return try {
            repository.list(conversationId).also { publish(it) }
        } catch (e: Exception) {
            _uiState.value = ScheduleUiState.Error(e.message ?: "Failed to load schedules")
            emptyList()
        }
    }

    /** Delete a schedule through the shared repository path (scoped to the bound conversation). */
    suspend fun deleteSchedule(id: Uuid): ScheduleMutationResult {
        val conversationId = _conversationId.value
            ?: return ScheduleMutationResult.Rejected("no conversation bound")
        val result = repository.delete(conversationId, id)
        if (result is ScheduleMutationResult.Accepted) refresh(conversationId)
        return result
    }

    /**
     * Pause ([enabled] = false) or resume ([enabled] = true) a schedule through the shared repository
     * path (SPEC.md M5 / task T11), scoped to the bound conversation. The repository is the SINGLE
     * legality path — it re-checks the caps on resume and arms/cancels the WorkManager fire — so the VM
     * never flips `enabled` directly. On acceptance the list is re-published so the card observes the new
     * run-state; a [ScheduleMutationResult.Rejected] (e.g. a cap-breach resume) is returned for the card
     * to surface (it has no dialog, so the screen reverts the switch and toasts the reason).
     */
    suspend fun setScheduleEnabled(id: Uuid, enabled: Boolean): ScheduleMutationResult {
        val conversationId = _conversationId.value
            ?: return ScheduleMutationResult.Rejected("no conversation bound")
        val result = repository.setEnabled(conversationId, id, enabled)
        if (result is ScheduleMutationResult.Accepted) refresh(conversationId)
        return result
    }

    private suspend fun refresh(conversationId: Uuid) {
        publish(repository.list(conversationId))
    }

    /** Map a freshly listed snapshot set onto the screen's content/empty states (one mapping, no drift). */
    private fun publish(list: List<ScheduleSnapshot>) {
        _uiState.value = if (list.isEmpty()) ScheduleUiState.Empty else ScheduleUiState.Content(list)
    }

    // --- fire-and-forget UI entry points --------------------------------------------------------

    fun create(draft: ScheduleDraft, onResult: (ScheduleMutationResult) -> Unit = {}) {
        viewModelScope.launch { onResult(createSchedule(draft)) }
    }

    fun delete(id: Uuid, onResult: (ScheduleMutationResult) -> Unit = {}) {
        viewModelScope.launch { onResult(deleteSchedule(id)) }
    }

    fun setEnabled(id: Uuid, enabled: Boolean, onResult: (ScheduleMutationResult) -> Unit = {}) {
        viewModelScope.launch { onResult(setScheduleEnabled(id, enabled)) }
    }

    fun load() {
        viewModelScope.launch { listSchedules() }
    }
}
