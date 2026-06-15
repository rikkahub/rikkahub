package me.rerere.rikkahub.web.a2a

import org.junit.Assert.assertEquals
import org.junit.Test

class A2aCompletedClassificationTest {

    @Test
    fun `done signal without pending approval or error completes task`() {
        assertEquals(
            A2aTaskState.COMPLETED,
            classifyA2aTransition(
                jobPresent = false,
                prevJobPresent = true,
                doneForContext = true,
                newError = false,
                cancelRequested = false,
                pendingApproval = false,
            )
        )
    }
}
