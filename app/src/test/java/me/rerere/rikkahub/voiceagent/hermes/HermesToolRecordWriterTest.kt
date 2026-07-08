package me.rerere.rikkahub.voiceagent.hermes

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.voiceagent.persistence.VoiceTranscriptPersister
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class HermesToolRecordWriterTest {

    private val writer = HermesToolRecordWriter(nowIso = { "2026-07-08T00:00:05Z" })

    private fun emptyConversation(): Conversation =
        Conversation.ofId(Uuid.random())

    private fun Conversation.singleToolPart(): UIMessagePart.Tool =
        currentMessages.flatMap { it.parts }.filterIsInstance<UIMessagePart.Tool>().single()

    private fun Conversation.toolPart(callId: String): UIMessagePart.Tool =
        currentMessages.flatMap { it.parts }.filterIsInstance<UIMessagePart.Tool>().last { it.toolCallId == callId }

    private fun Conversation.toolParts(): List<UIMessagePart.Tool> =
        currentMessages.flatMap { it.parts }.filterIsInstance<UIMessagePart.Tool>()

    // --- pinned behaviors (verbatim from the brief) ---

    @Test
    fun `first upsert appends a pending record`() {
        val conversation = writer.upsertHermesTool(
            conversation = emptyConversation(),
            callId = "call-1",
            prompt = "the prompt",
            status = VoiceToolRecordStatus.Pending,
        )
        val record = conversation.hermesQueueRecords().single()
        assertEquals("call-1", record.callId)
        assertEquals(HermesQueueStatus.Pending, record.status)
        assertEquals(HermesAnnouncementState.NotAnnounced, record.announcement)
        assertNull(record.jobId)
    }

    @Test
    fun `active upsert with a jobId adopts the pending record in place`() {
        var conversation = writer.upsertHermesTool(
            emptyConversation(), callId = "call-1", prompt = "p", status = VoiceToolRecordStatus.Pending,
        )
        conversation = writer.upsertHermesTool(
            conversation, callId = "call-1", prompt = "p",
            status = VoiceToolRecordStatus.Queued, jobId = "job-1",
        )
        val record = conversation.hermesQueueRecords().single()
        assertEquals("job-1", record.jobId)
        assertEquals(HermesQueueStatus.Queued, record.status)
    }

    @Test
    fun `terminal record is frozen against any further upsert of the same identity`() {
        var conversation = writer.upsertHermesTool(
            emptyConversation(), callId = "call-1", prompt = "p",
            status = VoiceToolRecordStatus.Complete("answer"), jobId = "job-1",
        )
        val frozen = writer.upsertHermesTool(
            conversation, callId = "call-1", prompt = "p",
            status = VoiceToolRecordStatus.Running, jobId = "job-1",
        )
        assertEquals(conversation, frozen)
        val reterminaled = writer.upsertHermesTool(
            conversation, callId = "call-1", prompt = "p",
            status = VoiceToolRecordStatus.Failed("late failure"), jobId = "job-1",
        )
        assertEquals(conversation, reterminaled)
    }

    @Test
    fun `canceled record without a jobId adopts the jobId from a canceled update`() {
        var conversation = writer.upsertHermesTool(
            emptyConversation(), callId = "call-1", prompt = "p",
            status = VoiceToolRecordStatus.Canceled("Hermes job canceled."), announceOnWrite = true,
        )
        conversation = writer.upsertHermesTool(
            conversation, callId = "call-1", prompt = "p",
            status = VoiceToolRecordStatus.Canceled("Hermes job canceled."), jobId = "job-1",
            announceOnWrite = true,
        )
        val record = conversation.hermesQueueRecords().single()
        assertEquals("job-1", record.jobId)
        assertEquals(HermesQueueStatus.Canceled, record.status)
        assertEquals(HermesAnnouncementState.Announced, record.announcement)
    }

    @Test
    fun `announceOnWrite false carries the existing announcement`() {
        var conversation = writer.upsertHermesTool(
            emptyConversation(), callId = "call-1", prompt = "p",
            status = VoiceToolRecordStatus.Running, jobId = "job-1",
        )
        conversation = writer.markStillWorkingAnnounced(conversation, callId = "call-1", jobId = "job-1")
        conversation = writer.upsertHermesTool(
            conversation, callId = "call-1", prompt = "p",
            status = VoiceToolRecordStatus.Complete("answer"), jobId = "job-1",
        )
        assertEquals(
            HermesAnnouncementState.StillWorkingAnnounced,
            conversation.hermesQueueRecords().single().announcement,
        )
    }

    @Test
    fun `marks are at-most-once through advance`() {
        var conversation = writer.upsertHermesTool(
            emptyConversation(), callId = "call-1", prompt = "p",
            status = VoiceToolRecordStatus.Complete("answer"), jobId = "job-1",
        )
        conversation = writer.markResultAnnounced(conversation, callId = "call-1", jobId = "job-1")
        val marked = conversation.hermesQueueRecords().single()
        assertEquals(HermesAnnouncementState.Announced, marked.announcement)
        val again = writer.markResultAnnounced(conversation, callId = "call-1", jobId = "job-1")
        assertEquals(conversation, again)
    }

    @Test
    fun `markResultAnnounced ignores active records`() {
        var conversation = writer.upsertHermesTool(
            emptyConversation(), callId = "call-1", prompt = "p",
            status = VoiceToolRecordStatus.Running, jobId = "job-1",
        )
        val unchanged = writer.markResultAnnounced(conversation, callId = "call-1", jobId = "job-1")
        assertEquals(conversation, unchanged)
    }

    @Test
    fun `markMessageWritten requires a terminal record`() {
        var conversation = writer.upsertHermesTool(
            emptyConversation(), callId = "call-1", prompt = "p",
            status = VoiceToolRecordStatus.Running, jobId = "job-1",
        )
        assertEquals(conversation, writer.markMessageWritten(conversation, "call-1", "job-1"))
        conversation = writer.upsertHermesTool(
            conversation, callId = "call-1", prompt = "p",
            status = VoiceToolRecordStatus.Failed("boom"), jobId = "job-1",
        )
        val written = writer.markMessageWritten(conversation, "call-1", "job-1")
        assertEquals(HermesAnnouncementState.MessageWritten, written.hermesQueueRecords().single().announcement)
    }

    @Test
    fun `tool part carries no legacy keys, no voice_status, and bare-text output`() {
        val conversation = writer.upsertHermesTool(
            emptyConversation(), callId = "call-1", prompt = "p",
            status = VoiceToolRecordStatus.Complete("answer"), jobId = "job-1", sessionId = "session-9",
        )
        val part = conversation.singleToolPart()
        val metadata = part.metadata!!
        assertFalse("voice_tool_source" in metadata)
        assertFalse("voice_tool_result_announced" in metadata)
        assertFalse("voice_tool_still_working_announced" in metadata)
        assertFalse("voice_tool_message_written" in metadata)
        assertFalse("voice_status" in metadata)
        assertEquals("session-9", metadata["voice_session_id"]!!.jsonPrimitive.content)
        assertEquals("call-1", metadata["voice_call_id"]!!.jsonPrimitive.content)
        val outputText = part.output.filterIsInstance<UIMessagePart.Text>().single()
        assertEquals("answer", outputText.text)
        assertNull(outputText.metadata)
    }

    @Test
    fun `updateHermesRecord preserves non-hermes metadata keys on transform`() {
        var conversation = writer.upsertHermesTool(
            emptyConversation(), callId = "call-1", prompt = "p",
            status = VoiceToolRecordStatus.Failed("boom"), jobId = "job-1", sessionId = "session-9",
        )
        conversation = writer.markResultAnnounced(conversation, callId = "call-1", jobId = "job-1")
        val metadata = conversation.singleToolPart().metadata!!
        assertEquals("session-9", metadata["voice_session_id"]!!.jsonPrimitive.content)
        assertEquals("announced", metadata[HERMES_TOOL_ANNOUNCEMENT_KEY]!!.jsonPrimitive.content)
    }

    // --- ported from VoiceConversationPersisterTest per the Task 1 inventory (rows 1-36) ---
    // Rows that contradicted a semantic pin are noted inline and marked obsolete in the
    // inventory rather than ported; the new pinned behavior they were replaced by is the
    // test immediately following the note.

    @Test
    fun `upsert appends a tool part with call id tool name status prompt and answer`() {
        // Ported from row 2 (the transcript-composition portion of that old test is
        // unaffected by this task -- appendUserTurn/appendAssistantTurn are unchanged).
        val conversation = writer.upsertHermesTool(
            conversation = emptyConversation(),
            callId = "call-1",
            prompt = "Ask Hermes this",
            status = VoiceToolRecordStatus.Complete("Hermes answer"),
        )
        val tool = conversation.singleToolPart()
        assertEquals("call-1", tool.toolCallId)
        assertEquals("ask_hermes", tool.toolName)
        assertEquals("complete", tool.metadata!![HERMES_TOOL_STATUS_KEY]!!.jsonPrimitive.content)
        assertEquals("Ask Hermes this", tool.input.promptJson())
        assertEquals("Hermes answer", tool.output.text())
    }

    @Test
    fun `pending to complete upsert replaces matching call id in latest assistant message`() {
        // Ported from row 3.
        val conversation = emptyConversation()
            .let {
                writer.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "Original prompt",
                    status = VoiceToolRecordStatus.Pending,
                )
            }
            .let {
                writer.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "Updated prompt",
                    status = VoiceToolRecordStatus.Complete("Final answer"),
                )
            }

        val assistantMessage = conversation.currentMessages.single()
        val tools = assistantMessage.parts.filterIsInstance<UIMessagePart.Tool>()

        assertEquals(1, tools.size)
        assertEquals("call-1", tools.single().toolCallId)
        assertEquals("complete", tools.single().metadata!![HERMES_TOOL_STATUS_KEY]!!.jsonPrimitive.content)
        assertEquals("Updated prompt", tools.single().input.promptJson())
        assertEquals("Final answer", tools.single().output.text())
    }

    @Test
    fun `complete upsert updates pending tool before newer assistant turn without appending duplicate`() {
        // Ported from row 10.
        val persister = VoiceTranscriptPersister()
        val conversation = emptyConversation()
            .let {
                writer.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "Original prompt",
                    status = VoiceToolRecordStatus.Pending,
                )
            }
            .let { persister.appendAssistantTurn(it, "Assistant reply", interrupted = false) }
            .let {
                writer.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "Updated prompt",
                    status = VoiceToolRecordStatus.Complete("Final answer"),
                )
            }

        assertEquals(2, conversation.currentMessages.size)
        assertEquals(
            "Assistant reply",
            conversation.currentMessages[1].parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text },
        )

        val tools = conversation.toolParts()
        assertEquals(1, tools.size)
        assertEquals("call-1", tools.single().toolCallId)
        assertEquals("Updated prompt", tools.single().input.promptJson())
        assertEquals("Final answer", tools.single().output.text())
    }

    @Test
    fun `pending status records machine readable pending status on tool metadata`() {
        // Ported from row 11.
        val conversation = writer.upsertHermesTool(
            conversation = emptyConversation(),
            callId = "call-1",
            prompt = "Prompt",
            status = VoiceToolRecordStatus.Pending,
        )

        val tool = conversation.singleToolPart()
        assertEquals("pending", tool.metadata!![HERMES_TOOL_STATUS_KEY]!!.jsonPrimitive.content)
        assertTrue(tool.output.isEmpty())
    }

    @Test
    fun `pending upsert while a terminal null-jobId record exists is a no-op`() {
        // Row 12 (`pending upsert with reused call id appends after completed Hermes
        // record`) is OBSOLETE: pin 2's terminal freeze means a fresh upsert under the
        // same identity (callId + jobId == null) as a terminal record is now a no-op
        // rather than an append. This is the new pinned behavior.
        val completed = writer.upsertHermesTool(
            conversation = emptyConversation(),
            callId = "call-1",
            prompt = "Original prompt",
            status = VoiceToolRecordStatus.Complete("Original answer"),
        )
        val afterPending = writer.upsertHermesTool(
            conversation = completed,
            callId = "call-1",
            prompt = "Reused prompt",
            status = VoiceToolRecordStatus.Pending,
        )
        assertEquals(completed, afterPending)
    }

    @Test
    fun `terminal upsert with reused null-jobId identity is a no-op, not a replace`() {
        // Row 13 (`terminal upsert with reused call id replaces latest Hermes terminal
        // record`) is OBSOLETE for the same reason as row 12: the identity (call-1,
        // jobId = null) is already terminal after the first upsert, so pin 2 freezes any
        // further upsert of that identity. Covered by
        // `terminal record is frozen against any further upsert of the same identity`.
        val failed = writer.upsertHermesTool(
            conversation = emptyConversation(),
            callId = "call-1",
            prompt = "Original prompt",
            status = VoiceToolRecordStatus.Failed("Tool call canceled by session end"),
        )
        val frozen = writer.upsertHermesTool(
            conversation = failed,
            callId = "call-1",
            prompt = "Original prompt",
            status = VoiceToolRecordStatus.Complete("Late answer"),
        )
        assertEquals(failed, frozen)
    }

    @Test
    fun `upsert ignores non Hermes tool with same call id`() {
        // Ported from row 14.
        val normalTool = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "normal_tool",
            input = """{"value":"original"}""",
            output = listOf(UIMessagePart.Text("normal result")),
        )
        val conversation = emptyConversation()
            .updateCurrentMessages(
                listOf(
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(normalTool),
                    )
                )
            )

        val updated = writer.upsertHermesTool(
            conversation = conversation,
            callId = "call-1",
            prompt = "Hermes prompt",
            status = VoiceToolRecordStatus.Complete("Hermes answer"),
        )

        val tools = updated.toolParts()
        assertEquals(2, tools.size)
        assertEquals(normalTool, tools[0])
        assertEquals("ask_hermes", tools[1].toolName)
        assertEquals("Hermes prompt", tools[1].input.promptJson())
        assertEquals("Hermes answer", tools[1].output.text())
    }

    @Test
    fun `new call id after user turn appends tool message without changing old assistant`() {
        // Ported from row 15.
        val persister = VoiceTranscriptPersister()
        val conversation = emptyConversation()
            .let { persister.appendAssistantTurn(it, "Old assistant reply", interrupted = false) }
            .let { persister.appendUserTurn(it, "New user request") }
            .let {
                writer.upsertHermesTool(
                    conversation = it,
                    callId = "call-2",
                    prompt = "Fresh prompt",
                    status = VoiceToolRecordStatus.Complete("Fresh answer"),
                )
            }

        assertEquals(
            listOf(MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT),
            conversation.currentMessages.map { it.role },
        )

        val oldAssistantTools = conversation.currentMessages[0].parts.filterIsInstance<UIMessagePart.Tool>()
        assertTrue(oldAssistantTools.isEmpty())

        val tool = conversation.currentMessages[2].parts.single() as UIMessagePart.Tool
        assertEquals("call-2", tool.toolCallId)
        assertEquals("Fresh prompt", tool.input.promptJson())
        assertEquals("Fresh answer", tool.output.text())
    }

    @Test
    fun `failed status records machine readable failure status as tool output`() {
        // Ported from row 16. The old output-metadata assertion is dropped: pin 4
        // (mirror deletion) sets output Text parts' metadata to null.
        val conversation = writer.upsertHermesTool(
            conversation = emptyConversation(),
            callId = "call-1",
            prompt = "Prompt",
            status = VoiceToolRecordStatus.Failed("Hermes failed"),
        )

        val tool = conversation.singleToolPart()
        val output = tool.output.single() as UIMessagePart.Text
        assertEquals("failed", tool.metadata!![HERMES_TOOL_STATUS_KEY]!!.jsonPrimitive.content)
        assertEquals("Hermes failed", output.text)
        assertNull(output.metadata)
    }

    @Test
    fun `job id metadata is preserved across statuses for the same call id`() {
        // Ported from row 17.
        val pending = writer.upsertHermesTool(
            conversation = emptyConversation(),
            callId = "call-1",
            prompt = "Prompt",
            status = VoiceToolRecordStatus.Pending,
            jobId = "job-1",
        )
        assertEquals("job-1", pending.toolPart("call-1").metadata!![HERMES_TOOL_JOB_ID_KEY]!!.jsonPrimitive.content)

        val complete = writer.upsertHermesTool(
            conversation = pending,
            callId = "call-1",
            prompt = "Prompt",
            status = VoiceToolRecordStatus.Complete("Answer"),
            jobId = "job-1",
        )
        assertEquals("job-1", complete.toolPart("call-1").metadata!![HERMES_TOOL_JOB_ID_KEY]!!.jsonPrimitive.content)

        val failed = writer.upsertHermesTool(
            conversation = emptyConversation(),
            callId = "call-2",
            prompt = "Prompt",
            status = VoiceToolRecordStatus.Failed("Hermes job expired"),
            jobId = "job-2",
        )
        assertEquals("job-2", failed.toolPart("call-2").metadata!![HERMES_TOOL_JOB_ID_KEY]!!.jsonPrimitive.content)
    }

    @Test
    fun `upsertHermesTool stamps generic voice artifact metadata when sessionId is present`() {
        // Ported from the Hermes-tool portion of row 19 (`voice artifacts include session
        // source identifiers status and timestamps`). The transcript portions of that row
        // are unaffected by this task and remain in VoiceTranscriptPersisterTest (renamed
        // from VoiceConversationPersisterTest in Task 4). The old assertion of a
        // "voice_status" key and of output-part metadata is dropped:
        // pin 4 removes voice_status from tool metadata and nulls output-part metadata.
        val conversation = writer.upsertHermesTool(
            conversation = emptyConversation(),
            callId = "call-1",
            prompt = "Ask Hermes",
            status = VoiceToolRecordStatus.Complete("Hermes answer"),
            sessionId = "session-1",
        )

        val tool = conversation.singleToolPart()
        val metadata = tool.metadata!!
        assertEquals("voice_agent", metadata["voice_source"]!!.jsonPrimitive.content)
        assertEquals("session-1", metadata["voice_session_id"]!!.jsonPrimitive.content)
        assertEquals("call-1", metadata["voice_event_id"]!!.jsonPrimitive.content)
        assertEquals("call-1", metadata["voice_call_id"]!!.jsonPrimitive.content)
        assertTrue(metadata["voice_created_at"]!!.jsonPrimitive.content.isNotBlank())
        assertTrue(metadata["voice_updated_at"]!!.jsonPrimitive.content.isNotBlank())
        assertNull((tool.output.single() as UIMessagePart.Text).metadata)
    }

    @Test
    fun `queued and running statuses persist job metadata with created timestamp preserved`() {
        // Ported from row 20. Tightened per review: the old test only asserted the
        // updated timestamp is non-blank at both stages, not that it advances (this
        // writer instance uses a fixed clock, so it could not advance meaningfully
        // anyway). The legacy `voice_tool_result_announced` boolean assertion is replaced
        // by the single `voice_tool_announcement` field.
        val queuedConversation = writer.upsertHermesTool(
            conversation = emptyConversation(),
            callId = "call-1",
            prompt = "queued prompt",
            status = VoiceToolRecordStatus.Queued,
            sessionId = "session-1",
            jobId = "job-1",
        )
        val queuedMetadata = queuedConversation.singleToolPart().metadata!!
        assertEquals("queued", queuedMetadata[HERMES_TOOL_STATUS_KEY]!!.jsonPrimitive.content)
        assertEquals("job-1", queuedMetadata[HERMES_TOOL_JOB_ID_KEY]!!.jsonPrimitive.content)
        assertEquals("not_announced", queuedMetadata[HERMES_TOOL_ANNOUNCEMENT_KEY]!!.jsonPrimitive.content)
        val queuedCreatedAt = queuedMetadata[HERMES_TOOL_CREATED_AT_KEY]!!.jsonPrimitive.content
        assertTrue(queuedCreatedAt.isNotBlank())
        assertTrue(queuedMetadata[HERMES_TOOL_UPDATED_AT_KEY]!!.jsonPrimitive.content.isNotBlank())

        val runningConversation = writer.upsertHermesTool(
            conversation = queuedConversation,
            callId = "call-1",
            prompt = "queued prompt",
            status = VoiceToolRecordStatus.Running,
            sessionId = "session-1",
            jobId = "job-1",
        )
        val runningMetadata = runningConversation.singleToolPart().metadata!!
        assertEquals("running", runningMetadata[HERMES_TOOL_STATUS_KEY]!!.jsonPrimitive.content)
        assertEquals("job-1", runningMetadata[HERMES_TOOL_JOB_ID_KEY]!!.jsonPrimitive.content)
        assertEquals("not_announced", runningMetadata[HERMES_TOOL_ANNOUNCEMENT_KEY]!!.jsonPrimitive.content)
        assertEquals(queuedCreatedAt, runningMetadata[HERMES_TOOL_CREATED_AT_KEY]!!.jsonPrimitive.content)
        assertTrue(runningMetadata[HERMES_TOOL_UPDATED_AT_KEY]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `job-keyed terminal upsert replaces legacy active record without job id`() {
        // Ported from row 30 (distinct from row 21 / the brief's provided
        // `active upsert with a jobId adopts the pending record in place` test: this one
        // exercises the terminal-status adoption branch of mayAdoptJobId).
        val active = writer.upsertHermesTool(
            conversation = emptyConversation(),
            callId = "call-1",
            prompt = "legacy active prompt",
            status = VoiceToolRecordStatus.Pending,
        )
        val activeCreatedAt = active.singleToolPart().metadata!![HERMES_TOOL_CREATED_AT_KEY]!!.jsonPrimitive.content

        val completed = writer.upsertHermesTool(
            conversation = active,
            callId = "call-1",
            prompt = "completed prompt",
            status = VoiceToolRecordStatus.Complete("completed answer"),
            jobId = "job-1",
        )

        val tools = completed.toolParts()
        assertEquals(1, tools.size)
        val tool = tools.single()
        assertEquals("complete", tool.metadata!![HERMES_TOOL_STATUS_KEY]!!.jsonPrimitive.content)
        assertEquals("job-1", tool.metadata!![HERMES_TOOL_JOB_ID_KEY]!!.jsonPrimitive.content)
        assertEquals(activeCreatedAt, tool.metadata!![HERMES_TOOL_CREATED_AT_KEY]!!.jsonPrimitive.content)
        assertEquals("completed answer", tool.output.text())
    }

    @Test
    fun `persisted status names round-trip through the queue status parser`() {
        // Ported from row 22. The old per-output-part status metadata assertion is
        // dropped: pin 4 nulls output-part metadata.
        val statuses = listOf(
            VoiceToolRecordStatus.Pending to HermesQueueStatus.Pending,
            VoiceToolRecordStatus.Queued to HermesQueueStatus.Queued,
            VoiceToolRecordStatus.Running to HermesQueueStatus.Running,
            VoiceToolRecordStatus.Complete("answer") to HermesQueueStatus.Complete,
            VoiceToolRecordStatus.Failed("failed") to HermesQueueStatus.Failed,
            VoiceToolRecordStatus.Expired("expired") to HermesQueueStatus.Expired,
            VoiceToolRecordStatus.Canceled("canceled") to HermesQueueStatus.Canceled,
        )

        statuses.forEachIndexed { index, (status, expectedQueueStatus) ->
            val conversation = writer.upsertHermesTool(
                conversation = emptyConversation(),
                callId = "call-$index",
                prompt = "prompt",
                status = status,
                jobId = "job-$index",
            )
            val tool = conversation.singleToolPart()
            assertEquals(
                expectedQueueStatus,
                HermesQueueStatus.fromWireName(tool.metadata!![HERMES_TOOL_STATUS_KEY]!!.jsonPrimitive.content),
            )
        }
    }

    @Test
    fun `expired and canceled statuses persist terminal message text`() {
        // Ported from row 23. The old output-metadata equality assertions are dropped:
        // pin 4 nulls output-part metadata.
        val conversation = emptyConversation()
            .let {
                writer.upsertHermesTool(
                    conversation = it,
                    callId = "call-expired",
                    prompt = "expired prompt",
                    status = VoiceToolRecordStatus.Expired("expired message"),
                    jobId = "job-expired",
                )
            }
            .let {
                writer.upsertHermesTool(
                    conversation = it,
                    callId = "call-canceled",
                    prompt = "canceled prompt",
                    status = VoiceToolRecordStatus.Canceled("canceled message"),
                    jobId = "job-canceled",
                )
            }

        val tools = conversation.toolParts()
        assertEquals(
            listOf("expired", "canceled"),
            tools.map { it.metadata!![HERMES_TOOL_STATUS_KEY]!!.jsonPrimitive.content },
        )
        assertEquals(listOf("expired message", "canceled message"), tools.map { it.output.text() })
    }

    @Test
    fun `terminal replay of the same identity is frozen and keeps its announced flag`() {
        // Ported from row 24. The freeze (pin 2) makes the second upsert a no-op instead
        // of a "replay that carries the announced flag forward" -- the observable outcome
        // (the record stays announced) is identical, per pin 1's own reasoning. The old
        // output-metadata assertion is dropped: pin 4 nulls output-part metadata.
        val conversation = emptyConversation()
            .let {
                writer.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "prompt",
                    status = VoiceToolRecordStatus.Complete("answer"),
                    jobId = "job-1",
                )
            }
            .let { writer.markResultAnnounced(it, callId = "call-1", jobId = "job-1") }
            .let {
                writer.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "prompt",
                    status = VoiceToolRecordStatus.Complete("answer"),
                    jobId = "job-1",
                )
            }

        val tool = conversation.singleToolPart()
        assertEquals("announced", tool.metadata!![HERMES_TOOL_ANNOUNCEMENT_KEY]!!.jsonPrimitive.content)
    }

    @Test
    fun `terminal upsert with new job id appends instead of replacing historical terminal record`() {
        // Ported from row 25.
        val conversation = emptyConversation()
            .let {
                writer.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "old prompt",
                    status = VoiceToolRecordStatus.Complete("old answer"),
                    jobId = "job-old",
                )
            }
            .let {
                writer.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "new prompt",
                    status = VoiceToolRecordStatus.Complete("new answer"),
                    jobId = "job-new",
                )
            }

        val tools = conversation.toolParts()
        assertEquals(2, tools.size)
        assertEquals(
            listOf("job-old", "job-new"),
            tools.map { it.metadata!![HERMES_TOOL_JOB_ID_KEY]!!.jsonPrimitive.content },
        )
        assertEquals(listOf("old answer", "new answer"), tools.map { it.output.text() })
    }

    @Test
    fun `terminal upsert without job id appends instead of replacing historical terminal record with job id`() {
        // Ported from row 26.
        val conversation = emptyConversation()
            .let {
                writer.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "old prompt",
                    status = VoiceToolRecordStatus.Complete("old answer"),
                    jobId = "job-old",
                )
            }
            .let {
                writer.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "submit failed prompt",
                    status = VoiceToolRecordStatus.Failed("submit failed before job id"),
                    jobId = null,
                )
            }

        val tools = conversation.toolParts()
        assertEquals(2, tools.size)
        assertEquals("job-old", tools[0].metadata!![HERMES_TOOL_JOB_ID_KEY]!!.jsonPrimitive.content)
        assertFalse(tools[1].metadata!!.containsKey(HERMES_TOOL_JOB_ID_KEY))
        assertEquals(listOf("old answer", "submit failed before job id"), tools.map { it.output.text() })
    }

    @Test
    fun `terminal upsert without job id does not replace active record with job id`() {
        // Ported from row 27.
        val conversation = emptyConversation()
            .let {
                writer.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "active prompt",
                    status = VoiceToolRecordStatus.Running,
                    jobId = "job-active",
                )
            }
            .let {
                writer.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "submit failed prompt",
                    status = VoiceToolRecordStatus.Failed("submit failed before job id"),
                    jobId = null,
                )
            }

        val tools = conversation.toolParts()
        assertEquals(2, tools.size)
        assertEquals("running", tools[0].metadata!![HERMES_TOOL_STATUS_KEY]!!.jsonPrimitive.content)
        assertEquals("job-active", tools[0].metadata!![HERMES_TOOL_JOB_ID_KEY]!!.jsonPrimitive.content)
        assertEquals("failed", tools[1].metadata!![HERMES_TOOL_STATUS_KEY]!!.jsonPrimitive.content)
        assertFalse(tools[1].metadata!!.containsKey(HERMES_TOOL_JOB_ID_KEY))
    }

    @Test
    fun `active upsert without job id does not replace active record with job id`() {
        // Ported from row 28.
        val conversation = emptyConversation()
            .let {
                writer.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "job prompt",
                    status = VoiceToolRecordStatus.Running,
                    jobId = "job-active",
                )
            }
            .let {
                writer.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "legacy active prompt",
                    status = VoiceToolRecordStatus.Queued,
                    jobId = null,
                )
            }

        val tools = conversation.toolParts()
        assertEquals(2, tools.size)
        assertEquals("running", tools[0].metadata!![HERMES_TOOL_STATUS_KEY]!!.jsonPrimitive.content)
        assertEquals("job-active", tools[0].metadata!![HERMES_TOOL_JOB_ID_KEY]!!.jsonPrimitive.content)
        assertEquals("queued", tools[1].metadata!![HERMES_TOOL_STATUS_KEY]!!.jsonPrimitive.content)
        assertFalse(tools[1].metadata!!.containsKey(HERMES_TOOL_JOB_ID_KEY))
    }

    @Test
    fun `terminal upsert with job id replaces matching active record not historical terminal record`() {
        // Ported from row 29.
        val conversation = emptyConversation()
            .let {
                writer.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "old prompt",
                    status = VoiceToolRecordStatus.Complete("old answer"),
                    jobId = "job-old",
                )
            }
            .let {
                writer.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "new prompt",
                    status = VoiceToolRecordStatus.Running,
                    jobId = "job-new",
                )
            }
            .let {
                writer.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "new prompt",
                    status = VoiceToolRecordStatus.Complete("new answer"),
                    jobId = "job-new",
                )
            }

        val tools = conversation.toolParts()
        assertEquals(2, tools.size)
        assertEquals(
            listOf("job-old", "job-new"),
            tools.map { it.metadata!![HERMES_TOOL_JOB_ID_KEY]!!.jsonPrimitive.content },
        )
        assertEquals(listOf("old answer", "new answer"), tools.map { it.output.text() })
    }

    @Test
    fun `markResultAnnounced by explicit identity marks only the matching call, leaving others untouched`() {
        // Rows 31 and 32 (`mark Hermes result announced updates only matching terminal
        // tool`, `... updates only latest terminal record for reused call id`) are
        // OBSOLETE: both relied on the persister's "latest terminal record for this call
        // id, ignoring jobId" convenience (calling markHermesToolResultAnnounced with no
        // jobId while the located records actually carried a jobId). Pin 3 states this
        // branch is unreachable from HermesQueueStore (it always passes the real jobId or
        // explicit null) and the writer's markResultAnnounced requires an exact identity
        // match. This test covers the same underlying intent -- marking one call doesn't
        // affect a different call or an active record -- using explicit, reachable
        // identities.
        val conversation = emptyConversation()
            .let {
                writer.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "prompt",
                    status = VoiceToolRecordStatus.Complete("answer"),
                    jobId = "job-1",
                )
            }
            .let {
                writer.upsertHermesTool(
                    conversation = it,
                    callId = "call-2",
                    prompt = "other prompt",
                    status = VoiceToolRecordStatus.Complete("other answer"),
                    jobId = "job-2",
                )
            }
            .let {
                writer.upsertHermesTool(
                    conversation = it,
                    callId = "call-3",
                    prompt = "active prompt",
                    status = VoiceToolRecordStatus.Running,
                    jobId = "job-3",
                )
            }
            .let { writer.markResultAnnounced(it, callId = "call-1", jobId = "job-1") }
            .let { writer.markResultAnnounced(it, callId = "call-3", jobId = "job-3") }

        val recordsByCallId = conversation.hermesQueueRecords().associateBy { it.callId }
        assertEquals(HermesAnnouncementState.Announced, recordsByCallId.getValue("call-1").announcement)
        assertEquals(HermesAnnouncementState.NotAnnounced, recordsByCallId.getValue("call-2").announcement)
        assertEquals(HermesAnnouncementState.NotAnnounced, recordsByCallId.getValue("call-3").announcement)
    }

    @Test
    fun `markResultAnnounced can target reused call id by job id`() {
        // Ported from row 33.
        val conversation = emptyConversation()
            .let {
                writer.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "old prompt",
                    status = VoiceToolRecordStatus.Complete("old answer"),
                    jobId = "job-old",
                )
            }
            .let {
                writer.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "new prompt",
                    status = VoiceToolRecordStatus.Complete("new answer"),
                    jobId = "job-new",
                )
            }
            .let { writer.markResultAnnounced(it, callId = "call-1", jobId = "job-old") }
            .let { writer.markResultAnnounced(it, callId = "call-1", jobId = "job-new") }

        val tools = conversation.toolParts()
        assertEquals(
            listOf("job-old", "job-new"),
            tools.map { it.metadata!![HERMES_TOOL_JOB_ID_KEY]!!.jsonPrimitive.content },
        )
        assertEquals(
            listOf("announced", "announced"),
            tools.map { it.metadata!![HERMES_TOOL_ANNOUNCEMENT_KEY]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun `markResultAnnounced marks duplicate terminal records for same job id`() {
        // Ported from row 34: pin 3's mark scan policy transforms every matching record.
        val conversation = emptyConversation()
            .let {
                writer.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "old prompt",
                    status = VoiceToolRecordStatus.Complete("old answer"),
                    jobId = "job-1",
                )
            }
            .withDuplicateToolPart(callId = "call-1", prompt = "latest prompt", answer = "latest answer")
            .let { writer.markResultAnnounced(it, callId = "call-1", jobId = "job-1") }

        val tools = conversation.toolParts()
        assertEquals(2, tools.size)
        assertEquals(
            listOf("announced", "announced"),
            tools.map { it.metadata!![HERMES_TOOL_ANNOUNCEMENT_KEY]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun `markResultAnnounced for duplicate job id does not mark other job ids`() {
        // Ported from row 35.
        val conversation = emptyConversation()
            .let {
                writer.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "first prompt",
                    status = VoiceToolRecordStatus.Complete("first answer"),
                    jobId = "job-1",
                )
            }
            .withDuplicateToolPart(callId = "call-1", prompt = "duplicate first prompt", answer = "duplicate first answer")
            .let {
                writer.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "second prompt",
                    status = VoiceToolRecordStatus.Complete("second answer"),
                    jobId = "job-2",
                )
            }
            .let { writer.markResultAnnounced(it, callId = "call-1", jobId = "job-1") }

        val tools = conversation.toolParts()
        assertEquals(
            listOf("job-1", "job-1", "job-2"),
            tools.map { it.metadata!![HERMES_TOOL_JOB_ID_KEY]!!.jsonPrimitive.content },
        )
        assertEquals(
            listOf("announced", "announced", "not_announced"),
            tools.map { it.metadata!![HERMES_TOOL_ANNOUNCEMENT_KEY]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun `markResultAnnounced with a null jobId can target a reused call id without job id`() {
        // Ported from row 36: this is exactly the reachable branch (matchMissingJobId ==
        // true is how HermesQueueStore always calls markResultAnnounced when jobId ==
        // null).
        val conversation = emptyConversation()
            .let {
                writer.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "submit failed prompt",
                    status = VoiceToolRecordStatus.Failed("submit failed before job id"),
                    jobId = null,
                )
            }
            .let {
                writer.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "new prompt",
                    status = VoiceToolRecordStatus.Complete("new answer"),
                    jobId = "job-new",
                )
            }
            .let { writer.markResultAnnounced(it, callId = "call-1", jobId = null) }

        val tools = conversation.toolParts()
        assertEquals(2, tools.size)
        assertFalse(tools[0].metadata!!.containsKey(HERMES_TOOL_JOB_ID_KEY))
        assertEquals("announced", tools[0].metadata!![HERMES_TOOL_ANNOUNCEMENT_KEY]!!.jsonPrimitive.content)
        assertEquals("job-new", tools[1].metadata!![HERMES_TOOL_JOB_ID_KEY]!!.jsonPrimitive.content)
        assertEquals("not_announced", tools[1].metadata!![HERMES_TOOL_ANNOUNCEMENT_KEY]!!.jsonPrimitive.content)
    }

    @Test
    fun `markStillWorkingAnnounced marks every active duplicate for the same identity`() {
        // Mirror of the markResultAnnounced duplicate scan: several active records sharing
        // one identity all advance to StillWorkingAnnounced.
        val conversation = emptyConversation()
            .let {
                writer.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "old prompt",
                    status = VoiceToolRecordStatus.Running,
                    jobId = "job-1",
                )
            }
            .withDuplicateToolPart(callId = "call-1", prompt = "latest prompt", answer = "latest working")
            .let { writer.markStillWorkingAnnounced(it, callId = "call-1", jobId = "job-1") }

        val tools = conversation.toolParts()
        assertEquals(2, tools.size)
        assertEquals(
            listOf("still_working_announced", "still_working_announced"),
            tools.map { it.metadata!![HERMES_TOOL_ANNOUNCEMENT_KEY]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun `markMessageWritten marks every terminal duplicate for the same identity`() {
        // Mirror of the markResultAnnounced duplicate scan: several terminal records
        // sharing one identity all advance to MessageWritten.
        val conversation = emptyConversation()
            .let {
                writer.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "old prompt",
                    status = VoiceToolRecordStatus.Failed("old failure"),
                    jobId = "job-1",
                )
            }
            .withDuplicateToolPart(callId = "call-1", prompt = "latest prompt", answer = "latest failure")
            .let { writer.markMessageWritten(it, "call-1", "job-1") }

        val tools = conversation.toolParts()
        assertEquals(2, tools.size)
        assertEquals(
            listOf("message_written", "message_written"),
            tools.map { it.metadata!![HERMES_TOOL_ANNOUNCEMENT_KEY]!!.jsonPrimitive.content },
        )
    }

    private fun Conversation.withDuplicateToolPart(
        callId: String,
        prompt: String,
        answer: String,
    ): Conversation {
        val sourceTool = toolParts().last { it.toolCallId == callId }
        val duplicateTool = sourceTool.copy(
            input = buildJsonObject {
                put("prompt", JsonPrimitive(prompt))
            }.toString(),
            output = sourceTool.output.map { part ->
                if (part is UIMessagePart.Text) {
                    part.copy(text = answer)
                } else {
                    part
                }
            },
        )
        return updateCurrentMessages(
            currentMessages + UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(duplicateTool),
            )
        )
    }

    private fun String.promptJson(): String = JsonInstant
        .parseToJsonElement(this)
        .jsonObject["prompt"]!!
        .jsonPrimitive
        .content

    private fun List<UIMessagePart>.text(): String = filterIsInstance<UIMessagePart.Text>()
        .joinToString("") { it.text }
}
