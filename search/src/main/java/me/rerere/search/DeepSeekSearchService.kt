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
import kotlinx.serialization.json.buildJsonObject
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
import okhttp3.Response

object DeepSeekSearchService : SearchService<SearchServiceOptions.DeepSeekOptions> {
    override val name: String = "DeepSeek"

    private const val SEARCH_URL = "https://api.deepseek.com/v1/tools/search"
    private const val FETCH_URL = "https://api.deepseek.com/v1/tools/fetch"

    @Composable
    override fun Description() {
        val urlHandler = LocalUriHandler.current
        TextButton(
            onClick = {
                urlHandler.openUri("https://platform.deepseek.com/api_keys")
            }
        ) {
            Text(stringResource(R.string.click_to_get_api_key))
        }
    }

    override fun parameters(options: SearchServiceOptions.DeepSeekOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "$SEARCH_QUERY_DESCRIPTION 1-128 characters. Supports operators: site:, -site:, -keyword, \"exact phrase\", " +
                            "lang:zh,en, after:2025-01-01, before:2026-06-01"
                    )
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.DeepSeekOptions): InputSchema? =
        InputSchema.Obj(
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
        serviceOptions: SearchServiceOptions.DeepSeekOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())

            val body = buildJsonObject {
                put("query", query)
                put("count", commonOptions.resultSize.coerceIn(1, 50))
            }

            val request = Request.Builder()
                .url(SEARCH_URL)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $apiKey")
                .build()

            val response = httpClient.newCall(request).await()
            val bodyRaw = response.body.string()
            if (!response.isSuccessful) {
                error("DeepSeek search failed: ${response.errorMessage(bodyRaw)}")
            }

            val searchResponse = json.decodeFromString<DeepSeekSearchResponse>(bodyRaw)
            SearchResult(
                items = searchResponse.results.map { result ->
                    SearchResultItem(
                        title = result.title,
                        url = result.url,
                        text = result.summary.ifBlank { result.snippet },
                        publishedDate = result.date,
                    )
                }
            )
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.DeepSeekOptions
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = params["url"]?.jsonPrimitive?.content ?: error("url is required")
            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())

            val body = buildJsonObject {
                put("url", url)
            }

            val request = Request.Builder()
                .url(FETCH_URL)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $apiKey")
                .build()

            val response = httpClient.newCall(request).await()
            val bodyRaw = response.body.string()
            if (!response.isSuccessful) {
                error("DeepSeek fetch failed: ${response.errorMessage(bodyRaw)}")
            }

            val fetchResponse = json.decodeFromString<DeepSeekFetchResponse>(bodyRaw)
            ScrapedResult(
                urls = listOf(
                    ScrapedResultUrl(
                        url = url,
                        content = fetchResponse.content,
                        metadata = ScrapedResultMetadata(
                            title = fetchResponse.title.ifBlank { null },
                        )
                    )
                )
            )
        }
    }

    private fun Response.errorMessage(bodyRaw: String): String {
        val message = runCatching {
            json.decodeFromString<DeepSeekErrorResponse>(bodyRaw).error.message
        }.getOrNull() ?: bodyRaw
        return "#$code $message"
    }

    @Serializable
    data class DeepSeekSearchResponse(
        val results: List<DeepSeekSearchResultItem> = emptyList(),
    )

    @Serializable
    data class DeepSeekSearchResultItem(
        val title: String,
        val url: String,
        val snippet: String = "",
        val summary: String = "",
        val content: String = "",
        @SerialName("site_name")
        val siteName: String? = null,
        val date: String? = null,
        @SerialName("rerank_score")
        val rerankScore: Double? = null,
        val authority: Int? = null,
    )

    @Serializable
    data class DeepSeekFetchResponse(
        val title: String = "",
        val content: String = "",
    )

    @Serializable
    data class DeepSeekErrorResponse(
        val error: DeepSeekError,
    )

    @Serializable
    data class DeepSeekError(
        val message: String,
    )
}
