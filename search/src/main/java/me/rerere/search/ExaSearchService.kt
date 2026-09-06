package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import me.rerere.search.SearchService.Companion.keyRoulette
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.Locale

object ExaSearchService : SearchService<SearchServiceOptions.ExaOptions> {
    private const val MAX_EVIDENCE_TEXT_CHARACTERS = 8_000
    private const val MAX_EVIDENCE_HIGHLIGHT_CHARACTERS = 1_200
    private const val MIN_MAX_AGE_HOURS = -1
    private const val MAX_MAX_AGE_HOURS = 720
    private const val CURRENT_QUERY_MAX_AGE_HOURS = 0
    private val temporalQueryPattern = Regex(
        "\\b(latest|newest|current|currently|today|now|recent|recently|aktuell(?:ste|er|e|en)?|neueste|heute|derzeit)\\b",
        RegexOption.IGNORE_CASE,
    )

    override val name: String = "Exa"

    @Composable
    override fun Description() {
        val urlHandler = LocalUriHandler.current
        TextButton(
            onClick = {
                urlHandler.openUri("https://dashboard.exa.ai/api-keys")
            }
        ) {
            Text(stringResource(R.string.click_to_get_api_key))
        }
    }

    override fun parameters(options: SearchServiceOptions.ExaOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
                put("type", buildJsonObject {
                    put("type", "string")
                    put("description", "Search type: fast (quick results), auto (default, balanced), deep (synthesized answer with citations)")
                    put("enum", buildJsonArray {
                        add("fast")
                        add("auto")
                        add("deep")
                    })
                })
                put("startPublishedDate", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional ISO-8601 publication date lower bound; results are published after this date")
                })
                put("endPublishedDate", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional ISO-8601 publication date upper bound; results are published before this date")
                })
                put("includeDomains", domainArraySchema("Optional domains to include"))
                put("excludeDomains", domainArraySchema("Optional domains to exclude"))
                put("maxAgeHours", buildJsonObject {
                    put("type", "integer")
                    put("description", "Optional maximum age in hours for fetched page content; use only when content freshness matters")
                    put("minimum", MIN_MAX_AGE_HOURS)
                    put("maximum", MAX_MAX_AGE_HOURS)
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.ExaOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "url to scrape")
                })
                put("maxAgeHours", buildJsonObject {
                    put("type", "integer")
                    put("description", "Optional maximum age in hours for fetched page content")
                    put("minimum", MIN_MAX_AGE_HOURS)
                    put("maximum", MAX_MAX_AGE_HOURS)
                })
            },
            required = listOf("url")
        )

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.ExaOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val effectiveParams = prepareCurrentInfoParams(params)
            val body = buildSearchRequestBody(effectiveParams, commonOptions.resultSize)
            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())

            val request = Request.Builder()
                .url("https://api.exa.ai/search")
                .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $apiKey")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyRaw = response.body.string()
                val response = runCatching {
                    json.decodeFromString<ExaData>(bodyRaw)
                }.onFailure {
                    it.printStackTrace()
                    println(bodyRaw)
                    error("Failed to decode response: $bodyRaw")
                }.getOrThrow()

                val result = mapSearchResult(response)
                return@withContext Result.success(
                    if (isCurrentInfoQuery(effectiveParams)) prioritizeCurrentResults(result) else result
                )
            } else {
                println(response.body.string())
                error("response failed #${response.code}")
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.ExaOptions
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildScrapeRequestBody(params)
            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())

            val request = Request.Builder()
                .url("https://api.exa.ai/contents")
                .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $apiKey")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyRaw = response.body.string()
                val data = runCatching {
                    json.decodeFromString<ExaData>(bodyRaw)
                }.onFailure {
                    it.printStackTrace()
                    println(bodyRaw)
                    error("Failed to decode response: $bodyRaw")
                }.getOrThrow()

                return@withContext Result.success(mapScrapedResult(data))
            } else {
                println(response.body.string())
                error("response failed #${response.code}")
            }
        }
    }

    @Serializable
    data class ExaData(
        @SerialName("requestId")
        val requestId: String? = null,
        @SerialName("autopromptString")
        val autopromptString: String? = null,
        @SerialName("resolvedSearchType")
        val resolvedSearchType: String? = null,
        @SerialName("results")
        val results: List<ExaResult>,
        @SerialName("output")
        val output: ExaOutput? = null,
    )

    @Serializable
    data class ExaOutput(
        @SerialName("content")
        val content: String? = null,
        @SerialName("grounding")
        val grounding: List<ExaGrounding> = emptyList(),
    )

    @Serializable
    data class ExaGrounding(
        @SerialName("field")
        val field: String? = null,
        @SerialName("citations")
        val citations: List<ExaCitation> = emptyList(),
        @SerialName("confidence")
        val confidence: String? = null,
    )

    @Serializable
    data class ExaCitation(
        @SerialName("url")
        val url: String,
        @SerialName("title")
        val title: String,
    )

    @Serializable
    data class ExaResult(
        @SerialName("id")
        val id: String,
        @SerialName("title")
        val title: String,
        @SerialName("url")
        val url: String,
        @SerialName("publishedDate")
        val publishedDate: String? = null,
        @SerialName("author")
        val author: String? = null,
        @SerialName("text")
        val text: String? = null,
        @SerialName("image")
        val image: String? = null,
        @SerialName("highlights")
        val highlights: List<String>? = null,
    )

    internal fun buildSearchRequestBody(
        params: JsonObject,
        resultSize: Int,
    ) = buildJsonObject {
        val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
        val maxAgeHours = optionalMaxAgeHours(params)
        val hasEvidenceOptions = hasOptionalString(params, "startPublishedDate") ||
            hasOptionalString(params, "endPublishedDate") ||
            hasOptionalStringArray(params, "includeDomains") ||
            hasOptionalStringArray(params, "excludeDomains") ||
            maxAgeHours != null

        put("query", JsonPrimitive(query))
        put("numResults", JsonPrimitive(resultSize))
        put("type", JsonPrimitive(params["type"]?.jsonPrimitive?.content ?: "auto"))
        putOptionalString(this, params, "startPublishedDate")
        putOptionalString(this, params, "endPublishedDate")
        putOptionalStringArray(this, params, "includeDomains")
        putOptionalStringArray(this, params, "excludeDomains")
        put("contents", buildJsonObject {
            if (hasEvidenceOptions) {
                put("text", buildJsonObject {
                    put("maxCharacters", JsonPrimitive(MAX_EVIDENCE_TEXT_CHARACTERS))
                })
                put("highlights", buildJsonObject {
                    put("maxCharacters", JsonPrimitive(MAX_EVIDENCE_HIGHLIGHT_CHARACTERS))
                })
            } else {
                put("text", JsonPrimitive(true))
            }
            maxAgeHours?.let { put("maxAgeHours", it) }
        })
    }

    /**
     * Adds only conservative defaults for clearly time-sensitive queries. Explicit tool-call
     * filters remain authoritative; ordinary queries keep the legacy request unchanged.
     */
    internal fun prepareCurrentInfoParams(params: JsonObject): JsonObject {
        if (!isCurrentInfoQuery(params)) return params

        val query = params["query"]?.jsonPrimitive?.contentOrNull ?: return params
        val preferredDomains = preferredDomains(query)
        val hasExplicitDomains = "includeDomains" in params

        return buildJsonObject {
            params.forEach { (name, value) -> put(name, value) }
            if (!hasExplicitDomains && preferredDomains.isNotEmpty()) {
                put("includeDomains", buildJsonArray { preferredDomains.forEach(::add) })
            }
            if ("maxAgeHours" !in params) {
                put("maxAgeHours", CURRENT_QUERY_MAX_AGE_HOURS)
            }
        }
    }

    internal fun isCurrentInfoQuery(params: JsonObject): Boolean =
        params["query"]?.jsonPrimitive?.contentOrNull?.let(::isCurrentInfoQuery) == true

    internal fun isCurrentInfoQuery(query: String): Boolean =
        temporalQueryPattern.containsMatchIn(query)

    private fun preferredDomains(query: String): List<String> {
        val normalized = query.lowercase(Locale.ROOT)
        return buildList {
            if ("rikkahub" in normalized) add("github.com")
            if ("scaleway" in normalized) add("scaleway.com")
            if ("berget" in normalized) add("berget.ai")
            if ("opper" in normalized) add("opper.ai")
        }.distinct()
    }

    internal fun prioritizeCurrentResults(result: SearchResult): SearchResult {
        val items = result.items.withIndex()
            .sortedWith(
                compareBy<IndexedValue<SearchResultItem>> {
                    publishedEpoch(it.value.publishedDate) == null
                }.thenByDescending { publishedEpoch(it.value.publishedDate) ?: Long.MIN_VALUE }
            )
            .map { it.value }
        return result.copy(items = items)
    }

    private fun publishedEpoch(value: String?): Long? =
        value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    internal fun buildScrapeRequestBody(params: JsonObject) = buildJsonObject {
        val url = params["url"]?.jsonPrimitive?.content ?: error("url is required")
        put("urls", buildJsonArray {
            add(JsonPrimitive(url))
        })
        put("text", buildJsonObject {
            put("maxCharacters", JsonPrimitive(MAX_EVIDENCE_TEXT_CHARACTERS))
        })
        optionalMaxAgeHours(params)?.let { put("maxAgeHours", it) }
    }

    internal fun mapSearchResult(data: ExaData): SearchResult = SearchResult(
        answer = data.output?.content,
        items = data.results.map {
            SearchResultItem(
                title = it.title,
                url = it.url,
                text = it.text ?: "",
                publishedDate = it.publishedDate,
                highlights = it.highlights.orEmpty(),
            )
        },
        images = data.results.mapNotNull { it.image?.takeIf { url -> url.isNotBlank() } },
    )

    internal fun mapScrapedResult(data: ExaData): ScrapedResult = ScrapedResult(
        urls = data.results.map {
            ScrapedResultUrl(
                url = it.url,
                content = it.text ?: "",
                metadata = ScrapedResultMetadata(
                    title = it.title,
                    publishedDate = it.publishedDate,
                )
            )
        },
    )

    private fun domainArraySchema(description: String) = buildJsonObject {
        put("type", "array")
        put("description", description)
        put("items", buildJsonObject {
            put("type", "string")
        })
    }

    private fun putOptionalString(
        builder: kotlinx.serialization.json.JsonObjectBuilder,
        params: JsonObject,
        name: String,
    ) {
        params[name]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { builder.put(name, it) }
    }

    private fun putOptionalStringArray(
        builder: kotlinx.serialization.json.JsonObjectBuilder,
        params: JsonObject,
        name: String,
    ) {
        val values = params[name]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
            ?.takeIf { it.isNotEmpty() }
            ?: return
        builder.put(name, buildJsonArray { values.forEach { add(it) } })
    }

    private fun hasOptionalString(params: JsonObject, name: String): Boolean =
        runCatching {
            params[name]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true
        }.getOrDefault(false)

    private fun hasOptionalStringArray(params: JsonObject, name: String): Boolean =
        runCatching {
            params[name]?.jsonArray?.any {
                it.jsonPrimitive.contentOrNull?.isNotBlank() == true
            } == true
        }.getOrDefault(false)

    private fun optionalMaxAgeHours(params: JsonObject): Int? =
        runCatching { params["maxAgeHours"]?.jsonPrimitive?.intOrNull }
            .getOrNull()
            ?.takeIf { it in MIN_MAX_AGE_HOURS..MAX_MAX_AGE_HOURS }
}
