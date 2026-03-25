package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.SillyTavernPromptTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SillyTavernMacroTransformerTest {
    @Test
    fun `macros should resolve variables and remove empty prompt messages`() {
        val env = StMacroEnvironment(
            user = "Alice",
            char = "Seraphina",
            group = "Seraphina",
            groupNotMuted = "Seraphina",
            notChar = "Alice",
            characterDescription = "Guardian",
            characterPersonality = "Warm",
            scenario = "Forest",
            persona = "",
            charPrompt = "Card Main",
            charInstruction = "Card Jailbreak",
            charDepthPrompt = "Depth Note",
            creatorNotes = "Creator Notes",
            exampleMessagesRaw = "<START>\nSeraphina: Hello",
            lastChatMessage = "Latest reply",
            lastUserMessage = "Latest user input",
            lastAssistantMessage = "Latest assistant output",
            modelName = "Test Model",
        )

        val result = SillyTavernMacroTransformer.applySillyTavernMacros(
            messages = listOf(
                UIMessage.system("{{setvar::style::Gentle}}{{// hidden}}"),
                UIMessage.system("{{getvar::style}} / {{char}} / {{user}} / {{lastUsermessage}}"),
            ),
            env = env,
        )

        assertEquals(1, result.size)
        assertEquals("Gentle / Seraphina / Alice / Latest user input", result.single().toText())
    }

    @Test
    fun `macros should support random roll and optional system squashing`() {
        val env = StMacroEnvironment(
            user = "Alice",
            char = "Seraphina",
            group = "Seraphina",
            groupNotMuted = "Seraphina",
            notChar = "Alice",
            characterDescription = "",
            characterPersonality = "",
            scenario = "",
            persona = "",
            charPrompt = "",
            charInstruction = "",
            charDepthPrompt = "",
            creatorNotes = "",
            exampleMessagesRaw = "",
            lastChatMessage = "",
            lastUserMessage = "",
            lastAssistantMessage = "",
            modelName = "Test Model",
        )

        val result = SillyTavernMacroTransformer.applySillyTavernMacros(
            messages = listOf(
                UIMessage.system("{{random::A::B}}"),
                UIMessage.system("{{roll 1d1}}"),
                UIMessage.system("[Start]"),
                UIMessage.user("Hi"),
            ),
            env = env,
            template = SillyTavernPromptTemplate(
                newChatPrompt = "[Start]",
                squashSystemMessages = true,
            ),
        )

        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.SYSTEM, MessageRole.USER),
            result.map { it.role }
        )
        assertTrue(result.first().toText() == "A\n1" || result.first().toText() == "B\n1")
        assertEquals("[Start]", result[1].toText())
    }
}
