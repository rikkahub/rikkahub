package me.rerere.rikkahub.voiceagent.voicelab

import kotlinx.coroutines.runBlocking
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

class VoiceLabMobileApiTest {
    @Test
    fun `createSession sends mobile credentials and parses response`() = runBlocking {
        var seenRequest: Request? = null
        var seenBody = ""
        val transport = transportFor { request ->
            seenRequest = request
            seenBody = request.body.bodyToUtf8()
            responseFor(
                request = request,
                body = """
                {
                  "token":"tok",
                  "modelId":"gemini-flash",
                  "providerModel":"gemini-3.1-flash-live-preview",
                  "apiVersion":"v1alpha",
                  "websocketUrl":"wss://example.test/live",
                  "inputSampleRate":16000,
                  "outputSampleRate":24000,
                  "liveConnectConfig":{"responseModalities":["AUDIO"],"inputAudioTranscription":{},"outputAudioTranscription":{},"tools":[]}
                }
                """.trimIndent(),
            )
        }

        val api = VoiceLabMobileApi(
            baseUrl = "https://voice-lab.example.test",
            credentials = VoiceLabMobileCredentials(
                hermesProfileApiKey = "profile-api-key",
                cloudflareClientId = "cf-id",
                cloudflareClientSecret = "cf-secret",
            ),
            transport = transport,
        )

        val session = api.createSession("gemini-flash")

        val request = requireNotNull(seenRequest)
        assertEquals("POST", request.method)
        assertEquals("/api/mobile/voice/session", request.url.encodedPath)
        assertEquals("application/json; charset=utf-8", request.body?.contentType().toString())
        assertEquals("Bearer profile-api-key", request.header("Authorization"))
        assertEquals("cf-id", request.header("CF-Access-Client-Id"))
        assertEquals("cf-secret", request.header("CF-Access-Client-Secret"))
        assertTrue(seenBody.contains("\"modelId\":\"gemini-flash\""))
        assertEquals("tok", session.token)
        assertEquals(16000, session.inputSampleRate)
    }

    @Test
    fun `askHermes omits default profileId and parses response`() = runBlocking {
        var seenRequest: Request? = null
        var seenBody = ""
        val transport = transportFor { request ->
            seenRequest = request
            seenBody = request.body.bodyToUtf8()
            responseFor(
                request = request,
                body = """
                {
                  "callId":"call-1",
                  "answer":"done",
                  "model":"ms-agent",
                  "profileId":"default",
                  "profileLabel":"Default",
                  "elapsedMs":321
                }
                """.trimIndent(),
            )
        }

        val api = VoiceLabMobileApi(
            baseUrl = "https://voice-lab.example.test/base",
            credentials = VoiceLabMobileCredentials(hermesProfileApiKey = "profile-api-key"),
            transport = transport,
        )

        val response = api.askHermes(callId = "call-1", prompt = "status")

        val request = requireNotNull(seenRequest)
        assertEquals("/base/api/mobile/hermes", request.url.encodedPath)
        assertEquals("Bearer profile-api-key", request.header("Authorization"))
        assertNull(request.header("CF-Access-Client-Id"))
        assertNull(request.header("CF-Access-Client-Secret"))
        assertTrue(seenBody.contains("\"callId\":\"call-1\""))
        assertTrue(seenBody.contains("\"prompt\":\"status\""))
        assertFalse(seenBody.contains("profileId"))
        assertEquals("done", response.answer)
        assertEquals("default", response.profileId)
        assertEquals(321L, response.elapsedMs)
    }

    @Test
    fun `askHermes sends explicit profileId`() = runBlocking {
        var seenBody = ""
        val transport = transportFor { request ->
            seenBody = request.body.bodyToUtf8()
            responseFor(
                request = request,
                body = """
                {
                  "callId":"call-1",
                  "answer":"done",
                  "model":"ms-agent",
                  "profileId":"research",
                  "profileLabel":"Research"
                }
                """.trimIndent(),
            )
        }

        val api = VoiceLabMobileApi(
            baseUrl = "https://voice-lab.example.test",
            credentials = VoiceLabMobileCredentials(hermesProfileApiKey = "profile-api-key"),
            transport = transport,
        )

        api.askHermes(callId = "call-1", prompt = "status", profileId = "research")

        assertTrue(seenBody.contains("\"profileId\":\"research\""))
    }

