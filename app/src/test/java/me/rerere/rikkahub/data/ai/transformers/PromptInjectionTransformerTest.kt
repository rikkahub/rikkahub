package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.Lorebook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class PromptInjectionTransformerTest {

    // region Helper functions
    private fun createAssistant(
        modeInjectionIds: Set<Uuid> = emptySet(),
        lorebookIds: Set<Uuid> = emptySet()
    ) = Assistant(
        modeInjectionIds = modeInjectionIds,
        lorebookIds = lorebookIds
    )

    private fun createModeInjection(
        id: Uuid = Uuid.random(),
        name: String = "Test Injection",
        enabled: Boolean = true,
        priority: Int = 0,
        position: InjectionPosition = InjectionPosition.AFTER_SYSTEM_PROMPT,
        content: String = "Injected content",
        injectDepth: Int = 4,
        role: MessageRole = MessageRole.USER
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
        constantActive: Boolean = false
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
        constantActive = constantActive
    )

    private fun createLorebook(
        id: Uuid = Uuid.random(),
        name: String = "Test Lorebook",
        enabled: Boolean = true,
        entries: List<PromptInjection.RegexInjection> = emptyList()
    ) = Lorebook(
        id = id,
        name = name,
        enabled = enabled,
        entries = entries
    )

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

    // region TOP_OF_CHAT tests
    @Test
    fun `mode injection with TOP_OF_CHAT should insert before first user message`() {
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

        assertEquals(4, result.size)
        assertEquals(MessageRole.SYSTEM, result[0].role)
        assertEquals("System prompt", getMessageText(result[0]))
        assertEquals(MessageRole.USER, result[1].role)
        assertEquals("Top of chat content", getMessageText(result[1]))
        assertEquals(MessageRole.USER, result[2].role)
    }
    // endregion

    // region BOTTOM_OF_CHAT tests
    @Test
    fun `mode injection with BOTTOM_OF_CHAT should insert before last message`() {
        val injectionId = Uuid.random()
        val injection = createModeInjection(
            id = injectionId,
            position = InjectionPosition.BOTTOM_OF_CHAT,
            content = "Bottom of chat content"
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello"),
            UIMessage.assistant("Hi!"),
            UIMessage.user("How are you?")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(injectionId)),
            modeInjections = listOf(injection),
            lorebooks = emptyList()
        )

        assertEquals(5, result.size)
        assertEquals(MessageRole.USER, result[3].role)
        assertEquals("Bottom of chat content", getMessageText(result[3]))
        assertEquals(MessageRole.USER, result[4].role)
        assertEquals("How are you?", getMessageText(result[4]))
    }
    // endregion

    // region AT_DEPTH tests
    @Test
    fun `mode injection with AT_DEPTH should insert at specified depth from end`() {
        val injectionId = Uuid.random()
        val injection = createModeInjection(
            id = injectionId,
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

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(injectionId)),
            modeInjections = listOf(injection),
            lorebooks = emptyList()
        )

        // depth=2 means insert before the 2nd message from the end
        // Original: [System, User1, Asst1, User2, Asst2] (5 messages)
        // Insert at index 5-2=3, so: [System, User1, Asst1, Injected, User2, Asst2]
        assertEquals(6, result.size)
        assertEquals(MessageRole.USER, result[3].role)
        assertEquals("At depth 2 content", getMessageText(result[3]))
        assertEquals(MessageRole.USER, result[4].role)
        assertEquals("Message 2", getMessageText(result[4]))
    }

    @Test
    fun `AT_DEPTH with depth 1 should insert before last message`() {
        val injectionId = Uuid.random()
        val injection = createModeInjection(
            id = injectionId,
            position = InjectionPosition.AT_DEPTH,
            injectDepth = 1,
            content = "Before last"
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

        assertEquals(4, result.size)
        assertEquals("Before last", getMessageText(result[2]))
        assertEquals("Hi!", getMessageText(result[3]))
    }

    @Test
    fun `AT_DEPTH with depth larger than message count should insert at beginning`() {
        val injectionId = Uuid.random()
        val injection = createModeInjection(
            id = injectionId,
            position = InjectionPosition.AT_DEPTH,
            injectDepth = 100,
            content = "Large depth content"
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

        assertEquals(3, result.size)
        assertEquals("Large depth content", getMessageText(result[0]))
    }

    @Test
    fun `multiple AT_DEPTH injections with different depths should all apply`() {
        val id1 = Uuid.random()
        val id2 = Uuid.random()

        val injections = listOf(
            createModeInjection(
                id = id1,
                position = InjectionPosition.AT_DEPTH,
                injectDepth = 1,
                content = "Depth 1"
            ),
            createModeInjection(
                id = id2,
                position = InjectionPosition.AT_DEPTH,
                injectDepth = 3,
                content = "Depth 3"
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
            assistant = createAssistant(modeInjectionIds = setOf(id1, id2)),
            modeInjections = injections,
            lorebooks = emptyList()
        )

        // Both should be inserted
        assertEquals(7, result.size)
        assertTrue(result.any { getMessageText(it).contains("Depth 1") })
        assertTrue(result.any { getMessageText(it).contains("Depth 3") })
    }

    @Test
    fun `multiple AT_DEPTH injections with same depth should be merged`() {
        val id1 = Uuid.random()
        val id2 = Uuid.random()

        val injections = listOf(
            createModeInjection(
                id = id1,
                position = InjectionPosition.AT_DEPTH,
                injectDepth = 2,
                priority = 10,
                content = "Higher priority"
            ),
            createModeInjection(
                id = id2,
                position = InjectionPosition.AT_DEPTH,
                injectDepth = 2,
                priority = 5,
                content = "Lower priority"
            )
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello"),
            UIMessage.assistant("Hi!")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(id1, id2)),
            modeInjections = injections,
            lorebooks = emptyList()
        )

        // Same depth injections should be merged into one message
        assertEquals(4, result.size)
        val injectedText = getMessageText(result[1])
        assertTrue(injectedText.contains("Higher priority"))
        assertTrue(injectedText.contains("Lower priority"))
        // Higher priority should come first
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

        assertEquals(3, result.size)
        val systemText = getMessageText(result[0])
        assertTrue(systemText.startsWith("Before"))
        assertTrue(systemText.contains("System"))
        assertTrue(systemText.endsWith("After"))
        assertEquals("Top", getMessageText(result[1]))
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

    // region Tool call chain preservation tests
    @Test
    fun `BOTTOM_OF_CHAT should not break tool call chain`() {
        val injectionId = Uuid.random()
        val injection = createModeInjection(
            id = injectionId,
            position = InjectionPosition.BOTTOM_OF_CHAT,
            content = "Bottom injection"
        )

        // 消息序列: SYSTEM -> USER -> ASSISTANT(unexecuted tool) -> ASSISTANT(executed tool)
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Call a tool"),
            createAssistantWithUnexecutedTool("call_123", "test_tool"),
            createAssistantWithExecutedTool("call_123", "test_tool")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(injectionId)),
            modeInjections = listOf(injection),
            lorebooks = emptyList()
        )

        // 注入应该在工具调用链之前，而不是在中间
        assertEquals(5, result.size)

        // 验证工具调用链没有被破坏
        val unexecutedIndex = result.indexOfFirst { it.getTools().any { t -> !t.isExecuted } }
        val executedIndex = result.indexOfFirst { it.getTools().any { t -> t.isExecuted } }

        // executed tool 应该紧跟在 unexecuted tool 后面
        assertEquals(unexecutedIndex + 1, executedIndex)

        // 注入的消息应该在工具调用链之前
        val injectedIndex = result.indexOfFirst { getMessageText(it).contains("Bottom injection") }
        assertTrue(injectedIndex < unexecutedIndex)
    }

    @Test
    fun `AT_DEPTH should not break tool call chain`() {
        val injectionId = Uuid.random()
        val injection = createModeInjection(
            id = injectionId,
            position = InjectionPosition.AT_DEPTH,
            injectDepth = 1, // 尝试在最后一条消息之前插入
            content = "Depth injection"
        )

        // 消息序列: SYSTEM -> USER -> ASSISTANT(unexecuted tool) -> ASSISTANT(executed tool)
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Call a tool"),
            createAssistantWithUnexecutedTool("call_456", "test_tool"),
            createAssistantWithExecutedTool("call_456", "test_tool")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(injectionId)),
            modeInjections = listOf(injection),
            lorebooks = emptyList()
        )

        assertEquals(5, result.size)

        // 验证工具调用链没有被破坏
        val unexecutedIndex = result.indexOfFirst { it.getTools().any { t -> !t.isExecuted } }
        val executedIndex = result.indexOfFirst { it.getTools().any { t -> t.isExecuted } }
        assertEquals(unexecutedIndex + 1, executedIndex)
    }

    @Test
    fun `multiple tool calls should all be preserved`() {
        val injectionId = Uuid.random()
        val injection = createModeInjection(
            id = injectionId,
            position = InjectionPosition.AT_DEPTH,
            injectDepth = 2,
            content = "Depth injection"
        )

        // 消息序列: SYSTEM -> USER -> ASSISTANT(unexecuted1) -> ASSISTANT(executed1) -> ASSISTANT(unexecuted2) -> ASSISTANT(executed2)
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Call tools"),
            createAssistantWithUnexecutedTool("call_1", "tool_1"),
            createAssistantWithExecutedTool("call_1", "tool_1"),
            createAssistantWithUnexecutedTool("call_2", "tool_2"),
            createAssistantWithExecutedTool("call_2", "tool_2")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(injectionId)),
            modeInjections = listOf(injection),
            lorebooks = emptyList()
        )

        // 验证两个工具调用链都没有被破坏
        val unexecuted = result.mapIndexedNotNull { index, msg ->
            if (msg.getTools().any { !it.isExecuted }) index else null
        }
        val executed = result.mapIndexedNotNull { index, msg ->
            if (msg.getTools().any { it.isExecuted }) index else null
        }

        assertEquals(2, unexecuted.size)
        assertEquals(2, executed.size)

        // 每个 unexecuted tool 后面紧跟着对应的 executed tool
        unexecuted.forEachIndexed { i, callIndex ->
            assertEquals(callIndex + 1, executed[i])
        }
    }

    @Test
    fun `findSafeInsertIndex should return safe position before tool call chain`() {
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello"),
            createAssistantWithUnexecutedTool("call_1", "tool"),
            createAssistantWithExecutedTool("call_1", "tool")
        )

        // 尝试在索引 3 (executed tool 位置) 插入
        val safeIndex = findSafeInsertIndex(messages, 3)
        // 应该返回 2 (unexecuted tool 之前)
        assertEquals(2, safeIndex)
    }

    @Test
    fun `findSafeInsertIndex should handle consecutive tool call chains`() {
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello"),
            createAssistantWithUnexecutedTool("call_1", "tool1"),
            createAssistantWithExecutedTool("call_1", "tool1"),
            createAssistantWithUnexecutedTool("call_2", "tool2"),
            createAssistantWithExecutedTool("call_2", "tool2")
        )

        // 尝试在索引 5 (最后一个 executed tool) 插入
        val safeIndex = findSafeInsertIndex(messages, 5)
        // 应该返回 4 (第二个 unexecuted tool 之前)
        assertEquals(4, safeIndex)

        // 尝试在索引 3 (第一个 executed tool) 插入
        val safeIndex2 = findSafeInsertIndex(messages, 3)
        // 应该返回 2 (第一个 unexecuted tool 之前)
        assertEquals(2, safeIndex2)
    }

    @Test
    fun `findSafeInsertIndex should return original index when not in tool chain`() {
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello"),
            UIMessage.assistant("Hi!"),
            UIMessage.user("How are you?")
        )

        // 没有工具调用，应该返回原索引
        assertEquals(3, findSafeInsertIndex(messages, 3))
        assertEquals(2, findSafeInsertIndex(messages, 2))
        assertEquals(0, findSafeInsertIndex(messages, 0))
    }

    @Test
    fun `injection after completed tool chain should work normally`() {
        val injectionId = Uuid.random()
        val injection = createModeInjection(
            id = injectionId,
            position = InjectionPosition.BOTTOM_OF_CHAT,
            content = "Bottom injection"
        )

        // 消息序列: SYSTEM -> USER -> ASSISTANT(executed tool) -> ASSISTANT(final response) -> USER
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Call a tool"),
            createAssistantWithExecutedTool("call_1", "tool"),
            UIMessage.assistant("Here's the result"),
            UIMessage.user("Thanks!")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(injectionId)),
            modeInjections = listOf(injection),
            lorebooks = emptyList()
        )

        assertEquals(6, result.size)

        // 注入应该在最后一条用户消息之前
        val injectedIndex = result.indexOfFirst { getMessageText(it).contains("Bottom injection") }
        val lastUserIndex = result.indexOfLast { it.role == MessageRole.USER && getMessageText(it) == "Thanks!" }
        assertEquals(lastUserIndex - 1, injectedIndex)
    }

    @Test
    fun `findSafeInsertIndex should handle assistant with multiple tools in one message`() {
        // 一个 assistant 消息包含多个 tool（agentic loop 场景）
        val multiToolAssistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call_1",
                    toolName = "tool_1",
                    input = "{}",
                    output = emptyList()
                ),
                UIMessagePart.Tool(
                    toolCallId = "call_2",
                    toolName = "tool_2",
                    input = "{}",
                    output = emptyList()
                )
            )
        )
        val executedToolAssistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call_1",
                    toolName = "tool_1",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("result1"))
                ),
                UIMessagePart.Tool(
                    toolCallId = "call_2",
                    toolName = "tool_2",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("result2"))
                )
            )
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello"),
            multiToolAssistant,
            executedToolAssistant
        )

        // 尝试在索引 3 插入，应该返回 2（多 tool 消息之前）
        val safeIndex = findSafeInsertIndex(messages, 3)
        assertEquals(2, safeIndex)
    }
    // endregion
}
