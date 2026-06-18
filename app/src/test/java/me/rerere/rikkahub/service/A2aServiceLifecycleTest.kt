package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Test

class A2aServiceLifecycleTest {

    @Test
    fun `running state keeps the service in the foreground`() {
        assertEquals(
            A2aServiceLifecycleAction.RUNNING,
            a2aServiceLifecycleAction(wasRunning = false, isRunning = true, isLoading = false, hasError = false),
        )
        // running wins even if a stale error/loading flag is set alongside
        assertEquals(
            A2aServiceLifecycleAction.RUNNING,
            a2aServiceLifecycleAction(wasRunning = true, isRunning = true, isLoading = true, hasError = true),
        )
    }

    @Test
    fun `loading is a no-op so the starting service is not torn down mid-start`() {
        assertEquals(
            A2aServiceLifecycleAction.NONE,
            a2aServiceLifecycleAction(wasRunning = false, isRunning = false, isLoading = true, hasError = false),
        )
    }

    @Test
    fun `initial idle state before start is a no-op`() {
        // the observer sees the default A2aServerState() first: nothing happened yet
        assertEquals(
            A2aServiceLifecycleAction.NONE,
            a2aServiceLifecycleAction(wasRunning = false, isRunning = false, isLoading = false, hasError = false),
        )
    }

    @Test
    fun `failed start that never ran stops the service AND clears the enable flag`() {
        assertEquals(
            A2aServiceLifecycleAction.STOP_AND_DISABLE,
            a2aServiceLifecycleAction(wasRunning = false, isRunning = false, isLoading = false, hasError = true),
        )
    }

    @Test
    fun `stop after a successful run tears down without touching the enable flag`() {
        assertEquals(
            A2aServiceLifecycleAction.STOP,
            a2aServiceLifecycleAction(wasRunning = true, isRunning = false, isLoading = false, hasError = false),
        )
        // a run that ended with an error is still a plain STOP — the user had enabled it knowingly
        assertEquals(
            A2aServiceLifecycleAction.STOP,
            a2aServiceLifecycleAction(wasRunning = true, isRunning = false, isLoading = false, hasError = true),
        )
    }
}
