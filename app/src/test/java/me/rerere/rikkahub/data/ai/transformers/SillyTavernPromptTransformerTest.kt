package me.rerere.rikkahub.data.ai.transformers

import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.Model
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.AssistantRegexPlacement
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
        val template = SillyTavernPromptTemplate(
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
        )
        val assistant = Assistant(
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
            template = template,
        )

        assertEquals(
            listOf(
                "Base System",
                "<wi>Lore Before</wi>",
                "Main Prompt",
                "<wi>Lore After</wi>",
                "Character Description",
                "Card Jailbreak",
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
            assistant = Assistant(),
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
    fun `prompt entries with literal regex tags should remain in rendered prompt`() {
        val template = SillyTavernPromptTemplate(
            prompts = listOf(
                SillyTavernPromptItem(
                    identifier = "main",
                    role = MessageRole.SYSTEM,
                    content = "<regex order=3>\"/Human: /gs\":\"User: \"</regex>",
                ),
                SillyTavernPromptItem(identifier = "chatHistory", marker = true),
            ),
            orderedPromptIds = listOf("main", "chatHistory"),
        )

        val result = transformSillyTavernPrompt(
            messages = listOf(UIMessage.user("Hello")),
            assistant = Assistant(),
            lorebooks = emptyList(),
            template = template,
        )

        assertEquals(
            listOf("<regex order=3>\"/Human: /gs\":\"User: \"</regex>", "Hello"),
            result.map { it.toText() }
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
            assistant = Assistant(),
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
    fun `author note lorebook entries should use their configured inject depth`() {
        val lorebook = Lorebook(
            id = Uuid.random(),
            entries = listOf(
                PromptInjection.RegexInjection(
                    id = Uuid.random(),
                    constantActive = true,
                    position = InjectionPosition.AUTHOR_NOTE_TOP,
                    injectDepth = 1,
                    content = "Author Note",
                ),
            )
        )
        val template = SillyTavernPromptTemplate(
            prompts = listOf(SillyTavernPromptItem(identifier = "chatHistory", marker = true)),
            orderedPromptIds = listOf("chatHistory"),
        )
        val assistant = Assistant(lorebookIds = setOf(lorebook.id))

        val result = transformSillyTavernPrompt(
            messages = listOf(
                UIMessage.user("U1"),
                UIMessage.assistant("A1"),
                UIMessage.user("U2"),
            ),
            assistant = assistant,
            lorebooks = listOf(lorebook),
            template = template,
        )

        assertEquals(
            listOf("U1", "A1", "Author Note", "U2"),
            result.map { it.toText() }
        )
    }

    @Test
    fun `leading system prompt sections should stay separate until squash is enabled`() {
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
                stCharacterData = SillyTavernCharacterData(description = "Character Description"),
            ),
            lorebooks = emptyList(),
            template = template,
        )

        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.SYSTEM, MessageRole.SYSTEM, MessageRole.SYSTEM, MessageRole.USER),
            result.map { it.role }
        )
        assertEquals(
            listOf(
                "Base System",
                "Main Prompt",
                "Character Description",
                "Jailbreak Prompt",
                "Hello",
            ),
            result.map { it.toText() }
        )
    }

    @Test
    fun `squashSystemMessages should merge separated prompt sections only when enabled`() = runBlocking {
        val template = SillyTavernPromptTemplate(
            useSystemPrompt = true,
            squashSystemMessages = true,
            prompts = listOf(
                SillyTavernPromptItem(identifier = "main", content = "Main Prompt"),
                SillyTavernPromptItem(identifier = "charDescription", marker = true),
                SillyTavernPromptItem(identifier = "chatHistory", marker = true),
                SillyTavernPromptItem(identifier = "jailbreak", content = "Jailbreak Prompt"),
            ),
            orderedPromptIds = listOf("main", "charDescription", "chatHistory", "jailbreak"),
        )

        val result = listOf(
            UIMessage.system("Base System"),
            UIMessage.user("Hello"),
        ).transforms(
            transformers = listOf(SillyTavernPromptTransformer, SillyTavernMacroTransformer),
            context = ContextWrapper(null),
            model = Model(),
            assistant = Assistant(
                stCharacterData = SillyTavernCharacterData(description = "Character Description"),
            ),
            settings = Settings(
                stPresetEnabled = true,
                stPresetTemplate = template,
            ),
        )

        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.USER),
            result.map { it.role }
        )
        assertEquals(
            listOf(
                "Base System\nMain Prompt\nCharacter Description\nJailbreak Prompt",
                "Hello",
            ),
            result.map { it.toText() }
        )
    }

    @Test
    fun `useSystemPrompt false should keep all leading system content before prompt text without collapsing it`() {
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
                UIMessage.system("Assistant System"),
                UIMessage.system("Runtime Tool Prompt"),
                UIMessage.user("Hello"),
            ),
            assistant = Assistant(
                systemPrompt = "Assistant System",
            ),
            lorebooks = emptyList(),
            template = template,
        )

        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.SYSTEM, MessageRole.SYSTEM, MessageRole.USER),
            result.map { it.role }
        )
        assertEquals(
            listOf(
                "Assistant System",
                "Runtime Tool Prompt",
                "ST Main",
                "Hello",
            ),
            result.map { it.toText() }
        )
    }

    @Test
    fun `transforms should demote system messages added after ST transformer when useSystemPrompt is off`() = runBlocking {
        val template = SillyTavernPromptTemplate(
            useSystemPrompt = false,
            prompts = listOf(
                SillyTavernPromptItem(identifier = "chatHistory", marker = true),
            ),
            orderedPromptIds = listOf("chatHistory"),
        )
        val lateSystemTransformer = object : InputMessageTransformer {
            override suspend fun transform(
                ctx: TransformerContext,
                messages: List<UIMessage>,
            ): List<UIMessage> {
                return messages + UIMessage.system("Late System")
            }
        }

        val result = listOf(UIMessage.user("Hello")).transforms(
            transformers = listOf(SillyTavernPromptTransformer, lateSystemTransformer),
            context = ContextWrapper(null),
            model = Model(),
            assistant = Assistant(),
            settings = Settings(
                stPresetEnabled = true,
                stPresetTemplate = template,
            ),
        )

        assertEquals(
            listOf(MessageRole.USER, MessageRole.USER),
            result.map { it.role }
        )
        assertEquals(
            listOf("Hello", "Late System"),
            result.map { it.toText() }
        )
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
                lorebookIds = setOf(lorebook.id),
            ),
            lorebooks = listOf(lorebook),
            template = template,
        )

        assertEquals("beta breadcrumb", result[0].toText())
        assertEquals("Recursive lore", result[1].toText())
    }

    @Test
    fun `st lorebook author note anchors should inject as a dedicated depth note`() {
        val lorebook = Lorebook(
            id = Uuid.random(),
            entries = listOf(
                PromptInjection.RegexInjection(
                    id = Uuid.random(),
                    constantActive = true,
                    position = InjectionPosition.AUTHOR_NOTE_TOP,
                    content = "AN top",
                ),
                PromptInjection.RegexInjection(
                    id = Uuid.random(),
                    constantActive = true,
                    position = InjectionPosition.AUTHOR_NOTE_BOTTOM,
                    content = "AN bottom",
                ),
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
                UIMessage.user("U1"),
                UIMessage.assistant("A1"),
                UIMessage.user("U2"),
                UIMessage.assistant("A2"),
                UIMessage.user("U3"),
            ),
            assistant = Assistant(
                lorebookIds = setOf(lorebook.id),
            ),
            lorebooks = listOf(lorebook),
            template = template,
        )

        assertEquals(
            listOf("U1", "AN top\nAN bottom", "A1", "U2", "A2", "U3"),
            result.map { it.toText() }
        )
        assertEquals(
            listOf(
                MessageRole.USER,
                MessageRole.SYSTEM,
                MessageRole.ASSISTANT,
                MessageRole.USER,
                MessageRole.ASSISTANT,
                MessageRole.USER,
            ),
            result.map { it.role }
        )
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
    fun `world info regex placement should transform lorebook content in prompt phase`() {
        val lorebook = Lorebook(
            id = Uuid.random(),
            entries = listOf(
                PromptInjection.RegexInjection(
                    id = Uuid.random(),
                    constantActive = true,
                    position = InjectionPosition.BEFORE_SYSTEM_PROMPT,
                    content = "hero archive",
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
        val settings = Settings(
            regexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    enabled = true,
                    findRegex = "hero",
                    replaceString = "legend",
                    affectingScope = setOf(AssistantAffectScope.SYSTEM),
                    promptOnly = true,
                    stPlacements = setOf(AssistantRegexPlacement.WORLD_INFO),
                )
            )
        )

        val result = transformSillyTavernPrompt(
            messages = listOf(UIMessage.user("Hello")),
            assistant = Assistant(
                lorebookIds = setOf(lorebook.id),
            ),
            settings = settings,
            lorebooks = listOf(lorebook),
            template = template,
        )

        assertEquals(
            listOf("legend archive", "Hello"),
            result.map { it.toText() }
        )
    }

    @Test
    fun `st lorebook example message anchors should wrap dialogue examples`() {
        val lorebook = Lorebook(
            id = Uuid.random(),
            entries = listOf(
                PromptInjection.RegexInjection(
                    id = Uuid.random(),
                    constantActive = true,
                    position = InjectionPosition.EXAMPLE_MESSAGES_TOP,
                    content = "User: Lore Before\nAssistant: Lore Before Reply",
                ),
                PromptInjection.RegexInjection(
                    id = Uuid.random(),
                    constantActive = true,
                    position = InjectionPosition.EXAMPLE_MESSAGES_BOTTOM,
                    content = "User: Lore After\nAssistant: Lore After Reply",
                ),
            )
        )
        val template = SillyTavernPromptTemplate(
            prompts = listOf(
                SillyTavernPromptItem(identifier = "dialogueExamples", marker = true),
                SillyTavernPromptItem(identifier = "chatHistory", marker = true),
            ),
            orderedPromptIds = listOf("dialogueExamples", "chatHistory"),
        )

        val result = transformSillyTavernPrompt(
            messages = listOf(UIMessage.user("Hello")),
            assistant = Assistant(
                lorebookIds = setOf(lorebook.id),
                stCharacterData = SillyTavernCharacterData(
                    exampleMessagesRaw = "User: Base Example\nAssistant: Base Reply",
                ),
            ),
            lorebooks = listOf(lorebook),
            template = template,
        )

        assertEquals(
            listOf(
                "Hello",
                "User: Lore Before",
                "Assistant: Lore Before Reply",
                "User: Base Example",
                "Assistant: Base Reply",
                "User: Lore After",
                "Assistant: Lore After Reply",
            ),
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
                    systemPrompt = false,
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
            assistant = Assistant(),
            lorebooks = emptyList(),
            template = template,
        )
        val continueResult = transformSillyTavernPrompt(
            messages = listOf(UIMessage.user("Hello")),
            assistant = Assistant(),
            lorebooks = emptyList(),
            template = template,
            generationType = "continue",
        )

        assertEquals(
            listOf("Main Prompt", "Hello"),
            normalResult.map { it.toText() }
        )
        assertEquals(
            listOf("Main Prompt", "Continue Prompt", "Hello"),
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
            assistant = Assistant(),
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
            assistant = Assistant(),
            lorebooks = emptyList(),
            template = template,
            generationType = "continue",
            supportsAssistantPrefill = true,
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
            assistant = Assistant(),
            lorebooks = emptyList(),
            template = template,
            generationType = "impersonate",
            supportsAssistantPrefill = true,
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
    fun `continue prefill should not prepend assistant prefill on non claude providers`() {
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
            assistant = Assistant(),
            lorebooks = emptyList(),
            template = template,
            generationType = "continue",
            supportsAssistantPrefill = false,
        )

        assertEquals(
            listOf("U1", "A1 "),
            result.map { it.toText() }
        )
        assertEquals(
            listOf(MessageRole.USER, MessageRole.ASSISTANT),
            result.map { it.role }
        )
    }

    @Test
    fun `impersonate generation should not append assistant seed on non claude providers`() {
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
            assistant = Assistant(),
            lorebooks = emptyList(),
            template = template,
            generationType = "impersonate",
            supportsAssistantPrefill = false,
        )

        assertEquals(
            listOf("U1", "A1", "Act as {{user}}"),
            result.map { it.toText() }
        )
        assertEquals(
            listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.SYSTEM),
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
            assistant = Assistant(),
            lorebooks = emptyList(),
            template = template,
        )

        assertEquals(
            listOf("{{user}}: Hello", "{{char}}: Hi"),
            result.map { it.toText() }
        )
    }

    @Test
    fun `names behavior 1 should fall back to content prefixing`() {
        val template = SillyTavernPromptTemplate(
            namesBehavior = 1,
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
            assistant = Assistant(),
            lorebooks = emptyList(),
            template = template,
        )

        assertEquals(
            listOf("{{user}}: Hello", "{{char}}: Hi"),
            result.map { it.toText() }
        )
    }

    @Test
    fun `outlet lorebook entries should populate macro state without injecting prompt text`() {
        val lorebook = Lorebook(
            id = Uuid.random(),
            entries = listOf(
                PromptInjection.RegexInjection(
                    id = Uuid.random(),
                    constantActive = true,
                    position = InjectionPosition.OUTLET,
                    content = "Stored memory",
                    stMetadata = mapOf("outlet_name" to "memory"),
                )
            )
        )
        val stMacroState = StMacroState()
        val template = SillyTavernPromptTemplate(
            prompts = listOf(
                SillyTavernPromptItem(identifier = "chatHistory", marker = true),
            ),
            orderedPromptIds = listOf("chatHistory"),
        )

        val result = transformSillyTavernPrompt(
            messages = listOf(UIMessage.user("Hello")),
            assistant = Assistant(
                lorebookIds = setOf(lorebook.id),
            ),
            lorebooks = listOf(lorebook),
            template = template,
            stMacroState = stMacroState,
        )

        assertEquals(listOf("Hello"), result.map { it.toText() })
        assertEquals("Stored memory", stMacroState.outlets["memory"])
    }
}
