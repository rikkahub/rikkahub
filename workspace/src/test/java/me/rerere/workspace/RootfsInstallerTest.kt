package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.GZIPOutputStream

class RootfsInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `extract skips OTHER entry data exactly once`() {
        // OTHER 条目 (如 GNU sparse) 带 size>0 数据区, 双重 skip 会让后续 header 错位
        val archive = tmp.newFile("rootfs.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("a.txt", '0', "hello".toByteArray())
            out.writeTarEntry("sparse.bin", 'S', ByteArray(700) { 1 })
            out.writeTarEntry("b.txt", '0', "world".toByteArray())
            out.write(ByteArray(TAR_BLOCK * 2))
        }

        val target = tmp.newFolder("out")
        createInstaller().extractTar(archive, target) {}

        assertEquals("hello", File(target, "a.txt").readText())
        assertEquals("world", File(target, "b.txt").readText())
        assertFalse(File(target, "sparse.bin").exists())
    }

    @Test
    fun `extract handles directories and zero size entries`() {
        val archive = tmp.newFile("rootfs.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("dir/", '5', ByteArray(0))
            out.writeTarEntry("dir/file.txt", '0', "content".toByteArray())
            out.write(ByteArray(TAR_BLOCK * 2))
        }

        val target = tmp.newFolder("out")
        createInstaller().extractTar(archive, target) {}

        assertEquals(true, File(target, "dir").isDirectory)
        assertEquals("content", File(target, "dir/file.txt").readText())
    }

    @Test
    fun `extract skips root entries like dot slash`() {
        // 许多 rootfs tar 以 "./" 根条目开头, 旧实现对其抛错导致解压失败
        val archive = tmp.newFile("rootfs.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("./", '5', ByteArray(0))
            out.writeTarEntry("./bin/", '5', ByteArray(0))
            out.writeTarEntry("./bin/sh", '0', "#!/bin/sh".toByteArray())
            out.write(ByteArray(TAR_BLOCK * 2))
        }

        val target = tmp.newFolder("out")
        createInstaller().extractTar(archive, target) {}

        assertEquals("#!/bin/sh", File(target, "bin/sh").readText())
    }

    @Test
    fun `archiveContainsShell finds known shell entry`() {
        val archive = tmp.newFile("rootfs.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("./etc/os-release", '0', "ID=test".toByteArray())
            out.writeTarEntry("./bin/sh", '0', "#!/bin/sh".toByteArray())
            out.write(ByteArray(TAR_BLOCK * 2))
        }

        assertTrue(createInstaller().archiveContainsShell(archive))
    }

    @Test
    fun `archiveContainsShell rejects archive without shell`() {
        val archive = tmp.newFile("random.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("docs/readme.md", '0', "hello".toByteArray())
            out.write(ByteArray(TAR_BLOCK * 2))
        }

        assertFalse(createInstaller().archiveContainsShell(archive))
    }

    @Test
    fun `install from local path installs rootfs with uploading progress`() {
        val archive = tmp.newFile("rootfs.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("./", '5', ByteArray(0))
            out.writeTarEntry("bin/", '5', ByteArray(0))
            out.writeTarEntry("bin/sh", '0', "#!/bin/sh".toByteArray())
            out.write(ByteArray(TAR_BLOCK * 2))
        }

        val base = tmp.newFolder("workspaces")
        val manager = WorkspaceManager(base)
        val stages = mutableListOf<RootfsInstallStage>()
        RootfsInstaller(manager).install("ws1", archive.absolutePath) { stages.add(it.stage) }

        assertTrue(File(manager.linuxDir("ws1"), "bin/sh").isFile)
        assertTrue(manager.hasRootfs("ws1"))
        assertTrue(RootfsInstallStage.UPLOADING in stages)
        assertTrue(RootfsInstallStage.EXTRACTING in stages)
        assertTrue(RootfsInstallStage.INSTALLED in stages)
        assertFalse(RootfsInstallStage.DOWNLOADING in stages)
    }

    @Test
    fun `install rejects non rootfs archive`() {
        val archive = tmp.newFile("random.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("docs/readme.md", '0', "hello".toByteArray())
            out.write(ByteArray(TAR_BLOCK * 2))
        }

        val base = tmp.newFolder("workspaces")
        val manager = WorkspaceManager(base)
        val error = runCatching {
            RootfsInstaller(manager).install("ws1", archive.absolutePath) {}
        }.exceptionOrNull()

        assertEquals("not a valid rootfs archive file", error?.message)
        assertFalse(manager.hasRootfs("ws1"))
    }

    @Test
    fun `install rejects garbage file`() {
        val archive = tmp.newFile("garbage.tar.gz")
        archive.writeBytes(ByteArray(1024) { it.toByte() })

        val base = tmp.newFolder("workspaces")
        val manager = WorkspaceManager(base)
        val error = runCatching {
            RootfsInstaller(manager).install("ws1", archive.absolutePath) {}
        }.exceptionOrNull()

        assertEquals("not a valid rootfs archive file", error?.message)
    }

    @Test
    fun `install accepts file url source`() {
        val archive = tmp.newFile("rootfs.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("bin/sh", '0', "#!/bin/sh".toByteArray())
            out.write(ByteArray(TAR_BLOCK * 2))
        }

        val base = tmp.newFolder("workspaces")
        val manager = WorkspaceManager(base)
        RootfsInstaller(manager).install("ws1", archive.toURI().toString()) {}

        assertTrue(manager.hasRootfs("ws1"))
    }

    @Test
    fun `install from stream installs rootfs`() {
        val archive = tmp.newFile("rootfs.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("bin/sh", '0', "#!/bin/sh".toByteArray())
            out.write(ByteArray(TAR_BLOCK * 2))
        }

        val base = tmp.newFolder("workspaces")
        val manager = WorkspaceManager(base)
        val stages = mutableListOf<RootfsInstallStage>()
        RootfsInstaller(manager).install(
            root = "ws1",
            input = archive.inputStream(),
            format = RootfsInstaller.ArchiveFormat.TAR_GZ,
            totalBytes = archive.length(),
        ) { stages.add(it.stage) }

        assertTrue(manager.hasRootfs("ws1"))
        assertTrue(RootfsInstallStage.INSTALLED in stages)
    }

    @Test
    fun `detectRootfsShell resolves absolute symlink inside rootfs`() {
        // Alpine 等镜像的 /bin/sh 是指向绝对路径的符号链接; 直接 File.isFile 会去宿主机上找而误判
        val linuxDir = tmp.newFolder("linux")
        File(linuxDir, "usr/bin").mkdirs()
        File(linuxDir, "usr/bin/bash").writeText("ELF")
        File(linuxDir, "bin").mkdirs()
        Files.createSymbolicLink(
            File(linuxDir, "bin/sh").toPath(),
            Paths.get("/usr/bin/bash"),
        )

        assertEquals("/usr/bin/bash", detectRootfsShell(linuxDir))
    }

    @Test
    fun `detectRootfsShell resolves relative symlink`() {
        val linuxDir = tmp.newFolder("linux")
        File(linuxDir, "bin").mkdirs()
        File(linuxDir, "bin/busybox").writeText("ELF")
        Files.createSymbolicLink(
            File(linuxDir, "bin/ash").toPath(),
            Paths.get("busybox"),
        )

        assertEquals("/bin/ash", detectRootfsShell(linuxDir))
    }

    @Test
    fun `detectRootfsShell returns null without shell`() {
        val linuxDir = tmp.newFolder("linux")
        File(linuxDir, "etc").mkdirs()

        assertNull(detectRootfsShell(linuxDir))
    }

    @Test
    fun `detectRootfsShell handles dangling absolute symlink`() {
        val linuxDir = tmp.newFolder("linux")
        File(linuxDir, "bin").mkdirs()
        Files.createSymbolicLink(
            File(linuxDir, "bin/sh").toPath(),
            Paths.get("/nonexistent/shell"),
        )

        assertNull(detectRootfsShell(linuxDir))
    }

    @Test
    fun `detectRootfsShell prefers bash over sh`() {
        val linuxDir = tmp.newFolder("linux")
        File(linuxDir, "bin").mkdirs()
        File(linuxDir, "bin/bash").writeText("ELF")
        File(linuxDir, "bin/sh").writeText("ELF")

        assertEquals("/bin/bash", detectRootfsShell(linuxDir))
    }

    @Test
    fun `detectRootfsShell prefers passwd root shell over candidates`() {
        // Alpine 真实格式: GECOS/home 均非空, 不能按 "root:x:0:0::/root:" 字面前缀匹配
        val linuxDir = tmp.newFolder("linux")
        File(linuxDir, "bin").mkdirs()
        File(linuxDir, "bin/bash").writeText("ELF")
        File(linuxDir, "bin/ash").writeText("ELF")
        File(linuxDir, "etc").mkdirs()
        File(linuxDir, "etc/passwd").writeText(
            "root:x:0:0:root:/root:/bin/ash\nbin:x:1:1:bin:/bin:/sbin/nologin\n"
        )

        assertEquals("/bin/ash", detectRootfsShell(linuxDir))
    }

    @Test
    fun `detectRootfsShell uses passwd shell outside candidate list`() {
        val linuxDir = tmp.newFolder("linux")
        File(linuxDir, "usr/bin").mkdirs()
        File(linuxDir, "usr/bin/zsh").writeText("ELF")
        File(linuxDir, "etc").mkdirs()
        File(linuxDir, "etc/passwd").writeText("root:x:0:0:root:/root:/usr/bin/zsh\n")

        assertEquals("/usr/bin/zsh", detectRootfsShell(linuxDir))
    }

    @Test
    fun `detectRootfsShell falls back when passwd shell is missing`() {
        val linuxDir = tmp.newFolder("linux")
        File(linuxDir, "bin").mkdirs()
        File(linuxDir, "bin/sh").writeText("ELF")
        File(linuxDir, "etc").mkdirs()
        File(linuxDir, "etc/passwd").writeText("root:x:0:0:root:/root:/usr/bin/zsh\n")

        assertEquals("/bin/sh", detectRootfsShell(linuxDir))
    }

    @Test
    fun `detectRootfsShell ignores nologin passwd shell`() {
        val linuxDir = tmp.newFolder("linux")
        File(linuxDir, "bin").mkdirs()
        File(linuxDir, "bin/sh").writeText("ELF")
        File(linuxDir, "sbin").mkdirs()
        File(linuxDir, "sbin/nologin").writeText("ELF")
        File(linuxDir, "etc").mkdirs()
        File(linuxDir, "etc/passwd").writeText("root:x:0:0:root:/root:/sbin/nologin\n")

        assertEquals("/bin/sh", detectRootfsShell(linuxDir))
    }

    @Test
    fun `detectRootfsShell ignores empty passwd file`() {
        val linuxDir = tmp.newFolder("linux")
        File(linuxDir, "bin").mkdirs()
        File(linuxDir, "bin/sh").writeText("ELF")
        File(linuxDir, "etc").mkdirs()
        File(linuxDir, "etc/passwd").writeText("")

        assertEquals("/bin/sh", detectRootfsShell(linuxDir))
    }

    private fun createInstaller() = RootfsInstaller(WorkspaceManager(tmp.newFolder()))

    private fun OutputStream.writeTarEntry(name: String, type: Char, data: ByteArray) {
        val header = ByteArray(TAR_BLOCK)
        name.toByteArray(Charsets.UTF_8).copyInto(header, 0)
        "0000755".toByteArray().copyInto(header, 100)
        data.size.toLong().toOctalField().copyInto(header, 124)
        header[156] = type.code.toByte()
        write(header)
        write(data)
        val padding = (TAR_BLOCK - data.size % TAR_BLOCK) % TAR_BLOCK
        write(ByteArray(padding))
    }

    private fun Long.toOctalField(): ByteArray =
        toString(8).padStart(11, '0').toByteArray(Charsets.UTF_8)

    companion object {
        private const val TAR_BLOCK = 512
    }
}
