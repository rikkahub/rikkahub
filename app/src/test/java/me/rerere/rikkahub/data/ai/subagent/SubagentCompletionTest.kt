package me.rerere.rikkahub.data.ai.subagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.runtime.task.TaskBudgetBreach
import me.rerere.ai.runtime.task.TaskBudgetCap
import me.rerere.ai.runtime.task.TaskBudgetUsage
import me.rerere.ai.runtime.task.TaskState
import me.rerere.rikkahub.data.db.entity.AgentEventStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SubagentCompletionTest {

    private val conv = Uuid.parse("00000000-0000-0000-0000-0000000000c0")
    private val task = Uuid.parse("00000000-0000-0000-0000-0000000000a1")

    private fun payload(c: SubagentCompletion) = Json.parseToJsonElement(c.payloadJson).jsonObject

    @Test
    fun `succeeded carries status + summary, no error`() {
        val c = SubagentCompletion.of(conv, task, TaskState.Succeeded("all done"), steps = 3, tokens = 120)
        val p = payload(c)
        assertEquals("SUCCEEDED", p["status"]!!.jsonPrimitive.content)
        assertEquals("all done", p["summary"]!!.jsonPrimitive.content)
        assertNull(p["error"])
        assertEquals(task.toString(), p["taskId"]!!.jsonPrimitive.content)
        assertEquals(3, p["steps"]!!.jsonPrimitive.content.toInt())
        assertEquals(120L, p["tokens"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `failed carries error, no summary`() {
        val c = SubagentCompletion.of(conv, task, TaskState.Failed("boom"), steps = 1, tokens = 0)
        val p = payload(c)
        assertEquals("FAILED", p["status"]!!.jsonPrimitive.content)
        assertEquals("boom", p["error"]!!.jsonPrimitive.content)
        assertNull(p["summary"])
    }

    @Test
    fun `budget exhausted names the cap`() {
        val breach = TaskBudgetBreach(TaskBudgetCap.Tokens, TaskBudgetUsage(steps = 2, tokens = 999))
        val c = SubagentCompletion.of(conv, task, TaskState.BudgetExhausted(breach), steps = 2, tokens = 999)
        val p = payload(c)
        assertEquals("BUDGET_EXHAUSTED", p["status"]!!.jsonPrimitive.content)
        assertTrue(p["error"]!!.jsonPrimitive.content.contains("Tokens"))
    }

    @Test
    fun `non-terminal state fails closed as interrupted`() {
        val c = SubagentCompletion.of(conv, task, TaskState.Interrupted("progress"), steps = 0, tokens = 0)
        val p = payload(c)
        assertEquals("FAILED", p["status"]!!.jsonPrimitive.content)
        assertEquals("interrupted", p["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `dedupeKey is the taskId`() {
        val c = SubagentCompletion.of(conv, task, TaskState.Cancelled, steps = 0, tokens = 0)
        assertEquals(task.toString(), c.dedupeKey)
        assertEquals(SubagentCompletion.KIND, c.kind)
    }

    @Test
    fun `pending entity carries kind, payload, PENDING status, dedupeKey`() {
        val c = SubagentCompletion.of(conv, task, TaskState.Succeeded("x"), steps = 0, tokens = 0)
        val e = c.asPendingAgentEventEntity(eventId = "evt-1", enqueueSeq = 7, createdAt = 1000)
        assertEquals("evt-1", e.id)
        assertEquals(SubagentCompletion.KIND, e.kind)
        assertEquals(c.payloadJson, e.payloadJson)
        assertEquals(AgentEventStatus.PENDING.name, e.status)
        assertEquals(task.toString(), e.dedupeKey)
        assertEquals(conv.toString(), e.conversationId)
        assertEquals(7L, e.enqueueSeq)
        assertEquals(1000L, e.createdAt)
    }
}
