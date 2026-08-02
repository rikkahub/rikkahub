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
import kotlinx.coroutines.flow.update
import me.rerere.rikkahub.data.db.entity.AgentApprovalEntity
import me.rerere.rikkahub.data.db.entity.AgentRunEntity
import me.rerere.rikkahub.data.repository.AgentRunRepository

sealed interface AgentRunDetailState {
    val runId: String?
    val canNavigateBack: Boolean
    val navigationDepth: Int

    data object Closed : AgentRunDetailState {
        override val runId: String? = null
        override val canNavigateBack: Boolean = false
        override val navigationDepth: Int = 0
    }

    data class Loading(
        override val runId: String,
        override val canNavigateBack: Boolean = false,
        override val navigationDepth: Int = 1,
    ) : AgentRunDetailState

    data class Missing(
        override val runId: String,
        override val canNavigateBack: Boolean = false,
        override val navigationDepth: Int = 1,
    ) : AgentRunDetailState

    data class Content(
        val detail: AgentRunDetail,
        override val canNavigateBack: Boolean = false,
        override val navigationDepth: Int = 1,
    ) : AgentRunDetailState {
        override val runId: String = detail.run.id
    }
}

internal data class AgentRunNavigation(
    private val path: List<String> = emptyList(),
) {
    val selectedRunId: String? get() = path.lastOrNull()
    val canNavigateBack: Boolean get() = path.size > 1
    val navigationDepth: Int get() = path.size

    fun openRoot(runId: String): AgentRunNavigation = AgentRunNavigation(listOf(runId))

    fun openChild(runId: String): AgentRunNavigation {
        if (runId == selectedRunId) return this
        val existingIndex = path.indexOf(runId)
        return if (existingIndex >= 0) {
            AgentRunNavigation(path.take(existingIndex + 1))
        } else {
            AgentRunNavigation(path + runId)
        }
    }

    fun back(): AgentRunNavigation = if (canNavigateBack) {
        AgentRunNavigation(path.dropLast(1))
    } else {
        this
    }

    fun close(): AgentRunNavigation = AgentRunNavigation()
}

/** Keeps the compact Run Center backed directly by lifecycle and approval Room flows. */
class AgentRunVM(
    private val conversationId: String,
    private val repository: AgentRunRepository,
) : ViewModel() {
    val latestRun: StateFlow<AgentRunEntity?> = repository.observeLatestRun(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val activeRun: StateFlow<AgentRunEntity?> = repository.observeActiveRun(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val navigation = MutableStateFlow(AgentRunNavigation())
    val selectedRun: StateFlow<String?> = navigation
        .map { it.selectedRunId }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val detail: StateFlow<AgentRunDetailState> = navigation.flatMapLatest { destination ->
        val runId = destination.selectedRunId
        if (runId == null) {
            flowOf(AgentRunDetailState.Closed)
        } else {
            observeDetail(repository, runId)
                .map { detail ->
                    detail?.let {
                        AgentRunDetailState.Content(
                            detail = it,
                            canNavigateBack = destination.canNavigateBack,
                            navigationDepth = destination.navigationDepth,
                        )
                    } ?: AgentRunDetailState.Missing(
                        runId = runId,
                        canNavigateBack = destination.canNavigateBack,
                        navigationDepth = destination.navigationDepth,
                    )
                }
                .onStart {
                    emit(
                        AgentRunDetailState.Loading(
                            runId = runId,
                            canNavigateBack = destination.canNavigateBack,
                            navigationDepth = destination.navigationDepth,
                        ),
                    )
                }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AgentRunDetailState.Closed)

    val activeDetail: StateFlow<AgentRunDetail?> = combine(activeRun, selectedRun) { run, selectedId ->
        when {
            run == null -> flowOf(null)
            run.id == selectedId -> detail.map { (it as? AgentRunDetailState.Content)?.detail }
            else -> observeDetail(repository, run.id)
        }
    }.flatMapLatest { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun openRun(id: String) {
        navigation.update { it.openRoot(id) }
    }

    fun openChildRun(id: String) {
        navigation.update { it.openChild(id) }
    }

    fun navigateBack() {
        navigation.update(AgentRunNavigation::back)
    }

    fun closeRun() {
        navigation.update(AgentRunNavigation::close)
    }

    private fun observeDetail(repository: AgentRunRepository, runId: String): Flow<AgentRunDetail?> {
        return combine(
            repository.observeRun(runId),
            repository.observeApprovals(runId),
        ) { run, approvals ->
            run?.let {
                AgentRunDetail(
                    it,
                    steps = emptyList(),
                    tools = emptyList(),
                    approvals = approvals,
                )
            }
        }
    }
}
