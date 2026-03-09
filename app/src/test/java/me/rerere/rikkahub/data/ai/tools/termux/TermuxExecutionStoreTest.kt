package me.rerere.rikkahub.data.ai.tools.termux

import java.nio.file.Files
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxExecutionStoreTest {
    @Test
    fun `markCompleted should persist result and clear pending`() {
        val store = TermuxExecutionStore(
            baseDir = Files.createTempDirectory("termux-execution-store").toFile(),
            json = JsonInstant,
        )

        store.recordPending(
            TermuxPendingExecutionRecord(
                executionId = "execution-1",
                commandPath = "/bin/bash",
                arguments = listOf("-lc", "echo test"),
                workdir = "/tmp",
                label = "demo",
            )
        )
        store.markCompleted(
            executionId = "execution-1",
            result = TermuxResult(stdout = "done", exitCode = 0),
        )

        assertNull(store.readPending("execution-1"))
        assertEquals(
            TermuxResult(stdout = "done", exitCode = 0),
            store.takeCompletedResult("execution-1"),
        )
        assertNull(store.takeCompletedResult("execution-1"))
    }

    @Test
    fun `clearAll should remove persisted files`() {
        val baseDir = Files.createTempDirectory("termux-execution-store").toFile()
        val store = TermuxExecutionStore(
            baseDir = baseDir,
            json = JsonInstant,
        )

        store.recordPending(
            TermuxPendingExecutionRecord(
                executionId = "execution-2",
                commandPath = "/bin/bash",
                workdir = "/tmp",
            )
        )
        store.markCompleted(
            executionId = "execution-2",
            result = TermuxResult(stderr = "warn", exitCode = 1),
        )
        store.clearAll("execution-2")

        assertNull(store.readPending("execution-2"))
        assertNull(store.takeCompletedResult("execution-2"))
        assertTrue(baseDir.exists())
        assertFalse(baseDir.resolve("pending/execution-2.json").exists())
        assertFalse(baseDir.resolve("results/execution-2.json").exists())
    }
}
