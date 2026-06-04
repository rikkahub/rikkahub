package me.rerere.search

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Regression coverage for "search not responding".
 *
 * The app wires the search subsystem with its shared OkHttp client, which carries a
 * 10-minute readTimeout tuned for long-running generation/SSE. Used verbatim, a slow
 * or hung search provider blocked the search_web tool — and the whole agent turn —
 * for up to ten minutes. [SearchService.init] must cap the cloned client to
 * search-appropriate timeouts so the tool always returns.
 */
class SearchServiceClientTest {

    @Test
    fun initCapsInheritedGenerationTimeout() {
        val generationClient = OkHttpClient.Builder()
            .readTimeout(10, TimeUnit.MINUTES)   // the shared app client's timeout
            .callTimeout(0, TimeUnit.MILLISECONDS) // unbounded, like the shared client
            .build()

        SearchService.init(generationClient, context = null)

        assertEquals(
            "search readTimeout must be capped to 30s, not the inherited 10 minutes",
            TimeUnit.SECONDS.toMillis(30).toInt(),
            SearchService.httpClient.readTimeoutMillis,
        )
        assertEquals(
            "search callTimeout must bound the whole call to 45s",
            TimeUnit.SECONDS.toMillis(45).toInt(),
            SearchService.httpClient.callTimeoutMillis,
        )
        assertTrue(
            "search client must not inherit the 10-minute generation readTimeout",
            SearchService.httpClient.readTimeoutMillis < TimeUnit.MINUTES.toMillis(1),
        )
    }

    @Test
    fun initPreservesSharedClientConfiguration() {
        // The cloned client must reuse the shared client's pool/dispatcher, not be a
        // fresh unconfigured instance — so it keeps the app's interceptors.
        val shared = OkHttpClient.Builder()
            .readTimeout(10, TimeUnit.MINUTES)
            .build()

        SearchService.init(shared, context = null)

        assertEquals(shared.connectionPool, SearchService.httpClient.connectionPool)
        assertEquals(shared.dispatcher, SearchService.httpClient.dispatcher)
    }
}
