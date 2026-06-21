package me.rerere.workspace

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.withEdgecases
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WorkspaceFileSystemHardeningTest {

    private val fs = WorkspaceFileSystem()

    @Test
    fun `delete and move reject root aliases and null byte paths`() {
        val root = Files.createTempDirectory("workspace-delete-move-hardening").toFile()
        File(root, "source.txt").writeText("hello")

        listOf("", ".", " ", "./", "foo/..", " . ", "\u0000", " \u0000 ", "a/b/../../..").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { fs.delete(root, path) }
            assertThrows(IllegalArgumentException::class.java) { fs.delete(root, path, recursive = true) }
            assertThrows(IllegalArgumentException::class.java) { fs.move(root, path, "target") }
            assertThrows(IllegalArgumentException::class.java) { fs.move(root, "source.txt", path) }
        }
    }

    @Test
    fun `delete preserves outside canary for generated model paths`() {
        runBlocking {
            checkAll(200, arbModelPath().withEdgecases("", ".", ".", " ", "./", "foo/..", " . ", "a/b/../../..")) { model ->
                val root = Files.createTempDirectory("workspace-delete-prop").toFile()
                val outside = Files.createTempDirectory("workspace-delete-outside").toFile()
                val canary = File(outside, "outside.txt").apply { writeText("keep-me") }

                val path = if (isRootAlias(model)) model else "payload/$model"
                if (isRootAlias(model)) {
                    assertThrows(IllegalArgumentException::class.java) { fs.delete(root, path) }
                } else {
                    val victim = File(root, path).apply { mkdirs() }
                    val escape = File(victim, "escape").toPath()
                    Files.createSymbolicLink(escape, canary.toPath())
                    fs.delete(root, path, recursive = true)
                    assertFalse(victim.exists())
                }

                assertEquals("keep-me", canary.readText())
            }
        }
    }

    @Test
    fun `symlink delete invariant removes symlink as a link and preserves outside target`() {
        val root = Files.createTempDirectory("workspace-delete-symlink").toFile()
        val outside = Files.createTempDirectory("workspace-delete-symlink-outside").toFile()
        val canary = File(outside, "secret.txt").apply { writeText("secret") }
        val victim = File(root, "victim").apply { mkdirs() }
        Files.createSymbolicLink(File(victim, "escape").toPath(), canary.toPath())
        File(victim, "data.txt").writeText("in-victim")

        fs.delete(root, "victim", recursive = true)

        assertFalse(victim.exists())
        assertEquals("secret", canary.readText())
    }

    @Test
    fun `move overwrite keeps outside canary when target is symlinked outside`() {
        val root = Files.createTempDirectory("workspace-move-symlink").toFile()
        val outside = Files.createTempDirectory("workspace-move-outside").toFile()
        val canary = File(outside, "secret.txt").apply { writeText("secret") }

        val source = File(root, "source").apply { mkdirs() }
        File(source, "data.txt").writeText("source")
        val target = File(root, "target").apply { mkdirs() }
        Files.createSymbolicLink(File(target, "escape").toPath(), canary.toPath())

        fs.move(root, "source", "target", overwrite = true)

        assertEquals("source", File(root, "target/data.txt").readText())
        assertFalse(File(root, "source").exists())
        assertEquals("secret", canary.readText())
    }

    @Test
    fun `delete of an in-root symlink removes only the link and preserves its in-root target`() {
        // Regression: a `link -> real` entry lists with its own name but resolved to `real`, so the
        // file browser's delete followed the link and wiped the (separately visible) `real` dir.
        val root = Files.createTempDirectory("workspace-delete-inroot-symlink").toFile()
        val realDir = File(root, "real").apply { mkdirs() }
        File(realDir, "keep.txt").writeText("keep")
        Files.createSymbolicLink(File(root, "link").toPath(), realDir.toPath())

        assertTrue(fs.delete(root, "link", recursive = true))

        assertFalse("the symlink itself must be removed", File(root, "link").exists())
        assertTrue("the symlink's in-root target must survive", realDir.exists())
        assertEquals("keep", File(realDir, "keep.txt").readText())
    }

    @Test
    fun `move of an in-root symlink moves the link and preserves its in-root target`() {
        // Entries report logical paths, so a listed `link -> real` round-tripped into move must
        // relocate the link, not follow it and rename the (separately visible) `real` dir.
        val root = Files.createTempDirectory("workspace-move-inroot-symlink").toFile()
        val realDir = File(root, "real").apply { mkdirs() }
        File(realDir, "keep.txt").writeText("keep")
        Files.createSymbolicLink(File(root, "link").toPath(), realDir.toPath())

        fs.move(root, "link", "moved")

        assertFalse("the original link path must be gone", File(root, "link").exists())
        assertTrue(
            "moved must be the relocated symlink",
            Files.isSymbolicLink(File(root, "moved").toPath()),
        )
        assertTrue("the symlink's in-root target must survive", realDir.exists())
        assertEquals("keep", File(realDir, "keep.txt").readText())
    }

    @Test
    fun `grep does not read through a symlink escaping the workspace root`() {
        val root = Files.createTempDirectory("workspace-grep-symlink").toFile()
        val outside = Files.createTempDirectory("workspace-grep-outside").toFile()
        File(outside, "secret.txt").writeText("TOPSECRET marker")
        Files.createSymbolicLink(File(root, "leak.txt").toPath(), File(outside, "secret.txt").toPath())

        val matches = fs.grep(root, "TOPSECRET")

        assertTrue("grep must not surface content read through an escaping symlink", matches.isEmpty())
        assertEquals("secret outside file must be untouched", "TOPSECRET marker", File(outside, "secret.txt").readText())
    }

    @Test
    fun `glob does not surface a symlink escaping the workspace root`() {
        val root = Files.createTempDirectory("workspace-glob-symlink").toFile()
        val outside = Files.createTempDirectory("workspace-glob-outside").toFile()
        File(outside, "secret.txt").writeText("x")
        File(root, "real.txt").writeText("y")
        Files.createSymbolicLink(File(root, "leak.txt").toPath(), File(outside, "secret.txt").toPath())

        val paths = fs.glob(root, "*.txt").map { it.path }.toSet()

        assertTrue("an in-root file is found", paths.contains("real.txt"))
        assertFalse("an escaping symlink must not be surfaced", paths.contains("leak.txt"))
    }

    @Test
    fun `list reports a symlink entry by its own path not the resolved target`() {
        val root = Files.createTempDirectory("workspace-list-symlink").toFile()
        val realDir = File(root, "real").apply { mkdirs() }
        Files.createSymbolicLink(File(root, "link").toPath(), realDir.toPath())

        val paths = fs.list(root).map { it.path }.toSet()
        assertTrue("symlink must list by its own path", paths.contains("link"))
        assertTrue(paths.contains("real"))
    }

    @Test
    fun `trailing slash child paths stay within workspace`() {
        val root = Files.createTempDirectory("workspace-trailing-slash").toFile()
        File(root, "dir").mkdir()
        File(root, "dir/inside.txt").writeText("ok")
        assertTrue(fs.delete(root, "dir/", recursive = true))
    }

    private fun arbModelPath(): Arb<String> = arbitrary {
        Arb.list(arbSegment(), 1..3)
            .bind()
            .joinToString("/")
    }

    private fun isRootAlias(path: String): Boolean = when {
        path.isBlank() -> true
        path == "." -> true
        path == " . " -> true
        path == " " -> true
        path == "./" -> true
        path == "foo/.." -> true
        path == "a/b/../../.." -> true
        path.contains('\u0000') -> true
        path.endsWith("/..") -> true
        else -> false
    }
}
