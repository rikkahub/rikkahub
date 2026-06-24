package me.rerere.rikkahub.data.repository

import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.ai.shellrun.ShellCwdDecision
import me.rerere.rikkahub.data.ai.shellrun.ShellCwdOutcome
import me.rerere.rikkahub.data.ai.shellrun.ShellCwdStatus
import me.rerere.rikkahub.data.ai.shellrun.ShellCwdTracker
import me.rerere.rikkahub.data.ai.shellrun.ShellRunCoordinator
import me.rerere.rikkahub.data.ai.shellrun.ShellRunRequest
import me.rerere.rikkahub.data.ai.shellrun.ShellRunResult
import me.rerere.rikkahub.data.ai.shellrun.ShellRunStore
import me.rerere.rikkahub.data.ai.shellrun.decideTrackedCwd
import me.rerere.rikkahub.data.ai.shellrun.extractFinalCwd
import me.rerere.rikkahub.data.ai.shellrun.newCwdCaptureToken
import me.rerere.rikkahub.data.db.entity.ShellRunEntity
import me.rerere.rikkahub.data.ai.tools.WorkspaceToolDefaultApprovals
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.common.json.JsonInstant
import me.rerere.workspace.DEFAULT_OUTPUT_SIZE_CAP_BYTES
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceCwdPolicy
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceSearchMatch
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.workspace.seededRelativeCwd
import java.io.File
import kotlin.uuid.Uuid

