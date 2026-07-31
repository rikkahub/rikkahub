package me.rerere.rikkahub.data.ai.agent

import me.rerere.rikkahub.data.model.ToolExecutionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentRunRuntimeTransitionTest {
    @Test
    fun `successful tool completion only accepts executable source states`() {
        assertEquals(
            setOf(ToolExecutionStatus.AUTHORIZED, ToolExecutionStatus.RUNNING),
            toolFinishSourceStatuses(ToolExecutionStatus.SUCCEEDED),
        )
    }

    @Test
    fun `tool completion rejects active target states`() {
        assertThrows(IllegalArgumentException::class.java) {
            toolFinishSourceStatuses(ToolExecutionStatus.RUNNING)
        }
    }
}
