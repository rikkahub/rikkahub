package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.provider.providers.antigravityWebSearch
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.httpClient

/**
 * Google-Search-grounded search through the Gagy managed backend. Offered only while an
 * Gagy Google provider is configured (the app gates the type and injects the refresh token
 * live into [SearchServiceOptions.GoogleSearchOptions] at tool-creation time). Search-only — no scrape.
 */
object GoogleSearchService : SearchService<SearchServiceOptions.GoogleSearchOptions> {
    override val name: String = "Google Search"

    @Composable
    override fun Description() {
        Text("Uses your configured Antigravity (Google) login for Google Search grounding.")
    }

    override fun parameters(options: SearchServiceOptions.GoogleSearchOptions): InputSchema =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
            },
            required = listOf("query"),
        )

    override fun scrapingParameters(options: SearchServiceOptions.GoogleSearchOptions): InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.GoogleSearchOptions,
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            if (serviceOptions.refreshToken.isBlank()) {
                error("Antigravity is not configured — enable it on a Google provider first")
            }
            val result = antigravityWebSearch(
                client = httpClient,
                refreshToken = serviceOptions.refreshToken,
                query = query,
                maxSources = commonOptions.resultSize,
            )
            SearchResult(
                answer = result.answer.ifBlank { null },
                items = result.sources.map { SearchResultItem(title = it.title, url = it.url, text = "") },
            )
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.GoogleSearchOptions,
    ): Result<ScrapedResult> = Result.failure(Exception("Scraping is not supported for Google Search"))
}
