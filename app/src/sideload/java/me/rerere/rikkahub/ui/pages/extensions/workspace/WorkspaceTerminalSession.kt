package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import me.rerere.workspace.RootfsPatchOptions
import me.rerere.workspace.RootfsPatcher
import me.rerere.workspace.WorkspaceConfig
import me.rerere.workspace.WorkspaceCwdPolicy
import me.rerere.workspace.WorkspaceFileSystem
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.seededRelativeCwd
import java.io.File

/**
 * The interactive terminal's initial PRoot `-w`, derived through the ONE central cwd mapping so it is
 * the IDENTICAL normalized value the LLM exec sink uses for the same workspace (issue #282, W-I6).
 * Both this and `WorkspaceManager.executeCommand`'s ABSENT branch reduce to
 * `WorkspaceCwdPolicy.toShellPath(seededRelativeCwd(filesDir, workingDir))`; the terminal has no
 * per-call override, so its cwd is the SEED from the workspace `working_dir` (an unset row resolves to
 * the files root, the project working directory default). This is a snapshot at session-open time —
 * runtime `cd` drift is never written back (OSC 7 sync is out of scope, W-S3).
 */
internal fun workspaceTerminalCwd(filesDir: File, workingDir: String): String =
    WorkspaceCwdPolicy.toShellPath(seededRelativeCwd(filesDir, workingDir))

internal fun createWorkspaceTerminalSession(
    context: Context,
    root: String,
    workingDir: String,
    client: TerminalSessionClient,
): TerminalSession {
    // The terminal resolves directories directly (it reimplements the interactive PRoot launch), so
    // it MUST apply the same root-name validation WorkspaceManager enforces — a stored root is just
    // text and a corrupt/imported row could otherwise path-traverse out of the workspaces dir.
    require(WorkspaceManager.isValidRoot(root)) { "Invalid workspace root: $root" }
    val appContext = context.applicationContext
    val workspaceDir = File(File(appContext.filesDir, "workspaces"), root)
    val filesDir = File(workspaceDir, "files").apply { mkdirs() }
    val linuxDir = File(workspaceDir, "linux")
    val tempDir = File(workspaceDir, "tmp").apply { mkdirs() }
    val nativeLibraryDir = File(appContext.applicationInfo.nativeLibraryDir)
    val proot = File(nativeLibraryDir, "libproot_exec.so")
    val loader = File(nativeLibraryDir, "libproot_loader.so")
    RootfsPatcher().patch(
        linuxDir,
        RootfsPatchOptions(nameservers = appContext.activeDnsServers())
    )

    // Materialize the seed working dir so PRoot's `-w` (below) lands in an EXISTING directory: the
    // default project dir (.poci/scratch) the caller resolves an unset workspace to may not have been
    // created by a manager-backed op for this workspace yet, and PRoot fails to chdir into a missing
    // `-w`. Goes through the SAME containment-checked [WorkspaceFileSystem.ensureDir] the manager uses
    // (not a raw mkdirs) so an in-workspace symlink can't make the create escape the files root; blank
    // resolves to filesDir (already created above), so it is a no-op then.
    WorkspaceFileSystem(WorkspaceConfig()).ensureDir(filesDir, workingDir)

    // The initial `-w` is the seed resolved through the central policy (W-I6) — NOT a hard-coded
    // `/workspace`. seededRelativeCwd resolves the working_dir seed (blank => the files root) so the
    // shell lands in an existing directory. The bind-mount target stays the workspace ROOT alias; only
    // the working directory differs.
    val initialCwd = workspaceTerminalCwd(filesDir, workingDir)
    val args = mutableListOf(
        "--root-id",
        "--link2symlink",
        "--kill-on-exit",
        "-r",
        linuxDir.absolutePath,
        "-w",
        initialCwd,
        "-b",
        "${filesDir.absolutePath}:${WorkspaceCwdPolicy.WORKSPACE_DIR}",
    )
    listOf("/dev", "/proc", "/sys").forEach { path ->
        if (File(path).exists()) {
            args += "-b"
            args += path
        }
    }
    args += listOf(
        "/usr/bin/env",
        "-i",
        "HOME=/root",
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "TERM=xterm-256color",
        "LANG=C.UTF-8",
        "LC_ALL=C.UTF-8",
        "USER=root",
        "SHELL=/bin/bash",
        "/bin/bash",
    )

    val env = arrayOf(
        "PROOT_LOADER=${loader.absolutePath}",
        "PROOT_TMP_DIR=${tempDir.absolutePath}",
        "TMPDIR=${tempDir.absolutePath}",
    )

    return TerminalSession(
        proot.absolutePath,
        filesDir.absolutePath,
        args.toTypedArray(),
        env,
        2_000,
        client,
    ).apply {
        mSessionName = root
    }
}

internal fun workspaceRootfsReady(context: Context, root: String): Boolean {
    if (!WorkspaceManager.isValidRoot(root)) return false
    val linuxDir = File(File(File(context.applicationContext.filesDir, "workspaces"), root), "linux")
    return linuxDir.isDirectory && File(linuxDir, "bin/sh").isFile
}

internal class WorkspaceTerminalSessionClient(
    private val context: Context,
    private val onFinished: () -> Unit,
) : TerminalSessionClient {
    var terminalView: TerminalView? = null

    override fun onTextChanged(changedSession: TerminalSession) {
        terminalView?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) = Unit

    override fun onSessionFinished(finishedSession: TerminalSession) {
        terminalView?.onScreenUpdated()
        onFinished()
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?: return
        val bytes = text.toByteArray()
        session.write(bytes, 0, bytes.size)
    }

    override fun onBell(session: TerminalSession) = Unit

    override fun onColorsChanged(session: TerminalSession) {
        terminalView?.invalidate()
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        terminalView?.invalidate()
    }

    override fun getTerminalCursorStyle(): Int =
        TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, "Terminal error", e)
    }
}

internal class WorkspaceTerminalViewClient(
    private val context: Context,
) : TerminalViewClient {
    var terminalView: TerminalView? = null
    var controlDown: Boolean = false
    var altDown: Boolean = false

    override fun onScale(scale: Float): Float = scale.coerceIn(0.8f, 1.25f)

    override fun onSingleTapUp(e: MotionEvent) {
        focusAndShowKeyboard()
    }

    fun focusAndShowKeyboard() {
        val view = terminalView ?: return
        val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        view.post {
            view.requestFocus()
            // The WindowInsetsController replacement needs API 30 / a Window handle; this client
            // only holds the view and minSdk is 26, so the implicit-show flag must stay.
            @Suppress("DEPRECATION")
            inputMethodManager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = controlDown

    override fun readAltKey(): Boolean = altDown

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

    override fun onEmulatorSet() = Unit

    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, "Terminal view error", e)
    }
}

private fun Context.activeDnsServers(): List<String> {
    val connectivityManager =
        getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return emptyList()
    val network = connectivityManager.activeNetwork ?: return emptyList()
    return connectivityManager.getLinkProperties(network)
        ?.dnsServers
        ?.mapNotNull { it.hostAddress }
        .orEmpty()
}
