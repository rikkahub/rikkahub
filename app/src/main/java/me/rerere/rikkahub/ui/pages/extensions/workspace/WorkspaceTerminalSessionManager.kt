package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.Context
import android.util.Log
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.AppScope
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns workspace terminal sessions independently from the terminal page lifecycle.
 *
 * A page only attaches a [com.termux.view.TerminalView] to the selected tab. Navigating away
 * therefore keeps every shell and its screen buffer alive; a session is finished only when its
 * tab is explicitly closed (or the shell exits by itself).
 */
class WorkspaceTerminalSessionManager internal constructor(
    context: Context,
    private val appScope: AppScope,
) {
    private val appContext = context.applicationContext
    private val workspaceStates = MutableStateFlow<Map<String, WorkspaceTerminalTabsState>>(emptyMap())
    private val nextTabId = AtomicLong(1)
    private val creationJobs = mutableMapOf<String, Job>()

    internal fun observeWorkspace(root: String): Flow<WorkspaceTerminalTabsState> =
        workspaceStates
            .map { states -> states[root] ?: WorkspaceTerminalTabsState() }
            .distinctUntilChanged()

    internal fun ensureSession(root: String) {
        launchCreateTab(root = root, onlyIfEmpty = true)
    }

    internal fun createTab(root: String) {
        launchCreateTab(root = root, onlyIfEmpty = false)
    }

    /**
     * Returns an existing UI tab, or creates one through the same path used by the terminal page.
     * Agent calls therefore operate on the exact PTY already visible to the user.
     */
    internal suspend fun ensureAgentSession(
        root: String,
        tabId: Long? = null,
        createNewTab: Boolean = false,
    ): WorkspaceTerminalAgentSession = withContext(Dispatchers.Main.immediate) {
        val current = currentState(root)
        val existing = if (tabId != null) {
            current.tabs.firstOrNull { it.id == tabId }
                ?: error("Terminal session not found: $tabId")
        } else {
            current.tabs.firstOrNull { it.id == current.selectedTabId }
                ?: current.tabs.firstOrNull()
        }
        if (existing != null && !createNewTab) return@withContext existing.toAgentSession()

        val previousIds = current.tabs.mapTo(mutableSetOf()) { it.id }
        launchCreateTab(root = root, onlyIfEmpty = false)
        val result = withTimeout(AGENT_SESSION_CREATE_TIMEOUT_MS) {
            workspaceStates
                .map { states -> states[root] ?: WorkspaceTerminalTabsState() }
                .first { state ->
                    state.tabs.any { it.id !in previousIds } ||
                        state.readiness == WorkspaceTerminalReadiness.NotInstalled ||
                        (!state.isCreating && !state.isCreating && state.tabs.isEmpty())
                }
        }
        result.tabs.firstOrNull { it.id !in previousIds }?.toAgentSession()
            ?: error("Workspace terminal is not ready")
    }

    internal suspend fun listAgentSessions(root: String): List<WorkspaceTerminalAgentSession> =
        withContext(Dispatchers.Main.immediate) {
            currentState(root).tabs.map { it.toAgentSession() }
        }

    internal suspend fun sendAgentInput(
        root: String,
        tabId: Long,
        input: String?,
        keys: List<String>,
        pressEnter: Boolean,
        waitFor: String? = null,
        timeoutMillis: Long = DEFAULT_AGENT_WAIT_TIMEOUT_MS,
    ): WorkspaceTerminalScreen = withContext(Dispatchers.Main.immediate) {
        val tab = findTab(root, tabId)
        check(tab.session.isRunning) { "Terminal session has exited: $tabId" }
        input?.takeIf { it.isNotEmpty() }?.let { tab.session.writeAgentText(it) }
        keys.forEach { key -> tab.session.writeAgentText(key.toTerminalInput()) }
        if (pressEnter) tab.session.writeAgentText("\r")
        awaitAgentScreen(tab, waitFor, timeoutMillis)
    }

    internal suspend fun readAgentScreen(
        root: String,
        tabId: Long,
        waitFor: String? = null,
        timeoutMillis: Long = DEFAULT_AGENT_WAIT_TIMEOUT_MS,
    ): WorkspaceTerminalScreen = withContext(Dispatchers.Main.immediate) {
        awaitAgentScreen(findTab(root, tabId), waitFor, timeoutMillis)
    }

    internal suspend fun killAgentSession(root: String, tabId: Long): Boolean =
        withContext(Dispatchers.Main.immediate) {
            if (currentState(root).tabs.none { it.id == tabId }) return@withContext false
            closeTab(root, tabId)
            true
        }

    internal fun selectTab(root: String, tabId: Long) {
        updateState(root) { state ->
            if (state.tabs.none { it.id == tabId }) state else state.copy(selectedTabId = tabId)
        }
    }

    internal fun closeTab(root: String, tabId: Long) {
        var closedTab: WorkspaceTerminalTab? = null
        updateState(root) { state ->
            val closedIndex = state.tabs.indexOfFirst { it.id == tabId }
            if (closedIndex < 0) return@updateState state

            closedTab = state.tabs[closedIndex]
            val remainingTabs = state.tabs.filterNot { it.id == tabId }
            val selectedTabId = if (state.selectedTabId == tabId) {
                remainingTabs.getOrNull(closedIndex)?.id
                    ?: remainingTabs.getOrNull(closedIndex - 1)?.id
            } else {
                state.selectedTabId
            }
            state.copy(
                tabs = remainingTabs,
                selectedTabId = selectedTabId,
            )
        }

        // Remove it from observable state before finishing so the finish callback cannot put it
        // back into the UI while the selected TerminalView is being disposed.
        closedTab?.let { tab ->
            tab.client.terminalView = null
            tab.session.finishIfRunning()
        }
    }

    /**
     * Stops all sessions owned by [root] before its rootfs is replaced or the workspace is deleted.
     */
    internal suspend fun closeWorkspace(root: String) = withContext(Dispatchers.Main.immediate) {
        // Wait for rootfs preparation to leave its IO section before callers delete or replace the
        // same files. CancellationException is deliberately rethrown by createTab().
        creationJobs[root]?.cancelAndJoin()

        val state = workspaceStates.getAndUpdate { states -> states - root }[root]
            ?: return@withContext
        state.tabs.forEach { tab ->
            tab.client.terminalView = null
            tab.session.finishIfRunning()
        }
    }

    private fun launchCreateTab(root: String, onlyIfEmpty: Boolean) {
        if (root in creationJobs) return

        lateinit var job: Job
        job = appScope.launch(start = CoroutineStart.LAZY) {
            try {
                createTab(root = root, onlyIfEmpty = onlyIfEmpty)
            } finally {
                creationJobs.remove(root, job)
            }
        }
        creationJobs[root] = job
        job.start()
    }

    private suspend fun createTab(root: String, onlyIfEmpty: Boolean) = withContext(Dispatchers.Main.immediate) {
        val initialState = currentState(root)
        if (initialState.isCreating || (onlyIfEmpty && initialState.tabs.isNotEmpty())) {
            return@withContext
        }
        updateState(root) { it.copy(isCreating = true) }

        val prepared = if (initialState.readiness == WorkspaceTerminalReadiness.Ready) {
            true
        } else {
            try {
                withContext(Dispatchers.IO) {
                    if (!workspaceRootfsReady(appContext, root)) {
                        false
                    } else {
                        prepareWorkspaceTerminalSession(appContext, root)
                        true
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "Failed to prepare terminal for workspace $root", error)
                false
            }
        }

        if (!prepared) {
            updateState(root) {
                it.copy(
                    readiness = WorkspaceTerminalReadiness.NotInstalled,
                    isCreating = false,
                )
            }
            return@withContext
        }

        val tabId = nextTabId.getAndIncrement()
        val tabNumber = currentState(root).nextTabNumber
        val client = WorkspaceTerminalSessionClient(appContext) {
            markFinished(root = root, tabId = tabId)
        }
        val session = runCatching {
            createWorkspaceTerminalSession(
                context = appContext,
                root = root,
                client = client,
            )
        }.onFailure { error ->
            Log.e(TAG, "Failed to create terminal for workspace $root", error)
        }.getOrNull()

        if (session == null) {
            updateState(root) { it.copy(isCreating = false) }
            return@withContext
        }

        // Agent calls can arrive before TerminalView has attached and supplied a size.
        // Give the emulator a stable initial buffer; TerminalView will resize it later.
        runCatching { session.initializeEmulator(DEFAULT_AGENT_COLUMNS, DEFAULT_AGENT_ROWS) }
            .onFailure { error ->
                Log.w(TAG, "Failed to initialize terminal emulator for workspace $root", error)
            }

        val tab = WorkspaceTerminalTab(
            id = tabId,
            number = tabNumber,
            session = session,
            client = client,
        )
        updateState(root) { state ->
            state.copy(
                tabs = state.tabs + tab,
                selectedTabId = tab.id,
                readiness = WorkspaceTerminalReadiness.Ready,
                isCreating = false,
                nextTabNumber = tabNumber + 1,
            )
        }
    }

    private fun markFinished(root: String, tabId: Long) {
        workspaceStates.update { states ->
            val state = states[root] ?: return@update states
            if (state.tabs.none { it.id == tabId }) return@update states

            states + (root to state.copy(
                tabs = state.tabs.map { tab ->
                    if (tab.id == tabId) tab.copy(finished = true) else tab
                },
            ))
        }
    }

    private fun currentState(root: String): WorkspaceTerminalTabsState =
        workspaceStates.value[root] ?: WorkspaceTerminalTabsState()

    private fun findTab(root: String, tabId: Long): WorkspaceTerminalTab =
        currentState(root).tabs.firstOrNull { it.id == tabId }
            ?: error("Terminal session not found: $tabId")

    private suspend fun awaitAgentScreen(
        tab: WorkspaceTerminalTab,
        waitFor: String?,
        timeoutMillis: Long,
    ): WorkspaceTerminalScreen {
        val timeout = timeoutMillis.coerceIn(0L, MAX_AGENT_WAIT_TIMEOUT_MS)
        var screen = tab.toAgentScreen()
        if (waitFor.isNullOrBlank()) return screen
        val deadline = System.currentTimeMillis() + timeout
        while (waitFor !in screen.screen && System.currentTimeMillis() < deadline) {
            delay(50)
            screen = tab.toAgentScreen()
        }
        return screen
    }

    private inline fun updateState(
        root: String,
        transform: (WorkspaceTerminalTabsState) -> WorkspaceTerminalTabsState,
    ) {
        workspaceStates.update { states ->
            states + (root to transform(states[root] ?: WorkspaceTerminalTabsState()))
        }
    }

    private companion object {
        const val TAG = "WorkspaceTerminalManager"
        const val DEFAULT_AGENT_COLUMNS = 120
        const val DEFAULT_AGENT_ROWS = 32
    }
}

internal data class WorkspaceTerminalAgentSession(
    val id: Long,
    val number: Int,
    val cwd: String,
    val running: Boolean,
    val finished: Boolean,
)

internal data class WorkspaceTerminalScreen(
    val session: WorkspaceTerminalAgentSession,
    val screen: String,
    val columns: Int,
    val rows: Int,
    val cursorRow: Int,
    val cursorColumn: Int,
)

private fun WorkspaceTerminalTab.toAgentSession() = WorkspaceTerminalAgentSession(
    id = id,
    number = number,
    cwd = session.getCwd().orEmpty(),
    running = session.isRunning,
    finished = finished,
)

private fun WorkspaceTerminalTab.toAgentScreen(): WorkspaceTerminalScreen {
    val emulator = session.getEmulator()
    val buffer = emulator.getScreen()
    return WorkspaceTerminalScreen(
        session = toAgentSession(),
        screen = buffer.getTranscriptTextWithFullLinesJoined().takeLast(MAX_AGENT_SCREEN_CHARS),
        columns = emulator.mColumns,
        rows = emulator.mRows,
        cursorRow = emulator.getCursorRow(),
        cursorColumn = emulator.getCursorCol(),
    )
}

private fun TerminalSession.writeAgentText(text: String) {
    val bytes = text.toByteArray(Charsets.UTF_8)
    write(bytes, 0, bytes.size)
}

private fun String.toTerminalInput(): String = when (trim().lowercase()) {
    "enter", "return" -> "\r"
    "tab" -> "\t"
    "esc", "escape" -> "\u001B"
    "backspace" -> "\u007F"
    "up" -> "\u001B[A"
    "down" -> "\u001B[B"
    "right" -> "\u001B[C"
    "left" -> "\u001B[D"
    "home" -> "\u001B[H"
    "end" -> "\u001B[F"
    "c-c" -> "\u0003"
    "c-d" -> "\u0004"
    "c-z" -> "\u001A"
    else -> if (startsWith("c-") && length == 3) {
        (this[2].uppercaseChar().code and 0x1F).toChar().toString()
    } else {
        this
    }
}

private const val MAX_AGENT_SCREEN_CHARS = 64 * 1024
private const val DEFAULT_AGENT_WAIT_TIMEOUT_MS = 10_000L
private const val MAX_AGENT_WAIT_TIMEOUT_MS = 120_000L
private const val AGENT_SESSION_CREATE_TIMEOUT_MS = 15_000L

internal data class WorkspaceTerminalTabsState(
    val tabs: List<WorkspaceTerminalTab> = emptyList(),
    val selectedTabId: Long? = null,
    val readiness: WorkspaceTerminalReadiness = WorkspaceTerminalReadiness.Loading,
    val isCreating: Boolean = false,
    val nextTabNumber: Int = 1,
)

internal data class WorkspaceTerminalTab(
    val id: Long,
    val number: Int,
    val session: TerminalSession,
    val client: WorkspaceTerminalSessionClient,
    val finished: Boolean = false,
)

internal enum class WorkspaceTerminalReadiness {
    Loading,
    Ready,
    NotInstalled,
}
