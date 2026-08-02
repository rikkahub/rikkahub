package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import me.rerere.rikkahub.data.db.fts.MessageSearchSort
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class ConversationToolsTest {
    @Test
    fun `same keyword only returns conversations of the tool assistant`() = runBlocking {
        val firstAssistant = Uuid.random()
        val secondAssistant = Uuid.random()
        val firstResult = MessageSearchResult(
            nodeId = "node-first",
            messageId = "message-first",
            conversationId = "conversation-first",
            title = "First assistant",
            updateAt = Instant.EPOCH,
            snippet = "[shared keyword]",
        )
        val secondResult = firstResult.copy(
            nodeId = "node-second",
            messageId = "message-second",
            conversationId = "conversation-second",
            title = "Second assistant",
        )
        val search = { assistantId: Uuid, _: String, _: MessageSearchSort ->
            if (assistantId == firstAssistant) listOf(firstResult) else listOf(secondResult)
        }

        val firstTool = createConversationTools(
            assistantId = firstAssistant,
            getRecentConversations = { _, _ -> emptyList() },
            searchMessages = search,
        ).first { it.name == "conversation_search" }
        val secondTool = createConversationTools(
            assistantId = secondAssistant,
            getRecentConversations = { _, _ -> emptyList() },
            searchMessages = search,
        ).first { it.name == "conversation_search" }
        val input = buildJsonObject { put("query", "shared keyword") }

        val firstOutput = (firstTool.execute(input).single() as UIMessagePart.Text).text
        val secondOutput = (secondTool.execute(input).single() as UIMessagePart.Text).text

        assertTrue(firstOutput.contains(firstResult.conversationId))
        assertFalse(firstOutput.contains(secondResult.conversationId))
        assertTrue(secondOutput.contains(secondResult.conversationId))
        assertFalse(secondOutput.contains(firstResult.conversationId))
    }
}
