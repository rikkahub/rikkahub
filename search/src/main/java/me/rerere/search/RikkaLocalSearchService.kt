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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.Locale

import androidx.compose.ui.res.stringResource
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

object RikkaLocalSearchService : SearchService<SearchServiceOptions.RikkaLocalOptions> {
    // Built-in SearXNG public instances - fast, stable, 100% uptime as of 2025-2026
    private val SEARXNG_INSTANCES = listOf(
        "https://searx.lunar.icu",      // DE, 0.038s, 99% uptime
        "https://search.url4irl.com",   // DE, 0.074s, 100% uptime
        "https://sx.catgirl.cloud",     // DE, 0.108s, 100% uptime
        "https://search.inetol.net",    // ES, 0.546s, 100% uptime
        "https://search.rowie.at"       // AT, 0.748s, 100% uptime
    )

    private val searXNGClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val searXNGJson = Json { ignoreUnknownKeys = true }
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
        context: android.content.Context,
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.RikkaLocalOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")

            // 并发调用多个源：Bing + DuckDuckGo + SearXNG
            val tasks = listOf(
                async { searchBing(query, commonOptions.resultSize) },
                async { searchDuckDuckGo(query, commonOptions.resultSize) },
                async { searchSearXNG(query, commonOptions.resultSize) }
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
        context: android.content.Context,
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.RikkaLocalOptions
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = params["url"]?.jsonPrimitive?.content ?: error("url is required")

            // Try to use WebViewCrawler first for better compatibility
            val html = try {
                WebViewCrawler.scrape(context, url)
            } catch (e: Exception) {
                // Fallback to Jsoup if WebView fails (though WebViewCrawler has its own timeout)
                e.printStackTrace()
                Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                    .timeout(10000)
                    .get()
                    .outerHtml()
            }

            val markdown = LocalReader.extract(html)
            val title = Jsoup.parse(html).title()
            val description = Jsoup.parse(html).select("meta[name=description]").attr("content")

            ScrapedResult(
                urls = listOf(
                    ScrapedResultUrl(
                        url = url,
                        content = markdown,
                        metadata = ScrapedResultMetadata(
                            title = title,
                            description = description
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

    /**
     * 使用内置的 SearXNG 公共实例进行搜索
     * 会并发尝试多个实例，返回第一个成功的结果
     */
    private suspend fun searchSearXNG(query: String, limit: Int): List<SearchResultItem> = withContext(Dispatchers.IO) {
        // 并发尝试所有实例，谁先返回用谁
        val tasks = SEARXNG_INSTANCES.map { instanceUrl ->
            async {
                try {
                    searchSearXNGInstance(instanceUrl, query, limit)
                } catch (e: Exception) {
                    e.printStackTrace()
                    emptyList()
                }
            }
        }

        // 等待所有完成，取第一个非空结果
        val results = tasks.awaitAll()
        results.firstOrNull { it.isNotEmpty() } ?: emptyList()
    }

    /**
     * 从单个 SearXNG 实例获取搜索结果
     */
    private fun searchSearXNGInstance(instanceUrl: String, query: String, limit: Int): List<SearchResultItem> {
        val url = instanceUrl.trimEnd('/') +
                "/search?q=" + URLEncoder.encode(query, "UTF-8") +
                "&format=json"

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .build()

        val response = searXNGClient.newCall(request).execute()
        if (!response.isSuccessful) {
            return emptyList()
        }

        val body = response.body?.string() ?: return emptyList()
        val searchResponse = searXNGJson.decodeFromString<SearXNGResponse>(body)

        return searchResponse.results
            .take(limit)
            .map { result ->
                SearchResultItem(
                    title = result.title,
                    url = result.url,
                    text = result.content
                )
            }
            .filter { it.url.startsWith("http") }
    }

    // SearXNG 响应数据类
    @Serializable
    private data class SearXNGResponse(
        @SerialName("results")
        val results: List<SearXNGResult>
    )

    @Serializable
    private data class SearXNGResult(
        @SerialName("url")
        val url: String,
        @SerialName("title")
        val title: String,
        @SerialName("content")
        val content: String
    )
}
