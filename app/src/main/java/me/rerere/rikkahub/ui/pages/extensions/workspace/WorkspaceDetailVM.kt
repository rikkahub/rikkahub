package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.launchVm
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea

// Slice 6a: management half only — read-only file browse + per-tool approval toggles. The terminal,
// rootfs install, shell-enable toggle, and import/export/delete file write operations are slice 6b /
// out of scope (the sideload flavor seam fills the shell controls). [error] carries the raw exception
// message; the page maps a null fallback to a localized string.
class WorkspaceDetailVM(
    private val id: String,
    private val repository: WorkspaceRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(WorkspaceDetailState())
    val state = _state.asStateFlow()

    // Single in-flight directory listing. selectArea/open/goUp each retarget the browse location and
    // re-refresh; without cancelling the prior job two listings could complete out of order and the
    // later-committing one would overwrite the current area/path with stale entries. Holding and
    // cancelling the previous job makes the latest navigation the sole writer.
    private var refreshJob: Job? = null

    init {
        // Observe the row instead of read-modify-reloading it. setToolApproval (and any other writer)
        // mutates the row inside a DB transaction; Room re-emits the fresh row on this Flow, so
        // _state.workspace always reflects the latest committed write. The previous loadWorkspace()
        // launched an unordered viewModelScope coroutine per call, so rapid approval toggles raced and
        // whichever getById resumed last won the _state write — a stale snapshot on the switches. A
        // single collected Flow has exactly one writer, so that out-of-order-write race cannot occur.
        repository.getByIdFlow(id)
            .onEach { workspace -> _state.update { foldWorkspaceRow(it, workspace) } }
            .launchIn(viewModelScope)
        refresh()
    }

    fun selectArea(area: WorkspaceStorageArea) {
        _state.update {
            it.copy(
                area = area,
                path = "",
                entries = emptyList(),
                error = null,
            )
        }
        refresh()
    }

    fun open(entry: WorkspaceFileEntry) {
        if (!entry.isDirectory) return
        _state.update { it.copy(path = entry.path, entries = emptyList(), error = null) }
        refresh()
    }

    fun goUp() {
        val path = state.value.path
        if (path.isBlank()) return
        _state.update {
            it.copy(
                path = path.substringBeforeLast('/', missingDelimiterValue = ""),
                entries = emptyList(),
                error = null,
            )
        }
        refresh()
    }

    fun refresh() {
        // launchVm rethrows CancellationException (so a superseding navigation that cancels this job
        // is never swallowed into UI error state) and routes only recoverable throwables to onError —
        // the repo's canonical VM guard (CoroutineUtils.launchVm, pinned by VmSafeLaunchTest).
        refreshJob?.cancel()
        refreshJob = launchVm(
            onError = { error ->
                _state.update {
                    it.copy(
                        entries = emptyList(),
                        loading = false,
                        error = error.message,
                    )
                }
            },
        ) {
            _state.update { it.copy(loading = true, error = null) }
            val entries = repository.listFiles(
                id = id,
                area = state.value.area,
                path = state.value.path,
            )
            _state.update { it.copy(entries = entries, loading = false) }
        }
    }

    fun setToolApproval(toolName: String, needsApproval: Boolean) {
        viewModelScope.launch {
            val workspace = state.value.workspace ?: return@launch
            // No reload after the write: the getByIdFlow collector in init re-emits the freshly
            // committed row, so _state.workspace updates from the single Flow writer.
            repository.setToolApproval(workspace.id, toolName, needsApproval)
        }
    }
}

/**
 * Fold one observed workspace-row emission into the browse state, replacing ONLY the `workspace`
 * field and leaving the area/path/entries browse state untouched. Pure so the row-observation
 * single-writer contract is unit-testable on the JVM without viewModelScope/Room (see
 * WorkspaceRowObserveTest): the collector applies emissions in the order the row Flow delivers them,
 * so the final state always equals the latest emission — the out-of-order-write race the old
 * per-toggle getById reload allowed cannot occur with a single ordered Flow writer.
 */
internal fun foldWorkspaceRow(
    state: WorkspaceDetailState,
    workspace: WorkspaceEntity?,
): WorkspaceDetailState = state.copy(workspace = workspace)

data class WorkspaceDetailState(
    val workspace: WorkspaceEntity? = null,
    val area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    val path: String = "",
    val entries: List<WorkspaceFileEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)
