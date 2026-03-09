package me.rerere.rikkahub.data.ai.tools.termux

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
