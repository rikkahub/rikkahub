package me.rerere.rikkahub.web.a2a

import org.junit.Assert.assertEquals
import org.junit.Test

class A2aBackgroundedShellCancelTest {

    @Test
    fun `cancel request classifies task as canceled even if tool continues in background`() {
        assertEquals(
            A2aTaskState.CANCELED,
            classifyA2aTransition(
                jobPresent = false,
                prevJobPresent = true,
                doneForContext = false,
                newError = false,
                cancelRequested = true,
                pendingApproval = false,
            )
        )
    }
}
