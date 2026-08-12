package me.rerere.ai.ui

import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageContextLimitTest {
    @Test
    fun `limitContext with limit 0 should return original list`() {
        val messages = createTestMessages(5)
        assertEquals(messages, messages.limitContext(0))
    }

    @Test
    fun `limitContext with negative limit should return original list`() {
        val messages = createTestMessages(5)
        assertEquals(messages, messages.limitContext(-1))
    }

    @Test
    fun `limitContext within limit should return original list`() {
        val messages = createTestMessages(10)
        assertEquals(messages, messages.limitContext(10))
    }

    @Test
    fun `limitContext with empty list should return empty list`() {
        assertEquals(emptyList<UIMessage>(), emptyList<UIMessage>().limitContext(5))
    }

    /**
     * limit 过小时无法构造滞回, 但至少不能崩溃, 也不能把上下文清空
     */
    @Test
    fun `limitContext with tiny limit should degrade gracefully`() {
        val all = createTestMessages(20)
        for (limit in 1..4) {
            for (size in (limit + 1)..20) {
                val result = all.subList(0, size).limitContext(limit)
                assertTrue(
                    "limit=$limit size=$size produced ${result.size} messages",
                    result.size in 1..limit
                )
                assertEquals(all.subList(size - result.size, size), result)
            }
        }
    }

    @Test
    fun `limitContext should drop to about half when limit is first exceeded`() {
        val messages = createTestMessages(11)
        val result = messages.limitContext(10)

        // limit=10 -> target=5, stride=5, startIndex=5
        assertEquals(6, result.size)
        assertEquals(messages.subList(5, 11), result)
    }

    /**
     * 核心性质: 同一级台阶内追加消息时截断起点必须保持不动, 否则请求前缀每轮都变, 提示词缓存必然失效
     */
    @Test
    fun `limitContext should keep the same start message while within one step`() {
        val all = createTestMessages(60)

        val startsWithinStep = (11..14).map { size ->
            all.subList(0, size).limitContext(10).first()
        }
        assertEquals(1, startsWithinStep.distinct().size)
        assertEquals(all[5], startsWithinStep.first())

        assertEquals(all[10], all.subList(0, 15).limitContext(10).first())
        assertEquals(all[10], all.subList(0, 19).limitContext(10).first())
        assertEquals(all[15], all.subList(0, 20).limitContext(10).first())
    }

    @Test
    fun `limitContext should never exceed the limit nor drop below the target`() {
        val all = createTestMessages(120)
        for (size in 11..120) {
            val result = all.subList(0, size).limitContext(10)
            assertTrue(
                "size=$size produced ${result.size} messages",
                result.size in 5..9
            )
            assertEquals(all.subList(size - result.size, size), result)
        }
    }

    @Test
    fun `limitContext should only truncate once per step`() {
        val all = createTestMessages(60)
        val distinctStarts = (11..60).map { size ->
            all.subList(0, size).limitContext(10).first()
        }.distinct()

        assertEquals(11, distinctStarts.size)
    }

    @Test
    fun `limitContext with executed tool at start should include corresponding tool call`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("User message"))),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "call1",
                        toolName = "test_tool",
                        input = "{}",
                        output = emptyList()
                    )
                )
            ),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "call1",
                        toolName = "test_tool",
                        input = "{}",
                        output = listOf(UIMessagePart.Text("result"))
                    )
                )
            ),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Final response")))
        )

        val result = messages.limitContext(3)
        assertEquals(messages, result)
    }

    @Test
    fun `limitContext with tool call at start should include corresponding user message`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Old query"))),
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("User query"))),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "call1",
                        toolName = "test_tool",
                        input = "{}",
                        output = emptyList()
                    )
                )
            ),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "call1",
                        toolName = "test_tool",
                        input = "{}",
                        output = listOf(UIMessagePart.Text("result"))
                    )
                )
            ),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Final response")))
        )

        val result = messages.limitContext(4)
        assertEquals(messages.subList(1, 5), result)
    }

    private fun createTestMessages(count: Int): List<UIMessage> = List(count) { index ->
        UIMessage(
            role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("Message $index"))
        )
    }
}