class WorkspaceRepository(
    private val dao: WorkspaceDAO,
    private val manager: WorkspaceManager,
    private val rootfsInstaller: RootfsInstaller,
    private val settingsStore: SettingsStore,
    private val db: AppDatabase,
    private val shellRunCoordinator: ShellRunCoordinator,
    // Durable shell-run rows (issue #291). The tail seam looks a run up by taskId here and verifies
    // it belongs to the calling workspace+conversation before reading its output file.
    private val shellRunStore: ShellRunStore,
    // App-private directory the detachable shell runs stream their output to (productDecision #3:
    // <cacheDir>/workspace-shell-tasks/<taskId>.output — chosen over /workspace/.rikkahub/tasks
    // because the latter is shell-mutable). Read back only via the workspace_shell_tail tool.
    private val shellTasksDir: File,
    // Per-conversation, in-memory drifting shell cwd (project-jailed). Used only by the blocking
    // [executeTrackedCommand] LLM-shell path; the detached path and the interactive terminal are unaffected.
    private val shellCwdTracker: ShellCwdTracker,
) {
    fun listFlow(): Flow<List<WorkspaceEntity>> = dao.listFlow()

    suspend fun getById(id: String): WorkspaceEntity? = dao.getById(id)

    fun getByIdFlow(id: String): Flow<WorkspaceEntity?> = dao.getByIdFlow(id)

    suspend fun create(name: String): WorkspaceEntity {
        val id = Uuid.random().toString()
        val now = System.currentTimeMillis()
        val workspace = WorkspaceEntity(
            id = id,
            name = name.ifBlank { "Workspace" },
            root = id,
            shellEnabled = false,
            shellStatus = WorkspaceShellStatus.DISABLED.name,
            createdAt = now,
            updatedAt = now,
            lastAccessAt = null,
        )
        manager.ensureWorkspace(workspace.root)
        dao.upsert(workspace)
        return workspace
    }

    // I-ATOMIC (issue #197 slice 6a, finding #5): the read-modify-write mutators below read the row
    // then upsert a `.copy(...)` that depends on the read. With the per-tool approval toggles and
    // rename now multi-writer in the management UI, two concurrent writers could interleave the
    // read of one and the write of the other and lose an update. Each mutator wraps its read AND its
    // dependent write in a single `db.withTransaction { ... }` so concurrent writers serialize on
    // the SQLite transaction instead of clobbering each other.
    suspend fun rename(id: String, name: String): Boolean = db.withTransaction {
        val workspace = dao.getById(id) ?: return@withTransaction false
        dao.upsert(
            workspace.copy(
                name = name.ifBlank { workspace.name },
                updatedAt = System.currentTimeMillis(),
            )
        )
        true
    }

    suspend fun setShellEnabled(id: String, enabled: Boolean): Boolean {
        // hasRootfs / ensureWorkspace touch the PRoot manager (filesystem IO), kept OUTSIDE the
        // transaction; only the row read + status write are atomic.
        val root = (dao.getById(id) ?: return false).root
        manager.ensureWorkspace(root)
        val status = if (enabled) {
            if (manager.hasRootfs(root)) WorkspaceShellStatus.READY.name else WorkspaceShellStatus.BROKEN.name
        } else {
            WorkspaceShellStatus.DISABLED.name
        }
        return db.withTransaction {
            val workspace = dao.getById(id) ?: return@withTransaction false
            dao.upsert(
                workspace.copy(
                    shellEnabled = enabled,
                    shellStatus = status,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            true
        }
    }

    suspend fun setToolApproval(id: String, toolName: String, needsApproval: Boolean): Boolean =
        db.withTransaction {
            val workspace = dao.getById(id) ?: return@withTransaction false
            dao.upsert(
                workspace.copy(
                    toolApprovals = mergeToolApprovalOverride(workspace.toolApprovals, toolName, needsApproval),
                    updatedAt = System.currentTimeMillis(),
                )
            )
            true
        }

    /**
     * Persist a workspace-level shell working directory SEED (issue #282). The stored value is the
     * NORMALIZED FILES-relative path ([WorkspaceCwdPolicy.normalize]), so a `..`/NUL/absolute escape is
     * rejected at the source and the round-trip `setWorkingDir(p); getById().workingDir == normalize(p)`
     * holds (W-R1). Read-modify-write inside a single transaction, matching the [rename]/[setToolApproval]
     * lost-update guard (I-ATOMIC).
     */
    suspend fun setWorkingDir(id: String, path: String): Boolean = db.withTransaction {
        val workspace = dao.getById(id) ?: return@withTransaction false
        dao.upsert(
            workspace.copy(
                workingDir = WorkspaceCwdPolicy.normalize(path),
                updatedAt = System.currentTimeMillis(),
            )
        )
        true
    }

    /**
     * Clear the working-directory seed back to the UNSET sentinel `""` (issue #282, W-D4): the
     * resolver then falls back to the files root (the project working directory default). Resetting
     * twice is a no-op — `""` is already the unset value — so this is idempotent.
     */
    suspend fun resetWorkingDir(id: String): Boolean = db.withTransaction {
        val workspace = dao.getById(id) ?: return@withTransaction false
        dao.upsert(
            workspace.copy(
                workingDir = "",
                updatedAt = System.currentTimeMillis(),
            )
        )
        true
    }

    suspend fun installRootfs(
        id: String,
        url: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ): Boolean {
        val flipped = db.withTransaction {
            val workspace = dao.getById(id) ?: return@withTransaction false
            dao.upsert(
                workspace.copy(
                    shellEnabled = true,
                    shellStatus = WorkspaceShellStatus.INSTALLING.name,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            true
        }
        if (!flipped) return false
        return runCatching {
            withContext(Dispatchers.IO) {
                rootfsInstaller.install(dao.getById(id)?.root ?: error("Workspace not found: $id"), url, onProgress)
            }
        }.fold(
            onSuccess = {
                applyShellStatus(id, WorkspaceShellStatus.READY.name)
                true
            },
            onFailure = {
                // Log the host only — the full URL may carry a `?key=`/token query the operator
                // pasted; the host is enough to diagnose a failed install without leaking it.
                val host = runCatching { java.net.URI(url).host }.getOrNull() ?: "?"
                Log.e(TAG, "installRootfs failed: workspace=$id, host=$host", it)
                applyShellStatus(id, WorkspaceShellStatus.BROKEN.name)
                throw it
            },
        )
    }

    private suspend fun applyShellStatus(id: String, status: String): Boolean = db.withTransaction {
        val workspace = dao.getById(id) ?: return@withTransaction false
        dao.upsert(
            workspace.copy(
                shellEnabled = true,
                shellStatus = status,
                updatedAt = System.currentTimeMillis(),
            )
        )
        true
    }

    suspend fun listFiles(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext emptyList()
        manager.ensureWorkspace(workspace.root)
        manager.listFiles(workspace.root, path, area)
    }

    /**
     * Find files in the FILES area by glob [pattern], recursively from the files root. The pattern is
     * matched against each file's files-root-relative path (so a recursive globstar pattern finds every
     * Kotlin file). Containment + the result cap live in the file-system layer. FILES area only.
     */
    suspend fun glob(id: String, pattern: String): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext emptyList()
        manager.ensureWorkspace(workspace.root)
        manager.glob(workspace.root, pattern)
    }

    /**
     * Search FILES-area file contents for [query] (literal substring by default, a regex when [regex]).
     * [includeGlob] optionally restricts which files are searched (matched against the files-root-relative
     * path). Containment, the per-file size cap, and the result cap live in the file-system layer.
     */
    suspend fun grep(
        id: String,
        query: String,
        regex: Boolean,
        ignoreCase: Boolean,
        includeGlob: String?,
    ): List<WorkspaceSearchMatch> = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext emptyList()
        manager.ensureWorkspace(workspace.root)
        manager.grep(workspace.root, query, regex = regex, ignoreCase = ignoreCase, includeGlob = includeGlob)
    }

    suspend fun resolvedWorkingDir(id: String): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext ""
        manager.ensureWorkspace(workspace.root)
        seededRelativeCwd(
            filesDir = manager.filesDir(workspace.root),
            workingDir = workspace.workingDir,
        )
    }

    /**
     * The workspace's stored project dir — the files-root-relative `working_dir` seed, "" if unset or
     * the row is missing. The file tools fetch this at CALL time so a model-supplied relative path can
     * be resolved against the SAME project dir the shell uses (the unified base), letting a project-dir
     * change in the terminal/sheet take effect without recreating the tool pool.
     */
    suspend fun workingDirOf(id: String): String = dao.getById(id)?.workingDir.orEmpty()

    suspend fun readText(
        id: String,
        path: String,
    ): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.readText(workspace.root, path)
    }

    /** Resolve a FILES-area path to its on-disk file (containment + size-capped) for image rendering. */
    suspend fun resolveReadableFile(
        id: String,
        path: String,
        maxBytes: Long,
    ): File = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.resolveReadableFile(workspace.root, path, maxBytes)
    }

    suspend fun writeText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.writeText(workspace.root, path, text, overwrite)
    }

    suspend fun createFolder(
        id: String,
        path: String,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.mkdir(workspace.root, path)
    }

    suspend fun deleteFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        recursive: Boolean,
    ): Boolean {
        val deleted = withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: return@withContext false
            // ensureWorkspace before the delete, matching list/read/write/move (W-I7): a workspace whose
            // dirs were never materialized (e.g. created before first access) must have its area dirs
            // present so the path resolves inside the right root instead of against a missing tree.
            manager.ensureWorkspace(workspace.root)
            manager.deleteFile(workspace.root, path, recursive, area)
        }
        return deleted
    }

    suspend fun moveFile(
        id: String,
        source: String,
        target: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.moveFile(workspace.root, source, target, overwrite)
    }

    suspend fun executeCommand(
        id: String,
        command: String,
        // NULLABLE: `null` == ABSENT (resolve from the workspace working_dir, blank => the files root);
        // an explicit relative cwd is project-relative, a `/workspace/...` cwd is root-absolute. The
        // Absent-vs-Explicit distinction the old `.orEmpty()` at the tool gate collapsed (issue #282).
        cwd: String? = null,
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
    ): WorkspaceCommandResult {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        // I-ENABLE: enforce the user's shell toggle + rootfs readiness BEFORE dispatching to
        // Dispatchers.IO or touching the manager, so a disabled/not-ready workspace can never run a
        // command. Without this the shellEnabled/shellStatus pair was display-only at this sink.
        if (!isShellRunnable(workspace.shellEnabled, workspace.shellStatus)) {
            return WorkspaceCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "Shell is not enabled for this workspace",
                timedOut = false,
            )
        }
        // runInterruptible turns coroutine cancellation into a thread interrupt, which unblocks the blocking Process.waitFor and kills the process
        return runInterruptible(Dispatchers.IO) {
            manager.ensureWorkspace(workspace.root)
            // Pass the persisted working_dir SEED so the central policy resolves override > working_dir >
            // files root; an ABSENT (null) cwd here falls through to that seed, an explicit cwd wins.
            manager.executeCommand(workspace.root, command, cwd, workspace.workingDir, timeoutMillis)
        }
    }

    /**
     * Blocking LLM-shell exec with a PROJECT-JAILED, per-conversation DRIFTING cwd. Unlike
     * [executeCommand] (a stateless per-call exec), this tracks where the shell ends up: an absent [cwd]
     * starts at the per-conversation tracked cwd (which starts at the project dir, the workspace
     * working_dir), the command's final `pwd` is captured, and the tracked cwd PERSISTS if it stayed
     * inside the project jail or REVERTS to the latest in-jail value if the command left it. No command
     * is blocked — only whether the cwd persists differs. The captured cwd is untrusted ADVISORY: a
     * random per-call token plus jail + `/workspace`-parse validation make a spoofed cwd-line harmless.
     *
     * Scope: the blocking LLM `workspace_shell` only — the detached path ([startBackgroundCommand]) and
     * the interactive terminal are unaffected. State is in-memory ([ShellCwdTracker]); it resets to the
     * project dir on process death.
     */
    suspend fun executeTrackedCommand(
        id: String,
        conversationId: Uuid,
        command: String,
        cwd: String? = null,
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
    ): ShellCwdOutcome {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        val floor = WorkspaceCwdPolicy.normalize(workspace.workingDir)
        // Same I-ENABLE guard as executeCommand, before any process: a disabled/not-ready shell returns
        // the byte-identical "Shell is not enabled" result and never spawns.
        if (!isShellRunnable(workspace.shellEnabled, workspace.shellStatus)) {
            return ShellCwdOutcome(
                result = WorkspaceCommandResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "Shell is not enabled for this workspace",
                    timedOut = false,
                ),
                cwd = WorkspaceCwdPolicy.toShellPath(floor),
                status = ShellCwdStatus.UNKNOWN,
            )
        }
        return runInterruptible(Dispatchers.IO) {
            manager.ensureWorkspace(workspace.root)
            // The revert target ("latest in-jail project_dir"): the tracked cwd if it is STILL inside the
            // current floor AND still exists on disk; else the floor. This keeps the manager's
            // exists/isDirectory guard from throwing on a stale (deleted, or out-of-jail after a floor
            // change) tracked cwd.
            val tracked = shellCwdTracker.get(id, conversationId)
            val base = if (tracked != null &&
                WorkspaceCwdPolicy.isWithin(floor, tracked) &&
                manager.isDirectory(workspace.root, tracked)
            ) tracked else floor
            // Absent cwd -> start at the tracked base, passed as a /workspace-absolute path so the policy
            // returns it verbatim (no project-relative double-join). Explicit cwd -> the model's path,
            // resolved project-relative by the policy exactly as the untracked path does.
            val startCwd = cwd ?: WorkspaceCwdPolicy.toShellPath(base)
            val token = newCwdCaptureToken()
            // The capture postlude runs in the runner OUTSIDE the eval'd command (so malformed user
            // syntax can't consume it); the command is passed RAW. The runner emits <token><pwd> which
            // extractFinalCwd parses back out below.
            val raw = manager.executeCommand(
                workspace.root,
                command,
                startCwd,
                workspace.workingDir,
                timeoutMillis,
                cwdCaptureToken = token,
            )
            val (cleanStdout, capturedPwd) = extractFinalCwd(raw.stdout, token)
            val decision = if (capturedPwd == null) {
                // No cwd captured (footer never ran: exec/exit/syntax error, or truncated past the
                // 128 KiB stdout cap) -> keep the prior in-jail cwd, report UNKNOWN.
                ShellCwdDecision(base, ShellCwdStatus.UNKNOWN)
            } else {
                // ADVISORY: parseShellPath returns null for a non-/workspace rootfs path (e.g. /etc) =>
                // treated as outside the jail => REVERTED by the state machine.
                val finalRel = runCatching { WorkspaceCwdPolicy.parseShellPath(capturedPwd) }.getOrNull()
                decideTrackedCwd(floor, base, finalRel)
            }
            shellCwdTracker.set(id, conversationId, decision.cwd)
            ShellCwdOutcome(
                result = raw.copy(stdout = cleanStdout),
                cwd = WorkspaceCwdPolicy.toShellPath(decision.cwd),
                status = decision.status,
            )
        }
    }

    /**
     * Run a command that may AUTO-BACKGROUND (issue #291). Distinct from [executeCommand] — the
     * blocking path is untouched. The [ShellRunCoordinator] owns the foreground-wait-and-maybe-detach
     * state machine; this method only re-runs the SAME I-ENABLE guard ([isShellRunnable]) so
     * NO_PROCESS_WHEN_DISABLED holds (a disabled/not-ready workspace returns the byte-identical
     * "Shell is not enabled" result WITHOUT starting a process), resolves the app-private output file,
     * and hands the run to the coordinator.
     *
     * @param detachAfterSeconds the foreground budget before the run backgrounds; null == never
     *   detach (the coordinator then waits inline, identical to [executeCommand]'s result shape).
     * @param hardTimeoutMillis the kill ceiling the handle's `await()` enforces (foreground +
     *   detached); the size cap is [sizeCapBytes].
     * @throws kotlinx.coroutines.CancellationException on a user stop during the foreground wait,
     *   AFTER the run was detached under NonCancellable (STOP_IS_DETACH_NOT_KILL).
     */
    suspend fun startBackgroundCommand(
        id: String,
        conversationId: Uuid,
        command: String,
        cwd: String? = null,
        detachAfterSeconds: Int?,
        hardTimeoutMillis: Long,
        sizeCapBytes: Long = DEFAULT_OUTPUT_SIZE_CAP_BYTES,
    ): ShellRunResult {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        // I-ENABLE / NO_PROCESS_WHEN_DISABLED: the same guard executeCommand applies, BEFORE the
        // coordinator can start a process. A disabled/not-ready workspace returns the byte-identical
        // inline "Shell is not enabled" result and never spawns.
        if (!isShellRunnable(workspace.shellEnabled, workspace.shellStatus)) {
            return ShellRunResult.Inline(
                WorkspaceCommandResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "Shell is not enabled for this workspace",
                    timedOut = false,
                )
            )
        }
        manager.ensureWorkspace(workspace.root)
        val taskId = Uuid.random()
        val outputFile = File(shellTasksDir, "$taskId.output")
        return shellRunCoordinator.run(
            request = ShellRunRequest(
                workspaceId = id,
                root = workspace.root,
                conversationId = conversationId,
                command = command,
                cwd = cwd,
                workingDir = workspace.workingDir,
                outputPath = outputFile.absolutePath,
                detachAfterSeconds = detachAfterSeconds,
                hardTimeoutMillis = hardTimeoutMillis,
                sizeCapBytes = sizeCapBytes,
            ),
            taskId = taskId,
        )
    }

    /**
     * Read the trailing [maxBytes] of a background shell run's app-private output file by [taskId]
     * (issue #291). The output lives under [shellTasksDir] (app-private, NOT the workspace root), so
     * workspace_read_file — which only reads under the workspace files root — cannot reach it; this is
     * the read path the workspace_shell_tail tool uses.
     *
     * SCOPING INVARIANT: taskIds are MODEL-controlled, so the read MUST be confined to runs the caller
     * actually owns. The taskId is resolved to its persisted [ShellRunEntity] and read ONLY when the
     * row's `workspaceId` AND `conversationId` both match the caller's [id] / [conversationId] — else
     * "" (no cross-workspace or cross-conversation read, and no stale read after the row was deleted).
     * The output path read is the row's own persisted `outputPath`, not a reconstructed
     * shellTasksDir path, so a missing/deleted row can never resolve to a file on disk.
     */
    suspend fun tailShellRun(id: String, conversationId: Uuid, taskId: String, maxBytes: Int): String =
        withContext(Dispatchers.IO) {
            // Parse rejects a non-canonical UUID (preserved behaviour); the row lookup + scoping below
            // is what actually confines the read, not the file-name shape.
            val uuid = Uuid.parse(taskId)
            val run = shellRunStore.getByTaskId(uuid)
            val file = resolveTailFile(run, id, conversationId.toString()) ?: return@withContext ""
            if (!file.exists()) return@withContext ""
            val length = file.length()
            if (length <= maxBytes) return@withContext file.readText()
            file.inputStream().use { stream ->
                stream.skip(length - maxBytes)
                stream.readBytes().toString(Charsets.UTF_8)
            }
        }

    suspend fun delete(id: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        dao.deleteById(id)
        manager.deleteWorkspace(workspace.root)
        settingsStore.update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.workspaceId?.toString() == id) {
                        assistant.copy(workspaceId = null)
                    } else {
                        assistant
                    }
                }
            )
        }
        return true
    }

    companion object {
        private const val TAG = "WorkspaceRepository"
    }
}

