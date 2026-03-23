package me.rerere.sandbox

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Test

class RootFsInstallerTest {
    private val zeroDate = Date(0L)

    @Test
    fun `should install tar xz rootfs with permissions and links`() {
        val workingDirectory = Files.createTempDirectory("rootfs-installer-test").toFile()
        val archiveFile = File(workingDirectory, "rootfs.tar.xz")
        val targetDirectory = File(workingDirectory, "target")

        createRootFsArchive(archiveFile)

        val result = RootFsInstaller().installTarXz(archiveFile, targetDirectory)

        val shellFile = File(targetDirectory, "bin/sh")
        val envLink = File(targetDirectory, "usr/bin/env")
        val hardLink = File(targetDirectory, "bin/sh-copy")

        assertEquals(5, result.totalEntries)
        assertEquals(2, result.directoryCount)
        assertEquals(1, result.fileCount)
        assertEquals(1, result.symbolicLinkCount)
        assertEquals(1, result.hardLinkCount)
        assertTrue(shellFile.exists())
        assertTrue(Files.isExecutable(shellFile.toPath()))
        assertTrue(Files.isSymbolicLink(envLink.toPath()))
        assertEquals("../../bin/sh", Files.readSymbolicLink(envLink.toPath()).toString())

        shellFile.appendText("\necho hard-link-check\n")
        assertTrue(hardLink.readText().contains("hard-link-check"))
    }

    @Test
    fun `should install tar xz rootfs wrapped in extra top-level directory`() {
        val workingDirectory = Files.createTempDirectory("rootfs-installer-wrapped").toFile()
        val archiveFile = File(workingDirectory, "archlinux-aarch64.tar.xz")
        val targetDirectory = File(workingDirectory, "target")

        createWrappedRootFsArchive(archiveFile, "archlinux-aarch64")

        val result = RootFsInstaller().installTarXz(archiveFile, targetDirectory)

        val shellFile = File(targetDirectory, "bin/sh")
        val envLink = File(targetDirectory, "usr/bin/env")
        val hardLink = File(targetDirectory, "bin/sh-copy")

        assertEquals(5, result.totalEntries)
        assertEquals(2, result.directoryCount)
        assertEquals(1, result.fileCount)
        assertEquals(1, result.symbolicLinkCount)
        assertEquals(1, result.hardLinkCount)
        assertTrue(shellFile.exists())
        assertTrue(Files.isExecutable(shellFile.toPath()))
        assertTrue(Files.isSymbolicLink(envLink.toPath()))
        assertEquals("../../bin/sh", Files.readSymbolicLink(envLink.toPath()).toString())

        shellFile.appendText("\necho hard-link-check\n")
        assertTrue(hardLink.readText().contains("hard-link-check"))
    }

    @Test
    fun `should reject path traversal entry`() {
        val workingDirectory = Files.createTempDirectory("rootfs-installer-unsafe").toFile()
        val archiveFile = File(workingDirectory, "unsafe.tar.xz")
        val targetDirectory = File(workingDirectory, "target")

        createTraversalArchive(archiveFile)

        assertFailsWith<IllegalArgumentException> {
            RootFsInstaller().installTarXz(archiveFile, targetDirectory)
        }
    }

    private fun createRootFsArchive(archiveFile: File) {
        createArchive(archiveFile) { tarOutput ->
            tarOutput.writeDirectory("bin", 0b111_101_101)
            tarOutput.writeDirectory("usr/bin", 0b111_101_101)
            tarOutput.writeFile("bin/sh", "#!/bin/sh\nexit 0\n".toByteArray(), 0b111_101_101)
            tarOutput.writeSymbolicLink("usr/bin/env", "../../bin/sh")
            tarOutput.writeHardLink("bin/sh-copy", "bin/sh")
        }
    }

    private fun createWrappedRootFsArchive(archiveFile: File, wrapperDir: String) {
        createArchive(archiveFile) { tarOutput ->
            tarOutput.writeDirectory(wrapperDir, 0b111_101_101)
            tarOutput.writeDirectory("$wrapperDir/bin", 0b111_101_101)
            tarOutput.writeDirectory("$wrapperDir/usr/bin", 0b111_101_101)
            tarOutput.writeFile("$wrapperDir/bin/sh", "#!/bin/sh\nexit 0\n".toByteArray(), 0b111_101_101)
            tarOutput.writeSymbolicLink("$wrapperDir/usr/bin/env", "../../bin/sh")
            tarOutput.writeHardLink("$wrapperDir/bin/sh-copy", "$wrapperDir/bin/sh")
        }
    }

    private fun createTraversalArchive(archiveFile: File) {
        createArchive(archiveFile) { tarOutput ->
            tarOutput.writeFile("../evil.txt", "nope".toByteArray(), 0b110_100_100)
        }
    }

    private fun createArchive(
        archiveFile: File,
        block: (TarArchiveOutputStream) -> Unit,
    ) {
        archiveFile.parentFile?.mkdirs()
        BufferedOutputStream(FileOutputStream(archiveFile)).use { fileOutput ->
            XZCompressorOutputStream(fileOutput).use { xzOutput ->
                TarArchiveOutputStream(xzOutput).use { tarOutput ->
                    tarOutput.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    block(tarOutput)
                    tarOutput.finish()
                }
            }
        }
    }

    private fun TarArchiveOutputStream.writeDirectory(name: String, mode: Int) {
        val entry = TarArchiveEntry("$name/")
        entry.mode = mode
        entry.modTime = zeroDate
        putArchiveEntry(entry)
        closeArchiveEntry()
    }

    private fun TarArchiveOutputStream.writeFile(name: String, content: ByteArray, mode: Int) {
        val entry = TarArchiveEntry(name)
        entry.size = content.size.toLong()
        entry.mode = mode
        entry.modTime = zeroDate
        putArchiveEntry(entry)
        write(content)
        closeArchiveEntry()
    }

    private fun TarArchiveOutputStream.writeSymbolicLink(name: String, target: String) {
        val entry = TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK)
        entry.linkName = target
        entry.modTime = zeroDate
        putArchiveEntry(entry)
        closeArchiveEntry()
    }

    private fun TarArchiveOutputStream.writeHardLink(name: String, target: String) {
        val entry = TarArchiveEntry(name, TarArchiveEntry.LF_LINK)
        entry.linkName = target
        entry.modTime = zeroDate
        putArchiveEntry(entry)
        closeArchiveEntry()
    }
}
