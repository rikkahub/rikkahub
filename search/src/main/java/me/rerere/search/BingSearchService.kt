package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
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
import java.util.Locale

object BingSearchService : SearchService<SearchServiceOptions.BingLocalOptions> {
    override val name: String = "Bing"

    @Composable
    override fun Description() {
        Text(stringResource(R.string.bing_desc))
    }

    override fun parameters(options: SearchServiceOptions.BingLocalOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.BingLocalOptions): InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.BingLocalOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            val url = "https://www.bing.com/search?q=" + URLEncoder.encode(query, "UTF-8")
            val locale = Locale.getDefault()
            val acceptLanguage = "${locale.language}-${locale.country},${locale.language}"
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36")
                .header(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
                )
                .header("Accept-Language", acceptLanguage)
                .header("Accept-Encoding", "gzip, deflate, sdch")
                .header("Accept-Charset", "utf-8")
                .header("Connection", "keep-alive")
                .referrer("https://www.bing.com/")
                .cookie("SRCHHPGUSR", "ULSR=1")
                .timeout(10000)
                .get()

            val results = doc.select("li.b_algo").map { element ->
                val title = element.select("h2").text()
                val link = element.select("h2 > a").attr("href")
                val snippet = element.select(".b_caption p, p.b_algoSlug").text()
                SearchResultItem(
                    title = title,
                    url = link,
                    text = snippet
                )
            }

            if (results.isEmpty()) {
                val fallbackResults = doc.select(".b_algo, .b_ans").map { element ->
                    val title = element.select("h2, .b_algoheader a").text()
                    val link = element.select("h2 a, .b_algoheader a").attr("href")
                    val snippet = element.select("p, .b_caption p, .b_snippet").text()
                    SearchResultItem(
                        title = title,
                        url = link,
                        text = snippet
                    )
                }
                SearchResult(items = fallbackResults)
            } else {
                SearchResult(items = results)
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.BingLocalOptions
    ): Result<ScrapedResult> {
        return Result.failure(Exception("Scraping is not supported for Bing"))
    }
}
