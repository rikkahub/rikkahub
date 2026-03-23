package me.rerere.sandbox

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

class RootFsInstaller {
    fun installTarXz(
        archiveFile: File,
        targetDirectory: File,
        cleanTargetDirectory: Boolean = false,
    ): RootFsInstallResult {
        return install(
            archiveFile = archiveFile,
            targetDirectory = targetDirectory,
            archiveFormat = RootFsArchiveFormat.TAR_XZ,
            cleanTargetDirectory = cleanTargetDirectory,
        )
    }

    fun install(
        archiveFile: File,
        targetDirectory: File,
        archiveFormat: RootFsArchiveFormat = RootFsArchiveFormat.detect(archiveFile),
        cleanTargetDirectory: Boolean = false,
    ): RootFsInstallResult {
        require(archiveFile.exists()) { "archive does not exist: ${archiveFile.absolutePath}" }
        require(archiveFile.isFile) { "archive is not a file: ${archiveFile.absolutePath}" }

        prepareTargetDirectory(targetDirectory, cleanTargetDirectory)

        val rootDirectory = targetDirectory.canonicalFile
        val deferredDirectoryModes = mutableListOf<PendingMode>()
        val deferredHardLinks = mutableListOf<PendingHardLink>()

        var totalEntries = 0
        var directoryCount = 0
        var fileCount = 0
        var symbolicLinkCount = 0
        var hardLinkCount = 0

        val strippablePrefix = detectStrippablePrefix(archiveFile, archiveFormat)

        openTarArchive(archiveFile, archiveFormat).use { tarInput ->
            while (true) {
                val entry = tarInput.nextEntry ?: break
                val entryName = stripEntryPrefix(entry.name, strippablePrefix) ?: continue
                totalEntries++

                if (entry.isDirectory) {
                    val directory = resolveArchivePath(rootDirectory, entryName)
                    directory.mkdirs()
                    deferredDirectoryModes += PendingMode(directory, entry.mode)
                    directory.setLastModified(entry.modTime.time)
                    directoryCount++
                    continue
                }

                if (entry.isSymbolicLink) {
                    val symbolicLink = resolveArchivePath(rootDirectory, entryName)
                    symbolicLink.parentFile?.mkdirs()
                    Files.deleteIfExists(symbolicLink.toPath())
                    Files.createSymbolicLink(symbolicLink.toPath(), Paths.get(entry.linkName))
                    symbolicLinkCount++
                    continue
                }

                if (entry.isLink) {
                    val hardLink = resolveArchivePath(rootDirectory, entryName)
                    hardLink.parentFile?.mkdirs()
                    val strippedLinkName = stripEntryPrefix(entry.linkName, strippablePrefix) ?: entry.linkName
                    deferredHardLinks += PendingHardLink(hardLink, strippedLinkName)
                    hardLinkCount++
                    continue
                }

                if (!entry.isFile) {
                    throw IOException("Unsupported tar entry type: ${entry.name}")
                }

                val outputFile = resolveArchivePath(rootDirectory, entryName)
                outputFile.parentFile?.mkdirs()
                outputFile.outputStream().use { output ->
                    tarInput.copyTo(output)
                }
                applyMode(outputFile.toPath(), entry.mode)
                outputFile.setLastModified(entry.modTime.time)
                fileCount++
            }
        }

        deferredHardLinks.forEach { pendingHardLink ->
            val targetFile = resolveArchivePath(rootDirectory, pendingHardLink.targetPath)
            require(targetFile.exists()) {
                "hard link target does not exist: ${pendingHardLink.targetPath}"
            }
            Files.deleteIfExists(pendingHardLink.linkFile.toPath())
            Files.createLink(pendingHardLink.linkFile.toPath(), targetFile.toPath())
        }

        deferredDirectoryModes
            .sortedByDescending { it.path.absolutePath.length }
            .forEach { pendingMode ->
                applyMode(pendingMode.path.toPath(), pendingMode.mode)
            }

        return RootFsInstallResult(
            archiveFile = archiveFile,
            targetDirectory = rootDirectory,
            totalEntries = totalEntries,
            directoryCount = directoryCount,
            fileCount = fileCount,
            symbolicLinkCount = symbolicLinkCount,
            hardLinkCount = hardLinkCount,
        )
    }

    private fun openTarArchive(
        archiveFile: File,
        archiveFormat: RootFsArchiveFormat,
    ): TarArchiveInputStream {
        val fileInput = BufferedInputStream(FileInputStream(archiveFile))
        val archiveInput = when (archiveFormat) {
            RootFsArchiveFormat.TAR -> fileInput
            RootFsArchiveFormat.TAR_XZ -> XZCompressorInputStream(fileInput)
        }
        return TarArchiveInputStream(archiveInput)
    }

