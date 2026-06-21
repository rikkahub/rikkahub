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
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
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

    // Live rootfs-install progress for the sideload shell-controls UI (slice 6b). null = no install
    // in flight; the install button reads this together with the persisted shellStatus.
    private val _installProgress = MutableStateFlow<RootfsInstallProgress?>(null)
    val installProgress = _installProgress.asStateFlow()

    private val _installError = MutableStateFlow<String?>(null)
    val installError = _installError.asStateFlow()

    // Transient error from a file action (create/set-project) — surfaced as a toast by the page, then
    // dismissed. Kept separate from [WorkspaceDetailState.error] (which is the directory-listing error
    // shown as an inline card) so a failed "New Folder" doesn't blank the file list.
    private val _actionError = MutableStateFlow<String?>(null)
    val actionError = _actionError.asStateFlow()

    // The file currently open in the read-only viewer (null = closed).
    private val _fileView = MutableStateFlow<FileViewState?>(null)
    val fileView = _fileView.asStateFlow()

    // The in-flight rootfs install, if any. Held so installRootfs() can refuse re-entry while one is
    // running (a second install races the shared tmp archive/staging dir).
    private var installJob: Job? = null

    // Single in-flight directory listing. selectArea/open/goUp each retarget the browse location and
    // re-refresh; without cancelling the prior job two listings could complete out of order and the
    // later-committing one would overwrite the current area/path with stale entries. Holding and
    // cancelling the previous job makes the latest navigation the sole writer.
    private var refreshJob: Job? = null

    // One-shot guard: on the first workspace-row emission, open the FILES view at the project dir.
    private var seededInitialPath = false

    // The in-flight file-open read; cancelled when a newer file opens or the viewer closes so a stale
    // read can't repopulate/overwrite the current view.
    private var fileViewJob: Job? = null

    init {
        // Observe the row instead of read-modify-reloading it. setToolApproval (and any other writer)
        // mutates the row inside a DB transaction; Room re-emits the fresh row on this Flow, so
        // _state.workspace always reflects the latest committed write. The previous loadWorkspace()
        // launched an unordered viewModelScope coroutine per call, so rapid approval toggles raced and
        // whichever getById resumed last won the _state write — a stale snapshot on the switches. A
        // single collected Flow has exactly one writer, so that out-of-order-write race cannot occur.
        repository.getByIdFlow(id)
            .onEach { workspace ->
                _state.update { foldWorkspaceRow(it, workspace) }
                // On the first row load, open the FILES view at the project dir (if one is set) so the
                // browser starts where the agent works, not at the root. Guarded so it never overrides
                // later user navigation.
                if (!seededInitialPath && workspace != null) {
                    seededInitialPath = true
                    val projectDir = workspace.workingDir
                    if (state.value.area == WorkspaceStorageArea.FILES &&
                        state.value.path.isBlank() &&
                        projectDir.isNotBlank()
                    ) {
                        browseTo(projectDir)
                    }
                }
            }
            .launchIn(viewModelScope)
        refresh()
    }

    fun selectArea(area: WorkspaceStorageArea) {
        // Re-entering FILES lands on the project dir (the working_dir seed) instead of the root, so a
        // round-trip through the rootfs tab keeps the user where their project is.
        val seed = if (area == WorkspaceStorageArea.FILES) {
            state.value.workspace?.workingDir.orEmpty()
        } else {
            ""
        }
        _state.update {
            it.copy(
                area = area,
                path = seed,
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

    /** Jump directly to a known FILES-relative folder path (breadcrumb navigation); "" == root. */
    fun browseTo(path: String) {
        if (path == state.value.path) return
        _state.update { it.copy(path = path, entries = emptyList(), error = null) }
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

    // Resolve a new entry name against the currently-browsed FILES path. Folder/file creation only
    // applies to the FILES area (the project tree); the LINUX rootfs is managed by the installer.
    // The name must be a single path segment: reject separators and `.`/`..` so a typed `../x` can't
    // create an entry outside the directory the user is looking at (resolvePath already bounds it to
    // the workspace root, but New file/folder must stay a child of the current dir).
    private fun childPath(name: String): String {
        val clean = name.trim()
        require(
            clean.isNotEmpty() && clean != "." && clean != ".." &&
                !clean.contains('/') && !clean.contains('\\')
        ) { "Name must be a single folder/file name without path separators" }
        val base = state.value.path
        return if (base.isBlank()) clean else "$base/$clean"
    }

    fun createFolder(name: String) {
        if (name.isBlank() || state.value.area != WorkspaceStorageArea.FILES) return
        launchVm(onError = { _actionError.value = it.message ?: "Failed to create folder" }) {
            repository.createFolder(id, childPath(name))
            refresh()
        }
    }

    fun createFile(name: String) {
        if (name.isBlank() || state.value.area != WorkspaceStorageArea.FILES) return
        launchVm(onError = { _actionError.value = it.message ?: "Failed to create file" }) {
            // Empty file, overwrite=false so an existing name surfaces a clear error instead of clobbering.
            repository.writeText(id, childPath(name), text = "", overwrite = false)
            refresh()
        }
    }

    fun deleteEntry(entry: WorkspaceFileEntry) {
        launchVm(onError = { _actionError.value = it.message ?: "Failed to delete" }) {
            repository.deleteFile(id, state.value.area, entry.path, recursive = entry.isDirectory)
            refresh()
        }
    }

    /** Open a file in the read-only viewer. Binary/non-text content is reported, never dumped as garbage. */
    fun openFile(entry: WorkspaceFileEntry) {
        if (entry.isDirectory || state.value.area != WorkspaceStorageArea.FILES) return
        // Supersede any in-flight open so a slow read can't land after the user closed the sheet or
        // opened a different file.
        fileViewJob?.cancel()
        if (isLikelyBinaryName(entry.name)) {
            _fileView.value = FileViewState(name = entry.name, path = entry.path, content = null, isBinary = true)
            return
        }
        _fileView.value = FileViewState(name = entry.name, path = entry.path, loading = true)
        fileViewJob = launchVm(onError = {
            _fileView.value = null
            // readText throws on a too-large file (maxReadBytes) — surface that instead of a blank viewer.
            _actionError.value = it.message ?: "Failed to open file"
        }) {
            val text = repository.readText(id, entry.path)
            // A NUL byte is the cheap, reliable binary tell that the extension allowlist missed.
            val binary = text.contains('\u0000')
            _fileView.value = FileViewState(
                name = entry.name,
                path = entry.path,
                content = if (binary) null else text,
                isBinary = binary,
            )
        }
    }

    /** Write edited text back to the open file (FILES area), then reflect it in the viewer + listing. */
    fun saveFile(text: String) {
        val current = _fileView.value ?: return
        if (current.isBinary || current.loading || current.path.isBlank()) return
        launchVm(onError = { _actionError.value = it.message ?: "Failed to save file" }) {
            repository.writeText(id, current.path, text, overwrite = true)
            // Only reflect the save if the same file is still open (the sheet may have been closed or
            // switched to another file while writing).
            if (_fileView.value?.path == current.path) {
                _fileView.value = current.copy(content = text)
            }
            refresh()
        }
    }

    fun closeFile() {
        fileViewJob?.cancel()
        _fileView.value = null
    }

    /** Designate the currently-browsed FILES folder as the workspace project dir (the agent's cwd seed). */
    fun setCurrentAsProjectDir() {
        if (state.value.area != WorkspaceStorageArea.FILES) return
        val target = state.value.path
        launchVm(onError = { _actionError.value = it.message ?: "Failed to set project directory" }) {
            // The row re-emits via getByIdFlow, so state.workspace.workingDir updates without a reload.
            repository.setWorkingDir(id, target)
        }
    }

    /** Clear the project dir back to the files root (unset). */
    fun clearProjectDir() {
        launchVm(onError = { _actionError.value = it.message ?: "Failed to clear project directory" }) {
            repository.resetWorkingDir(id)
        }
    }

    fun dismissActionError() {
        _actionError.value = null
    }

    fun setToolApproval(toolName: String, needsApproval: Boolean) {
        viewModelScope.launch {
            val workspace = state.value.workspace ?: return@launch
            // No reload after the write: the getByIdFlow collector in init re-emits the freshly
            // committed row, so _state.workspace updates from the single Flow writer.
            repository.setToolApproval(workspace.id, toolName, needsApproval)
        }
    }

    fun setShellEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val workspace = state.value.workspace ?: return@launch
            // No reload: the getByIdFlow collector re-emits the committed row (single Flow writer).
            repository.setShellEnabled(workspace.id, enabled)
        }
    }

    fun installRootfs(url: String) {
        val workspace = state.value.workspace ?: return
        // Re-entry guard: a second install for the same workspace would race the first over the shared
        // tmp/rootfs.tar.gz + staging dir (corrupting the download) and double-flip the row to
        // INSTALLING. VM methods run on the main thread, so this check-then-assign is atomic — the
        // UI-disabled button is only advisory; this Job guard is the real one.
        if (installJob?.isActive == true) return
        // launchVm (CoroutineUtils, pinned by VmSafeLaunchTest) rethrows CancellationException so a
        // cancelled install is NEVER captured into _installError, and routes only recoverable
        // throwables to onError — replacing upstream's runCatching{}.onFailure which swallowed
        // cancellation. The repository.installRootfs failure path already rethrows after flipping the
        // row to BROKEN, so the error surfaces here.
        installJob = launchVm(
            onError = { error -> _installError.value = error.message ?: "Rootfs install failed" },
        ) {
            _installError.value = null
            _installProgress.value = RootfsInstallProgress(stage = RootfsInstallStage.DOWNLOADING)
            try {
                repository.installRootfs(workspace.id, url) { progress ->
                    _installProgress.value = progress
                }
            } finally {
                // Clear progress on EVERY terminal path (success, recoverable failure, OR cancellation
                // unwinding through this finally) so the button never sticks in the installing state.
                _installProgress.value = null
            }
        }
    }

    fun dismissInstallError() {
        _installError.value = null
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

/** State of the read-only file viewer: text [content], or [isBinary] when the file isn't human-readable. */
data class FileViewState(
    val name: String,
    val path: String = "",
    val content: String? = null,
    val isBinary: Boolean = false,
    val loading: Boolean = false,
)

// Extensions whose content is not human-readable text — skip reading them and report a binary file
// instead of dumping garbage into the viewer. A NUL-byte content check (openFile) catches the rest.
private val BINARY_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "svgz", "pdf", "zip", "gz", "tar", "tgz",
    "7z", "rar", "jar", "apk", "aab", "so", "o", "a", "exe", "dll", "bin", "class", "dex", "wasm",
    "mp3", "mp4", "m4a", "wav", "ogg", "flac", "avi", "mov", "mkv", "ttf", "otf", "woff", "woff2",
    "eot", "db", "sqlite", "dat",
)

private fun isLikelyBinaryName(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in BINARY_EXTENSIONS
