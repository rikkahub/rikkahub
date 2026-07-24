package me.rerere.workspace

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Random

class ProotShellRunnerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun extraBindMountsAreIncludedInCommand() {
        val nativeDir = tempFolder.newFolder("native")
        val prootFile = File(nativeDir, "libproot_exec.so").apply { createNewFile() }
        File(nativeDir, "libproot_loader.so").apply { createNewFile() }
        val skillsDir = tempFolder.newFolder("skills")

        val runner = ProotShellRunner(
            nativeLibraryDir = nativeDir,
            extraBindMounts = listOf(
                WorkspaceBindMount(source = skillsDir, target = "/skills")
            )
        )

        val linuxDir = tempFolder.newFolder("linux").apply {
            File(this, "bin").mkdirs()
            File(this, "bin/sh").createNewFile()
        }
        val filesDir = tempFolder.newFolder("files")
        val tempDir = tempFolder.newFolder("temp")

        val context = WorkspaceShellContext(
            root = "test",
            command = "cat /skills/test.txt",
            cwd = "",
            filesDir = filesDir,
            linuxDir = linuxDir,
            tempDir = tempDir,
            workingDir = filesDir,
            timeoutMillis = 1000,
        )

        val method = ProotShellRunner::class.java.getDeclaredMethod(
            "buildCommand",
            WorkspaceShellContext::class.java,
            File::class.java
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val commandList = method.invoke(runner, context, prootFile) as List<String>

        assertTrue(commandList.contains("-b"))
        assertTrue(commandList.contains("${skillsDir.absolutePath}:/skills"))
    }

    @Test
    fun hostShellRunnerExecuteRawStreamsBinaryDataWithoutTruncationUnderLimit() {
        val workingDir = tempFolder.newFolder("work")
        val runner = HostShellRunner()
        val randomBytes = ByteArray(256 * 1024)
        Random(42).nextBytes(randomBytes)

        val context = WorkspaceShellContext(
            root = "test",
            command = "cat input.bin",
            cwd = "",
            filesDir = workingDir,
            linuxDir = workingDir,
            tempDir = workingDir,
            workingDir = workingDir,
            timeoutMillis = 5000,
        )
        File(workingDir, "input.bin").writeBytes(randomBytes)

        val output = ByteArrayOutputStream()
        val result = runner.executeRaw(
            context = context,
            maxBytes = 8 * 1024 * 1024,
            outputStream = output,
        )

        assertEquals(0, result.exitCode)
        assertFalse(result.truncated)
        assertFalse(result.timedOut)
        assertArrayEquals(randomBytes, output.toByteArray())
    }

    @Test
    fun hostShellRunnerExecuteRawTruncatesWhenExceedingLimit() {
        val workingDir = tempFolder.newFolder("work")
        val runner = HostShellRunner()
        val randomBytes = ByteArray(300 * 1024)
        Random(42).nextBytes(randomBytes)

        val context = WorkspaceShellContext(
            root = "test",
            command = "cat input.bin",
            cwd = "",
            filesDir = workingDir,
            linuxDir = workingDir,
            tempDir = workingDir,
            workingDir = workingDir,
            timeoutMillis = 5000,
        )
        File(workingDir, "input.bin").writeBytes(randomBytes)

        val output = ByteArrayOutputStream()
        val limit = 128 * 1024
        val result = runner.executeRaw(
            context = context,
            maxBytes = limit,
            outputStream = output,
        )

        assertEquals(0, result.exitCode)
        assertTrue(result.truncated)
        assertEquals(limit, output.toByteArray().size)
    }

    @Test
    fun hostShellRunnerExecuteRawPassesStdinToProcessAndNotStdout() {
        val workingDir = tempFolder.newFolder("work")
        val runner = HostShellRunner()
        val inputBytes = ByteArray(64 * 1024)
        Random(123).nextBytes(inputBytes)

        val context = WorkspaceShellContext(
            root = "test",
            command = "cat",
            cwd = "",
            filesDir = workingDir,
            linuxDir = workingDir,
            tempDir = workingDir,
            workingDir = workingDir,
            timeoutMillis = 5000,
            stdin = inputBytes,
        )

        val output = ByteArrayOutputStream()
        val result = runner.executeRaw(
            context = context,
            maxBytes = 1 * 1024 * 1024,
            outputStream = output,
        )

        assertEquals(0, result.exitCode)
        assertFalse(result.truncated)
        assertFalse(result.timedOut)
        assertArrayEquals(inputBytes, output.toByteArray())
    }

    @Test
    fun hostShellRunnerExecuteRawPropagatesOutputStreamIOException() {
        val workingDir = tempFolder.newFolder("work")
        val runner = HostShellRunner()

        val context = WorkspaceShellContext(
            root = "test",
            command = "echo hello",
            cwd = "",
            filesDir = workingDir,
            linuxDir = workingDir,
            tempDir = workingDir,
            workingDir = workingDir,
            timeoutMillis = 5000,
        )

        val failingOutputStream = object : OutputStream() {
            override fun write(b: Int) {
                throw IOException("Write failed")
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                throw IOException("Write failed")
            }
        }

        try {
            runner.executeRaw(
                context = context,
                maxBytes = 1024,
                outputStream = failingOutputStream,
            )
            fail("Expected IOException to be thrown when output stream fails")
        } catch (e: IOException) {
            assertEquals("Write failed", e.message)
        }
    }

    @Test
    fun workspaceManagerRejectsNegativeMaxBytes() {
        val baseDir = tempFolder.newFolder("workspace-base")
        val manager = WorkspaceManager(baseDir)
        val root = "test-workspace"
        manager.ensureWorkspace(root)

        try {
            manager.executeRawCommand(
                root = root,
                command = "echo hello",
                maxBytes = -1,
                outputStream = ByteArrayOutputStream(),
            )
            fail("Expected IllegalArgumentException for negative maxBytes")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("maxBytes must be non-negative") == true)
        }
    }
}
