package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.db.entity.ShellRunEntity
import me.rerere.rikkahub.data.db.entity.ShellRunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundShellJobsTest {
    private val conversationId = "00000000-0000-0000-0000-000000000001"
    private val otherConversationId = "00000000-0000-0000-0000-000000000002"

    @Test
    fun `empty rows hide the background job indicator`() {
        val jobs = mapBackgroundShellJobs(emptyList(), conversationId)

        assertFalse(isBackgroundShellJobIndicatorVisible(jobs))
        assertEquals(0, backgroundShellJobIndicatorCount(jobs))
    }

    @Test
    fun `non-empty background rows show the indicator with correct count`() {
        val jobs = mapBackgroundShellJobs(
            listOf(
                row("detached", conversationId, ShellRunStatus.DETACHED, detachedAt = 2_000L),
                row("running", conversationId, ShellRunStatus.BACKGROUND_RUNNING, detachedAt = 1_000L),
            ),
            conversationId,
        )

        assertTrue(isBackgroundShellJobIndicatorVisible(jobs))
        assertEquals(2, backgroundShellJobIndicatorCount(jobs))
        assertEquals(listOf("detached", "running"), jobs.map { it.command })
    }

    @Test
    fun `mapper excludes foreground terminal and other-conversation rows`() {
        val jobs = mapBackgroundShellJobs(
            listOf(
                row("same conversation", conversationId, ShellRunStatus.BACKGROUND_RUNNING),
                row("foreground", conversationId, ShellRunStatus.FOREGROUND_WAITING),
                row("terminal", conversationId, ShellRunStatus.SUCCEEDED),
                row("other conversation", otherConversationId, ShellRunStatus.DETACHED),
            ),
            conversationId,
        )

        assertEquals(1, jobs.size)
        assertEquals("same conversation", jobs.single().command)
        assertEquals(conversationId, jobs.single().conversationId)
    }

    private fun row(
        command: String,
        conversationId: String,
        status: ShellRunStatus,
        detachedAt: Long? = 1_000L,
    ): ShellRunEntity =
        ShellRunEntity(
            taskId = "task-$command",
            conversationId = conversationId,
            workspaceId = "workspace",
            command = command,
            cwd = "",
            outputPath = "/tmp/output",
            status = status.name,
            createdAt = 100L,
            startedAt = 500L,
            detachedAt = detachedAt,
        )
}
