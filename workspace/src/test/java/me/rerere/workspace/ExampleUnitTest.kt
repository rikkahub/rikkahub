package me.rerere.workspace

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class ExampleUnitTest {
    @Test
    fun fileOperationsWorkInsideWorkspaceRoot() {
        val root = Files.createTempDirectory("workspace-test").toFile()
        val fileSystem = WorkspaceFileSystem()

        fileSystem.writeText(root, "src/main.txt", "hello\nworkspace")

        assertEquals("hello\nworkspace", fileSystem.readText(root, "src/main.txt"))
        assertEquals(listOf("src"), fileSystem.list(root).map { it.path })
        assertEquals(listOf("src/main.txt"), fileSystem.glob(root, "**/*.txt").map { it.path })
        assertEquals(
            listOf(WorkspaceSearchMatch(path = "src/main.txt", line = 2, text = "workspace")),
            fileSystem.grep(root, "workspace"),
        )
    }

    @Test
    fun pathEscapeIsRejected() {
        val root = Files.createTempDirectory("workspace-test").toFile()
        val fileSystem = WorkspaceFileSystem()

        var rejected = false
        try {
            fileSystem.writeText(root, "../escape.txt", "nope")
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun rootfsInstallerExtractsTarGz() {
        val baseDir = Files.createTempDirectory("workspace-manager-test").toFile()
        val manager = WorkspaceManager(baseDir, shellRunner = HostShellRunner())
        val installer = RootfsInstaller(manager)
        val archiveBytes = tarGz(
            TarTestEntry("bin/", type = '5'),
            TarTestEntry("bin/hello", content = "echo hello\n".toByteArray(), mode = 493),
            TarTestEntry("usr/bin/hello-link", type = '2', linkName = "../../bin/hello"),
        )
        val root = "test-workspace"
        manager.ensureWorkspace(root)
        // The extract/symlink path is tested directly (stageArchive) because the download channel is
        // HTTPS-gated and a loopback HTTPS server's self-signed cert is not injectable into the
        // installer's internal HttpURLConnection. The HTTPS gate itself is covered by
        // installRejectsNonHttpsUrl below.
        val archive = File(manager.tempDir(root), "rootfs.tar.gz").apply { writeBytes(archiveBytes) }
        installer.stageArchive(root, archive)

        val linuxDir = manager.linuxDir(root)
        assertEquals("echo hello\n", File(linuxDir, "bin/hello").readText())
        assertTrue(File(linuxDir, "bin/hello").canExecute())
        assertTrue(Files.isSymbolicLink(File(linuxDir, "usr/bin/hello-link").toPath()))
    }

    @Test
    fun installRejectsNonHttpsUrl() {
        val baseDir = Files.createTempDirectory("workspace-https-test").toFile()
        val manager = WorkspaceManager(baseDir, shellRunner = HostShellRunner())
        val installer = RootfsInstaller(manager)

        // I-ENABLE/hardening #2: a plaintext rootfs URL is MITM-swappable and must be refused before
        // any download — the rootfs becomes the filesystem an LLM-driven shell runs inside.
        val error = assertThrows(IllegalArgumentException::class.java) {
            installer.install("test-workspace", "http://example.com/rootfs.tar.gz")
        }
        assertTrue(error.message!!.contains("HTTPS"))
    }

    @Test
    fun extractionRejectsADecompressionBomb() {
        val baseDir = Files.createTempDirectory("workspace-bomb-test").toFile()
        val manager = WorkspaceManager(baseDir, shellRunner = HostShellRunner())
        val installer = RootfsInstaller(manager)
        val root = "test-workspace"
        manager.ensureWorkspace(root)

        // Two entries whose declared sizes SUM past MAX_EXTRACTED_BYTES (the aggregate cap is the real
        // property): a small real file, then a bomb header declaring ~8 GB (the max a 12-byte octal
        // tar size field can hold — a single entry can't exceed the cap, only the running total can).
        // gzip makes the archive tiny (under MAX_ROOTFS_BYTES) but extraction must refuse before
        // writing the bomb. The cap is checked on header.size before any copy, so the bomb's empty
        // body is never read.
        val nearMaxOctal = 8_589_934_591L // 8^11 - 1, the largest 11-octal-digit tar size
        val archiveBytes = tarGz(
            TarTestEntry("small", content = ByteArray(16) { 'a'.code.toByte() }),
            TarTestEntry("bomb", declaredSize = nearMaxOctal),
        )
        val archive = File(manager.tempDir(root), "rootfs.tar.gz").apply { writeBytes(archiveBytes) }

        val error = assertThrows(IllegalArgumentException::class.java) {
            installer.stageArchive(root, archive)
        }
        assertTrue(error.message!!.contains("extracted size"))
    }

    @Test
    fun commandRunsInsideWorkspaceFilesDirectory() {
        val baseDir = Files.createTempDirectory("workspace-command-test").toFile()
        val manager = WorkspaceManager(baseDir, shellRunner = HostShellRunner())
        val root = "test-workspace"
        manager.ensureWorkspace(root)

        val result = manager.executeCommand(root, "printf hello > command.txt && cat command.txt")

        assertEquals(0, result.exitCode)
        assertEquals("hello", result.stdout)
        assertEquals("hello", File(manager.filesDir(root), "command.txt").readText())
    }

    @Test
    fun commandOutputIsTruncatedAtLimit() {
        val baseDir = Files.createTempDirectory("workspace-truncate-test").toFile()
        val manager = WorkspaceManager(baseDir, shellRunner = HostShellRunner())
        val root = "test-workspace"
        manager.ensureWorkspace(root)

        // Emit more than MAX_OUTPUT_CHARS so the StreamCollector caps it and flags truncation rather
        // than buffering unbounded output into the LLM context / OOMing.
        val result = manager.executeCommand(
            root,
            "awk 'BEGIN { for (i = 0; i < 300000; i++) printf \"a\" }'",
        )

        assertEquals(0, result.exitCode)
        assertTrue(result.truncated)
        assertEquals(MAX_OUTPUT_CHARS, result.stdout.length)
    }

    @Test
    fun prootRunnerRequiresRootfs() {
        val baseDir = Files.createTempDirectory("workspace-proot-test").toFile()
        val manager = WorkspaceManager(
            baseDir = baseDir,
            shellRunner = ProotShellRunner(File(baseDir, "native"))
        )
        val root = "test-workspace"
        manager.ensureWorkspace(root)

        val result = manager.executeCommand(root, "cat /etc/os-release")

        assertEquals(127, result.exitCode)
        assertEquals("Rootfs is not installed", result.stderr)
    }

    @Test
    fun rootfsPatcherAppliesAndroidProotDefaults() {
        val linuxDir = Files.createTempDirectory("rootfs-patch-test").toFile()
        File(linuxDir, "etc").mkdirs()
        File(linuxDir, "etc/resolv.conf").writeText("nameserver 127.0.0.53\n")
        File(linuxDir, "etc/group").writeText("root:x:0:\n")

        RootfsPatcher().patch(
            linuxDir,
            RootfsPatchOptions(
                nameservers = listOf("9.9.9.9", "8.8.8.8"),
                hostname = "workspace-test",
                groupIds = listOf(3003, 9997),
            )
        )

        assertEquals(
            """
            # Generated by RikkaHub workspace.
            nameserver 9.9.9.9
            nameserver 8.8.8.8
            options edns0 trust-ad

            """.trimIndent(),
            File(linuxDir, "etc/resolv.conf").readText()
        )
        assertTrue(File(linuxDir, "etc/hosts").readText().contains("127.0.0.1 localhost workspace-test"))
        assertEquals("workspace-test\n", File(linuxDir, "etc/hostname").readText())
        assertEquals("LANG=C.UTF-8\n", File(linuxDir, "etc/default/locale").readText())
        assertTrue(File(linuxDir, "etc/group").readText().contains("android_gid_3003:x:3003:"))
        assertTrue(File(linuxDir, "etc/group").readText().contains("android_gid_9997:x:9997:"))
        assertTrue(File(linuxDir, "tmp").canWrite())
        assertTrue(File(linuxDir, "var/tmp").canWrite())
        assertTrue(File(linuxDir, "root").isDirectory)
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
        // When set, the header declares this size instead of content.size — lets a test simulate a
        // decompression bomb (a tiny archive whose header claims an enormous extracted size).
        val declaredSize: Long? = null,
    )
}
