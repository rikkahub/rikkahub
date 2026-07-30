package me.rerere.rikkahub.voiceagent.livekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveKitVoiceExperienceContractsTest {
    @Test
    fun `accepted event requires exact session job call and request hash`() {
        val event = parseLiveKitVoiceExperienceEvent(
            """{"version":1,"voiceSessionId":"lvs_1","eventId":"evt_1","kind":"job_accepted","observedAt":"2026-07-30T12:00:00Z","userTurnId":"turn_1","requestHash":"sha256:${"2".repeat(64)}","toolCallId":"call_1","argumentHash":"sha256:${"1".repeat(64)}","jobId":"hj_1","prompt":"private question"}"""
        )

        assertEquals("hj_1", (event as LiveKitVoiceExperienceEvent.JobAccepted).jobId)
        assertEquals(acceptedEventJson(), event.canonicalJson())
        assertNull(parseLiveKitVoiceExperienceEvent("""{"version":1,"kind":"job_accepted"}"""))
    }

    @Test
    fun `terminal success requires answer hash to match private answer`() {
        val answer = "answer"
        val valid = parseLiveKitVoiceExperienceEvent(
            succeededEventJson(answer = answer, resultHash = voiceSha256(answer))
        )

        assertEquals(answer, (valid as LiveKitVoiceExperienceEvent.JobState).answer)
        assertNull(
            parseLiveKitVoiceExperienceEvent(
                succeededEventJson(answer = answer, resultHash = "sha256:${"0".repeat(64)}")
            )
        )
    }

    @Test
    fun `terminal failure requires a safe reason and forbids success fields`() {
        val failed = parseLiveKitVoiceExperienceEvent(
            jobStateJson(
                kind = "job_failed",
                suffix = ""","failureReason":"Hermes request failed"""",
            )
        )

        assertEquals(
            "Hermes request failed",
            (failed as LiveKitVoiceExperienceEvent.JobState).failureReason,
        )
        assertNull(
            parseLiveKitVoiceExperienceEvent(
                jobStateJson(
                    kind = "job_failed",
                    suffix = ""","failureReason":"Hermes request failed","answer":"private answer"""",
                )
            )
        )
    }

    @Test
    fun `transcript accepts exact role interruption and optional grounding`() {
        val user = parseLiveKitVoiceExperienceEvent(
            transcriptJson(role = "user", interrupted = false)
        )
        val assistant = parseLiveKitVoiceExperienceEvent(
            transcriptJson(
                role = "assistant",
                interrupted = true,
                grounding = ""","groundedJobId":"hj_1","groundedResultHash":"sha256:${"3".repeat(64)}"""",
            )
        )

        assertEquals("turn_1", (user as LiveKitVoiceExperienceEvent.Transcript).turnId)
        assistant as LiveKitVoiceExperienceEvent.Transcript
        assertTrue(assistant.interrupted)
        assertEquals("hj_1", assistant.groundedJobId)
        assertNull(
            parseLiveKitVoiceExperienceEvent(
                transcriptJson(
                    role = "user",
                    interrupted = false,
                    grounding = ""","groundedJobId":"hj_1","groundedResultHash":"sha256:${"3".repeat(64)}"""",
                )
            )
        )
        assertNull(
            parseLiveKitVoiceExperienceEvent(
                transcriptJson(role = "user", interrupted = true)
            )
        )
    }

    @Test
    fun `delivery announcement accepts only the exact job call and assistant turn`() {
        val event = parseLiveKitVoiceExperienceEvent(
            deliveryJson(
                kind = "delivery_announced",
                assistantTurn = ""","assistantTurnId":"assistant_1"""",
            )
        )

        assertEquals(
            "assistant_1",
            (event as LiveKitVoiceExperienceEvent.Delivery).assistantTurnId,
        )
        assertNull(
            parseLiveKitVoiceExperienceEvent(
                deliveryJson(
                    kind = "delivery_announced",
                    assistantTurn = ""","assistantTurnId":"assistant_1","unexpected":true""",
                )
            )
        )
    }

    @Test
    fun `event parser rejects noncanonical JSON unsafe identifiers timestamps hashes and versions`() {
        val canonical = acceptedEventJson()
        listOf(
            canonical.replace("\"version\":1", "\"version\":2"),
            canonical.replace("\"voiceSessionId\":\"lvs_1\"", "\"voiceSessionId\":\"bad session\""),
            canonical.replace("\"eventId\":\"evt_1\"", "\"eventId\":\"evt/1\""),
            canonical.replace("\"observedAt\":\"2026-07-30T12:00:00Z\"", "\"observedAt\":\"2026-07-30T12:00:00+00:00\""),
            canonical.replace("sha256:${"2".repeat(64)}", "sha256:${"A".repeat(64)}"),
            canonical.replace("sha256:${"1".repeat(64)}", "sha256:abcd"),
            canonical.replace(",\"kind\":\"job_accepted\"", ", \"kind\": \"job_accepted\""),
            canonical.replace("}", ""","extra":true}"""),
            canonical.replace(
                "\"eventId\":\"evt_1\"",
                "\"eventId\":\"evt_1\",\"eventId\":\"evt_2\"",
            ),
        ).forEach { payload ->
            assertNull(payload, parseLiveKitVoiceExperienceEvent(payload))
        }
    }

    @Test
    fun `persistence acknowledgement round trips only as canonical persisted v1 JSON`() {
        val ack = LiveKitPersistenceAck(
            version = 1,
            voiceSessionId = "lvs_1",
            eventId = "evt_1",
            status = "persisted",
            persistedAt = "2026-07-30T12:00:01Z",
        )
        val payload = ack.canonicalJson()

        assertEquals(
            """{"version":1,"voiceSessionId":"lvs_1","eventId":"evt_1","status":"persisted","persistedAt":"2026-07-30T12:00:01Z"}""",
            payload,
        )
        assertEquals(ack, parseLiveKitPersistenceAck(payload))
        assertNull(parseLiveKitPersistenceAck(payload.replace("\"persisted\"", "\"queued\"")))
        assertNull(parseLiveKitPersistenceAck(payload.replace(",\"eventId\"", ", \"eventId\"")))
    }
}