    private fun prepareTargetDirectory(targetDirectory: File, cleanTargetDirectory: Boolean) {
        if (cleanTargetDirectory && targetDirectory.exists()) {
            require(targetDirectory.deleteRecursively()) {
                "failed to clean target directory: ${targetDirectory.absolutePath}"
            }
        }
        if (!targetDirectory.exists()) {
            targetDirectory.mkdirs()
        }
        require(targetDirectory.isDirectory) {
            "target directory is not a directory: ${targetDirectory.absolutePath}"
        }
    }

    /**
     * Detects whether all entries in the archive share a common single top-level directory
     * (e.g. `archlinux-aarch64/bin/sh` → prefix `archlinux-aarch64`).
     * Returns null if the archive already extracts directly to a rootfs layout.
     */
    private fun detectStrippablePrefix(
        archiveFile: File,
        archiveFormat: RootFsArchiveFormat,
    ): String? {
        var candidate: String? = null
        openTarArchive(archiveFile, archiveFormat).use { tarInput ->
            while (true) {
                val entry = tarInput.nextEntry ?: break
                val normalized = entry.name.removePrefix("./").trimEnd('/')
                if (normalized.isEmpty()) continue
                val firstComponent = normalized.substringBefore('/')
                if (firstComponent.isEmpty()) return null
                when {
                    candidate == null -> candidate = firstComponent
                    candidate != firstComponent -> return null
                }
            }
        }
        return candidate
    }

    /**
     * Strips the common top-level prefix from an archive entry name.
     * Returns null if this entry IS the prefix directory itself (should be skipped).
     */
    private fun stripEntryPrefix(name: String, prefix: String?): String? {
        if (prefix == null) return name
        val normalized = name.removePrefix("./")
        val withoutTrailingSlash = normalized.trimEnd('/')
        return when {
            withoutTrailingSlash == prefix -> null
            normalized.startsWith("$prefix/") -> normalized.removePrefix("$prefix/")
            else -> name
        }
    }

    private fun resolveArchivePath(rootDirectory: File, archivePath: String): File {
        val normalizedPath = archivePath.removePrefix("./")
        val resolved = File(rootDirectory, normalizedPath).canonicalFile
        val rootPath = rootDirectory.absolutePath
        require(
            resolved.absolutePath == rootPath ||
                resolved.absolutePath.startsWith(rootPath + File.separator)
        ) {
            "illegal archive entry: $archivePath"
        }
        return resolved
    }

    private fun applyMode(path: Path, mode: Int) {
        val permissions = buildSet {
            addIf(mode and 0b100_000_000 != 0, PosixFilePermission.OWNER_READ)
            addIf(mode and 0b010_000_000 != 0, PosixFilePermission.OWNER_WRITE)
            addIf(mode and 0b001_000_000 != 0, PosixFilePermission.OWNER_EXECUTE)
            addIf(mode and 0b000_100_000 != 0, PosixFilePermission.GROUP_READ)
            addIf(mode and 0b000_010_000 != 0, PosixFilePermission.GROUP_WRITE)
            addIf(mode and 0b000_001_000 != 0, PosixFilePermission.GROUP_EXECUTE)
            addIf(mode and 0b000_000_100 != 0, PosixFilePermission.OTHERS_READ)
            addIf(mode and 0b000_000_010 != 0, PosixFilePermission.OTHERS_WRITE)
            addIf(mode and 0b000_000_001 != 0, PosixFilePermission.OTHERS_EXECUTE)
        }

        runCatching {
            Files.setPosixFilePermissions(path, permissions)
        }.getOrElse {
            applyModeFallback(path.toFile(), mode)
        }
    }

    private fun applyModeFallback(file: File, mode: Int) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)

        val ownerOnlyRead = mode and 0b000_100_100 == 0
        val ownerOnlyWrite = mode and 0b000_010_010 == 0
        val ownerOnlyExecute = mode and 0b000_001_001 == 0

        file.setReadable(mode and 0b100_100_100 != 0, ownerOnlyRead)
        file.setWritable(mode and 0b010_010_010 != 0, ownerOnlyWrite)
        file.setExecutable(mode and 0b001_001_001 != 0, ownerOnlyExecute)
    }

    private fun <T> MutableSet<T>.addIf(condition: Boolean, value: T) {
        if (condition) {
            add(value)
        }
    }

    private data class PendingMode(
        val path: File,
        val mode: Int,
    )

    private data class PendingHardLink(
        val linkFile: File,
        val targetPath: String,
    )
}

enum class RootFsArchiveFormat {
    TAR,
    TAR_XZ,
    ;

    companion object {
        fun detect(archiveFile: File): RootFsArchiveFormat {
            val fileName = archiveFile.name.lowercase()
            return when {
                fileName.endsWith(".tar.xz") || fileName.endsWith(".txz") -> TAR_XZ
                fileName.endsWith(".tar") -> TAR
                else -> throw IllegalArgumentException("unsupported rootfs archive: $fileName")
            }
        }
    }
}
