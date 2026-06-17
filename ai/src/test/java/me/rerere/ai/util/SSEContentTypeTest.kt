package me.rerere.ai.util

import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the "Invalid content-type: null" failure on the ChatGPT/Codex Responses
 * backend. That endpoint returns a 200 SSE stream with NO `Content-Type` header, so OkHttp's
 * `ResponseBody.contentType()` is null. The old rule (`contentType() ?: return false`) rejected the
 * valid stream; since the check only runs after `Response.isSuccessful`, an absent content-type must
 * be accepted as a stream while a present non-SSE type is still rejected.
 */
class SSEContentTypeTest {

    @Test
    fun absentContentType_isTreatedAsEventStream() {
        // The ChatGPT/Codex Responses 200 omits Content-Type — this is the case that used to throw.
        assertTrue(isEventStreamContentType(null))
    }

    @Test
    fun eventStreamContentType_isAccepted() {
        assertTrue(isEventStreamContentType("text/event-stream".toMediaType()))
    }

    @Test
    fun eventStreamWithCharsetParam_isAccepted() {
        assertTrue(isEventStreamContentType("text/event-stream; charset=utf-8".toMediaType()))
    }

    @Test
    fun jsonContentType_isRejected() {
        // A present, non-SSE type (e.g. an error body returned with 200) must still be rejected.
        assertFalse(isEventStreamContentType("application/json".toMediaType()))
    }

    @Test
    fun htmlContentType_isRejected() {
        // e.g. a Cloudflare/anti-bot HTML challenge page must not be read as a stream.
        assertFalse(isEventStreamContentType("text/html; charset=UTF-8".toMediaType()))
    }
}
