package me.rerere.baselineprofile

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * 兼容 OpenAI Chat Completions 的假接口，跑在生成器进程里，app 通过 127.0.0.1 访问。
 *
 * 需要先应用 `app/baselineprofile/mock-provider.patch`，让 app 的默认模型指向这里。
 * 流式请求返回思考过程 + Markdown 正文，正文末尾带递增的编号，方便生成器判断这一轮的回复已经渲染完。
 * 非流式请求是标题和建议生成，返回固定文本。
 */
class MockOpenAIServer(private val port: Int = PORT) {
    private val serverSocket = ServerSocket()
    private val replyCount = AtomicInteger(0)

    fun start() {
        serverSocket.reuseAddress = true
        // Android 上 getLoopbackAddress() 返回的是 ::1，app 连的是 127.0.0.1，必须显式绑定 IPv4
        serverSocket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
        thread(name = "mock-openai-accept", isDaemon = true) {
            while (!serverSocket.isClosed) {
                val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
                thread(name = "mock-openai-conn", isDaemon = true) { socket.use(::handle) }
            }
        }
    }

    fun stop() {
        serverSocket.close()
    }

    private fun handle(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = input.readLine() ?: return
        var contentLength = 0
        while (true) {
            val line = input.readLine() ?: return
            if (line.isEmpty()) break
            val (name, value) = line.split(":", limit = 2).map { it.trim() }.let { it[0] to it.getOrElse(1) { "" } }
            if (name.equals("Content-Length", ignoreCase = true)) contentLength = value.toInt()
        }
        val body = ByteArray(contentLength).also { DataInputStream(input).readFully(it) }.toString(Charsets.UTF_8)
        val output = socket.getOutputStream()

        if (!requestLine.contains("/chat/completions")) {
            output.writeResponse(404, "application/json", """{"error":{"message":"not found"}}""")
            return
        }
        val request = JSONObject(body)
        if (request.optBoolean("stream")) {
            streamReply(output, replyCount.incrementAndGet())
        } else {
            val content = if (body.contains("suggestions")) SUGGESTIONS else TITLE
            output.writeResponse(200, "application/json", completion(content).toString())
        }
    }

    private fun streamReply(output: OutputStream, index: Int) {
        output.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\n" +
                "Connection: close\r\n\r\n").toByteArray()
        )
        fun send(delta: JSONObject, finishReason: String? = null) {
            val chunk = JSONObject()
                .put("id", "chatcmpl-mock-$index")
                .put("object", "chat.completion.chunk")
                .put("model", "mock-model")
                .put(
                    "choices", JSONArray().put(
                        JSONObject()
                            .put("index", 0)
                            .put("delta", delta)
                            .put("finish_reason", finishReason ?: JSONObject.NULL)
                    )
                )
            output.write("data: $chunk\n\n".toByteArray())
            output.flush()
            Thread.sleep(CHUNK_DELAY_MS)
        }

        REASONING.chunked(CHUNK_SIZE).forEach { send(JSONObject().put("reasoning_content", it)) }
        "$REPLY\n\n$REPLY_MARKER $index".chunked(CHUNK_SIZE).forEach { send(JSONObject().put("content", it)) }
        send(JSONObject(), finishReason = "stop")
        val usage = JSONObject()
            .put("id", "chatcmpl-mock-$index")
            .put("object", "chat.completion.chunk")
            .put("choices", JSONArray())
            .put("usage", JSONObject().put("prompt_tokens", 128).put("completion_tokens", 512).put("total_tokens", 640))
        output.write("data: $usage\n\ndata: [DONE]\n\n".toByteArray())
        output.flush()
    }

    private fun completion(content: String) = JSONObject()
        .put("id", "chatcmpl-mock")
        .put("object", "chat.completion")
        .put("model", "mock-model")
        .put(
            "choices", JSONArray().put(
                JSONObject()
                    .put("index", 0)
                    .put("message", JSONObject().put("role", "assistant").put("content", content))
                    .put("finish_reason", "stop")
            )
        )
        .put("usage", JSONObject().put("prompt_tokens", 64).put("completion_tokens", 8).put("total_tokens", 72))

    private fun OutputStream.writeResponse(code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray()
        write(
            ("HTTP/1.1 $code ${if (code == 200) "OK" else "Not Found"}\r\nContent-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()
        )
        write(bytes)
        flush()
    }

    private fun InputStream.readLine(): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = read()
            if (b == -1) return if (buffer.size() == 0) null else buffer.toString(Charsets.UTF_8.name())
            if (b == '\n'.code) return buffer.toString(Charsets.UTF_8.name()).trimEnd('\r')
            buffer.write(b)
        }
    }

    companion object {
        const val PORT = 18765
        const val REPLY_MARKER = "Baseline reply"

        private const val CHUNK_SIZE = 24
        private const val CHUNK_DELAY_MS = 20L

        private const val TITLE = "Hello RikkaHub"
        private const val SUGGESTIONS = "再举个例子\n解释一下代码\n换个写法"

        private val REASONING = """
            用户发来了一段 Markdown 示例，我需要用同样丰富的格式回复：标题、列表、表格、代码块和公式都要覆盖到。
            先给出简短说明，再分点列出要点，最后附上一段可以运行的代码。
        """.trimIndent()

        private val REPLY = """
            ## 收到你的消息

            这是一段由 **mock 接口** 返回的回复，用来覆盖 *流式渲染* 路径，包含 `行内代码`、~~删除线~~ 和[链接](https://github.com/rikkahub/rikkahub)。

            ### 要点

            1. 有序列表第一项
            2. 有序列表第二项
               - 嵌套的无序列表
               - [x] 已完成的任务
               - [ ] 未完成的任务

            > 引用块：Baseline Profile 能减少首次运行时的 JIT 开销。

            | 模式 | 说明 | 速度 |
            | --- | --- | --- |
            | None | 不预编译 | 慢 |
            | Partial | 按 Profile 预编译 | 快 |

            行内公式 ${'$'}E = mc^2${'$'}，以及块级公式：

            ${'$'}${'$'}
            \int_0^1 x^2 \, dx = \frac{1}{3}
            ${'$'}${'$'}

            ```kotlin
            data class Message(val role: String, val content: String)

            fun main() {
                val messages = listOf(Message("user", "Hello"), Message("assistant", "Hi"))
                messages.filter { it.role == "assistant" }.forEach { println(it.content) }
            }
            ```

            ```python
            def fib(n: int) -> int:
                return n if n < 2 else fib(n - 1) + fib(n - 2)

            print([fib(i) for i in range(10)])
            ```

            ---
        """.trimIndent()
    }
}
