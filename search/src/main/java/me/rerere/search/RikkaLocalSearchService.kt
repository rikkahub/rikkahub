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

            // 并发调用多个源：Bing + DuckDuckGo + SearXNG + Sogou + Google
            val tasks = listOf(
                async { searchBing(query, commonOptions.resultSize) },
                async { searchDuckDuckGo(query, commonOptions.resultSize) },
                async { searchSearXNG(query, commonOptions.resultSize) },
                async { searchSogou(query, commonOptions.resultSize) },
                async { searchGoogle(query, commonOptions.resultSize) }
            )

            val allResults = tasks.awaitAll().flatten()

            // 过滤和排序
            val filteredResults = allResults
                .filter { isValidResult(it) }  // 过滤低质量结果
                .distinctBy { normalizeUrl(it.url) }  // URL 标准化去重
                .sortedByDescending { scoreResult(it) }  // 简单评分排序

            require(filteredResults.isNotEmpty()) {
                "Search failed: no results found"
            }

            SearchResult(items = filteredResults.take(commonOptions.resultSize * 2))  // 返回足够多的结果
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
     * 搜狗搜索 - 可获取微信公众号内容
     */
    private suspend fun searchSogou(query: String, limit: Int): List<SearchResultItem> {
        return try {
            val url = "https://www.sogou.com/web?query=" + URLEncoder.encode(query, "UTF-8")
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .referrer("https://www.sogou.com/")
                .timeout(5000)
                .get()

            doc.select(".vrwrap").take(limit).map { element ->
                SearchResultItem(
                    title = element.select("h3 a").text(),
                    url = element.select("h3 a").attr("href"),
                    text = element.select(".str-text-info, .space-txt").text()
                )
            }.filter { it.url.startsWith("http") }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Google 搜索 (通过 Startpage 代理，避免直接被墙)
     * Startpage 使用 Google 搜索结果但保护隐私
     */
    private suspend fun searchGoogle(query: String, limit: Int): List<SearchResultItem> {
        return try {
            // 使用 Startpage 作为 Google 代理
            val url = "https://www.startpage.com/sp/search?query=" + URLEncoder.encode(query, "UTF-8")
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .timeout(8000)
                .get()

            doc.select(".w-gl__result").take(limit).map { element ->
                SearchResultItem(
                    title = element.select("h3").text(),
                    url = element.select("a").attr("href"),
                    text = element.select(".w-gl__description").text()
                )
            }.filter { it.url.startsWith("http") }
        } catch (e: Exception) {
            // Startpage 失败时尝试直接 Google (可能在某些网络环境下可用)
            try {
                searchGoogleDirect(query, limit)
            } catch (e2: Exception) {
                emptyList()
            }
        }
    }

    /**
     * 直接 Google 搜索 (备用方案)
     */
    private fun searchGoogleDirect(query: String, limit: Int): List<SearchResultItem> {
        val url = "https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8") + "&num=$limit"
        val doc = Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .cookie("CONSENT", "YES+")
            .timeout(5000)
            .get()

        return doc.select("#search .g").take(limit).map { element ->
            SearchResultItem(
                title = element.select("h3").text(),
                url = element.select("a").attr("href"),
                text = element.select("[data-sncf], .VwiC3b").text()
            )
        }.filter { it.url.startsWith("http") }
    }

    // ==================== 结果过滤和排序 ====================

    /**
     * 检查是否为有效的搜索结果
     */
    private fun isValidResult(item: SearchResultItem): Boolean {
        // 过滤空白标题
        if (item.title.isBlank()) return false
        // 过滤无效 URL
        if (!item.url.startsWith("http")) return false
        // 过滤广告链接
        val adPatterns = listOf("/ad/", "/ads/", "/sponsored", "/promo/", "advertising", "doubleclick", "googlesyndication")
        if (adPatterns.any { item.url.contains(it, ignoreCase = true) }) return false
        return true
    }

    /**
     * URL 标准化，用于去重
     */
    private fun normalizeUrl(url: String): String {
        return url
            .lowercase()
            .removeSuffix("/")
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
    }

    /**
     * 对搜索结果进行简单评分 (分数越高越好)
     */
    private fun scoreResult(item: SearchResultItem): Int {
        var score = 0

        // 标题长度适中加分
        if (item.title.length in 10..80) score += 10

        // 摘要长度加分 (有内容的摘要更好)
        if (item.text.length > 50) score += 5
        if (item.text.length > 100) score += 5

        // 来自高质量域名加分
        val highQualityDomains = listOf(
            "wikipedia.org", "github.com", "stackoverflow.com", "reddit.com",
            "medium.com", "zhihu.com", "csdn.net", "juejin.cn",
            "docs.", "developer.", "documentation"
        )
        if (highQualityDomains.any { item.url.contains(it, ignoreCase = true) }) {
            score += 15
        }

        // 过于短的摘要减分
        if (item.text.length < 20) score -= 5

        return score
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
