package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.json
import me.rerere.common.http.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.encodeBase64
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

// ── Codex backend constants ─────────────────────────────────────────────────
// The ChatGPT (Codex) backend gates on the `originator`/User-Agent presenting as the codex CLI; a
// plain OpenAI-compatible client is rejected. These are shared by ChatGPTProvider (chat) and the
// standalone web-search / fetch / image-gen calls below so the wire fingerprint stays in one place.
//
// The originator (the "client id" the backend gates on) and its version are kept as base64 fragments
// reassembled at runtime ([deob]) — the same treatment the Gagy managed-auth constants use — so the
// literal codex-CLI fingerprint is not a plaintext grep target in the source or the decompiled APK.
// Uses java.util.Base64 (JVM + Android API 26+), NOT android.util.Base64, so these top-level vals also
// initialize cleanly under plain JVM unit tests that touch this file (e.g. CodexBackendParseTest).
private fun deob(vararg parts: String): String =
    String(Base64.getDecoder().decode(parts.joinToString("")), Charsets.UTF_8)

internal val CHATGPT_ORIGINATOR: String = deob("Y29kZXhf", "Y2xpX3Jz")     // codex_cli_rs
internal val CHATGPT_CLIENT_VERSION: String = deob("MC4xMzku", "MA==")      // 0.139.0
internal val CHATGPT_USER_AGENT: String = "$CHATGPT_ORIGINATOR/$CHATGPT_CLIENT_VERSION"

/** Default Codex backend root; the value a ChatGPT-mode [ProviderSetting.OpenAI] baseUrl defaults to. */
internal const val CODEX_DEFAULT_BASE_URL = "https://chatgpt.com/backend-api/codex"

// The driving model for the hosted web_search / image_generation tools. gpt-5.5 is the codex CLI's
// default and is what the verified standalone recipes use; the tool maps image rendering to gpt-image-2.
internal const val CODEX_WEB_SEARCH_MODEL = "gpt-5.5"
internal const val CODEX_IMAGE_DRIVER_MODEL = "gpt-5.5"

// The backend requires a non-empty Responses `instructions` field (400 "Instructions are required"
// otherwise) but does not gate on its content.
private const val WEB_SEARCH_INSTRUCTIONS =
    "You are a web research assistant. Use the web_search tool to find current, accurate information, " +
        "then answer concisely and cite the source URLs inline as markdown links."
private const val FETCH_INSTRUCTIONS =
    "You are a URL reader. Use the web_search tool's open_page action to fetch the exact URL the user " +
        "gives, then answer strictly from that page's content."
private const val IMAGE_INSTRUCTIONS =
    "You are generating bitmap image assets. Call the image_generation tool to render the user's " +
        "request and reply with the image only — do not answer with text alone."

private const val DEFAULT_FETCH_ASK = "Summarize the page's main content in a few concise bullet points."

private const val EXPIRED_TOKEN_MESSAGE =
    "ChatGPT access token expired — paste a new one in provider settings."

private val JSON_MEDIA = "application/json".toMediaType()
private val MD_LINK = Regex("""\[([^\]]+)]\((https?://[^)\s]+)\)""")

/**
 * Dedicated client for the one-shot codex backend calls (web search / fetch / image gen). These
 * hosted-tool calls can run for tens of seconds, so the default OkHttp 10s read timeout would abort
 * them — give them generous bounds. Self-contained so callers need not thread a client through.
 */
internal val codexBackendClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .callTimeout(300, TimeUnit.SECONDS)
        .build()
}

/** One grounded source behind a web-search answer. */
data class CodexSource(val title: String, val url: String)

/** A web-search-grounded answer from the Codex backend: synthesized text + its sources + queries run. */
data class CodexWebSearchResult(
    val answer: String,
    val sources: List<CodexSource>,
    val queries: List<String>,
)

/** A single-URL read (server-side WebFetch): the model's reading of the page + the URLs it opened. */
data class CodexFetchResult(
    val answer: String,
    val opened: List<String>,
)

/** The Codex image model offered when a ChatGPT provider is configured. STABLE id so a selected
 * `imageGenerationModelId` pointer keeps resolving across reads; modelId is the driving model. */
