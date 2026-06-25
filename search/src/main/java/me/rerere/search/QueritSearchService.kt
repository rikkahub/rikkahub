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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
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
 *
 * Supported filters (all optional, configured in provider settings):
 *   - language: english, japanese, korean, german, french, spanish, portuguese
 *   - country: e.g. "united states"
 *   - dateRange: d1 (past day) / w1 (past week) / m1 (past month) / y1 (past year)
 *   - siteInclude: comma-separated domains to include
 *   - siteExclude: comma-separated domains to exclude
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

                // Build optional filters object. Only include non-empty filters so the
                // server applies its own defaults for fields the user left blank.
                val filters = buildFiltersJson(serviceOptions)
                if (filters.isNotEmpty()) {
                    put("filters", filters)
                }
            }
            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())

            val request = Request.Builder()
                .url(ENDPOINT)
                .post(body.toString().toRequestBody())
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Accept", "application/json")
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

    /**
     * Build the `filters` JSON object from the user's provider settings.
     * Returns an empty object if no filters are configured, so the caller can skip
     * adding it to the payload entirely.
     *
     * Querit filter structure (from official providers.yaml):
     * {
     *   "sites": { "include": ["..."], "exclude": ["..."] },
     *   "timeRange": { "date": "d1|w1|m1|y1" },
     *   "geo": { "countries": { "include": ["..."] } },
     *   "languages": { "include": ["..."] }
     * }
     */
    private fun buildFiltersJson(options: SearchServiceOptions.QueritOptions): JsonObject {
        val sitesInclude = options.siteInclude.split(",", ";").map { it.trim() }.filter { it.isNotEmpty() }
        val sitesExclude = options.siteExclude.split(",", ";").map { it.trim() }.filter { it.isNotEmpty() }
        val languages = options.language.split(",", ";").map { it.trim() }.filter { it.isNotEmpty() }
        val countries = options.country.split(",", ";").map { it.trim() }.filter { it.isNotEmpty() }

        return buildJsonObject {
            if (sitesInclude.isNotEmpty() || sitesExclude.isNotEmpty()) {
                put("sites", buildJsonObject {
                    if (sitesInclude.isNotEmpty()) {
                        put("include", JsonArray(sitesInclude.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                    }
                    if (sitesExclude.isNotEmpty()) {
                        put("exclude", JsonArray(sitesExclude.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                    }
                })
            }
            if (options.dateRange.isNotBlank()) {
                put("timeRange", buildJsonObject {
                    put("date", options.dateRange.trim())
                })
            }
            if (countries.isNotEmpty()) {
                put("geo", buildJsonObject {
                    put("countries", buildJsonObject {
                        put("include", JsonArray(countries.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                    })
                })
            }
            if (languages.isNotEmpty()) {
                put("languages", buildJsonObject {
                    put("include", JsonArray(languages.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                })
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
