package me.rerere.common.http

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.internal.closeQuietly
import okio.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration

/**
 * [callTimeout] is applied as OkHttp's per-call timeout, which spans the ENTIRE call — including
 * the caller's later blocking body reads. That is the only bound that covers a server stalling
 * the body after headers: invokeOnCancellation below can only cancel while the coroutine is
 * still suspended here, and coroutine cancellation cannot interrupt a blocking
 * `response.body.string()` afterwards.
 */
suspend fun Call.await(callTimeout: Duration? = null): Response {
    callTimeout?.let { timeout().timeout(it.inWholeMilliseconds, TimeUnit.MILLISECONDS) }
    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { cause, _, _ ->
                    response.closeQuietly()
                }
            }
        })
    }
}
