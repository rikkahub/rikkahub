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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "OllamaSearchService"

object OllamaSearchService : SearchService<SearchServiceOptions.OllamaOptions> {
    override val name: String = "Ollama"

    @Composable
    override fun Description() {
        val uriHandler = LocalUriHandler.current
        TextButton(onClick = { uriHandler.openUri("https://ollama.com/settings/keys") }) {
            Text(stringResource(R.string.click_to_get_api_key))
        }
    }

    override fun parameters(options: SearchServiceOptions.OllamaOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.OllamaOptions): InputSchema? =
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
        serviceOptions: SearchServiceOptions.OllamaOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")

            val body = buildJsonObject {
                put("query", query)
                put("max_results", commonOptions.resultSize.coerceIn(5..10))
            }

            val request = Request.Builder()
                .url("https://ollama.com/api/web_search")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer ${serviceOptions.apiKey}")
                .build()

            val response = httpClient.newCall(request).await()
            if (response.isSuccessful) {
                val responseBody = response.body.string()
                val searchResponse = json.decodeFromString<OllamaSearchResponse>(responseBody)

                return@withContext Result.success(
                    SearchResult(
                        items = searchResponse.results.map {
                            SearchResultItem(
                                title = it.title,
                                url = it.url,
                                text = it.content
                            )
                        }
                    )
                )
            } else {
                error("Ollama search failed with code ${response.code}: ${response.message}")
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.OllamaOptions
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = params["url"]?.jsonPrimitive?.content ?: error("url is required")

            val body = buildJsonObject {
                put("url", url)
            }

            val request = Request.Builder()
                .url("https://ollama.com/api/web_fetch")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer ${serviceOptions.apiKey}")
                .build()

            val response = httpClient.newCall(request).await()
            if (!response.isSuccessful) {
                error("response failed for url $url #${response.code}")
            }
            val responseData = response.body.string().let {
                json.decodeFromString<OllamaScrapeResponse>(it)
            }

            ScrapedResult(
                urls = listOf(
                    ScrapedResultUrl(
                        url = url,
                        content = responseData.content,
                        metadata = ScrapedResultMetadata(
                            title = responseData.title
                        )
                    )
                )
            )
        }
    }

    @Serializable
    private data class OllamaSearchResponse(
        val results: List<OllamaSearchResult>
    )

    @Serializable
    private data class OllamaSearchResult(
        val title: String,
        val url: String,
        val content: String
    )

    @Serializable
    private data class OllamaScrapeResponse(
        val title: String,
        val content: String,
        val links: List<String>
    )
}
