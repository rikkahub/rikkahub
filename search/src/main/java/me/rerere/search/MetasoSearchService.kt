package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

object MetasoSearchService : SearchService<SearchServiceOptions.MetasoOptions> {
    override val name: String = "Metaso"

    @Composable
    override fun Description() {
        Text(buildAnnotatedString {
            append("秘塔搜索: ")
            withLink(LinkAnnotation.Url("https://metaso.cn/")) {
                append("https://metaso.cn/")
            }
        })
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
        serviceOptions: SearchServiceOptions.MetasoOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")

            val requestBody = buildJsonObject {
                put("q", JsonPrimitive(query))
                put("scope", JsonPrimitive("webpage"))
                put("size", JsonPrimitive(commonOptions.resultSize))
                put("includeSummary", JsonPrimitive(false))
            }

            val request = Request.Builder()
                .url("https://metaso.cn/api/v1/search")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer ${serviceOptions.apiKey}")
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .build()

            val response = httpClient.newCall(request).await()
            if (response.isSuccessful) {
                val bodyRaw = response.body?.string() ?: error("Failed to get response body")
                val searchResponse = runCatching {
                    json.decodeFromString<MetasoSearchResponse>(bodyRaw)
                }.onFailure {
                    it.printStackTrace()
                    println("Failed to decode Metaso response: $bodyRaw")
                    error("Failed to decode response: $bodyRaw")
                }.getOrThrow()

                return@withContext Result.success(
                    SearchResult(
                        items = searchResponse.webpages.map { webpage ->
                            SearchResultItem(
                                title = webpage.title,
                                url = webpage.link,
                                text = webpage.snippet ?: ""
                            )
                        }
                    )
                )
            } else {
                val errorBody = response.body?.string()
                println("Metaso search failed with code ${response.code}: $errorBody")
                error("Search request failed with code ${response.code}: $errorBody")
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.MetasoOptions
    ): Result<ScrapedResult> {
        return Result.failure(Exception("Scraping is not supported for Metaso"))
    }

    @Serializable
    data class MetasoSearchResponse(
        @SerialName("credits")
        val credits: Int,
        @SerialName("searchParameters")
        val searchParameters: MetasoSearchParameters,
        @SerialName("webpages")
        val webpages: List<MetasoWebpage>
    )

    @Serializable
    data class MetasoSearchParameters(
        @SerialName("q")
        val query: String,
        @SerialName("scope")
        val scope: String,
        @SerialName("size")
        val size: Int,
    )

    @Serializable
    data class MetasoWebpage(
        @SerialName("title")
        val title: String,
        @SerialName("link")
        val link: String,
        @SerialName("score")
        val score: String,
        @SerialName("snippet")
        val snippet: String?,
        @SerialName("summary")
        val summary: String?,
        @SerialName("position")
        val position: Int,
        @SerialName("date")
        val date: String,
    )
}
