package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object CustomJsSearchService : SearchService<SearchServiceOptions.CustomJsOptions> {
    override val name: String = "Custom JS"

    @Composable
    override fun Description() {
        Text(stringResource(R.string.custom_js_desc))
    }

    override fun parameters(options: SearchServiceOptions.CustomJsOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.CustomJsOptions): InputSchema? {
        if (options.scrapeScript.isBlank()) return null
        return InputSchema.Obj(
            properties = buildJsonObject {
                put("urls", buildJsonObject {
                    put("type", "array")
                    put("description", "urls to scrape")
                })
            },
            required = listOf("urls")
        )
    }

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.CustomJsOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            val script = serviceOptions.searchScript.ifBlank { error("Search script is empty") }

            val resultJson = executeScript(
                userScript = script,
                invocation = "search(${quoteJsString(query)}, ${commonOptions.resultSize})"
            )

            json.decodeFromString<SearchResult>(resultJson)
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.CustomJsOptions
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            val script = serviceOptions.scrapeScript.ifBlank { error("Scrape script is empty") }
            val urlsJson = params["urls"]?.toString() ?: error("urls is required")

            val resultJson = executeScript(
                userScript = script,
                invocation = "scrape($urlsJson)"
            )

            json.decodeFromString<ScrapedResult>(resultJson)
        }
    }

    private fun executeScript(userScript: String, invocation: String): String {
        val context = QuickJSContext.create()
        try {
            injectApis(context)
            context.evaluate(userScript)

            val result = context.evaluate("JSON.stringify($invocation)")
            return result as? String ?: error("Function returned null or undefined")
        } finally {
            context.destroy()
        }
    }

    private fun injectApis(context: QuickJSContext) {
        context.globalObject.setProperty("__httpRequest", JSCallFunction { args ->
            val url = args[0] as? String ?: error("url is required")
            val method = (args[1] as? String ?: "GET").uppercase()
            val headersJson = args[2] as? String
            val body = args[3] as? String

            val requestBuilder = Request.Builder().url(url)

            val parsedHeaders = if (!headersJson.isNullOrBlank() && headersJson != "null") {
                json.parseToJsonElement(headersJson).jsonObject
            } else null

            parsedHeaders?.entries?.forEach { (key, value) ->
                requestBuilder.addHeader(key, value.jsonPrimitive.content)
            }

            val contentType = try {
                parsedHeaders?.get("Content-Type")?.jsonPrimitive?.content
            } catch (_: Exception) {
                null
            }

            val mediaType = (contentType ?: "application/json").toMediaType()
            when (method) {
                "GET" -> requestBuilder.get()
                "HEAD" -> requestBuilder.head()
                else -> {
                    val reqBody = body?.toRequestBody(mediaType)
                        ?: if (method in setOf("POST", "PUT", "PATCH")) {
                            "".toRequestBody(mediaType)
                        } else {
                            null
                        }
                    requestBuilder.method(method, reqBody)
                }
            }

            val response = httpClient.newCall(requestBuilder.build()).execute()
            val responseBody = response.body.string()
            val code = response.code
            val message = response.message
            response.close()

            json.encodeToString(
                HttpResponseDto.serializer(),
                HttpResponseDto(
                    status = code,
                    ok = code in 200..299,
                    statusText = message,
                    body = responseBody,
                )
            )
        })

        context.evaluate(FETCH_POLYFILL)
    }

    private fun quoteJsString(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    @Serializable
    private data class HttpResponseDto(
        val status: Int,
        val ok: Boolean,
        val statusText: String,
        val body: String,
    )

    // fetch() returns a Response object synchronously (not a Promise)
    // because this QuickJS wrapper doesn't support microtask scheduling.
    private const val FETCH_POLYFILL = """
globalThis.fetch = function(url, options) {
    options = options || {};
    var method = (options.method || 'GET').toUpperCase();
    var headers = options.headers ? JSON.stringify(options.headers) : null;
    var body = options.body;
    if (typeof body === 'object' && body !== null) {
        body = JSON.stringify(body);
    } else if (typeof body !== 'string') {
        body = null;
    }

    var raw = __httpRequest(url, method, headers, body);
    var data = JSON.parse(raw);
    return {
        status: data.status,
        ok: data.ok,
        statusText: data.statusText,
        url: url,
        _body: data.body,
        text: function() { return this._body; },
        json: function() { return JSON.parse(this._body); }
    };
};
"""
}
