package me.rerere.common.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds

class CallTimeoutBoundsBodyReadTest {

    // Regression for the post-header stall gap: invokeOnCancellation in await() only covers the
    // window while the coroutine is suspended waiting for headers. Once onResponse fired, the
    // provider's blocking body read (response.body.string()) is outside coroutine cancellation,
    // so a server that sends headers and then stalls the body used to hang until the shared
    // client's read-timeout ceiling (10 minutes in the app). The per-call timeout passed to
    // await() is OkHttp's call timeout, which spans the ENTIRE call including body reads.
    @Test
    fun `per-call timeout aborts a stalled body read`() {
        ServerSocket(0).use { server ->
            val stall = thread(isDaemon = true) {
                runCatching {
                    server.accept().use { socket ->
                        socket.getInputStream().read(ByteArray(8192))
                        socket.getOutputStream().run {
                            write(
                                ("HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: text/plain\r\n" +
                                    "Content-Length: 1000\r\n" +
                                    "\r\n" +
                                    "ab").toByteArray()
                            )
                            flush()
                        }
                        Thread.sleep(30_000)
                    }
                }
            }
            // readTimeout 0 mimics the app's effectively-unbounded shared client.
            val client = OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
            val request = Request.Builder().url("http://127.0.0.1:${server.localPort}/").build()

            val startNs = System.nanoTime()
            try {
                runBlocking {
                    val response = client.newCall(request).await(callTimeout = 500.milliseconds)
                    withContext(Dispatchers.IO) { response.body?.string() }
                }
                fail("stalled body read must be aborted by the per-call timeout")
            } catch (expected: IOException) {
                // canceled/stream-reset — any bounded IO failure is the correct outcome
            }
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
            assertTrue("expected bounded failure, took ${elapsedMs}ms", elapsedMs < 10_000)
            stall.interrupt()
        }
    }
}
