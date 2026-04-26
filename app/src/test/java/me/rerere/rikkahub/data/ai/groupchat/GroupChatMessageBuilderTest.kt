package me.rerere.rikkahub.data.ai.groupchat

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.GroupChatConfig
import me.rerere.rikkahub.data.model.GroupChatParticipant
import me.rerere.rikkahub.data.model.SpeakingOrder
import me.rerere.rikkahub.data.model.MessageNode
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.uuid.Uuid

class GroupChatMessageBuilderTest {

    private lateinit var assistant1: Assistant
    private lateinit var assistant2: Assistant
    private lateinit var assistant3: Assistant

    @Before
    fun setup() {
        assistant1 = Assistant(
            id = Uuid.random(),
            name = "GPT-4o",
            systemPrompt = "You are a helpful assistant."
        )
        assistant2 = Assistant(
            id = Uuid.random(),
            name = "Claude",
            systemPrompt = "You are a creative assistant."
        )
        assistant3 = Assistant(
            id = Uuid.random(),
            name = "Gemini",
            systemPrompt = "You are a logical assistant."
        )
    }

    @Test
    fun `getParticipantLabel should return assistant name`() {
        val participant = GroupChatParticipant(
            id = Uuid.random(),
            assistantId = assistant1.id,
            order = 0
        )

        val label = getParticipantLabel(
            participant = participant,
            allParticipants = listOf(participant),
            getAssistant = { id ->
                when (id) {
                    assistant1.id -> assistant1
                    else -> null
                }
            },
            getModelDisplayName = { "Test Model" }
        )

        assertEquals("GPT-4o", label)
    }

    @Test
    fun `getParticipantLabel should add suffix for same model participants`() {
        val participant1 = GroupChatParticipant(
            id = Uuid.random(),
            assistantId = assistant1.id,
            order = 0
        )
        val participant2 = GroupChatParticipant(
            id = Uuid.random(),
            assistantId = assistant1.id,
            order = 1
        )

        val label1 = getParticipantLabel(
            participant = participant1,
            allParticipants = listOf(participant1, participant2),
            getAssistant = { id ->
                when (id) {
                    assistant1.id -> assistant1
                    else -> null
                }
            },
            getModelDisplayName = { "Test Model" }
        )

        val label2 = getParticipantLabel(
            participant = participant2,
            allParticipants = listOf(participant1, participant2),
            getAssistant = { id ->
                when (id) {
                    assistant1.id -> assistant1
                    else -> null
                }
            },
            getModelDisplayName = { "Test Model" }
        )

        assertEquals("GPT-4o #1", label1)
        assertEquals("GPT-4o #2", label2)
    }

    @Test
    fun `buildGroupRoster should contain all participants`() {
        val participant1 = GroupChatParticipant(
            id = Uuid.random(),
            assistantId = assistant1.id,
            order = 0
        )
        val participant2 = GroupChatParticipant(
            id = Uuid.random(),
            assistantId = assistant2.id,
            order = 1
        )

        val config = GroupChatConfig(
            isGroupChat = true,
            participants = listOf(participant1, participant2)
        )

        val conversation = createConversation(config)

        val roster = buildGroupRoster(
            conversation = conversation,
            selfParticipantId = participant1.id,
            getAssistant = { id ->
                when (id) {
                    assistant1.id -> assistant1
                    assistant2.id -> assistant2
                    else -> null
                }
            },
            getModelDisplayName = { "Test Model" }
        )

        assertTrue(roster.contains("GPT-4o"))
        assertTrue(roster.contains("Claude"))
        assertTrue(roster.contains("← you"))
    }

    @Test
    fun `buildGroupRoster should contain group chat rules`() {
        val participant = GroupChatParticipant(
            id = Uuid.random(),
            assistantId = assistant1.id,
            order = 0
        )

        val config = GroupChatConfig(
            isGroupChat = true,
            participants = listOf(participant, participant.copy(id = Uuid.random(), order = 1))
        )

        val conversation = createConversation(config)

        val roster = buildGroupRoster(
            conversation = conversation,
            selfParticipantId = participant.id,
            getAssistant = { id ->
                when (id) {
                    assistant1.id -> assistant1
                    else -> null
                }
            },
            getModelDisplayName = { "Test Model" }
        )

        assertTrue(roster.contains("group chat with multiple AI participants"))
        assertTrue(roster.contains("Think independently"))
        assertTrue(roster.contains("Constructive debate is encouraged"))
    }

