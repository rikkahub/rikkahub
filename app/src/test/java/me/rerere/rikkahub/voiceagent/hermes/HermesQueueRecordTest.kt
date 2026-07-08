package me.rerere.rikkahub.voiceagent.hermes

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.VoiceAgentToolNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class HermesQueueRecordTest {

    private fun record(
        status: HermesQueueStatus = HermesQueueStatus.Running,
        announcement: HermesAnnouncementState = HermesAnnouncementState.NotAnnounced,
        jobId: String? = "job-1",
        answer: String? = if (status == HermesQueueStatus.Complete) "the answer" else null,
        error: String? = if (status.isTerminal && status != HermesQueueStatus.Complete) "boom" else null,
    ) = HermesQueueRecord(
        callId = "call-1",
        jobId = jobId,
        prompt = "the prompt",
        status = status,
        answer = answer,
        error = error,
        announcement = announcement,
        createdAt = "2026-07-08T00:00:00Z",
        updatedAt = "2026-07-08T00:00:01Z",
    )

    private fun HermesQueueRecord.toToolPart(): UIMessagePart.Tool = UIMessagePart.Tool(
        toolCallId = callId,
        toolName = VoiceAgentToolNames.ASK_HERMES,
        input = """{"prompt":"$prompt"}""",
        output = listOfNotNull(
            (answer ?: error)?.let { UIMessagePart.Text(it, metadata = null) }
        ),
        metadata = toMetadata(nowIso = updatedAt ?: "2026-07-08T00:00:01Z"),
    )

    // --- round-trip property ---

    @Test
    fun `round-trips every status and announcement state`() {
        for (status in HermesQueueStatus.entries) {
            for (announcement in listOf(
                HermesAnnouncementState.NotAnnounced,
                HermesAnnouncementState.StillWorkingAnnounced,
                HermesAnnouncementState.MessageWritten,
                HermesAnnouncementState.Announced,
            )) {
                val original = record(status = status, announcement = announcement)
                val parsed = HermesQueueRecord.fromToolPart(original.toToolPart())
                assertEquals("status=$status announcement=$announcement", original, parsed)
            }
        }
    }

    @Test
    fun `round-trips a null jobId`() {
        val original = record(status = HermesQueueStatus.Pending, jobId = null)
        assertEquals(original, HermesQueueRecord.fromToolPart(original.toToolPart()))
    }

    // --- clean-break fallback (the announced-default rule, single home) ---

    @Test
    fun `missing announcement key on a terminal record falls back to Announced`() {
        val part = record(status = HermesQueueStatus.Complete).toToolPart()
        val stripped = part.copy(metadata = buildJsonObject {
            part.metadata!!.forEach { (k, v) -> if (k != HERMES_TOOL_ANNOUNCEMENT_KEY) put(k, v) }
        })
        assertEquals(
            HermesAnnouncementState.Announced,
            HermesQueueRecord.fromToolPart(stripped)!!.announcement,
        )
    }

    @Test
    fun `missing announcement key on an active record falls back to NotAnnounced`() {
        val part = record(status = HermesQueueStatus.Running).toToolPart()
        val stripped = part.copy(metadata = buildJsonObject {
            part.metadata!!.forEach { (k, v) -> if (k != HERMES_TOOL_ANNOUNCEMENT_KEY) put(k, v) }
        })
        assertEquals(
            HermesAnnouncementState.NotAnnounced,
            HermesQueueRecord.fromToolPart(stripped)!!.announcement,
        )
    }

    @Test
    fun `legacy boolean keys are honored until the writer migrates (bridge)`() {
        // TEMPORARY Task-2->3 bridge test: DELETED in Task 3 with the bridge.
        val part = record(status = HermesQueueStatus.Running).toToolPart()
        val legacy = part.copy(metadata = buildJsonObject {
            part.metadata!!.forEach { (k, v) -> if (k != HERMES_TOOL_ANNOUNCEMENT_KEY) put(k, v) }
            put("voice_tool_still_working_announced", true)
        })
        assertEquals(
            HermesAnnouncementState.StillWorkingAnnounced,
            HermesQueueRecord.fromToolPart(legacy)!!.announcement,
        )
    }

    // --- advance transition table (at-most-once falls out of nulls) ---

    @Test
    fun `advance StillWorkingFired marks an active record`() {
        val advanced = record(status = HermesQueueStatus.Running).advance(HermesAnnouncementEvent.StillWorkingFired)
        assertEquals(HermesAnnouncementState.StillWorkingAnnounced, advanced!!.announcement)
    }

    @Test
    fun `advance StillWorkingFired is illegal on terminal records and repeats`() {
        assertNull(record(status = HermesQueueStatus.Complete).advance(HermesAnnouncementEvent.StillWorkingFired))
        assertNull(
            record(announcement = HermesAnnouncementState.StillWorkingAnnounced)
                .advance(HermesAnnouncementEvent.StillWorkingFired)
        )
    }

    @Test
    fun `advance VisibleMessageWritten requires a terminal record`() {
        assertNull(record(status = HermesQueueStatus.Running).advance(HermesAnnouncementEvent.VisibleMessageWritten))
        val advanced = record(status = HermesQueueStatus.Failed)
            .advance(HermesAnnouncementEvent.VisibleMessageWritten)
        assertEquals(HermesAnnouncementState.MessageWritten, advanced!!.announcement)
    }

    @Test
    fun `advance VisibleMessageWritten allowed from StillWorkingAnnounced, illegal from MessageWritten and Announced`() {
        val fromStillWorking = record(
            status = HermesQueueStatus.Failed,
            announcement = HermesAnnouncementState.StillWorkingAnnounced,
        ).advance(HermesAnnouncementEvent.VisibleMessageWritten)
        assertEquals(HermesAnnouncementState.MessageWritten, fromStillWorking!!.announcement)
        assertNull(
            record(status = HermesQueueStatus.Failed, announcement = HermesAnnouncementState.MessageWritten)
                .advance(HermesAnnouncementEvent.VisibleMessageWritten)
        )
        assertNull(
            record(status = HermesQueueStatus.Failed, announcement = HermesAnnouncementState.Announced)
                .advance(HermesAnnouncementEvent.VisibleMessageWritten)
        )
    }

    @Test
    fun `advance ResultAnnounced reaches Announced from every non-Announced state and never repeats`() {
        for (from in listOf(
            HermesAnnouncementState.NotAnnounced,
            HermesAnnouncementState.StillWorkingAnnounced,
            HermesAnnouncementState.MessageWritten,
        )) {
            val advanced = record(status = HermesQueueStatus.Complete, announcement = from)
                .advance(HermesAnnouncementEvent.ResultAnnounced)
            assertEquals("from=$from", HermesAnnouncementState.Announced, advanced!!.announcement)
        }
        assertNull(
            record(status = HermesQueueStatus.Complete, announcement = HermesAnnouncementState.Announced)
                .advance(HermesAnnouncementEvent.ResultAnnounced)
        )
    }

    @Test
    fun `advance changes only the announcement`() {
        val original = record(status = HermesQueueStatus.Running)
        val advanced = original.advance(HermesAnnouncementEvent.StillWorkingFired)
        // Timestamps are re-stamped by toMetadata at write time; advance itself is a pure copy.
        assertEquals(original.copy(announcement = HermesAnnouncementState.StillWorkingAnnounced), advanced)
    }

    // --- identity predicates ---

    @Test
    fun `matchesIdentity requires exact callId and jobId including null-to-null`() {
        assertEquals(true, record().matchesIdentity(callId = "call-1", jobId = "job-1"))
        assertEquals(false, record().matchesIdentity(callId = "call-1", jobId = null))
        assertEquals(false, record().matchesIdentity(callId = "other", jobId = "job-1"))
        assertEquals(true, record(jobId = null, status = HermesQueueStatus.Pending).matchesIdentity("call-1", null))
    }

    @Test
    fun `mayAdoptJobId allows active and canceled-to-canceled records without a jobId`() {
        val activeNoJob = record(status = HermesQueueStatus.Pending, jobId = null)
        assertEquals(true, activeNoJob.mayAdoptJobId(HermesQueueStatus.Queued))
        val canceledNoJob = record(status = HermesQueueStatus.Canceled, jobId = null)
        assertEquals(true, canceledNoJob.mayAdoptJobId(HermesQueueStatus.Canceled))
        assertEquals(false, canceledNoJob.mayAdoptJobId(HermesQueueStatus.Complete))
        assertEquals(false, record(status = HermesQueueStatus.Pending).mayAdoptJobId(HermesQueueStatus.Queued))
    }

    // --- snapshot behavior preserved (ported from the old suite, rows 37-49 of the
    // phase3-behavior-inventory-pr-a.md doc) ---

    @Test
    fun `snapshot partitions active, unannounced terminal, and announced terminal`() {
        // Ported from old row 45 (`queue snapshot separates active jobs and unannounced
        // terminal results`).
        val active = record(status = HermesQueueStatus.Running, jobId = "job-active")
            .copy(callId = "active", prompt = "active request")
            .toToolPart()
        val unannouncedTerminal = record(
            status = HermesQueueStatus.Complete,
            jobId = "job-new",
            announcement = HermesAnnouncementState.NotAnnounced,
            answer = "new answer",
        ).copy(callId = "new-result", prompt = "new terminal request").toToolPart()
        val announcedTerminal = record(
            status = HermesQueueStatus.Complete,
            jobId = "job-old",
            announcement = HermesAnnouncementState.Announced,
            answer = "old answer",
        ).copy(callId = "old-result", prompt = "old terminal request").toToolPart()

        val snapshot = HermesQueueSnapshot.from(conversationOf(active, unannouncedTerminal, announcedTerminal))

        assertEquals(listOf("active"), snapshot.active.map { it.callId })
        assertEquals(listOf("job-active"), snapshot.active.map { it.jobId })
        assertEquals(listOf("new-result"), snapshot.unannouncedTerminal.map { it.callId })
        assertEquals(listOf("old-result"), snapshot.announcedTerminal.map { it.callId })
        assertEquals(listOf("active request"), snapshot.active.map { it.prompt })
        assertEquals(listOf("new answer"), snapshot.unannouncedTerminal.map { it.answer })
        assertEquals(listOf("old answer"), snapshot.announcedTerminal.map { it.answer })
    }

    @Test
    fun `status question summary exposes active prompts and terminal counts without terminal content`() {
        // Ported from old row 46, assertions kept verbatim.
        val active = record(status = HermesQueueStatus.Running, jobId = "job-active")
            .copy(callId = "active", prompt = "active request").toToolPart()
        val complete = record(
            status = HermesQueueStatus.Complete,
            jobId = "job-complete",
            answer = "complete private answer",
        ).copy(callId = "complete", prompt = "complete private request").toToolPart()
        val failed = record(
            status = HermesQueueStatus.Failed,
            jobId = "job-failed",
            error = "failed private reason",
        ).copy(callId = "failed", prompt = "failed private request").toToolPart()
        val expired = record(
            status = HermesQueueStatus.Expired,
            jobId = "job-expired",
            error = "expired private reason",
        ).copy(callId = "expired", prompt = "expired private request").toToolPart()
        val canceled = record(
            status = HermesQueueStatus.Canceled,
            jobId = "job-canceled",
            error = "canceled private reason",
        ).copy(callId = "canceled", prompt = "canceled private request").toToolPart()

        val summary = HermesQueueSnapshot.from(conversationOf(active, complete, failed, expired, canceled))
            .toStatusQuestionPromptSummary()

        assertTrue(summary.contains("Durable Hermes queue status:"))
        assertTrue(summary.contains("- Still running: active request"))
        assertTrue(summary.contains("- Unannounced terminal results: completed=1, failed=1, expired=1, canceled=1"))
        assertTrue(summary.contains("answer only from this durable queue status"))
        assertFalse(summary.contains("complete private request"))
        assertFalse(summary.contains("complete private answer"))
        assertFalse(summary.contains("failed private request"))
        assertFalse(summary.contains("failed private reason"))
        assertFalse(summary.contains("expired private request"))
        assertFalse(summary.contains("expired private reason"))
        assertFalse(summary.contains("canceled private request"))
        assertFalse(summary.contains("canceled private reason"))
    }

    @Test
    fun `status question summary is empty for announced terminal records only`() {
        // Ported from old row 47.
        val announced = record(
            status = HermesQueueStatus.Complete,
            jobId = "job-announced",
            announcement = HermesAnnouncementState.Announced,
            answer = "announced private answer",
        ).copy(callId = "announced", prompt = "announced private request").toToolPart()

        val summary = HermesQueueSnapshot.from(conversationOf(announced)).toStatusQuestionPromptSummary()

        assertEquals("", summary)
    }

    @Test
    fun `queue snapshot uses latest record for duplicate durable identity`() {
        // Ported from old row 48 (latestByHermesDurableIdentity dedup).
        val old = record(status = HermesQueueStatus.Complete, jobId = "job-duplicate", answer = "old answer")
            .copy(callId = "call-duplicate", prompt = "old prompt").toToolPart()
        val latest = record(status = HermesQueueStatus.Failed, jobId = "job-duplicate", error = "latest failure")
            .copy(callId = "call-duplicate", prompt = "latest prompt").toToolPart()

        val conversation = conversationOf(old, latest)
        val records = conversation.hermesQueueRecords()
        val snapshot = HermesQueueSnapshot.from(conversation)

        assertEquals(2, records.size)
        assertEquals(listOf(HermesQueueStatus.Failed), snapshot.unannouncedTerminal.map { it.status })
        assertTrue(snapshot.announcedTerminal.isEmpty())
        assertEquals(listOf("latest failure"), snapshot.unannouncedTerminal.map { it.error })
    }

    @Test
    fun `new terminal Hermes records default to unannounced queue results`() {
        // Ported from old row 49.
        val complete = record(status = HermesQueueStatus.Complete, jobId = "job-complete")
            .copy(callId = "complete", prompt = "complete request").toToolPart()
        val failed = record(status = HermesQueueStatus.Failed, jobId = "job-failed")
            .copy(callId = "failed", prompt = "failed request").toToolPart()
        val expired = record(status = HermesQueueStatus.Expired, jobId = "job-expired")
            .copy(callId = "expired", prompt = "expired request").toToolPart()
        val canceled = record(status = HermesQueueStatus.Canceled, jobId = "job-canceled")
            .copy(callId = "canceled", prompt = "canceled request").toToolPart()

        val snapshot = HermesQueueSnapshot.from(conversationOf(complete, failed, expired, canceled))

        assertTrue(snapshot.active.isEmpty())
        assertTrue(snapshot.announcedTerminal.isEmpty())
        assertEquals(
            listOf("complete", "failed", "expired", "canceled"),
            snapshot.unannouncedTerminal.map { it.callId },
        )
        assertTrue(snapshot.unannouncedTerminal.all { !it.resultAnnounced })
    }

    @Test
    fun `snapshot records expired and canceled terminal errors`() {
        // Ported from old row 43.
        val expired = record(status = HermesQueueStatus.Expired, jobId = "job-expired", error = "expired reason")
            .copy(callId = "call-expired", prompt = "expired request")
            .toToolPart()
        val canceled = record(status = HermesQueueStatus.Canceled, jobId = "job-canceled", error = "canceled reason")
            .copy(callId = "call-canceled", prompt = "canceled request")
            .toToolPart()

        val snapshot = HermesQueueSnapshot.from(conversationOf(expired, canceled))

        assertEquals(listOf("call-expired", "call-canceled"), snapshot.unannouncedTerminal.map { it.callId })
        assertEquals(listOf("expired request", "canceled request"), snapshot.unannouncedTerminal.map { it.prompt })
        assertEquals(listOf("expired reason", "canceled reason"), snapshot.unannouncedTerminal.map { it.error })
    }

    @Test
    fun `reads queued running and complete Hermes records from conversation`() {
        // Ported from old row 44.
        val queued = record(status = HermesQueueStatus.Queued, jobId = "job-queued")
            .copy(callId = "call-queued", prompt = "first request")
            .toToolPart()
        val running = record(status = HermesQueueStatus.Running, jobId = "job-running")
            .copy(callId = "call-running", prompt = "second request")
            .toToolPart()
        val complete = record(status = HermesQueueStatus.Complete, jobId = "job-complete", answer = "done")
            .copy(callId = "call-complete", prompt = "third request")
            .toToolPart()

        val records = conversationOf(queued, running, complete).hermesQueueRecords()

        assertEquals(listOf("call-queued", "call-running", "call-complete"), records.map { it.callId })
        assertEquals(listOf("job-queued", "job-running", "job-complete"), records.map { it.jobId })
        assertEquals(HermesQueueStatus.Queued, records[0].status)
        assertEquals(HermesQueueStatus.Running, records[1].status)
        assertEquals(HermesQueueStatus.Complete, records[2].status)
        assertEquals("first request", records[0].prompt)
        assertEquals("done", records[2].answer)
        assertEquals(HermesAnnouncementState.NotAnnounced, records[2].announcement)
    }

    @Test
    fun `legacy pending record without announced metadata reads as an active queue record`() {
        // Ported from old row 37: a legacy pending Hermes tool record with no announcement
        // metadata is read as a single active queue record, absent from both terminal buckets.
        val part = record(status = HermesQueueStatus.Pending, jobId = "job-pending")
            .copy(prompt = "legacy pending request")
            .toToolPart()
        val stripped = part.copy(metadata = buildJsonObject {
            part.metadata!!.forEach { (k, v) -> if (k != HERMES_TOOL_ANNOUNCEMENT_KEY) put(k, v) }
        })
        val conversation = conversationOf(stripped)

        val records = conversation.hermesQueueRecords()
        val snapshot = HermesQueueSnapshot.from(conversation)

        assertEquals(listOf("call-1"), records.map { it.callId })
        assertEquals(HermesQueueStatus.Pending, records.single().status)
        assertEquals("legacy pending request", records.single().prompt)
        assertFalse(records.single().status.isTerminal)
        assertEquals(listOf("call-1"), snapshot.active.map { it.callId })
        assertTrue(snapshot.unannouncedTerminal.isEmpty())
        assertTrue(snapshot.announcedTerminal.isEmpty())
    }

    @Test
    fun `malformed Hermes metadata is skipped or defaulted without crashing queue parsing`() {
        // Ported from old row 39.
        val badStatusShape = hermesToolPart(
            callId = "bad-status",
            prompt = "bad status request",
            metadata = buildJsonObject {
                put(HERMES_TOOL_STATUS_KEY, buildJsonObject { put("bad", "shape") })
            },
        )
        val badOptionals = hermesToolPart(
            callId = "bad-optionals",
            prompt = "bad optional request",
            metadata = buildJsonObject {
                put(HERMES_TOOL_STATUS_KEY, "running")
                put(HERMES_TOOL_JOB_ID_KEY, buildJsonObject { put("bad", "shape") })
                put(HERMES_TOOL_ANNOUNCEMENT_KEY, buildJsonObject { put("bad", "shape") })
                put(HERMES_TOOL_CREATED_AT_KEY, buildJsonObject { put("bad", "shape") })
                put(HERMES_TOOL_UPDATED_AT_KEY, buildJsonObject { put("bad", "shape") })
            },
        )

        val records = conversationOf(badStatusShape, badOptionals).hermesQueueRecords()

        assertEquals(listOf("bad-optionals"), records.map { it.callId })
        assertEquals(HermesQueueStatus.Running, records.single().status)
        assertEquals(null, records.single().jobId)
        assertEquals(HermesAnnouncementState.NotAnnounced, records.single().announcement)
        assertEquals(null, records.single().createdAt)
        assertEquals(null, records.single().updatedAt)
    }

    @Test
    fun `malformed status with unannounced output recovers as failed terminal record`() {
        // Ported from old row 40.
        val part = hermesToolPart(
            callId = "bad-status-output",
            prompt = "private malformed status request",
            outputText = "private malformed status answer",
            metadata = buildJsonObject {
                put(HERMES_TOOL_STATUS_KEY, "not-a-status")
                put(HERMES_TOOL_RESULT_ANNOUNCED_KEY, false)
            },
        )

        val snapshot = HermesQueueSnapshot.from(conversationOf(part))
        val record = snapshot.unannouncedTerminal.single()

        assertEquals("bad-status-output", record.callId)
        assertEquals(HermesQueueStatus.Failed, record.status)
        assertEquals("private malformed status request", record.prompt)
        assertEquals("private malformed status answer", record.error)
        assertEquals(HermesAnnouncementState.NotAnnounced, record.announcement)
    }

    @Test
    fun `queue parser skips non-Hermes tools and defaults malformed input prompts`() {
        // Ported from old row 42.
        val nonHermes = hermesToolPart(
            callId = "not-hermes",
            prompt = "unrelated request",
            toolName = "other_tool",
            metadata = buildJsonObject { put(HERMES_TOOL_STATUS_KEY, "running") },
        )
        val badInput = UIMessagePart.Tool(
            toolCallId = "bad-input",
            toolName = VoiceAgentToolNames.ASK_HERMES,
            input = "not json",
            output = emptyList(),
            metadata = buildJsonObject { put(HERMES_TOOL_STATUS_KEY, "running") },
        )

        val records = conversationOf(nonHermes, badInput).hermesQueueRecords()

        assertEquals(listOf("bad-input"), records.map { it.callId })
        assertEquals("", records.single().prompt)
        assertEquals(HermesQueueStatus.Running, records.single().status)
    }

    private fun conversationOf(vararg tools: UIMessagePart.Tool): Conversation {
        return Conversation.ofId(Uuid.random())
            .updateCurrentMessages(
                listOf(
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = tools.toList(),
                    )
                )
            )
    }

    /** Hand-builds a tool part with caller-controlled (possibly malformed) metadata. */
    private fun hermesToolPart(
        callId: String,
        prompt: String,
        toolName: String = VoiceAgentToolNames.ASK_HERMES,
        outputText: String? = null,
        metadata: JsonObject,
    ): UIMessagePart.Tool = UIMessagePart.Tool(
        toolCallId = callId,
        toolName = toolName,
        input = """{"prompt":"$prompt"}""",
        output = outputText?.let { listOf(UIMessagePart.Text(it, metadata = null)) }.orEmpty(),
        metadata = metadata,
    )
}
