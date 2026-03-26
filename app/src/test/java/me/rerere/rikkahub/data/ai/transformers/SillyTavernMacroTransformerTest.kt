package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.SillyTavernPromptTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

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
            persona = "I catalog every answer carefully.",
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
                UIMessage.system("{{getvar::style}} / {{char}} / {{user}} / {{persona}} / {{lastUsermessage}}"),
            ),
            env = env,
        )

        assertEquals(1, result.size)
        assertEquals(
            "Gentle / Seraphina / Alice / I catalog every answer carefully. / Latest user input",
            result.single().toText()
        )
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

    @Test
    fun `macros should support scoped if else and trim blocks`() {
        val env = StMacroEnvironment(
            user = "Alice",
            char = "Seraphina",
            group = "Seraphina",
            groupNotMuted = "Seraphina",
            notChar = "Alice",
            characterDescription = "Guardian",
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
                UIMessage.system(
                    """
                    {{if description}}
                      # Description
                      {{description}}
                    {{else}}
                      Missing
                    {{/if}}
                    """.trimIndent()
                ),
                UIMessage.system("{{trim}}\n\n  padded text\n\n{{/trim}}"),
            ),
            env = env,
        )

        assertEquals(2, result.size)
        assertEquals("# Description\nGuardian", result[0].toText())
        assertEquals("padded text", result[1].toText())
    }

    @Test
    fun `macros should support inline if and variable shorthand`() {
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
                UIMessage.system(
                    "{{setvar::showHeader::1}}{{if .showHeader::Visible}}{{newline}}{{if !personality::Fallback}}"
                ),
            ),
            env = env,
        )

        assertEquals(1, result.size)
        assertEquals("Visible\nFallback", result.single().toText())
    }

    @Test
    fun `macros should persist variables when the same state is reused`() {
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
        val state = StMacroState()

        SillyTavernMacroTransformer.applySillyTavernMacros(
            messages = listOf(
                UIMessage.system("{{setvar::style::Gentle}}{{setglobalvar::theme::Moon}}"),
            ),
            env = env,
            state = state,
        )

        val result = SillyTavernMacroTransformer.applySillyTavernMacros(
            messages = listOf(
                UIMessage.system("{{getvar::style}} / {{getglobalvar::theme}}"),
            ),
            env = env,
            state = state,
        )

        assertEquals(1, result.size)
        assertEquals("Gentle / Moon", result.single().toText())
    }

    @Test
    fun `macros should expose extended st environment state`() {
        val state = StMacroState()
        val env = StMacroEnvironment(
            user = "Alice",
            char = "Seraphina",
            group = "Seraphina",
            groupNotMuted = "Seraphina",
            notChar = "Alice",
            characterDescription = "Guardian",
            characterPersonality = "Warm",
            scenario = "Forest",
            persona = "Archivist",
            charPrompt = "Character Prompt",
            charInstruction = "Instruction",
            charDepthPrompt = "Depth",
            creatorNotes = "Creator Notes",
            exampleMessagesRaw = "<START>",
            lastChatMessage = "Latest reply",
            lastUserMessage = "Current input",
            lastAssistantMessage = "Latest reply",
            modelName = "Test Model",
            input = "Current input",
            original = "Current input",
            charVersion = "3.0",
            lastMessageId = "5",
            firstIncludedMessageId = "1",
            firstDisplayedMessageId = "0",
            maxPrompt = "8192",
            defaultSystemPrompt = "Default System",
            systemPrompt = "Character Prompt",
            generationType = "continue",
            availableExtensions = setOf("regex"),
            now = ZonedDateTime.of(2026, 3, 26, 14, 30, 0, 0, ZoneId.of("UTC")),
            lastUserMessageCreatedAt = LocalDateTime.of(2026, 3, 26, 12, 30, 0),
        )

        val firstPick = SillyTavernMacroTransformer.applySillyTavernMacros(
            messages = listOf(
                UIMessage.system("{{pick::red::blue}}"),
            ),
            env = env,
            state = state,
        ).single().toText()
        val secondPick = SillyTavernMacroTransformer.applySillyTavernMacros(
            messages = listOf(
                UIMessage.system("{{pick::red::blue}}"),
            ),
            env = env,
            state = state,
        ).single().toText()
        val stateResult = SillyTavernMacroTransformer.applySillyTavernMacros(
            messages = listOf(
                UIMessage.system(
                    "{{input}} / {{original}} / {{charVersion}} / {{lastMessageId}} / " +
                        "{{firstIncludedMessageId}} / {{firstDisplayedMessageId}} / {{maxPrompt}} / " +
                        "{{lastGenerationType}} / {{hasExtension::regex}} / {{reverse::abc}} / " +
                        "{{systemPrompt}} / {{defaultSystemPrompt}} / {{idleDuration}} / " +
                        "{{datetimeformat::YYYY-MM-DD HH:mm}}"
                ),
            ),
            env = env,
            state = state,
        ).single().toText()

        assertTrue(firstPick == "red" || firstPick == "blue")
        assertEquals(firstPick, secondPick)
        assertEquals(
            "Current input / Current input / 3.0 / 5 / 1 / 0 / 8192 / continue / true / cba / " +
                "Character Prompt / Default System / 2 hours / 2026-03-26 14:30",
            stateResult
        )
    }

    @Test
    fun `macros should support time diff and scoped comments`() {
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
            now = ZonedDateTime.of(2026, 3, 26, 8, 0, 0, 0, ZoneId.of("UTC")),
        )

        val result = SillyTavernMacroTransformer.applySillyTavernMacros(
            messages = listOf(
                UIMessage.system("{{//}}hidden{{///}}show"),
                UIMessage.system("{{timeDiff::2026-03-26 15:00:00::2026-03-26 12:00:00}}"),
            ),
            env = env,
        )

        assertEquals(listOf("show", "in 3 hours"), result.map { it.toText() })
    }
}
