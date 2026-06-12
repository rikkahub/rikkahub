package me.rerere.common.http

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.IOException
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.reflect.KClass

class RequestAwaitCancellationTest {

    // H1 regression: cancelling the awaiting coroutine must cancel the in-flight HTTP call.
    // Before the fix, await() registered no invokeOnCancellation, so the OkHttp call kept
    // running until the client's read timeout (10 min on the shared client).
    @Test
    fun `coroutine cancellation cancels the underlying call`() = runBlocking {
        val call = FakeCall()

        val job = launch {
            call.await()
        }
        while (call.enqueuedCallback == null) {
            yield()
        }

        job.cancelAndJoin()

        assertTrue("Call.cancel() must be invoked on coroutine cancellation", call.isCanceled())
    }

    @Test
    fun `success path resumes with the response`() = runBlocking {
        val call = FakeCall(onEnqueue = { c, callback ->
            callback.onResponse(c, fakeResponse(c.request(), code = 200))
        })

        val response = call.await()

        assertEquals(200, response.code)
        assertTrue(!call.isCanceled())
    }

    @Test
    fun `failure path resumes with the exception`() = runBlocking {
        val call = FakeCall(onEnqueue = { c, callback ->
            callback.onFailure(c, IOException("boom"))
        })

        try {
            call.await()
            fail("await() must rethrow the enqueue failure")
        } catch (e: IOException) {
            assertEquals("boom", e.message)
        }
    }

    private fun fakeResponse(request: Request, code: Int): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .body("".toResponseBody(null))
            .build()

    private class FakeCall(
        private val onEnqueue: ((Call, Callback) -> Unit)? = null,
    ) : Call {
        @Volatile
        var enqueuedCallback: Callback? = null

        @Volatile
        private var canceled = false

        private val request: Request = Request.Builder().url("http://localhost/").build()

        override fun request(): Request = request

        override fun execute(): Response = throw UnsupportedOperationException("fake")

        override fun enqueue(responseCallback: Callback) {
            enqueuedCallback = responseCallback
            onEnqueue?.invoke(this, responseCallback)
        }

        override fun cancel() {
            canceled = true
        }

        override fun isExecuted(): Boolean = enqueuedCallback != null

        override fun isCanceled(): Boolean = canceled

        override fun timeout(): Timeout = Timeout.NONE

        override fun <T : Any> tag(type: KClass<T>): T? = null

        override fun <T> tag(type: Class<out T>): T? = null

        override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T = computeIfAbsent()

        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()

        override fun clone(): Call = this
    }
}
