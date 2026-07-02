package me.rerere.rikkahub.voiceagent.voicelab

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.voiceagent.telemetry.VoiceTraceContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private val DEFAULT_HTTP_CLIENT by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.MINUTES)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .build()
}
private val DEV_HTTP_HOSTS = setOf("localhost", "127.0.0.1", "::1", "10.0.2.2", "10.0.3.2")
private const val ERROR_BODY_PREVIEW_LIMIT = 2048L
private val ERROR_SENSITIVE_KEYS = setOf(
    "authorization",
    "apikey",
    "key",
    "cfaccessclientid",
    "cfaccessclientsecret",
    "cloudflareclientid",
    "cloudflareclientsecret",
    "hermesprofileapikey",
    "accesstoken",
    "refreshtoken",
    "token",
    "secret",
    "password",
    "websocketurl",
    "liveconnectconfig",
    "connectconfig",
    "providerconfig",
    "sessionurl",
    "prompt",
    "answer",
)
private val ERROR_SECRET_PATTERNS = listOf(
    Regex(
        pattern = """\b(Bearer\s+)([A-Za-z0-9._~+/=-]+)""",
        option = RegexOption.IGNORE_CASE,
    ),
    Regex(
        pattern = """("?(?:authorization|api[_-]?key|key|cf[_-]?access[_-]?client[_-]?id|cf[_-]?access[_-]?client[_-]?secret|cloudflare[_-]?client[_-]?id|cloudflare[_-]?client[_-]?secret|client[_-]?id|client[_-]?secret|access[_-]?token|refresh[_-]?token|token|secret|password|websocket[_-]?url|live[_-]?connect[_-]?config|connect[_-]?config|provider[_-]?config|session[_-]?url|prompt|answer)"?\s*[:=]\s*")([^"]*)("?|$)""",
        option = RegexOption.IGNORE_CASE,
    ),
    Regex(
        pattern = """('?(?:authorization|api[_-]?key|key|cf[_-]?access[_-]?client[_-]?id|cf[_-]?access[_-]?client[_-]?secret|cloudflare[_-]?client[_-]?id|cloudflare[_-]?client[_-]?secret|client[_-]?id|client[_-]?secret|access[_-]?token|refresh[_-]?token|token|secret|password|websocket[_-]?url|live[_-]?connect[_-]?config|connect[_-]?config|provider[_-]?config|session[_-]?url|prompt|answer)'?\s*[:=]\s*')([^']*)('?|$)""",
        option = RegexOption.IGNORE_CASE,
    ),
    Regex(
        pattern = """\b((?:authorization|api[_-]?key|key|cf[_-]?access[_-]?client[_-]?id|cf[_-]?access[_-]?client[_-]?secret|cloudflare[_-]?client[_-]?id|cloudflare[_-]?client[_-]?secret|client[_-]?id|client[_-]?secret|access[_-]?token|refresh[_-]?token|token|secret|password|websocket[_-]?url|live[_-]?connect[_-]?config|connect[_-]?config|provider[_-]?config|session[_-]?url|prompt|answer)\s*[:=]\s*)([^\r\n,;}\]]+)""",
        option = RegexOption.IGNORE_CASE,
    ),
)

data class VoiceLabTraceHeaders(
    val traceId: String,
    val voiceSessionId: String,
    val sentryTrace: String? = null,
    val sentryBaggage: String? = null,
) {
    init {
        require(traceId.isValidVoiceTraceHeader()) {
            "Voice trace id must be non-blank, at most 128 characters, and must be safe for HTTP headers"
        }
        require(voiceSessionId.isValidVoiceTraceHeader()) {
            "Voice session id must be non-blank, at most 128 characters, and must be safe for HTTP headers"
        }
        require(sentryTrace == null || sentryTrace.isValidSentryPropagationHeader()) {
            "Sentry trace header must be non-blank, at most 8192 characters, and must be safe for HTTP headers"
        }
        require(sentryBaggage == null || sentryBaggage.isValidSentryPropagationHeader()) {
            "Sentry baggage header must be non-blank, at most 8192 characters, and must be safe for HTTP headers"
        }
    }

    companion object {
        fun from(trace: VoiceTraceContext): VoiceLabTraceHeaders =
            VoiceLabTraceHeaders(
                traceId = trace.traceId,
                voiceSessionId = trace.voiceSessionId,
                sentryTrace = trace.sentryTrace,
                sentryBaggage = trace.sentryBaggage,
            )
    }
}

internal fun interface VoiceLabHttpTransport {
    suspend fun execute(request: Request): Response
}

private class OkHttpVoiceLabTransport(
    private val client: OkHttpClient,
) : VoiceLabHttpTransport {
    override suspend fun execute(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isCancelled) return
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (continuation.isCancelled) {
                            response.close()
                        } else {
                            continuation.resume(response) { _, resource, _ -> resource.close() }
                        }
                    }
                }
            )
        }
}

class VoiceLabHttpException(
    val statusCode: Int,
    val safePreview: String,
    val failure: VoiceFailure? = null,
) : IllegalStateException("Voice Lab request failed $statusCode: $safePreview")

