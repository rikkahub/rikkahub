package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.MemoryToolNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class MemoryConsolidatorTest {
    private val zone = TimeZone.UTC

    private fun minute(minute: Int) = LocalDateTime(2026, 1, 1, 10, minute)
    private fun timeOf(minute: Int) = minute(minute).toInstant(zone).toEpochMilliseconds()

    private fun user(minute: Int, text: String = "user $minute") =
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)), createdAt = minute(minute))

    private fun assistant(minute: Int, vararg parts: UIMessagePart = arrayOf(UIMessagePart.Text("reply $minute"))) =
        UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList(), createdAt = minute(minute))

    private fun tool(name: String, input: String = "{}", output: String? = "{}") = UIMessagePart.Tool(
        toolCallId = name,
        toolName = name,
        input = input,
        output = listOfNotNull(output?.let { UIMessagePart.Text(it) }),
    )

    private fun pending(messages: List<UIMessage>, consolidatedAt: Long, updatedAt: Long = 0) =
        pendingMemoryTurns(messages, updatedAt = updatedAt, consolidatedAt = consolidatedAt, timeZone = zone)

    @Test
    fun `only turns whose user message is newer than the mark are pending`() {
        val messages = listOf(user(1), assistant(2), user(3), assistant(4), user(5), assistant(6))

        val result = pending(messages, consolidatedAt = timeOf(2), updatedAt = timeOf(7))

        assertEquals(listOf(2, 4), result.turns.map { it.position })
        assertEquals(listOf("user 3", "reply 4"), result.turns.first().messages.map { it.value.toText() })
        assertEquals(timeOf(7), result.consolidatedThrough)
    }

    @Test
    fun `a regenerated reply does not make its turn pending again`() {
        val messages = listOf(user(1), assistant(9))

        val result = pending(messages, consolidatedAt = timeOf(5))

        assertTrue(result.turns.isEmpty())
        assertEquals(timeOf(9), result.consolidatedThrough)
    }

    @Test
    fun `an unfinished last turn is left for next time`() {
        val waitingForApproval = assistant(6, tool("workspace_shell", output = null))

        listOf(
            listOf(user(1), assistant(2), user(3), assistant(4), user(5)),
            listOf(user(1), assistant(2), user(3), assistant(4), user(5), waitingForApproval),
        ).forEach { messages ->
            val result = pending(messages, consolidatedAt = timeOf(2), updatedAt = timeOf(7))

            assertEquals(listOf(2), result.turns.map { it.position })
            // 进度停在没结束的那一轮前面，它下次还会被找出来
            assertEquals(timeOf(5) - 1, result.consolidatedThrough)
        }
    }

    @Test
    fun `nothing advances while the only new turn is unfinished`() {
        val result = pending(listOf(user(1), assistant(2), user(3)), consolidatedAt = timeOf(3) - 1)

        assertTrue(result.turns.isEmpty())
        assertNull(result.consolidatedThrough)
    }

    @Test
    fun `turns without user text and compression checkpoints are skipped`() {
        val imageOnly = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Image(url = "file:///a.png")),
            createdAt = minute(1),
        )
        val checkpoint = user(3, "summary of earlier messages").copy(isContextCheckpoint = true)
        val messages = listOf(imageOnly, assistant(2), checkpoint, user(4), assistant(5))

        val result = pending(messages, consolidatedAt = 0)

        assertEquals(listOf(3), result.turns.map { it.position })
        assertEquals(listOf("user 4", "reply 5"), result.turns.single().messages.map { it.value.toText() })
    }

    // 15 轮对话，第 n 轮的用户消息在第 2n-1 分钟
    private val longChat = (1..15).flatMap { turn -> listOf(user(turn * 2 - 1), assistant(turn * 2)) }

    @Test
    fun `a backlog larger than one batch is worked through oldest first without skipping turns`() {
        val first = pending(longChat, consolidatedAt = 0, updatedAt = timeOf(31))

        assertEquals(15, first.totalTurns)
        assertEquals((0 until 12).map { it * 2 }, first.turns.map { it.position })
        // 进度停在第 13 轮前面，没交给模型的 3 轮还在
        assertEquals(timeOf(25) - 1, first.consolidatedThrough)

        val second = pending(longChat, consolidatedAt = first.consolidatedThrough!!, updatedAt = timeOf(31))

        assertEquals(listOf(24, 26, 28), second.turns.map { it.position })
        assertEquals(3, second.totalTurns)
        assertEquals(timeOf(31), second.consolidatedThrough)
    }

    @Test
    fun `a conversation seen for the first time only looks back one batch`() {
        val start = initialMemoryProgress(longChat, zone)

        // 最近 12 轮从第 4 轮开始
        assertEquals(timeOf(7) - 1, start)
        assertEquals(12, pending(longChat, consolidatedAt = start).totalTurns)
        assertEquals(0L, initialMemoryProgress(emptyList(), zone))
    }

    @Test
    fun `turns sharing one timestamp still move progress forward`() {
        // 导入的对话可能每条消息都是同一个时间
        val imported = (1..14).flatMap { listOf(user(5, "question $it"), assistant(5)) }
        val start = initialMemoryProgress(imported, zone)

        val first = pending(imported, consolidatedAt = start)

        assertEquals(12, first.turns.size)
        assertEquals(timeOf(5), first.consolidatedThrough)
        assertTrue(pending(imported, consolidatedAt = first.consolidatedThrough!!).turns.isEmpty())
    }

    @Test
    fun `candidates without new turns do not use up the scan limit`() = runBlocking {
        val checked = mutableListOf<Int>()
        // 前 8 个候选都没有新轮次，后面才是真正待整理的
        val found = collectPending(candidates = (1..20).toList(), limit = 2) { candidate ->
            checked += candidate
            if (candidate > 8) PendingMemoryConversation(Uuid.random(), "memory", turns = candidate) else null
        }

        assertEquals(listOf(9, 10), found.map { it.turns })
        // 凑够就停，不把剩下的候选都读一遍
        assertEquals((1..10).toList(), checked)
    }

    @Test
    fun `transcript numbers messages, cuts long ones and marks memory writes the user asked for`() {
        val written = tool(
            name = MemoryToolNames.STR_REPLACE,
            output = """{"path":"/topics/food.md","version":"abc"}""",
        )
        val rejected = tool(name = MemoryToolNames.APPEND, output = """{"error":"Version conflict"}""")
        val messages = listOf(
            user(1, "I no longer eat peanuts, remember that"),
            assistant(2, written, rejected, tool("search_web"), UIMessagePart.Text("Noted, and here is a long answer")),
        )

        val transcript = pending(messages, consolidatedAt = 0).turns.single().messages
            .toMemoryTranscript(userLength = 100, assistantLength = 10)

        assertEquals(
            """
                [USER #0]: I no longer eat peanuts, remember that

                [ASSISTANT #1]: Noted, and...

                [assistant updated memory: memory_str_replace /topics/food.md]
            """.trimIndent(),
            transcript,
        )
    }
}