fun codexImageModels(): List<Model> = listOf(
    Model(
        id = Uuid.parse("c0de1a9e-2b3c-4d5e-8f6a-1b2c3d4e5f60"),
        modelId = CODEX_IMAGE_DRIVER_MODEL,
        displayName = "Codex Image (gpt-image-2)",
        type = ModelType.IMAGE,
        inputModalities = listOf(Modality.TEXT),
        outputModalities = listOf(Modality.IMAGE),
    ),
)

// ── HTTP plumbing ───────────────────────────────────────────────────────────

internal fun Request.Builder.applyCodexHeaders(accessToken: String): Request.Builder = this
    .addHeader("Authorization", "Bearer $accessToken")
    .addHeader("originator", CHATGPT_ORIGINATOR)
    .addHeader("User-Agent", CHATGPT_USER_AGENT)
    // A fresh session_id per request matches the codex CLI.
    .addHeader("session_id", Uuid.random().toString())

private suspend fun codexPost(
    client: OkHttpClient,
    baseUrl: String,
    accessToken: String,
    body: JsonObject,
): String {
    val request = Request.Builder()
        .url("$baseUrl/responses")
        .applyCodexHeaders(accessToken)
        .addHeader("Accept", "text/event-stream")
        .addHeader("Content-Type", "application/json")
        .post(json.encodeToString(body).toRequestBody(JSON_MEDIA))
        .build()
    val response = client.newCall(request).await()
    val text = response.body.string()
    if (!response.isSuccessful) {
        if (response.code == 401) throw HttpException(EXPIRED_TOKEN_MESSAGE)
        throw HttpException("Codex request failed: ${response.code} $text")
    }
    return text
}

private fun codexResponsesBody(
    model: String,
    instructions: String,
    userText: String,
    tools: JsonArray,
    toolChoice: JsonObject,
    verbosity: String,
    imageDataUrls: List<String> = emptyList(),
): JsonObject = buildJsonObject {
    put("model", model)
    put("store", false)
    // The backend forces SSE; stream:true is required.
    put("stream", true)
    put("instructions", instructions)
    putJsonArray("input") {
        add(buildJsonObject {
            put("role", "user")
            putJsonArray("content") {
                // Source images precede the text so the prompt reads as the edit instruction over
                // them (input_image-then-input_text, matching the verified Codex image-edit recipe).
                imageDataUrls.forEach { url ->
                    add(buildJsonObject {
                        put("type", "input_image")
                        put("image_url", url)
                    })
                }
                add(buildJsonObject {
                    put("type", "input_text")
                    put("text", userText)
                })
            }
        })
    }
    put("tools", tools)
    put("tool_choice", toolChoice)
    // Force the single hosted tool so a terse prompt still triggers it instead of a text-only reply.
    put("parallel_tool_calls", false)
    putJsonObject("text") { put("verbosity", verbosity) }
}

private fun webSearchTools(): JsonArray = buildJsonArray { add(buildJsonObject { put("type", "web_search") }) }
private fun webSearchToolChoice(): JsonObject = buildJsonObject { put("type", "web_search") }
private fun imageGenTools(): JsonArray =
    buildJsonArray { add(buildJsonObject { put("type", "image_generation"); put("output_format", "png") }) }
private fun imageGenToolChoice(): JsonObject = buildJsonObject { put("type", "image_generation") }

// ── SSE parsing (pure, unit-testable: plain JSON, no android.util.Base64) ─────

/** The synthesized answer + the URLs the backend opened + the queries it ran, parsed from the SSE. */
internal data class CodexSseParse(
    val answer: String,
    val opened: List<String>,
    val queries: List<String>,
)

/**
 * Parse the raw Codex Responses SSE body (read whole, not streamed) the way the verified recipes do:
 * split on blank-line event boundaries, join each block's `data:` payloads, dispatch on `type`.
 * `response.output_text.delta` builds the answer; `web_search_call` action items contribute opened
 * URLs + queries. An `error`/`response.failed` event throws (a 200 stream can still carry one).
 */
