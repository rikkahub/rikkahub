package me.rerere.rikkahub.voiceagent.hermes

import org.junit.Assert.assertEquals
import org.junit.Test

class HermesQueueEventTest {

    @Test
    fun `toJson emits all fields in fixed key order`() {
        val event = HermesQueueEvent(
            type = "job_completed",
            callId = "call-1",
            jobId = "job-9",
            status = "succeeded",
            elapsedMs = 1234L,
            serverElapsedMs = 987L,
            answerChars = 42,
            sent = true,
        )
        assertEquals(
            """{"type":"job_completed","callId":"call-1","jobId":"job-9","status":"succeeded",""" +
                """"elapsedMs":1234,"serverElapsedMs":987,"answerChars":42,"sent":true}""",
            event.toJson(),
        )
    }

    @Test
    fun `toJson omits null fields`() {
        val event = HermesQueueEvent(type = "job_created", callId = "call-1", jobId = "job-9", status = "queued")
        assertEquals(
            """{"type":"job_created","callId":"call-1","jobId":"job-9","status":"queued"}""",
            event.toJson(),
        )
    }

    @Test
    fun `toJson bridge-shaped event matches the old coordinator output`() {
        val event = HermesQueueEvent(type = "late_text_turn_sent", callId = "call-1", jobId = "none", sent = false)
        assertEquals(
            """{"type":"late_text_turn_sent","callId":"call-1","jobId":"none","sent":false}""",
            event.toJson(),
        )
    }

    @Test
    fun `toLogDetail formats present fields`() {
        val event = HermesQueueEvent(
            type = "job_failed", callId = "call-1", jobId = "none", status = "expired", sent = null,
        )
        assertEquals("type=job_failed callId=call-1 jobId=none status=expired sent=n/a", event.toLogDetail())
    }

    @Test
    fun `toLogDetail formats sent boolean`() {
        val event = HermesQueueEvent(type = "still_working_text_turn_sent", callId = "c", jobId = "none", sent = true)
        assertEquals("type=still_working_text_turn_sent callId=c jobId=none status=none sent=true", event.toLogDetail())
    }

    @Test
    fun `fully minimal event serializes three keys and default log detail`() {
        val event = HermesQueueEvent(type = "job_created", callId = "call-1", jobId = "job-9")
        assertEquals("""{"type":"job_created","callId":"call-1","jobId":"job-9"}""", event.toJson())
        assertEquals("type=job_created callId=call-1 jobId=job-9 status=none sent=n/a", event.toLogDetail())
    }
}
