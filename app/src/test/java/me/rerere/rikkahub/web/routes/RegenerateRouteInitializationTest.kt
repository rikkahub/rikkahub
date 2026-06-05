package me.rerere.rikkahub.web.routes

import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Regression test for issue #76: POST /conversations/{id}/regenerate returned
 * "Message not found" for a persisted message after the in-memory session was dropped
 * (app restart / idle ConversationSession removal).
 *
 * This drives the REAL production helper [resolveRegenerateTarget] (ConversationRoutes.kt) — the
 * exact function the regenerate route now calls — against fakes that reproduce the two ChatService
 * collaborators it depends on:
 *
 *  - [getConversation] mirrors `getConversationFlow(uuid).first()` -> `getOrCreateSession`, which on
 *    a session cache-miss fabricates a FRESH EMPTY `Conversation.ofId(...)` (ChatService.kt:268).
 *  - [initialize] mirrors `ChatService.initializeConversation` (ChatService.kt:400): load the
 *    persisted conversation from the repository into the session before the state is read.
 *
 * The fix is load-bearing in the production code, not in the test: [resolveRegenerateTarget] calls
 * `initialize(conversationId)` BEFORE `getConversation(conversationId)`. Delete that one call from
 * the helper and the first test below fails — the fresh empty session can no longer resolve the
 * persisted message. (No mockk/Robolectric/ktor-test-host is on the unit-test classpath; the route
 * resolve logic was extracted into this pure helper precisely so it can be unit-tested for real.)
 */
@OptIn(ExperimentalUuidApi::class)
class RegenerateRouteInitializationTest {

    private val assistantId = Uuid.random()

    /** Durably-persisted conversations — analog of ConversationRepository. */
    private val repository = ConcurrentHashMap<Uuid, Conversation>()

    /**
     * In-memory session store — analog of ChatService.sessions. On cache-miss it fabricates a FRESH
     * EMPTY Conversation, mirroring getOrCreateSession's `computeIfAbsent`.
     */
    private val sessions = ConcurrentHashMap<Uuid, Conversation>()

    /** Fake of `getConversationFlow(uuid).first()`: getOrCreateSession then read state. */
    private val getConversation: suspend (Uuid) -> Conversation = { id ->
        sessions.computeIfAbsent(id) { Conversation.ofId(id = it, assistantId = assistantId) }
    }

    /** Fake of `ChatService.initializeConversation`: load persisted conversation into the session. */
    private val initialize: suspend (Uuid) -> Unit = { id ->
        sessions.computeIfAbsent(id) { Conversation.ofId(id = it, assistantId = assistantId) }
        repository[id]?.let { sessions[id] = it }
    }

    private fun persistConversationWithMessage(): Pair<Uuid, UIMessage> {
        val conversationId = Uuid.random()
        val message = UIMessage.assistant("hello from a persisted turn")
        val conversation = Conversation.ofId(
            id = conversationId,
            assistantId = assistantId,
            messages = listOf(message.toMessageNode()),
        )
        repository[conversationId] = conversation
        return conversationId to message
    }

    @Test
    fun `regenerate resolves a persisted message whose session was dropped`() = runBlocking {
        val (conversationId, message) = persistConversationWithMessage()

        // Session was dropped (cache-miss). The helper must initialize before reading, otherwise the
        // fresh empty session yields null and the route 404s — the issue #76 bug. This asserts the
        // production helper's initialize-before-read ordering: drop that call and this fails.
        val resolved = resolveRegenerateTarget(
            conversationId = conversationId,
            messageId = message.id,
            initialize = initialize,
            getConversation = getConversation,
        )

        assertEquals(
            "resolveRegenerateTarget must initialize the dropped session from the repository before " +
                "reading it, so the persisted message resolves instead of 404'ing",
            message.id,
            resolved?.id,
        )
    }

    @Test
    fun `regenerate returns null for an unknown messageId`() = runBlocking {
        val (conversationId, _) = persistConversationWithMessage()

        // A genuinely unknown messageId must still resolve to null (route still 404s) even after the
        // persisted state is loaded — guards against the fix over-resolving.
        val resolved = resolveRegenerateTarget(
            conversationId = conversationId,
            messageId = Uuid.random(),
            initialize = initialize,
            getConversation = getConversation,
        )

        assertNull(
            "an unknown messageId is absent from the loaded persisted state, so the route still 404s",
            resolved,
        )
    }
}