    @Test
    fun `non successful responses include status and body`() {
        val transport = transportFor { request ->
            responseFor(
                request = request,
                code = 503,
                message = "Unavailable",
                body = """{"error":"down"}""",
            )
        }
        val api = VoiceLabMobileApi(
            baseUrl = "https://voice-lab.example.test",
            credentials = VoiceLabMobileCredentials(hermesProfileApiKey = "profile-api-key"),
            transport = transport,
        )

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { api.createSession("gemini-flash") }
        }

        assertTrue(error.message.orEmpty().contains("503"))
        assertTrue(error.message.orEmpty().contains("down"))
    }

    @Test
    fun `successful response decode failures keep prompt and answer visible while redacting tokens`() {
        val transport = transportFor { request ->
            responseFor(
                request = request,
                body = """
                {
                  "token":"decode-token",
                  "prompt":"decode-prompt",
                  "answer":"decode-answer"
                }
                """.trimIndent(),
            )
        }
        val api = VoiceLabMobileApi(
            baseUrl = "https://voice-lab.example.test",
            credentials = VoiceLabMobileCredentials(hermesProfileApiKey = "profile-api-key"),
            transport = transport,
        )

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { api.createSession("gemini-flash") }
        }

        val message = error.message.orEmpty()
        assertTrue(message.contains("Voice Lab response decode failed"))
        assertTrue(message.contains("[redacted]"))
        assertFalse(message.contains("decode-token"))
        assertTrue(message.contains("decode-prompt"))
        assertTrue(message.contains("decode-answer"))
    }

    @Test
    fun `non successful response bodies are bounded`() {
        val responseBody = "x".repeat(5000)
        val transport = transportFor { request ->
            responseFor(
                request = request,
                code = 500,
                message = "Error",
                body = responseBody,
            )
        }
        val api = VoiceLabMobileApi(
            baseUrl = "https://voice-lab.example.test",
            credentials = VoiceLabMobileCredentials(hermesProfileApiKey = "profile-api-key"),
            transport = transport,
        )

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { api.createSession("gemini-flash") }
        }

        val message = error.message.orEmpty()
        assertTrue(message.contains("500"))
        assertTrue(message.contains("[truncated]"))
        assertFalse(message.contains(responseBody))
    }

    @Test
    fun `non successful response previews keep prompt and answer visible while redacting credentials`() {
        val transport = transportFor { request ->
            responseFor(
                request = request,
                code = 500,
                message = "Error",
                body = """
                {
                  "error":"failed",
                  "key":"plain-key-field",
                  "token":"live-token",
                  "apiKey":"server-key",
                  "CF-Access-Client-Id":"cf-header-id",
                  "CF-Access-Client-Secret":"cf-header-secret",
                  "cloudflareClientId":"cf-config-id",
                  "cloudflareClientSecret":"cf-config-secret",
                  "hermesProfileApiKey":"hermes-profile-key",
                  "websocketUrl":"wss://signed.example.test/live?token=url-token",
                  "liveConnectConfig":{"sessionToken":"config-token"},
                  "prompt":"private prompt",
                  "answer":"private answer",
                  "nested":{"prompt":"before \"escaped-secret\" after"},
                  "embedded":"{\"prompt\":\"embedded-prompt\",\"token\":\"embedded-token\"}",
                  "single":"token='single-token'",
                  "unquoted":"api_key=plain-key",
                  "header":"Authorization: Bearer authorization-token",
                  "detail":"Bearer header-token"
                }
                """.trimIndent(),
            )
        }
        val api = VoiceLabMobileApi(
            baseUrl = "https://voice-lab.example.test",
            credentials = VoiceLabMobileCredentials(hermesProfileApiKey = "profile-api-key"),
            transport = transport,
        )

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { api.createSession("gemini-flash") }
        }

        val message = error.message.orEmpty()
        assertTrue(message.contains("failed"))
        assertTrue(message.contains("[redacted]"))
        assertFalse(message.contains("plain-key-field"))
        assertFalse(message.contains("live-token"))
        assertFalse(message.contains("server-key"))
        assertFalse(message.contains("cf-header-id"))
        assertFalse(message.contains("cf-header-secret"))
        assertFalse(message.contains("cf-config-id"))
        assertFalse(message.contains("cf-config-secret"))
        assertFalse(message.contains("hermes-profile-key"))
        assertFalse(message.contains("signed.example.test"))
        assertFalse(message.contains("url-token"))
        assertFalse(message.contains("config-token"))
        assertTrue(message.contains("private prompt"))
        assertTrue(message.contains("private answer"))
        assertTrue(message.contains("escaped-secret"))
        assertTrue(message.contains("embedded-prompt"))
        assertFalse(message.contains("embedded-token"))
        assertFalse(message.contains("single-token"))
        assertFalse(message.contains("plain-key"))
        assertFalse(message.contains("authorization-token"))
        assertFalse(message.contains("header-token"))
    }

    @Test
    fun `plain text response previews redact client credential names`() {
        val transport = transportFor { request ->
            responseFor(
                request = request,
                code = 500,
                message = "Error",
                body = """
                CF-Access-Client-Id: plain-cf-id
                CF-Access-Client-Secret: plain-cf-secret
                cloudflareClientId=plain-config-id
                cloudflareClientSecret=plain-config-secret
                clientId=plain-client-id
                clientSecret=plain-client-secret
                """.trimIndent(),
            )
        }
        val api = VoiceLabMobileApi(
            baseUrl = "https://voice-lab.example.test",
            credentials = VoiceLabMobileCredentials(hermesProfileApiKey = "profile-api-key"),
            transport = transport,
        )

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { api.createSession("gemini-flash") }
        }

        val message = error.message.orEmpty()
        assertTrue(message.contains("[redacted]"))
        assertFalse(message.contains("plain-cf-id"))
        assertFalse(message.contains("plain-cf-secret"))
        assertFalse(message.contains("plain-config-id"))
        assertFalse(message.contains("plain-config-secret"))
        assertFalse(message.contains("plain-client-id"))
        assertFalse(message.contains("plain-client-secret"))
    }

    @Test
    fun `decode failure previews redact session transport fields`() {
        val transport = transportFor { request ->
            responseFor(
                request = request,
                body = """
                {
                  "websocketUrl":"wss://signed.example.test/live?token=url-token",
                  "liveConnectConfig":{"sessionToken":"config-token"}
                }
                """.trimIndent(),
            )
        }
        val api = VoiceLabMobileApi(
            baseUrl = "https://voice-lab.example.test",
            credentials = VoiceLabMobileCredentials(hermesProfileApiKey = "profile-api-key"),
            transport = transport,
        )

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { api.createSession("gemini-flash") }
        }

        val message = error.message.orEmpty()
        assertTrue(message.contains("[redacted]"))
        assertFalse(message.contains("signed.example.test"))
        assertFalse(message.contains("url-token"))
        assertFalse(message.contains("config-token"))
    }

    @Test
    fun `truncated response previews redact unfinished quoted secrets`() {
        val secretPrefix = "secret-prefix"
        val transport = transportFor { request ->
            responseFor(
                request = request,
                code = 500,
                message = "Error",
                body = """{"token":"$secretPrefix${"x".repeat(5000)}""",
            )
        }
        val api = VoiceLabMobileApi(
            baseUrl = "https://voice-lab.example.test",
            credentials = VoiceLabMobileCredentials(hermesProfileApiKey = "profile-api-key"),
            transport = transport,
        )

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { api.createSession("gemini-flash") }
        }

        val message = error.message.orEmpty()
        assertTrue(message.contains("[redacted]"))
        assertFalse(message.contains(secretPrefix))
    }

    @Test
    fun `sensitive contract toString output is redacted`() {
        val session = MobileVoiceSessionResponse(
            token = "session-token",
            modelId = "gemini-flash",
            providerModel = "gemini-live",
            apiVersion = "v1alpha",
            websocketUrl = "wss://example.test/live",
            inputSampleRate = 16000,
            outputSampleRate = 24000,
            liveConnectConfig = kotlinx.serialization.json.JsonObject(emptyMap()),
        )
        val hermesRequest = MobileHermesRequest(
            callId = "call-1",
            prompt = "private prompt",
            profileId = "default",
        )
        val credentials = VoiceLabMobileCredentials(
            hermesProfileApiKey = "profile-api-key",
            cloudflareClientId = "cf-id",
            cloudflareClientSecret = "cf-secret",
        )
        val hermesResponse = MobileHermesResponse(
            callId = "call-1",
            answer = "private answer",
            model = "ms-agent",
            profileId = "default",
            profileLabel = "Default",
        )

        assertFalse(session.toString().contains("session-token"))
        assertFalse(session.toString().contains("wss://example.test/live"))
        assertTrue(session.toString().contains("liveConnectConfig=[redacted]"))
        assertFalse(hermesRequest.toString().contains("private prompt"))
        assertFalse(credentials.toString().contains("profile-api-key"))
        assertFalse(credentials.toString().contains("cf-id"))
        assertFalse(credentials.toString().contains("cf-secret"))
        assertFalse(hermesResponse.toString().contains("private answer"))
    }

    @Test
    fun `baseUrl must be https unless it is a local development host`() {
        val credentials = VoiceLabMobileCredentials(hermesProfileApiKey = "profile-api-key")

        assertThrows(IllegalArgumentException::class.java) {
            VoiceLabMobileApi(baseUrl = "http://voice-lab.example.test", credentials = credentials)
        }

        VoiceLabMobileApi(baseUrl = "http://127.0.0.1:8787", credentials = credentials)
        VoiceLabMobileApi(baseUrl = "http://10.0.2.2:8787", credentials = credentials)
        VoiceLabMobileApi(baseUrl = "http://100.83.49.15:8787", credentials = credentials)
    }

    @Test
    fun `baseUrl must not include query or fragment`() {
        val credentials = VoiceLabMobileCredentials(hermesProfileApiKey = "profile-api-key")

        assertThrows(IllegalArgumentException::class.java) {
            VoiceLabMobileApi(baseUrl = "https://voice-lab.example.test?x=1", credentials = credentials)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VoiceLabMobileApi(baseUrl = "https://voice-lab.example.test#voice", credentials = credentials)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VoiceLabMobileApi(baseUrl = "https://user:pass@voice-lab.example.test", credentials = credentials)
        }
    }

    @Test
    fun `credentials must not be blank or partial`() {
        assertThrows(IllegalArgumentException::class.java) {
            VoiceLabMobileApi(
                baseUrl = "https://voice-lab.example.test",
                credentials = VoiceLabMobileCredentials(hermesProfileApiKey = " "),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            VoiceLabMobileApi(
                baseUrl = "https://voice-lab.example.test",
                credentials = VoiceLabMobileCredentials(
                    hermesProfileApiKey = "profile-api-key",
                    cloudflareClientId = "cf-id",
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            VoiceLabMobileApi(
                baseUrl = "https://voice-lab.example.test",
                credentials = VoiceLabMobileCredentials(
                    hermesProfileApiKey = "profile-api-key",
                    cloudflareClientId = "",
                    cloudflareClientSecret = "cf-secret",
                ),
            )
        }
    }
}

private fun transportFor(handler: (Request) -> Response): VoiceLabHttpTransport =
    object : VoiceLabHttpTransport {
        override suspend fun execute(request: Request): Response = handler(request)
    }

private fun responseFor(
    request: Request,
    code: Int = 200,
    message: String = "OK",
    body: String,
): Response =
    Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(message)
        .body(body.toResponseBody())
        .build()

private fun okhttp3.RequestBody?.bodyToUtf8(): String {
    if (this == null) return ""
    val buffer = okio.Buffer()
    writeTo(buffer)
    return buffer.readUtf8()
}
