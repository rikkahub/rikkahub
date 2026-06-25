package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class RootfsInstallerHardeningTest {

    @Test
    fun `stageArchive rejects file entries that resolve to the target directory`() {
        val baseDir = Files.createTempDirectory("rootfs-hardening-file").toFile()
        val manager = WorkspaceManager(baseDir, shellRunner = HostShellRunner())
        val root = "test-workspace"
        manager.ensureWorkspace(root)
        val linuxDir = manager.linuxDir(root).apply { mkdirs() }
        val canary = File(linuxDir, "sentinel").apply { writeText("keep") }
        val archive = File(manager.tempDir(root), "rootfs.tar.gz").apply {
            writeBytes(
                tarGz(
                    TarTestEntry(name = ".", content = "root-content".toByteArray(), type = '0'),
                )
            )
        }
        val installer = RootfsInstaller(manager)

        assertThrows(IllegalArgumentException::class.java) {
            installer.stageArchive(root, archive)
        }

        assertEquals("keep", canary.readText())
    }

    @Test
    fun `stageArchive rejects directory entries that resolve to the target directory`() {
        val baseDir = Files.createTempDirectory("rootfs-hardening-dir").toFile()
        val manager = WorkspaceManager(baseDir, shellRunner = HostShellRunner())
        val root = "test-workspace"
        manager.ensureWorkspace(root)
        val linuxDir = manager.linuxDir(root).apply { mkdirs() }
        val canary = File(linuxDir, "sentinel").apply { writeText("keep") }
        val archive = File(manager.tempDir(root), "rootfs.tar.gz").apply {
            writeBytes(
                tarGz(
                    TarTestEntry(name = ".", type = '5'),
                )
            )
        }
        val installer = RootfsInstaller(manager)

        assertThrows(IllegalArgumentException::class.java) {
            installer.stageArchive(root, archive)
        }

        assertEquals("keep", canary.readText())
    }

    @Test
    fun `stageArchive rejects symlink entries that resolve to the target directory`() {
        val baseDir = Files.createTempDirectory("rootfs-hardening-symlink").toFile()
        val manager = WorkspaceManager(baseDir, shellRunner = HostShellRunner())
        val root = "test-workspace"
        manager.ensureWorkspace(root)
        val linuxDir = manager.linuxDir(root).apply { mkdirs() }
        val canary = File(linuxDir, "sentinel").apply { writeText("keep") }
        val archive = File(manager.tempDir(root), "rootfs.tar.gz").apply {
            writeBytes(
                tarGz(
                    TarTestEntry(name = ".", type = '2', linkName = "x"),
                )
            )
        }
        val installer = RootfsInstaller(manager)

        assertThrows(IllegalArgumentException::class.java) {
            installer.stageArchive(root, archive)
        }

        assertEquals("keep", canary.readText())
    }

    @Test
    fun `stageArchive rejects absolute symlink targets before deleting existing entries`() {
        val baseDir = Files.createTempDirectory("rootfs-hardening-absolute-link").toFile()
        val manager = WorkspaceManager(baseDir, shellRunner = HostShellRunner())
        val root = "test-workspace"
        manager.ensureWorkspace(root)
        val linuxDir = manager.linuxDir(root).apply { mkdirs() }
        val canary = File(linuxDir, "sentinel").apply { writeText("keep") }
        val archive = File(manager.tempDir(root), "rootfs.tar.gz").apply {
            writeBytes(
                tarGz(
                    TarTestEntry(name = "link", type = '2', linkName = "/abs"),
                )
            )
        }
        val installer = RootfsInstaller(manager)

        assertThrows(IllegalArgumentException::class.java) {
            installer.stageArchive(root, archive)
        }

        assertEquals("keep", canary.readText())
    }

    @Test
    fun `stageArchive rejects escaping relative symlink targets before replacing staging root`() {
        val baseDir = Files.createTempDirectory("rootfs-hardening-relative-link").toFile()
        val manager = WorkspaceManager(baseDir, shellRunner = HostShellRunner())
        val root = "test-workspace"
        manager.ensureWorkspace(root)
        val linuxDir = manager.linuxDir(root).apply { mkdirs() }
        val canary = File(linuxDir, "sentinel").apply { writeText("keep") }
        val archive = File(manager.tempDir(root), "rootfs.tar.gz").apply {
            writeBytes(
                tarGz(
                    TarTestEntry(name = "link", type = '2', linkName = "../escape"),
                )
            )
        }
        val installer = RootfsInstaller(manager)

        assertThrows(IllegalArgumentException::class.java) {
            installer.stageArchive(root, archive)
        }

        assertEquals("keep", canary.readText())
    }

    @Test
    fun `stageArchive installs when the workspace base is reached through a symlinked parent`() {
        // Reproduces the Android shape where the app data dir is reached via `/data/user/0/<pkg>`, a
        // symlink to `/data/data/<pkg>`: a symlinked parent in the workspace base path. Before the fix
        // deleteRecursivelyNoFollow canonicalized the root (following the symlink) while each candidate
        // used NOFOLLOW realpath (keeping it), so every in-tree path looked like it escaped and the
        // rootfs install failed ("Rootfs path escapes target directory"). Skip where the FS can't symlink.
        val realBase = Files.createTempDirectory("rootfs-symlink-real").toFile()
        val linkBase = File(realBase.parentFile, realBase.name + "-link")
        val linked = runCatching { Files.createSymbolicLink(linkBase.toPath(), realBase.toPath()) }.isSuccess
        assumeTrue("filesystem supports symlinks", linked)

        val manager = WorkspaceManager(linkBase, shellRunner = HostShellRunner())
        val root = "test-workspace"
        manager.ensureWorkspace(root)
        // A leftover staging dir from a prior attempt is exactly what makes the pre-extract cleanup walk
        // (and previously reject) the tree; without it the cleanup early-returns and never trips.
        val stagingDir = File(manager.tempDir(root), "rootfs-staging").apply { mkdirs() }
        File(stagingDir, "stale").writeText("x")
        // A regular file plus an in-tree relative symlink (the shape every real rootfs ships): the
        // file exercises the pre-extract cleanup containment, the symlink exercises createSymlink's
        // containment — both had the same canonicalize-only-the-root defect under a symlinked base.
        val archive = File(manager.tempDir(root), "rootfs.tar.gz").apply {
            writeBytes(
                tarGz(
                    TarTestEntry(name = "hostname", content = "poci".toByteArray(), type = '0'),
                    TarTestEntry(name = "sh", type = '2', linkName = "hostname"),
                )
            )
        }
        val installer = RootfsInstaller(manager)

        installer.stageArchive(root, archive)

        val linuxDir = manager.linuxDir(root)
        assertEquals("poci", File(linuxDir, "hostname").readText())
        assertTrue(Files.isSymbolicLink(File(linuxDir, "sh").toPath()))
    }

    private fun tarGz(vararg entries: TarTestEntry): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { gzip ->
            entries.forEach { entry ->
                gzip.write(tarHeader(entry))
                if (entry.content.isNotEmpty()) {
                    gzip.write(entry.content)
                    gzip.write(ByteArray(entry.content.size.paddingSize()))
                }
            }
            gzip.write(ByteArray(1024))
        }
        return output.toByteArray()
    }

    private fun tarHeader(entry: TarTestEntry): ByteArray {
        val header = ByteArray(512)
        header.writeString(0, 100, entry.name)
        header.writeOctal(100, 8, entry.mode.toLong())
        header.writeOctal(108, 8, 0)
        header.writeOctal(116, 8, 0)
        header.writeOctal(124, 12, entry.declaredSize ?: entry.content.size.toLong())
        header.writeOctal(136, 12, 0)
        header.fill(' '.code.toByte(), 148, 156)
        header[156] = entry.type.code.toByte()
        header.writeString(157, 100, entry.linkName)
        header.writeString(257, 6, "ustar")
        header.writeString(263, 2, "00")
        val checksum = header.sumOf { it.toUByte().toInt() }
        header.writeOctal(148, 8, checksum.toLong())
        return header
    }

    private fun ByteArray.writeString(offset: Int, length: Int, value: String) {
        val bytes = value.toByteArray()
        bytes.copyInto(this, offset, 0, minOf(bytes.size, length))
    }

    private fun ByteArray.writeOctal(offset: Int, length: Int, value: Long) {
        val text = value.toString(8).padStart(length - 1, '0')
        writeString(offset, length, text)
        this[offset + length - 1] = 0
    }

    private fun Int.paddingSize(): Int = (512 - (this % 512)).let {
        if (it == 512) 0 else it
    }

    private data class TarTestEntry(
        val name: String,
        val content: ByteArray = byteArrayOf(),
        val mode: Int = 420,
        val type: Char = '0',
        val linkName: String = "",
        val declaredSize: Long? = null,
    )
}
