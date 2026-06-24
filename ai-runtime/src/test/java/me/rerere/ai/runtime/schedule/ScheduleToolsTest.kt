package me.rerere.ai.runtime.schedule

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.runtime.contract.MisfirePolicy
import me.rerere.ai.runtime.contract.ScheduleDraft
import me.rerere.ai.runtime.contract.ScheduleKind
import me.rerere.ai.runtime.contract.ScheduleMutationResult
import me.rerere.ai.runtime.contract.ScheduleOwner
import me.rerere.ai.runtime.contract.ScheduleSnapshot
import me.rerere.ai.runtime.contract.TaskSchedulePort
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Unit tests for the neutral, port-backed schedule-tool factories (SPEC.md M4 / task T6). Drives
 * [buildScheduleTools] against a recording [FakeSchedulePort] and pins:
 *  - the three wire names `schedule_create / schedule_list / schedule_delete`,
 *  - the per-tool `needsApproval` surface (create/delete = true, list = false read-only),
 *  - input parsing (required args, uuid/kind wire formats, recurrenceSpec passthrough,
 *    malformed-input errors thrown via `error()` before the port is reached),
 *  - that EVERY mutation routes through [TaskSchedulePort] — the fake is the tools' only
 *    collaborator, so a recorded call is proof of the single repository-enforced path,
 *  - that repository rejections (cap breach, unspawnable target, cross-conversation id) surface as
 *    structured `{ok:false, error}` tool output instead of crashing the turn.
 */
class ScheduleToolsTest {

    private val json = Json

    private val scheduleId = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val targetId = Uuid.parse("00000000-0000-0000-0000-000000000002")

    private fun snapshot(
        id: Uuid = scheduleId,
        target: Uuid = targetId,
        kind: ScheduleKind = ScheduleKind.ONE_SHOT,
        recurrenceSpec: String? = null,
    ) = ScheduleSnapshot(
        id = id,
        targetAssistantId = target,
        prompt = "morning briefing",
        owner = ScheduleOwner.AGENT,
        kind = kind,
        firstFireAt = 1_000L,
        nextFireAt = 1_000L,
        timeZoneId = "America/New_York",
        recurrenceSpec = recurrenceSpec,
        misfirePolicy = MisfirePolicy.FIRE_ONCE_AND_COALESCE,
        enabled = true,
        lastFiredAt = null,
        lastTaskRunId = null,
        runningTaskRunId = null,
    )

    private class FakeSchedulePort : TaskSchedulePort {
        val created = mutableListOf<ScheduleDraft>()
        val deleted = mutableListOf<Uuid>()
        var listCalls = 0

        var createResult: ScheduleMutationResult? = null
        var listResult: List<ScheduleSnapshot> = emptyList()
        var deleteResult: ScheduleMutationResult? = null

        override suspend fun create(draft: ScheduleDraft): ScheduleMutationResult {
            created += draft
            return createResult ?: error("createResult not configured")
        }

        override suspend fun list(): List<ScheduleSnapshot> {
            listCalls++
            return listResult
        }

        override suspend fun delete(id: Uuid): ScheduleMutationResult {
            deleted += id
            return deleteResult ?: error("deleteResult not configured")
        }
    }

    private fun tool(port: TaskSchedulePort, name: String): Tool =
        buildScheduleTools(port).single { it.name == name }

