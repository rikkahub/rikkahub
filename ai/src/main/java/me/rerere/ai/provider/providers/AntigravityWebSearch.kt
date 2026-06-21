package me.rerere.ai.provider.providers

import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.json
import me.rerere.common.http.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** One grounded source behind a web-search answer. */
data class AntigravitySource(val title: String, val url: String)

/** A Google-Search-grounded answer from the managed backend: the synthesized text + its sources. */
data class AntigravityWebSearchResult(
    val answer: String,
    val sources: List<AntigravitySource>,
    val queries: List<String>,
)

/**
 * Run a Google-Search-grounded web search through the Gagy managed backend, reusing the same
 * OAuth/project/envelope plumbing as the chat path. The wire is a `generateContent` POST with
 * `requestType:"web_search"`, the [ANTIGRAVITY_WEB_SEARCH_MODEL_ID] model, and
 * `request.tools:[{googleSearch:{}}]`; the reply carries the synthesized answer plus
 * `candidates[0].groundingMetadata` (webSearchQueries / groundingChunks[].web).
 *
 * Lives in the `ai` module (not `search`) so the deobfuscated endpoint/auth constants stay in one place;
 * the `search` module's GoogleSearchService calls this and maps the result to its own SearchResult.
 */
suspend fun antigravityWebSearch(
    client: OkHttpClient,
    refreshToken: String,
    query: String,
    maxSources: Int = 10,
): AntigravityWebSearchResult {
    val auth = AntigravityGoogleAuth(client)
    val access = auth.accessToken(refreshToken)
    val project = auth.project(access)

    val inner = buildJsonObject {
        putJsonArray("contents") {
            add(buildJsonObject {
                put("role", "user")
                putJsonArray("parts") { add(buildJsonObject { put("text", query) }) }
            })
        }
        putJsonArray("tools") { add(buildJsonObject { put("googleSearch", buildJsonObject { }) }) }
    }
    val envelope = auth.wrapEnvelope(inner, ANTIGRAVITY_WEB_SEARCH_MODEL_ID, project, "web_search")

    val request = Request.Builder()
        .url("https://${auth.host()}/v1internal:generateContent")
        .addHeader("Authorization", "Bearer $access")
        .header("User-Agent", auth.userAgent())
        .post(json.encodeToString(envelope).toRequestBody("application/json".toMediaType()))
        .build()

    val response = client.newCall(request).await()
    val text = response.body.string()
    if (!response.isSuccessful) throw HttpException("Antigravity web search failed: ${response.code} $text")

    val body = json.parseToJsonElement(text).jsonObject
    val r = body["response"]?.jsonObject ?: body
    val candidate = r["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
        ?: throw HttpException("Antigravity web search: no candidates")

    val answer = candidate["content"]?.jsonObject?.get("parts")?.jsonArray.orEmpty()
        .filter { it.jsonObject["thought"]?.jsonPrimitive?.booleanOrNull != true }
        .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
        .joinToString("")
        .trim()

    val grounding = candidate["groundingMetadata"]?.jsonObject
    val queries = grounding?.get("webSearchQueries")?.jsonArray.orEmpty()
        .mapNotNull { it.jsonPrimitive.contentOrNull }
    val sources = grounding?.get("groundingChunks")?.jsonArray.orEmpty()
        .mapNotNull { chunk ->
            val web = chunk.jsonObject["web"]?.jsonObject ?: return@mapNotNull null
            val url = web["uri"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            AntigravitySource(title = web["title"]?.jsonPrimitive?.contentOrNull ?: url, url = url)
        }
        .take(maxSources)

    return AntigravityWebSearchResult(answer = answer, sources = sources, queries = queries)
}
