package me.rerere.rikkahub.web.a2a

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class A2aCancelIdempotentTest {

    @Test
    fun `repeated cancel keeps same terminal task state`() {
        val registry = A2aTaskRegistry()
        val accepted = registry.admit(Uuid.random(), Uuid.random(), "message") as A2aAdmission.Accepted

        registry.requestCancel(accepted.entry.taskId)
        val first = registry.transition(accepted.entry.taskId, A2aTaskState.CANCELED, terminal = true)
        registry.requestCancel(accepted.entry.taskId)
        val second = registry.transition(accepted.entry.taskId, A2aTaskState.CANCELED, terminal = true)

        assertEquals(accepted.entry.taskId, first?.taskId)
        assertEquals(null, second)
        assertEquals(A2aTaskState.CANCELED, registry.get(accepted.entry.taskId)?.state)
    }
}
