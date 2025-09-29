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

object ExaSearchService : SearchService<SearchServiceOptions.ExaOptions> {
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
        serviceOptions: SearchServiceOptions.ExaOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            val body = buildJsonObject {
                put("query", JsonPrimitive(query))
                put("numResults", JsonPrimitive(commonOptions.resultSize))
                put("contents", buildJsonObject {
                    put("text", JsonPrimitive(true))
                })
            }

            val request = Request.Builder()
                .url("https://api.exa.ai/search")
                .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer ${serviceOptions.apiKey}")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyRaw = response.body?.string() ?: error("Failed to get response body")
                val response = runCatching {
                    json.decodeFromString<ExaData>(bodyRaw)
                }.onFailure {
                    it.printStackTrace()
                    println(bodyRaw)
                    error("Failed to decode response: $bodyRaw")
                }.getOrThrow()

                return@withContext Result.success(
                    SearchResult(
                        items = response.results.map {
                            SearchResultItem(
                                title = it.title,
                                url = it.url,
                                text = it.text
                            )
                        }
                    ))
            } else {
                println(response.body?.string())
                error("response failed #${response.code}")
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.ExaOptions
    ): Result<ScrapedResult> {
        return Result.failure(Exception("Scraping is not supported for Exa"))
    }

    @Serializable
    data class ExaData(
        @SerialName("requestId")
        val requestId: String,
        @SerialName("autopromptString")
        val autopromptString: String,
        @SerialName("resolvedSearchType")
        val resolvedSearchType: String,
        @SerialName("results")
        val results: List<ExaResult>,
        @SerialName("costDollars")
        val costDollars: ExaCostDollars
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
        val publishedDate: String?,
        @SerialName("author")
        val author: String?,
        @SerialName("text")
        val text: String,
    )

    @Serializable
    data class ExaCostDollars(
        @SerialName("total")
        val total: Double,
        @SerialName("search")
        val search: ExaSearchCost,
        @SerialName("contents")
        val contents: ExaContentsCost
    )

    @Serializable
    data class ExaSearchCost(
        @SerialName("neural")
        val neural: Double
    )

    @Serializable
    data class ExaContentsCost(
        @SerialName("text")
        val text: Double
    )
}
