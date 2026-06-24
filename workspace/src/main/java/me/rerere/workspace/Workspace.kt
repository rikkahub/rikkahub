package me.rerere.workspace

data class Workspace(
    val id: String,
    val name: String,
    val root: String,
    val shellEnabled: Boolean = false,
    val shellStatus: WorkspaceShellStatus = WorkspaceShellStatus.DISABLED,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAccessAt: Long? = null,
    // FILES-area relative seed for the resolved shell cwd; "" == UNSET (resolver falls back to the
    // files root, the project working directory default). Never rootfs-absolute, never per-conversation
    // (issue #282).
    val workingDir: String = "",
)

enum class WorkspaceShellStatus {
    DISABLED,
    INSTALLING,
    READY,
    BROKEN,
}

enum class WorkspaceStorageArea {
    FILES,
    LINUX,
}

enum class RootfsInstallStage {
    DOWNLOADING,
    EXTRACTING,
    INSTALLED,
}

data class RootfsInstallProgress(
    val stage: RootfsInstallStage,
    val bytesRead: Long = 0,
    val totalBytes: Long? = null,
    val entriesExtracted: Int = 0,
    val currentEntry: String? = null,
)

data class WorkspaceConfig(
    val maxReadBytes: Long = 512 * 1024,
    val maxWriteBytes: Long = 2 * 1024 * 1024,
    val maxListEntries: Int = 500,
    val maxSearchResults: Int = 100,
    // Per-match line cap for grep. A matching line is truncated to this many characters (with a marker)
    // so a hit inside a minified / single-giant-line file can't push a multi-megabyte snippet into the
    // result — bounding both the in-memory result set and the serialized tool output.
    val maxSearchLineChars: Int = 500,
)

data class WorkspaceFileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val updatedAt: Long,
)

data class WorkspaceSearchMatch(
    val path: String,
    val line: Int,
    val text: String,
)

data class WorkspaceCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
)
