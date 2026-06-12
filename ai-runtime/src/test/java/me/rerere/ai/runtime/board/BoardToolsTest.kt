package me.rerere.ai.runtime.board

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.runtime.contract.BoardItemSnapshot
import me.rerere.ai.runtime.contract.BoardMutationResult
import me.rerere.ai.runtime.contract.TaskBoardPort
import me.rerere.ai.runtime.contract.WorkItemDraft
import me.rerere.ai.runtime.contract.WorkItemPatch
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Unit tests for the neutral, port-backed board-tool factories (SPEC.md M1). Drives
 * [buildBoardTools] against a recording [FakeBoardPort] and pins:
 *  - the four wire names `task_create/get/list/update` (spec assumption 5),
 *  - input parsing (required args, uuid/status/action wire formats, malformed-input errors),
 *  - that EVERY read and mutation routes through [TaskBoardPort] — the fake is the tools' only
 *    collaborator, so a recorded call is proof of the single repository-enforced path
 *    (maintainer decision #4),
 *  - that repository rejections (cycle, illegal transition) surface as structured
 *    `{ok:false, error}` tool output instead of crashing the turn.
 */
class BoardToolsTest {

    private val json = Json

    private val itemId = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val blockerId = Uuid.parse("00000000-0000-0000-0000-000000000002")
    private val conversationId = Uuid.parse("00000000-0000-0000-0000-0000000000aa")

    private fun snapshot(
        id: Uuid = itemId,
        subject: String = "write the report",
        status: WorkItemStatus = WorkItemStatus.Pending,
        blockedBy: List<Uuid> = emptyList(),
    ) = BoardItemSnapshot(
        item = WorkItem(
            id = id,
            conversationId = conversationId,
            subject = subject,
            status = status,
        ),
        blockedBy = blockedBy,
    )

    private class FakeBoardPort : TaskBoardPort {
        val created = mutableListOf<WorkItemDraft>()
        val fetched = mutableListOf<Uuid>()
        val listed = mutableListOf<Set<WorkItemStatus>?>()
        val patched = mutableListOf<WorkItemPatch>()

        var createResult: BoardMutationResult? = null
        var getResult: BoardItemSnapshot? = null
        var listResult: List<BoardItemSnapshot> = emptyList()
        var updateResult: BoardMutationResult? = null

        override suspend fun create(draft: WorkItemDraft): BoardMutationResult {
            created += draft
            return createResult ?: error("createResult not configured")
        }

        override suspend fun get(id: Uuid): BoardItemSnapshot? {
            fetched += id
            return getResult
        }

        override suspend fun list(statuses: Set<WorkItemStatus>?): List<BoardItemSnapshot> {
            listed += statuses
            return listResult
        }

        override suspend fun update(patch: WorkItemPatch): BoardMutationResult {
            patched += patch
            return updateResult ?: error("updateResult not configured")
        }
    }

    private fun tool(port: TaskBoardPort, name: String): Tool =
        buildBoardTools(port).single { it.name == name }

    private fun execute(port: TaskBoardPort, name: String, args: JsonElement): JsonElement = runBlocking {
        val parts = tool(port, name).execute(args)
        json.parseToJsonElement((parts.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `builds exactly the four board tools, none approval-gated`() {
        val tools = buildBoardTools(FakeBoardPort())
        assertEquals(
            listOf("task_create", "task_get", "task_list", "task_update"),
            tools.map { it.name },
        )
        assertTrue(tools.none { it.needsApproval })
    }

    @Test
    fun `create parses subject, description, activeForm, blockedBy and routes through the port`() {
        val port = FakeBoardPort().apply { createResult = BoardMutationResult.Accepted(snapshot()) }
        val out = execute(port, "task_create", buildJsonObject {
            put("subject", "write the report")
            put("description", "quarterly numbers")
            put("activeForm", "Writing the report")
            put("blockedBy", buildJsonArray { add(blockerId.toString()) })
        })
        assertEquals(
            listOf(
                WorkItemDraft(
                    subject = "write the report",
                    description = "quarterly numbers",
                    activeForm = "Writing the report",
                    blockedBy = listOf(blockerId),
                )
            ),
            port.created,
        )
        val obj = out.jsonObject
        assertTrue(obj["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(itemId.toString(), obj["item"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `create without subject errors before reaching the port`() {
        val port = FakeBoardPort()
        assertThrows(IllegalStateException::class.java) {
            execute(port, "task_create", buildJsonObject { put("description", "no subject") })
        }
        assertTrue(port.created.isEmpty())
    }

    @Test
    fun `create surfaces a port rejection as structured error output`() {
        val port = FakeBoardPort().apply {
            createResult = BoardMutationResult.Rejected("dependency cycle: a -> b -> a")
        }
        val out = execute(port, "task_create", buildJsonObject { put("subject", "cyclic") })
        val obj = out.jsonObject
        assertFalse(obj["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("dependency cycle: a -> b -> a", obj["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `get parses id, routes through the port, and returns the snapshot`() {
        val port = FakeBoardPort().apply {
            getResult = snapshot(status = WorkItemStatus.InProgress, blockedBy = listOf(blockerId))
        }
        val out = execute(port, "task_get", buildJsonObject { put("id", itemId.toString()) })
        assertEquals(listOf(itemId), port.fetched)
        val item = out.jsonObject["item"]!!.jsonObject
        assertEquals("write the report", item["subject"]!!.jsonPrimitive.content)
        assertEquals("in_progress", item["status"]!!.jsonPrimitive.content)
        assertEquals(
            listOf(blockerId.toString()),
            item["blockedBy"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `get of an unknown id returns structured not-found, not a crash`() {
        val port = FakeBoardPort() // getResult stays null
        val out = execute(port, "task_get", buildJsonObject { put("id", itemId.toString()) })
        val obj = out.jsonObject
        assertFalse(obj["ok"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(obj["error"]!!.jsonPrimitive.content.contains(itemId.toString()))
    }

    @Test
    fun `get with a malformed uuid errors before reaching the port`() {
        val port = FakeBoardPort()
        assertThrows(IllegalStateException::class.java) {
            execute(port, "task_get", buildJsonObject { put("id", "not-a-uuid") })
        }
        assertTrue(port.fetched.isEmpty())
    }

    @Test
    fun `list without a filter passes null statuses to the port`() {
        val port = FakeBoardPort().apply { listResult = listOf(snapshot()) }
        val out = execute(port, "task_list", buildJsonObject { })
        assertEquals(listOf<Set<WorkItemStatus>?>(null), port.listed)
        assertEquals(1, out.jsonObject["items"]!!.jsonArray.size)
    }

    @Test
    fun `list parses status wire names into the port filter`() {
        val port = FakeBoardPort()
        execute(port, "task_list", buildJsonObject {
            put("status", buildJsonArray {
                add("pending")
                add("in_progress")
            })
        })
        assertEquals(
            listOf<Set<WorkItemStatus>?>(setOf(WorkItemStatus.Pending, WorkItemStatus.InProgress)),
            port.listed,
        )
    }

    @Test
    fun `list with an unknown status errors before reaching the port`() {
        val port = FakeBoardPort()
        assertThrows(IllegalStateException::class.java) {
            execute(port, "task_list", buildJsonObject {
                put("status", buildJsonArray { add("doing") })
            })
        }
        assertTrue(port.listed.isEmpty())
    }

    @Test
    fun `update routes id, field edits, action, and new blockers through the port`() {
        val port = FakeBoardPort().apply {
            updateResult = BoardMutationResult.Accepted(snapshot(status = WorkItemStatus.Completed))
        }
        val out = execute(port, "task_update", buildJsonObject {
            put("id", itemId.toString())
            put("subject", "write the FULL report")
            put("action", "complete")
            put("blockedBy", buildJsonArray { add(blockerId.toString()) })
        })
        assertEquals(
            listOf(
                WorkItemPatch(
                    id = itemId,
                    subject = "write the FULL report",
                    action = WorkItemAction.Complete,
                    addBlockedBy = listOf(blockerId),
                )
            ),
            port.patched,
        )
        assertTrue(out.jsonObject["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(
            "completed",
            out.jsonObject["item"]!!.jsonObject["status"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `update with only field edits leaves the action null`() {
        val port = FakeBoardPort().apply { updateResult = BoardMutationResult.Accepted(snapshot()) }
        execute(port, "task_update", buildJsonObject {
            put("id", itemId.toString())
            put("description", "new description")
        })
        val patch = port.patched.single()
        assertNull(patch.action)
        assertEquals("new description", patch.description)
    }

    @Test
    fun `update with an unknown action errors before reaching the port`() {
        val port = FakeBoardPort()
        assertThrows(IllegalStateException::class.java) {
            execute(port, "task_update", buildJsonObject {
                put("id", itemId.toString())
                put("action", "destroy")
            })
        }
        assertTrue(port.patched.isEmpty())
    }

    @Test
    fun `update without id errors before reaching the port`() {
        val port = FakeBoardPort()
        assertThrows(IllegalStateException::class.java) {
            execute(port, "task_update", buildJsonObject { put("action", "claim") })
        }
        assertTrue(port.patched.isEmpty())
    }

    @Test
    fun `update surfaces an illegal-transition rejection as structured error output`() {
        val port = FakeBoardPort().apply {
            updateResult = BoardMutationResult.Rejected("illegal work-item transition: Complete on Pending")
        }
        val out = execute(port, "task_update", buildJsonObject {
            put("id", itemId.toString())
            put("action", "complete")
        })
        val obj = out.jsonObject
        assertFalse(obj["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(
            "illegal work-item transition: Complete on Pending",
            obj["error"]!!.jsonPrimitive.content,
        )
    }
}
