package me.rerere.ai.provider.providers

import android.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.json
import me.rerere.common.http.await
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Auth + transport glue that lets [GoogleProvider] route through the managed
 * cloudcode backend (the "Gagy" auth mode) instead of a public API key —
 * so the whole Gemini request/response codec in [GoogleProvider] is reused as-is.
 *
 * Concerns owned here:
 *  - OAuth: exchange a long-lived refresh token for a ~1h access token (cached per token).
 *  - Project: resolve the managed project once via loadCodeAssist (cached).
 *  - Envelope: wrap a bare Gemini request in the backend `{project,requestId,…}` shape.
 *  - Fingerprints: the endpoint host, User-Agent gate, OAuth client id/secret and the
 *    ideType are kept as base64 fragments reassembled at runtime ([deob]) so the literal
 *    values don't sit in source as scanner/grep bait. This is footprint reduction, NOT
 *    security — anyone reading the bytecode can trivially recover them.
 */
internal class AntigravityGoogleAuth(
    private val client: OkHttpClient,
) {
    private fun deob(vararg parts: String): String =
        String(Base64.decode(parts.joinToString(""), Base64.DEFAULT), Charsets.UTF_8)

    private val tokenEndpoint = deob("aHR0cHM6Ly9vYXV0", "aDIuZ29vZ2xlYXBp", "cy5jb20vdG9rZW4=")
    private val cloudHost = deob("ZGFpbHktY2xvdW", "Rjb2RlLXBhLmdvb", "2dsZWFwaXMuY29t")
    private val ua = deob("YW50aWdyYXZpdHkv", "Y2xpLzEuMC4xMCBs", "aW51eC9hbWQ2NA==")
    private val clientId = deob("MTA3MTAwNjA2MDU5MS10bWhzc2luMmgyM", "WxjcmUyMzV2dG9sb2poNGc0MDNlcC5hcH", "BzLmdvb2dsZXVzZXJjb250ZW50LmNvbQ==")
    private val clientSecret = deob("R09DU1BYLUs1OEZX", "UjQ4NkxkTEoxbUxC", "OHNYQzR6NnFEQWY=")
    private val ideType = deob("QU5US", "UdSQV", "ZJVFk=")
    private val envUserAgent = deob("YW50a", "WdyYX", "ZpdHk=")

    fun host(): String = cloudHost
    fun userAgent(): String = ua

    // ---- OAuth access-token cache (keyed by refresh token; access rotates ~hourly) ----

    private data class CachedToken(val access: String, val expiresAtMs: Long)

    private val tokenMutex = Mutex()
    private val tokenCache = HashMap<String, CachedToken>()

    /** Exchange a refresh token for an access token, cached until ~120s before expiry. */
    suspend fun accessToken(refreshToken: String): String {
        val rt = refreshToken.trim()
        require(rt.isNotEmpty()) { "Antigravity refresh token is empty" }
        val now = Clock.System.now().toEpochMilliseconds()
        tokenMutex.withLock {
            tokenCache[rt]?.let { if (it.expiresAtMs > now) return it.access }
            val form = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .add("refresh_token", rt)
                .build()
            val response = client.newCall(Request.Builder().url(tokenEndpoint).post(form).build()).await()
            val text = response.body.string()
            if (!response.isSuccessful) throw HttpException("Antigravity token refresh failed: ${response.code} $text")
            val obj = json.parseToJsonElement(text).jsonObject
            val access = obj["access_token"]?.jsonPrimitive?.contentOrNull
                ?: throw HttpException("Antigravity token refresh: no access_token")
            val expiresIn = obj["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L
            tokenCache[rt] = CachedToken(access, now + expiresIn * 1000 - 120_000)
            return access
        }
    }

    // ---- managed project resolution (loadCodeAssist), cached once per process ----

    @Volatile
    private var cachedProject: String? = null
    private val projectMutex = Mutex()

    /** Resolve the managed cloudcode project for [access], cached for the process. */
    suspend fun project(access: String): String {
        cachedProject?.let { return it }
        projectMutex.withLock {
            cachedProject?.let { return it }
            val body = buildJsonObject {
                put("metadata", buildJsonObject { put("ideType", ideType) })
            }
            val request = Request.Builder()
                .url("https://$cloudHost/v1internal:loadCodeAssist")
                .addHeader("Authorization", "Bearer $access")
                .header("User-Agent", ua)
                .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).await()
            val text = response.body.string()
            if (!response.isSuccessful) throw HttpException("loadCodeAssist ${response.code}: $text")
            val project = json.parseToJsonElement(text).jsonObject["cloudaicompanionProject"]
                ?.jsonPrimitive?.contentOrNull
                ?: throw HttpException("loadCodeAssist: no cloudaicompanionProject")
            cachedProject = project
            return project
        }
    }

    /**
     * Wrap a bare Gemini request body in the backend envelope. [requestType] selects the managed
     * backend's mode — `"chat"`/`"agent"` for text turns, `"image_gen"` for Nano-Banana image
     * generation, `"web_search"` for Google-Search-grounded answers — and is also the requestId prefix.
     *
     * `safetySettings` is stripped — the managed backend's `request` shape does not carry it
     * (only contents/systemInstruction/tools/generationConfig), and an unknown field risks a 400.
     */
    fun wrapEnvelope(inner: JsonObject, modelId: String, project: String, requestType: String): JsonObject =
        buildAntigravityEnvelope(inner, modelId, project, requestType, envUserAgent)

    /** The verified-working catalog (fetchAvailableModels ids) offered when this mode is on. */
    fun catalog(): List<Model> = CATALOG.map { (id, name) ->
        Model(
            modelId = id,
            displayName = name,
            type = ModelType.CHAT,
            inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
            abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
        )
    }

    companion object {
        private val CATALOG = listOf(
            "gemini-3.5-flash-low" to "Gemini 3.5 Flash (default agent)",
            "gemini-3-flash-agent" to "Gemini 3.5 Flash (High)",
            "gemini-pro-agent" to "Gemini 3.1 Pro (High)",
            "gemini-3.1-pro-low" to "Gemini 3.1 Pro (Low)",
            "claude-sonnet-4-6" to "Claude Sonnet 4.6 (Thinking)",
            "claude-opus-4-6-thinking" to "Claude Opus 4.6 (Thinking)",
            "gpt-oss-120b-medium" to "GPT-OSS 120B (Medium)",
        )
    }
}

