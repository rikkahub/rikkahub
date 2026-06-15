package me.rerere.rikkahub.web.a2a

import org.junit.Assert.assertEquals
import org.junit.Test

class A2aEventOrderingTest {

    @Test
    fun `classifier emits submitted working artifacts then one terminal`() {
        val states = mutableListOf(A2aTaskState.SUBMITTED)

        classifyA2aTransition(
            jobPresent = true,
            prevJobPresent = false,
            doneForContext = false,
            newError = false,
            cancelRequested = false,
            pendingApproval = false,
        )?.let(states::add)

        val artifacts = listOf("hello")

        classifyA2aTransition(
            jobPresent = false,
            prevJobPresent = true,
            doneForContext = true,
            newError = false,
            cancelRequested = false,
            pendingApproval = false,
        )?.let(states::add)

        assertEquals(listOf(A2aTaskState.SUBMITTED, A2aTaskState.WORKING, A2aTaskState.COMPLETED), states)
        assertEquals(listOf("hello"), artifacts)
    }
}
