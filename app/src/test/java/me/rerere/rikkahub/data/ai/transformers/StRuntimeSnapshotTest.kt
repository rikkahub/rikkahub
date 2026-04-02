package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class StRuntimeSnapshotTest {
    @Test
    fun `message metadata should round trip st runtime snapshot`() {
        val snapshot = StRuntimeSnapshot(
            generationType = "continue",
            localVariables = mapOf("mood" to "calm"),
            lorebookRuntimeState = LorebookRuntimeStateSnapshot(
                stickyEffects = mapOf(
                    Uuid.random() to TimedLorebookEffectSnapshot(
                        startMessageCount = 3,
                        endMessageCount = 5,
                    )
                )
            ),
        )

        val message = UIMessage.assistant("Hello").withStRuntimeSnapshot(snapshot)

        assertEquals(snapshot, message.readStRuntimeSnapshot())
    }

    @Test
    fun `message metadata helper should clear existing st runtime snapshot`() {
        val message = UIMessage.assistant("Hello")
            .withStRuntimeSnapshot(
                StRuntimeSnapshot(
                    generationType = "normal",
                    localVariables = mapOf("test" to "value"),
                )
            )
            .withStRuntimeSnapshot(null)

        assertNull(message.readStRuntimeSnapshot())
    }

    @Test
    fun `latest assistant runtime snapshot should ignore later assistant messages without snapshots`() {
        val snapshot = StRuntimeSnapshot(
            generationType = "continue",
            localVariables = mapOf("turn" to "2"),
        )
        val messages = listOf(
            UIMessage.user("hello"),
            UIMessage.assistant("generated").withStRuntimeSnapshot(snapshot),
            UIMessage.assistant("imported greeting"),
        )

        assertEquals(snapshot, messages.readLatestAssistantStRuntimeSnapshot())
    }

    @Test
    fun `latest assistant runtime snapshot should be null when no assistant snapshot exists`() {
        val messages = listOf(
            UIMessage.user("hello"),
            UIMessage.assistant("preset greeting"),
        )

        assertNull(messages.readLatestAssistantStRuntimeSnapshot())
    }

    @Test
    fun `lorebook runtime state should restore from persistence snapshot`() {
        val snapshot = LorebookRuntimeStateSnapshot(
            stickyEffects = mapOf(
                Uuid.random() to TimedLorebookEffectSnapshot(
                    startMessageCount = 2,
                    endMessageCount = 4,
                )
            ),
            cooldownEffects = mapOf(
                Uuid.random() to TimedLorebookEffectSnapshot(
                    startMessageCount = 4,
                    endMessageCount = 6,
                    protected = true,
                )
            ),
        )

        val state = LorebookRuntimeState()
        state.restoreFromSnapshot(snapshot)

        assertEquals(snapshot, state.snapshotForPersistence())
    }
}
