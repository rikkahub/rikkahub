package me.rerere.rikkahub.service

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AGENT_EVENT_ID_METADATA_KEY
import me.rerere.rikkahub.data.model.AGENT_EVENT_KIND_METADATA_KEY
import me.rerere.rikkahub.data.model.AGENT_EVENT_SYNTHETIC_KIND
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.SYNTHETIC_KIND_METADATA_KEY
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Pure regression seam for issue #290's startup replay race. [ChatService.initializeConversation]
 * installs a persisted Room snapshot, while startup replay can concurrently append a synthetic
 * agent-event node, consume the durable row, and continue the model. The initialization write must
 * preserve that live append-only tail; otherwise the consumed event becomes zero-delivery, or loses
 * its assistant/tool continuation after one delivery.
 */
class ConversationInitializationAgentEventRaceTest {

    @Test
    fun `initialization snapshot preserves concurrently appended synthetic agent event`() {
        val persisted = conversation(
            message("hello", MessageRole.USER).toMessageNode(),
            message("hi", MessageRole.ASSISTANT).toMessageNode(),
        )
        val synthetic = syntheticAgentEventNode("event-1")
        val liveAfterReplay = persisted.copy(messageNodes = persisted.messageNodes + synthetic)

        val merged = preserveConcurrentSyntheticAgentEventNodes(
            snapshot = persisted,
            live = liveAfterReplay,
        )

        assertEquals(persisted.messageNodes + synthetic, merged.messageNodes)
    }

    @Test
    fun `initialization snapshot preserves concurrent replay assistant continuation`() {
        val persisted = conversation(
            message("hello", MessageRole.USER, createdAt = beforeSnapshot).toMessageNode(),
            message("hi", MessageRole.ASSISTANT, createdAt = beforeSnapshot).toMessageNode(),
        )
        val synthetic = syntheticAgentEventNode("event-1", createdAt = afterSnapshot)
        val assistantContinuation = message(
            text = "handled event",
            role = MessageRole.ASSISTANT,
            createdAt = afterSnapshot,
        ).toMessageNode()
        val liveAfterReplay = persisted.copy(
            messageNodes = persisted.messageNodes + synthetic + assistantContinuation,
        )

        val merged = preserveConcurrentSyntheticAgentEventNodes(
            snapshot = persisted,
            live = liveAfterReplay,
        )

        assertEquals(persisted.messageNodes + synthetic + assistantContinuation, merged.messageNodes)
    }

    @Test
    fun `synthetic node already present in snapshot is not duplicated`() {
        val synthetic = syntheticAgentEventNode("event-1")
        val persisted = conversation(
            message("hello", MessageRole.USER).toMessageNode(),
            synthetic,
        )

        val merged = preserveConcurrentSyntheticAgentEventNodes(
            snapshot = persisted,
            live = persisted,
        )

        assertSame("unchanged snapshots should not allocate a duplicate merge", persisted, merged)
        assertEquals(listOf(synthetic), merged.messageNodes.filter { it == synthetic })
    }

    @Test
    fun `initialization merge does not resurrect non agent event live tail removed by snapshot`() {
        val persisted = conversation(message("hello", MessageRole.USER).toMessageNode())
        val removedTail = message(
            text = "old assistant tail",
            role = MessageRole.ASSISTANT,
            createdAt = afterSnapshot,
        ).toMessageNode()
        val liveBeforeDeletion = persisted.copy(messageNodes = persisted.messageNodes + removedTail)

        val merged = preserveConcurrentSyntheticAgentEventNodes(snapshot = persisted, live = liveBeforeDeletion)

        assertSame(persisted, merged)
    }

    @Test
    fun `initialization merge does not preserve live tail after branch switch`() {
        val liveUser = message("hello", MessageRole.USER, createdAt = beforeSnapshot).toMessageNode()
        val liveAssistant = message("old branch", MessageRole.ASSISTANT, createdAt = beforeSnapshot).toMessageNode()
        val snapshot = conversation(
            liveUser,
            message("new branch", MessageRole.ASSISTANT, createdAt = beforeSnapshot).toMessageNode(),
        )
        val live = snapshot.copy(
            messageNodes = listOf(liveUser, liveAssistant, syntheticAgentEventNode("event-1")),
        )

        val merged = preserveConcurrentSyntheticAgentEventNodes(snapshot = snapshot, live = live)

        assertSame(snapshot, merged)
    }

    private val beforeSnapshot = LocalDateTime(2025, 1, 1, 12, 0, 0)
    private val afterSnapshot = LocalDateTime(2027, 1, 1, 12, 0, 0)

    private fun conversation(vararg nodes: MessageNode): Conversation =
        Conversation.ofId(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messages = nodes.toList(),
        ).copy(updateAt = snapshotUpdatedAt)

    private fun message(
        text: String,
        role: MessageRole,
        createdAt: LocalDateTime = beforeSnapshot,
    ): UIMessage =
        UIMessage(
            role = role,
            parts = listOf(UIMessagePart.Text(text)),
            createdAt = createdAt,
        )

    private fun syntheticAgentEventNode(
        eventId: String,
        createdAt: LocalDateTime = afterSnapshot,
    ): MessageNode =
        UIMessage(
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Text(
                    text = """{"ok":true}""",
                    metadata = buildJsonObject {
                        put(SYNTHETIC_KIND_METADATA_KEY, AGENT_EVENT_SYNTHETIC_KIND)
                        put(AGENT_EVENT_ID_METADATA_KEY, eventId)
                        put(AGENT_EVENT_KIND_METADATA_KEY, "test")
                    },
                )
            ),
            createdAt = createdAt,
        ).toMessageNode()

    private companion object {
        val snapshotUpdatedAt: Instant = Instant.parse("2026-01-01T12:00:00Z")
    }
}
