package me.rerere.search

import android.util.Log
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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "GrokSearchService"
private const val GROK_ENDPOINT = "https://api.x.ai/v1/responses"

object GrokSearchService : SearchService<SearchServiceOptions.GrokOptions> {
    override val name: String = "Grok"

    @Composable
    override fun Description() {
        val uriHandler = LocalUriHandler.current
        TextButton(
            onClick = {
                uriHandler.openUri("https://console.x.ai/")
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
                    put("description", "The question to ask, can be a natural language question")
                })
            },
            required = listOf("query")
        )

    override val scrapingParameters: InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.GrokOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (serviceOptions.apiKey.isBlank()) {
                error("Grok API key is required")
            }

            val query = params["query"]?.jsonPrimitive?.content
                ?: error("query is required")

            val body = buildJsonObject {
                put("model", JsonPrimitive(serviceOptions.model))
                put("input", buildJsonArray {
                    add(buildJsonObject {
                        put("role", JsonPrimitive("system"))
                        put(
                            "content",
                            JsonPrimitive("You are a helpful search assistant. Search the web to find accurate and up-to-date information for the user's query. Provide a comprehensive answer with citations.")
                        )
                    })
                    add(buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("content", JsonPrimitive(query))
                    })
                })
                put("tools", buildJsonArray {
                    add(buildJsonObject {
                        put("type", JsonPrimitive("web_search"))
                    })
                    add(buildJsonObject {
                        put("type", JsonPrimitive("x_search"))
                    })
                })
                put("store", JsonPrimitive(false))
            }

            Log.i(TAG, "search: $query")

            val request = Request.Builder()
                .url(GROK_ENDPOINT)
                .post(body.toString().toRequestBody())
                .addHeader("Authorization", "Bearer ${serviceOptions.apiKey}")
                .addHeader("Content-Type", "application/json")
                .build()

            val response = httpClient.newCall(request).await()
            if (response.isSuccessful) {
                val responseBody = response.body.string().let {
                    json.decodeFromString<GrokResponse>(it)
                }

                val messageOutput = responseBody.output.firstOrNull {
                    it.type == "message" && it.role == "assistant"
                }
                val textContent = messageOutput?.content?.firstOrNull {
                    it.type == "output_text"
                }

                val answer = textContent?.text

                val items = textContent?.annotations
                    ?.filter { it.type == "url_citation" && !it.url.isNullOrBlank() }
                    ?.distinctBy { it.url }
                    ?.take(commonOptions.resultSize)
                    ?.map { annotation ->
                        SearchResultItem(
                            title = annotation.url!!,
                            url = annotation.url,
                            text = ""
                        )
                    } ?: emptyList()

                return@withContext Result.success(
                    SearchResult(
                        answer = answer,
                        items = items
                    )
                )
            } else {
                error("response failed #${response.code}: ${response.body?.string()}")
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.GrokOptions
    ): Result<ScrapedResult> {
        return Result.failure(Exception("Scraping is not supported for Grok"))
    }

    @Serializable
    private data class GrokResponse(
        val output: List<GrokOutputItem> = emptyList()
    )

    @Serializable
    private data class GrokOutputItem(
        val type: String,
        val role: String? = null,
        val status: String? = null,
        val content: List<GrokContent>? = null,
    )

    @Serializable
    private data class GrokContent(
        val type: String,
        val text: String? = null,
        val annotations: List<GrokAnnotation>? = null
    )

    @Serializable
    private data class GrokAnnotation(
        val type: String,
        val url: String? = null,
        val title: String? = null,
        @SerialName("start_index") val startIndex: Int? = null,
        @SerialName("end_index") val endIndex: Int? = null
    )
}
