package me.rerere.rikkahub.subagent

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentRegistryTest {

    private fun makeRun(
        id: String,
        parentChat: String? = "chat-1",
        parentAssistant: String = "asst-1",
        status: SubAgentStatus = SubAgentStatus.PENDING,
    ): SubAgentRun = SubAgentRun(
        id = id,
        parentChatId = parentChat,
        parentAssistantId = parentAssistant,
        label = "label-$id",
        task = "task",
        modelId = null,
        tools = null,
        runInBackground = false,
        timeoutSeconds = SubAgentDefaults.DEFAULT_TIMEOUT_SECONDS,
        maxTrips = SubAgentDefaults.DEFAULT_MAX_TRIPS,
        status = status,
        startedAtMs = System.currentTimeMillis(),
    )

    @Test fun `addPending puts run in flow`() {
        val r = SubAgentRegistry()
        r.addPending(makeRun("a"))
        assertEquals(1, r.runs.value.size)
        assertEquals(SubAgentStatus.PENDING, r.runs.value["a"]?.status)
    }

    @Test fun `update transforms existing run`() {
        val r = SubAgentRegistry()
        r.addPending(makeRun("a"))
        r.update("a") { it.copy(status = SubAgentStatus.SUCCEEDED, result = "done") }
        assertEquals(SubAgentStatus.SUCCEEDED, r.runs.value["a"]?.status)
        assertEquals("done", r.runs.value["a"]?.result)
    }

    @Test fun `update on missing id is no-op`() {
        val r = SubAgentRegistry()
        r.update("nope") { it.copy(status = SubAgentStatus.SUCCEEDED) }
        assertTrue(r.runs.value.isEmpty())
    }

    @Test fun `list active_only filters terminal`() {
        val r = SubAgentRegistry()
        r.addPending(makeRun("running", status = SubAgentStatus.RUNNING))
        r.addPending(makeRun("done", status = SubAgentStatus.SUCCEEDED))
        r.addPending(makeRun("failed", status = SubAgentStatus.FAILED))
        assertEquals(3, r.list(activeOnly = false).size)
        assertEquals(1, r.list(activeOnly = true).size)
        assertEquals("running", r.list(activeOnly = true)[0].id)
    }

    @Test fun `activeCountForAssistant counts only matching parent in non-terminal`() {
        val r = SubAgentRegistry()
        r.addPending(makeRun("a1", parentAssistant = "asst-1", status = SubAgentStatus.RUNNING))
        r.addPending(makeRun("a2", parentAssistant = "asst-1", status = SubAgentStatus.SUCCEEDED))
        r.addPending(makeRun("b1", parentAssistant = "asst-2", status = SubAgentStatus.RUNNING))
        assertEquals(1, r.activeCountForAssistant("asst-1"))
        assertEquals(1, r.activeCountForAssistant("asst-2"))
    }

    @Test fun `requestCancel returns false when no job registered`() {
        val r = SubAgentRegistry()
        r.addPending(makeRun("a"))  // no Job
        assertFalse(r.requestCancel("a"))
    }

    @Test fun `cancelAllForParent returns count of cancellable jobs`() {
        val r = SubAgentRegistry()
        // Three runs from same parent chat, with mock Jobs (we fake them via SupervisorJob)
        for (id in listOf("a", "b", "c")) {
            val job = Job()
            r.addPending(makeRun(id, parentChat = "chat-X", status = SubAgentStatus.RUNNING), job = job)
        }
        // One run from a different parent
        r.addPending(
            makeRun("other", parentChat = "chat-Y", status = SubAgentStatus.RUNNING),
            job = Job(),
        )
        // One terminal run from chat-X — should not be cancelled
        r.addPending(makeRun("done", parentChat = "chat-X", status = SubAgentStatus.SUCCEEDED), job = null)
        assertEquals(3, r.cancelAllForParent("chat-X"))
    }

    @Test fun `unknown id returns null on get`() {
        val r = SubAgentRegistry()
        assertNull(r.get("nonexistent"))
    }
}
