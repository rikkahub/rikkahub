package me.rerere.rikkahub.voiceagent.hermes

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.VoiceAgentToolNames
import me.rerere.rikkahub.voiceagent.persistence.VoiceConversationPersister
import me.rerere.rikkahub.voiceagent.persistence.VoiceToolRecordStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class HermesQueueRecordTest {
    private val persister = VoiceConversationPersister()

    @Test
    fun `reads legacy pending Hermes records without announced metadata as active queue records`() {
        val conversation = conversationOf(
            legacyHermesTool(
                callId = "call-pending",
                prompt = "legacy pending request",
                status = "pending",
                jobId = "job-pending",
            )
        )

        val records = conversation.hermesQueueRecords()
        val snapshot = HermesQueueSnapshot.from(conversation)

        assertEquals(listOf("call-pending"), records.map { it.callId })
        assertEquals(HermesQueueStatus.Pending, records.single().status)
        assertEquals("legacy pending request", records.single().prompt)
        assertFalse(records.single().status.isTerminal)
        assertEquals(listOf("call-pending"), snapshot.active.map { it.callId })
        assertTrue(snapshot.unannouncedTerminal.isEmpty())
        assertTrue(snapshot.announcedTerminal.isEmpty())
    }

    @Test
    fun `legacy terminal Hermes records without announced metadata are treated as announced`() {
        val conversation = conversationOf(
            legacyHermesTool(
                callId = "call-complete",
                prompt = "legacy complete request",
                status = "complete",
                outputText = "legacy complete answer",
            ),
            legacyHermesTool(
                callId = "call-failed",
                prompt = "legacy failed request",
                status = "failed",
                outputText = "legacy failed reason",
            )
        )

        val snapshot = HermesQueueSnapshot.from(conversation)

        assertTrue(snapshot.active.isEmpty())
        assertTrue(snapshot.unannouncedTerminal.isEmpty())
        assertEquals(listOf("call-complete", "call-failed"), snapshot.announcedTerminal.map { it.callId })
    }

    @Test
    fun `malformed Hermes metadata is skipped or defaulted without crashing queue parsing`() {
        val conversation = conversationOf(
            legacyHermesTool(
                callId = "bad-status",
                prompt = "bad status request",
                metadata = buildJsonObject {
                    put(HERMES_TOOL_SOURCE_KEY, VoiceAgentToolNames.ASK_HERMES)
                    put(HERMES_TOOL_STATUS_KEY, buildJsonObject { put("bad", "shape") })
                },
            ),
            legacyHermesTool(
                callId = "bad-optionals",
                prompt = "bad optional request",
                status = "running",
                metadata = buildJsonObject {
                    put(HERMES_TOOL_SOURCE_KEY, VoiceAgentToolNames.ASK_HERMES)
                    put(HERMES_TOOL_STATUS_KEY, "running")
                    put(HERMES_TOOL_JOB_ID_KEY, buildJsonObject { put("bad", "shape") })
                    put(HERMES_TOOL_RESULT_ANNOUNCED_KEY, buildJsonObject { put("bad", "shape") })
                    put(HERMES_TOOL_CREATED_AT_KEY, buildJsonObject { put("bad", "shape") })
                    put(HERMES_TOOL_UPDATED_AT_KEY, buildJsonObject { put("bad", "shape") })
                },
            )
        )

        val records = conversation.hermesQueueRecords()

        assertEquals(listOf("bad-optionals"), records.map { it.callId })
        assertEquals(HermesQueueStatus.Running, records.single().status)
        assertEquals(null, records.single().jobId)
        assertFalse(records.single().resultAnnounced)
        assertEquals(null, records.single().createdAt)
        assertEquals(null, records.single().updatedAt)
    }

    @Test
    fun `terminal records with malformed announcement metadata remain unannounced`() {
        val conversation = conversationOf(
            legacyHermesTool(
                callId = "bad-announced",
                prompt = "bad announced request",
                status = "complete",
                outputText = "bad announced answer",
                metadata = buildJsonObject {
                    put(HERMES_TOOL_SOURCE_KEY, VoiceAgentToolNames.ASK_HERMES)
                    put(HERMES_TOOL_STATUS_KEY, "complete")
                    put(HERMES_TOOL_RESULT_ANNOUNCED_KEY, "not-a-boolean")
                },
            )
        )

        val snapshot = HermesQueueSnapshot.from(conversation)

        assertEquals(listOf("bad-announced"), snapshot.unannouncedTerminal.map { it.callId })
        assertTrue(snapshot.announcedTerminal.isEmpty())
    }

    @Test
    fun `queue parser skips non-Hermes tools and defaults malformed input prompts`() {
        val conversation = conversationOf(
            legacyHermesTool(
                callId = "not-hermes",
                prompt = "unrelated request",
                status = "running",
                toolName = "other_tool",
                metadata = buildJsonObject {
                    put(HERMES_TOOL_SOURCE_KEY, VoiceAgentToolNames.ASK_HERMES)
                    put(HERMES_TOOL_STATUS_KEY, "running")
                },
            ),
            legacyHermesTool(
                callId = "bad-input",
                prompt = "ignored",
                status = "running",
                input = "not json",
            )
        )

        val records = conversation.hermesQueueRecords()

        assertEquals(listOf("bad-input"), records.map { it.callId })
        assertEquals("", records.single().prompt)
        assertEquals(HermesQueueStatus.Running, records.single().status)
    }

    @Test
    fun `snapshot records expired and canceled terminal errors`() {
        val conversation = Conversation.ofId(Uuid.random())
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "call-expired",
                    prompt = "expired request",
                    status = VoiceToolRecordStatus.Expired("expired reason"),
                    jobId = "job-expired",
                    resultAnnounced = false,
                )
            }
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "call-canceled",
                    prompt = "canceled request",
                    status = VoiceToolRecordStatus.Canceled("canceled reason"),
                    jobId = "job-canceled",
                    resultAnnounced = false,
                )
            }

        val snapshot = HermesQueueSnapshot.from(conversation)

        assertEquals(listOf("call-expired", "call-canceled"), snapshot.unannouncedTerminal.map { it.callId })
        assertEquals(listOf("expired request", "canceled request"), snapshot.unannouncedTerminal.map { it.prompt })
        assertEquals(listOf("expired reason", "canceled reason"), snapshot.unannouncedTerminal.map { it.error })
    }

    @Test
    fun `reads queued running and complete Hermes records from conversation`() {
        val conversation = Conversation.ofId(Uuid.random())
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "call-queued",
                    prompt = "first request",
                    status = VoiceToolRecordStatus.Queued,
                    jobId = "job-queued",
                )
            }
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "call-running",
                    prompt = "second request",
                    status = VoiceToolRecordStatus.Running,
                    jobId = "job-running",
                )
            }
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "call-complete",
                    prompt = "third request",
                    status = VoiceToolRecordStatus.Complete("done"),
                    jobId = "job-complete",
                    resultAnnounced = false,
                )
            }

        val records = conversation.hermesQueueRecords()

        assertEquals(listOf("call-queued", "call-running", "call-complete"), records.map { it.callId })
        assertEquals(listOf("job-queued", "job-running", "job-complete"), records.map { it.jobId })
        assertEquals(HermesQueueStatus.Queued, records[0].status)
        assertEquals(HermesQueueStatus.Running, records[1].status)
        assertEquals(HermesQueueStatus.Complete, records[2].status)
        assertEquals("first request", records[0].prompt)
        assertEquals("done", records[2].answer)
        assertFalse(records[2].resultAnnounced)
    }

    @Test
    fun `queue snapshot separates active jobs and unannounced terminal results`() {
        val conversation = Conversation.ofId(Uuid.random())
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "active",
                    prompt = "active request",
                    status = VoiceToolRecordStatus.Running,
                    jobId = "job-active",
                )
            }
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "new-result",
                    prompt = "new terminal request",
                    status = VoiceToolRecordStatus.Complete("new answer"),
                    jobId = "job-new",
                    resultAnnounced = false,
                )
            }
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "old-result",
                    prompt = "old terminal request",
                    status = VoiceToolRecordStatus.Complete("old answer"),
                    jobId = "job-old",
                    resultAnnounced = true,
                )
            }

        val snapshot = HermesQueueSnapshot.from(conversation)

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
        val conversation = Conversation.ofId(Uuid.random())
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "active",
                    prompt = "active request",
                    status = VoiceToolRecordStatus.Running,
                    jobId = "job-active",
                )
            }
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "complete",
                    prompt = "complete private request",
                    status = VoiceToolRecordStatus.Complete("complete private answer"),
                    jobId = "job-complete",
                    resultAnnounced = false,
                )
            }
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "failed",
                    prompt = "failed private request",
                    status = VoiceToolRecordStatus.Failed("failed private reason"),
                    jobId = "job-failed",
                    resultAnnounced = false,
                )
            }
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "expired",
                    prompt = "expired private request",
                    status = VoiceToolRecordStatus.Expired("expired private reason"),
                    jobId = "job-expired",
                    resultAnnounced = false,
                )
            }
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "canceled",
                    prompt = "canceled private request",
                    status = VoiceToolRecordStatus.Canceled("canceled private reason"),
                    jobId = "job-canceled",
                    resultAnnounced = false,
                )
            }

        val summary = HermesQueueSnapshot.from(conversation).toStatusQuestionPromptSummary()

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
        val conversation = persister.upsertHermesTool(
            conversation = Conversation.ofId(Uuid.random()),
            callId = "announced",
            prompt = "announced private request",
            status = VoiceToolRecordStatus.Complete("announced private answer"),
            jobId = "job-announced",
            resultAnnounced = true,
        )

        val summary = HermesQueueSnapshot.from(conversation).toStatusQuestionPromptSummary()

        assertEquals("", summary)
    }

    @Test
    fun `queue snapshot uses latest record for duplicate durable identity`() {
        val conversation = conversationOf(
            legacyHermesTool(
                callId = "call-duplicate",
                prompt = "old prompt",
                status = "complete",
                jobId = "job-duplicate",
                outputText = "old answer",
                metadata = hermesMetadata(
                    status = "complete",
                    jobId = "job-duplicate",
                    resultAnnounced = false,
                ),
            ),
            legacyHermesTool(
                callId = "call-duplicate",
                prompt = "latest prompt",
                status = "failed",
                jobId = "job-duplicate",
                outputText = "latest failure",
                metadata = hermesMetadata(
                    status = "failed",
                    jobId = "job-duplicate",
                    resultAnnounced = false,
                ),
            ),
        )

        val records = conversation.hermesQueueRecords()
        val snapshot = HermesQueueSnapshot.from(conversation)

        assertEquals(2, records.size)
        assertEquals(listOf(HermesQueueStatus.Failed), snapshot.unannouncedTerminal.map { it.status })
        assertTrue(snapshot.announcedTerminal.isEmpty())
        assertEquals(listOf("latest failure"), snapshot.unannouncedTerminal.map { it.error })
    }

    @Test
    fun `new terminal Hermes records default to unannounced queue results`() {
        val conversation = Conversation.ofId(Uuid.random())
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "complete",
                    prompt = "complete request",
                    status = VoiceToolRecordStatus.Complete("complete answer"),
                    jobId = "job-complete",
                )
            }
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "failed",
                    prompt = "failed request",
                    status = VoiceToolRecordStatus.Failed("failed reason"),
                    jobId = "job-failed",
                )
            }
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "expired",
                    prompt = "expired request",
                    status = VoiceToolRecordStatus.Expired("expired reason"),
                    jobId = "job-expired",
                )
            }
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "canceled",
                    prompt = "canceled request",
                    status = VoiceToolRecordStatus.Canceled("canceled reason"),
                    jobId = "job-canceled",
                )
            }

        val snapshot = HermesQueueSnapshot.from(conversation)

        assertTrue(snapshot.active.isEmpty())
        assertTrue(snapshot.announcedTerminal.isEmpty())
        assertEquals(
            listOf("complete", "failed", "expired", "canceled"),
            snapshot.unannouncedTerminal.map { it.callId },
        )
        assertTrue(snapshot.unannouncedTerminal.all { !it.resultAnnounced })
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

    private fun legacyHermesTool(
        callId: String,
        prompt: String,
        status: String? = null,
        toolName: String = VoiceAgentToolNames.ASK_HERMES,
        input: String = """{"prompt":"$prompt"}""",
        jobId: String? = null,
        outputText: String? = null,
        metadata: kotlinx.serialization.json.JsonObject? = null,
    ): UIMessagePart.Tool {
        return UIMessagePart.Tool(
            toolCallId = callId,
            toolName = toolName,
            input = input,
            output = outputText?.let { listOf(UIMessagePart.Text(it)) }.orEmpty(),
            metadata = metadata ?: buildJsonObject {
                put(HERMES_TOOL_SOURCE_KEY, VoiceAgentToolNames.ASK_HERMES)
                status?.let { put(HERMES_TOOL_STATUS_KEY, it) }
                jobId?.let { put(HERMES_TOOL_JOB_ID_KEY, it) }
            },
        )
    }

    private fun hermesMetadata(
        status: String,
        jobId: String?,
        resultAnnounced: Boolean,
    ) = buildJsonObject {
        put(HERMES_TOOL_SOURCE_KEY, VoiceAgentToolNames.ASK_HERMES)
        put(HERMES_TOOL_STATUS_KEY, status)
        jobId?.let { put(HERMES_TOOL_JOB_ID_KEY, it) }
        put(HERMES_TOOL_RESULT_ANNOUNCED_KEY, resultAnnounced)
    }
}
