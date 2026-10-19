package me.rerere.ai.provider.providers.chatgpt

import me.rerere.ai.provider.providers.openai.ResponseApiStreamDecoder
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.StreamChunk
import me.rerere.common.http.sse.newEventSource
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Codex 后端的流式响应不带 Content-Type，回放真实响应验证能正常解析 */
class CodexEventStreamTest {
    // 摘自真实的 /backend-api/codex/responses 响应（省略了 response 对象里的 tools）
    private val codexStream = """
        event: response.created
        data: {"type":"response.created","sequence_number":0,"response":{"id":"resp_1","object":"response","status":"in_progress","model":"gpt-6.1-sol","output":[]}}

        event: response.output_item.added
        data: {"type":"response.output_item.added","sequence_number":2,"output_index":0,"item":{"type":"message","id":"msg_1","role":"assistant","status":"in_progress","content":[],"phase":"final_answer"}}

        event: response.content_part.added
        data: {"type":"response.content_part.added","sequence_number":3,"item_id":"msg_1","output_index":0,"content_index":0,"part":{"type":"output_text","text":"","annotations":[],"logprobs":[]}}

        event: response.output_text.delta
        data: {"type":"response.output_text.delta","sequence_number":4,"item_id":"msg_1","output_index":0,"content_index":0,"delta":"Hey","logprobs":[],"obfuscation":"vuYFpIPYC1HLb"}

        event: response.output_text.delta
        data: {"type":"response.output_text.delta","sequence_number":5,"item_id":"msg_1","output_index":0,"content_index":0,"delta":"！有什么我可以帮你的？","logprobs":[],"obfuscation":"8RxaghPw9oUjku0"}

        event: response.metadata
        data: {"type":"response.metadata","sequence_number":12,"response_id":"resp_1","metadata":{}}

        event: response.output_text.done
        data: {"type":"response.output_text.done","sequence_number":13,"item_id":"msg_1","output_index":0,"content_index":0,"text":"Hey！有什么我可以帮你的？","logprobs":[]}

        event: response.content_part.done
        data: {"type":"response.content_part.done","sequence_number":14,"item_id":"msg_1","output_index":0,"content_index":0,"part":{"type":"output_text","text":"Hey！有什么我可以帮你的？","annotations":[],"logprobs":[]}}

        event: response.output_item.done
        data: {"type":"response.output_item.done","sequence_number":15,"output_index":0,"item":{"type":"message","id":"msg_1","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Hey！有什么我可以帮你的？","annotations":[],"logprobs":[]}],"phase":"final_answer"}}

        event: response.completed
        data: {"type":"response.completed","sequence_number":16,"response":{"id":"resp_1","object":"response","status":"completed","model":"gpt-6.1-sol","output":[{"type":"message","id":"msg_1","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Hey！有什么我可以帮你的？","annotations":[],"logprobs":[]}],"phase":"final_answer"}],"usage":{"input_tokens":10,"output_tokens":8,"total_tokens":18}}}


    """.trimIndent()

    // 假后端：和真实响应一样，200 但没有 Content-Type
    private val codexBackend = Interceptor { chain ->
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_2)
            .code(200)
            .message("")
            .body(codexStream.toResponseBody(null))
            .build()
    }

    private class Result(val text: String, val failure: Throwable?, val finish: StreamChunk.Finish?)

    private fun stream(client: OkHttpClient): Result {
        val decoder = ResponseApiStreamDecoder()
        val text = StringBuilder()
        var failure: Throwable? = null
        var finish: StreamChunk.Finish? = null
        val done = CountDownLatch(1)

        fun collect(chunks: Iterable<StreamChunk>) = chunks.forEach { chunk ->
            when (chunk) {
                is StreamChunk.TextDelta -> text.append(chunk.text)
                is StreamChunk.Finish -> finish = chunk
                else -> {}
            }
        }

        client.newEventSource(
            Request.Builder().url("$CODEX_BASE_URL/responses").build(),
            object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    collect(decoder.accept(SseEvent(id = id, event = type, data = data)).chunks)
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    failure = t ?: IllegalStateException("failed with ${response?.code}")
                    done.countDown()
                }

                override fun onClosed(eventSource: EventSource) {
                    collect(decoder.onClosed())
                    done.countDown()
                }
            },
        )
        assertTrue("stream did not finish", done.await(10, TimeUnit.SECONDS))
        return Result(text.toString(), failure, finish)
    }

    @Test
    fun `codex stream without content type is parsed`() {
        val client = OkHttpClient.Builder().addInterceptor(codexBackend).build()

        val result = stream(client)

        assertNull(result.failure)
        assertEquals("Hey！有什么我可以帮你的？", result.text)
        assertEquals("resp_1", result.finish?.responseId)
    }
}
