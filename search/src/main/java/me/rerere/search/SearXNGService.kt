package me.rerere.search

import android.util.Log
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.net.URLEncoder

private const val TAG = "SearXNGService"

object SearXNGService : SearchService<SearchServiceOptions.SearXNGOptions> {
    override val name: String = "SearXNG"

    @Composable
    override fun Description() {
        Text(stringResource(R.string.searxng_desc_1))
        Text(stringResource(R.string.searxng_desc_2))
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

    override val scrapingParameters: InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.SearXNGOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(serviceOptions.url.isNotBlank()) {
                "SearXNG URL cannot be empty"
            }

            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")

            // 构建查询URL
            val baseUrl = serviceOptions.url.trimEnd('/')
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$baseUrl/search?q=$encodedQuery&format=json"
                .toHttpUrl()
                .newBuilder()
                .apply {
                    if (serviceOptions.engines.isNotBlank()) {
                        addQueryParameter("engines", serviceOptions.engines)
                    }
                    if (serviceOptions.language.isNotBlank()) {
                        addQueryParameter("language", serviceOptions.language)
                    }
                }
                .build()

            // 发送请求
            val request = Request.Builder()
                .url(url)
                .get()
                .apply {
                    // 添加HTTP Basic Auth支持
                    if (serviceOptions.username.isNotBlank() && serviceOptions.password.isNotBlank()) {
                        header("Authorization", Credentials.basic(serviceOptions.username, serviceOptions.password))
                    }
                }
                .build()

            Log.i(TAG, "search: $url")

            val response = httpClient.newCall(request).await()
            if (response.isSuccessful) {
                val bodyRaw = response.body.string()
                val searchResponse = runCatching {
                    json.decodeFromString<SearXNGResponse>(bodyRaw)
                }.onFailure {
                    it.printStackTrace()
                    println("SearXNG response body: $bodyRaw")
                    error("Failed to decode SearXNG response: ${it.message}")
                }.getOrThrow()

                // 转换为标准格式，取前 N 个结果
                val items = searchResponse.results
                    .take(commonOptions.resultSize)
                    .map { result ->
                        SearchResultItem(
                            title = result.title,
                            url = result.url,
                            text = result.content
                        )
                    }

                return@withContext Result.success(SearchResult(items = items))
            } else {
                val errorBody = response.body?.string()
                println("SearXNG API error: ${response.code} - $errorBody")
                error("SearXNG request failed with status ${response.code}")
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.SearXNGOptions
    ): Result<ScrapedResult> {
        return Result.failure(Exception("Scraping is not supported for SearXNG"))
    }


    @Serializable
    data class SearXNGResponse(
        @SerialName("query")
        val query: String,
        @SerialName("number_of_results")
        val numberOfResults: Int,
        @SerialName("results")
        val results: List<SearXNGResult>,
    )

    @Serializable
    data class SearXNGResult(
        @SerialName("url")
        val url: String,
        @SerialName("title")
        val title: String,
        @SerialName("content")
        val content: String,
        @SerialName("thumbnail")
        val thumbnail: String? = null,
        @SerialName("engine")
        val engine: String,
        @SerialName("template")
        val template: String,
        @SerialName("parsed_url")
        val parsedUrl: List<String> = emptyList(),
        @SerialName("img_src")
        val imgSrc: String? = null,
        @SerialName("priority")
        val priority: String? = null,
        @SerialName("engines")
        val engines: List<String> = emptyList(),
        @SerialName("positions")
        val positions: List<Int> = emptyList(),
        @SerialName("score")
        val score: Double = 0.0,
        @SerialName("category")
        val category: String = "",
        @SerialName("publishedDate")
        val publishedDate: String? = null,
        @SerialName("iframe_src")
        val iframeSrc: String? = null
    )
}
