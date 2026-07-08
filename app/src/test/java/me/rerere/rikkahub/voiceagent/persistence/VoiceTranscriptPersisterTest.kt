package me.rerere.rikkahub.voiceagent.persistence

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.voiceagent.hermes.HermesToolRecordWriter
import me.rerere.rikkahub.voiceagent.hermes.VoiceToolRecordStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class VoiceTranscriptPersisterTest {
    @Test
    fun `remove legacy voice session started notes removes only metadata tagged notes`() {
        val persister = VoiceTranscriptPersister()
        val legacyNote = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text(
                    text = "Voice Agent session started\n\nTrace ID: VA-old\nSession ID: VA-old",
                    metadata = buildJsonObject {
                        put("voice_source", JsonPrimitive("voice_agent"))
                        put("voice_status", JsonPrimitive("session-started"))
                        put("voice_trace_id", JsonPrimitive("VA-old"))
                    }
                )
            )
        )
        val normalMessage = UIMessage.assistant("Voice Agent session started as normal text")
        val conversation = emptyConversation().copy(
            messageNodes = listOf(
                MessageNode.of(legacyNote),
                MessageNode.of(normalMessage),
            )
        )

        val updated = persister.removeLegacyVoiceSessionStartedNotes(conversation)

        assertEquals(listOf(normalMessage.id), updated.currentMessages.map { it.id })
    }

    @Test
    fun `voice transcript upsert keeps streaming fragments in one visible turn`() {
        val persister = VoiceTranscriptPersister()
        val conversation = emptyConversation()
            .let { persister.upsertUserTranscriptTurn(it, "hel", turnId = "user-1") }
            .let { persister.upsertUserTranscriptTurn(it, "hello", turnId = "user-1") }
            .let { persister.upsertAssistantTranscriptTurn(it, "h", interrupted = false, turnId = "assistant-1") }
            .let { persister.upsertAssistantTranscriptTurn(it, "hi", interrupted = true, turnId = "assistant-1") }

        assertEquals(
            listOf(MessageRole.USER, MessageRole.ASSISTANT),
            conversation.currentMessages.map { it.role },
        )
        assertEquals("hello", conversation.currentMessages[0].parts.text())
        assertEquals("hi", conversation.currentMessages[1].parts.text())
        val assistantText = conversation.currentMessages[1].parts.single() as UIMessagePart.Text
        assertEquals("interrupted", assistantText.metadata!!["voice_status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `upsert transcript keeps same turn id from different voice sessions`() {
        val persister = VoiceTranscriptPersister()
        val conversation = Conversation.ofId(Uuid.random())

        val afterFirst = persister.upsertUserTranscriptTurn(
            conversation = conversation,
            text = "first session text",
            turnId = "user-1",
            sessionId = "session-a",
            status = VoiceTranscriptStatus.Complete,
        )
        val afterSecond = persister.upsertUserTranscriptTurn(
            conversation = afterFirst,
            text = "second session text",
            turnId = "user-1",
            sessionId = "session-b",
            status = VoiceTranscriptStatus.Complete,
        )

        val texts = afterSecond.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Text>()
            .map { it.text }

        assertEquals(listOf("first session text", "second session text"), texts)
    }

    @Test
    fun `upsert transcript with session id does not replace legacy transcript without session id`() {
        val persister = VoiceTranscriptPersister()
        val conversation = Conversation.ofId(Uuid.random())

        val legacyTranscript = persister.upsertUserTranscriptTurn(
            conversation = conversation,
            text = "legacy text",
            turnId = "user-1",
            sessionId = null,
            status = VoiceTranscriptStatus.Complete,
        )
        val newSessionTranscript = persister.upsertUserTranscriptTurn(
            conversation = legacyTranscript,
            text = "new session text",
            turnId = "user-1",
            sessionId = "session-b",
            status = VoiceTranscriptStatus.Complete,
        )

        val texts = newSessionTranscript.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Text>()
            .map { it.text }

        assertEquals(listOf("legacy text", "new session text"), texts)
    }

    @Test
    fun `user transcript upsert preserves partial and session closed statuses`() {
        val persister = VoiceTranscriptPersister()
        val conversation = emptyConversation()
            .let {
                persister.upsertUserTranscriptTurn(
                    conversation = it,
                    text = "hel",
                    turnId = "user-1",
                    status = VoiceTranscriptStatus.Partial,
                )
            }
            .let {
                persister.upsertUserTranscriptTurn(
                    conversation = it,
                    text = "hello",
                    turnId = "user-1",
                    status = VoiceTranscriptStatus.SessionClosedBeforeFinal,
                )
            }

        assertEquals(1, conversation.currentMessages.size)
        val userText = conversation.currentMessages.single().parts.single() as UIMessagePart.Text
        assertEquals("hello", userText.text)
        assertEquals("session-closed-before-final", userText.metadata!!["voice_status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `assistant transcript upsert preserves partial complete and session closed statuses`() {
        val persister = VoiceTranscriptPersister()
        val conversation = emptyConversation()
            .let {
                persister.upsertAssistantTranscriptTurn(
                    conversation = it,
                    text = "h",
                    interrupted = false,
                    turnId = "assistant-1",
                    status = VoiceTranscriptStatus.Partial,
                )
            }
            .let {
                persister.upsertAssistantTranscriptTurn(
                    conversation = it,
                    text = "hi",
                    interrupted = false,
                    turnId = "assistant-1",
                    status = VoiceTranscriptStatus.Complete,
                )
            }
            .let {
                persister.upsertAssistantTranscriptTurn(
                    conversation = it,
                    text = "later",
                    interrupted = false,
                    turnId = "assistant-2",
                    status = VoiceTranscriptStatus.SessionClosedBeforeFinal,
                )
            }

        assertEquals(2, conversation.currentMessages.size)
        val completeText = conversation.currentMessages[0].parts.single() as UIMessagePart.Text
        val closedText = conversation.currentMessages[1].parts.single() as UIMessagePart.Text
        assertEquals("hi", completeText.text)
        assertEquals("complete", completeText.metadata!!["voice_status"]!!.jsonPrimitive.content)
        assertEquals("later", closedText.text)
        assertEquals("session-closed-before-final", closedText.metadata!!["voice_status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `voice transcript upsert replaces matching turn even when tool record interleaves`() {
        // The interleaved Hermes tool record is written through HermesToolRecordWriter
        // (Task 3); the assertion under test is purely about transcript positioning.
        val persister = VoiceTranscriptPersister()
        val writer = HermesToolRecordWriter()
        val conversation = emptyConversation()
            .let { persister.upsertUserTranscriptTurn(it, "hel", turnId = "user-1") }
            .let {
                writer.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "Prompt",
                    status = VoiceToolRecordStatus.Pending,
                )
            }
            .let { persister.upsertUserTranscriptTurn(it, "hello", turnId = "user-1") }

        assertEquals(2, conversation.currentMessages.size)
        assertEquals("hello", conversation.currentMessages[0].parts.text())
        val tool = conversation.currentMessages[1].parts.single() as UIMessagePart.Tool
        assertEquals("call-1", tool.toolCallId)
    }

    @Test
    fun `interrupted assistant turn records voice status metadata while preserving text`() {
        val persister = VoiceTranscriptPersister()

        val conversation = persister.appendAssistantTurn(
            conversation = emptyConversation(),
            text = "Partial answer",
            interrupted = true,
        )

        val textPart = conversation.currentMessages.single().parts.single() as UIMessagePart.Text
        assertEquals("Partial answer", textPart.text)
        assertEquals("interrupted", textPart.metadata!!["voice_status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `voice artifacts include session source identifiers status and timestamps`() {
        // The Hermes-tool portion of this row moved to
        // HermesToolRecordWriterTest.`upsertHermesTool stamps generic voice artifact
        // metadata when sessionId is present` (Task 3): pin 4 also drops voice_status and
        // output-part metadata from tool writes, which the old combined assertion here
        // could no longer satisfy.
        val persister = VoiceTranscriptPersister()
        val conversation = emptyConversation()
            .let {
                persister.upsertUserTranscriptTurn(
                    conversation = it,
                    text = "hello",
                    turnId = "user-1",
                    sessionId = "session-1",
                )
            }
            .let {
                persister.upsertAssistantTranscriptTurn(
                    conversation = it,
                    text = "hi",
                    interrupted = true,
                    turnId = "assistant-1",
                    sessionId = "session-1",
                )
            }

        val userText = conversation.currentMessages[0].parts.single() as UIMessagePart.Text
        val assistantText = conversation.currentMessages[1].parts.single() as UIMessagePart.Text

        assertVoiceMetadata(
            metadata = userText.metadata!!,
            sessionId = "session-1",
            eventId = "user-1",
            status = "complete",
        )
        assertVoiceMetadata(
            metadata = assistantText.metadata!!,
            sessionId = "session-1",
            eventId = "assistant-1",
            status = "interrupted",
        )
    }

    private fun emptyConversation(): Conversation = Conversation.ofId(
        id = Uuid.random(),
        messages = emptyList(),
    )

    private fun assertVoiceMetadata(
        metadata: kotlinx.serialization.json.JsonObject,
        sessionId: String,
        eventId: String,
        status: String,
    ) {
        assertEquals("voice_agent", metadata["voice_source"]!!.jsonPrimitive.content)
        assertEquals(sessionId, metadata["voice_session_id"]!!.jsonPrimitive.content)
        assertEquals(eventId, metadata["voice_event_id"]!!.jsonPrimitive.content)
        assertEquals(status, metadata["voice_status"]!!.jsonPrimitive.content)
        assertTrue(metadata["voice_created_at"]!!.jsonPrimitive.content.isNotBlank())
        assertTrue(metadata["voice_updated_at"]!!.jsonPrimitive.content.isNotBlank())
    }

    private fun List<UIMessagePart>.text(): String = filterIsInstance<UIMessagePart.Text>()
        .joinToString("") { it.text }
}
