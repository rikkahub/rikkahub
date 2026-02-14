package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import okhttp3.Request
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

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

    // 搜索源标识
    private const val SOURCE_SEARXNG = "searxng"
    private const val SOURCE_DDG = "duckduckgo"
    private const val SOURCE_BING = "bing"
    private const val SOURCE_SOGOU = "sogou"
    private const val SOURCE_GOOGLE = "google"

    // 低复杂度稳定性参数
    private const val GLOBAL_MIN_SEARCH_INTERVAL_MS = 450L
    private const val SOURCE_COOLDOWN_MS = 90_000L
    private const val MAX_RETRY_ATTEMPTS = 2
    private const val INITIAL_RETRY_BACKOFF_MS = 350L

    // 统一的请求指纹池（降低固定特征）
    private val USER_AGENTS = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_6_1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.6723.91 Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.6723.116 Safari/537.36",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1"
    )

    private val inFlightSearches = ConcurrentHashMap<String, Deferred<List<SearchResultItem>>>()
    private val sourceCooldownUntil = ConcurrentHashMap<String, Long>()
    private val sourceFailureCount = ConcurrentHashMap<String, Int>()
    private val searchLock = Any()

    @Volatile
    private var lastSearchAtMs: Long = 0L

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
            val normalizedQuery = "${query.trim().lowercase()}#${commonOptions.resultSize}"

            val currentDeferred = inFlightSearches[normalizedQuery]
            if (currentDeferred != null) {
                val mergedResults = currentDeferred.await()
                require(mergedResults.isNotEmpty()) { "Search failed: no results found" }
                return@runCatching SearchResult(items = mergedResults.take(commonOptions.resultSize * 2))
            }

            val deferred = CompletableDeferred<List<SearchResultItem>>()
            val existing = inFlightSearches.putIfAbsent(normalizedQuery, deferred)
            if (existing != null) {
                val mergedResults = existing.await()
                require(mergedResults.isNotEmpty()) { "Search failed: no results found" }
                return@runCatching SearchResult(items = mergedResults.take(commonOptions.resultSize * 2))
            }

            try {
                enforceGlobalSearchInterval()

                val allResults = runSearchPipelines(query, commonOptions.resultSize)

                // 过滤和排序
                val filteredResults = allResults
                    .filter { isValidResult(it) }  // 过滤低质量结果
                    .distinctBy { normalizeUrl(it.url) }  // URL 标准化去重
                    .sortedByDescending { scoreResult(it) }  // 简单评分排序

                require(filteredResults.isNotEmpty()) {
                    "Search failed: no results found"
                }

                val finalResults = filteredResults.take(commonOptions.resultSize * 2)
                deferred.complete(finalResults)
                SearchResult(items = finalResults)  // 返回足够多的结果
            } catch (e: Exception) {
                deferred.completeExceptionally(e)
                throw e
            } finally {
                inFlightSearches.remove(normalizedQuery)
            }
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
                    .userAgent(randomUserAgent())
                    .header("Accept-Language", randomAcceptLanguage())
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

    /**
     * 搜索执行编排（低复杂度）：按稳定优先顺序逐源尝试，达到目标后提前停止
     */
    private suspend fun runSearchPipelines(query: String, limit: Int): List<SearchResultItem> {
        val targetCount = (limit * 3).coerceAtLeast(limit + 4)
        val sourceOrder = listOf(SOURCE_SEARXNG, SOURCE_DDG, SOURCE_BING, SOURCE_SOGOU, SOURCE_GOOGLE)
        val collected = mutableListOf<SearchResultItem>()

        for ((index, source) in sourceOrder.withIndex()) {
            if (collected.size >= targetCount) break

            if (index > 0) {
                delay(Random.nextLong(120L, 320L))
            }

            val sourceResults = when (source) {
                SOURCE_SEARXNG -> searchSearXNG(query, limit)
                SOURCE_DDG -> searchDuckDuckGo(query, limit)
                SOURCE_BING -> searchBing(query, limit)
                SOURCE_SOGOU -> searchSogou(query, limit)
                SOURCE_GOOGLE -> searchGoogle(query, limit)
                else -> emptyList()
            }

            if (sourceResults.isNotEmpty()) {
                collected += sourceResults
            }
        }

        return collected
    }

    /**
     * Bing 搜索入口（带重试、冷却和随机指纹）
     */
    internal suspend fun searchBing(query: String, limit: Int): List<SearchResultItem> {
        return executeSourceSearch(SOURCE_BING) {
            val url = "https://www.bing.com/search?q=" + URLEncoder.encode(query, "UTF-8")
            val doc = Jsoup.connect(url)
                .userAgent(randomUserAgent())
                .header("Accept-Language", randomAcceptLanguage())
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
        }
    }

    /**
     * DuckDuckGo 搜索入口（带重试、冷却和随机指纹）
     */
    internal suspend fun searchDuckDuckGo(query: String, limit: Int): List<SearchResultItem> {
        return executeSourceSearch(SOURCE_DDG) {
            val url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8")
            val doc = Jsoup.connect(url)
                .userAgent(randomUserAgent())
                .header("Accept-Language", randomAcceptLanguage())
                .timeout(5000)
                .get()

            doc.select(".result").take(limit).mapNotNull { element ->
                // 从 result__a 的 href 提取真实链接，并解码 uddg 跳转
                val href = element.select("a.result__a").attr("href")
                val realUrl = extractDuckDuckGoRealUrl(href)

                if (realUrl.isBlank()) {
                    null
                } else {
                    SearchResultItem(
                        title = element.select(".result__a").text(),
                        url = realUrl,
                        text = element.select(".result__snippet").text()
                    )
                }
            }
        }
    }

    /**
     * 解码 DuckDuckGo 跳转链接，提取真实 URL
     */
    private fun extractDuckDuckGoRealUrl(href: String): String {
        if (href.isBlank()) return ""

        // 直接是真实链接（非跳转）
        if (href.startsWith("http") && !href.contains("duckduckgo.com/l/?")) {
            return href
        }

        // 解析 uddg 跳转链接
        if (href.contains("duckduckgo.com/l/?") && href.contains("uddg=")) {
            val uddgStart = href.indexOf("uddg=") + 5
            val uddgEnd = href.indexOf("&", uddgStart).takeIf { it > 0 } ?: href.length
            val encodedUrl = href.substring(uddgStart, uddgEnd)
            return try {
                java.net.URLDecoder.decode(encodedUrl, "UTF-8")
            } catch (_: Exception) {
                ""
            }
        }

        return ""
    }

    /**
     * 搜狗搜索入口（带重试、冷却和随机指纹）
     */
    internal suspend fun searchSogou(query: String, limit: Int): List<SearchResultItem> {
        return executeSourceSearch(SOURCE_SOGOU) {
            val url = "https://www.sogou.com/web?query=" + URLEncoder.encode(query, "UTF-8")
            val doc = Jsoup.connect(url)
                .userAgent(randomUserAgent())
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
        }
    }

    /**
     * Google 搜索入口（优先 Startpage，失败时回退 Google 直连）
     */
    internal suspend fun searchGoogle(query: String, limit: Int): List<SearchResultItem> {
        return executeSourceSearch(SOURCE_GOOGLE) {
            try {
                val url = "https://www.startpage.com/sp/search?query=" + URLEncoder.encode(query, "UTF-8")
                val doc = Jsoup.connect(url)
                    .userAgent(randomUserAgent())
                    .header("Accept-Language", randomAcceptLanguage(Locale.US))
                    .timeout(8000)
                    .get()

                doc.select(".w-gl__result").take(limit).map { element ->
                    SearchResultItem(
                        title = element.select("h3").text(),
                        url = element.select("a").attr("href"),
                        text = element.select(".w-gl__description").text()
                    )
                }.filter { it.url.startsWith("http") }
            } catch (_: Exception) {
                searchGoogleDirect(query, limit)
            }
        }
    }

    /**
     * 直接 Google 搜索（备用）
     */
    private fun searchGoogleDirect(query: String, limit: Int): List<SearchResultItem> {
        val url = "https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8") + "&num=$limit"
        val doc = Jsoup.connect(url)
            .userAgent(randomUserAgent())
            .header("Accept-Language", randomAcceptLanguage(Locale.US))
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

    /**
     * SearXNG 搜索入口（实例随机顺序，成功即返回）
     */
    internal suspend fun searchSearXNG(query: String, limit: Int): List<SearchResultItem> {
        return executeSourceSearch(SOURCE_SEARXNG) {
            val instances = SEARXNG_INSTANCES.shuffled()
            for ((index, instanceUrl) in instances.withIndex()) {
                if (index > 0) {
                    delay(Random.nextLong(80L, 220L))
                }

                val results = try {
                    searchSearXNGInstance(instanceUrl, query, limit)
                } catch (_: Exception) {
                    emptyList()
                }

                if (results.isNotEmpty()) {
                    return@executeSourceSearch results
                }
            }
            emptyList()
        }
    }

    /**
     * 从单个 SearXNG 实例获取结果
     */
    private fun searchSearXNGInstance(instanceUrl: String, query: String, limit: Int): List<SearchResultItem> {
        val url = instanceUrl.trimEnd('/') +
                "/search?q=" + URLEncoder.encode(query, "UTF-8") +
                "&format=json"

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("Accept-Language", randomAcceptLanguage())
            .header("User-Agent", randomUserAgent())
            .build()

        searXNGClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw SourceHttpException(response.code)
            }

            val body = response.body.string()
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
    }

    /**
     * 统一源请求执行器：负责冷却、重试退避、失败统计
     */
    private suspend fun executeSourceSearch(
        source: String,
        fetcher: suspend () -> List<SearchResultItem>
    ): List<SearchResultItem> {
        if (isSourceCoolingDown(source)) {
            return emptyList()
        }

        var backoffMs = INITIAL_RETRY_BACKOFF_MS
        repeat(MAX_RETRY_ATTEMPTS + 1) { attempt ->
            try {
                if (attempt > 0) {
                    delay(backoffMs + Random.nextLong(80L, 220L))
                    backoffMs = (backoffMs * 2).coerceAtMost(2_400L)
                }

                val results = fetcher()
                if (results.isEmpty()) {
                    throw SourceEmptyResultException("Empty results (possibly blocked)")
                }
                markSourceSuccess(source)
                return results
            } catch (e: Exception) {
                val statusCode = extractStatusCode(e)
                markSourceFailure(source, statusCode)

                val canRetry = attempt < MAX_RETRY_ATTEMPTS && isRetriable(e, statusCode) && !isSourceCoolingDown(source)
                if (!canRetry) {
                    return emptyList()
                }
            }
        }

        return emptyList()
    }

    /**
     * 判断搜索源是否处于冷却期
     */
    private fun isSourceCoolingDown(source: String): Boolean {
        val cooldownUntil = sourceCooldownUntil[source] ?: return false
        if (System.currentTimeMillis() >= cooldownUntil) {
            sourceCooldownUntil.remove(source)
            return false
        }
        return true
    }

    /**
     * 源请求成功后清理失败计数
     */
    private fun markSourceSuccess(source: String) {
        sourceFailureCount.remove(source)
        sourceCooldownUntil.remove(source)
    }

    /**
     * 记录源失败并按策略触发冷却
     */
    private fun markSourceFailure(source: String, statusCode: Int?) {
        val failures = (sourceFailureCount[source] ?: 0) + 1
        sourceFailureCount[source] = failures

        val shouldCooldown = statusCode == 403 || statusCode == 429 || failures >= 3
        if (shouldCooldown) {
            sourceCooldownUntil[source] = System.currentTimeMillis() + SOURCE_COOLDOWN_MS
        }
    }

    /**
     * 是否属于可重试错误
     */
    private fun isRetriable(error: Exception, statusCode: Int?): Boolean {
        if (statusCode != null) {
            return statusCode == 408 || statusCode == 429 || statusCode >= 500
        }
        return error is IOException || error.cause is IOException
    }

    /**
     * 从异常中提取 HTTP 状态码
     */
    private fun extractStatusCode(error: Exception): Int? {
        return when (error) {
            is HttpStatusException -> error.statusCode
            is SourceHttpException -> error.statusCode
            else -> {
                (error.cause as? HttpStatusException)?.statusCode
                    ?: (error.cause as? SourceHttpException)?.statusCode
            }
        }
    }

    /**
     * 生成随机 User-Agent
     */
    private fun randomUserAgent(): String {
        return USER_AGENTS[Random.nextInt(USER_AGENTS.size)]
    }

    /**
     * 生成轻量随机 Accept-Language
     */
    private fun randomAcceptLanguage(locale: Locale = Locale.getDefault()): String {
        val language = locale.language.ifBlank { "en" }
        val country = locale.country.ifBlank { if (language == "zh") "CN" else "US" }
        val primary = "$language-$country"
        val candidates = listOf(
            "$primary,$language;q=0.9,en;q=0.6",
            "$primary,$language;q=0.8,en-US;q=0.6,en;q=0.4",
            "$language,$primary;q=0.9,en;q=0.5"
        )
        return candidates[Random.nextInt(candidates.size)]
    }

    /**
     * 控制全局查询间隔，避免短时间高频请求
     */
    private suspend fun enforceGlobalSearchInterval() {
        val waitMs = synchronized(searchLock) {
            val now = System.currentTimeMillis()
            val earliest = lastSearchAtMs + GLOBAL_MIN_SEARCH_INTERVAL_MS
            val scheduledAt = if (now >= earliest) {
                now
            } else {
                earliest + Random.nextLong(60L, 180L)
            }
            lastSearchAtMs = scheduledAt
            (scheduledAt - now).coerceAtLeast(0L)
        }

        if (waitMs > 0) {
            delay(waitMs)
        }
    }

    /**
     * 内部 HTTP 异常（用于非 Jsoup 通道的状态码传递）
     */
    private class SourceHttpException(val statusCode: Int) : IOException("HTTP $statusCode")

    /**
     * 搜索结果为空异常（可能是被拦截）
     */
    private class SourceEmptyResultException(message: String) : IOException(message)


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
