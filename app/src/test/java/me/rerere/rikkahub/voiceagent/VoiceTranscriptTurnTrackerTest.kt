package me.rerere.rikkahub.voiceagent

import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.persistence.VoiceTranscriptPersister
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class VoiceTranscriptTurnTrackerTest {

    @Test
    fun `user turn ids increment and a speaker switch starts a new turn`() {
        val diagnostics = mutableListOf<Pair<String, String>>()
        val tracker = tracker(recordDiagnostic = { name, detail -> diagnostics += name to detail })

        tracker.appendUserDelta("hel")
        val secondUserSnapshot = tracker.appendUserDelta("lo")
        tracker.appendAssistantDelta("hi", suppressed = false)
        val thirdUserSnapshot = tracker.appendUserDelta("again")

        val turnIds = diagnostics
            .filter { it.first == "input_transcript_delta" || it.first == "output_transcript_delta" }
            .map { it.second.substringAfter("turnId=").substringBefore(",") }

        assertEquals(listOf("user-1", "user-1", "assistant-2", "user-3"), turnIds)
        // Second delta in the same (unswitched) turn accumulates onto the existing buffer.
        assertEquals("hello", secondUserSnapshot)
        // Switching away and back to User starts a fresh turn/buffer rather than accumulating.
        assertEquals("again", thirdUserSnapshot)
    }

    @Test
    fun `assistant partial persists carry Partial status`() {
        val transforms = mutableListOf<(Conversation) -> Conversation>()
        val tracker = tracker(persist = recordingPersist(transforms))

        tracker.appendAssistantDelta("hi", suppressed = false)

        assertEquals("partial", lastAssistantStatus(transforms))
    }

    @Test
    fun `interruptAssistantTurn marks Interrupted unless already Complete`() {
        // Partial -> Interrupted
        val partialTransforms = mutableListOf<(Conversation) -> Conversation>()
        val partialTracker = tracker(persist = recordingPersist(partialTransforms))
        partialTracker.appendAssistantDelta("hi", suppressed = false)
        partialTracker.interruptAssistantTurn(suppressed = true)
        assertEquals("interrupted", lastAssistantStatus(partialTransforms))

        // Complete -> stays Complete even when interrupted afterwards
        val completeTransforms = mutableListOf<(Conversation) -> Conversation>()
        val completeTracker = tracker(persist = recordingPersist(completeTransforms))
        completeTracker.appendAssistantDelta("hi", suppressed = false)
        completeTracker.completeAssistantTurn(suppressed = false)
        completeTracker.interruptAssistantTurn(suppressed = true)
        assertEquals("complete", lastAssistantStatus(completeTransforms))
    }

    @Test
    fun `completeAssistantTurn is a no-op when suppressed or interrupted or blank`() {
        // Blank turn: never persists at all.
        val blankTransforms = mutableListOf<(Conversation) -> Conversation>()
        val blankTracker = tracker(persist = recordingPersist(blankTransforms))
        blankTracker.completeAssistantTurn(suppressed = false)
        assertEquals(0, blankTransforms.size)

        // Suppressed: no additional persist beyond the initial partial delta.
        val suppressedTransforms = mutableListOf<(Conversation) -> Conversation>()
        val suppressedTracker = tracker(persist = recordingPersist(suppressedTransforms))
        suppressedTracker.appendAssistantDelta("hi", suppressed = false)
        val suppressedCountBefore = suppressedTransforms.size
        suppressedTracker.completeAssistantTurn(suppressed = true)
        assertEquals(suppressedCountBefore, suppressedTransforms.size)

        // Already interrupted: completeAssistantTurn does not persist again.
        val interruptedTransforms = mutableListOf<(Conversation) -> Conversation>()
        val interruptedTracker = tracker(persist = recordingPersist(interruptedTransforms))
        interruptedTracker.appendAssistantDelta("hi", suppressed = false)
        interruptedTracker.interruptAssistantTurn(suppressed = true)
        val interruptedCountBefore = interruptedTransforms.size
        interruptedTracker.completeAssistantTurn(suppressed = false)
        assertEquals(interruptedCountBefore, interruptedTransforms.size)
    }

    @Test
    fun `persistAssistantForSessionClose status matrix`() {
        // suppressed -> Interrupted
        val suppressedTransforms = mutableListOf<(Conversation) -> Conversation>()
        val suppressedTracker = tracker(persist = recordingPersist(suppressedTransforms))
        suppressedTracker.appendAssistantDelta("hi", suppressed = false)
        suppressedTracker.persistAssistantForSessionClose(suppressed = true)
        assertEquals("interrupted", lastAssistantStatus(suppressedTransforms))

        // already interrupted -> Interrupted
        val interruptedTransforms = mutableListOf<(Conversation) -> Conversation>()
        val interruptedTracker = tracker(persist = recordingPersist(interruptedTransforms))
        interruptedTracker.appendAssistantDelta("hi", suppressed = false)
        interruptedTracker.interruptAssistantTurn(suppressed = true)
        interruptedTracker.persistAssistantForSessionClose(suppressed = false)
        assertEquals("interrupted", lastAssistantStatus(interruptedTransforms))

        // complete -> Complete
        val completeTransforms = mutableListOf<(Conversation) -> Conversation>()
        val completeTracker = tracker(persist = recordingPersist(completeTransforms))
        completeTracker.appendAssistantDelta("hi", suppressed = false)
        completeTracker.completeAssistantTurn(suppressed = false)
        completeTracker.persistAssistantForSessionClose(suppressed = false)
        assertEquals("complete", lastAssistantStatus(completeTransforms))

        // partial, non-suppressed -> SessionClosedBeforeFinal
        val partialTransforms = mutableListOf<(Conversation) -> Conversation>()
        val partialTracker = tracker(persist = recordingPersist(partialTransforms))
        partialTracker.appendAssistantDelta("hi", suppressed = false)
        partialTracker.persistAssistantForSessionClose(suppressed = false)
        assertEquals("session-closed-before-final", lastAssistantStatus(partialTransforms))

        // blank turn -> no persist
        val blankTransforms = mutableListOf<(Conversation) -> Conversation>()
        val blankTracker = tracker(persist = recordingPersist(blankTransforms))
        blankTracker.persistAssistantForSessionClose(suppressed = false)
        assertEquals(0, blankTransforms.size)
    }

    @Test
    fun `final transcript telemetry fires once per turn`() {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        val transforms = mutableListOf<(Conversation) -> Conversation>()
        val tracker = tracker(
            persist = recordingPersist(transforms),
            recordEvent = { name, attributes -> events += name to attributes },
        )

        tracker.appendAssistantDelta("hi", suppressed = false)
        assertEquals(0, events.count { it.first == "voicelab.mobile.transcript.assistant_final" })
        assertEquals(0, events.count { it.first == "voicelab.mobile.transcript.turn" })

        tracker.completeAssistantTurn(suppressed = false)
        tracker.completeAssistantTurn(suppressed = false)

        assertEquals(1, events.count { it.first == "voicelab.mobile.transcript.assistant_final" })
        assertEquals(1, events.count { it.first == "voicelab.mobile.transcript.turn" })
    }

    @Test
    fun `persistUserForSessionClose skips blank input`() {
        val transforms = mutableListOf<(Conversation) -> Conversation>()
        val tracker = tracker(persist = recordingPersist(transforms))

        tracker.persistUserForSessionClose()

        assertEquals(0, transforms.size)

        // Sanity: a non-blank turn does persist through the same path.
        tracker.appendUserDelta("hel")
        transforms.clear()
        tracker.persistUserForSessionClose()
        assertEquals(1, transforms.size)
        val conversation = transforms.single().invoke(emptyConversation())
        val text = conversation.currentMessages.single().parts.filterIsInstance<UIMessagePart.Text>().single()
        assertEquals("session-closed-before-final", text.metadata!!["voice_status"]!!.jsonPrimitive.content)
    }

    private fun tracker(
        persist: (transform: (Conversation) -> Conversation, onPersisted: () -> Unit) -> Unit = { _, onPersisted -> onPersisted() },
        recordEvent: (name: String, attributes: Map<String, Any?>) -> Unit = { _, _ -> },
        recordDiagnostic: (name: String, detail: String) -> Unit = { _, _ -> },
    ): VoiceTranscriptTurnTracker = VoiceTranscriptTurnTracker(
        transcriptPersister = VoiceTranscriptPersister(),
        sessionId = "session-under-test",
        persist = persist,
        recordEvent = recordEvent,
        recordDiagnostic = recordDiagnostic,
    )

    private fun recordingPersist(
        transforms: MutableList<(Conversation) -> Conversation>,
    ): (transform: (Conversation) -> Conversation, onPersisted: () -> Unit) -> Unit =
        { transform, onPersisted ->
            transforms += transform
            onPersisted()
        }

    private fun lastAssistantStatus(transforms: List<(Conversation) -> Conversation>): String {
        val conversation = transforms.last().invoke(emptyConversation())
        val text = conversation.currentMessages.single().parts.filterIsInstance<UIMessagePart.Text>().single()
        return text.metadata!!["voice_status"]!!.jsonPrimitive.content
    }

    private fun emptyConversation(): Conversation = Conversation.ofId(
        id = Uuid.random(),
        messages = emptyList(),
    )
}
