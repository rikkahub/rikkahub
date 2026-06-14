package me.rerere.workspace

import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class WorkspaceManager(
    private val baseDir: File,
    private val config: WorkspaceConfig = WorkspaceConfig(),
    // No default: the runner must be chosen explicitly at every construction site. Defaulting to
    // HostShellRunner silently routes commands to the device host shell (bypassing the PRoot
    // sandbox entirely) — a sandbox-escape if a future caller forgets to inject ProotShellRunner.
    // Production DI wires ProotShellRunner (RepositoryModule); tests pass HostShellRunner explicitly.
    private val shellRunner: WorkspaceShellRunner,
) {
    private val fileSystem = WorkspaceFileSystem(config)

    init {
        baseDir.mkdirs()
    }

    fun ensureWorkspace(root: String): File {
        val dir = workspaceDir(root)
        filesDir(root).mkdirs()
        linuxDir(root).mkdirs()
        tempDir(root).mkdirs()
        return dir
    }

    fun workspaceDir(root: String): File {
        requireValidRoot(root)
        return File(baseDir, root)
    }

    fun filesDir(root: String): File = File(workspaceDir(root), FILES_DIR)

    fun linuxDir(root: String): File = File(workspaceDir(root), LINUX_DIR)

    fun tempDir(root: String): File = File(workspaceDir(root), TEMP_DIR)

    fun hasRootfs(root: String): Boolean = linuxDir(root).listFiles()?.isNotEmpty() == true

    fun deleteWorkspace(root: String): Boolean = workspaceDir(root).deleteRecursively()

    fun listFiles(
        root: String,
        path: String = "",
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): List<WorkspaceFileEntry> =
        fileSystem.list(areaDir(root, area), path)

    fun readText(
        root: String,
        path: String,
        charset: Charset = StandardCharsets.UTF_8,
    ): String = fileSystem.readText(filesDir(root), path, charset)

    fun writeText(
        root: String,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry = fileSystem.writeText(filesDir(root), path, text, overwrite, charset)

    fun deleteFile(
        root: String,
        path: String,
        recursive: Boolean = false,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Boolean =
        fileSystem.delete(areaDir(root, area), path, recursive)

    fun moveFile(root: String, source: String, target: String, overwrite: Boolean = false): WorkspaceFileEntry =
        fileSystem.move(filesDir(root), source, target, overwrite)

    fun glob(root: String, pattern: String, path: String = ""): List<WorkspaceFileEntry> =
        fileSystem.glob(filesDir(root), pattern, path)

    fun grep(
        root: String,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
    ): List<WorkspaceSearchMatch> =
        fileSystem.grep(filesDir(root), query, path, regex, ignoreCase, includeGlob)

    /**
     * Run [command] in the workspace shell. The cwd is resolved by the ONE central policy
     * [WorkspaceCwdPolicy.resolveRelative] (issue #282) so the value handed to the runner — and thus
     * mapped to PRoot `-w` — is the SAME normalized value the sideload terminal derives (W-I6):
     *
     *  - [cwd] is NULLABLE. `null` == ABSENT (fall back to [workingDir]/the scratch default); an
     *    explicit `""`/`"."` == the files root. Collapsing the two (the old `String = ""` default, the
     *    `.orEmpty()` at the tool gate) is the bug this nullable param recovers from.
     *  - resolution order is explicit-override > [workingDir] > default `.xcloudz/scratch`.
     *  - an ABSENT cwd over an UNSET [workingDir] materializes the default scratch dir via
     *    [ensureDefaultScratch] (mkdir-p, clobber-safe) so the resolved directory actually exists.
     */
    fun executeCommand(
        root: String,
        command: String,
        cwd: String? = null,
        workingDir: String = "",
        timeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MS,
    ): WorkspaceCommandResult {
        require(command.isNotBlank()) { "Command is required" }
        val files = filesDir(root)
        val override =
            if (cwd == null) WorkspaceCwdPolicy.CwdOverride.Absent
            else WorkspaceCwdPolicy.CwdOverride.Explicit(cwd)
        // ABSENT routes through the SAME [seededRelativeCwd] the sideload terminal uses, so the exec
        // `-w` and the terminal `-w` are the IDENTICAL normalized value for a given workspace (W-I6).
        // seededRelativeCwd materializes the scratch default clobber-safely on the unset path; an
        // EXPLICIT override stays on the central policy resolver (override > files-root, no auto-mkdir).
        val resolved: String = when (override) {
            is WorkspaceCwdPolicy.CwdOverride.Absent -> seededRelativeCwd(files, workingDir)
            is WorkspaceCwdPolicy.CwdOverride.Explicit -> WorkspaceCwdPolicy.resolveRelative(override, workingDir)
        }
        val workingDirFile: File = fileSystem.resolve(files, resolved)
        require(workingDirFile.exists()) { "Working directory does not exist: $resolved" }
        require(workingDirFile.isDirectory) { "Working path is not a directory: $resolved" }

        return shellRunner.execute(
            WorkspaceShellContext(
                root = root,
                command = command,
                cwd = resolved,
                filesDir = files,
                linuxDir = linuxDir(root),
                tempDir = tempDir(root),
                workingDir = workingDirFile,
                timeoutMillis = timeoutMillis,
            )
        )
    }

    private fun requireValidRoot(root: String) {
        require(isValidRoot(root)) {
            "Invalid workspace root name: $root"
        }
    }

    private fun areaDir(root: String, area: WorkspaceStorageArea): File = when (area) {
        WorkspaceStorageArea.FILES -> filesDir(root)
        WorkspaceStorageArea.LINUX -> linuxDir(root)
    }

    companion object {
        private const val FILES_DIR = "files"
        private const val LINUX_DIR = "linux"
        private const val TEMP_DIR = "tmp"
        const val DEFAULT_COMMAND_TIMEOUT_MS = 30_000L
        private val ROOT_NAME_REGEX = Regex("[A-Za-z0-9._-]+")

        /**
         * Whether [root] is a safe workspace directory name (no path separators, no `..` traversal).
         * The single source of truth callers must use before building a path under the workspaces
         * base dir — e.g. the sideload terminal, which resolves directories directly instead of
         * through an instance. A stored [WorkspaceEntity.root] is just text; a corrupt/imported row
         * must not be able to escape the workspaces dir. Rejects the bare `.`/`..` traversal segments
         * too — they pass the char-class regex (dot is allowed) but `File(baseDir, "..")` escapes.
         */
        fun isValidRoot(root: String): Boolean =
            root.matches(ROOT_NAME_REGEX) && root != "." && root != ".."
    }
}
