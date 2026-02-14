package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    private val probeClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(1200, TimeUnit.MILLISECONDS)
        .readTimeout(1200, TimeUnit.MILLISECONDS)
        .callTimeout(2400, TimeUnit.MILLISECONDS)
        .build()

    private val searXNGJson = Json { ignoreUnknownKeys = true }
    private const val SOURCE_SEARXNG = "searxng"
    private const val SOURCE_DDG = "duckduckgo"
    private const val SOURCE_BING = "bing"
    private const val SOURCE_SOGOU = "sogou"
    private const val SOURCE_BAIDU = "baidu"
    private const val SOURCE_360 = "so360"
    private const val SOURCE_SHENMA = "shenma"
    private const val SOURCE_GOOGLE = "google"

    private val CN_SOURCES = setOf(SOURCE_SOGOU, SOURCE_BAIDU, SOURCE_360, SOURCE_SHENMA)
    private val GLOBAL_SOURCES = setOf(SOURCE_SEARXNG, SOURCE_DDG, SOURCE_BING, SOURCE_GOOGLE)

    private const val ROUTING_CACHE_TTL_MS = 180_000L
    private const val PROBE_CACHE_TTL_MS = 120_000L
    private const val GLOBAL_MIN_SEARCH_INTERVAL_MS = 450L
    private const val SOURCE_COOLDOWN_MS = 90_000L
    private const val MAX_RETRY_ATTEMPTS = 2
    private const val INITIAL_RETRY_BACKOFF_MS = 350L

    // Randomized user agents to reduce fingerprinting.
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
    private val sourceProbeCache = ConcurrentHashMap<String, SourceProbe>()
    private val searchLock = Any()

    @Volatile
    private var lastSearchAtMs: Long = 0L

    @Volatile
    private var cachedRoutingDecision: RoutingDecision? = null

    override val name: String = "RikkaLocal"

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
                val filteredResults = allResults
                    .filter { isValidResult(it) }
                    .distinctBy { normalizeUrl(it.url) }
                    .sortedByDescending { scoreResult(it) }
                require(filteredResults.isNotEmpty()) {
                    "Search failed: no results found"
                }

                val finalResults = filteredResults.take(commonOptions.resultSize * 2)
                deferred.complete(finalResults)
                SearchResult(items = finalResults)
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
     * 闂傚倸鍊烽懗鍫曞箠閹捐瑙﹂悗锝庡墮閸ㄦ繈骞栧ǎ顒€濡肩痪鎯с偢閺屾洘绻涢悙顒佺彅缂備胶濮伴崕鎶藉箟閹间礁绠ｉ柨鏃囥€€閸嬫挻绗熼埀顒勭嵁鐎ｎ喗鍋愰柣銏㈩暜缁辨娊姊绘担鍛婃儓缂佸鐓″畷婵嗙暆閳ь剛鍒掗銏犵缂備焦顭囬崣鍡椻攽閻愭潙鐏︽い顓炴处閺呭爼寮崒婊咃紳閻庡箍鍎遍幊蹇浰夐悙纰樺亾閸偅绶查悗姘煎墴椤㈡ɑ绺界粙鍨€垮┑鈽嗗灣閸樠囧春瀹€鍕拺缁绢厼鎳庨ˉ宥夋煙濞茶绨介柟宄邦儔閺佹劙宕堕…鎴炵稐婵犳鍠楅…鍫ュ春閺嶎厼鐤炬い鎺嶇劍閸欏繑鎱ㄥΔ鈧Λ妤呯嵁閺嶎厽鐓熼柨鏇氱閸氬湱绱掓潏銊ョ闁逞屽墾缂嶅棙绂嶅鍫濇辈闂侇剙绉甸悡鐔兼煥濠靛棙鎼愬ù婊堢畺閹鎮介棃娑樹粯闁句紮缍侀弻娑樷攽閸℃浠奸悗娈垮枟椤ㄥ棙绌辨繝鍥ㄥ€锋い蹇撳閸嬫捇寮撮姀鐘殿啇濡炪倖鍔戦崐娑欐叏閾忣偁浜滈柟鎯у船閻忣亪鏌ｉ幒鎴吋闁哄瞼鍠栭弻鍥晝閳ь剟鐛鈧弻锝夊冀椤撴繃鍠氶梺璇″枟椤牏绮诲☉銏犵睄闁稿本绮庨弳浼存⒒娴ｉ涓查悗闈涚焸瀹曞綊宕奸弴鐐寸€悗骞垮劚閹虫劙寮抽崱娑欑厱闁硅埇鍔嶅▍鍛箾閹冲嘲娲﹂埛鎴︽煕濞戞﹫宸ョ紒妤佽壘椤潡鎮烽悧鍫闁绘挶鍊曢湁闁稿繐鍚嬬紞鎴︽煕閹般劌浜鹃梻鍌欑劍閹爼宕曞鍫濈妞ゆ劗鍠庢禍楣冩煟閵忕姵鍟為柣鎾寸懅缁辨挻鎷呮慨鎴簼閹便劑宕惰閻斿棛鎲歌箛鏃傜闁逞屽墰閳ь剝顫夊ú姗€宕归悽绯曗偓锕傚Ω閳轰胶顦伴梻鍌氱墛缁嬫垿藟?     */
    private suspend fun runSearchPipelines(query: String, limit: Int): List<SearchResultItem> {
        val targetCount = (limit * 3).coerceAtLeast(limit + 4)
        val routing = resolveRoutingDecision()
        val sourceOrder = routing.orderedSources
        val collected = mutableListOf<SearchResultItem>()

        for ((index, source) in sourceOrder.withIndex()) {
            if (collected.size >= targetCount) break

            if (index > 0) {
                delay(Random.nextLong(120L, 320L))
            }

            val reachableHint = routing.reachableBySource[source]
            val retryBudget = determineRetryBudget(source, index, routing.mode, reachableHint)

            val sourceResults = when (source) {
                SOURCE_SEARXNG -> searchSearXNG(query, limit, reachableHint, retryBudget)
                SOURCE_DDG -> searchDuckDuckGo(query, limit, reachableHint, retryBudget)
                SOURCE_BING -> searchBing(query, limit, reachableHint, retryBudget)
                SOURCE_SOGOU -> searchSogou(query, limit, reachableHint, retryBudget)
                SOURCE_BAIDU -> searchBaidu(query, limit, reachableHint, retryBudget)
                SOURCE_360 -> search360(query, limit, reachableHint, retryBudget)
                SOURCE_SHENMA -> searchShenma(query, limit, reachableHint, retryBudget)
                SOURCE_GOOGLE -> searchGoogle(query, limit, reachableHint, retryBudget)
                else -> emptyList()
            }

            if (sourceResults.isNotEmpty()) {
                collected += sourceResults
            }
        }

        return collected
    }

    /**
     * Bing 闂傚倸鍊烽懗鍫曞箠閹捐瑙﹂悗锝庡墮閸ㄦ繈骞栧ǎ顒€濡肩痪鎯с偢閺屾洘绻涢悙顒佺彅缂備讲鍋撻柛宀€鍋為悡蹇擃熆鐠鸿櫣澧曢柛鏃€姘ㄧ槐鎾愁吋閸℃ê纾抽梺鍝勮閸斿矂鍩ユ径濞㈢喖鎮℃惔顔芥瘒闂傚倷鐒﹂惇褰掑春閸曨垰纾诲┑鐘叉搐閽冪喖鎮橀悙鐢垫憘闁告艾顑夐弻娑㈩敃閻樿尙浠剧紒妤佸灴濮婄粯绗熼埀顒€顭囪铻為柡鍐ㄥ€婚惌鍡椼€掑锝呬壕閻庤娲滄晶妤呭箚閺冨牆惟闁靛／灞炬暏闂傚倷绀侀幖顐λ囨导鏉戝瀭闁告挷璁查崑鎾愁潩椤撶姴寮ㄥ┑顔硷工椤嘲鐣烽妸鈺婃晣闁绘ɑ顔栭崬瑙勭節閻㈤潧顫掗柍褜鍓熷畷鎴﹀箻缂佹ǚ鎷洪梺闈╁瘜閸欏酣鎮為悙顒傜閻忕偛鍊告俊濂稿极閸喍绻嗛柕鍫濇噺閸ｆ椽鏌?     */
    internal suspend fun searchBing(
        query: String,
        limit: Int,
        reachableHint: Boolean? = null,
        maxRetryAttempts: Int = MAX_RETRY_ATTEMPTS
    ): List<SearchResultItem> {
        return executeSourceSearch(SOURCE_BING, reachableHint, maxRetryAttempts) {
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
     * DuckDuckGo 闂傚倸鍊烽懗鍫曞箠閹捐瑙﹂悗锝庡墮閸ㄦ繈骞栧ǎ顒€濡肩痪鎯с偢閺屾洘绻涢悙顒佺彅缂備讲鍋撻柛宀€鍋為悡蹇擃熆鐠鸿櫣澧曢柛鏃€姘ㄧ槐鎾愁吋閸℃ê纾抽梺鍝勮閸斿矂鍩ユ径濞㈢喖鎮℃惔顔芥瘒闂傚倷鐒﹂惇褰掑春閸曨垰纾诲┑鐘叉搐閽冪喖鎮橀悙鐢垫憘闁告艾顑夐弻娑㈩敃閻樿尙浠剧紒妤佸灴濮婄粯绗熼埀顒€顭囪铻為柡鍐ㄥ€婚惌鍡椼€掑锝呬壕閻庤娲滄晶妤呭箚閺冨牆惟闁靛／灞炬暏闂傚倷绀侀幖顐λ囨导鏉戝瀭闁告挷璁查崑鎾愁潩椤撶姴寮ㄥ┑顔硷工椤嘲鐣烽妸鈺婃晣闁绘ɑ顔栭崬瑙勭節閻㈤潧顫掗柍褜鍓熷畷鎴﹀箻缂佹ǚ鎷洪梺闈╁瘜閸欏酣鎮為悙顒傜閻忕偛鍊告俊濂稿极閸喍绻嗛柕鍫濇噺閸ｆ椽鏌?     */
    internal suspend fun searchDuckDuckGo(
        query: String,
        limit: Int,
        reachableHint: Boolean? = null,
        maxRetryAttempts: Int = MAX_RETRY_ATTEMPTS
    ): List<SearchResultItem> {
        return executeSourceSearch(SOURCE_DDG, reachableHint, maxRetryAttempts) {
            val url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8")
            val doc = Jsoup.connect(url)
                .userAgent(randomUserAgent())
                .header("Accept-Language", randomAcceptLanguage())
                .timeout(5000)
                .get()

            doc.select(".result").take(limit).mapNotNull { element ->
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
     * 闂傚倷娴囧畷鐢稿窗閹扮増鍋￠弶鍫氭櫅缁躲倝鏌涜椤ㄥ棝宕?DuckDuckGo 闂傚倷娴囧畷鍨叏閹绢喖绠规い鎰堕檮閸嬵亪鏌涢妷顔句汗鐟滅増甯掗獮銏＄箾閹寸偟鎳呴柛娆忓濮婅櫣绱掑Ο鍝勑曢梺鍛婃尰閻燂箑顕ラ崟顐悑濠㈣泛顑囬崣鍡椻攽閻愭潙鐏﹂拑杈ㄧ節閳ь剟骞嶉钘夋瀾婵☆偊顣﹂懗鍓佺玻閺冨倵鍋撶憴鍕┛缂傚秳绀侀锝嗙鐎ｅ灚鏅濋梺鎸庣箓閹虫劙宕濋鐐粹拺?URL
     */
    private fun extractDuckDuckGoRealUrl(href: String): String {
        if (href.isBlank()) return ""

        if (href.startsWith("http") && !href.contains("duckduckgo.com/l/?")) {
            return href
        }

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
     * 闂傚倸鍊烽懗鍫曞箠閹捐瑙﹂悗锝庡墮閸ㄦ繈骞栨潏鍓у矝闁稿鎸搁～婵嬵敆閸屽們鍕垫闁绘劕顕晶顏呫亜椤撴粌濮傜€规洏鍔戦、姗€鎮欓悧鍫濇灓闂傚倸鍊烽懗鍫曗€﹂崼銏″床闁割偁鍎辩壕鍧楀级閸偄浜栧ù婊嗩潐缁绘盯骞嬪▎蹇曚痪闂佸搫鎷嬮崜鐔煎蓟濞戙埄鏁冮柨婵嗘川閻ｅジ姊烘潪浼存闁告梹鍨甸～蹇撁洪鍕唽闂佸湱鍎ら弸顒勫Ψ閳哄倻鍘介梺鎸庢椤曆囨倶閿曗偓閳规垿鍨惧畷鍥ㄦ喖闂佺懓鍢查幊鎰垝濞嗘挸绠伴幖杈剧岛閸嬫挻绻濆顓涙嫼闂佸憡绋戦…顒€鈻撻弴鐔虹闁稿繗鍋愰幊鍥殽閻愯尙绠查悗浣冨亹閳ь剚绋掕彜闁圭瀚板Λ鍛搭敃閵忊€愁槱濠殿喖锕ゅ﹢閬嶆嚍閸楃儐娼╅柤鍝ユ暩閸樼數绱撴担鍓插剱閻庣瑳鍐惧晠婵犻潧娲﹂崣蹇涙偡濞嗗繐顏存繛鍫熺矒閺?     */
    internal suspend fun searchSogou(
        query: String,
        limit: Int,
        reachableHint: Boolean? = null,
        maxRetryAttempts: Int = MAX_RETRY_ATTEMPTS
    ): List<SearchResultItem> {
        return executeSourceSearch(SOURCE_SOGOU, reachableHint, maxRetryAttempts) {
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
     * Google 闂傚倸鍊烽懗鍫曞箠閹捐瑙﹂悗锝庡墮閸ㄦ繈骞栧ǎ顒€濡肩痪鎯с偢閺屾洘绻涢悙顒佺彅缂備讲鍋撻柛宀€鍋為悡蹇擃熆鐠鸿櫣澧曢柛鏃€姘ㄧ槐鎾愁吋閸℃ê纾抽梺鍝勮閸斿矂鍩ユ径濞㈢喖鎮℃惔顔芥瘑缂傚倸鍊风欢姘辨兜閹间礁鐭楅柛鎰╁妿閺?Startpage闂傚倸鍊烽悞锔锯偓绗涘懐鐭欓柟杈鹃檮閸ゆ劖銇勯弽顐粶濡楀懘姊洪悷閭﹀殶濠殿喚鏁搁埀顒佽壘椤兘寮婚弴鐔风窞婵炴垯鍨洪宥咁渻閵囶垯绀佸ú锕傛偂閺囥垺鐓忓璇″灠閸熲晛危閹扮増鈷戦梺顐ゅ仜閼活垶宕㈤幘顔界厱?Google 闂傚倸鍊烽懗鍫曞磿閻㈢鐤炬繝闈涚懁婢舵劕绠涙い鏃囨鎼村﹤鈹戦悙鏉戠仧闁搞劍妞藉?     */
    internal suspend fun searchBaidu(
        query: String,
        limit: Int,
        reachableHint: Boolean? = null,
        maxRetryAttempts: Int = MAX_RETRY_ATTEMPTS
    ): List<SearchResultItem> {
        return executeSourceSearch(SOURCE_BAIDU, reachableHint, maxRetryAttempts) {
            val url = "https://www.baidu.com/s?wd=" + URLEncoder.encode(query, "UTF-8")
            val doc = Jsoup.connect(url)
                .userAgent(randomUserAgent())
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .referrer("https://www.baidu.com/")
                .timeout(5000)
                .get()

            doc.select("#content_left .result, #content_left .result-op, #content_left .c-container")
                .take(limit)
                .mapNotNull { element ->
                    val anchor = element.selectFirst("h3 a") ?: return@mapNotNull null
                    val title = anchor.text().trim()
                    val href = anchor.attr("href").trim()
                    if (title.isBlank() || !href.startsWith("http")) {
                        return@mapNotNull null
                    }
                    SearchResultItem(
                        title = title,
                        url = href,
                        text = element.select(
                            ".c-abstract, .c-span-last, .content-right_8Zs40, .c-font-normal"
                        ).text().trim()
                    )
                }
        }
    }

    internal suspend fun search360(
        query: String,
        limit: Int,
        reachableHint: Boolean? = null,
        maxRetryAttempts: Int = MAX_RETRY_ATTEMPTS
    ): List<SearchResultItem> {
        return executeSourceSearch(SOURCE_360, reachableHint, maxRetryAttempts) {
            val url = "https://www.so.com/s?q=" + URLEncoder.encode(query, "UTF-8")
            val doc = Jsoup.connect(url)
                .userAgent(randomUserAgent())
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .referrer("https://www.so.com/")
                .timeout(5000)
                .get()

            doc.select("li.res-list, li[class*=res-list]")
                .mapNotNull { element ->
                    val anchor = element.selectFirst("h3 a[href], h2 a[href], a[href]") ?: return@mapNotNull null
                    val title = anchor.text().trim().ifBlank { element.select("h3, h2").text().trim() }
                    val href = anchor.absUrl("href").ifBlank { anchor.attr("href").trim() }
                    if (title.isBlank() || !href.startsWith("http")) {
                        return@mapNotNull null
                    }
                    SearchResultItem(
                        title = title,
                        url = href,
                        text = element.select(".res-desc, .mh-detail, p").text().trim()
                    )
                }
                .distinctBy { normalizeUrl(it.url) }
                .take(limit)
        }
    }

    internal suspend fun searchShenma(
        query: String,
        limit: Int,
        reachableHint: Boolean? = null,
        maxRetryAttempts: Int = MAX_RETRY_ATTEMPTS
    ): List<SearchResultItem> {
        return executeSourceSearch(SOURCE_SHENMA, reachableHint, maxRetryAttempts) {
            val url = "https://m.sm.cn/s?q=" + URLEncoder.encode(query, "UTF-8")
            val doc = Jsoup.connect(url)
                .userAgent(randomUserAgent())
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .referrer("https://m.sm.cn/")
                .timeout(5000)
                .get()

            doc.select("a[href]")
                .mapNotNull { anchor ->
                    val href = anchor.absUrl("href").ifBlank { anchor.attr("href").trim() }
                    if (!href.startsWith("http") || isBlockedNavigationUrl(href)) {
                        return@mapNotNull null
                    }

                    val title = anchor.text().trim()
                    if (title.length < 4) {
                        return@mapNotNull null
                    }

                    SearchResultItem(
                        title = title,
                        url = href,
                        text = anchor.parent()?.select("p, .desc, .summary")?.text()?.trim().orEmpty()
                    )
                }
                .distinctBy { normalizeUrl(it.url) }
                .sortedByDescending { scoreShenmaCandidate(it, query) }
                .take(limit)
        }
    }

    private fun isBlockedNavigationUrl(url: String): Boolean {
        val lower = url.lowercase()
        val blockedKeywords = listOf(
            "m.sm.cn/adclick",
            "dailyact.cn/",
            "api.m.sm.cn/rest?method=policy",
            "wappass.baidu.com/static/captcha"
        )
        return blockedKeywords.any { lower.contains(it) }
    }

    private fun scoreShenmaCandidate(item: SearchResultItem, query: String): Int {
        var score = 0
        val queryLower = query.lowercase()
        val titleLower = item.title.lowercase()
        val urlLower = item.url.lowercase()
        if (titleLower.contains(queryLower)) score += 10
        if (urlLower.contains(queryLower)) score += 6
        if (item.text.length > 40) score += 3
        if (item.title.length > 70) score -= 3
        return score
    }

    internal suspend fun searchGoogle(
        query: String,
        limit: Int,
        reachableHint: Boolean? = null,
        maxRetryAttempts: Int = MAX_RETRY_ATTEMPTS
    ): List<SearchResultItem> {
        return executeSourceSearch(SOURCE_GOOGLE, reachableHint, maxRetryAttempts) {
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
     * 闂傚倸鍊烽懗鍫曞磿閻㈢鐤炬繛鎴欏灪閸嬨倝鏌曟繛褍瀚▓?Google 闂傚倸鍊烽懗鍫曞箠閹捐瑙﹂悗锝庡墮閸ㄦ繈骞栧ǎ顒€濡肩痪鎯с偢閺屾洘绻涢悙顒佺彆闂佸搫鎷嬮崜鐔煎蓟濞戙埄鏁冮柨婵嗘川椤撶厧鈹戦檱鐏忔瑩寮繝姘摕闁挎繂顦～鍛存煃閸濆嫬鏆欓悽顖樺妿缁?     */
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
     * SearXNG 闂傚倸鍊烽懗鍫曞箠閹捐瑙﹂悗锝庡墮閸ㄦ繈骞栧ǎ顒€濡肩痪鎯с偢閺屾洘绻涢悙顒佺彅缂備讲鍋撻柛宀€鍋為悡蹇擃熆鐠鸿櫣澧曢柛鏃€姘ㄧ槐鎾愁吋閸℃ê纾抽梺鍝勮閸斿矂鍩ユ径濞㈢喖鎮℃惔顔芥瘒闂傚倷鐒﹂惇褰掑磹閺囩姭鍋撳鐓庡箻缂侇噮鍙冮幃銏㈡偘閳ュ厖澹曢梺鎸庣箓妤犳悂寮搁悢鎼炰簻闁规儳纾粔娲煛瀹€瀣瘈鐎规洖銈搁幃銏ゅ箹閻愭媽妾搁梻鍌欑窔濞佳兾涘Δ鍐ｅ亾濞戞帗娅婃鐐叉瀵噣宕堕埡鍐╂緫闂備線娼ч敍蹇涘椽娴ｅ墣銉╂⒒閸屾瑨鍏岄柟铏崌閹椽濡搁埡浣侯槷闂佸搫娲ㄩ崰鎰板矗韫囨梻绠鹃柟瀛樼懃閻忊晝鐥娑樹壕闂備浇顕ф绋匡耿鏉堛劍鍙忓瀣捣娑撳秹鏌＄仦璇插姕闁绘挻娲熼弻鏇熺珶椤栨艾鏆欏鐟邦儑缁?     */
    internal suspend fun searchSearXNG(
        query: String,
        limit: Int,
        reachableHint: Boolean? = null,
        maxRetryAttempts: Int = MAX_RETRY_ATTEMPTS
    ): List<SearchResultItem> {
        return executeSourceSearch(SOURCE_SEARXNG, reachableHint, maxRetryAttempts) {
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
     * 濠电姷鏁搁崑娑㈩敋椤撶喐鍙忛悗娑欙供濞堢晫绱掔€ｎ厽纭堕柡鍡畵閺岋綁寮幐搴㈠枑闂?SearXNG 闂傚倷娴囬褎顨ョ粙鍖¤€块梺顒€绉寸壕缁樹繆椤栨艾鎮戞い鎰矙閺屾洘寰勯崱妯荤彆闂佹悶鍊栧濠氬焵椤掑倹鍤€閻庢凹鍘奸…鍨熼悡搴ｇ瓘闁荤姵浜介崝搴ｅ閽樺褰掓晲閸偅缍堥柣蹇撶箣閸楁娊寮?     */
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
     * 缂傚倸鍊搁崐鎼佸磹閻戣姤鍤勯柛顐ｆ礀缁愭鈧箍鍎卞ú銊╁础濮樿埖鐓涘璺侯儏閻忓秹鏌￠埀顒勬晝閸屾稓鍘甸梻渚囧弿缁犳垿鎮橀柆宥嗙厱闁靛牆娲ゆ禒锕傛煙娓氬灝濮傞柟顔惧厴瀵埖鎯旈幘鍏呭闁荤喐鐟ョ€氼厼鈻嶉悩缁樼厽闁挎繂鎳忓﹢浼存煕鐎ｎ偓鑰块柟顔斤耿閹瑧鎹勯惄鎺撴崌閺岋絽鈹戦崟顐熷亾濠靛钃熼柕濞垮劗濡插牊绻涢崱妤冨嚬閻庢稈鏅濈槐鎾存媴閹绘帊澹曢梺璇插嚱缂嶅棝宕滃鎵佸亾濮橆剦鐓奸柡灞诲妼閳规垿宕卞Ο鐑橆仩闂備礁鎼鍛存煀閿濆钃熸繛鎴烆焸閺冣偓閹峰懘鎮滃Ο娲诲敪闂傚倷绶氶埀顒傚仜閼活垶宕㈤崫銉х＜闁兼悂娼ч崫铏光偓瑙勬礃婵炲﹤鐣烽锕€唯闁挎洍鍋撻柣婵呭嵆濮婃椽宕崟顒傤槺闂佺懓鍟垮ú顓炵暦閹达箑绠婚悹鍥皺閸旓箑顪冮妶鍡楃瑨閻庢凹鍙冮幃鐐寸附閸涘﹤浠繛杈剧秮濞佳囧焵椤掍礁濮嶇€规洘鍨块獮妯尖偓娑櫭鎾绘⒑绾懎浜归柛瀣耿瀵爼顢橀姀锛勫幗闂佺粯锚閸熸寧鎱ㄩ崒鐐寸厱閹艰揪绱曢敍宥夋煕閹烘挸娴柟鐓庢贡閹叉挳宕熼顐熷亾?     */
    private suspend fun executeSourceSearch(
        source: String,
        reachableHint: Boolean? = null,
        maxRetryAttempts: Int = MAX_RETRY_ATTEMPTS,
        fetcher: suspend () -> List<SearchResultItem>
    ): List<SearchResultItem> {
        if (reachableHint == false || maxRetryAttempts < 0) {
            return emptyList()
        }

        if (isSourceCoolingDown(source)) {
            return emptyList()
        }

        var backoffMs = INITIAL_RETRY_BACKOFF_MS
        repeat(maxRetryAttempts + 1) { attempt ->
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

                val canRetry = attempt < maxRetryAttempts && isRetriable(e, statusCode) && !isSourceCoolingDown(source)
                if (!canRetry) {
                    return emptyList()
                }
            }
        }

        return emptyList()
    }

    /**
     * 闂傚倸鍊风粈渚€骞夐敍鍕殰闁搞儺鍓欑壕褰掓煛瀹ュ骸骞栭柦鍐枛閺屾盯濡烽鐓庮潻缂備胶濮鹃～澶屾崲濠靛洨绡€闁稿本绋戝▍锝夋⒑缁嬫鍎忕紒澶屾暩閹广垹鈹戞繝搴⑿梻浣告啞濮婂綊鎮ч弴鐐茬カ闂備礁缍婂Λ璺ㄧ矆娴ｇ儤鍠嗛柨鏇炲€归悡銉╂煛閸ヮ煈娈斿ù婊勫劤椤啴濡惰箛姘倕闂佸搫鎳忕划搴ｅ垝鐎ｎ亶鍚嬮柛鈩兠崝鍛存⒑閻愯棄鍔ユ繛鍛礋閹绻濆顓涙嫼闂佸憡绋戦…顒€鈻撻弮鍫熺厽閹烘娊宕濆畝鍕櫇?     */
    private fun isSourceCoolingDown(source: String): Boolean {
        val cooldownUntil = sourceCooldownUntil[source] ?: return false
        if (System.currentTimeMillis() >= cooldownUntil) {
            sourceCooldownUntil.remove(source)
            return false
        }
        return true
    }

    /**
     * 婵犵數濮烽弫鍛婃叏瀹ュ绀嬫い鎰╁焺濞兼瑦淇婇悙顏勨偓鏍洪敃鍌氱婵炲棙鍔栧▍蹇涙⒒娴ｄ警鐒鹃柡鍫墰缁瑩骞嬮敂钘夆偓鍫曟煕閵夘喖澧柣鎾存礋閺岋繝宕橀妸銉㈠亾閹间礁鍑犻幖娣妽閻撴瑦銇勯弮鍌滄憘婵炲牊妫冮弻鏇㈠幢濡儤璇為悗瑙勬磸閸旀垿銆佸☉妯锋婵☆垰鍢叉禍楣冩煟閹邦剚鈻曢柣鏂挎閺岀喖顢涘☉姗嗘缂備降鍔屽锟犲箖瀹勬壋鏋庢繛鍡楁禋濞差參姊洪悷鏉挎Щ妞ゆ垵顦靛畷娲礋椤栨氨顦ㄩ梺鍐叉惈閸嬪棝宕埀?     */
    private fun markSourceSuccess(source: String) {
        sourceFailureCount.remove(source)
        sourceCooldownUntil.remove(source)
    }

    /**
     * 闂傚倷娴囧畷鍨叏閹惰姤鍊块柨鏇楀亾妞ゎ厼鐏濊灒闁兼祴鏅濋ˇ顖炴倵楠炲灝鍔氶柟鍐差樀瀵劑鏁冮崒娑氬幍闂備緡鍙忕粻鎴﹀储闂堟耽鐟邦煥閸愶箑浠梺璇″枛缂嶅﹪鐛崶顒€鐓涘ù锝囶焾椤捇姊绘担钘夘棈鐎规洜鏁诲畷浼村冀椤撶姴绁﹂梺褰掑亰閸樿偐娆㈤悙纰樺亾閸忓浜鹃梺閫炲苯澧存鐐搭殔椤劑宕奸悢鍝勫箺闂備焦瀵х换鍌炲疮椤愶箑绀夐柕澹嫬浠忓┑鐘绘涧椤戝棝鎮￠弴鐔虹闁糕剝锚婵洭鏌ｈ箛瀣姦闁哄本鐩幃鈺呭蓟閵夈儱鍙婇柣?     */
    private fun markSourceFailure(source: String, statusCode: Int?) {
        // 闂傚倷娴囬褍顫濋敃鍌︾稏濠㈣泛鑻弸鍫⑩偓骞垮劚閹锋垿鎳撻崸妤佺厓闁靛／鍐х紦闂侀€炲苯澧悽顖椻偓宕囨殾闁靛ň鏅╅弫宥嗙箾閹寸們姘跺磻閹剧粯鍊烽柛婵嗗妤犲洭姊虹€圭姵銆冮柣鎺為檮瀵板嫰宕熼鍌滅槇闂侀€炲苯澧撮柡浣哥Ч瀹曠喖顢楅埀顒勵敁濞戙垺鈷戦柛婵嗗濡插吋绻涙径瀣缂侇喗妫冨畷绋课旀担鍝勫汲婵犵數鍋為崹鍫曗€﹂崶顒佸€块柛娑橈梗缁诲棝鏌ゆ總鍓叉澓闁搞倖鐟﹂妵?Map 闂傚倸鍊风粈渚€骞栭锕€鐤柣妤€鐗忕粻楣冩煃瑜滈崜姘┍婵犲洤绀堢憸宥夊礉鐎ｎ喗鐓涢柛娑卞幘閸╋綁鏌熼鐣屾噰闁诡喗绮岃灒闁煎鍊栬
        cleanupExpiredRecords()

        val failures = (sourceFailureCount[source] ?: 0) + 1
        sourceFailureCount[source] = failures

        val shouldCooldown = statusCode == 403 || statusCode == 429 || failures >= 3
        if (shouldCooldown) {
            sourceCooldownUntil[source] = System.currentTimeMillis() + SOURCE_COOLDOWN_MS
        }
    }

    /**
     * 婵犵數濮烽弫鎼佸磻閻愬搫绠伴柟闂寸缁犵娀鏌熼悧鍫熺凡缂佺姵鐗曢埞鎴︽偐閹绘帗娈叉繛瀛樺殠閸婃繈寮诲☉銏犖ㄩ柨婵嗘噹椤姊虹化鏇熸珔闁稿﹤娼″濠氭晲婢跺á鈺呮煏婢跺牆鍔村ù鐘层偢濮婃椽宕妷銉愩垽姊虹敮顔惧埌闁伙絿鍏橀弫宥夊礋椤愵偅顥￠梺璇插嚱缂嶄礁顭囪瀵偉銇愰幒鎾充画濠电姴锕ら幊搴ㄣ€傞崗鑲╃闁告瑥顦辨晶鍨殽閻愯尙绠婚柟顔ㄥ洤閱囬柣鏃囧煐閸炲姊绘担瑙勫仩闁稿孩绮嶉幏鍛存煥鐎ｎ偄闂梻鍌氬€烽悞锕傛儑瑜版帒绀夌€光偓閳ь剟鍩€椤掍礁鍤柛鎾跺枑娣囧﹪鎮界粙璺吅闂佺粯锚閸熷潡顢旈幖浣光拺闂傚牊渚楀Ο鍫ユ煟閹惧磭澧︾€规洏鍎甸崺鈧い鎺戝閳?     */
    private fun cleanupExpiredRecords() {
        val now = System.currentTimeMillis()
        // 婵犵數濮烽弫鎼佸磻閻愬搫绠伴柟闂寸缁犵娀鏌熼悧鍫熺凡缂佺姵鐗曢埞鎴︽偐閹绘帊绨介梺鍝ュ枎閹虫﹢寮婚妶澶嬪亗閹肩补妲呴弳銏㈢磽娴ｅ搫校闁绘濞€瀵鏁撻悩鏌ュ敹闂侀潧绻掓慨鐢告倶娴ｇ硶鏀介柣鎰皺婢ф盯鏌涢妸銉т虎闁伙絽鍢茶灒闁惧繗顫夊▓婵嬫⒑閸濆嫷妲兼繛澶嬫礋婵″爼鏌嗗鍡欏幗闂佺粯顭堟禍顒勫礉瀹ュ棛绡€闁靛繆妲呴悞鐣岀磼?        sourceCooldownUntil.entries.removeIf { it.value < now }
        sourceProbeCache.entries.removeIf { now - it.value.checkedAtMs > PROBE_CACHE_TTL_MS }

        val decision = cachedRoutingDecision
        if (decision != null && now - decision.checkedAtMs > ROUTING_CACHE_TTL_MS) {
            cachedRoutingDecision = null
        }

        if (sourceFailureCount.size > 20) {
            sourceFailureCount.clear()
        }
    }

    /**
     * 闂傚倸鍊风粈渚€骞栭銈傚亾濮樺崬鍘寸€规洝顫夌€靛ジ寮堕幋鐘垫毎濠电偠鎻紞鈧俊妞煎妼閳讳粙顢旈崼鐔哄幗闂佸綊鍋婇崢濂杆夌€ｎ喗鐓犵憸鐗堝笧閻ｆ椽鏌＄仦鍓р槈闁宠楠歌灒闁惧繘鈧稒顢橀梻鍌欑窔濞艰崵寰婃禒瀣婵犲﹤鐗嗙粻顖炴煟濡鍤欓柛妤佸▕閺屾洝绠涙繝鍌氣拤濠电偛鎳忛幑鍥蓟?     */
    private suspend fun resolveRoutingDecision(): RoutingDecision = coroutineScope {
        cleanupExpiredRecords()
        val now = System.currentTimeMillis()
        val cached = cachedRoutingDecision
        if (cached != null && now - cached.checkedAtMs <= ROUTING_CACHE_TTL_MS) {
            return@coroutineScope cached
        }

        val probeTargets = mapOf(
            SOURCE_SOGOU to listOf("https://www.sogou.com/"),
            SOURCE_BAIDU to listOf("https://www.baidu.com/"),
            SOURCE_360 to listOf("https://www.so.com/"),
            SOURCE_SHENMA to listOf("https://m.sm.cn/"),
            SOURCE_SEARXNG to SEARXNG_INSTANCES.take(2),
            SOURCE_BING to listOf("https://www.bing.com/"),
            SOURCE_DDG to listOf("https://html.duckduckgo.com/html/"),
            SOURCE_GOOGLE to listOf("https://www.google.com/")
        )

        val reachability = probeTargets.map { (source, endpoints) ->
            async(Dispatchers.IO) {
                source to probeSourceReachability(source, endpoints, now)
            }
        }.awaitAll().toMap()

        val globalReachableCount = GLOBAL_SOURCES.count { reachability[it] == true }
        val mode = if (globalReachableCount >= 2) RoutingMode.GLOBAL else RoutingMode.CN_FIRST
        val baseOrder = when (mode) {
            RoutingMode.CN_FIRST ->
                listOf(
                    SOURCE_SOGOU,
                    SOURCE_360,
                    SOURCE_SHENMA,
                    SOURCE_BAIDU,
                    SOURCE_SEARXNG,
                    SOURCE_BING,
                    SOURCE_DDG,
                    SOURCE_GOOGLE
                )

            RoutingMode.GLOBAL ->
                listOf(
                    SOURCE_SEARXNG,
                    SOURCE_DDG,
                    SOURCE_BING,
                    SOURCE_GOOGLE,
                    SOURCE_SOGOU,
                    SOURCE_360,
                    SOURCE_SHENMA,
                    SOURCE_BAIDU
                )
        }

        val ordered = baseOrder
            .filter { reachability[it] == true }
            .plus(baseOrder.filter { reachability[it] != true })

        val decision = RoutingDecision(
            mode = mode,
            orderedSources = ordered,
            reachableBySource = reachability,
            checkedAtMs = now
        )
        cachedRoutingDecision = decision
        decision
    }

    private fun determineRetryBudget(
        source: String,
        sourceIndex: Int,
        mode: RoutingMode,
        reachableHint: Boolean?
    ): Int {
        if (reachableHint == false) {
            return -1
        }
        if (mode == RoutingMode.CN_FIRST && source in GLOBAL_SOURCES && sourceIndex >= CN_SOURCES.size) {
            return 0
        }
        if (mode == RoutingMode.GLOBAL && source in CN_SOURCES && sourceIndex >= GLOBAL_SOURCES.size) {
            return 0
        }
        return MAX_RETRY_ATTEMPTS
    }

    private fun probeSourceReachability(source: String, endpoints: List<String>, now: Long): Boolean {
        val cached = sourceProbeCache[source]
        if (cached != null && now - cached.checkedAtMs <= PROBE_CACHE_TTL_MS) {
            return cached.reachable
        }

        val reachable = endpoints.any { endpoint -> probeEndpoint(endpoint) }
        sourceProbeCache[source] = SourceProbe(reachable = reachable, checkedAtMs = now)
        return reachable
    }

    private fun probeEndpoint(endpoint: String): Boolean {
        return runCatching {
            val request = Request.Builder()
                .url(endpoint)
                .get()
                .header("User-Agent", randomUserAgent())
                .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                .build()

            probeClient.newCall(request).execute().use { response ->
                response.code in 200..499
            }
        }.getOrElse { false }
    }

    private fun isRetriable(error: Exception, statusCode: Int?): Boolean {
        if (statusCode != null) {
            return statusCode == 408 || statusCode == 429 || statusCode >= 500
        }
        return error is IOException || error.cause is IOException
    }

    /**
     * 濠电姷鏁搁崑娑㈩敋椤撶喐鍙忛悗娑欙供濞堜粙鏌涘┑鍕姕妞ゎ偅娲熼弻鐔煎箲閹邦剛鍘梺鍝勬缁挸顫忔繝姘劦妞ゆ帒瀚粻娑欍亜閺嶃劏澹樼紒鐘冲灴濮婄粯鎷呴崨濠呯缂備緡鍣崹璺虹暦濠靛鍗抽幒铏?HTTP 闂傚倸鍊烽懗鍓佸垝椤栫偐鈧箓宕奸妷銉︽К闂佸搫绋侀崢濂告倿閸偁浜滈柟杈剧到閸旂敻鏌涜箛鎾剁劯闁?     */
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
     * 闂傚倸鍊烽悞锕傛儑瑜版帒鍨傚┑鐘宠壘缁愭鏌熼悧鍫熺凡闁搞劌鍊归幈銊ノ熼崹顔惧帿闂佹娊鏀卞ú鐔兼偂椤愶箑鐐婇柕濠忚吂閹峰湱绱?User-Agent
     */
    private fun randomUserAgent(): String {
        return USER_AGENTS[Random.nextInt(USER_AGENTS.size)]
    }

    /**
     * 闂傚倸鍊烽悞锕傛儑瑜版帒鍨傚┑鐘宠壘缁愭鏌熼悧鍫熺凡闁搞劌鍊归幈銊ヮ潨閸℃绠烘繝娈垮枟缁矂鍩為幋锔藉亹閻庡湱濮撮ˉ婵嗏攽閻愬弶鍣烽柛濠冪箓椤繑绻濆顒€绐涘銈嗘尵婵兘顢欐径濞炬斀闁绘ɑ鐟㈤崑鎾绘煕?Accept-Language
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
     * 闂傚倸鍊烽懗鍫曘€佹繝鍥ㄥ剹闁搞儺鍓欑粈鍐煏婵炑冨暙缁犳垿姊婚崒娆掑厡妞ゎ厼鐗忛幑銏ゅ醇閵夈儳顦悗鍏夊亾闁告洦鍓欓崜鍐差渻閵堝棗绗掗悗姘煎墴閹锋垿鎮㈤崗鑲╁帾婵犮垼鍩栭…鍥储濞戙垺鐓曢柕鍫濇噹椤忣厽鎱ㄦ繝鍌ょ吋鐎规洘甯掗埥澶娾枎韫囨挻鏁┑鐘垫暩閸嬫盯鎮ュ鍫濈倞闁靛闄勯悵顐︽煟鎼粹€冲辅闁稿鎹囬弻娑㈠即閵娿倗鏁栫紓浣介哺瀹€绋款潖閾忓湱鐭欓悹鎭掑妿椤斿洨绱撴担鍓叉Ц闁绘牕銈搁獮鍐晸閻欌偓閺佸洭鏌曡箛鏇炐ラ柣顐㈠濮婃椽宕滈幓鎺嶇凹濠电偛寮堕敃銏ゅ极瀹ュ鍋勯柤鑼劋濡啫鐣烽幒鎴旀閻庡湱濮崑鎾诲箰鎼存稐绨婚梺鎸庣箓閹冲酣銆傞幎鑺ョ厵濡炲楠搁埢鍫ユ煛鐏炶濡块柍褜鍓ㄧ紞鍡涘礈濮樿泛绀?     */
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
     * 闂傚倸鍊风粈渚€骞夐敓鐘茬闁哄洢鍨圭粻鐘虫叏濡炶浜鹃悗?HTTP 闂備浇顕х€涒晠顢欓弽顓炵獥闁哄稁鍘肩壕褰掓煙闂傚鍔嶉柛瀣樀閺屾盯顢曢敐鍡欘槰闂佸搫鎷嬮崜鐔煎蓟濞戙埄鏁冮柣妯垮皺娴犻箖姊洪崫鍕棞缂佺粯鍔欏﹢浣糕攽閻樿宸ラ柛鐔告尦瀹曪綁宕熼鍌滎啎?Jsoup 闂傚倸鍊搁崐椋庢閿熺姴纾婚柛娑卞幘閺嗗棝鏌涘☉姗堝姛妞も晜褰冭灃闁挎繂鎳庨弳鐐烘煕鎼达紕效闁哄本鐩鎾Ω閵夈儳顔愮紓浣诡殕閸ㄥ灝顫忛搹瑙勫珰闁炽儱鍟块獮鎰版⒑閻撳骸鏆遍柣鏍с偢楠炲啴濡烽妷顔惧弳闂佸憡娲﹂崑鎺楀吹閵堝鈷戠紓浣癸供閻掍粙鏌涙惔锝嗘毈鐎规洘鍨块獮妯兼嫚閼碱剦鍞洪梻浣虹帛閸旀寮幖浣€?     */
    private class SourceHttpException(val statusCode: Int) : IOException("HTTP $statusCode")

    /**
     * 闂傚倸鍊烽懗鍫曞箠閹捐瑙﹂悗锝庡墮閸ㄦ繈骞栧ǎ顒€濡肩痪鎯с偢閺屾洘绻涜閹虫劙鏁嶅鍫熲拺閻熸瑥瀚崝銈嗐亜閺囥劌骞楃紒鍌氱Ч椤㈡稑顫濋敐鍡╂綌闂備浇顫夊畷姗€锝炴径鎰垫晜闁绘绮悡鍐偡濞嗗繐顏褜浜濋妵鍕即椤忓棛袦閻庤娲樼敮鎺楋綖濠靛纭€闁绘劙娼ч獮鈧梻鍌氬€烽悞锔锯偓绗涘懐鐭欓柟杈鹃檮閸嬪鏌涢埄鍐ㄦ惛濞存粌缍婇弻锛勪沪鐠囨彃濮曢梺鎼炲€曠€氫即寮婚妶澶婄畳闁圭儤鍨垫慨鏇犵磼濡や礁鐏撮柡宀嬬秮閹瑩寮堕幋婵囩槗闂備胶顭堥鍛村箠閹版澘鐒垫い鎺嶇閹兼悂鏌涙繝鍐疄闁绘侗鍠氶埀顒婄秵閸ｎ噣寮崒鐐茬閺夊牆澧介幃鍏笺亜?     */
    private class SourceEmptyResultException(message: String) : IOException(message)


    // ==================== 缂傚倸鍊搁崐鎼佸磹閻戣姤鍊块柨鏇炲€哥粻鏉库攽閻樺磭顣查柛濠呮硾椤法鎹勬笟顖氬壉婵炲瓨鍤庨崐婵嬪蓟濞戙垹唯闁挎繂鎳庨‖澶嬬節濞堝灝鏋旈柛銊ょ矙瀵鈽夊鍡欏弳闂佸憡鍔忛弲婊堝箯闁秵鐓熼柕蹇婃櫅閻忥繝鏌熺粙娆剧吋妤?====================

    /**
     * 婵犵數濮烽。钘壩ｉ崨鏉戠；闁逞屽墴閺屾稓鈧綆鍋呭畷宀勬煛瀹€瀣？濞寸媴濡囬幏鐘诲箵閹烘埈娼ュ┑鐘殿暯閳ь剙鍟跨痪褔鏌熼鐓庘偓鎼佹偩閻戣棄唯闁冲搫鍊瑰▍鍡涙⒑閸忕厧澧柛銊﹀▕钘濆ù鐓庣摠閳锋垿鏌涘┑鍡楊伀濠⒀勬礃閵囧嫰鏁傞崹顔肩ギ閻庢鍠楅悡鈩冧繆閻戣棄鐓涢柛灞捐壘婢瑰嫰姊绘担鍛婂暈婵炶绠撳畷婊冾潩鐠鸿櫣锛涘┑鐘绘涧閻楀繒澹曢悡搴涒偓鎺戭潩椤掑倷铏庨梺璇叉禋閸犳氨妲愰幒妤婃晩闁告繂瀚～鍥倵?     */
    private fun isValidResult(item: SearchResultItem): Boolean {
        if (item.title.isBlank()) return false
        if (!item.url.startsWith("http")) return false
        if (isBlockedNavigationUrl(item.url)) return false

        val adPatterns = listOf(
            "/ad/",
            "/ads/",
            "/sponsored",
            "/promo/",
            "advertising",
            "doubleclick",
            "googlesyndication"
        )
        if (adPatterns.any { item.url.contains(it, ignoreCase = true) }) return false

        val noisyPatterns = listOf(
            "info.so.com/feedback",
            "api.m.sm.cn/rest?method=policy",
            "m.quark.cn/vsearch/news",
            "wappass.baidu.com/static/captcha"
        )
        if (noisyPatterns.any { item.url.contains(it, ignoreCase = true) }) return false
        return true
    }

    /**
     * URL 闂傚倸鍊风粈渚€骞栭銈囩煋闁哄鍤氬ú顏勎у璺猴躬濡嘲顪冮妶鍡欏⒈闁稿绋撶划鍫濈暆閸曨剛鍘搁悗骞垮劚濞诧箓寮抽埡鍐＜闁逞屽墯瀵板嫰骞囬鐘插箺婵＄偑鍊ら崑鎺楀礈濞嗘劒绻嗛柧蹇撴贡绾惧吋銇勯鐔风仴濠⒀冾嚟閳ь剝顫夊ú蹇涘垂閾忚宕叉繝闈涙－濞尖晜銇勯幒宥囪窗濞?     */
    private fun normalizeUrl(url: String): String {
        return url
            .lowercase()
            .removeSuffix("/")
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
    }

    /**
     * 闂傚倷娴囬褏鑺遍懖鈺佺筏濠电姵鐔紞鏍ь熆閼搁潧濮囩紒鐘崇墱閳ь剝顫夊ú鏍洪敃鍌︾稏闁哄洢鍨洪悡蹇涚叓閸パ屽剰闁诲浚鍠楃换娑欏緞閸繄浼堥梺鍝勭焿缁辨洟鍩€椤掑﹦绉甸柛瀣閹本鎯旈敐鍥╋紲缂傚倷鐒﹂敃鈺呮倿娴犲鐓欐い鏃囧Г缁舵煡鏌ｉ敐鍡欑疄闁糕斁鍋撳銈嗗笒鐎氼參宕戦埡鍌滅瘈闂傚牊渚楅崕蹇曠棯椤撴稑浜鹃梻鍌欑閹碱偄霉閸曨厽顫曢柡鍥╁€ｉ敐澶婄闁挎梻鏅崢?(闂傚倸鍊风粈渚€骞夐敍鍕殰闁圭儤鍤﹀☉妯锋瀻闁规崘娅曟潏鍫ユ⒑缂佹ɑ鈷掗柛搴涘€濋獮鍡涘Ψ閵夈垺鏂€闂佺粯锚瀵泛顔忓┑鍫熷枑闂侇叏绠戦悘锕傛煙椤旇姤銇濇鐐村浮楠炲顢涘顐ょ煑闂?
     */
    private fun scoreResult(item: SearchResultItem): Int {
        var score = 0

        // Prefer concise, readable titles.
        if (item.title.length in 10..80) score += 10

        // 闂傚倸鍊烽懗鍫曘€佹繝鍐╁弿闁靛牆顦埀顒婄畵瀹曞ジ濡烽敐鍌氫壕闁告稑鐡ㄩ弲婵嬫煕鐏炲墽鈻撻柟宄扮秺濮婃椽宕滈懠顒€甯ラ梺鍝ュУ閻楃娀銆佸▎鎰瘈闁搞儯鍔庨崢閬嶆⒑閸濆嫬鏆欓柛濠勵焾閻☆參姊?(闂傚倸鍊风粈渚€骞栭锔藉亱闁糕剝铔嬮崶顒夋晬闁绘劘灏崺鐐烘煟鎼达絾鏆╃痪顓炵埣瀹曟垿骞樼紒妯轰画闂佺粯顨呴悧鎰兜閳ь剚绻濋悽闈涗粶闁绘锕畷褰掑锤濡ゅ啫绁﹀┑掳鍊愰崑鎾绘懚閿濆棛绠鹃柛鈩冪懃娴滈箖鏌ｉ鐕佹疁婵﹥妞藉畷顐﹀礋椤掆偓椤︹晛鈹戦悙鎻掓倯闁荤啿鏅滄穱?
        if (item.text.length > 50) score += 5
        if (item.text.length > 100) score += 5
        val highQualityDomains = listOf(
            "wikipedia.org", "github.com", "stackoverflow.com", "reddit.com",
            "medium.com", "zhihu.com", "csdn.net", "juejin.cn",
            "docs.", "developer.", "documentation"
        )
        if (highQualityDomains.any { item.url.contains(it, ignoreCase = true) }) {
            score += 15
        }

        // Very short snippets are usually lower quality.
        if (item.text.length < 20) score -= 5

        return score
    }
    // SearXNG response DTO
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

    private enum class RoutingMode {
        CN_FIRST,
        GLOBAL
    }

    private data class RoutingDecision(
        val mode: RoutingMode,
        val orderedSources: List<String>,
        val reachableBySource: Map<String, Boolean>,
        val checkedAtMs: Long
    )

    private data class SourceProbe(
        val reachable: Boolean,
        val checkedAtMs: Long
    )
}
