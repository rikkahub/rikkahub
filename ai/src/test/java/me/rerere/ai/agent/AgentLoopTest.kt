package me.rerere.ai.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AgentLoopTest {
    private val model = Model(modelId = "test-model")
    private val provider = FakeProvider()
    private val hooks = RecordingHooks(model)

    private fun request(
        messages: List<UIMessage> = listOf(UIMessage.user("hi")),
        tools: List<Tool> = emptyList(),
        stream: Boolean = true,
        maxSteps: Int = 256,
        retry: RetryPolicy = RetryPolicy.NONE,
    ) = AgentRequest(
        provider = provider,
        providerSetting = ProviderSetting.OpenAI(),
        messages = messages,
        tools = tools,
        stream = stream,
        maxSteps = maxSteps,
        retry = retry,
    )

    private suspend fun runToEnd(
        request: AgentRequest = request(),
        hooks: AgentHooks = this.hooks,
    ) = runAgent(request, hooks).toList()

    // 模拟会改内容的输出转换：给这一步新增的文本加后缀，之前步骤的文本不能被重复处理，也不能漏掉
    private val suffixHooks = object : AgentHooks by hooks {
        override suspend fun finishStep(message: UIMessage, newPartsFrom: Int) = message.copy(
            parts = message.parts.mapIndexed { index, part ->
                if (index >= newPartsFrom && part is UIMessagePart.Text) part.copy(text = part.text + "!") else part
            }
        )
    }

    @Test
    fun `reply without tool calls completes after one request`() = runTest {
        provider.stream {
            text("hello")
            finish()
        }

        val events = runToEnd()

        val finished = events.finished()
        assertEquals(AgentFinishReason.Completed, finished.reason)
        assertEquals(listOf("hello"), finished.message.texts())
        assertEquals(MessageRole.ASSISTANT, finished.message.role)
        assertEquals(model.id, finished.message.modelId)
        assertNotNull(finished.message.finishedAt)
        assertEquals(1, provider.requests.size)
        // 第一步还没有内容的助手消息不能发给模型
        assertEquals(listOf(MessageRole.USER), hooks.prepared.single().map { it.role })
        assertEquals(setOf(finished.message.id), events.messageIds())
    }

    @Test
    fun `tool results go back to the model inside the same assistant message`() = runTest {
        val received = mutableListOf<JsonElement>()
        val search = tool("search") { args ->
            received += args
            listOf(UIMessagePart.Text("result"))
        }
        provider.stream {
            toolCall("call-1", "search", """{"q":"kotlin"}""")
            finish()
        }
        provider.stream {
            text("answer")
            finish()
        }

        val finished = runToEnd(request(tools = listOf(search))).finished()

        assertEquals(AgentFinishReason.Completed, finished.reason)
        assertEquals("kotlin", received.single().jsonObject["q"]?.jsonPrimitive?.content)
        assertEquals("result", finished.message.tool("call-1").outputText())
        assertEquals(listOf("answer"), finished.message.texts())
        // 第二步发给模型的是带工具结果的同一条助手消息，工具声明来自 AgentRequest
        val second = provider.requests[1]
        assertEquals(finished.message.id, second.messages.last().id)
        assertTrue(second.messages.last().tool("call-1").isExecuted)
        assertEquals(listOf("search"), second.params.tools.map { it.name })
        // 第二步新生成的 part 从工具调用之后开始
        assertEquals(listOf(0, 1), hooks.newPartsFrom)
    }

    @Test
    fun `a tool needing approval holds back the whole batch`() = runTest {
        val executed = mutableListOf<String>()
        val tools = listOf(
            tool("write", needsApproval = true) {
                executed += "write"
                ok()
            },
            tool("read") {
                executed += "read"
                ok()
            },
        )
        provider.stream {
            toolCall("call-1", "write")
            toolCall("call-2", "read")
            finish()
        }

        val finished = runToEnd(request(tools = tools)).finished()

        assertEquals(AgentFinishReason.AwaitingApproval(setOf("call-1")), finished.reason)
        assertEquals(ToolApprovalState.Pending, finished.message.tool("call-1").approvalState)
        assertEquals(ToolApprovalState.Auto, finished.message.tool("call-2").approvalState)
        assertTrue(executed.isEmpty())
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun `resuming after approval runs every remaining tool in call order`() = runTest {
        val executed = mutableListOf<String>()
        val tools = listOf(
            tool("write", needsApproval = true) {
                executed += "write"
                ok()
            },
            tool("read") {
                executed += "read"
                ok()
            },
        )
        val user = UIMessage.user("hi")
        provider.stream {
            toolCall("call-1", "write")
            toolCall("call-2", "read")
            finish()
        }
        val waiting = runToEnd(request(messages = listOf(user), tools = tools)).finished().message

        provider.stream {
            text("done")
            finish()
        }
        val approved = waiting.withApproval("call-1", ToolApprovalState.Approved)
        val finished = runToEnd(request(messages = listOf(user, approved), tools = tools)).finished()

        assertEquals(AgentFinishReason.Completed, finished.reason)
        assertEquals(listOf("write", "read"), executed)
        assertEquals(waiting.id, finished.message.id)
        assertTrue(provider.requests[1].messages.last().getTools().all { it.isExecuted })
    }

    @Test
    fun `resuming re-checks approval for tools that have not been decided`() = runTest {
        var executed = false
        val tools = listOf(tool("shell", needsApproval = true) {
            executed = true
            ok()
        })
        val message = assistant(UIMessagePart.Tool("call-1", "shell", "{}"))

        val finished = runToEnd(request(messages = listOf(UIMessage.user("hi"), message), tools = tools)).finished()

        assertEquals(AgentFinishReason.AwaitingApproval(setOf("call-1")), finished.reason)
        assertFalse(executed)
        assertTrue(provider.requests.isEmpty())
    }

    @Test
    fun `denied and answered tools get outputs without executing`() = runTest {
        val executed = mutableListOf<String>()
        val tools = listOf(
            tool("ask_user", needsApproval = true) {
                executed += "ask_user"
                ok()
            },
            tool("delete", needsApproval = true) {
                executed += "delete"
                ok()
            },
        )
        val message = assistant(
            UIMessagePart.Tool("call-1", "ask_user", "{}", approvalState = ToolApprovalState.Answered("blue")),
            UIMessagePart.Tool("call-2", "delete", "{}", approvalState = ToolApprovalState.Denied("too risky")),
        )
        provider.stream {
            text("ok")
            finish()
        }

        val finished = runToEnd(request(messages = listOf(UIMessage.user("hi"), message), tools = tools)).finished()

        assertEquals(AgentFinishReason.Completed, finished.reason)
        assertEquals("blue", finished.message.tool("call-1").outputText())
        assertTrue(finished.message.tool("call-2").outputText().contains("too risky"))
        assertTrue(executed.isEmpty())
        // 没有真正运行的工具不计时
        assertNull(finished.message.tool("call-1").startedAt)
        assertNull(finished.message.tool("call-2").startedAt)
    }

    @Test
    fun `stopping during tool execution keeps results of tools that already ran`() = runTest {
        val secondStarted = CompletableDeferred<Unit>()
        val tools = listOf(
            tool("first") { listOf(UIMessagePart.Text("first done")) },
            tool("second") {
                secondStarted.complete(Unit)
                awaitCancellation()
            },
        )
        provider.stream {
            toolCall("call-1", "first")
            toolCall("call-2", "second")
            finish()
        }

        val events = mutableListOf<AgentEvent>()
        val job = launch { runAgent(request(tools = tools), hooks).toList(events) }
        secondStarted.await()
        job.cancelAndJoin()

        val last = (events.last() as AgentEvent.MessageUpdated).message
        assertEquals("first done", last.tool("call-1").outputText())
        assertNotNull(last.tool("call-1").startedAt)
        assertNotNull(last.tool("call-1").finishedAt)
        assertFalse(last.tool("call-2").isExecuted)
        // 开始执行时就写入了 startedAt，界面能实时计时
        assertNotNull(last.tool("call-2").startedAt)
        assertNull(last.tool("call-2").finishedAt)
    }

    @Test
    fun `tool failures are reported to the model and the run continues`() = runTest {
        val tools = listOf(tool("broken") { throw IllegalStateException("disk full") })
        provider.stream {
            toolCall("call-1", "broken")
            finish()
        }
        provider.stream {
            text("sorry")
            finish()
        }

        val finished = runToEnd(request(tools = tools)).finished()

        val output = finished.message.tool("call-1").outputText()
        assertTrue(output.contains("IllegalStateException"))
        assertTrue(output.contains("disk full"))
        assertEquals(AgentFinishReason.Completed, finished.reason)
    }

    @Test
    fun `invalid arguments and unknown tools are reported instead of executed`() = runTest {
        var executed = false
        val tools = listOf(tool("search") {
            executed = true
            ok()
        })
        provider.stream {
            toolCall("call-1", "search", "{bad")
            toolCall("call-2", "missing")
            finish()
        }
        provider.stream {
            text("ok")
            finish()
        }

        val message = runToEnd(request(tools = tools)).finished().message

        assertTrue(message.tool("call-1").outputText().contains("Invalid tool arguments JSON for search"))
        assertTrue(message.tool("call-2").outputText().contains("Tool missing not found"))
        assertFalse(executed)
    }

    @Test
    fun `a tool returning nothing still counts as executed and does not run again`() = runTest {
        var runs = 0
        val tools = listOf(tool("silent") {
            runs++
            emptyList()
        })
        provider.stream {
            toolCall("call-1", "silent")
            finish()
        }
        provider.stream {
            toolCall("call-2", "silent")
            finish()
        }
        provider.stream {
            text("done")
            finish()
        }

        val message = runToEnd(request(tools = tools)).finished().message

        assertEquals(2, runs)
        assertTrue(message.getTools().all { it.isExecuted })
    }

    @Test
    fun `step limit ends the run after the last request's tools are executed`() = runTest {
        provider.stream {
            toolCall("call-1", "search")
            finish()
        }

        val finished = runToEnd(request(tools = listOf(tool("search")), maxSteps = 1)).finished()

        assertEquals(AgentFinishReason.StepLimitReached, finished.reason)
        assertTrue(finished.message.tool("call-1").isExecuted)
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun `finishStep only has to process the parts produced by the current step`() = runTest {
        provider.stream {
            text("checking")
            toolCall("call-1", "search")
            finish()
        }
        provider.stream {
            text("done")
            finish()
        }

        val message = runToEnd(request(tools = listOf(tool("search"))), suffixHooks).finished().message

        assertEquals(listOf("checking!", "done!"), message.texts())
        // 下一步发给模型的是处理后的内容
        assertEquals(listOf("checking!"), provider.requests[1].messages.last().texts())
    }

    @Test
    fun `preview output is only emitted and never merged back into the step`() = runTest {
        val previewHooks = object : AgentHooks by hooks {
            override suspend fun previewStep(message: UIMessage, newPartsFrom: Int) = message.copy(
                parts = message.parts.map { part ->
                    if (part is UIMessagePart.Text) part.copy(text = part.text.uppercase()) else part
                }
            )
        }
        provider.stream {
            emit(StreamChunk.TextStart("text"))
            emit(StreamChunk.TextDelta("text", "hel"))
            emit(StreamChunk.TextDelta("text", "lo"))
            emit(StreamChunk.TextEnd("text"))
            finish()
        }

        val events = runToEnd(hooks = previewHooks)

        val previews = events.filterIsInstance<AgentEvent.MessageUpdated>().dropLast(1).map { it.message.texts() }
        assertTrue(listOf("HEL") in previews)
        assertTrue(listOf("HELLO") in previews)
        // 这一步结束时拿到的是原始内容，预览时的改动不会混进去
        assertEquals(listOf("hello"), events.finished().message.texts())
    }

    @Test
    fun `usage accumulates across steps while context tokens follow the last request`() = runTest {
        provider.stream {
            toolCall("call-1", "search")
            usage(100, 10)
            finish()
        }
        provider.stream {
            text("answer")
            usage(150, 20)
            finish()
        }

        val message = runToEnd(request(tools = listOf(tool("search")))).finished().message

        assertEquals(250, message.usage?.promptTokens)
        assertEquals(30, message.usage?.completionTokens)
        assertEquals(TokenUsage(promptTokens = 150, completionTokens = 20, totalTokens = 170), message.lastUsage)
        assertEquals(2, message.stepCount)
        assertNotNull(message.generationTimeMs)
    }

    @Test
    fun `stream retry starts the step over and keeps the message id`() = runTest {
        provider.stream {
            text("partial")
            throw IOException("connection reset")
        }
        provider.stream {
            text("full")
            finish()
        }

        val events = runToEnd(request(retry = RetryPolicy.NETWORK))

        val retrying = events.filterIsInstance<AgentEvent.Retrying>().single()
        assertEquals(1, retrying.attempt.retry)
        assertEquals(3, retrying.attempt.maxRetries)
        val finished = events.finished()
        assertEquals(listOf("full"), finished.message.texts())
        assertEquals(setOf(finished.message.id), events.messageIds())
        assertEquals(2, provider.requests.size)
    }

    @Test
    fun `failures in the collector are not retried as network errors`() = runTest {
        provider.stream {
            text("hello")
            finish()
        }

        val error = runCatching {
            runAgent(request(retry = RetryPolicy.NETWORK), hooks).collect { event ->
                if (event is AgentEvent.MessageUpdated) throw IOException("ui failed")
            }
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals("ui failed", error?.message)
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun `errors other than network failures are not retried`() = runTest {
        provider.stream { throw IllegalArgumentException("bad request") }

        val error = runCatching { runToEnd(request(retry = RetryPolicy.NETWORK)) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun `non-streaming steps merge into the same message`() = runTest {
        provider.result(
            assistant(UIMessagePart.Text("let me search"), UIMessagePart.Tool("call-1", "search", "{}")),
            usage = TokenUsage(promptTokens = 10, completionTokens = 2),
        )
        provider.result(
            UIMessage.assistant("answer"),
            usage = TokenUsage(promptTokens = 20, completionTokens = 3),
        )

        val events = runToEnd(request(tools = listOf(tool("search")), stream = false))

        val finished = events.finished()
        val message = finished.message
        assertEquals(AgentFinishReason.Completed, finished.reason)
        assertTrue(message.tool("call-1").isExecuted)
        assertEquals(listOf("let me search", "answer"), message.texts())
        assertEquals(30, message.usage?.promptTokens)
        assertEquals(TokenUsage(promptTokens = 20, completionTokens = 3), message.lastUsage)
        assertEquals(setOf(message.id), events.messageIds())
    }

    @Test
    fun `non-streaming text after a step that ended with text is still transformed`() = runTest {
        provider.result(assistant(UIMessagePart.Tool("call-1", "search", "{}"), UIMessagePart.Text("before")))
        provider.result(UIMessage.assistant("after"))

        val message = runToEnd(request(tools = listOf(tool("search")), stream = false), suffixHooks)
            .finished().message

        assertEquals(listOf("before!", "after!"), message.texts())
    }
}

/** 按调用顺序依次返回预设的响应，并记录每次请求 */
private class FakeProvider : Provider<ProviderSetting> {
    data class Request(val messages: List<UIMessage>, val params: TextGenerationParams)

    val requests = mutableListOf<Request>()
    private val streams = ArrayDeque<suspend FlowCollector<StreamChunk>.() -> Unit>()
    private val results = ArrayDeque<TextGenerationResult>()

    fun stream(block: suspend FlowCollector<StreamChunk>.() -> Unit) {
        streams += block
    }

    fun result(message: UIMessage, usage: TokenUsage? = null) {
        results += TextGenerationResult(id = "result", model = "test-model", message = message, usage = usage)
    }

    override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()

    override suspend fun generateText(
        providerSetting: ProviderSetting,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        requests += Request(messages, params)
        return results.removeFirst()
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> {
        requests += Request(messages, params)
        val block = streams.removeFirst()
        return flow { block() }
    }
}

private class RecordingHooks(private val model: Model) : AgentHooks {
    val prepared = mutableListOf<List<UIMessage>>()
    val newPartsFrom = mutableListOf<Int>()

    override suspend fun prepareStep(messages: List<UIMessage>): StepRequest {
        prepared += messages
        return StepRequest(messages, TextGenerationParams(model = model))
    }

    override suspend fun finishStep(message: UIMessage, newPartsFrom: Int): UIMessage {
        this.newPartsFrom += newPartsFrom
        return message
    }
}

private suspend fun FlowCollector<StreamChunk>.text(text: String, id: String = "text") {
    emit(StreamChunk.TextStart(id))
    emit(StreamChunk.TextDelta(id, text))
    emit(StreamChunk.TextEnd(id))
}

private suspend fun FlowCollector<StreamChunk>.toolCall(id: String, name: String, input: String = "{}") {
    emit(StreamChunk.ToolCallStart(id, name))
    emit(StreamChunk.ToolCallDelta(id, inputDelta = input))
    emit(StreamChunk.ToolCallEnd(id))
}

private suspend fun FlowCollector<StreamChunk>.usage(prompt: Int, completion: Int) {
    emit(StreamChunk.Usage(TokenUsage(promptTokens = prompt, completionTokens = completion)))
}

private suspend fun FlowCollector<StreamChunk>.finish() {
    emit(StreamChunk.Finish(finishReason = "stop"))
}

private fun tool(
    name: String,
    needsApproval: Boolean = false,
    execute: suspend (JsonElement) -> List<UIMessagePart> = { ok() },
) = Tool(name = name, description = name, needsApproval = { needsApproval }, execute = execute)

private fun ok() = listOf(UIMessagePart.Text("ok"))

private fun assistant(vararg parts: UIMessagePart) = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())

private fun UIMessage.tool(id: String) = getTools().single { it.toolCallId == id }

private fun UIMessage.texts() = parts.filterIsInstance<UIMessagePart.Text>().map { it.text }

private fun UIMessage.withApproval(id: String, state: ToolApprovalState) = copy(
    parts = parts.map { part ->
        if (part is UIMessagePart.Tool && part.toolCallId == id) part.copy(approvalState = state) else part
    }
)

private fun UIMessagePart.Tool.outputText() =
    output.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

private fun List<AgentEvent>.finished() = last() as AgentEvent.Finished

private fun List<AgentEvent>.messageIds() =
    filterIsInstance<AgentEvent.MessageUpdated>().map { it.message.id }.toSet()
