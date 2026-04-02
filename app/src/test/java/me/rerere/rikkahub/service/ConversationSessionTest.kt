package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import me.rerere.rikkahub.data.ai.transformers.StRuntimeSnapshot
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationSessionTest {
    @Test
    fun `restore should prefer persisted local variables over snapshot locals`() {
        val session = createSession()
        val snapshot = StRuntimeSnapshot(
            generationType = "continue",
            localVariables = mapOf("source" to "snapshot"),
        )

        session.restoreStRuntimeState(
            snapshot = snapshot,
            persistentLocalVariables = mapOf("source" to "conversation"),
        )

        assertEquals("continue", session.stGenerationType)
        assertEquals(
            mapOf("source" to "conversation"),
            session.getPersistentLocalVariablesSnapshot(),
        )
    }

    @Test
    fun `restore should fall back to snapshot locals when persisted locals are empty`() {
        val session = createSession()
        val snapshot = StRuntimeSnapshot(
            generationType = "impersonate",
            localVariables = mapOf("source" to "snapshot"),
        )

        session.restoreStRuntimeState(
            snapshot = snapshot,
            persistentLocalVariables = emptyMap(),
        )

        assertEquals("impersonate", session.stGenerationType)
        assertEquals(
            mapOf("source" to "snapshot"),
            session.getPersistentLocalVariablesSnapshot(),
        )
    }

    @Test
    fun `macro state should track local variable mutations`() {
        val session = createSession()
        var callbackCount = 0
        val macroState = session.getStMacroState(
            globalVariables = mutableMapOf(),
            onLocalVariablesChanged = { callbackCount++ },
        )

        macroState.localVariables["mood"] = "calm"
        macroState.localVariables["mood"] = "calm"
        macroState.localVariables["style"] = "gentle"
        macroState.localVariables.remove("mood")

        assertEquals(3, callbackCount)
        assertEquals(
            mapOf("style" to "gentle"),
            session.getPersistentLocalVariablesSnapshot(),
        )
    }

    private fun createSession(): ConversationSession {
        val conversationId = Uuid.random()
        return ConversationSession(
            id = conversationId,
            initial = Conversation.ofId(id = conversationId),
            scope = CoroutineScope(Job()),
            onIdle = {},
        )
    }
}
