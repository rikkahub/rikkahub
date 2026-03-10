package me.rerere.rikkahub.data.ai.tools.termux

import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import me.rerere.rikkahub.utils.JsonInstant

class TermuxCommandManagerTest {
    @Test
    fun `completeDeferredBeforePersist should release waiter before persistence work`() = runBlocking {
        val deferred = CompletableDeferred<TermuxResult>()
        val result = TermuxResult(exitCode = 0)
        var completedDuringPersistence = false

        completeDeferredBeforePersist(
            deferred = deferred,
            result = result,
        ) {
            completedDuringPersistence = deferred.isCompleted
        }

        assertTrue(completedDuringPersistence)
        assertEquals(result, deferred.await())
    }

    @Test
    fun `clearExecutionStoreAfterTermination should remove pending and completed files`() = runBlocking {
        val executionId = "execution-1"
        val store = TermuxExecutionStore(
            baseDir = Files.createTempDirectory("termux-command-store").toFile(),
            json = JsonInstant,
        )
        store.recordPending(
            TermuxPendingExecutionRecord(
                executionId = executionId,
                commandPath = "/bin/sh",
                workdir = "/tmp",
            )
        )
        store.markCompleted(executionId, TermuxResult(exitCode = 0))

        val message = clearExecutionStoreAfterTermination(
            executionStore = store,
            executionId = executionId,
        ) {
            "terminated"
        }

        assertEquals("terminated", message)
        assertFalse(store.pendingFileExistsForTest(executionId))
        assertFalse(store.resultFileExistsForTest(executionId))
    }

    private fun TermuxExecutionStore.pendingFileExistsForTest(executionId: String): Boolean {
        val method = javaClass.getDeclaredMethod("pendingFile", String::class.java)
        method.isAccessible = true
        return (method.invoke(this, executionId) as java.io.File).exists()
    }

    private fun TermuxExecutionStore.resultFileExistsForTest(executionId: String): Boolean {
        val method = javaClass.getDeclaredMethod("resultFile", String::class.java)
        method.isAccessible = true
        return (method.invoke(this, executionId) as java.io.File).exists()
    }
}
