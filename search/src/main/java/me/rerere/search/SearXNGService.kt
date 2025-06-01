package me.rerere.search

import android.util.Log
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import okhttp3.Request
import java.net.URLEncoder

private const val TAG = "SearXNGService"

object SearXNGService : SearchService<SearchServiceOptions.SearXNGOptions> {
    override val name: String = "SearXNG"

    @Composable
    override fun Description() {
        Text("settings.yml内需要开启json format支持:")
        Text("""
            search:
              formats:
                - html
                - json
        """.trimIndent())
    }

    override suspend fun search(
        query: String,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.SearXNGOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(serviceOptions.url.isNotBlank()) {
                "SearXNG URL cannot be empty"
            }

            // 构建查询URL
            val baseUrl = serviceOptions.url.trimEnd('/')
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$baseUrl/search?q=$encodedQuery&format=json"

            // 发送请求
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            Log.i(TAG, "search: $url")

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyRaw = response.body?.string() ?: error("Failed to get response body")
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

                return@withContext Result.success(SearchResult(items))
            } else {
                val errorBody = response.body?.string()
                println("SearXNG API error: ${response.code} - $errorBody")
                error("SearXNG request failed with status ${response.code}")
            }
        }
    }

    @Serializable
    data class SearXNGResponse(
        @SerialName("query")
        val query: String,
        @SerialName("number_of_results")
        val numberOfResults: Int,
        @SerialName("results")
        val results: List<SearXNGResult>,
        @SerialName("answers")
        val answers: List<String> = emptyList(),
        @SerialName("corrections")
        val corrections: List<String> = emptyList(),
        @SerialName("suggestions")
        val suggestions: List<String> = emptyList(),
        @SerialName("unresponsive_engines")
        val unresponsiveEngines: List<List<String>> = emptyList()
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