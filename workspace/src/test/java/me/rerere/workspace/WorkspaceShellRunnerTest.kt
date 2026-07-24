package me.rerere.workspace

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random

class WorkspaceShellRunnerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun hostShellRunnerExecuteRawStreamsBinaryDataWithoutTruncationUnderLimit() {
        val workingDir = tempFolder.newFolder("work")
        val runner = HostShellRunner()
        val randomBytes = ByteArray(256 * 1024)
        Random(42).nextBytes(randomBytes)

        File(workingDir, "input.bin").writeBytes(randomBytes)

        val result = runner.executeRaw(
            context = shellContext(workingDir, "cat input.bin"),
            maxBytes = 8 * 1024 * 1024,
        )

        assertEquals(0, result.exitCode)
        assertFalse(result.truncated)
        assertFalse(result.timedOut)
        assertArrayEquals(randomBytes, result.stdout)
    }

    @Test
    fun hostShellRunnerExecuteRawTruncatesWhenExceedingLimit() {
        val workingDir = tempFolder.newFolder("work")
        val runner = HostShellRunner()
        val randomBytes = ByteArray(300 * 1024)
        Random(42).nextBytes(randomBytes)

        File(workingDir, "input.bin").writeBytes(randomBytes)

        val limit = 128 * 1024
        val result = runner.executeRaw(
            context = shellContext(workingDir, "cat input.bin"),
            maxBytes = limit,
        )

        assertEquals(0, result.exitCode)
        assertTrue(result.truncated)
        assertEquals(limit, result.stdout.size)
    }

    private fun shellContext(workingDir: File, command: String) = WorkspaceShellContext(
        root = "test",
        command = command,
        cwd = "",
        filesDir = workingDir,
        linuxDir = workingDir,
        tempDir = workingDir,
        workingDir = workingDir,
        timeoutMillis = 5000,
    )
}
