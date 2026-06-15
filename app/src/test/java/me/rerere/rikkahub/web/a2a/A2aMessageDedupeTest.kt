package me.rerere.rikkahub.web.a2a

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class A2aMessageDedupeTest {

    @Test
    fun `duplicate message id returns existing admission`() {
        val registry = A2aTaskRegistry()
        val contextId = Uuid.random()
        val assistantId = Uuid.random()
        val sourceMessageId = "message-id"

        val first = registry.admit(contextId, assistantId, sourceMessageId)
        val second = registry.admit(contextId, assistantId, sourceMessageId)

        val firstTaskId = (first as A2aAdmission.Accepted).entry.taskId
        val secondTaskId = (second as A2aAdmission.Duplicate).existing.taskId

        assertEquals(firstTaskId, secondTaskId)
        assertTrue(registry.tasks.containsKey(firstTaskId))
        assertTrue(registry.seenMessageIds[contextId]?.contains(sourceMessageId) == true)
    }
}