/**
 * I-ENABLE (design note security-model-design:197 §4.3): a shell command may run only when the owning
 * workspace is explicitly enabled AND its rootfs is READY. Enforced at the [WorkspaceRepository.executeCommand]
 * sink — the lowest layer that still holds the [WorkspaceEntity] — independent of the tool layer, so a
 * disabled or not-ready shell can never reach the PRoot manager. Pure (no Context/SettingsStore coupling)
 * so it is unit-testable in the :app JVM unit source set.
 */
internal fun isShellRunnable(shellEnabled: Boolean, shellStatus: String): Boolean =
    shellEnabled && shellStatus == WorkspaceShellStatus.READY.name

/**
 * The tail-scoping chokepoint (issue #291): the only file a `workspace_shell_tail` read may touch is
 * the persisted [run]'s own `outputPath`, and ONLY when the row belongs to the calling [workspaceId]
 * AND [conversationId]. A missing/deleted row ([run] == null) or any workspace/conversation mismatch
 * resolves to null (⇒ the tail returns ""), so a model-controlled taskId can never read another
 * workspace's / conversation's run, nor a stale output file whose row was already deleted. Pure (no
 * IO/Context) so the scoping invariant is unit-testable in the :app JVM source set, matching the
 * [isShellRunnable] precedent. [conversationId] is the caller's id stringified, compared against the
 * row's stored `conversationId` (a `Uuid.toString()`), mirroring the existing `workspaceId` compare.
 */
