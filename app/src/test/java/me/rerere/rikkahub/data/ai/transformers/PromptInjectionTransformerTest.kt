package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.LorebookGlobalSettings
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.SillyTavernCharacterData
import me.rerere.rikkahub.data.model.StDepthPrompt
import me.rerere.rikkahub.data.model.WorldInfoCharacterStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class PromptInjectionTransformerTest {

    // region Helper functions
    private fun createAssistant(
        id: Uuid = Uuid.random(),
        name: String = "",
        userPersona: String = "",
        modeInjectionIds: Set<Uuid> = emptySet(),
        lorebookIds: Set<Uuid> = emptySet(),
        stCharacterData: SillyTavernCharacterData? = null,
    ) = Assistant(
        id = id,
        name = name,
        userPersona = userPersona,
        modeInjectionIds = modeInjectionIds,
        lorebookIds = lorebookIds,
        stCharacterData = stCharacterData,
    )

    private fun createModeInjection(
        id: Uuid = Uuid.random(),
        name: String = "Test Injection",
        enabled: Boolean = true,
        priority: Int = 0,
        position: InjectionPosition = InjectionPosition.AFTER_SYSTEM_PROMPT,
        content: String = "Injected content",
        injectDepth: Int = 4,
        role: MessageRole = MessageRole.SYSTEM
    ) = PromptInjection.ModeInjection(
        id = id,
        name = name,
        enabled = enabled,
        priority = priority,
        position = position,
        content = content,
        injectDepth = injectDepth,
        role = role
    )

    private fun createRegexInjection(
        id: Uuid = Uuid.random(),
        name: String = "Test Regex",
        enabled: Boolean = true,
        priority: Int = 0,
        position: InjectionPosition = InjectionPosition.AFTER_SYSTEM_PROMPT,
        content: String = "Regex injected content",
        injectDepth: Int = 4,
        role: MessageRole = MessageRole.USER,
        keywords: List<String> = listOf("trigger"),
        useRegex: Boolean = false,
        caseSensitive: Boolean = false,
        scanDepth: Int = 5,
        constantActive: Boolean = false,
        matchPersonaDescription: Boolean = false,
        matchCharacterDescription: Boolean = false,
        matchCharacterPersonality: Boolean = false,
        matchScenario: Boolean = false,
        matchCreatorNotes: Boolean = false,
        matchCharacterDepthPrompt: Boolean = false,
    ) = PromptInjection.RegexInjection(
        id = id,
        name = name,
        enabled = enabled,
        priority = priority,
        position = position,
        content = content,
        injectDepth = injectDepth,
        role = role,
        keywords = keywords,
        useRegex = useRegex,
        caseSensitive = caseSensitive,
        scanDepth = scanDepth,
        constantActive = constantActive,
        matchPersonaDescription = matchPersonaDescription,
        matchCharacterDescription = matchCharacterDescription,
        matchCharacterPersonality = matchCharacterPersonality,
        matchScenario = matchScenario,
        matchCreatorNotes = matchCreatorNotes,
        matchCharacterDepthPrompt = matchCharacterDepthPrompt,
    )

    private fun createLorebook(
        id: Uuid = Uuid.random(),
        name: String = "Test Lorebook",
        enabled: Boolean = true,
        recursiveScanning: Boolean = false,
        tokenBudget: Int? = null,
        entries: List<PromptInjection.RegexInjection> = emptyList()
    ) = Lorebook(
        id = id,
        name = name,
        enabled = enabled,
        recursiveScanning = recursiveScanning,
        tokenBudget = tokenBudget,
        entries = entries
    )

    private fun transformWithAlwaysActiveLorebookEntry(
        entry: PromptInjection.RegexInjection,
        messages: List<UIMessage>,
        lorebookId: Uuid = Uuid.random(),
    ): List<UIMessage> {
        val lorebook = createLorebook(
            id = lorebookId,
            entries = listOf(
                entry.copy(
                    keywords = emptyList(),
                    constantActive = true,
                )
            )
        )
        return transformMessages(
            messages = messages,
            assistant = createAssistant(lorebookIds = setOf(lorebookId)),
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook),
        )
    }

    private fun getMessageText(message: UIMessage): String {
        return message.parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("") { it.text }
    }

    private fun createAssistantWithUnexecutedTool(toolCallId: String, toolName: String): UIMessage {
        return UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = toolCallId,
                    toolName = toolName,
                    input = "{}",
                    output = emptyList()
                )
            )
        )
    }

    private fun createAssistantWithExecutedTool(toolCallId: String, toolName: String): UIMessage {
        return UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = toolCallId,
                    toolName = toolName,
                    input = "{}",
                    output = listOf(UIMessagePart.Text("result"))
                )
            )
        )
    }

    private fun createLegacyToolMessage(content: String = "tool result"): UIMessage {
        return UIMessage(
            role = MessageRole.TOOL,
            parts = listOf(UIMessagePart.Text(content))
        )
    }
    // endregion

    // region No injection tests
    @Test
    fun `no injections should return original messages`() {
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello"),
            UIMessage.assistant("Hi there!")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(),
            modeInjections = emptyList(),
            lorebooks = emptyList()
        )

        assertEquals(messages, result)
    }

    @Test
    fun `disabled mode injection should not be applied`() {
        val injectionId = Uuid.random()
        val injection = createModeInjection(
            id = injectionId,
            enabled = false,
            content = "Should not appear"
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(injectionId)),
            modeInjections = listOf(injection),
            lorebooks = emptyList()
        )

        assertEquals(messages, result)
    }

    @Test
    fun `unlinked mode injection should not be applied`() {
        val injection = createModeInjection(content = "Should not appear")

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(), // No linked injections
            modeInjections = listOf(injection),
            lorebooks = emptyList()
        )

        assertEquals(messages, result)
    }

    @Test
    fun `lorebook should support effective global persona description matching`() {
        val lorebookId = Uuid.random()
        val lorebook = createLorebook(
            id = lorebookId,
            entries = listOf(
                createRegexInjection(
                    keywords = listOf("archivist"),
                    matchPersonaDescription = true,
                )
            )
        )

        val injections = collectInjections(
            messages = listOf(UIMessage.user("Hello")),
            assistant = createAssistant(lorebookIds = setOf(lorebookId)).copy(userPersona = "Legacy assistant persona"),
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook),
            personaDescription = "I am an archivist who documents everything.",
            stPromptTemplateActive = false,
        )

        assertEquals(1, injections.size)
        assertEquals("Regex injected content", injections.single().content)
    }
    // endregion

    // region AFTER_SYSTEM_PROMPT tests
    @Test
    fun `mode injection with AFTER_SYSTEM_PROMPT should append to system message`() {
        val injectionId = Uuid.random()
        val injection = createModeInjection(
            id = injectionId,
            position = InjectionPosition.AFTER_SYSTEM_PROMPT,
            content = "Appended content"
        )

        val messages = listOf(
            UIMessage.system("Original system prompt"),
            UIMessage.user("Hello")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(injectionId)),
            modeInjections = listOf(injection),
            lorebooks = emptyList()
        )

        assertEquals(2, result.size)
        val systemText = getMessageText(result[0])
        assertTrue(systemText.startsWith("Original system prompt"))
        assertTrue(systemText.endsWith("Appended content"))
    }
    // endregion

    // region BEFORE_SYSTEM_PROMPT tests
    @Test
    fun `mode injection with BEFORE_SYSTEM_PROMPT should prepend to system message`() {
        val injectionId = Uuid.random()
        val injection = createModeInjection(
            id = injectionId,
            position = InjectionPosition.BEFORE_SYSTEM_PROMPT,
            content = "Prepended content"
        )

        val messages = listOf(
            UIMessage.system("Original system prompt"),
            UIMessage.user("Hello")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(injectionId)),
            modeInjections = listOf(injection),
            lorebooks = emptyList()
        )

        assertEquals(2, result.size)
        val systemText = getMessageText(result[0])
        assertTrue(systemText.startsWith("Prepended content"))
        assertTrue(systemText.contains("Original system prompt"))
    }

    @Test
    fun `injection without existing system message should create new system message`() {
        val injectionId = Uuid.random()
        val injection = createModeInjection(
            id = injectionId,
            position = InjectionPosition.AFTER_SYSTEM_PROMPT,
            content = "New system content"
        )

        val messages = listOf(
            UIMessage.user("Hello"),
            UIMessage.assistant("Hi!")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(injectionId)),
            modeInjections = listOf(injection),
            lorebooks = emptyList()
        )

        assertEquals(3, result.size)
        assertEquals(MessageRole.SYSTEM, result[0].role)
        assertEquals("New system content", getMessageText(result[0]))
    }
    // endregion

    // region Mode injection normalization tests
    @Test
    fun `mode injection should normalize non system positions to AFTER_SYSTEM_PROMPT`() {
        val injectionId = Uuid.random()
        val injection = createModeInjection(
            id = injectionId,
            position = InjectionPosition.TOP_OF_CHAT,
            content = "Top of chat content"
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello"),
            UIMessage.assistant("Hi!")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(injectionId)),
            modeInjections = listOf(injection),
            lorebooks = emptyList()
        )

        assertEquals(3, result.size)
        assertEquals(MessageRole.SYSTEM, result[0].role)
        val systemText = getMessageText(result[0])
        assertTrue(systemText.startsWith("System prompt"))
        assertTrue(systemText.endsWith("Top of chat content"))
    }

    @Test
    fun `mode injection should always use SYSTEM role`() {
        val injectionId = Uuid.random()
        val injection = createModeInjection(
            id = injectionId,
            role = MessageRole.ASSISTANT,
            content = "Forced system content"
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(injectionId)),
            modeInjections = listOf(injection),
            lorebooks = emptyList()
        )

        assertEquals(2, result.size)
        assertEquals(MessageRole.SYSTEM, result[0].role)
        assertTrue(getMessageText(result[0]).contains("Forced system content"))
    }
    // endregion

    // region Lorebook position tests
    @Test
    fun `lorebook entry with TOP_OF_CHAT should insert before first user message`() {
        val injection = createRegexInjection(
            position = InjectionPosition.TOP_OF_CHAT,
            content = "Top of chat content"
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello"),
            UIMessage.assistant("Hi!")
        )

        val result = transformWithAlwaysActiveLorebookEntry(injection, messages)

        assertEquals(4, result.size)
        assertEquals(MessageRole.SYSTEM, result[0].role)
        assertEquals("System prompt", getMessageText(result[0]))
        assertEquals(MessageRole.USER, result[1].role)
        assertEquals("Top of chat content", getMessageText(result[1]))
        assertEquals(MessageRole.USER, result[2].role)
    }

    @Test
    fun `AUTHOR_NOTE_TOP lorebook entry should create a dedicated note in generic mode`() {
        val injection = createRegexInjection(
            position = InjectionPosition.AUTHOR_NOTE_TOP,
            content = "Author note top content",
            role = MessageRole.SYSTEM,
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Message 1"),
            UIMessage.assistant("Response 1"),
            UIMessage.user("Message 2"),
            UIMessage.assistant("Response 2"),
            UIMessage.user("Message 3"),
        )

        val result = transformWithAlwaysActiveLorebookEntry(injection, messages)

        assertEquals(7, result.size)
        assertEquals("Message 1", getMessageText(result[1]))
        assertEquals("Author note top content", getMessageText(result[2]))
        assertEquals(MessageRole.SYSTEM, result[2].role)
        assertEquals("Response 1", getMessageText(result[3]))
    }

    @Test
    fun `lorebook entry with BOTTOM_OF_CHAT should insert before last message`() {
        val injection = createRegexInjection(
            position = InjectionPosition.BOTTOM_OF_CHAT,
            content = "Bottom of chat content"
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello"),
            UIMessage.assistant("Hi!"),
            UIMessage.user("How are you?")
        )

        val result = transformWithAlwaysActiveLorebookEntry(injection, messages)

        assertEquals(5, result.size)
        assertEquals(MessageRole.USER, result[3].role)
        assertEquals("Bottom of chat content", getMessageText(result[3]))
        assertEquals(MessageRole.USER, result[4].role)
        assertEquals("How are you?", getMessageText(result[4]))
    }

    @Test
    fun `EXAMPLE_MESSAGES_BOTTOM lorebook entry should fallback to bottom of chat in generic mode`() {
        val injection = createRegexInjection(
            position = InjectionPosition.EXAMPLE_MESSAGES_BOTTOM,
            content = "Example bottom content",
            role = MessageRole.SYSTEM,
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello"),
            UIMessage.assistant("Hi!"),
            UIMessage.user("How are you?")
        )

        val result = transformWithAlwaysActiveLorebookEntry(injection, messages)

        assertEquals(5, result.size)
        assertEquals("Example bottom content", getMessageText(result[3]))
        assertEquals(MessageRole.SYSTEM, result[3].role)
        assertEquals("How are you?", getMessageText(result[4]))
    }

    @Test
    fun `author note top and bottom should combine into one system note in generic mode`() {
        val top = createRegexInjection(
            position = InjectionPosition.AUTHOR_NOTE_TOP,
            content = "AN top",
        )
        val bottom = createRegexInjection(
            position = InjectionPosition.AUTHOR_NOTE_BOTTOM,
            content = "AN bottom",
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Message 1"),
            UIMessage.assistant("Response 1"),
            UIMessage.user("Message 2"),
            UIMessage.assistant("Response 2"),
            UIMessage.user("Message 3"),
        )

        val result = applyInjections(
            messages = messages,
            byPosition = mapOf(
                InjectionPosition.AUTHOR_NOTE_TOP to listOf(top),
                InjectionPosition.AUTHOR_NOTE_BOTTOM to listOf(bottom),
            )
        )

        assertEquals(7, result.size)
        assertEquals("Message 1", getMessageText(result[1]))
        assertEquals("AN top\nAN bottom", getMessageText(result[2]))
        assertEquals(MessageRole.SYSTEM, result[2].role)
        assertEquals("Response 1", getMessageText(result[3]))
    }

    @Test
    fun `lorebook entry with AT_DEPTH should insert at specified depth from end`() {
        val injection = createRegexInjection(
            position = InjectionPosition.AT_DEPTH,
            injectDepth = 2,
            content = "At depth 2 content"
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Message 1"),
            UIMessage.assistant("Response 1"),
            UIMessage.user("Message 2"),
            UIMessage.assistant("Response 2")
        )

        val result = transformWithAlwaysActiveLorebookEntry(injection, messages)

        assertEquals(6, result.size)
        assertEquals(MessageRole.USER, result[3].role)
        assertEquals("At depth 2 content", getMessageText(result[3]))
        assertEquals(MessageRole.USER, result[4].role)
        assertEquals("Message 2", getMessageText(result[4]))
    }

    @Test
    fun `lorebook AT_DEPTH with depth 1 should insert before last message`() {
        val injection = createRegexInjection(
            position = InjectionPosition.AT_DEPTH,
            injectDepth = 1,
            content = "Before last"
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello"),
            UIMessage.assistant("Hi!")
        )

        val result = transformWithAlwaysActiveLorebookEntry(injection, messages)

        assertEquals(4, result.size)
        assertEquals("Before last", getMessageText(result[2]))
        assertEquals("Hi!", getMessageText(result[3]))
    }

    @Test
    fun `lorebook AT_DEPTH with depth larger than message count should insert at beginning`() {
        val injection = createRegexInjection(
            position = InjectionPosition.AT_DEPTH,
            injectDepth = 100,
            content = "Large depth content"
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello")
        )

        val result = transformWithAlwaysActiveLorebookEntry(injection, messages)

        assertEquals(3, result.size)
        assertEquals("Large depth content", getMessageText(result[0]))
    }

    @Test
    fun `multiple lorebook AT_DEPTH injections with different depths should all apply`() {
        val lorebookId = Uuid.random()
        val lorebook = createLorebook(
            id = lorebookId,
            entries = listOf(
                createRegexInjection(
                    position = InjectionPosition.AT_DEPTH,
                    injectDepth = 1,
                    content = "Depth 1",
                    keywords = emptyList(),
                    constantActive = true,
                ),
                createRegexInjection(
                    position = InjectionPosition.AT_DEPTH,
                    injectDepth = 3,
                    content = "Depth 3",
                    keywords = emptyList(),
                    constantActive = true,
                )
            )
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Message 1"),
            UIMessage.assistant("Response 1"),
            UIMessage.user("Message 2"),
            UIMessage.assistant("Response 2")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(lorebookIds = setOf(lorebookId)),
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook)
        )

        assertEquals(7, result.size)
        assertTrue(result.any { getMessageText(it).contains("Depth 1") })
        assertTrue(result.any { getMessageText(it).contains("Depth 3") })
    }

    @Test
    fun `multiple lorebook AT_DEPTH injections with same depth should be merged`() {
        val lorebookId = Uuid.random()
        val lorebook = createLorebook(
            id = lorebookId,
            entries = listOf(
                createRegexInjection(
                    position = InjectionPosition.AT_DEPTH,
                    injectDepth = 2,
                    priority = 10,
                    content = "Higher priority",
                    keywords = emptyList(),
                    constantActive = true,
                ),
                createRegexInjection(
                    position = InjectionPosition.AT_DEPTH,
                    injectDepth = 2,
                    priority = 5,
                    content = "Lower priority",
                    keywords = emptyList(),
                    constantActive = true,
                )
            )
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello"),
            UIMessage.assistant("Hi!")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(lorebookIds = setOf(lorebookId)),
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook)
        )

        assertEquals(4, result.size)
        val injectedText = getMessageText(result[1])
        assertTrue(injectedText.contains("Higher priority"))
        assertTrue(injectedText.contains("Lower priority"))
        assertTrue(injectedText.indexOf("Higher priority") < injectedText.indexOf("Lower priority"))
    }
    // endregion

    // region Priority tests
    @Test
    fun `injections should be ordered by priority descending`() {
        val id1 = Uuid.random()
        val id2 = Uuid.random()
        val id3 = Uuid.random()

        val injections = listOf(
            createModeInjection(id = id1, priority = 1, content = "Priority 1"),
            createModeInjection(id = id2, priority = 3, content = "Priority 3"),
            createModeInjection(id = id3, priority = 2, content = "Priority 2")
        )

        val messages = listOf(
            UIMessage.system("System"),
            UIMessage.user("Hello")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(id1, id2, id3)),
            modeInjections = injections,
            lorebooks = emptyList()
        )

        val systemText = getMessageText(result[0])
        // Higher priority should come first when joining
        assertTrue(systemText.contains("Priority 3"))
        assertTrue(systemText.indexOf("Priority 3") < systemText.indexOf("Priority 2"))
        assertTrue(systemText.indexOf("Priority 2") < systemText.indexOf("Priority 1"))
    }
    // endregion

    // region Lorebook tests
    @Test
    fun `lorebook with keyword match should trigger injection`() {
        val lorebookId = Uuid.random()
        val regexInjection = createRegexInjection(
            keywords = listOf("magic"),
            content = "Magic system explanation"
        )
        val lorebook = createLorebook(
            id = lorebookId,
            entries = listOf(regexInjection)
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Tell me about magic")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(lorebookIds = setOf(lorebookId)),
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook)
        )

        val systemText = getMessageText(result[0])
        assertTrue(systemText.contains("Magic system explanation"))
    }

    @Test
    fun `lorebook without keyword match should not trigger injection`() {
        val lorebookId = Uuid.random()
        val regexInjection = createRegexInjection(
            keywords = listOf("magic"),
            content = "Should not appear"
        )
        val lorebook = createLorebook(
            id = lorebookId,
            entries = listOf(regexInjection)
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Tell me about science")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(lorebookIds = setOf(lorebookId)),
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook)
        )

        assertEquals(2, result.size)
        val systemText = getMessageText(result[0])
        assertEquals("System prompt", systemText)
    }

    @Test
    fun `lorebook with constantActive should always trigger`() {
        val lorebookId = Uuid.random()
        val regexInjection = createRegexInjection(
            keywords = emptyList(),
            constantActive = true,
            content = "Always active content"
        )
        val lorebook = createLorebook(
            id = lorebookId,
            entries = listOf(regexInjection)
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Any message")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(lorebookIds = setOf(lorebookId)),
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook)
        )

        val systemText = getMessageText(result[0])
        assertTrue(systemText.contains("Always active content"))
    }

    @Test
    fun `lorebook with case insensitive match should trigger`() {
        val lorebookId = Uuid.random()
        val regexInjection = createRegexInjection(
            keywords = listOf("MAGIC"),
            caseSensitive = false,
            content = "Case insensitive match"
        )
        val lorebook = createLorebook(
            id = lorebookId,
            entries = listOf(regexInjection)
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("tell me about magic")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(lorebookIds = setOf(lorebookId)),
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook)
        )

        val systemText = getMessageText(result[0])
        assertTrue(systemText.contains("Case insensitive match"))
    }

    @Test
    fun `lorebook with case sensitive match should not trigger on different case`() {
        val lorebookId = Uuid.random()
        val regexInjection = createRegexInjection(
            keywords = listOf("MAGIC"),
            caseSensitive = true,
            content = "Should not appear"
        )
        val lorebook = createLorebook(
            id = lorebookId,
            entries = listOf(regexInjection)
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("tell me about magic")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(lorebookIds = setOf(lorebookId)),
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook)
        )

        assertEquals(2, result.size)
        val systemText = getMessageText(result[0])
        assertEquals("System prompt", systemText)
    }

    @Test
    fun `lorebook with regex pattern should match`() {
        val lorebookId = Uuid.random()
        val regexInjection = createRegexInjection(
            keywords = listOf("mag.*spell"),
            useRegex = true,
            content = "Regex match content"
        )
        val lorebook = createLorebook(
            id = lorebookId,
            entries = listOf(regexInjection)
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Can you explain magic and spell casting?")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(lorebookIds = setOf(lorebookId)),
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook)
        )

        val systemText = getMessageText(result[0])
        assertTrue(systemText.contains("Regex match content"))
    }

    @Test
    fun `scanDepth should limit message scanning range`() {
        val lorebookId = Uuid.random()
        val regexInjection = createRegexInjection(
            keywords = listOf("old keyword"),
            scanDepth = 2, // 只扫描最近2条消息
            content = "Should not appear"
        )
        val lorebook = createLorebook(
            id = lorebookId,
            entries = listOf(regexInjection)
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Message with old keyword"), // 第1条用户消息（超出扫描范围）
            UIMessage.assistant("Response 1"),
            UIMessage.user("Message 2"),
            UIMessage.assistant("Response 2"),
            UIMessage.user("Latest message") // 最近的消息，不包含关键词
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(lorebookIds = setOf(lorebookId)),
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook)
        )

        // 关键词在第1条用户消息中，但 scanDepth=2 只扫描最后2条
        // 所以不应该触发注入
        assertEquals(6, result.size)
        val systemText = getMessageText(result[0])
        assertEquals("System prompt", systemText)
    }

    @Test
    fun `scanDepth should trigger when keyword is within range`() {
        val lorebookId = Uuid.random()
        val regexInjection = createRegexInjection(
            keywords = listOf("latest"),
            scanDepth = 2,
            content = "Triggered content"
        )
        val lorebook = createLorebook(
            id = lorebookId,
            entries = listOf(regexInjection)
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Old message"),
            UIMessage.assistant("Response"),
            UIMessage.user("This is the latest message") // 在扫描范围内
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(lorebookIds = setOf(lorebookId)),
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook)
        )

        val systemText = getMessageText(result[0])
        assertTrue(systemText.contains("Triggered content"))
    }

    @Test
    fun `different entries should use their own scanDepth`() {
        val lorebookId = Uuid.random()
        val shallowEntry = createRegexInjection(
            keywords = listOf("old keyword"),
            scanDepth = 1, // 只扫描最后1条
            content = "Shallow scan content"
        )
        val deepEntry = createRegexInjection(
            keywords = listOf("old keyword"),
            scanDepth = 10, // 扫描最后10条
            content = "Deep scan content"
        )
        val lorebook = createLorebook(
            id = lorebookId,
            entries = listOf(shallowEntry, deepEntry)
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Message with old keyword"), // 较早的消息
            UIMessage.assistant("Response 1"),
            UIMessage.user("Response 2"),
            UIMessage.assistant("Response 3"),
            UIMessage.user("Latest message without keyword")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(lorebookIds = setOf(lorebookId)),
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook)
        )

        val systemText = getMessageText(result[0])
        // shallowEntry (scanDepth=1) 不应触发，因为最后1条消息不含关键词
        assertTrue(!systemText.contains("Shallow scan content"))
        // deepEntry (scanDepth=10) 应该触发，因为早期消息包含关键词
        assertTrue(systemText.contains("Deep scan content"))
    }

    @Test
    fun `recursive lorebook should activate chained entries`() {
        val lorebookId = Uuid.random()
        val seedEntry = createRegexInjection(
            keywords = listOf("alpha"),
            content = "beta breadcrumb"
        )
        val chainedEntry = createRegexInjection(
            keywords = listOf("beta"),
            content = "Chained lore"
        )
        val lorebook = createLorebook(
            id = lorebookId,
            recursiveScanning = true,
            entries = listOf(seedEntry, chainedEntry)
        )

        val result = transformMessages(
            messages = listOf(
                UIMessage.system("System prompt"),
                UIMessage.user("alpha trigger")
            ),
            assistant = createAssistant(lorebookIds = setOf(lorebookId)),
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook)
        )

        val systemText = getMessageText(result[0])
        assertTrue(systemText.contains("beta breadcrumb"))
        assertTrue(systemText.contains("Chained lore"))
    }

    @Test
    fun `recursive lorebook should respect prevent recursion metadata`() {
        val lorebookId = Uuid.random()
        val seedEntry = createRegexInjection(
            keywords = listOf("alpha"),
            content = "beta breadcrumb",
        ).copy(
            stMetadata = mapOf("prevent_recursion" to "true")
        )
        val chainedEntry = createRegexInjection(
            keywords = listOf("beta"),
            content = "Chained lore"
        )
        val lorebook = createLorebook(
            id = lorebookId,
            recursiveScanning = true,
            entries = listOf(seedEntry, chainedEntry)
        )

        val result = transformMessages(
            messages = listOf(
                UIMessage.system("System prompt"),
                UIMessage.user("alpha trigger")
            ),
            assistant = createAssistant(lorebookIds = setOf(lorebookId)),
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook)
        )

        val systemText = getMessageText(result[0])
        assertTrue(systemText.contains("beta breadcrumb"))
        assertTrue(!systemText.contains("Chained lore"))
    }

    @Test
    fun `lorebook should respect generation type triggers`() {
        val lorebookId = Uuid.random()
        val lorebook = createLorebook(
            id = lorebookId,
            entries = listOf(
                createRegexInjection(
                    keywords = listOf("magic"),
                    content = "Continue lore",
                ).copy(
                    stMetadata = mapOf("triggers" to "[continue]")
                )
            )
        )

        val normalResult = transformMessages(
            messages = listOf(UIMessage.system("System prompt"), UIMessage.user("magic")),
            assistant = createAssistant(lorebookIds = setOf(lorebookId)),
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook),
            generationType = "normal",
        )
        val continueResult = transformMessages(
            messages = listOf(UIMessage.system("System prompt"), UIMessage.user("magic")),
            assistant = createAssistant(lorebookIds = setOf(lorebookId)),
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook),
            generationType = "continue",
        )

        assertEquals("System prompt", getMessageText(normalResult[0]))
        assertTrue(getMessageText(continueResult[0]).contains("Continue lore"))
    }

    @Test
    fun `lorebook token budget should keep ignore budget entries`() {
        val lorebookId = Uuid.random()
        val lorebook = createLorebook(
            id = lorebookId,
            tokenBudget = 3,
            entries = listOf(
                createRegexInjection(
                    keywords = emptyList(),
                    constantActive = true,
                    priority = 300,
                    content = "alpha beta",
                ),
                createRegexInjection(
                    keywords = emptyList(),
                    constantActive = true,
                    priority = 200,
                    content = "gamma delta",
                ),
                createRegexInjection(
                    keywords = emptyList(),
                    constantActive = true,
                    priority = 100,
                    content = "epsilon",
                ).copy(
                    stMetadata = mapOf("ignore_budget" to "true")
                )
            )
        )

        val result = transformMessages(
            messages = listOf(UIMessage.system("System prompt"), UIMessage.user("hello")),
            assistant = createAssistant(lorebookIds = setOf(lorebookId)),
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook),
        )

        val systemText = getMessageText(result[0])
        assertTrue(systemText.contains("alpha beta"))
        assertTrue(systemText.contains("epsilon"))
        assertTrue(!systemText.contains("gamma delta"))
    }

    @Test
    fun `lorebook inclusion groups should keep the highest score winner`() {
        val lorebookId = Uuid.random()
        val lorebook = createLorebook(
            id = lorebookId,
            entries = listOf(
                createRegexInjection(
                    keywords = listOf("alpha"),
                    priority = 100,
                    content = "Single score",
                ).copy(
                    stMetadata = mapOf(
                        "group" to "facts",
                        "use_group_scoring" to "true",
                    )
                ),
                createRegexInjection(
                    keywords = listOf("alpha", "beta"),
                    priority = 100,
                    content = "Higher score",
                ).copy(
                    stMetadata = mapOf(
                        "group" to "facts",
                        "use_group_scoring" to "true",
                    )
                )
            )
        )

        val result = transformMessages(
            messages = listOf(UIMessage.system("System prompt"), UIMessage.user("alpha beta")),
            assistant = createAssistant(lorebookIds = setOf(lorebookId)),
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook),
        )

        val systemText = getMessageText(result[0])
        assertTrue(systemText.contains("Higher score"))
        assertTrue(!systemText.contains("Single score"))
    }

    @Test
    fun `sticky and cooldown worldbook effects should persist across turns`() {
        val lorebookId = Uuid.random()
        val runtimeState = LorebookRuntimeState()
        val lorebook = createLorebook(
            id = lorebookId,
            entries = listOf(
                createRegexInjection(
                    keywords = listOf("alpha"),
                    scanDepth = 1,
                    content = "Timed lore",
                ).copy(
                    stMetadata = mapOf(
                        "sticky" to "3",
                        "cooldown" to "2",
                    )
                )
            )
        )
        val assistant = createAssistant(lorebookIds = setOf(lorebookId))

        val firstTurn = transformMessages(
            messages = listOf(UIMessage.system("System prompt"), UIMessage.user("alpha")),
            assistant = assistant,
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook),
            runtimeState = runtimeState,
        )
        val stickyTurn = transformMessages(
            messages = listOf(
                UIMessage.system("System prompt"),
                UIMessage.user("alpha"),
                UIMessage.assistant("ack"),
                UIMessage.user("no match"),
            ),
            assistant = assistant,
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook),
            runtimeState = runtimeState,
        )
        val cooldownTurn = transformMessages(
            messages = listOf(
                UIMessage.system("System prompt"),
                UIMessage.user("alpha"),
                UIMessage.assistant("ack"),
                UIMessage.user("no match"),
                UIMessage.assistant("ack2"),
                UIMessage.user("still nothing"),
            ),
            assistant = assistant,
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook),
            runtimeState = runtimeState,
        )

        assertTrue(getMessageText(firstTurn[0]).contains("Timed lore"))
        assertTrue(getMessageText(stickyTurn[0]).contains("Timed lore"))
        assertEquals("System prompt", getMessageText(cooldownTurn[0]))
    }

    @Test
    fun `delay effect should suppress lorebook until enough turns exist`() {
        val lorebookId = Uuid.random()
        val lorebook = createLorebook(
            id = lorebookId,
            entries = listOf(
                createRegexInjection(
                    keywords = emptyList(),
                    constantActive = true,
                    content = "Delayed lore",
                ).copy(
                    stMetadata = mapOf("delay" to "3")
                )
            )
        )

        val earlyResult = transformMessages(
            messages = listOf(UIMessage.system("System prompt"), UIMessage.user("one")),
            assistant = createAssistant(lorebookIds = setOf(lorebookId)),
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook),
        )
        val readyResult = transformMessages(
            messages = listOf(
                UIMessage.system("System prompt"),
                UIMessage.user("one"),
                UIMessage.assistant("two"),
                UIMessage.user("three"),
            ),
            assistant = createAssistant(lorebookIds = setOf(lorebookId)),
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook),
        )

        assertEquals("System prompt", getMessageText(earlyResult[0]))
        assertTrue(getMessageText(readyResult[0]).contains("Delayed lore"))
    }

    @Test
    fun `generic lorebook triggering should include character card fields`() {
        val lorebookId = Uuid.random()
        val lorebook = createLorebook(
            id = lorebookId,
            entries = listOf(
                createRegexInjection(
                    keywords = listOf("forest guardian"),
                    content = "Matched description",
                    matchCharacterDescription = true,
                ),
                createRegexInjection(
                    keywords = listOf("warm and patient"),
                    content = "Matched personality",
                    matchCharacterPersonality = true,
                ),
                createRegexInjection(
                    keywords = listOf("moonlit ruins"),
                    content = "Matched scenario",
                    matchScenario = true,
                ),
                createRegexInjection(
                    keywords = listOf("speak softly"),
                    content = "Matched creator notes",
                    matchCreatorNotes = true,
                ),
                createRegexInjection(
                    keywords = listOf("hidden vow"),
                    content = "Matched depth prompt",
                    matchCharacterDepthPrompt = true,
                ),
            )
        )

        val result = transformMessages(
            messages = listOf(UIMessage.system("System prompt"), UIMessage.user("hello")),
            assistant = createAssistant(
                lorebookIds = setOf(lorebookId),
                stCharacterData = SillyTavernCharacterData(
                    description = "forest guardian",
                    personality = "warm and patient",
                    scenario = "moonlit ruins",
                    creatorNotes = "speak softly",
                    depthPrompt = StDepthPrompt(prompt = "hidden vow"),
                ),
            ),
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook),
        )

        val systemText = getMessageText(result.first())
        assertTrue(systemText.contains("Matched description"))
        assertTrue(systemText.contains("Matched personality"))
        assertTrue(systemText.contains("Matched scenario"))
        assertTrue(systemText.contains("Matched creator notes"))
        assertTrue(systemText.contains("Matched depth prompt"))
    }

    @Test
    fun `disabled world book should not trigger`() {
        val lorebookId = Uuid.random()
        val regexInjection = createRegexInjection(
            keywords = listOf("magic"),
            content = "Should not appear"
        )
        val lorebook = createLorebook(
            id = lorebookId,
            enabled = false,
            entries = listOf(regexInjection)
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Tell me about magic")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(lorebookIds = setOf(lorebookId)),
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook)
        )

        assertEquals(2, result.size)
        val systemText = getMessageText(result[0])
        assertEquals("System prompt", systemText)
    }
    // endregion

    // region Multiple injections tests
    @Test
    fun `multiple injections at different positions should all apply`() {
        val id1 = Uuid.random()
        val id2 = Uuid.random()
        val id3 = Uuid.random()

        val injections = listOf(
            createModeInjection(
                id = id1,
                position = InjectionPosition.BEFORE_SYSTEM_PROMPT,
                content = "Before"
            ),
            createModeInjection(
                id = id2,
                position = InjectionPosition.AFTER_SYSTEM_PROMPT,
                content = "After"
            ),
            createModeInjection(
                id = id3,
                position = InjectionPosition.TOP_OF_CHAT,
                content = "Top"
            )
        )

        val messages = listOf(
            UIMessage.system("System"),
            UIMessage.user("Hello")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(id1, id2, id3)),
            modeInjections = injections,
            lorebooks = emptyList()
        )

        assertEquals(2, result.size)
        val systemText = getMessageText(result[0])
        assertTrue(systemText.startsWith("Before"))
        assertTrue(systemText.contains("System"))
        assertTrue(systemText.contains("After"))
        assertTrue(systemText.contains("Top"))
        assertTrue(systemText.indexOf("After") < systemText.indexOf("Top"))
    }

    @Test
    fun `combined mode injection and world book should both apply`() {
        val modeId = Uuid.random()
        val lorebookId = Uuid.random()

        val modeInjection = createModeInjection(
            id = modeId,
            content = "Mode content"
        )

        val regexInjection = createRegexInjection(
            keywords = listOf("hello"),
            content = "WorldBook content"
        )
        val lorebook = createLorebook(
            id = lorebookId,
            entries = listOf(regexInjection)
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("hello world")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(
                modeInjectionIds = setOf(modeId),
                lorebookIds = setOf(lorebookId)
            ),
            modeInjections = listOf(modeInjection),
            lorebooks = listOf(lorebook)
        )

        val systemText = getMessageText(result[0])
        assertTrue(systemText.contains("Mode content"))
        assertTrue(systemText.contains("WorldBook content"))
    }
    // endregion

    // region collectInjections tests
    @Test
    fun `collectInjections should return empty for no matching conditions`() {
        val result = collectInjections(
            messages = listOf(UIMessage.user("Hello")),
            assistant = createAssistant(),
            modeInjections = listOf(createModeInjection()),
            lorebooks = emptyList()
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `collectInjections should collect linked and enabled mode injections`() {
        val id1 = Uuid.random()
        val id2 = Uuid.random()

        val injections = listOf(
            createModeInjection(id = id1, enabled = true),
            createModeInjection(id = id2, enabled = false)
        )

        val result = collectInjections(
            messages = listOf(UIMessage.user("Hello")),
            assistant = createAssistant(modeInjectionIds = setOf(id1, id2)),
            modeInjections = injections,
            lorebooks = emptyList()
        )

        assertEquals(1, result.size)
        assertEquals(id1, result[0].id)
    }
    // endregion

    // region applyInjections tests
    @Test
    fun `applyInjections with empty map should return original messages`() {
        val messages = listOf(
            UIMessage.system("System"),
            UIMessage.user("Hello")
        )

        val result = applyInjections(messages, emptyMap())

        assertEquals(messages, result)
    }

    @Test
    fun `applyInjections should handle messages without system message`() {
        val injection = createModeInjection(
            position = InjectionPosition.BEFORE_SYSTEM_PROMPT,
            content = "Before content"
        )

        val messages = listOf(
            UIMessage.user("Hello"),
            UIMessage.assistant("Hi!")
        )

        val result = applyInjections(
            messages,
            mapOf(InjectionPosition.BEFORE_SYSTEM_PROMPT to listOf(injection))
        )

        assertEquals(3, result.size)
        assertEquals(MessageRole.SYSTEM, result[0].role)
        assertEquals("Before content", getMessageText(result[0]))
    }
    // endregion

    // region findSafeInsertIndex tests
    @Test
    fun `findSafeInsertIndex should not insert between USER and ASSISTANT with tools`() {
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Call a tool"),
            createAssistantWithUnexecutedTool("call_1", "tool")
        )

        // 尝试在索引 2（USER 和 ASSISTANT(tool) 之间）插入，应该移到 USER 之前
        val safeIndex = findSafeInsertIndex(messages, 2)
        assertEquals(1, safeIndex)
    }

    @Test
    fun `findSafeInsertIndex should allow insert before ASSISTANT without tools`() {
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello"),
            UIMessage.assistant("Hi!")
        )

        // ASSISTANT 没有 tool，直接插入不受限制
        val safeIndex = findSafeInsertIndex(messages, 2)
        assertEquals(2, safeIndex)
    }

    @Test
    fun `findSafeInsertIndex should not insert between ASSISTANT with tools and TOOL result`() {
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Call a tool"),
            createAssistantWithUnexecutedTool("call_1", "tool"),
            createLegacyToolMessage()
        )

        val safeIndex = findSafeInsertIndex(messages, 3)
        assertEquals(1, safeIndex)
    }

    @Test
    fun `findSafeInsertIndex should not insert between consecutive TOOL results`() {
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Call a tool"),
            createAssistantWithUnexecutedTool("call_1", "tool"),
            createLegacyToolMessage("tool result 1"),
            createLegacyToolMessage("tool result 2")
        )

        val safeIndex = findSafeInsertIndex(messages, 4)
        assertEquals(1, safeIndex)
    }

    @Test
    fun `findSafeInsertIndex should return original index when no tools`() {
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello"),
            UIMessage.assistant("Hi!"),
            UIMessage.user("How are you?")
        )

        assertEquals(3, findSafeInsertIndex(messages, 3))
        assertEquals(2, findSafeInsertIndex(messages, 2))
        assertEquals(0, findSafeInsertIndex(messages, 0))
    }

    @Test
    fun `BOTTOM_OF_CHAT lorebook entry should not inject between USER and ASSISTANT with tools`() {
        val injection = createRegexInjection(
            position = InjectionPosition.BOTTOM_OF_CHAT,
            content = "Bottom injection"
        )

        // 消息序列: SYSTEM -> USER -> ASSISTANT(tool)
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Call a tool"),
            createAssistantWithUnexecutedTool("call_1", "tool")
        )

        val result = transformWithAlwaysActiveLorebookEntry(injection, messages)

        assertEquals(4, result.size)

        // 注入应该在 USER 之前，而不是 USER 和 ASSISTANT(tool) 之间
        val injectedIndex = result.indexOfFirst { getMessageText(it).contains("Bottom injection") }
        val originalUserIndex = result.indexOfFirst { getMessageText(it).contains("Call a tool") }
        val assistantWithToolIndex = result.indexOfFirst { it.getTools().isNotEmpty() }

        assertTrue(injectedIndex < originalUserIndex)
        assertEquals(originalUserIndex + 1, assistantWithToolIndex)
    }

    @Test
    fun `BOTTOM_OF_CHAT lorebook entry should not inject inside legacy tool result chain`() {
        val injection = createRegexInjection(
            position = InjectionPosition.BOTTOM_OF_CHAT,
            content = "Bottom injection"
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Call a tool"),
            createAssistantWithUnexecutedTool("call_1", "tool"),
            createLegacyToolMessage()
        )

        val result = transformWithAlwaysActiveLorebookEntry(injection, messages)

        assertEquals(5, result.size)

        val injectedIndex = result.indexOfFirst { getMessageText(it).contains("Bottom injection") }
        val originalUserIndex = result.indexOfFirst { getMessageText(it).contains("Call a tool") }
        val assistantWithToolIndex = result.indexOfFirst { it.getTools().isNotEmpty() }
        val toolResultIndex = result.indexOfLast { it.role == MessageRole.TOOL }

        assertTrue(injectedIndex < originalUserIndex)
        assertEquals(originalUserIndex + 1, assistantWithToolIndex)
        assertEquals(assistantWithToolIndex + 1, toolResultIndex)
    }

    @Test
    fun `AT_DEPTH lorebook entry should not inject between USER and ASSISTANT with tools`() {
        val injection = createRegexInjection(
            position = InjectionPosition.AT_DEPTH,
            injectDepth = 1,
            content = "Depth injection"
        )

        // 消息序列: SYSTEM -> USER -> ASSISTANT(tool)
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Call a tool"),
            createAssistantWithUnexecutedTool("call_1", "tool")
        )

        val result = transformWithAlwaysActiveLorebookEntry(injection, messages)

        assertEquals(4, result.size)

        val injectedIndex = result.indexOfFirst { getMessageText(it).contains("Depth injection") }
        val originalUserIndex = result.indexOfFirst { getMessageText(it).contains("Call a tool") }
        val assistantWithToolIndex = result.indexOfFirst { it.getTools().isNotEmpty() }

        assertTrue(injectedIndex < originalUserIndex)
        assertEquals(originalUserIndex + 1, assistantWithToolIndex)
    }

    @Test
    fun `BOTTOM_OF_CHAT lorebook entry after ASSISTANT with tools should work normally`() {
        val injection = createRegexInjection(
            position = InjectionPosition.BOTTOM_OF_CHAT,
            content = "Bottom injection"
        )

        // 消息序列: SYSTEM -> USER -> ASSISTANT(executed tool) -> ASSISTANT(final) -> USER
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Call a tool"),
            createAssistantWithExecutedTool("call_1", "tool"),
            UIMessage.assistant("Here's the result"),
            UIMessage.user("Thanks!")
        )

        val result = transformWithAlwaysActiveLorebookEntry(injection, messages)

        assertEquals(6, result.size)

        // 注入应该在最后一条用户消息之前
        val injectedIndex = result.indexOfFirst { getMessageText(it).contains("Bottom injection") }
        val lastUserIndex = result.indexOfLast { it.role == MessageRole.USER && getMessageText(it) == "Thanks!" }
        assertEquals(lastUserIndex - 1, injectedIndex)
    }

    @Test
    fun `global lorebook settings should allow name-prefixed matching`() {
        val lorebookId = Uuid.random()
        val lorebook = createLorebook(
            id = lorebookId,
            entries = listOf(
                createRegexInjection(
                    keywords = listOf("Alice: hello"),
                    scanDepth = 4,
                    content = "Matched with names",
                )
            )
        )

        val result = transformMessages(
            messages = listOf(UIMessage.system("System prompt"), UIMessage.user("hello")),
            assistant = createAssistant(lorebookIds = setOf(lorebookId)).copy(name = "Seraphina"),
            settings = Settings(
                displaySetting = DisplaySetting(userNickname = "Alice"),
                lorebookGlobalSettings = LorebookGlobalSettings(includeNames = true)
            ),
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook),
        )

        assertTrue(getMessageText(result[0]).contains("Matched with names"))
    }

    @Test
    fun `global lorebook min activations should expand scan depth`() {
        val lorebookId = Uuid.random()
        val lorebook = createLorebook(
            id = lorebookId,
            entries = listOf(
                createRegexInjection(
                    keywords = listOf("ancient sigil"),
                    scanDepth = 4,
                    content = "Expanded depth hit",
                )
            )
        )

        val result = transformMessages(
            messages = listOf(
                UIMessage.system("System prompt"),
                UIMessage.user("ancient sigil"),
                UIMessage.assistant("ack"),
                UIMessage.user("latest message"),
            ),
            assistant = createAssistant(lorebookIds = setOf(lorebookId)),
            settings = Settings(
                lorebookGlobalSettings = LorebookGlobalSettings(
                    scanDepth = 1,
                    minActivations = 1,
                    minActivationsDepthMax = 3,
                )
            ),
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook),
        )

        assertTrue(getMessageText(result[0]).contains("Expanded depth hit"))
    }

    @Test
    fun `character strategy should change which scoped lorebook entry wins shared budget`() {
        val characterEntry = createRegexInjection(
            priority = 10,
            position = InjectionPosition.BEFORE_SYSTEM_PROMPT,
            role = MessageRole.SYSTEM,
            content = "character",
            keywords = emptyList(),
            constantActive = true,
        )
        val globalEntry = createRegexInjection(
            priority = 100,
            position = InjectionPosition.BEFORE_SYSTEM_PROMPT,
            role = MessageRole.SYSTEM,
            content = "global",
            keywords = emptyList(),
            constantActive = true,
        )

        fun selectedWithStrategy(strategy: WorldInfoCharacterStrategy) = selectTriggeredLorebookEntries(
            entries = listOf(
                ActivatedLorebookEntry(characterEntry, LorebookScope.CHARACTER),
                ActivatedLorebookEntry(globalEntry, LorebookScope.GLOBAL),
            ),
            budget = 1,
            strategy = strategy,
        )

        assertEquals(
            listOf(characterEntry.id),
            selectedWithStrategy(WorldInfoCharacterStrategy.CHARACTER_FIRST).map { it.id },
        )
        assertEquals(
            listOf(globalEntry.id),
            selectedWithStrategy(WorldInfoCharacterStrategy.GLOBAL_FIRST).map { it.id },
        )
        assertEquals(
            listOf(globalEntry.id),
            selectedWithStrategy(WorldInfoCharacterStrategy.EVENLY).map { it.id },
        )
    }

    @Test
    fun `explicit global lorebook should inject even when assistant is not directly linked`() {
        val globalLorebookId = Uuid.random()
        val result = transformMessages(
            messages = listOf(
                UIMessage.system("System prompt"),
                UIMessage.user("hello"),
            ),
            assistant = createAssistant(),
            settings = Settings(
                globalLorebookIds = setOf(globalLorebookId),
                lorebookGlobalSettings = LorebookGlobalSettings(
                    budgetPercent = 0,
                    budgetCap = 16,
                ),
            ),
            modeInjections = emptyList(),
            lorebooks = listOf(
                createLorebook(
                    id = globalLorebookId,
                    entries = listOf(
                        createRegexInjection(
                            priority = 10,
                            position = InjectionPosition.BEFORE_SYSTEM_PROMPT,
                            role = MessageRole.SYSTEM,
                            content = "global lore",
                            keywords = emptyList(),
                            constantActive = true,
                        )
                    )
                )
            ),
        )

        assertTrue(getMessageText(result.first()).contains("global lore"))
    }

    @Test
    fun `shared lorebook budget should allow exact token fit`() {
        val firstLorebookId = Uuid.random()
        val secondLorebookId = Uuid.random()
        val assistant = createAssistant(lorebookIds = setOf(firstLorebookId, secondLorebookId))

        val result = transformMessages(
            messages = listOf(
                UIMessage.system("System prompt"),
                UIMessage.user("hello"),
            ),
            assistant = assistant,
            settings = Settings(
                assistants = listOf(assistant),
                lorebookGlobalSettings = LorebookGlobalSettings(
                    budgetPercent = 0,
                    budgetCap = 2,
                ),
            ),
            modeInjections = emptyList(),
            lorebooks = listOf(
                createLorebook(
                    id = firstLorebookId,
                    entries = listOf(
                        createRegexInjection(
                            priority = 10,
                            position = InjectionPosition.BEFORE_SYSTEM_PROMPT,
                            content = "alpha",
                            keywords = emptyList(),
                            constantActive = true,
                        )
                    )
                ),
                createLorebook(
                    id = secondLorebookId,
                    entries = listOf(
                        createRegexInjection(
                            priority = 9,
                            position = InjectionPosition.BEFORE_SYSTEM_PROMPT,
                            content = "beta",
                            keywords = emptyList(),
                            constantActive = true,
                        )
                    )
                ),
            ),
        )

        val systemText = getMessageText(result.first())
        assertTrue(systemText.contains("alpha"))
        assertTrue(systemText.contains("beta"))
    }

    @Test
    fun `lorebook local budget should allow exact token fit across candidates`() {
        val lorebookId = Uuid.random()
        val result = transformMessages(
            messages = listOf(
                UIMessage.system("System prompt"),
                UIMessage.user("hello"),
            ),
            assistant = createAssistant(lorebookIds = setOf(lorebookId)),
            modeInjections = emptyList(),
            lorebooks = listOf(
                createLorebook(
                    id = lorebookId,
                    tokenBudget = 2,
                    entries = listOf(
                        createRegexInjection(
                            priority = 10,
                            position = InjectionPosition.BEFORE_SYSTEM_PROMPT,
                            content = "alpha",
                            keywords = emptyList(),
                            constantActive = true,
                        ),
                        createRegexInjection(
                            priority = 9,
                            position = InjectionPosition.BEFORE_SYSTEM_PROMPT,
                            content = "beta",
                            keywords = emptyList(),
                            constantActive = true,
                        ),
                    ),
                )
            ),
        )

        val systemText = getMessageText(result.first())
        assertTrue(systemText.contains("alpha"))
        assertTrue(systemText.contains("beta"))
    }
    // endregion
}