class VoiceLabMobileApi internal constructor(
    private val baseUrl: String,
    private val credentials: VoiceLabMobileCredentials,
    private val transport: VoiceLabHttpTransport,
    private val traceHeaders: VoiceLabTraceHeaders? = null,
    private val json: Json = JsonInstant,
) {
    constructor(
        baseUrl: String,
        credentials: VoiceLabMobileCredentials,
    ) : this(
        baseUrl = baseUrl,
        credentials = credentials,
        json = JsonInstant,
        traceHeaders = null,
    )

    constructor(
        baseUrl: String,
        credentials: VoiceLabMobileCredentials,
        json: Json,
    ) : this(
        baseUrl = baseUrl,
        credentials = credentials,
        json = json,
        traceHeaders = null,
    )

    constructor(
        baseUrl: String,
        credentials: VoiceLabMobileCredentials,
        traceHeaders: VoiceLabTraceHeaders?,
    ) : this(
        baseUrl = baseUrl,
        credentials = credentials,
        json = JsonInstant,
        traceHeaders = traceHeaders,
    )

    constructor(
        baseUrl: String,
        credentials: VoiceLabMobileCredentials,
        json: Json,
        traceHeaders: VoiceLabTraceHeaders?,
    ) : this(
        baseUrl = baseUrl,
        credentials = credentials,
        transport = OkHttpVoiceLabTransport(DEFAULT_HTTP_CLIENT),
        traceHeaders = traceHeaders,
        json = json,
    )

    private val normalizedBaseUrl = baseUrl.trimEnd('/')
    private val parsedBaseUrl = normalizedBaseUrl.toHttpUrl()

    init {
        require(credentials.hermesProfileApiKey.isNotBlank()) {
            "Voice Lab Hermes profile API key must not be blank"
        }
        require(
            credentials.cloudflareClientId == null &&
                credentials.cloudflareClientSecret == null ||
                !credentials.cloudflareClientId.isNullOrBlank() &&
                !credentials.cloudflareClientSecret.isNullOrBlank()
        ) {
            "Voice Lab Cloudflare Access credentials must be provided together or omitted together"
        }
        require(parsedBaseUrl.isHttps || parsedBaseUrl.host.isDevHttpHost()) {
            "Voice Lab baseUrl must use HTTPS unless it targets a local development host"
        }
        require(parsedBaseUrl.query == null && parsedBaseUrl.fragment == null) {
            "Voice Lab baseUrl must not include a query or fragment"
        }
        require(parsedBaseUrl.username.isEmpty() && parsedBaseUrl.password.isEmpty()) {
            "Voice Lab baseUrl must not include username or password credentials"
        }
    }

    suspend fun createSession(modelId: String): MobileVoiceSessionResponse =
        postJson(
            path = "/api/mobile/voice/session",
            body = MobileVoiceSessionRequest(modelId = modelId),
        )

    suspend fun askHermes(
        callId: String,
        prompt: String,
        profileId: String? = null,
    ): MobileHermesResponse =
        postJson(
            path = "/api/mobile/hermes",
            body = MobileHermesRequest(callId = callId, prompt = prompt, profileId = profileId),
        )

    suspend fun submitHermesJob(
        callId: String,
        prompt: String,
        profileId: String? = null,
    ): MobileHermesJobSubmitResponse =
        postJson(
            path = "/api/mobile/hermes/jobs",
            body = MobileHermesRequest(callId = callId, prompt = prompt, profileId = profileId),
        )

    suspend fun getHermesJob(jobId: String): MobileHermesJobPollResponse =
        getJson(path = "/api/mobile/hermes/jobs/$jobId")

    suspend fun cancelHermesJob(jobId: String): MobileHermesJobPollResponse =
        deleteJson(path = "/api/mobile/hermes/jobs/$jobId")

    private suspend inline fun <reified Req, reified Res> postJson(path: String, body: Req): Res =
        executeJson(
            requestBuilder(path)
                .post(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE))
                .build()
        )

    private suspend inline fun <reified Res> getJson(path: String): Res =
        executeJson(
            requestBuilder(path)
                .get()
                .build()
        )

    private suspend inline fun <reified Res> deleteJson(path: String): Res =
        executeJson(
            requestBuilder(path)
                .delete()
                .build()
        )

    private fun requestBuilder(path: String): Request.Builder =
        Request.Builder()
            .url(normalizedBaseUrl + path)
            .addHeader("Authorization", "Bearer ${credentials.hermesProfileApiKey}")
            .apply {
                credentials.cloudflareClientId?.let { addHeader("CF-Access-Client-Id", it) }
                credentials.cloudflareClientSecret?.let { addHeader("CF-Access-Client-Secret", it) }
                traceHeaders?.let {
                    addHeader("X-Voice-Trace-Id", it.traceId)
                    addHeader("X-Voice-Session-Id", it.voiceSessionId)
                    it.sentryTrace?.let { sentryTrace -> addHeader("sentry-trace", sentryTrace) }
                    it.sentryBaggage?.let { sentryBaggage -> addHeader("baggage", sentryBaggage) }
                }
            }

    private suspend inline fun <reified Res> executeJson(request: Request): Res =
        withContext(Dispatchers.IO) {
            transport.execute(request).use { response ->
                if (!response.isSuccessful) {
                    val rawPreview = response.peekBody(ERROR_BODY_PREVIEW_LIMIT + 1).string()
                    throw VoiceLabHttpException(
                        statusCode = response.code,
                        safePreview = response.toErrorPreview(rawPreview),
                        failure = runCatching { json.decodeFromString<VoiceFailure>(rawPreview) }.getOrNull(),
                    )
                }
                val responseText = response.body.string()
                runCatching {
                    json.decodeFromString<Res>(responseText)
                }.getOrElse { error ->
                    val errorType = error::class.simpleName ?: "unknown"
                    throw IllegalStateException(
                        "Voice Lab response decode failed ($errorType): ${responseText.toSanitizedPreview()}"
                    )
                }
            }
        }
}