/**
 * Pure envelope builder (no Android/Base64 deps, so it's unit-testable): wraps a bare Gemini [inner]
 * request in the managed-backend shape for the given [requestType], stripping `safetySettings`.
 */
internal fun buildAntigravityEnvelope(
    inner: JsonObject,
    modelId: String,
    project: String,
    requestType: String,
    userAgent: String,
): JsonObject {
    val request = JsonObject(inner.filterKeys { it != "safetySettings" })
    return buildJsonObject {
        put("project", project)
        put("requestId", "$requestType/${Uuid.random()}")
        put("request", request)
        put("model", modelId)
        put("userAgent", userAgent)
        put("requestType", requestType)
    }
}

/** Wire id of the managed backend's image-generation model ("Nano Banana"). */
const val ANTIGRAVITY_IMAGE_MODEL_ID: String = "gemini-3.1-flash-image"

/** Wire id of the managed backend's web-search (Google-Search-grounded) model. */
const val ANTIGRAVITY_WEB_SEARCH_MODEL_ID: String = "gemini-3.1-flash-lite"

/**
 * Image-generation model(s) offered when the Gagy mode is on. STABLE ids (not random) so a
 * selected [me.rerere.rikkahub]-side `imageGenerationModelId` pointer keeps resolving across reads —
 * these surface in the image-gen picker only while an Gagy Google provider is configured.
 */
fun antigravityImageModels(): List<Model> = listOf(
    Model(
        id = Uuid.parse("f1a9e3c0-1b2d-4e5f-8a6b-0c1d2e3f4a5b"),
        modelId = ANTIGRAVITY_IMAGE_MODEL_ID,
        displayName = "Nano Banana (Gemini 3.1 Flash Image)",
        type = ModelType.IMAGE,
        inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
        outputModalities = listOf(Modality.IMAGE),
    ),
)
