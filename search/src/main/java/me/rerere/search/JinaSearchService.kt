package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object JinaSearchService : SearchService<SearchServiceOptions.JinaOptions> {
    override val name: String = "Jina"

    @Composable
    override fun Description() {
        val urlHandler = LocalUriHandler.current
        TextButton(
            onClick = {
                urlHandler.openUri("https://jina.ai/")
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
                    put("description", "url to scrape")
                })
            },
            required = listOf("url")
        )

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.JinaOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")

            val body = buildJsonObject {
                put("q", query)
            }

            val request = Request.Builder()
                .url("https://s.jina.ai/")
                .post(body.toString().toRequestBody())
                .addHeader("Authorization", "Bearer ${serviceOptions.apiKey}")
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .build()

            val response = httpClient.newCall(request).await()
            if (response.isSuccessful) {
                val responseData = response.body.string().let {
                    json.decodeFromString<JinaSearchResponse>(it)
                }

                return@withContext Result.success(
                    SearchResult(
                        items = responseData.data.take(commonOptions.resultSize).map {
                            SearchResultItem(
                                title = it.title,
                                url = it.url,
                                text = it.description
                            )
                        }
                    )
                )
            } else {
                error("response failed #${response.code}")
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.JinaOptions
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = params["url"]?.jsonPrimitive?.content ?: error("urls is required")

            val body = buildJsonObject {
                put("url", url)
            }

            val request = Request.Builder()
                .url("https://r.jina.ai/")
                .post(body.toString().toRequestBody())
                .addHeader("Authorization", "Bearer ${serviceOptions.apiKey}")
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Return-Format", "markdown")
                .build()

            val response = httpClient.newCall(request).await()
            if (!response.isSuccessful) {
                error("response failed for url $url #${response.code}")
            }
            val responseData = response.body.string().let {
                json.decodeFromString<JinaScrapeResponse>(it)
            }

            ScrapedResult(
                urls = listOf(
                    ScrapedResultUrl(
                        url = responseData.data.url,
                        content = responseData.data.content,
                        metadata = ScrapedResultMetadata(
                            title = responseData.data.title,
                            description = responseData.data.description
                        )
                    )
                )
            )
        }
    }

    @Serializable
    data class JinaSearchResponse(
        val code: Int,
        val status: Int,
        val data: List<JinaSearchResultItem>
    )

    @Serializable
    data class JinaSearchResultItem(
        val title: String,
        val url: String,
        val description: String,
        val content: String = "",
        val usage: JinaUsage? = null
    )

    @Serializable
    data class JinaUsage(
        val tokens: Int
    )

    @Serializable
    data class JinaScrapeResponse(
        val code: Int,
        val status: Int,
        val data: JinaScrapeData
    )

    @Serializable
    data class JinaScrapeData(
        val title: String,
        val description: String = "",
        val url: String,
        val content: String,
        val publishedTime: String? = null,
        val usage: JinaUsage? = null
    )
}
