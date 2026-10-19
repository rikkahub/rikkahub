package me.rerere.common.http.sse

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
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

class RealEventSourceTest {
    private class Recorded(
        val events: List<Triple<String?, String?, String>>,
        val failure: Throwable?,
        val failureResponseBody: String?,
        val failureCode: Int?,
        val accept: String?,
    )

    private fun stream(
        body: String,
        contentType: MediaType?,
        code: Int = 200,
    ): Recorded {
        var accept: String? = null
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                accept = chain.request().header("Accept")
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_2)
                    .code(code)
                    .message("")
                    .body(body.toResponseBody(contentType))
                    .build()
            }
            .build()

        val events = mutableListOf<Triple<String?, String?, String>>()
        var failure: Throwable? = null
        var failureBody: String? = null
        var failureCode: Int? = null
        val done = CountDownLatch(1)
        client.newEventSource(
            Request.Builder().url("https://example.com/stream").build(),
            object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    events += Triple(id, type, data)
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    failure = t
                    failureCode = response?.code
                    // 各 Provider 在这里读响应体解析错误详情
                    failureBody = response?.body?.string()
                    done.countDown()
                }

                override fun onClosed(eventSource: EventSource) {
                    done.countDown()
                }
            },
        )
        assertTrue("stream did not finish", done.await(10, TimeUnit.SECONDS))
        return Recorded(events, failure, failureBody, failureCode, accept)
    }

    private val sse = "event: delta\ndata: {\"text\":\"hi\"}\n\nid: 2\ndata: line1\ndata: line2\n\n"

    @Test
    fun `parses event stream`() {
        val result = stream(sse, "text/event-stream".toMediaType())

        assertNull(result.failure)
        assertEquals(
            listOf(
                Triple(null, "delta", "{\"text\":\"hi\"}"),
                Triple("2", null, "line1\nline2"),
            ),
            result.events,
        )
    }

    @Test
    fun `adds accept header`() {
        assertEquals("text/event-stream", stream(sse, "text/event-stream".toMediaType()).accept)
    }

    @Test
    fun `treats missing content type as event stream`() {
        val result = stream(sse, contentType = null)

        assertNull(result.failure)
        assertEquals(2, result.events.size)
    }

    @Test
    fun `rejects other declared content type and keeps body readable`() {
        val result = stream("""{"error":{"message":"bad"}}""", "application/json".toMediaType())

        assertTrue(result.failure?.message.orEmpty().startsWith("Invalid content-type"))
        assertTrue(result.events.isEmpty())
        assertEquals("""{"error":{"message":"bad"}}""", result.failureResponseBody)
    }

    @Test
    fun `reports unsuccessful response with readable body`() {
        val result = stream("""{"error":"unauthorized"}""", "application/json".toMediaType(), code = 401)

        assertNull(result.failure)
        assertEquals(401, result.failureCode)
        assertEquals("""{"error":"unauthorized"}""", result.failureResponseBody)
    }
}