internal fun resolveTailFile(
    run: ShellRunEntity?,
    workspaceId: String,
    conversationId: String,
): File? =
    if (run == null || run.workspaceId != workspaceId || run.conversationId != conversationId) null
    else File(run.outputPath)

/**
 * Merge one tool's approval policy onto the row's existing `tool_approvals` blob, returning the
 * re-encoded JSON. Pure so the lost-update invariant the [WorkspaceRepository.setToolApproval]
 * transaction protects is unit-testable in the :app JVM source set (see WorkspaceToolApprovalMergeTest).
 *
 * I-FAILCLOSED (#197 slice 6a review): a corrupt existing blob (un-decodable) is the SAME
 * security-relevant state the tool consumer ([resolveWorkspaceToolApproval]) treats as fail-CLOSED —
 * `toolApprovalOverrides()` returns null there, forcing approval for every tool. The write path must
 * agree: starting from {} would relax every UNSET tool to its (possibly no-approval) default, silently
 * downgrading the consumer's fail-closed state the moment the user toggles a single switch. So a
 * corrupt blob is repaired to a FAIL-CLOSED baseline — every known tool set to approval-required — and
 * the user's selected override is then applied on top. A well-formed blob (including an explicit {})
 * is merged as-is, preserving every already-set key (the property a stale, non-transactional read
 * would break).
 */
internal fun mergeToolApprovalOverride(
    existingJson: String,
    toolName: String,
    needsApproval: Boolean,
): String {
    val decoded = runCatching {
        JsonInstant.decodeFromString<Map<String, Boolean>>(existingJson)
    }.getOrNull()
    // decoded == null distinguishes a corrupt blob from an explicitly-empty {} map: corrupt repairs to
    // the fail-closed baseline (never {}), so unset tools stay approval-required after the merge.
    val base = decoded ?: WorkspaceToolDefaultApprovals.mapValues { true }
    return JsonInstant.encodeToString(base + (toolName to needsApproval))
}
