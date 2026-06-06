package me.rerere.rikkahub.voiceagent.voicelab

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.utils.JsonInstant
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
)
private val ERROR_SECRET_PATTERNS = listOf(
    Regex(
        pattern = """\b(Bearer\s+)([A-Za-z0-9._~+/=-]+)""",
        option = RegexOption.IGNORE_CASE,
    ),
    Regex(
        pattern = """("?(?:authorization|api[_-]?key|key|cf[_-]?access[_-]?client[_-]?id|cf[_-]?access[_-]?client[_-]?secret|cloudflare[_-]?client[_-]?id|cloudflare[_-]?client[_-]?secret|client[_-]?id|client[_-]?secret|access[_-]?token|refresh[_-]?token|token|secret|password|websocket[_-]?url|live[_-]?connect[_-]?config|connect[_-]?config|provider[_-]?config|session[_-]?url)"?\s*[:=]\s*")([^"]*)("?|$)""",
        option = RegexOption.IGNORE_CASE,
    ),
    Regex(
        pattern = """('?(?:authorization|api[_-]?key|key|cf[_-]?access[_-]?client[_-]?id|cf[_-]?access[_-]?client[_-]?secret|cloudflare[_-]?client[_-]?id|cloudflare[_-]?client[_-]?secret|client[_-]?id|client[_-]?secret|access[_-]?token|refresh[_-]?token|token|secret|password|websocket[_-]?url|live[_-]?connect[_-]?config|connect[_-]?config|provider[_-]?config|session[_-]?url)'?\s*[:=]\s*')([^']*)('?|$)""",
        option = RegexOption.IGNORE_CASE,
    ),
    Regex(
        pattern = """\b((?:authorization|api[_-]?key|key|cf[_-]?access[_-]?client[_-]?id|cf[_-]?access[_-]?client[_-]?secret|cloudflare[_-]?client[_-]?id|cloudflare[_-]?client[_-]?secret|client[_-]?id|client[_-]?secret|access[_-]?token|refresh[_-]?token|token|secret|password|websocket[_-]?url|live[_-]?connect[_-]?config|connect[_-]?config|provider[_-]?config|session[_-]?url)\s*[:=]\s*)([^\r\n,;}\]]+)""",
        option = RegexOption.IGNORE_CASE,
    ),
)

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

class VoiceLabMobileApi internal constructor(
    private val baseUrl: String,
    private val credentials: VoiceLabMobileCredentials,
    private val transport: VoiceLabHttpTransport,
    private val json: Json = JsonInstant,
) {
    constructor(
        baseUrl: String,
        credentials: VoiceLabMobileCredentials,
        json: Json = JsonInstant,
    ) : this(
        baseUrl = baseUrl,
        credentials = credentials,
        transport = OkHttpVoiceLabTransport(DEFAULT_HTTP_CLIENT),
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

    private suspend inline fun <reified Req, reified Res> postJson(path: String, body: Req): Res =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(normalizedBaseUrl + path)
                .addHeader("Authorization", "Bearer ${credentials.hermesProfileApiKey}")
                .apply {
                    credentials.cloudflareClientId?.let { addHeader("CF-Access-Client-Id", it) }
                    credentials.cloudflareClientSecret?.let { addHeader("CF-Access-Client-Secret", it) }
                }
                .post(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE))
                .build()
            transport.execute(request).use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException(
                        "Voice Lab request failed ${response.code}: ${response.toErrorPreview()}"
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

private fun String.isDevHttpHost(): Boolean =
    this in DEV_HTTP_HOSTS || isTailscaleIpv4()

private fun String.isTailscaleIpv4(): Boolean {
    val octets = split('.').map { it.toIntOrNull() ?: return false }
    return octets.size == 4 &&
        octets.all { it in 0..255 } &&
        octets[0] == 100 &&
        octets[1] in 64..127
}

private fun Response.toErrorPreview(): String =
    peekBody(ERROR_BODY_PREVIEW_LIMIT + 1).string().toSanitizedPreview(
        wasTruncated = (body.contentLength() > ERROR_BODY_PREVIEW_LIMIT).takeIf { body.contentLength() >= 0 }
    )

private fun String.toSanitizedPreview(wasTruncated: Boolean? = null): String {
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
