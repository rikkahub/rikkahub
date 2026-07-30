package me.rerere.rikkahub.ui.pages.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import me.rerere.rikkahub.data.db.entity.AgentApprovalEntity
import me.rerere.rikkahub.data.db.entity.AgentRunEntity
import me.rerere.rikkahub.data.db.entity.AgentStepEntity
import me.rerere.rikkahub.data.db.entity.AgentTraceEvent
import me.rerere.rikkahub.data.db.entity.ToolExecutionEntity
import me.rerere.rikkahub.data.repository.AgentRunRepository

sealed interface AgentRunDetailState {
    data object Closed : AgentRunDetailState
    data object Loading : AgentRunDetailState
    data object Missing : AgentRunDetailState
    data class Content(val detail: AgentRunDetail) : AgentRunDetailState
}

private data class AgentRunDetailPrimary(
    val run: AgentRunEntity?,
    val steps: List<AgentStepEntity>,
    val tools: List<ToolExecutionEntity>,
)

private data class AgentRunDetailSecondary(
    val approvals: List<AgentApprovalEntity>,
    val children: List<AgentRunEntity>,
    val traceEvents: List<AgentTraceEvent>,
)

/** Keeps the Run Center backed directly by Room flows so telemetry updates are reflected without polling. */
class AgentRunVM(
    private val conversationId: String,
    private val repository: AgentRunRepository,
) : ViewModel() {
    val latestRun: StateFlow<AgentRunEntity?> = repository.observeLatestRun(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val activeRun: StateFlow<AgentRunEntity?> = repository.observeActiveRun(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val selectedRunId = MutableStateFlow<String?>(null)
    val selectedRun: StateFlow<String?> = selectedRunId

    val detail: StateFlow<AgentRunDetailState> = selectedRunId.flatMapLatest { runId ->
        if (runId == null) {
            flowOf(AgentRunDetailState.Closed)
        } else {
            observeDetail(repository, runId)
                .map { detail ->
                    detail?.let(AgentRunDetailState::Content) ?: AgentRunDetailState.Missing
                }
                .onStart { emit(AgentRunDetailState.Loading) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AgentRunDetailState.Closed)

    val activeDetail: StateFlow<AgentRunDetail?> = combine(activeRun, selectedRunId) { run, selectedId ->
        when {
            run == null -> flowOf(null)
            run.id == selectedId -> detail.map { (it as? AgentRunDetailState.Content)?.detail }
            else -> observeDetail(repository, run.id)
        }
    }.flatMapLatest { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun openRun(id: String) {
        selectedRunId.value = id
    }

    fun closeRun() {
        selectedRunId.value = null
    }

    /** Content-free trace feed for Run Center consumers; presentation remains intentionally separate. */
    fun observeTraceEvents(runId: String): Flow<List<AgentTraceEvent>> = repository.observeTraceEvents(runId)

    private fun observeDetail(repository: AgentRunRepository, runId: String): Flow<AgentRunDetail?> {
        val primary = combine(
            repository.observeRun(runId),
            repository.observeSteps(runId),
            repository.observeToolExecutions(runId),
        ) { run, steps, tools -> AgentRunDetailPrimary(run, steps, tools) }
        val secondary = combine(
            repository.observeApprovals(runId),
            repository.observeChildRuns(runId),
            repository.observeTraceEvents(runId),
        ) { approvals, children, traceEvents -> AgentRunDetailSecondary(approvals, children, traceEvents) }
        return combine(primary, secondary) { first, second ->
            first.run?.let { AgentRunDetail(it, first.steps, first.tools, second.approvals, second.children, second.traceEvents) }
        }
    }
}
