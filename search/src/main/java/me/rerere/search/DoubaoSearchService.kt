package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
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

object DoubaoSearchService : SearchService<SearchServiceOptions.DoubaoOptions> {
    override val name: String = "Doubao"

    @Composable
    override fun Description() {
        val uriHandler = LocalUriHandler.current
        TextButton(onClick = {
            uriHandler.openUri("https://console.volcengine.com/search-infinity/api-key")
        }) {
            Text("豆包搜索 API Key")
        }
    }

    override fun parameters(options: SearchServiceOptions.DoubaoOptions): InputSchema =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.DoubaoOptions): InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.DoubaoOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            val body = when (serviceOptions.mode) {
                DoubaoSearchMode.GLOBAL -> buildJsonObject {
                    put("Query", query)
                    put("DocCount", commonOptions.resultSize.coerceIn(1, 20))
                    put("MaxSnippetLength", 300)
                    put("MaxImageCountPerDoc", 1)
                }

                DoubaoSearchMode.CUSTOM -> buildJsonObject {
                    put("Query", query)
                    put("SearchType", "web")
                    put("Count", commonOptions.resultSize.coerceIn(1, 50))
                    put("QueryControl", buildJsonObject {
                        put("QueryRewrite", false)
                    })
                }
            }
            val endpoint = when (serviceOptions.mode) {
                DoubaoSearchMode.GLOBAL -> "global_search"
                DoubaoSearchMode.CUSTOM -> "web_search"
            }
            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())
            val request = Request.Builder()
                .url("https://open.feedcoopapi.com/search_api/$endpoint")
                .post(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Authorization", "Bearer $apiKey")
                .build()

            httpClient.newCall(request).await().use { response ->
                val responseBody = response.body.string()
                if (!response.isSuccessful) {
                    error("Doubao search failed #${response.code}: $responseBody")
                }
                parseResponse(serviceOptions.mode, responseBody)
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.DoubaoOptions
    ): Result<ScrapedResult> =
        Result.failure(Exception("Scraping is not supported for Doubao"))

    internal fun parseResponse(mode: DoubaoSearchMode, responseBody: String): SearchResult {
        return when (mode) {
            DoubaoSearchMode.GLOBAL -> {
                val response = json.decodeFromString<GlobalResponse>(responseBody)
                response.metadata.error?.let { error(it.message ?: it.code ?: "Doubao API error") }
                val result = response.result ?: error("Doubao response does not contain Result")
                if (result.errorCode != null && result.errorCode != 0) {
                    error(result.errorMsg ?: "Doubao API error #${result.errorCode}")
                }
                SearchResult(
                    items = result.documents.map { document ->
                        SearchResultItem(
                            title = document.title,
                            url = document.url,
                            text = document.snippet.mapNotNull { it.text }.joinToString("\n")
                        )
                    },
                    images = result.documents.flatMap { document ->
                        document.snippet.mapNotNull { it.image?.imageUrl }
                    }.distinct()
                )
            }

            DoubaoSearchMode.CUSTOM -> {
                val response = json.decodeFromString<CustomResponse>(responseBody)
                response.metadata.error?.let { error(it.message ?: it.code ?: "Doubao API error") }
                val result = response.result ?: error("Doubao response does not contain Result")
                SearchResult(
                    items = result.webResults.map { item ->
                        SearchResultItem(
                            title = item.title,
                            url = item.url,
                            text = item.summary?.takeIf(String::isNotBlank) ?: item.snippet.orEmpty()
                        )
                    }
                )
            }
        }
    }

    @Serializable
    internal data class ResponseMetadata(
        @SerialName("Error") val error: ApiError? = null,
    )

    @Serializable
    internal data class ApiError(
        @SerialName("Code") val code: String? = null,
        @SerialName("Message") val message: String? = null,
    )

    @Serializable
    internal data class GlobalResponse(
        @SerialName("ResponseMetadata") val metadata: ResponseMetadata = ResponseMetadata(),
        @SerialName("Result") val result: GlobalResult? = null,
    )

    @Serializable
    internal data class GlobalResult(
        @SerialName("Documents") val documents: List<GlobalDocument> = emptyList(),
        @SerialName("ErrorCode") val errorCode: Int? = null,
        @SerialName("ErrorMsg") val errorMsg: String? = null,
    )

    @Serializable
    internal data class GlobalDocument(
        @SerialName("Url") val url: String,
        @SerialName("Title") val title: String,
        @SerialName("Snippet") val snippet: List<GlobalSnippet> = emptyList(),
    )

    @Serializable
    internal data class GlobalSnippet(
        @SerialName("Text") val text: String? = null,
        @SerialName("Image") val image: GlobalImage? = null,
    )

    @Serializable
    internal data class GlobalImage(
        @SerialName("ImageUrl") val imageUrl: String? = null,
    )

    @Serializable
    internal data class CustomResponse(
        @SerialName("ResponseMetadata") val metadata: ResponseMetadata = ResponseMetadata(),
        @SerialName("Result") val result: CustomResult? = null,
    )

    @Serializable
    internal data class CustomResult(
        @SerialName("WebResults") val webResults: List<CustomWebResult> = emptyList(),
    )

    @Serializable
    internal data class CustomWebResult(
        @SerialName("Title") val title: String,
        @SerialName("Url") val url: String,
        @SerialName("Snippet") val snippet: String? = null,
        @SerialName("Summary") val summary: String? = null,
    )

    private val JSON_MEDIA_TYPE = "application/json".toMediaType()
}
