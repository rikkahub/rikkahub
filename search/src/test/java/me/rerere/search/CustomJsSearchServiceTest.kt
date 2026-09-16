package me.rerere.search

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CustomJsSearchServiceTest {
    @Test(timeout = 15_000)
    fun `existing synchronous search and fetch options still work`() = runBlocking {
        val previousClient = SearchService.httpClient
        SearchService.httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("POST", request.method)
            assertEquals("token", request.header("X-Test"))
            val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            assertEquals("{\"query\":\"你好\\n\\\"\\u0001😀\",\"limit\":3}", body)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("""{"results":[{"title":"结果😀","url":"https://example.com","snippet":"内容"}]}""".toResponseBody())
                .build()
        }.build()
        try {
            val result = CustomJsSearchService.search(
                buildJsonObject { put("query", "你好\n\"\u0001😀") },
                SearchCommonOptions(resultSize = 3),
                SearchServiceOptions.CustomJsOptions(searchScript = """
                    function search(query, resultSize) {
                        console.log('searching');
                        const res = fetch('https://example.com', {
                            method: 'POST', headers: {'X-Test': 'token'},
                            body: {query: query, limit: resultSize}
                        });
                        if (!res.ok || res.status !== 200 || res.statusText !== 'OK') throw Error('response');
                        return {items: res.json().results.map(r => ({title:r.title, url:r.url, text:r.snippet}))};
                    }
                """.trimIndent()),
            ).getOrThrow()
            assertEquals("结果😀", result.items.single().title)
            assertEquals("内容", result.items.single().text)
        } finally {
            SearchService.httpClient = previousClient
        }
    }

    @Test(timeout = 15_000)
    fun `scraping supports async functions and response text`() = runBlocking {
        val previousClient = SearchService.httpClient
        SearchService.httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("page content".toResponseBody()).build()
        }.build()
        try {
            val result = CustomJsSearchService.scrape(
                buildJsonObject { put("urls", buildJsonArray { add("https://example.com") }) },
                SearchCommonOptions(),
                SearchServiceOptions.CustomJsOptions(scrapeScript = """
                    async function scrape(urls) {
                        const res = await fetch(urls[0]);
                        return {urls: [{url: res.url, content: await res.text()}]};
                    }
                """.trimIndent()),
            ).getOrThrow()
            assertEquals("page content", result.urls.single().content)
            assertEquals("https://example.com", result.urls.single().url)
        } finally {
            SearchService.httpClient = previousClient
        }
    }

    @Test(timeout = 15_000)
    fun `cancellation stops an in flight fetch`() = runBlocking {
        val previousClient = SearchService.httpClient
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        SearchService.httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            started.complete(Unit)
            while (!chain.call().isCanceled()) Thread.sleep(5)
            cancelled.complete(Unit)
            throw IOException("Canceled")
        }.build()
        try {
            val execution = async(Dispatchers.IO) {
                CustomJsSearchService.search(
                    buildJsonObject { put("query", "test") },
                    SearchCommonOptions(),
                    SearchServiceOptions.CustomJsOptions(),
                )
            }
            withTimeout(3_000) { started.await() }
            execution.cancel()
            withTimeout(3_000) {
                cancelled.await()
                execution.join()
            }
            assertTrue(execution.isCancelled)
        } finally {
            SearchService.httpClient.dispatcher.cancelAll()
            SearchService.httpClient = previousClient
        }
    }

    @Test(timeout = 15_000)
    fun `script errors do not poison subsequent searches`() = runBlocking {
        val params = buildJsonObject { put("query", "test") }
        val options = SearchServiceOptions.CustomJsOptions(searchScript = "throw Error('broken');")
        assertTrue(CustomJsSearchService.search(params, SearchCommonOptions(), options).isFailure)
        val valid = options.copy(searchScript = "async function search() { return {items: []}; }")
        assertTrue(CustomJsSearchService.search(params, SearchCommonOptions(), valid).getOrThrow().items.isEmpty())
    }
}