private fun acceptedEventJson(): String =
    """{"version":1,"voiceSessionId":"lvs_1","eventId":"evt_1","kind":"job_accepted","observedAt":"2026-07-30T12:00:00Z","userTurnId":"turn_1","requestHash":"sha256:${"2".repeat(64)}","toolCallId":"call_1","argumentHash":"sha256:${"1".repeat(64)}","jobId":"hj_1","prompt":"private question"}"""

private fun succeededEventJson(answer: String, resultHash: String): String =
    jobStateJson(
        kind = "job_succeeded",
        suffix = ""","resultHash":"$resultHash","answer":"$answer"""",
    )

private fun jobStateJson(kind: String, suffix: String = ""): String =
    """{"version":1,"voiceSessionId":"lvs_1","eventId":"evt_state","kind":"$kind","observedAt":"2026-07-30T12:00:02Z","userTurnId":"turn_1","requestHash":"sha256:${"2".repeat(64)}","toolCallId":"call_1","argumentHash":"sha256:${"1".repeat(64)}","jobId":"hj_1"$suffix}"""

private fun transcriptJson(
    role: String,
    interrupted: Boolean,
    grounding: String = "",
): String =
    """{"version":1,"voiceSessionId":"lvs_1","eventId":"evt_transcript","kind":"transcript","observedAt":"2026-07-30T12:00:03Z","turnId":"turn_1","role":"$role","text":"spoken words","interrupted":$interrupted$grounding}"""

private fun deliveryJson(kind: String, assistantTurn: String = ""): String =
    """{"version":1,"voiceSessionId":"lvs_1","eventId":"evt_delivery","kind":"$kind","observedAt":"2026-07-30T12:00:04Z","toolCallId":"call_1","jobId":"hj_1"$assistantTurn}"""
