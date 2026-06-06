package me.rerere.rikkahub.data.sync.archive

/**
 * Outcome of a restore pass. Entry names that were rejected by the path-safety
 * check land in [skipped] (so a malicious entry is recorded rather than
 * silently swallowed); names that threw while writing land in [failed].
 */
data class BackupRestoreReport(
    val restored: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
    val failed: List<String> = emptyList(),
)

internal class BackupRestoreReportBuilder {
    private val restored = mutableListOf<String>()
    private val skipped = mutableListOf<String>()
    private val failed = mutableListOf<String>()

    fun restored(name: String) {
        restored += name
    }

    fun skipped(name: String) {
        skipped += name
    }

    fun failed(name: String) {
        failed += name
    }

    fun build(): BackupRestoreReport = BackupRestoreReport(
        restored = restored.toList(),
        skipped = skipped.toList(),
        failed = failed.toList(),
    )
}
