package me.rerere.search

import androidx.compose.runtime.Composable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

interface SearchService<T : SearchServiceOptions> {
    val name: String

    @Composable
    fun Description()

    suspend fun search(
        query: String,
        commonOptions: SearchCommonOptions,
        serviceOptions: T
    ): Result<SearchResult>

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun <T : SearchServiceOptions> getService(options: T): SearchService<T> {
            return when (options) {
                is SearchServiceOptions.TavilyOptions -> TavilySearchService
                is SearchServiceOptions.ExaOptions -> ExaSearchService
                is SearchServiceOptions.ZhipuOptions -> ZhipuSearchService
                is SearchServiceOptions.BingLocalOptions -> BingSearchService
                is SearchServiceOptions.SearXNGOptions -> SearXNGService
            } as SearchService<T>
        }

        internal val httpClient by lazy {
            OkHttpClient.Builder()
                .build()
        }

        internal val json by lazy {
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        }
    }
}

@Serializable
data class SearchCommonOptions(
    val resultSize: Int = 10
)

@Serializable
data class SearchResult(
    val items: List<SearchResultItem>,
) {
    @Serializable
    data class SearchResultItem(
        val title: String,
        val url: String,
        val text: String,
    )
}

@Serializable
sealed class SearchServiceOptions {
    companion object {
        val DEFAULT = BingLocalOptions()

        val TYPES = mapOf(
            BingLocalOptions::class to "Bing",
            ZhipuOptions::class to "智谱",
            TavilyOptions::class to "Tavily",
            ExaOptions::class to "Exa",
            SearXNGOptions::class to "SearXNG"
        )
    }

    @Serializable
    @SerialName("bing_local")
    class BingLocalOptions : SearchServiceOptions()

    @Serializable
    @SerialName("zhipu")
    data class ZhipuOptions(
        val apiKey: String = ""
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("tavily")
    data class TavilyOptions(
        val apiKey: String = ""
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("exa")
    data class ExaOptions(
        val apiKey: String = ""
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("searxng")
    data class SearXNGOptions(
        val url: String = ""
    ) : SearchServiceOptions()
}