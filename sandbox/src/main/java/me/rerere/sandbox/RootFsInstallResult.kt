package me.rerere.sandbox

import java.io.File

data class RootFsInstallResult(
    val archiveFile: File,
    val targetDirectory: File,
    val totalEntries: Int,
    val directoryCount: Int,
    val fileCount: Int,
    val symbolicLinkCount: Int,
    val hardLinkCount: Int,
)
