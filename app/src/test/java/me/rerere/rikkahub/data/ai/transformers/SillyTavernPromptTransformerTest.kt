package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.SillyTavernCharacterData
import me.rerere.rikkahub.data.model.SillyTavernPromptItem
import me.rerere.rikkahub.data.model.SillyTavernPromptTemplate
import me.rerere.rikkahub.data.model.StPromptInjectionPosition
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class SillyTavernPromptTransformerTest {
    @Test
    fun `template should map card data and lorebook markers into ordered prompts`() {
        val lorebook = Lorebook(
            id = Uuid.random(),
            entries = listOf(
                PromptInjection.RegexInjection(
                    id = Uuid.random(),
                    constantActive = true,
                    position = InjectionPosition.BEFORE_SYSTEM_PROMPT,
                    content = "Lore Before",
                ),
                PromptInjection.RegexInjection(
                    id = Uuid.random(),
                    constantActive = true,
                    position = InjectionPosition.AFTER_SYSTEM_PROMPT,
                    content = "Lore After",
                ),
            )
        )
        val assistant = Assistant(
            stPromptTemplate = SillyTavernPromptTemplate(
                wiFormat = "<wi>{0}</wi>",
                prompts = listOf(
                    SillyTavernPromptItem(identifier = "main", content = "Main Prompt"),
                    SillyTavernPromptItem(identifier = "worldInfoBefore", marker = true),
                    SillyTavernPromptItem(identifier = "charDescription", marker = true),
                    SillyTavernPromptItem(identifier = "worldInfoAfter", marker = true),
                    SillyTavernPromptItem(identifier = "chatHistory", marker = true),
                    SillyTavernPromptItem(identifier = "jailbreak", content = "Preset Jailbreak"),
                ),
                orderedPromptIds = listOf(
                    "main",
                    "worldInfoBefore",
                    "charDescription",
                    "worldInfoAfter",
                    "chatHistory",
                    "jailbreak",
                ),
            ),
            stCharacterData = SillyTavernCharacterData(
                description = "Character Description",
                postHistoryInstructions = "Card Jailbreak",
            ),
            lorebookIds = setOf(lorebook.id),
        )

        val result = transformSillyTavernPrompt(
            messages = listOf(
                UIMessage.system("Base System"),
                UIMessage.user("Hello"),
                UIMessage.assistant("Hi"),
            ),
            assistant = assistant,
            lorebooks = listOf(lorebook),
            template = assistant.stPromptTemplate!!,
        )

        assertEquals(
            listOf(
                "Base System",
                "Main Prompt",
                "<wi>Lore Before</wi>",
                "Character Description",
                "<wi>Lore After</wi>",
                "Hello",
                "Hi",
                "Card Jailbreak",
            ),
            result.map { it.toText() }
        )
    }

    @Test
    fun `absolute prompts should be inserted by depth order and role`() {
        val template = SillyTavernPromptTemplate(
            prompts = listOf(
                SillyTavernPromptItem(identifier = "chatHistory", marker = true),
                SillyTavernPromptItem(
                    identifier = "absoluteSystem",
                    role = MessageRole.SYSTEM,
                    content = "Absolute System",
                    injectionPosition = StPromptInjectionPosition.ABSOLUTE,
                    injectionDepth = 1,
                    injectionOrder = 200,
                ),
                SillyTavernPromptItem(
                    identifier = "absoluteUser",
                    role = MessageRole.USER,
                    content = "Absolute User",
                    injectionPosition = StPromptInjectionPosition.ABSOLUTE,
                    injectionDepth = 1,
                    injectionOrder = 100,
                ),
            ),
            orderedPromptIds = listOf("chatHistory"),
        )

        val result = transformSillyTavernPrompt(
            messages = listOf(
                UIMessage.user("U1"),
                UIMessage.assistant("A1"),
                UIMessage.user("U2"),
            ),
            assistant = Assistant(stPromptTemplate = template),
            lorebooks = emptyList(),
            template = template,
        )

        assertEquals(
            listOf("U1", "A1", "Absolute System", "Absolute User", "U2"),
            result.map { it.toText() }
        )
        assertEquals(
            listOf(
                MessageRole.USER,
                MessageRole.ASSISTANT,
                MessageRole.SYSTEM,
                MessageRole.USER,
                MessageRole.USER,
            ),
            result.map { it.role }
        )
    }

    @Test
    fun `chat history should include new chat prompt and character depth prompt`() {
        val template = SillyTavernPromptTemplate(
            newChatPrompt = "[Start]",
            prompts = listOf(
                SillyTavernPromptItem(identifier = "chatHistory", marker = true),
            ),
            orderedPromptIds = listOf("chatHistory"),
        )

        val result = transformSillyTavernPrompt(
            messages = listOf(
                UIMessage.user("U1"),
                UIMessage.assistant("A1"),
                UIMessage.user("U2"),
            ),
            assistant = Assistant(
                stPromptTemplate = template,
                stCharacterData = SillyTavernCharacterData(
                    depthPrompt = me.rerere.rikkahub.data.model.StDepthPrompt(
                        prompt = "Depth Prompt",
                        depth = 1,
                        role = MessageRole.SYSTEM,
                    )
                ),
            ),
            lorebooks = emptyList(),
            template = template,
        )

        assertEquals(
            listOf("[Start]", "U1", "A1", "Depth Prompt", "U2"),
            result.map { it.toText() }
        )
    }

    @Test
    fun `chat history should append send_if_empty when last message is assistant`() {
        val template = SillyTavernPromptTemplate(
            sendIfEmpty = "[Keep going]",
            prompts = listOf(
                SillyTavernPromptItem(identifier = "chatHistory", marker = true),
            ),
            orderedPromptIds = listOf("chatHistory"),
        )

        val result = transformSillyTavernPrompt(
            messages = listOf(
                UIMessage.user("U1"),
                UIMessage.assistant("A1"),
            ),
            assistant = Assistant(stPromptTemplate = template),
            lorebooks = emptyList(),
            template = template,
        )

        assertEquals(
            listOf("U1", "A1", "[Keep going]"),
            result.map { it.toText() }
        )
        assertEquals(
            listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER),
            result.map { it.role }
        )
    }
}