    @Test
    fun `resolveTargetParticipants should return all enabled participants when no mentions`() {
        val participant1 = GroupChatParticipant(
            id = Uuid.random(),
            assistantId = assistant1.id,
            order = 0
        )
        val participant2 = GroupChatParticipant(
            id = Uuid.random(),
            assistantId = assistant2.id,
            order = 1
        )
        val disabledParticipant = GroupChatParticipant(
            id = Uuid.random(),
            assistantId = assistant3.id,
            order = 2,
            enabled = false
        )

        val config = GroupChatConfig(
            isGroupChat = true,
            participants = listOf(participant1, participant2, disabledParticipant)
        )

        val conversation = createConversation(config)

        val targets = resolveTargetParticipants(conversation)

        assertEquals(2, targets.size)
        assertTrue(targets.any { it.id == participant1.id })
        assertTrue(targets.any { it.id == participant2.id })
        assertFalse(targets.any { it.id == disabledParticipant.id })
    }

    @Test
    fun `resolveTargetParticipants should return only mentioned participants`() {
        val participant1 = GroupChatParticipant(
            id = Uuid.random(),
            assistantId = assistant1.id,
            order = 0
        )
        val participant2 = GroupChatParticipant(
            id = Uuid.random(),
            assistantId = assistant2.id,
            order = 1
        )
        val participant3 = GroupChatParticipant(
            id = Uuid.random(),
            assistantId = assistant3.id,
            order = 2
        )

        val config = GroupChatConfig(
            isGroupChat = true,
            participants = listOf(participant1, participant2, participant3)
        )

        val conversation = createConversation(config)

        val targets = resolveTargetParticipants(conversation, listOf(participant2.id))

        assertEquals(1, targets.size)
        assertEquals(participant2.id, targets[0].id)
    }

    @Test
    fun `buildSystemPromptForParticipant should include base and group prompts`() {
        val participant = GroupChatParticipant(
            id = Uuid.random(),
            assistantId = assistant1.id,
            order = 0
        )

        val config = GroupChatConfig(
            isGroupChat = true,
            participants = listOf(participant, participant.copy(id = Uuid.random(), order = 1)),
            groupSystemPrompt = "This is a special group chat."
        )

        val conversation = createConversation(config)

        val systemPrompt = buildSystemPromptForParticipant(
            conversation = conversation,
            participant = participant,
            baseSystemPrompt = assistant1.systemPrompt,
            getAssistant = { id ->
                when (id) {
                    assistant1.id -> assistant1
                    else -> null
                }
            },
            getModelDisplayName = { "Test Model" }
        )

        assertTrue(systemPrompt.contains("You are a helpful assistant."))
        assertTrue(systemPrompt.contains("This is a special group chat."))
        assertTrue(systemPrompt.contains("group chat with multiple AI participants"))
    }

    @Test
    fun `transformMessageForGroupChat should not modify non-group chat`() {
        val participant = GroupChatParticipant(
            id = Uuid.random(),
            assistantId = assistant1.id,
            order = 0
        )

        val config = GroupChatConfig(isGroupChat = false)
        val conversation = createConversation(config)

        val userMessage = UIMessage.user("Hello, how are you?")

        val transformed = transformMessageForGroupChat(
            message = userMessage,
            conversation = conversation,
            selfParticipantId = participant.id,
            getParticipantInfo = { null }
        )

        assertEquals(userMessage, transformed)
    }

