package me.rerere.rikkahub.web.a2a

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class A2aFailureClassificationTest {

    @Test
    fun `new tagged error fails the task`() {
        assertEquals(
            A2aTaskState.FAILED,
            classifyA2aTransition(
                jobPresent = false,
                prevJobPresent = true,
                doneForContext = false,
                newError = true,
                cancelRequested = false,
                pendingApproval = false,
            )
        )
    }

    @Test
    fun `unrelated errors do not affect the task`() {
        assertNull(
            classifyA2aTransition(
                jobPresent = false,
                prevJobPresent = true,
                doneForContext = false,
                newError = false,
                cancelRequested = false,
                pendingApproval = false,
            )
        )
    }
}
