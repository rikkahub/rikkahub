package me.rerere.ai.runtime.contract

import kotlin.uuid.Uuid

/**
 * Neutral projection of a conversation the recent-chats recall path needs (issue #243 §B). Carries
 * only the fields that path reads — no Room entity / app `Conversation` crosses the boundary.
 */
data class ConversationSummary(
    val id: Uuid,
    val assistantId: Uuid,
    val title: String,
)

/**
 * Neutral port reading recent conversations for the recent-chats reference feature (issue #243 §B).
 * The app adapter maps its `Conversation` entities onto [ConversationSummary].
 */
interface ConversationReader {
    suspend fun recentConversations(assistantId: Uuid, limit: Int): List<ConversationSummary>
}
