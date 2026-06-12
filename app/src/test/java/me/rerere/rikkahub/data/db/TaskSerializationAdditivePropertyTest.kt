package me.rerere.rikkahub.data.db

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import me.rerere.ai.runtime.board.WorkItem
import me.rerere.ai.runtime.board.WorkItemStatus
import me.rerere.common.json.JsonInstant
import me.rerere.rikkahub.data.db.entity.TaskRunEntity
import me.rerere.rikkahub.data.db.entity.TaskRunEventSummary
import me.rerere.rikkahub.data.db.entity.TaskRunPendingApproval
import me.rerere.rikkahub.data.db.entity.TaskRunStateTag
import me.rerere.rikkahub.data.db.entity.WorkItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * TASK_SERIALIZATION_ADDITIVE (SPEC.md M2): the task-run persistence layer is additive-only.
 *
 * The Room migration 24->25 introduces brand-new tables, so the serialization surface this
 * property pins is the JSON embedded in their TEXT columns ([TaskRunEventSummary] lists and
 * [TaskRunPendingApproval]) plus the entity<->domain mapping for work items:
 *
 * 1. "Legacy" payloads — minimal JSON carrying only the required keys — decode with the
 *    documented defaults (no MissingFieldException), exactly like the Assistant additive-field
 *    precedent (AssistantSubagentFieldsTest).
 * 2. New task summaries ROUNDTRIP: encode-then-decode is the identity for arbitrary values.
 * 3. Payloads written by a FUTURE version with extra unknown keys still decode today
 *    (JsonInstant ignoreUnknownKeys) — the other half of the additive contract.
 *
 * No message schema is touched by this migration, so UIMessageArb / the UIMessagePart roundtrip
 * suite need no extension (the spec mandates extending them ONLY when a persisted message schema
 * actually changes).
 */
class TaskSerializationAdditivePropertyTest {

    // --- generators -----------------------------------------------------------------------

    private val arbSummary: Arb<TaskRunEventSummary> = arbitrary {
        TaskRunEventSummary(
            sequence = Arb.long(0L..1_000_000L).bind(),
            summary = Arb.string(0..64).bind(),
            timestamp = Arb.long(0L..4_102_444_800_000L).bind(),
            kind = Arb.of("progress", "approval", "denied", "result").bind(),
        )
    }

    private val arbSummaries: Arb<List<TaskRunEventSummary>> = Arb.list(arbSummary, 0..16)

    private val arbPendingApproval: Arb<TaskRunPendingApproval> = arbitrary {
        TaskRunPendingApproval(
            childToolCallId = Arb.string(1..24).bind(),
            toolName = Arb.string(1..24).bind(),
        )
    }

    private val arbWorkItem: Arb<WorkItem> = arbitrary {
        val claimed = Arb.of(true, false).bind()
        WorkItem(
            id = Uuid.random(),
            conversationId = Uuid.random(),
            subject = Arb.string(0..32).bind(),
            description = Arb.string(0..64).bind(),
            activeForm = Arb.string(1..32).orNull(0.5).bind(),
            status = if (claimed) WorkItemStatus.InProgress else Arb.of(
                WorkItemStatus.Pending, WorkItemStatus.Completed, WorkItemStatus.Deleted
            ).bind(),
            ownerHandleId = if (claimed) Arb.string(1..16).bind() else null,
            ownerName = if (claimed) Arb.string(1..16).orNull(0.3).bind() else null,
        )
    }

    // --- 1. legacy JSON (required keys only) decodes with defaults -------------------------

    @Test
    fun `legacy event-summary JSON without the additive fields decodes with defaults`() {
        val legacyJson = """{"sequence":3,"summary":"fetched 2 sources"}"""

        val decoded = JsonInstant.decodeFromString<TaskRunEventSummary>(legacyJson)

        assertEquals(3L, decoded.sequence)
        assertEquals("fetched 2 sources", decoded.summary)
        assertEquals(0L, decoded.timestamp)
        assertEquals(TaskRunEventSummary.KIND_PROGRESS, decoded.kind)
    }

    @Test
    fun `legacy empty event-summaries column (the migration's empty-array default) decodes to an empty list`() {
        val entity = taskRunEntity(eventSummaries = "[]")
        assertEquals(emptyList<TaskRunEventSummary>(), entity.decodeEventSummaries())
    }

