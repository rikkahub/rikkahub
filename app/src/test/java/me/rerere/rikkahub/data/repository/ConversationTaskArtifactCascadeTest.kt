package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.entity.TaskRunEntity
import me.rerere.rikkahub.data.db.entity.TaskRunStateTag
import me.rerere.rikkahub.data.db.entity.WorkItemDependencyEntity
import me.rerere.rikkahub.data.db.entity.WorkItemEntity
import me.rerere.rikkahub.data.repository.fakes.FakeTaskRunDAO
import me.rerere.rikkahub.data.repository.fakes.FakeWorkItemDAO
import me.rerere.ai.runtime.board.WorkItemStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Tests for [deleteConversationTaskArtifacts] (review finding #4). The new task_runs / work_items /
 * work_item_dependencies tables declare NO foreign key to the conversation (their @Entity blocks
 * have no foreignKeys), so Room's CASCADE never reaches them on conversation delete; the cascade
 * must be performed explicitly. These pin that the explicit cascade removes ALL three row kinds for
 * the deleted conversation — and ONLY that conversation's rows.
 */
class ConversationTaskArtifactCascadeTest {

    private fun taskRun(id: String, conversationId: Uuid): TaskRunEntity = TaskRunEntity(
        id = id,
        conversationId = conversationId.toString(),
        parentToolCallId = "",
        agentTypeId = "agent",
        prompt = "go",
        latestState = TaskRunStateTag.SUCCEEDED.name,
        createdAt = 0,
        updatedAt = 0,
    )

    private fun workItem(id: String, conversationId: Uuid): WorkItemEntity = WorkItemEntity(
        id = id,
        conversationId = conversationId.toString(),
        subject = "task",
        status = WorkItemStatus.Pending.name,
        createdAt = 0,
        updatedAt = 0,
    )

    @Test
    fun `cascade deletes task runs, work items, and dependency edges of the conversation`() = runBlocking {
        val taskRunDAO = FakeTaskRunDAO()
        val workItemDAO = FakeWorkItemDAO()
        val convo = Uuid.random()

        taskRunDAO.upsert(taskRun("run-1", convo))
        workItemDAO.insert(workItem("item-a", convo))
        workItemDAO.insert(workItem("item-b", convo))
        workItemDAO.insertDependency(
            WorkItemDependencyEntity(conversationId = convo.toString(), blockerId = "item-a", blockedId = "item-b")
        )

        deleteConversationTaskArtifacts(taskRunDAO, workItemDAO, convo)

        assertNull("the conversation's task run must be deleted", taskRunDAO.getById("run-1"))
        assertTrue("the conversation's work items must be deleted", workItemDAO.listByConversation(convo.toString()).isEmpty())
        assertTrue(
            "the conversation's dependency edges must be deleted (no FK cascade reaches them)",
            workItemDAO.listDependencies(convo.toString()).isEmpty(),
        )
    }

    @Test
    fun `cascade leaves another conversation's artifacts untouched`() = runBlocking {
        val taskRunDAO = FakeTaskRunDAO()
        val workItemDAO = FakeWorkItemDAO()
        val deleted = Uuid.random()
        val kept = Uuid.random()

        taskRunDAO.upsert(taskRun("run-del", deleted))
        taskRunDAO.upsert(taskRun("run-keep", kept))
        workItemDAO.insert(workItem("item-del", deleted))
        workItemDAO.insert(workItem("item-keep", kept))

        deleteConversationTaskArtifacts(taskRunDAO, workItemDAO, deleted)

        assertNull(taskRunDAO.getById("run-del"))
        assertEquals("run-keep", taskRunDAO.getById("run-keep")?.id)
        assertTrue(workItemDAO.listByConversation(deleted.toString()).isEmpty())
        assertEquals(listOf("item-keep"), workItemDAO.listByConversation(kept.toString()).map { it.id })
    }
}
