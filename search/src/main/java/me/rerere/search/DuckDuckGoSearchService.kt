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
import me.rerere.search.SearchResult.SearchResultItem
import org.jsoup.Jsoup
import java.net.URLEncoder

object DuckDuckGoSearchService : SearchService<SearchServiceOptions.DuckDuckGoOptions> {
    override val name: String = "DuckDuckGo"

    @Composable
    override fun Description() {
        Text("Privacy-focused search engine based in Europe")
    }

    override fun parameters(options: SearchServiceOptions.DuckDuckGoOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.DuckDuckGoOptions): InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.DuckDuckGoOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            val url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8")

            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .timeout(15000)
                .followRedirects(true)
                .get()

            var results = doc.select(".result").mapNotNull { element ->
                val titleEl = element.select(".result__a").firstOrNull()
                val title = titleEl?.text() ?: ""
                var link = titleEl?.attr("href") ?: ""
                if (link.startsWith("//")) link = "https:$link"
                val snippet = element.select(".result__snippet").firstOrNull()?.text() ?: ""
                if (title.isNotBlank() && link.isNotBlank()) {
                    SearchResultItem(title = title, url = link, text = snippet)
                } else null
            }

            if (results.isEmpty()) {
                results = doc.select("article, .results_links, .links_main").mapNotNull { element ->
                    val titleEl = element.select("a").firstOrNull()
                    val title = titleEl?.text() ?: ""
                    var link = titleEl?.attr("href") ?: ""
                    if (link.startsWith("//")) link = "https:$link"
                    val snippet = element.select("p, .snippet, .result__snippet").firstOrNull()?.text() ?: ""
                    if (title.isNotBlank() && link.isNotBlank()) {
                        SearchResultItem(title = title, url = link, text = snippet)
                    } else null
                }
            }

            SearchResult(items = results)
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.DuckDuckGoOptions
    ): Result<ScrapedResult> {
        return Result.failure(Exception("Scraping is not supported for DuckDuckGo"))
    }
}