    @Test
    fun `corrupt event-summaries JSON decodes to null, not a throw and not a silent empty list`() {
        val entity = taskRunEntity(eventSummaries = "{not json")
        assertNull(entity.decodeEventSummaries())
    }

    @Test
    fun `absent pending approval decodes to null`() {
        assertNull(taskRunEntity(pendingApproval = null).decodePendingApproval())
    }

    // --- 2. new task summaries roundtrip ----------------------------------------------------

    @Test
    fun `event-summary lists survive the entity column encode-decode roundtrip`() {
        runBlocking {
            checkAll(arbSummaries) { summaries ->
                val entity = taskRunEntity(
                    eventSummaries = TaskRunEntity.encodeEventSummaries(summaries),
                )
                assertEquals(summaries, entity.decodeEventSummaries())
            }
        }
    }

    @Test
    fun `pending approvals survive the entity column encode-decode roundtrip`() {
        runBlocking {
            checkAll(arbPendingApproval) { approval ->
                val entity = taskRunEntity(
                    pendingApproval = TaskRunEntity.encodePendingApproval(approval),
                )
                assertEquals(approval, entity.decodePendingApproval())
            }
        }
    }

    @Test
    fun `work items survive the domain-entity-domain mapping roundtrip`() {
        runBlocking {
            checkAll(arbWorkItem) { item ->
                val entity = WorkItemEntity.fromWorkItem(
                    item = item,
                    createdAt = 1L,
                    updatedAt = 2L,
                )
                assertEquals(item, entity.toWorkItem())
            }
        }
    }

    // --- 3. forward compatibility: unknown keys from a future version are ignored ----------

    @Test
    fun `event summaries written by a future version with extra fields decode today`() {
        runBlocking {
            checkAll(arbSummary, Arb.string(0..32)) { summary, extra ->
                val futureJson = JsonInstant.encodeToString(summary)
                    .removeSuffix("}") + ",\"futureField\":${JsonInstant.encodeToString(extra)}}"
                assertEquals(summary, JsonInstant.decodeFromString<TaskRunEventSummary>(futureJson))
            }
        }
    }

    // --- state tags: the latest_state column's value domain ---------------------------------

    @Test
    fun `every state tag roundtrips through its persisted name and active states exclude terminals`() {
        TaskRunStateTag.entries.forEach { tag ->
            assertEquals(tag, TaskRunStateTag.fromPersistedOrNull(tag.name))
        }
        assertNull(TaskRunStateTag.fromPersistedOrNull("NOT_A_STATE"))
        // The recovery scan must pick up every non-terminal, non-interrupted run and nothing else.
        assertTrue(TaskRunStateTag.ACTIVE.none { it.isTerminal })
        assertTrue(TaskRunStateTag.INTERRUPTED !in TaskRunStateTag.ACTIVE)
        assertEquals(
            setOf(
                TaskRunStateTag.CREATED,
                TaskRunStateTag.QUEUED,
                TaskRunStateTag.STARTING,
                TaskRunStateTag.RUNNING,
                TaskRunStateTag.WAITING_APPROVAL,
                TaskRunStateTag.RESUMING,
            ),
            TaskRunStateTag.ACTIVE,
        )
    }

    @Test
    fun `work-item status column values are exactly the domain enum names`() {
        WorkItemStatus.entries.forEach { status ->
            val entity = WorkItemEntity.fromWorkItem(
                item = WorkItem(
                    id = Uuid.random(),
                    conversationId = Uuid.random(),
                    subject = "s",
                    status = status,
                    ownerHandleId = if (status == WorkItemStatus.InProgress) "h" else null,
                ),
                createdAt = 0L,
                updatedAt = 0L,
            )
            assertEquals(status.name, entity.status)
        }
    }

    // --- helpers ----------------------------------------------------------------------------

    private fun taskRunEntity(
        eventSummaries: String = "[]",
        pendingApproval: String? = null,
    ): TaskRunEntity = TaskRunEntity(
        id = Uuid.random().toString(),
        conversationId = Uuid.random().toString(),
        parentToolCallId = "call_1",
        agentTypeId = "agent_1",
        prompt = "do the thing",
        latestState = TaskRunStateTag.CREATED.name,
        eventSummaries = eventSummaries,
        pendingApproval = pendingApproval,
        createdAt = 0L,
        updatedAt = 0L,
    )
}
