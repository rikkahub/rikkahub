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
import me.rerere.search.SearchService.Companion.keyRoulette
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "SerperSearchService"

object SerperSearchService : SearchService<SearchServiceOptions.SerperOptions> {
    override val name: String = "Serper"

    @Composable
    override fun Description() {
        val urlHandler = LocalUriHandler.current
        TextButton(
            onClick = {
                urlHandler.openUri("https://serper.dev/api-keys")
            }
        ) {
            Text(stringResource(R.string.click_to_get_api_key))
        }
    }

    override fun parameters(options: SearchServiceOptions.SerperOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.SerperOptions): InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.SerperOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")

            val body = buildJsonObject {
                put("q", query)
                put("num", commonOptions.resultSize)
            }
            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())

            val request = Request.Builder()
                .url("https://google.serper.dev/search")
                .post(body.toString().toRequestBody())
                .addHeader("X-API-KEY", apiKey)
                .addHeader("Content-Type", "application/json")
                .build()

            val response = httpClient.newCall(request).await()
            if (response.isSuccessful) {
                val responseBody = response.body.string()
                val searchResponse = json.decodeFromString<SerperSearchResponse>(responseBody)

                val answer = searchResponse.answerBox?.let { it.answer ?: it.snippet }
                    ?: searchResponse.knowledgeGraph?.description

                val items = searchResponse.organic.map { result ->
                    SearchResultItem(
                        title = result.title,
                        url = result.link,
                        text = result.snippet ?: ""
                    )
                }

                return@withContext Result.success(
                    SearchResult(
                        answer = answer,
                        items = items
                    )
                )
            } else {
                error("Serper search failed with code ${response.code}: ${response.message}")
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.SerperOptions
    ): Result<ScrapedResult> {
        return Result.failure(Exception("Scraping is not supported for Serper"))
    }

    @Serializable
    data class SerperSearchResponse(
        val answerBox: AnswerBox? = null,
        val knowledgeGraph: KnowledgeGraph? = null,
        val organic: List<OrganicResult> = emptyList(),
    )

    @Serializable
    data class AnswerBox(
        val answer: String? = null,
        val snippet: String? = null,
        val title: String? = null,
    )

    @Serializable
    data class KnowledgeGraph(
        val title: String? = null,
        val description: String? = null,
    )

    @Serializable
    data class OrganicResult(
        val title: String,
        val link: String,
        val snippet: String? = null,
    )
}