    @Test
    fun `transformMessageForGroupChat should prefix user messages`() {
        val participant = GroupChatParticipant(
            id = Uuid.random(),
            assistantId = assistant1.id,
            order = 0
        )

        val config = GroupChatConfig(
            isGroupChat = true,
            participants = listOf(participant, participant.copy(id = Uuid.random(), order = 1))
        )
        val conversation = createConversation(config)

        val userMessage = UIMessage.user("Hello, how are you?")

        val transformed = transformMessageForGroupChat(
            message = userMessage,
            conversation = conversation,
            selfParticipantId = participant.id,
            getParticipantInfo = { null }
        )

        val text = transformed.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        assertTrue(text.startsWith("[User said]:"))
        assertTrue(text.contains("Hello, how are you?"))
    }

    @Test
    fun `transformMessageForGroupChat should prefix other AI messages`() {
        val participant1 = GroupChatParticipant(
            id = Uuid.random(),
            assistantId = assistant1.id,
            order = 0
        )
        val participant2 = GroupChatParticipant(
            id = Uuid.random(),
            assistantId = assistant2.id,
            order = 1
        )

        val config = GroupChatConfig(
            isGroupChat = true,
            participants = listOf(participant1, participant2)
        )
        val conversation = createConversation(config)

        val participant2Info = me.rerere.rikkahub.data.model.MessageParticipantInfo(
            participantId = participant2.id,
            assistantId = participant2.assistantId,
            displayName = "Claude"
        )

        val metadata = buildJsonObject {
            put("participantId", JsonPrimitive(participant2.id.toString()))
            put("assistantId", JsonPrimitive(participant2.assistantId.toString()))
            put("displayName", JsonPrimitive("Claude"))
        }

        val aiMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("I think this is a great idea!", metadata = metadata)
            )
        )

        val transformed = transformMessageForGroupChat(
            message = aiMessage,
            conversation = conversation,
            selfParticipantId = participant1.id,
            getParticipantInfo = { id ->
                if (id == participant2.id) participant2Info else null
            }
        )

        val text = transformed.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        assertTrue(text.startsWith("[Claude said]:"))
        assertTrue(text.contains("I think this is a great idea!"))
        assertEquals(MessageRole.USER, transformed.role)
    }

    @Test
    fun `extractMentionedParticipants should find mentions`() {
        val participant1 = GroupChatParticipant(
            id = Uuid.random(),
            assistantId = assistant1.id,
            order = 0
        )
        val participant2 = GroupChatParticipant(
            id = Uuid.random(),
            assistantId = assistant2.id,
            order = 1
        )
        val participant3 = GroupChatParticipant(
            id = Uuid.random(),
            assistantId = assistant3.id,
            order = 2
        )

        val config = GroupChatConfig(
            isGroupChat = true,
            participants = listOf(participant1, participant2, participant3)
        )
        val conversation = createConversation(config)

        val content = "Hello @Claude, what do you think? @Gemini, your opinion?"

        val mentioned = extractMentionedParticipants(
            content = content,
            conversation = conversation,
            selfParticipantId = participant1.id,
            getAssistant = { id ->
                when (id) {
                    assistant1.id -> assistant1
                    assistant2.id -> assistant2
                    assistant3.id -> assistant3
                    else -> null
                }
            },
            getModelDisplayName = { "Test Model" }
        )

        assertEquals(2, mentioned.size)
        assertTrue(mentioned.contains(participant2.id))
        assertTrue(mentioned.contains(participant3.id))
        assertFalse(mentioned.contains(participant1.id))
    }

    @Test
    fun `extractMentionedParticipants should return empty for non-group chat`() {
        val config = GroupChatConfig(isGroupChat = false)
        val conversation = createConversation(config)

        val content = "Hello @Someone, what do you think?"

        val mentioned = extractMentionedParticipants(
            content = content,
            conversation = conversation,
            selfParticipantId = Uuid.random(),
            getAssistant = { null },
            getModelDisplayName = { "Test Model" }
        )

        assertTrue(mentioned.isEmpty())
    }

    private fun createConversation(config: GroupChatConfig): Conversation {
        return Conversation(
            id = Uuid.random(),
            title = "Test Conversation",
            groupChatConfig = config
        )
    }
}
