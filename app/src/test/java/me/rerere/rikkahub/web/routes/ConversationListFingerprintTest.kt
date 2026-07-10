package me.rerere.rikkahub.web.routes

import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class ConversationListFingerprintTest {
    private val assistantId = Uuid.parse("11111111-1111-4111-8111-111111111111")
    private val conversationId = Uuid.parse("22222222-2222-4222-8222-222222222222")
    private val folderId = Uuid.parse("33333333-3333-4333-8333-333333333333")
    private val conversation = Conversation(
        id = conversationId,
        assistantId = assistantId,
        title = "Initial title",
        createAt = Instant.ofEpochMilli(1_000),
        updateAt = Instant.ofEpochMilli(2_000),
        messageNodes = emptyList(),
    )

    @Test
    fun `title changes affect the conversation list fingerprint`() {
        assertFingerprintChanges(conversation.copy(title = "Renamed"))
    }

    @Test
    fun `pin changes affect the conversation list fingerprint`() {
        assertFingerprintChanges(conversation.copy(isPinned = true))
    }

    @Test
    fun `folder changes affect the conversation list fingerprint`() {
        assertFingerprintChanges(conversation.copy(folderId = folderId))
    }

    @Test
    fun `generation changes affect the conversation list fingerprint`() {
        assertNotEquals(
            conversationListFingerprint(listOf(conversation), emptySet()),
            conversationListFingerprint(listOf(conversation), setOf(conversationId)),
        )
    }

    @Test
    fun `creation time changes affect the conversation list fingerprint`() {
        assertFingerprintChanges(conversation.copy(createAt = Instant.ofEpochMilli(3_000)))
    }

    @Test
    fun `update time changes affect the conversation list fingerprint`() {
        assertFingerprintChanges(conversation.copy(updateAt = Instant.ofEpochMilli(3_000)))
    }

    @Test
    fun `assistant changes affect the conversation list fingerprint`() {
        assertFingerprintChanges(conversation.copy(assistantId = Uuid.random()))
    }

    @Test
    fun `conversation creation and deletion affect the conversation list fingerprint`() {
        val empty = conversationListFingerprint(emptyList(), emptySet())
        val created = conversationListFingerprint(listOf(conversation), emptySet())

        assertNotEquals(empty, created)
        assertNotEquals(created, empty)
    }

    private fun assertFingerprintChanges(changed: Conversation) {
        assertNotEquals(
            conversationListFingerprint(listOf(conversation), emptySet()),
            conversationListFingerprint(listOf(changed), emptySet()),
        )
    }
}