private fun String.isValidVoiceTraceHeader(): Boolean =
    isNotBlank() &&
        length <= 128 &&
        all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == ':' || it == '-' }

private fun String.isValidSentryPropagationHeader(): Boolean =
    isValidHttpHeaderValue(maxLength = 8192)

private fun String.isValidHttpHeaderValue(maxLength: Int): Boolean =
    isNotBlank() &&
        length <= maxLength &&
        all { it == '\t' || it in ' '..'~' }

private fun String.isDevHttpHost(): Boolean =
    this in DEV_HTTP_HOSTS || isTailscaleIpv4()

private fun String.isTailscaleIpv4(): Boolean {
    val octets = split('.').map { it.toIntOrNull() ?: return false }
    return octets.size == 4 &&
        octets.all { it in 0..255 } &&
        octets[0] == 100 &&
        octets[1] in 64..127
}

private fun Response.toErrorPreview(rawPreview: String): String =
    rawPreview.toSanitizedPreview(
        wasTruncated = (body.contentLength() > ERROR_BODY_PREVIEW_LIMIT).takeIf { body.contentLength() >= 0 }
    )

private fun String.toSanitizedPreview(wasTruncated: Boolean? = null): String {
    if (isCloudflareAccessHtml()) {
        return "Cloudflare Access denied. Check Voice Agent Cloudflare Access client id and secret."
    }
    val preview = if (length > ERROR_BODY_PREVIEW_LIMIT) {
        take((ERROR_BODY_PREVIEW_LIMIT + 1).toInt())
    } else {
        this
    }
    val redacted = preview.redactSensitivePreview()
    val bounded = if (redacted.length <= ERROR_BODY_PREVIEW_LIMIT) {
        redacted
    } else {
        redacted.take(ERROR_BODY_PREVIEW_LIMIT.toInt()) + "... [truncated]"
    }
    val truncated = wasTruncated ?: (length > ERROR_BODY_PREVIEW_LIMIT)
    return if (truncated && !bounded.endsWith("[truncated]")) {
        "$bounded... [truncated]"
    } else {
        bounded
    }
}

private fun String.isCloudflareAccessHtml(): Boolean {
    val normalized = lowercase()
    return "cloudflare access" in normalized &&
        ("<!doctype html" in normalized || "<html" in normalized)
}

private fun String.redactSensitivePreview(): String {
    val jsonRedacted = runCatching {
        JsonInstant.encodeToString(JsonInstant.parseToJsonElement(this).redactSensitiveJson())
    }.getOrNull()
    return ERROR_SECRET_PATTERNS.fold(jsonRedacted ?: this) { value, pattern ->
        pattern.replace(value) { match ->
            when (match.groupValues.size) {
                4 -> match.groupValues[1] + "[redacted]" + match.groupValues[3]
                3 -> match.groupValues[1] + "[redacted]"
                else -> "[redacted]"
            }
        }
    }
}

private fun JsonElement.redactSensitiveJson(): JsonElement =
    when (this) {
        is JsonObject -> JsonObject(
            mapValues { (key, value) ->
                if (key.isSensitiveErrorKey()) {
                    JsonPrimitive("[redacted]")
                } else {
                    value.redactSensitiveJson()
                }
            }
        )

        is JsonArray -> JsonArray(map { it.redactSensitiveJson() })
        is JsonPrimitive -> if (contentOrNull?.trimStart()?.firstOrNull() in setOf('{', '[')) {
            JsonPrimitive(contentOrNull?.redactEmbeddedSensitiveJson().orEmpty())
        } else {
            this
        }
    }

private fun String.isSensitiveErrorKey(): Boolean =
    lowercase().filterNot { it == '_' || it == '-' || it == ' ' } in ERROR_SENSITIVE_KEYS

private fun String.redactEmbeddedSensitiveJson(): String {
    val redactedJson = runCatching {
        JsonInstant.encodeToString(JsonInstant.parseToJsonElement(this).redactSensitiveJson())
    }.getOrNull()
    return redactedJson ?: this
}
