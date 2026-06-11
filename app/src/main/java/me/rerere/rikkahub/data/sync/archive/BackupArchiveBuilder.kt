package me.rerere.rikkahub.data.sync.archive

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.common.text.fileSizeToString
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val TAG = "BackupArchiveBuilder"

/**
 * Builds the backward-compatible backup ZIP. Shared by S3 and WebDAV so that
 * archive contents/entry names are defined in exactly one place.
 */
class BackupArchiveBuilder(
    private val env: BackupArchiveEnvironment,
) {
    suspend fun build(selection: BackupArchiveSelection): File = withContext(Dispatchers.IO) {
        val backupFile = File(env.cacheDir, BackupArchiveLayout.backupFileName(LocalDateTime.now()))

        if (backupFile.exists()) {
            backupFile.delete()
        }

        ZipOutputStream(FileOutputStream(backupFile)).use { zipOut ->
            addVirtualFileToZip(
                zipOut = zipOut,
                name = BackupArchiveLayout.SETTINGS,
                content = env.readSettingsJson(),
            )

            if (selection.includeDatabase) {
                val dbFile = env.databaseFile(BackupArchiveLayout.DB_NAME)
                if (dbFile.exists()) {
                    addFileToZip(zipOut, dbFile, BackupArchiveLayout.DB)
                }

                val walFile = File(dbFile.parentFile, BackupArchiveLayout.DB_WAL)
                if (walFile.exists()) {
                    addFileToZip(zipOut, walFile, BackupArchiveLayout.DB_WAL)
                }

                val shmFile = File(dbFile.parentFile, BackupArchiveLayout.DB_SHM)
                if (shmFile.exists()) {
                    addFileToZip(zipOut, shmFile, BackupArchiveLayout.DB_SHM)
                }
            }

            if (selection.includeFiles) {
                val uploadFolder = File(env.filesDir, FileFolders.UPLOAD)
                if (uploadFolder.exists() && uploadFolder.isDirectory) {
                    Log.i(TAG, "build: Backing up files from ${uploadFolder.absolutePath}")
                    uploadFolder.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            addFileToZip(zipOut, file, "${BackupArchiveLayout.UPLOAD_PREFIX}${file.name}")
                        }
                    }
                } else {
                    Log.w(TAG, "build: Upload folder does not exist or is not a directory")
                }

                val skillsFolder = File(env.filesDir, FileFolders.SKILLS)
                if (skillsFolder.exists() && skillsFolder.isDirectory) {
                    Log.i(TAG, "build: Backing up skills from ${skillsFolder.absolutePath}")
                    addDirectoryToZip(
                        zipOut = zipOut,
                        rootDir = skillsFolder,
                        currentDir = skillsFolder,
                        entryPrefix = BackupArchiveLayout.SKILLS_PREFIX,
                    )
                } else {
                    Log.w(TAG, "build: Skills folder does not exist or is not a directory")
                }

                val fontsFolder = File(env.filesDir, FileFolders.FONTS)
                if (fontsFolder.exists() && fontsFolder.isDirectory) {
                    Log.i(TAG, "build: Backing up fonts from ${fontsFolder.absolutePath}")
                    fontsFolder.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            addFileToZip(zipOut, file, "${BackupArchiveLayout.FONTS_PREFIX}${file.name}")
                        }
                    }
                } else {
                    Log.w(TAG, "build: Fonts folder does not exist or is not a directory")
                }
            }
        }

        Log.i(TAG, "build: Created backup file ${backupFile.name} (${backupFile.length().fileSizeToString()})")
        backupFile
    }

    private fun addFileToZip(zipOut: ZipOutputStream, file: File, entryName: String) {
        FileInputStream(file).use { fis ->
            val zipEntry = ZipEntry(entryName)
            zipOut.putNextEntry(zipEntry)
            fis.copyTo(zipOut)
            zipOut.closeEntry()
            Log.d(TAG, "addFileToZip: Added $entryName (${file.length()} bytes) to zip")
        }
    }

    private fun addDirectoryToZip(
        zipOut: ZipOutputStream,
        rootDir: File,
        currentDir: File,
        entryPrefix: String,
    ) {
        currentDir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                addDirectoryToZip(
                    zipOut = zipOut,
                    rootDir = rootDir,
                    currentDir = file,
                    entryPrefix = entryPrefix,
                )
            } else if (file.isFile) {
                val relativePath = file.relativeTo(rootDir).invariantSeparatorsPath
                addFileToZip(zipOut, file, "$entryPrefix$relativePath")
            }
        }
    }

    private fun addVirtualFileToZip(zipOut: ZipOutputStream, name: String, content: String) {
        val zipEntry = ZipEntry(name)
        zipOut.putNextEntry(zipEntry)
        zipOut.write(content.toByteArray())
        zipOut.closeEntry()
        Log.i(TAG, "addVirtualFileToZip: $name (${content.length} bytes)")
    }
}