    private fun execute(port: TaskSchedulePort, name: String, args: JsonElement): JsonElement = runBlocking {
        val parts = tool(port, name).execute(args)
        json.parseToJsonElement((parts.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `builds exactly the three schedule tools`() {
        val tools = buildScheduleTools(FakeSchedulePort())
        assertEquals(
            listOf("schedule_create", "schedule_list", "schedule_delete"),
            tools.map { it.name },
        )
    }

    @Test
    fun `create and delete are approval-gated, list is read-only`() {
        val tools = buildScheduleTools(FakeSchedulePort())
        assertTrue(tools.single { it.name == "schedule_create" }.needsApproval)
        assertTrue(tools.single { it.name == "schedule_delete" }.needsApproval)
        assertFalse(tools.single { it.name == "schedule_list" }.needsApproval)
    }

    @Test
    fun `create description clarifies target uuid and recurrence time semantics`() {
        val description = tool(FakeSchedulePort(), "schedule_create").description

        assertTrue(description.contains("not the assistant's display name"))
        assertTrue(description.contains("MINUTES/HOURS are fixed"))
        assertTrue(description.contains("pins the local HH:mm"))
        assertTrue(description.contains("{ok:false,error}"))
    }

    @Test
    fun `create parses a one-shot draft and routes it through the port`() {
        val port = FakeSchedulePort().apply { createResult = ScheduleMutationResult.Accepted(snapshot()) }
        val out = execute(port, "schedule_create", buildJsonObject {
            put("targetAssistant", targetId.toString())
            put("prompt", "morning briefing")
            put("kind", "one_shot")
            put("firstFireAt", 1_000L)
            put("timeZoneId", "America/New_York")
        })
        assertEquals(
            listOf(
                ScheduleDraft(
                    targetAssistantId = targetId,
                    prompt = "morning briefing",
                    kind = ScheduleKind.ONE_SHOT,
                    firstFireAt = 1_000L,
                    timeZoneId = "America/New_York",
                    recurrenceSpec = null,
                )
            ),
            port.created,
        )
        val obj = out.jsonObject
        assertTrue(obj["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(scheduleId.toString(), obj["schedule"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `create passes the recurrence spec through as a serialized json string`() {
        val recurrenceJson = buildJsonObject {
            put("every", 1)
            put("unit", "DAYS")
            put("timeOfDay", "09:00")
        }
        val port = FakeSchedulePort().apply {
            createResult = ScheduleMutationResult.Accepted(
                snapshot(kind = ScheduleKind.RECURRING, recurrenceSpec = recurrenceJson.toString())
            )
        }
        execute(port, "schedule_create", buildJsonObject {
            put("targetAssistant", targetId.toString())
            put("prompt", "daily briefing")
            put("kind", "recurring")
            put("firstFireAt", 1_000L)
            put("timeZoneId", "America/New_York")
            put("recurrenceSpec", recurrenceJson)
        })
        val draft = port.created.single()
        assertEquals(ScheduleKind.RECURRING, draft.kind)
        // The port carries the spec as already-serialized JSON; parsing it back must round-trip.
        assertEquals(recurrenceJson, json.parseToJsonElement(draft.recurrenceSpec!!).jsonObject)
    }

    @Test
    fun `create without prompt errors before reaching the port`() {
        val port = FakeSchedulePort()
        assertThrows(IllegalStateException::class.java) {
            execute(port, "schedule_create", buildJsonObject {
                put("targetAssistant", targetId.toString())
                put("kind", "one_shot")
                put("firstFireAt", 1_000L)
                put("timeZoneId", "America/New_York")
            })
        }
        assertTrue(port.created.isEmpty())
    }

    @Test
    fun `create with an unknown kind errors before reaching the port`() {
        val port = FakeSchedulePort()
        assertThrows(IllegalStateException::class.java) {
            execute(port, "schedule_create", buildJsonObject {
                put("targetAssistant", targetId.toString())
                put("prompt", "x")
                put("kind", "weekly")
                put("firstFireAt", 1_000L)
                put("timeZoneId", "America/New_York")
            })
        }
        assertTrue(port.created.isEmpty())
    }

    @Test
    fun `create with a malformed target uuid errors before reaching the port`() {
        val port = FakeSchedulePort()
        assertThrows(IllegalStateException::class.java) {
            execute(port, "schedule_create", buildJsonObject {
                put("targetAssistant", "not-a-uuid")
                put("prompt", "x")
                put("kind", "one_shot")
                put("firstFireAt", 1_000L)
                put("timeZoneId", "America/New_York")
            })
        }
        assertTrue(port.created.isEmpty())
    }

    @Test
    fun `create surfaces a port rejection as structured error output`() {
        val port = FakeSchedulePort().apply {
            createResult = ScheduleMutationResult.Rejected("active-schedule cap reached for this conversation")
        }
        val out = execute(port, "schedule_create", buildJsonObject {
            put("targetAssistant", targetId.toString())
            put("prompt", "x")
            put("kind", "one_shot")
            put("firstFireAt", 1_000L)
            put("timeZoneId", "America/New_York")
        })
        val obj = out.jsonObject
        assertFalse(obj["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(
            "active-schedule cap reached for this conversation",
            obj["error"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `list routes through the port and returns the schedules`() {
        val port = FakeSchedulePort().apply {
            listResult = listOf(
                snapshot(kind = ScheduleKind.RECURRING, recurrenceSpec = "{\"every\":1,\"unit\":\"DAYS\"}")
            )
        }
        val out = execute(port, "schedule_list", buildJsonObject { })
        assertEquals(1, port.listCalls)
        val obj = out.jsonObject
        assertTrue(obj["ok"]!!.jsonPrimitive.content.toBoolean())
        val schedules = obj["schedules"]!!.jsonArray
        assertEquals(1, schedules.size)
        val first = schedules.single().jsonObject
        assertEquals(scheduleId.toString(), first["id"]!!.jsonPrimitive.content)
        assertEquals("recurring", first["kind"]!!.jsonPrimitive.content)
    }

    @Test
    fun `delete parses the id and routes it through the port`() {
        val port = FakeSchedulePort().apply {
            deleteResult = ScheduleMutationResult.Accepted(snapshot())
        }
        val out = execute(port, "schedule_delete", buildJsonObject { put("id", scheduleId.toString()) })
        assertEquals(listOf(scheduleId), port.deleted)
        assertTrue(out.jsonObject["ok"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `delete with a malformed uuid errors before reaching the port`() {
        val port = FakeSchedulePort()
        assertThrows(IllegalStateException::class.java) {
            execute(port, "schedule_delete", buildJsonObject { put("id", "not-a-uuid") })
        }
        assertTrue(port.deleted.isEmpty())
    }

    @Test
    fun `delete surfaces a cross-conversation rejection as structured error output`() {
        val port = FakeSchedulePort().apply {
            deleteResult = ScheduleMutationResult.Rejected("schedule not found: $scheduleId")
        }
        val out = execute(port, "schedule_delete", buildJsonObject { put("id", scheduleId.toString()) })
        val obj = out.jsonObject
        assertFalse(obj["ok"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(obj["error"]!!.jsonPrimitive.content.contains(scheduleId.toString()))
    }
}
