package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.SillyTavernCharacterData
import me.rerere.rikkahub.data.model.SillyTavernPromptItem
import me.rerere.rikkahub.data.model.SillyTavernPromptOrderItem
import me.rerere.rikkahub.data.model.SillyTavernPromptTemplate
import me.rerere.rikkahub.data.model.StPromptInjectionPosition
import me.rerere.rikkahub.data.model.withPromptOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
                "Base System\nMain Prompt\n<wi>Lore Before</wi>\nCharacter Description\n<wi>Lore After</wi>\nCard Jailbreak",
                "Hello",
                "Hi",
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
            orderedPromptIds = listOf("chatHistory", "absoluteSystem", "absoluteUser"),
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

    @Test
    fun `leading system prompt sections should collapse into one message`() {
        val template = SillyTavernPromptTemplate(
            prompts = listOf(
                SillyTavernPromptItem(identifier = "main", content = "Main Prompt"),
                SillyTavernPromptItem(identifier = "charDescription", marker = true),
                SillyTavernPromptItem(identifier = "chatHistory", marker = true),
                SillyTavernPromptItem(identifier = "jailbreak", content = "Jailbreak Prompt"),
            ),
            orderedPromptIds = listOf("main", "charDescription", "chatHistory", "jailbreak"),
        )

        val result = transformSillyTavernPrompt(
            messages = listOf(
                UIMessage.system("Base System"),
                UIMessage.user("Hello"),
            ),
            assistant = Assistant(
                stPromptTemplate = template,
                stCharacterData = SillyTavernCharacterData(description = "Character Description"),
            ),
            lorebooks = emptyList(),
            template = template,
        )

        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.USER),
            result.map { it.role }
        )
        assertEquals(
            "Base System\nMain Prompt\nCharacter Description\nJailbreak Prompt",
            result.first().toText()
        )
    }

    @Test
    fun `useSystemPrompt false should exclude assistant system prompt but keep remaining prelude`() {
        val template = SillyTavernPromptTemplate(
            useSystemPrompt = false,
            prompts = listOf(
                SillyTavernPromptItem(identifier = "main", content = "ST Main"),
                SillyTavernPromptItem(identifier = "chatHistory", marker = true),
            ),
            orderedPromptIds = listOf("main", "chatHistory"),
        )

        val result = transformSillyTavernPrompt(
            messages = listOf(
                UIMessage.system("Assistant System\nRuntime Tool Prompt"),
                UIMessage.user("Hello"),
            ),
            assistant = Assistant(
                systemPrompt = "Assistant System",
                stPromptTemplate = template,
            ),
            lorebooks = emptyList(),
            template = template,
        )

        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.USER),
            result.map { it.role }
        )
        assertFalse(result.first().toText().contains("Assistant System"))
        assertEquals("Runtime Tool Prompt\nST Main", result.first().toText())
    }

    @Test
    fun `st lorebook matching should respect scanDepth`() {
        val lorebook = Lorebook(
            id = Uuid.random(),
            entries = listOf(
                PromptInjection.RegexInjection(
                    id = Uuid.random(),
                    keywords = listOf("ancient keyword"),
                    scanDepth = 1,
                    position = InjectionPosition.TOP_OF_CHAT,
                    content = "Triggered lore",
                )
            )
        )
        val template = SillyTavernPromptTemplate(
            prompts = listOf(
                SillyTavernPromptItem(identifier = "chatHistory", marker = true),
            ),
            orderedPromptIds = listOf("chatHistory"),
        )

        val result = transformSillyTavernPrompt(
            messages = listOf(
                UIMessage.user("Ancient keyword appeared earlier"),
                UIMessage.assistant("Old reply"),
                UIMessage.user("Latest turn without match"),
            ),
            assistant = Assistant(
                stPromptTemplate = template,
                lorebookIds = setOf(lorebook.id),
            ),
            lorebooks = listOf(lorebook),
            template = template,
        )

        assertEquals(
            listOf(
                "Ancient keyword appeared earlier",
                "Old reply",
                "Latest turn without match",
            ),
            result.map { it.toText() }
        )
        assertTrue(result.none { it.toText() == "Triggered lore" })
    }

    @Test
    fun `st lorebook should support recursive scanning`() {
        val lorebook = Lorebook(
            id = Uuid.random(),
            recursiveScanning = true,
            entries = listOf(
                PromptInjection.RegexInjection(
                    id = Uuid.random(),
                    keywords = listOf("alpha"),
                    position = InjectionPosition.BEFORE_SYSTEM_PROMPT,
                    content = "beta breadcrumb",
                ),
                PromptInjection.RegexInjection(
                    id = Uuid.random(),
                    keywords = listOf("beta"),
                    position = InjectionPosition.AFTER_SYSTEM_PROMPT,
                    content = "Recursive lore",
                ),
            )
        )
        val template = SillyTavernPromptTemplate(
            prompts = listOf(
                SillyTavernPromptItem(identifier = "worldInfoBefore", marker = true),
                SillyTavernPromptItem(identifier = "worldInfoAfter", marker = true),
                SillyTavernPromptItem(identifier = "chatHistory", marker = true),
            ),
            orderedPromptIds = listOf("worldInfoBefore", "worldInfoAfter", "chatHistory"),
        )

        val result = transformSillyTavernPrompt(
            messages = listOf(UIMessage.user("alpha trigger")),
            assistant = Assistant(
                stPromptTemplate = template,
                lorebookIds = setOf(lorebook.id),
            ),
            lorebooks = listOf(lorebook),
            template = template,
        )

        assertTrue(result.first().toText().contains("beta breadcrumb"))
        assertTrue(result.first().toText().contains("Recursive lore"))
    }

    @Test
    fun `persona description prompt should render effective global persona`() {
        val template = SillyTavernPromptTemplate(
            prompts = listOf(
                SillyTavernPromptItem(identifier = "personaDescription", marker = true),
                SillyTavernPromptItem(identifier = "chatHistory", marker = true),
            ),
            orderedPromptIds = listOf("personaDescription", "chatHistory"),
        )

        val result = transformSillyTavernPrompt(
            messages = listOf(UIMessage.user("Hello")),
            assistant = Assistant(
                userPersona = "Legacy assistant persona",
                stPromptTemplate = template,
            ),
            lorebooks = emptyList(),
            template = template,
            personaDescription = "I speak like an archivist and keep meticulous notes.",
        )

        assertEquals(
            listOf("I speak like an archivist and keep meticulous notes.", "Hello"),
            result.map { it.toText() }
        )
    }

    @Test
    fun `st lorebook should support global persona description matching`() {
        val lorebook = Lorebook(
            id = Uuid.random(),
            entries = listOf(
                PromptInjection.RegexInjection(
                    id = Uuid.random(),
                    keywords = listOf("archivist"),
                    matchPersonaDescription = true,
                    position = InjectionPosition.BEFORE_SYSTEM_PROMPT,
                    content = "Persona lore",
                )
            )
        )
        val template = SillyTavernPromptTemplate(
            prompts = listOf(
                SillyTavernPromptItem(identifier = "worldInfoBefore", marker = true),
                SillyTavernPromptItem(identifier = "chatHistory", marker = true),
            ),
            orderedPromptIds = listOf("worldInfoBefore", "chatHistory"),
        )

        val result = transformSillyTavernPrompt(
            messages = listOf(UIMessage.user("Hello")),
            assistant = Assistant(
                userPersona = "Legacy assistant persona",
                stPromptTemplate = template,
                lorebookIds = setOf(lorebook.id),
            ),
            lorebooks = listOf(lorebook),
            template = template,
            personaDescription = "I am an archivist who documents everything.",
        )

        assertEquals(
            listOf("Persona lore", "Hello"),
            result.map { it.toText() }
        )
    }

    @Test
    fun `prompt order enablement should override prompt enabled and respect generation triggers`() {
        val template = SillyTavernPromptTemplate(
            prompts = listOf(
                SillyTavernPromptItem(
                    identifier = "main",
                    content = "Main Prompt",
                    enabled = false,
                ),
                SillyTavernPromptItem(
                    identifier = "continueOnly",
                    content = "Continue Prompt",
                    injectionTriggers = listOf("continue"),
                ),
                SillyTavernPromptItem(identifier = "chatHistory", marker = true),
            ),
        ).withPromptOrder(
            listOf(
                SillyTavernPromptOrderItem("main", enabled = true),
                SillyTavernPromptOrderItem("continueOnly", enabled = true),
                SillyTavernPromptOrderItem("chatHistory", enabled = true),
            )
        )

        val normalResult = transformSillyTavernPrompt(
            messages = listOf(UIMessage.user("Hello")),
            assistant = Assistant(stPromptTemplate = template),
            lorebooks = emptyList(),
            template = template,
        )
        val continueResult = transformSillyTavernPrompt(
            messages = listOf(UIMessage.user("Hello")),
            assistant = Assistant(stPromptTemplate = template),
            lorebooks = emptyList(),
            template = template,
            generationType = "continue",
        )

        assertEquals(
            listOf("Main Prompt", "Hello"),
            normalResult.map { it.toText() }
        )
        assertEquals(
            listOf("Main Prompt\nContinue Prompt", "Hello"),
            continueResult.map { it.toText() }
        )
    }

    @Test
    fun `continue generation should append control prompts instead of send_if_empty`() {
        val template = SillyTavernPromptTemplate(
            continueNudgePrompt = "Continue: {{lastChatMessage}}",
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
            generationType = "continue",
        )

        assertEquals(
            listOf("U1", "A1", "Continue: A1"),
            result.map { it.toText() }
        )
        assertEquals(
            listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.SYSTEM),
            result.map { it.role }
        )
    }

    @Test
    fun `continue prefill should seed assistant control message`() {
        val template = SillyTavernPromptTemplate(
            assistantPrefill = "Prefill",
            continuePrefill = true,
            continuePostfix = " ",
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
            generationType = "continue",
        )

        assertEquals(
            listOf("U1", "Prefill\n\nA1 "),
            result.map { it.toText() }
        )
        assertEquals(
            listOf(MessageRole.USER, MessageRole.ASSISTANT),
            result.map { it.role }
        )
    }

    @Test
    fun `impersonate generation should append runtime control prompts`() {
        val template = SillyTavernPromptTemplate(
            impersonationPrompt = "Act as {{user}}",
            assistantImpersonation = "I ",
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
            generationType = "impersonate",
        )

        assertEquals(
            listOf("U1", "A1", "Act as {{user}}", "I "),
            result.map { it.toText() }
        )
        assertEquals(
            listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.SYSTEM, MessageRole.ASSISTANT),
            result.map { it.role }
        )
    }

    @Test
    fun `names behavior content should prefix chat history speakers`() {
        val template = SillyTavernPromptTemplate(
            namesBehavior = 2,
            prompts = listOf(
                SillyTavernPromptItem(identifier = "chatHistory", marker = true),
            ),
            orderedPromptIds = listOf("chatHistory"),
        )

        val result = transformSillyTavernPrompt(
            messages = listOf(
                UIMessage.user("Hello"),
                UIMessage.assistant("Hi"),
            ),
            assistant = Assistant(stPromptTemplate = template),
            lorebooks = emptyList(),
            template = template,
        )

        assertEquals(
            listOf("{{user}}: Hello", "{{char}}: Hi"),
            result.map { it.toText() }
        )
    }
}
