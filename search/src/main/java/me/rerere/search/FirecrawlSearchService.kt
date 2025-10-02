package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object FirecrawlSearchService : SearchService<SearchServiceOptions.FirecrawlOptions> {
    override val name: String = "Firecrawl"

    @Composable
    override fun Description() {
        val urlHandler = LocalUriHandler.current
        TextButton(
            onClick = {
                urlHandler.openUri("https://docs.firecrawl.dev/features/search")
            }
        ) {
            Text(stringResource(R.string.click_to_get_api_key))
        }
    }

    override val parameters: InputSchema?
        get() = InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Search query string")
                })
                put("sources", buildJsonObject {
                    put("type", "array")
                    put("description", "Optional list of sources: `web`, `news`, default value is `web`")
                    put("items", buildJsonObject {
                        put("type", "string")
                    })
                })
                put("categories", buildJsonObject {
                    put("type", "array")
                    put(
                        "description",
                        "Optional list of categories to filter search results by: `github`, `research`, empty value means no filtering, default value is empty"
                    )
                    put("items", buildJsonObject {
                        put("type", "string")
                    })
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
                put("onlyMainContent", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to only scrape main content, default is true")
                })
            },
            required = listOf("url")
        )

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.FirecrawlOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")

            val sources = params["sources"].asStringList()
            val categories = params["categories"].asStringList()

            val body = buildJsonObject {
                put("query", query)
                put("limit", commonOptions.resultSize)
                sources?.takeIf { it.isNotEmpty() }?.let { list ->
                    put("sources", buildJsonArray {
                        list.forEach { add(it) }
                    })
                }
                categories?.takeIf { it.isNotEmpty() }?.let { list ->
                    put("categories", buildJsonArray {
                        list.forEach { add(it) }
                    })
                }
            }

            val request = Request.Builder()
                .url("https://api.firecrawl.dev/v2/search")
                .post(body.toString().toRequestBody())
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer ${serviceOptions.apiKey}")
                .build()

            val response = httpClient.newCall(request).await()
            if (!response.isSuccessful) {
                error("response failed #${'$'}{response.code}")
            }

            val bodyString = response.body.string()
            val payload = json.parseToJsonElement(bodyString).jsonObject
            val data = payload["data"]?.jsonObject ?: error("empty response data")
            val resultData = json.decodeFromJsonElement<FirecrawlSearchResultData>(data)
            val result = buildList {
                resultData.web?.forEach { item ->
                    add(SearchResultItem(title = item.title, url = item.url, text = item.description))
                }

                resultData.news?.forEach { item ->
                    add(
                        SearchResultItem(
                            title = item.title,
                            url = item.url,
                            text = """
                                ${item.snippet}
                                ${item.date}
                            """.trimIndent()
                        )
                    )
                }
            }
            SearchResult(
                items = result
            )
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.FirecrawlOptions
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = params["url"]?.jsonPrimitive?.content ?: error("url is required")
            val onlyMainContent = params["onlyMainContent"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: true

            val body = buildJsonObject {
                put("url", url)
                put("onlyMainContent", onlyMainContent)
                put("maxAge", 172800000)
                put("parsers", buildJsonArray { })
                put("formats", buildJsonArray {
                    add("markdown")
                })
            }

            val request = Request.Builder()
                .url("https://api.firecrawl.dev/v2/scrape")
                .post(body.toString().toRequestBody())
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer ${serviceOptions.apiKey}")
                .build()

            val response = httpClient.newCall(request).await()
            if (!response.isSuccessful) {
                error("response failed #${response.code}")
            }

            val bodyString = response.body.string()
            val payload = json.parseToJsonElement(bodyString).jsonObject

            val success = payload["success"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
            if (!success) {
                error("scrape request failed")
            }

            val data = payload["data"]?.jsonObject ?: error("empty response data")
            val markdown = data["markdown"]?.jsonPrimitive?.content ?: ""

            ScrapedResult(
                urls = listOf(
                    ScrapedResultUrl(
                        url = url,
                        content = markdown
                    )
                )
            )
        }
    }

    private fun JsonElement?.asStringList(): List<String>? {
        return when (this) {
            is JsonArray -> this.mapNotNull { element ->
                element.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }
            }

            is JsonPrimitive -> this.contentOrNull?.split(',')
                ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }

            else -> null
        }
    }
}

@Serializable
data class FirecrawlSearchResultWebItem(
    val url: String,
    val title: String,
    val description: String,
)

@Serializable
data class FirecrawlSearchResultNewsItem(
    val title: String,
    val url: String,
    val snippet: String,
    val date: String,
)

@Serializable
data class FirecrawlSearchResultData(
    val web: List<FirecrawlSearchResultWebItem>? = emptyList(),
    val news: List<FirecrawlSearchResultNewsItem>? = emptyList(),
)



