package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import me.rerere.search.SearchService.Companion.keyRoulette
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * AnySearch — privacy-first search infrastructure for AI agents.
 *
 * API docs: https://www.anysearch.com/docs
 * Endpoint: POST https://api.anysearch.com/v1/search
 * Auth: Authorization: Bearer <key> (optional — anonymous tier supported)
 *
 * The API key is optional. When omitted, requests fall back to an anonymous tier
 * that is rate-limited per IP and consumes a daily free quota. When provided,
 * requests are billed against the paid quota attached to the key. We send the
 * Authorization header only if the user has configured an API key, so the same
 * provider entry works for both anonymous and authenticated usage.
 */
object AnySearchSearchService : SearchService<SearchServiceOptions.AnySearchOptions> {
    private const val ENDPOINT = "https://api.anysearch.com/v1/search"
    private const val API_KEY_URL = "https://www.anysearch.com/console"

    override val name: String = "AnySearch"

    @Composable
    override fun Description() {
        val urlHandler = LocalUriHandler.current
        TextButton(
            onClick = {
                urlHandler.openUri(API_KEY_URL)
            }
        ) {
            Text(stringResource(R.string.click_to_get_api_key))
        }
    }

    override fun parameters(options: SearchServiceOptions.AnySearchOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.AnySearchOptions): InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.AnySearchOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")

            val body = buildJsonObject {
                put("query", query)
                put("max_results", commonOptions.resultSize)
            }

            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())

            val requestBuilder = Request.Builder()
                .url(ENDPOINT)
                .post(body.toString().toRequestBody())
                .addHeader("Content-Type", "application/json")

            // Authorization is optional — only send when the user has configured a key,
            // so the anonymous free tier still works out-of-the-box.
            if (apiKey.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $apiKey")
            }

            val request = requestBuilder.build()

            val response = httpClient.newCall(request).await()
            if (response.isSuccessful) {
                val responseBody = response.body.string()
                val searchResponse = json.decodeFromString<AnySearchResponse>(responseBody)

                val items = searchResponse.data.results.map { result ->
                    SearchResultItem(
                        title = result.title,
                        url = result.url,
                        // Prefer snippet; fall back to content (truncated) so the AI still
                        // gets useful context when the provider returns no snippet.
                        text = result.snippet.ifBlank { result.content.take(500) }
                    )
                }

                return@withContext Result.success(SearchResult(items = items))
            } else {
                error("AnySearch search failed with code ${response.code}: ${response.message}")
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.AnySearchOptions
    ): Result<ScrapedResult> {
        return Result.failure(Exception("Scraping is not supported for AnySearch"))
    }

    @Serializable
    private data class AnySearchResponse(
        val code: Int = 0,
        val message: String = "",
        val data: AnySearchData = AnySearchData(),
    )

    @Serializable
    private data class AnySearchData(
        val results: List<AnySearchResult> = emptyList(),
    )

    @Serializable
    private data class AnySearchResult(
        val title: String = "",
        val url: String = "",
        val snippet: String = "",
        val content: String = "",
    )
}