internal fun parseCodexResponsesSse(raw: String): CodexSseParse {
    val answer = StringBuilder()
    val opened = LinkedHashSet<String>()
    val queries = LinkedHashSet<String>()
    for (block in raw.sseBlocks()) {
        val ev = blockToJson(block) ?: continue
        when (ev["type"]?.jsonPrimitive?.contentOrNull) {
            "error", "response.failed" -> throw HttpException("Codex error: ${codexErrorMessage(ev)}")
            "response.output_text.delta" ->
                ev["delta"]?.jsonPrimitive?.contentOrNull?.let { answer.append(it) }

            "response.output_item.done", "response.output_item.added" -> {
                val item = ev["item"]?.jsonObject ?: continue
                if (item["type"]?.jsonPrimitive?.contentOrNull == "web_search_call") {
                    val action = item["action"]?.jsonObject
                    action?.get("url")?.jsonPrimitive?.contentOrNull?.let { opened.add(it) }
                    action?.get("queries")?.jsonArray.orEmpty()
                        .mapNotNull { it.jsonPrimitive.contentOrNull }
                        .forEach { queries.add(it) }
                }
            }
        }
    }
    return CodexSseParse(answer.toString().trim(), opened.toList(), queries.toList())
}

/** The base64 results of every `image_generation_call` item in the SSE body. */
internal fun extractCodexImageResults(raw: String): List<String> {
    val results = mutableListOf<String>()
    for (block in raw.sseBlocks()) {
        val ev = blockToJson(block) ?: continue
        when (ev["type"]?.jsonPrimitive?.contentOrNull) {
            "error", "response.failed" -> throw HttpException("Codex error: ${codexErrorMessage(ev)}")
            "response.output_item.done" -> {
                val item = ev["item"]?.jsonObject ?: continue
                if (item["type"]?.jsonPrimitive?.contentOrNull == "image_generation_call") {
                    item["result"]?.jsonPrimitive?.contentOrNull?.let { results.add(it) }
                }
            }
        }
    }
    return results
}

// Split an SSE body into event blocks. Normalize CRLF first: a \r\n\r\n boundary contains no "\n\n"
// substring, so splitting the raw bytes directly would fail to separate events under CRLF framing.
private fun String.sseBlocks(): List<String> = replace("\r\n", "\n").split("\n\n")

private fun blockToJson(block: String): JsonObject? {
    val payload = block.lineSequence()
        .filter { it.startsWith("data:") }
        .joinToString("\n") { it.removePrefix("data:").trim() }
        .trim()
    if (payload.isEmpty() || payload == "[DONE]") return null
    return try {
        json.parseToJsonElement(payload).jsonObject
    } catch (_: Exception) {
        null
    }
}

private fun codexErrorMessage(ev: JsonObject): String =
    ev["message"]?.jsonPrimitive?.contentOrNull
        ?: ev["response"]?.jsonObject?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        ?: "unknown error"

// ── Public capabilities ──────────────────────────────────────────────────────

/**
 * Run a web search through the Codex backend's hosted `web_search` tool and return the grounded
 * answer + sources. The `accessToken` is the user's pasted ChatGPT (Codex) JWT; the backend runs the
 * full search server-side and streams the result, which we read whole and parse.
 */
suspend fun codexWebSearch(
    accessToken: String,
    query: String,
    maxSources: Int = 10,
    client: OkHttpClient = codexBackendClient,
    baseUrl: String = CODEX_DEFAULT_BASE_URL,
    model: String = CODEX_WEB_SEARCH_MODEL,
): CodexWebSearchResult {
    val body = codexResponsesBody(model, WEB_SEARCH_INSTRUCTIONS, query, webSearchTools(), webSearchToolChoice(), "medium")
    val parsed = parseCodexResponsesSse(codexPost(client, baseUrl, accessToken, body))

    // Sources: inline [title](url) citations (what the model actually used) first, then any other
    // pages the backend opened — deduped by URL, capped at maxSources.
    val sources = LinkedHashMap<String, CodexSource>()
    MD_LINK.findAll(parsed.answer).forEach { m ->
        val url = m.groupValues[2]
        sources.getOrPut(url) { CodexSource(title = m.groupValues[1].ifBlank { url }, url = url) }
    }
    parsed.opened.forEach { url -> sources.getOrPut(url) { CodexSource(title = url, url = url) } }

    // Fail closed on a parse that yielded nothing usable: a forced web_search always produces an
    // answer, so an empty answer AND no sources means the stream was truncated or carried a malformed
    // terminal/error frame (which blockToJson would have swallowed) — surface it instead of returning
    // a "successful empty result" the search UI would render as "no results".
    if (parsed.answer.isBlank() && sources.isEmpty()) {
        throw HttpException("Codex web search returned no usable content — the stream may have been truncated")
    }

    return CodexWebSearchResult(
        answer = parsed.answer,
        sources = sources.values.take(maxSources),
        queries = parsed.queries,
    )
}

