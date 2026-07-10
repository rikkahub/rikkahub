package me.rerere.rikkahub.service

import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.PersistedConversationFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationPersistenceOrchestratorTest {
    private val conversationId = Uuid.parse("11111111-1111-4111-8111-111111111111")
    private val assistantId = Uuid.parse("22222222-2222-4222-8222-222222222222")
    private val oldFolderId = Uuid.parse("33333333-3333-4333-8333-333333333333")
    private val committedFolderId = Uuid.parse("44444444-4444-4444-8444-444444444444")

    @Test
    fun `existing stale save uses update then synchronizes session then indexes normalized row`() = runTest {
        val gateway = RecordingGateway(
            persistedLocation = PersistedConversationFolder(true, assistantId, committedFolderId),
        )
        val stale = conversation(folderId = oldFolderId)

        ConversationPersistenceOrchestrator(gateway).persist(
            conversationId = conversationId,
            conversation = stale,
            preservePersistedLocation = true,
        )

        assertEquals(listOf("read", "update", "session", "index"), gateway.events)
        assertEquals(committedFolderId, gateway.primaryConversation?.folderId)
        assertEquals(gateway.primaryConversation, gateway.sessionConversation)
        assertEquals(gateway.primaryConversation, gateway.indexedConversation)
    }

    @Test
    fun `new save uses insert and keeps requested folder`() = runTest {
        val gateway = RecordingGateway(PersistedConversationFolder(false, null, null))
        val requested = conversation(folderId = oldFolderId)

        ConversationPersistenceOrchestrator(gateway).persist(
            conversationId = conversationId,
            conversation = requested,
            preservePersistedLocation = true,
        )

        assertEquals(listOf("read", "insert", "session", "index"), gateway.events)
        assertEquals(oldFolderId, gateway.primaryConversation?.folderId)
    }

    @Test
    fun `assistant move bypasses persisted location while retaining production phase order`() = runTest {
        val targetAssistantId = Uuid.parse("55555555-5555-4555-8555-555555555555")
        val gateway = RecordingGateway(
            PersistedConversationFolder(true, assistantId, committedFolderId),
        )
        val moved = conversation(folderId = null).copy(assistantId = targetAssistantId)

        ConversationPersistenceOrchestrator(gateway).persist(
            conversationId = conversationId,
            conversation = moved,
            preservePersistedLocation = false,
        )

        assertEquals(targetAssistantId, gateway.primaryConversation?.assistantId)
        assertEquals(null, gateway.primaryConversation?.folderId)
        assertEquals(listOf("read", "update", "session", "index"), gateway.events)
    }

    @Test
    fun `primary failure propagates and suppresses session and index`() = runTest {
        val expected = IllegalStateException("room failed")
        val gateway = RecordingGateway(
            PersistedConversationFolder(true, assistantId, committedFolderId),
            primaryFailure = expected,
        )

        val actual = runCatching {
            ConversationPersistenceOrchestrator(gateway).persist(
                conversationId,
                conversation(oldFolderId),
                preservePersistedLocation = true,
            )
        }.exceptionOrNull()

        assertSame(expected, actual)
        assertEquals(listOf("read", "update"), gateway.events)
    }

    private fun conversation(folderId: Uuid?) = Conversation(
        id = conversationId,
        assistantId = assistantId,
        title = "saved",
        folderId = folderId,
        messageNodes = emptyList(),
    )

    private class RecordingGateway(
        private val persistedLocation: PersistedConversationFolder,
        private val primaryFailure: Throwable? = null,
    ) : ConversationPersistenceGateway {
        val events = mutableListOf<String>()
        var primaryConversation: Conversation? = null
        var sessionConversation: Conversation? = null
        var indexedConversation: Conversation? = null

        override suspend fun <T> serialize(
            conversationId: Uuid,
            persistPrimary: suspend (PersistedConversationFolder) -> T,
            onPrimaryCommitted: suspend (T) -> Unit,
            postPrimary: suspend (T) -> Unit,
        ): T {
            events += "read"
            val result = persistPrimary(persistedLocation)
            onPrimaryCommitted(result)
            postPrimary(result)
            return result
        }

        override suspend fun insertPrimary(conversation: Conversation) {
            events += "insert"
            primaryConversation = conversation
            primaryFailure?.let { throw it }
        }

        override suspend fun updatePrimary(conversation: Conversation) {
            events += "update"
            primaryConversation = conversation
            primaryFailure?.let { throw it }
        }

        override fun synchronizeSession(conversationId: Uuid, conversation: Conversation) {
            events += "session"
            sessionConversation = conversation
        }

        override suspend fun index(conversation: Conversation) {
            events += "index"
            indexedConversation = conversation
        }
    }
}
