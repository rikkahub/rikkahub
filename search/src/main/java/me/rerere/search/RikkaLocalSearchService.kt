package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

import androidx.compose.ui.res.stringResource

object RikkaLocalSearchService : SearchService<SearchServiceOptions.RikkaLocalOptions> {
    override val name: String = "Rikka内置"

    @Composable
    override fun Description() {
        Text(stringResource(me.rerere.search.R.string.rikka_local_desc))
    }

    override val parameters: InputSchema?
        get() = InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
            },
            required = listOf("query")
        )

    override val scrapingParameters: InputSchema?
        get() = InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "URL to scrape")
                })
            },
            required = listOf("url")
        )

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.RikkaLocalOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")

            // 并发调用多个源
            val tasks = listOf(
                async { searchBing(query, commonOptions.resultSize) },
                async { searchDuckDuckGo(query, commonOptions.resultSize) }
            )

            val allResults = tasks.awaitAll().flatten()

            // URL 去重
            val uniqueResults = allResults.distinctBy { it.url }

            require(uniqueResults.isNotEmpty()) {
                "Search failed: no results found"
            }

            SearchResult(items = uniqueResults)
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.RikkaLocalOptions
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = params["url"]?.jsonPrimitive?.content ?: error("url is required")
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .timeout(10000)
                .get()

            val markdown = LocalReader.extract(doc.outerHtml())

            ScrapedResult(
                urls = listOf(
                    ScrapedResultUrl(
                        url = url,
                        content = markdown,
                        metadata = ScrapedResultMetadata(
                            title = doc.title(),
                            description = doc.select("meta[name=description]").attr("content")
                        )
                    )
                )
            )
        }
    }

    private suspend fun searchBing(query: String, limit: Int): List<SearchResultItem> {
        return try {
            val url = "https://www.bing.com/search?q=" + URLEncoder.encode(query, "UTF-8")
            val locale = Locale.getDefault()
            val acceptLanguage = "${locale.language}-${locale.country},${locale.language}"
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header("Accept-Language", acceptLanguage)
                .referrer("https://www.bing.com/")
                .timeout(5000)
                .get()

            doc.select("li.b_algo").take(limit).map { element ->
                SearchResultItem(
                    title = element.select("h2").text(),
                    url = element.select("h2 > a").attr("href"),
                    text = element.select(".b_caption p, .b_snippet").text()
                )
            }.filter { it.url.startsWith("http") }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun searchDuckDuckGo(query: String, limit: Int): List<SearchResultItem> {
        return try {
            // 使用 DDG 的 HTML 版本（不需要 JS）
            val url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8")
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .timeout(5000)
                .get()

            doc.select(".result").take(limit).map { element ->
                SearchResultItem(
                    title = element.select(".result__title").text(),
                    url = element.select(".result__url").text().trim(),
                    text = element.select(".result__snippet").text()
                )
            }.filter { it.url.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
