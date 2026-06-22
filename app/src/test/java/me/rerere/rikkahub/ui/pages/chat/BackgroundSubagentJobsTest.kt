package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.db.entity.TaskRunEntity
import me.rerere.rikkahub.data.db.entity.TaskRunEventSummary
import me.rerere.rikkahub.data.db.entity.TaskRunStateTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundSubagentJobsTest {
    private val conversationId = "00000000-0000-0000-0000-000000000001"
    private val otherConversationId = "00000000-0000-0000-0000-000000000002"

    @Test
    fun `only in-flight background runs of this conversation map to jobs`() {
        val jobs = mapBackgroundSubagentJobs(
            listOf(
                row("bg-running", conversationId, TaskRunStateTag.RUNNING, background = true),
                row("bg-queued", conversationId, TaskRunStateTag.QUEUED, background = true),
                row("foreground", conversationId, TaskRunStateTag.RUNNING, background = false),
                row("bg-terminal", conversationId, TaskRunStateTag.SUCCEEDED, background = true),
                row("bg-other-conv", otherConversationId, TaskRunStateTag.RUNNING, background = true),
            ),
            conversationId,
        )

        assertEquals(setOf("bg-running", "bg-queued"), jobs.map { it.taskId }.toSet())
        assertTrue("every mapped job is a cancellable subagent", jobs.all {
            it.kind == BackgroundJobKind.Subagent && it.cancellable
        })
    }

    @Test
    fun `newest run sorts first and status is friendly`() {
        val jobs = mapBackgroundSubagentJobs(
            listOf(
                row("old", conversationId, TaskRunStateTag.RUNNING, background = true, createdAt = 1_000L),
                row("new", conversationId, TaskRunStateTag.WAITING_APPROVAL, background = true, createdAt = 2_000L),
            ),
            conversationId,
        )

        assertEquals(listOf("new", "old"), jobs.map { it.taskId })
        assertEquals("Waiting approval", jobs.first().status)
        assertEquals("Running", jobs.last().status)
    }

    @Test
    fun `command prefixes a non-generic agent type, detail uses the latest summary`() {
        val withType = mapBackgroundSubagentJobs(
            listOf(
                row("typed", conversationId, TaskRunStateTag.RUNNING, background = true, agentTypeId = "researcher")
                    .copy(prompt = "find the bug"),
            ),
            conversationId,
        ).single()
        assertEquals("researcher: find the bug", withType.command)
        assertEquals("the prompt seeds the detail when no summary exists yet", "find the bug", withType.detail)

        val generic = mapBackgroundSubagentJobs(
            listOf(
                row("generic", conversationId, TaskRunStateTag.RUNNING, background = true, agentTypeId = "agent")
                    .copy(
                        prompt = "do it",
                        eventSummaries = TaskRunEntity.encodeEventSummaries(
                            listOf(
                                TaskRunEventSummary(sequence = 1, summary = "step one"),
                                TaskRunEventSummary(sequence = 2, summary = "step two"),
                            )
                        ),
                    ),
            ),
            conversationId,
        ).single()
        assertEquals("a generic agent type is not prefixed", "do it", generic.command)
        assertEquals("detail tracks the latest summary", "step two", generic.detail)
    }

    private fun row(
        taskId: String,
        conversationId: String,
        state: TaskRunStateTag,
        background: Boolean,
        createdAt: Long = 1_000L,
        agentTypeId: String = "agent",
    ): TaskRunEntity = TaskRunEntity(
        id = taskId,
        conversationId = conversationId,
        parentToolCallId = "call",
        agentTypeId = agentTypeId,
        prompt = "p",
        latestState = state.name,
        createdAt = createdAt,
        updatedAt = createdAt,
        isBackground = background,
    )
}
