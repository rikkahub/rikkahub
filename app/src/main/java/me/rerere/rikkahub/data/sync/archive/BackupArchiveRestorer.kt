package me.rerere.rikkahub.data.sync.archive

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.SkillPaths
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

private const val TAG = "BackupArchiveRestorer"

/**
 * Restores a backup ZIP. Owns the single path-traversal invariant that was
 * previously duplicated (and, for uploads, missing) across the two transports:
 * every upload/fonts target is resolved through [SafeZipPaths.resolveChild] and
 * every skill target through [SkillPaths], so no entry can ever be written
 * outside its intended root.
 */
class BackupArchiveRestorer(
    private val env: BackupArchiveEnvironment,
) {
    suspend fun restore(
        file: File,
        selection: BackupArchiveSelection,
    ): BackupRestoreReport = withContext(Dispatchers.IO) {
        Log.i(TAG, "restore: Starting restore from ${file.absolutePath}")
        val report = BackupRestoreReportBuilder()

        ZipInputStream(FileInputStream(file)).use { zipIn ->
            var entry: ZipEntry?
            while (zipIn.nextEntry.also { entry = it } != null) {
                entry?.let { zipEntry ->
                    val name = zipEntry.name
                    Log.i(TAG, "restore: Processing entry $name")

                    when (name) {
                        BackupArchiveLayout.SETTINGS -> {
                            val settingsJson = zipIn.readBytes().toString(Charsets.UTF_8)
                            env.restoreSettingsJson(settingsJson)
                            report.restored(name)
                        }

                        BackupArchiveLayout.DB,
                        BackupArchiveLayout.DB_WAL,
                        BackupArchiveLayout.DB_SHM,
                        -> {
                            if (selection.includeDatabase) {
                                restoreDatabaseEntry(zipIn, name)
                                report.restored(name)
                            } else {
                                report.skipped(name)
                            }
                        }

                        else -> restoreFileEntry(zipIn, name, selection, report)
                    }

                    zipIn.closeEntry()
                }
            }
        }

        Log.i(TAG, "restore: Restore completed")
        report.build()
    }

    private fun restoreDatabaseEntry(zipIn: ZipInputStream, name: String) {
        val dbParent = env.databaseFile(BackupArchiveLayout.DB_NAME).parentFile
        val targetFile = when (name) {
            BackupArchiveLayout.DB -> env.databaseFile(BackupArchiveLayout.DB_NAME)
            BackupArchiveLayout.DB_WAL -> File(dbParent, BackupArchiveLayout.DB_WAL)
            BackupArchiveLayout.DB_SHM -> File(dbParent, BackupArchiveLayout.DB_SHM)
            else -> return
        }
        targetFile.parentFile?.mkdirs()
        FileOutputStream(targetFile).use { out -> zipIn.copyTo(out) }
        Log.i(TAG, "restore: Restored $name (${targetFile.length()} bytes)")
    }

    private fun restoreFileEntry(
        zipIn: ZipInputStream,
        name: String,
        selection: BackupArchiveSelection,
        report: BackupRestoreReportBuilder,
    ) {
        if (!selection.includeFiles) {
            Log.i(TAG, "restore: Skipping entry $name")
            report.skipped(name)
            return
        }

        when {
            name.startsWith(BackupArchiveLayout.UPLOAD_PREFIX) ->
                restoreIntoRoot(zipIn, name, BackupArchiveLayout.UPLOAD_PREFIX, FileFolders.UPLOAD, report)

            name.startsWith(BackupArchiveLayout.SKILLS_PREFIX) ->
                restoreSkillEntry(zipIn, name, report)

            name.startsWith(BackupArchiveLayout.FONTS_PREFIX) ->
                restoreIntoRoot(zipIn, name, BackupArchiveLayout.FONTS_PREFIX, FileFolders.FONTS, report)

            else -> {
                Log.i(TAG, "restore: Skipping entry $name")
                report.skipped(name)
            }
        }
    }

    private fun restoreIntoRoot(
        zipIn: ZipInputStream,
        name: String,
        prefix: String,
        folder: String,
        report: BackupRestoreReportBuilder,
    ) {
        val relativePath = name.substringAfter(prefix)
        if (relativePath.isEmpty()) {
            report.skipped(name)
            return
        }

        val root = File(env.filesDir, folder)
        val targetFile = SafeZipPaths.resolveChild(root, relativePath)
        if (targetFile == null) {
            Log.w(TAG, "restore: Rejected unsafe entry $name")
            report.skipped(name)
            return
        }

        if (!root.exists()) {
            root.mkdirs()
        }
        targetFile.parentFile?.mkdirs()

        try {
            FileOutputStream(targetFile).use { out -> zipIn.copyTo(out) }
            Log.i(TAG, "restore: Restored $name (${targetFile.length()} bytes)")
            report.restored(name)
        } catch (e: Exception) {
            Log.e(TAG, "restore: Failed to restore $name", e)
            report.failed(name)
            throw Exception("Failed to restore file $name: ${e.message}")
        }
    }

    private fun restoreSkillEntry(zipIn: ZipInputStream, name: String, report: BackupRestoreReportBuilder) {
        val relativePath = name.substringAfter(BackupArchiveLayout.SKILLS_PREFIX)
        val skillName = relativePath.substringBefore('/', missingDelimiterValue = "")
        val skillRelativePath = relativePath.substringAfter('/', missingDelimiterValue = "")

        if (skillName.isBlank() || skillRelativePath.isBlank()) {
            Log.w(TAG, "restore: Invalid skill entry $name")
            report.skipped(name)
            return
        }

        val skillsRoot = File(env.filesDir, FileFolders.SKILLS).apply { mkdirs() }
        val skillDir = SkillPaths.resolveSkillDir(skillsRoot, skillName)
        if (skillDir == null) {
            Log.w(TAG, "restore: Rejected unsafe skill directory $name")
            report.skipped(name)
            return
        }
        val targetFile = SkillPaths.resolveSkillFile(skillDir, skillRelativePath)
        if (targetFile == null) {
            Log.w(TAG, "restore: Rejected unsafe skill file $name")
            report.skipped(name)
            return
        }

        skillDir.mkdirs()
        targetFile.parentFile?.mkdirs()

        try {
            FileOutputStream(targetFile).use { out -> zipIn.copyTo(out) }
            Log.i(TAG, "restore: Restored skill file $name (${targetFile.length()} bytes)")
            report.restored(name)
        } catch (e: Exception) {
            Log.e(TAG, "restore: Failed to restore skill file $name", e)
            report.failed(name)
            throw Exception("Failed to restore skill file $name: ${e.message}")
        }
    }
}
