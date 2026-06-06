package me.rerere.rikkahub.ui.pages.backup

/**
 * Explicit state for the backup/restore async workflows (WebDAV / S3 / local file), each of which
 * is long-running, can fail mid-way, and needs the UI to show progress and a terminal outcome.
 * This replaces ad-hoc `isBackingUp` / `restoringItemId` booleans scattered across the tab
 * composables plus inline `runCatching { ... }.onFailure { toaster.show(it.message) }`, which leaked
 * raw exception text to the user and turned a navigated-away cancellation into an error toast
 * (issue #105).
 *
 * Feature-local: the backup list itself stays as `UiState<List<...>>`; this models only the
 * imperative operation, keyed by [Kind] so a single flow can drive every button.
 */
sealed interface BackupOperationState {
    data object Idle : BackupOperationState

    data class Running(val kind: Kind, val message: String? = null) : BackupOperationState

    data class Success(val kind: Kind) : BackupOperationState

    /** Terminal failure with a safe, already-mapped user-facing [message] (never raw throwable text). */
    data class Error(val kind: Kind, val message: String) : BackupOperationState

    enum class Kind { WebDavBackup, WebDavRestore, S3Backup, S3Restore, LocalExport, LocalRestore }
}
