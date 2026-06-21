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
import me.rerere.ai.provider.providers.codexWebSearch
import me.rerere.search.SearchResult.SearchResultItem

/**
 * Web search grounded through the ChatGPT (Codex) backend's hosted web_search tool. Offered only
 * while a ChatGPT provider is configured (the app gates the type and injects the access token live
 * into [SearchServiceOptions.CodexSearchOptions] at tool-creation time). Search-only — no scrape.
 */
object CodexSearchService : SearchService<SearchServiceOptions.CodexSearchOptions> {
    override val name: String = "Codex Search"

    @Composable
    override fun Description() {
        Text("Uses your configured ChatGPT (Codex) login for hosted web search grounding.")
    }

    override fun parameters(options: SearchServiceOptions.CodexSearchOptions): InputSchema =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
            },
            required = listOf("query"),
        )

    override fun scrapingParameters(options: SearchServiceOptions.CodexSearchOptions): InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.CodexSearchOptions,
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            if (serviceOptions.accessToken.isBlank()) {
                error("ChatGPT is not configured — add a ChatGPT provider with an access token first")
            }
            val result = codexWebSearch(
                accessToken = serviceOptions.accessToken,
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
        serviceOptions: SearchServiceOptions.CodexSearchOptions,
    ): Result<ScrapedResult> = Result.failure(Exception("Scraping is not supported for Codex Search"))
}
