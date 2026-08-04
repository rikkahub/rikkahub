package me.rerere.rikkahub.voiceagent.livekit

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveKitVoiceExperienceContractsTest {
    @Test
    fun `pinned persistence vocabulary is exact`() {
        val bytes = javaClass.classLoader
            ?.getResourceAsStream("voice-experience-ack-kinds-v1.json")
            ?.use { it.readBytes() }
            ?: error("ACK kind contract is missing")
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte ->
                byte.toInt().and(0xff).toString(radix = 16).padStart(length = 2, padChar = '0')
            }
        val contract = Json
            .parseToJsonElement(bytes.toString(Charsets.UTF_8))
            .jsonObject
        val kinds = requireNotNull(contract["kinds"]).jsonArray.map { element ->
            element.jsonPrimitive.content
        }

        assertEquals(
            "dbac96c9160b855c2f37d5b618e9b513" +
                "4ef70a3717c55ee593e5965440bedba0",
            hash,
        )
        assertEquals(setOf("kinds", "schemaVersion"), contract.keys)
        assertEquals(
            1,
            requireNotNull(contract["schemaVersion"]).jsonPrimitive.int,
        )
        assertEquals(kinds.size, kinds.toSet().size)
        kinds.forEach { kind ->
            assertEquals(
                kind,
                requireNotNull(
                    parseLiveKitVoiceExperienceEvent(ackEventJson(kind)),
                ) { kind }.kind,
            )
        }
        listOf("job_cancel_requested", "job_cancelled").forEach { removed ->
            assertNull(
                removed,
                parseLiveKitVoiceExperienceEvent(jobStateJson(kind = removed)),
            )
        }
    }

    @Test
    fun `accepted event requires exact session job call and request hash`() {
        val event = parseLiveKitVoiceExperienceEvent(acceptedEventJson())

        assertEquals("hj_1", (event as LiveKitVoiceExperienceEvent.JobAccepted).jobId)
        assertEquals(acceptedEventJson(), event.canonicalJson())
        assertNull(parseLiveKitVoiceExperienceEvent("""{"version":1,"kind":"job_accepted"}"""))
    }

    @Test
    fun `every job event rejects each missing correlation field and an unknown field`() {
        val exactEvents = listOf(
            acceptedEventJson(),
            jobStateJson(kind = "job_running"),
            jobStateJson(kind = "still_working"),
            succeededEventJson(answer = "answer", resultHash = voiceSha256("answer")),
            jobStateJson(kind = "job_failed", suffix = ""","failureReason":"safe failure""""),
            jobStateJson(kind = "job_expired", suffix = ""","failureReason":"safe expiration""""),
            jobStateJson(kind = "job_canceled", suffix = ""","failureReason":"safe cancellation""""),
        )

        exactEvents.forEach { payload ->
            val event = requireNotNull(parseLiveKitVoiceExperienceEvent(payload)) { payload }
            val parsedCorrelation = when (event) {
                is LiveKitVoiceExperienceEvent.JobAccepted -> event.correlation()
                is LiveKitVoiceExperienceEvent.JobState -> event.correlation()
                else -> error("Expected a job event")
            }
            assertEquals(event.kind, correlation, parsedCorrelation)

            correlationFields.forEach { field ->
                assertNull(
                    "${event.kind} accepted a payload missing ${field.substringBefore(':')}",
                    parseLiveKitVoiceExperienceEvent(payload.replace(",${field}", "")),
                )
            }
            assertNull(
                "${event.kind} accepted an unknown field",
                parseLiveKitVoiceExperienceEvent(payload.dropLast(1) + ""","unknown":true}"""),
            )
        }
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
    fun `follow up correlation accepts only exact turn assistant and result identifiers`() {
        val canonical = followUpCorrelationJson()
        val event = parseLiveKitVoiceExperienceEvent(canonical)

        event as LiveKitVoiceExperienceEvent.FollowUpCorrelation
        assertEquals("turn_2", event.followUpTurnId)
        assertEquals("assistant_2", event.assistantTurnId)
        assertEquals("sha256:${"3".repeat(64)}", event.resultHash)
        assertEquals(canonical, event.canonicalJson())

        listOf(
            canonical.replace("\"turn_2\"", "\"bad turn\""),
            canonical.replace("\"assistant_2\"", "\"bad/assistant\""),
            canonical.replace("sha256:${"3".repeat(64)}", "sha256:abcd"),
            canonical.replace("}", ""","transcript":"private surrounding text"}"""),
            canonical.replace("}", ""","marker":"VOICE-E2E-MARKER-42"}"""),
        ).forEach { payload ->
            assertNull(payload, parseLiveKitVoiceExperienceEvent(payload))
        }
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
            """{"eventId":"evt_1","persistedAt":"2026-07-30T12:00:01Z","status":"persisted","version":1,"voiceSessionId":"lvs_1"}""",
            payload,
        )
        assertEquals(ack, parseLiveKitPersistenceAck(payload))
        assertNull(parseLiveKitPersistenceAck(payload.replace("\"persisted\"", "\"queued\"")))
        assertNull(parseLiveKitPersistenceAck(payload.replace(",\"status\"", ", \"status\"")))
    }
}

private fun acceptedEventJson(): String =
    canonicalJson("""{"version":1,"voiceSessionId":"lvs_1","eventId":"evt_1","kind":"job_accepted","observedAt":"2026-07-30T12:00:00Z","userTurnId":"turn_1","requestHash":"sha256:${"2".repeat(64)}","toolCallId":"call_1","argumentHash":"sha256:${"1".repeat(64)}","jobId":"hj_1"${correlationJson()},"prompt":"private question"}""")

private fun ackEventJson(kind: String): String = when (kind) {
    "job_accepted" -> acceptedEventJson()
    "job_running", "still_working" -> jobStateJson(kind = kind)
    "job_succeeded" ->
        succeededEventJson(
            answer = "answer",
            resultHash = voiceSha256("answer"),
        )
    "job_failed", "job_expired", "job_canceled" ->
        jobStateJson(kind = kind, suffix = ""","failureReason":"safe failure"""")
    "transcript" -> transcriptJson(role = "assistant", interrupted = false)
    "follow_up_correlation" -> followUpCorrelationJson()
    "delivery_eligible", "delivery_started", "speech_started" ->
        deliveryJson(kind = kind)
    "delivery_blocked" ->
        deliveryJson(
            kind = kind,
            assistantTurn = ""","userSpeaking":true,"agentSpeaking":false""",
        )
    "delivery_announced" ->
        deliveryJson(kind = kind, assistantTurn = ""","assistantTurnId":"assistant_1"""")
    else -> error("Unknown pinned ACK kind: $kind")
}

private fun succeededEventJson(answer: String, resultHash: String): String =
    jobStateJson(
        kind = "job_succeeded",
        suffix = ""","resultHash":"$resultHash","answer":"$answer"""",
    )

private fun jobStateJson(kind: String, suffix: String = ""): String =
    canonicalJson("""{"version":1,"voiceSessionId":"lvs_1","eventId":"evt_state","kind":"$kind","observedAt":"2026-07-30T12:00:02Z","userTurnId":"turn_1","requestHash":"sha256:${"2".repeat(64)}","toolCallId":"call_1","argumentHash":"sha256:${"1".repeat(64)}","jobId":"hj_1"${correlationJson()}$suffix}""")

private fun transcriptJson(
    role: String,
    interrupted: Boolean,
    grounding: String = "",
): String =
    canonicalJson("""{"version":1,"voiceSessionId":"lvs_1","eventId":"evt_transcript","kind":"transcript","observedAt":"2026-07-30T12:00:03Z","turnId":"turn_1","role":"$role","text":"spoken words","interrupted":$interrupted$grounding}""")

private fun deliveryJson(kind: String, assistantTurn: String = ""): String =
    canonicalJson("""{"version":1,"voiceSessionId":"lvs_1","eventId":"evt_delivery","kind":"$kind","observedAt":"2026-07-30T12:00:04Z","toolCallId":"call_1","jobId":"hj_1"$assistantTurn}""")

private fun followUpCorrelationJson(): String =
    canonicalJson("""{"version":1,"voiceSessionId":"lvs_1","eventId":"evt_follow_up","kind":"follow_up_correlation","observedAt":"2026-07-30T12:00:05Z","followUpTurnId":"turn_2","assistantTurnId":"assistant_2","resultHash":"sha256:${"3".repeat(64)}"}""")

private fun canonicalJson(payload: String): String =
    CanonicalVoiceExperienceJson.encodeObject(Json.parseToJsonElement(payload).jsonObject)

private val correlation = LiveKitJobCorrelation(
    ownerHash = hash('1'),
    conversationHash = hash('2'),
    voiceSessionHash = voiceSha256("lvs_1"),
    roomHash = hash('3'),
    traceHash = hash('4'),
)

private val correlationFields = listOf(
    "\"ownerHash\":\"${correlation.ownerHash}\"",
    "\"conversationHash\":\"${correlation.conversationHash}\"",
    "\"voiceSessionHash\":\"${correlation.voiceSessionHash}\"",
    "\"roomHash\":\"${correlation.roomHash}\"",
    "\"traceHash\":\"${correlation.traceHash}\"",
)

private fun correlationJson(): String = correlationFields.joinToString(separator = ",", prefix = ",")

private fun hash(character: Char): String = "sha256:" + character.toString().repeat(64)
