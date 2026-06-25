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
 * Querit.ai — search API designed for AI/LLM applications.
 *
 * Homepage: https://www.querit.ai
 * Endpoint: POST https://api.querit.ai/v1/search
 * Auth:     Authorization: Bearer <api-key>
 *
 * Free tier: 1,000 requests/month, 1 req/s.
 * Paid tiers start at $4 / 1,000 requests.
 */
object QueritSearchService : SearchService<SearchServiceOptions.QueritOptions> {
    private const val ENDPOINT = "https://api.querit.ai/v1/search"
    private const val API_KEY_URL = "https://www.querit.ai/en"

    override val name: String = "Querit"

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

    override fun parameters(options: SearchServiceOptions.QueritOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.QueritOptions): InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.QueritOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")

            val body = buildJsonObject {
                put("query", query)
                put("count", commonOptions.resultSize)
            }
            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())

            val request = Request.Builder()
                .url(ENDPOINT)
                .post(body.toString().toRequestBody())
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .build()

            val response = httpClient.newCall(request).await()
            if (response.isSuccessful) {
                val responseBody = response.body.string()
                val searchResponse = json.decodeFromString<QueritSearchResponse>(responseBody)

                val items = searchResponse.results?.result.orEmpty().map { item ->
                    SearchResultItem(
                        title = item.title,
                        url = item.url,
                        text = item.snippet
                    )
                }

                return@withContext Result.success(SearchResult(items = items))
            } else {
                error("Querit search failed with code ${response.code}: ${response.message}")
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.QueritOptions
    ): Result<ScrapedResult> {
        return Result.failure(Exception("Scraping is not supported for Querit"))
    }

    @Serializable
    private data class QueritSearchResponse(
        val took: String? = null,
        val error_code: Int? = null,
        val error_msg: String? = null,
        // The results object is nullable / may be absent on error responses.
        val results: QueritResults? = null,
    )

    @Serializable
    private data class QueritResults(
        val result: List<QueritResult> = emptyList(),
    )

    @Serializable
    private data class QueritResult(
        val url: String = "",
        val title: String = "",
        val snippet: String = "",
        val page_age: String? = null,
        val site_name: String? = null,
    )
}
