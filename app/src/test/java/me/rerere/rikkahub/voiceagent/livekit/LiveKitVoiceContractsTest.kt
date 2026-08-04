package me.rerere.rikkahub.voiceagent.livekit

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.voiceagent.hermesvoice.HermesVoiceApi
import me.rerere.rikkahub.voiceagent.hermesvoice.HermesVoiceCredentials
import me.rerere.rikkahub.voiceagent.hermesvoice.HermesVoiceHttpTransport
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveKitVoiceContractsTest {
    @Test
    fun `worker ready parser accepts the exact canonical hashed contract`() {
        val message = requireNotNull(parseLiveKitReadyMessage(CANONICAL_READY_JSON))

        assertEquals(1, message.version)
        assertEquals("voice-session-id", message.voiceSessionId)
        assertEquals("ready", message.kind)
        assertEquals("2026-07-25T00:00:00Z", message.observedAt)
        assertEquals(WORKER_EVENT_HASH, message.eventIdHash)
    }

    @Test
    fun `worker ready parser accepts valid canonical UTC-second timestamps`() {
        listOf(
            "2026-07-25T00:00:00Z",
            "2024-02-29T23:59:59Z",
        ).forEach { observedAt ->
            assertEquals(
                observedAt,
                parseLiveKitReadyMessage(readyJsonWithObservedAt(observedAt))?.observedAt,
            )
        }
    }

    @Test
    fun `worker ready parser rejects malformed and noncanonical timestamps`() {
        listOf(
            "not-a-time",
            "2026-07-25T00:00:00+00:00",
            "2026-07-25T00:00:00.000Z",
            "2026-02-30T00:00:00Z",
            "2026-07-25T00:00:00z",
            " 2026-07-25T00:00:00Z",
            "2016-12-31T23:59:60Z",
        ).forEach { observedAt ->
            assertNull(
                observedAt,
                parseLiveKitReadyMessage(readyJsonWithObservedAt(observedAt)),
            )
        }
    }

    @Test
    fun `worker ready parser rejects duplicate missing extra uppercase and malformed fields`() {
        listOf(
            CANONICAL_READY_JSON.replace(
                ",\"eventIdHash\":\"$WORKER_EVENT_HASH\"",
                "",
            ),
            CANONICAL_READY_JSON.replace(
                "\"eventIdHash\":",
                "\"eventIdHash\":\"$WORKER_EVENT_HASH\",\"eventIdHash\":",
            ),
            CANONICAL_READY_JSON.replace(
                "}",
                ",\"extra\":true}",
            ),
            CANONICAL_READY_JSON.replace(WORKER_EVENT_HASH, "sha256:" + "A".repeat(64)),
            CANONICAL_READY_JSON.replace(WORKER_EVENT_HASH, "sha256:abcd"),
            "{\"version\":1,\"voiceSessionId\":\"voice-session-id\"," +
                "\"observedAt\":\"2026-07-25T00:00:00Z\",\"kind\":\"ready\"," +
                "\"eventIdHash\":\"$WORKER_EVENT_HASH\"}",
            CANONICAL_READY_JSON.replace(",\"kind\":\"ready\"", " , \"kind\": \"ready\""),
        ).forEach { payload ->
            assertNull(payload, parseLiveKitReadyMessage(payload))
        }
    }

    @Test
    fun `create LiveKit session uses exact mobile request and parses response`() = runBlocking {
        var seenRequest: Request? = null
        val api = HermesVoiceApi(
            baseUrl = "https://hermes-voice.example.test/base",
            credentials = HermesVoiceCredentials(deviceApiKey = "device-api-key"),
            transport = transportFor { request ->
                seenRequest = request
                responseFor(
                    request,
                    """
                    {
                      "livekitUrl":"wss://project.livekit.cloud",
                      "participantToken":"participant-secret-token",
                      "roomName":"rikka-0123456789abcdef0123456789abcdef",
                      "voiceSessionId":"lvs_0123456789abcdef0123456789abcdef",
                      "mobileParticipantIdentity":"mobile-lvs_0123456789abcdef0123456789abcdef",
                      "agentParticipantIdentity":"agent-lvs_0123456789abcdef0123456789abcdef",
                      "dispatchId":"AD_123",
                      "expiresAt":"2026-07-20T02:00:00Z",
                      "correlationBinding":{
                        "ownerHash":"${hash('1')}",
                        "conversationHash":"${hash('2')}",
                        "voiceSessionHash":"${hash('3')}",
                        "roomHash":"${hash('4')}",
                        "traceHash":"${hash('5')}"
                      }
                    }
                    """.trimIndent(),
                )
            },
        )

        val result = api.createLiveKitSession(
            conversationId = "018f0000-0000-7000-8000-000000000001",
            traceId = "trace-1",
        )

        val request = requireNotNull(seenRequest)
        assertEquals("POST", request.method)
        assertEquals("/base/api/mobile/livekit/session", request.url.encodedPath)
        assertEquals("application/json; charset=utf-8", request.body?.contentType().toString())
        assertEquals("Bearer device-api-key", request.header("Authorization"))
        assertEquals(setOf("Authorization"), request.headers.names())
        assertEquals(
            "{\"conversationId\":\"018f0000-0000-7000-8000-000000000001\",\"traceId\":\"trace-1\"}",
            request.body.bodyToUtf8(),
        )
        assertEquals("wss://project.livekit.cloud", result.livekitUrl)
        assertEquals("participant-secret-token", result.participantToken)
        assertEquals("rikka-0123456789abcdef0123456789abcdef", result.roomName)
        assertEquals("lvs_0123456789abcdef0123456789abcdef", result.voiceSessionId)
        assertEquals("mobile-lvs_0123456789abcdef0123456789abcdef", result.mobileParticipantIdentity)
        assertEquals("agent-lvs_0123456789abcdef0123456789abcdef", result.agentParticipantIdentity)
        assertEquals("AD_123", result.dispatchId)
        assertEquals("2026-07-20T02:00:00Z", result.expiresAt)
        assertEquals(
            LiveKitSessionCorrelationBinding(
                ownerHash = hash('1'),
                conversationHash = hash('2'),
                voiceSessionHash = hash('3'),
                roomHash = hash('4'),
                traceHash = hash('5'),
            ),
            result.correlationBinding,
        )
    }

    @Test
    fun `LiveKit session details redact URL and participant token`() {
        val details = validDetails()

        val rendered = details.toString()

        assertFalse(rendered.contains(details.livekitUrl))
        assertFalse(rendered.contains(details.participantToken))
        assertTrue(rendered.contains("livekitUrl=[redacted]"))
        assertTrue(rendered.contains("participantToken=[redacted]"))
        assertTrue(rendered.contains(details.voiceSessionId))
    }

    @Test
    fun `LiveKit session request and response reject invalid identifiers`() {
        assertThrows(IllegalArgumentException::class.java) {
            LiveKitSessionRequest(conversationId = "conversation/id", traceId = "trace-1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            validDetails().copy(dispatchId = "dispatch id")
        }
    }

    @Test
    fun `LiveKit session binding requires five lowercase SHA-256 hashes`() {
        val valid = validDetails()
        listOf<(LiveKitSessionCorrelationBinding) -> LiveKitSessionCorrelationBinding>(
            { it.copy(ownerHash = "sha256:${"a".repeat(63)}") },
            { it.copy(conversationHash = "sha256:${"A".repeat(64)}") },
            { it.copy(voiceSessionHash = "sha256:${"g".repeat(64)}") },
            { it.copy(roomHash = "sha256:${"0".repeat(65)}") },
            { it.copy(traceHash = "${"0".repeat(64)}") },
        ).forEach { mutate ->
            assertThrows(IllegalArgumentException::class.java) {
                valid.copy(correlationBinding = mutate(valid.correlationBinding))
            }
        }
    }

    @Test
    fun `LiveKit session details require a secure websocket URL`() {
        listOf(
            "https://project.livekit.cloud",
            "ws://project.livekit.cloud",
            "project.livekit.cloud",
            "wss://user:secret@project.livekit.cloud",
            "wss://project.livekit.cloud/room",
            "wss://project.livekit.cloud?region=test",
            "wss://project.livekit.cloud#fragment",
        ).forEach { url ->
            assertThrows(IllegalArgumentException::class.java) {
                validDetails().copy(livekitUrl = url)
            }
        }
    }

    @Test
    fun `invalid LiveKit response is rejected without revealing URL or token`() {
        val api = HermesVoiceApi(
            baseUrl = "https://hermes-voice.example.test",
            credentials = HermesVoiceCredentials(deviceApiKey = "device-api-key"),
            transport = transportFor { request ->
                responseFor(
                    request,
                    """
                    {
                      "livekitUrl":"wss://private-project.livekit.cloud",
                      "participantToken":"participant-secret-token",
                      "roomName":"invalid room",
                      "voiceSessionId":"lvs_valid",
                      "mobileParticipantIdentity":"mobile-lvs_valid",
                      "agentParticipantIdentity":"agent-lvs_valid",
                      "dispatchId":"AD_123",
                      "expiresAt":"2026-07-20T02:00:00Z",
                      "correlationBinding":{
                        "ownerHash":"${hash('1')}",
                        "conversationHash":"${hash('2')}",
                        "voiceSessionHash":"${hash('3')}",
                        "roomHash":"${hash('4')}",
                        "traceHash":"${hash('5')}"
                      }
                    }
                    """.trimIndent(),
                )
            },
        )

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { api.createLiveKitSession("conversation-1", "trace-1") }
        }

        assertFalse(error.message.orEmpty().contains("private-project"))
        assertFalse(error.message.orEmpty().contains("participant-secret-token"))
        assertTrue(error.message.orEmpty().contains("[redacted]"))
    }

    private fun validDetails() = LiveKitSessionDetails(
        livekitUrl = "wss://project.livekit.cloud",
        participantToken = "participant-secret-token",
        roomName = "rikka-0123456789abcdef0123456789abcdef",
        voiceSessionId = "lvs_0123456789abcdef0123456789abcdef",
        mobileParticipantIdentity = "mobile-lvs_0123456789abcdef0123456789abcdef",
        agentParticipantIdentity = "agent-lvs_0123456789abcdef0123456789abcdef",
        dispatchId = "AD_123",
        expiresAt = "2026-07-20T02:00:00Z",
        correlationBinding = LiveKitSessionCorrelationBinding(
            ownerHash = hash('1'),
            conversationHash = hash('2'),
            voiceSessionHash = hash('3'),
            roomHash = hash('4'),
            traceHash = hash('5'),
        ),
    )
}

private const val WORKER_EVENT_HASH =
    "sha256:0000000000000000000000000000000000000000000000000000000000000000"
private const val CANONICAL_READY_JSON =
    "{\"version\":1,\"voiceSessionId\":\"voice-session-id\",\"kind\":\"ready\"," +
        "\"observedAt\":\"2026-07-25T00:00:00Z\",\"eventIdHash\":\"$WORKER_EVENT_HASH\"}"

private fun hash(character: Char): String = "sha256:" + character.toString().repeat(64)

private fun readyJsonWithObservedAt(observedAt: String): String =
    CANONICAL_READY_JSON.replace("2026-07-25T00:00:00Z", observedAt)

private fun transportFor(handler: (Request) -> Response): HermesVoiceHttpTransport =
    HermesVoiceHttpTransport(handler)

private fun responseFor(request: Request, body: String): Response =
    Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(body.toResponseBody())
        .build()

private fun okhttp3.RequestBody?.bodyToUtf8(): String {
    if (this == null) return ""
    val buffer = okio.Buffer()
    writeTo(buffer)
    return buffer.readUtf8()
}