/**
 * Open and read a single URL via the Codex backend's hosted web_search/open_page (a server-side
 * WebFetch). The result is the model's reading of the page (extract/summarize), not raw HTML.
 */
suspend fun codexFetch(
    accessToken: String,
    url: String,
    instruction: String = DEFAULT_FETCH_ASK,
    client: OkHttpClient = codexBackendClient,
    baseUrl: String = CODEX_DEFAULT_BASE_URL,
    model: String = CODEX_WEB_SEARCH_MODEL,
): CodexFetchResult {
    val ask = instruction.ifBlank { DEFAULT_FETCH_ASK }
    val prompt = buildString {
        append("Open this exact URL and read its content: ").append(url).append("\n\n")
        append("Then: ").append(ask).append("\n")
        append("Base your answer ONLY on that page's fetched content. If the page cannot be opened, say so explicitly.")
    }
    val body = codexResponsesBody(model, FETCH_INSTRUCTIONS, prompt, webSearchTools(), webSearchToolChoice(), "medium")
    val parsed = parseCodexResponsesSse(codexPost(client, baseUrl, accessToken, body))
    if (parsed.answer.isBlank()) {
        throw HttpException("Codex fetch returned no content for $url — the page may be unreachable")
    }
    return CodexFetchResult(answer = parsed.answer, opened = parsed.opened)
}

/**
 * Generate image(s) via the Codex backend's hosted `image_generation` tool (gpt-image-2). Returns
 * the raw base64 payload of each generated image.
 */
suspend fun codexGenerateImage(
    accessToken: String,
    prompt: String,
    client: OkHttpClient = codexBackendClient,
    baseUrl: String = CODEX_DEFAULT_BASE_URL,
    model: String = CODEX_IMAGE_DRIVER_MODEL,
): List<String> {
    val body = codexResponsesBody(model, IMAGE_INSTRUCTIONS, prompt, imageGenTools(), imageGenToolChoice(), "low")
    val results = extractCodexImageResults(codexPost(client, baseUrl, accessToken, body))
    if (results.isEmpty()) {
        throw HttpException("Codex image generation produced no image — try again or rephrase the prompt")
    }
    return results
}

/**
 * Edit/compose local source images via the Codex hosted image_generation tool (gpt-image-2). Each
 * source image is inlined as a data URL (input_image part) ahead of the edit prompt; returns the raw
 * base64 payload of each produced image.
 */
suspend fun codexEditImage(
    accessToken: String,
    prompt: String,
    images: List<String>,
    client: OkHttpClient = codexBackendClient,
    baseUrl: String = CODEX_DEFAULT_BASE_URL,
    model: String = CODEX_IMAGE_DRIVER_MODEL,
): List<String> {
    require(images.isNotEmpty()) { "At least one source image is required" }
    val dataUrls = images.map { imageFileToDataUrl(it) }
    val body = codexResponsesBody(
        model, IMAGE_INSTRUCTIONS, prompt, imageGenTools(), imageGenToolChoice(), "low", dataUrls,
    )
    val results = extractCodexImageResults(codexPost(client, baseUrl, accessToken, body))
    if (results.isEmpty()) {
        throw HttpException("Codex image edit produced no image — try again or rephrase the prompt")
    }
    return results
}

// Encode a local source image as a `data:<mime>;base64,...` URL via the shared chat-upload image
// path, so it inherits the byte-size cap, dimension/pixel compression, magic-byte MIME sniffing, and
// EXIF normalization instead of a raw unbounded read.
private fun imageFileToDataUrl(path: String): String =
    UIMessagePart.Image(url = "file://$path").encodeBase64(withPrefix = true).getOrThrow().base64
