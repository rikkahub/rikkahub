package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

private const val WEB_FETCH_TIMEOUT_MS = 30_000L
private const val WEB_FETCH_BODY_CAP = 8 * 1024  // 8 KB

/**
 * Lightweight HTTP GET/POST tool so workflows / the LLM can fetch a URL without driving the
 * full in-app browser or shelling out to Termux+curl. Backed by the DI [OkHttpClient]
 * singleton (already NetworkChangeMonitor-registered). 30 s hard timeout via
 * [withTimeoutOrNull]; the response body is capped at 8 KB and the cap flagged.
 */
fun webFetchTool(client: OkHttpClient): Tool = Tool(
    name = "web_fetch",
    description = """
        Fetch a URL over HTTP(S). method is GET (default) or POST. Optionally pass headers
        (object of name->value) and a body string (POST only). Hard 30s timeout. The response
        body is capped at 8192 bytes; body_truncated=true when the response was longer.
        Returns {status, ok, headers, body, body_truncated} or {error, detail, recovery}.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "The http:// or https:// URL to fetch")
                })
                put("method", buildJsonObject {
                    put("type", "string")
                    put("description", "GET (default) or POST")
                })
                put("headers", buildJsonObject {
                    put("type", "object")
                    put("description", "Optional request headers as a name->value object")
                })
                put("body", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional request body string (POST only)")
                })
            },
            required = listOf("url"),
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        val url = obj["url"]?.jsonPrimitive?.contentOrNull?.trim()
        if (url.isNullOrBlank()) {
            return@Tool fmTextPart(fmErrEnvelope("missing_url", "url is required"))
        }
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            return@Tool fmTextPart(
                buildJsonObject {
                    put("error", "bad_url")
                    put("detail", "url must start with http:// or https://")
                    put("recovery", "Pass an absolute http(s) URL.")
                }.toString()
            )
        }
        val method = obj["method"]?.jsonPrimitive?.contentOrNull?.trim()?.uppercase() ?: "GET"
        if (method != "GET" && method != "POST") {
            return@Tool fmTextPart(
                buildJsonObject {
                    put("error", "bad_method")
                    put("detail", "method must be GET or POST, got $method")
                    put("recovery", "Use method=GET or method=POST.")
                }.toString()
            )
        }
        val bodyStr = obj["body"]?.jsonPrimitive?.contentOrNull

        val request = try {
            val builder = Request.Builder().url(url)
            (obj["headers"] as? kotlinx.serialization.json.JsonObject)?.forEach { (name, value) ->
                value.jsonPrimitive.contentOrNull?.let { builder.header(name, it) }
            }
            if (method == "POST") {
                builder.post((bodyStr ?: "").toRequestBody())
            } else {
                builder.get()
            }
            builder.build()
        } catch (e: IllegalArgumentException) {
            return@Tool fmTextPart(
                buildJsonObject {
                    put("error", "bad_request")
                    put("detail", e.message ?: "Could not build request")
                    put("recovery", "Check the URL and header names for invalid characters.")
                }.toString()
            )
        }

        val result = withTimeoutOrNull(WEB_FETCH_TIMEOUT_MS) {
            try {
                client.newCall(request).execute().use { resp ->
                    // Read the body through a bounded buffer instead of resp.body.bytes(),
                    // which would pull the whole (possibly multi-GB) response into memory.
                    // Read at most CAP+1 bytes: the extra byte tells us more remained.
                    val (raw, truncated) = readBounded(resp.body.byteStream(), WEB_FETCH_BODY_CAP)
                    val bodyText = String(
                        raw, 0, minOf(raw.size, WEB_FETCH_BODY_CAP), Charsets.UTF_8,
                    )
                    buildJsonObject {
                        put("status", resp.code)
                        put("ok", resp.isSuccessful)
                        put("headers", buildJsonObject {
                            resp.headers.forEach { (n, v) -> put(n, v) }
                        })
                        put("body", bodyText)
                        put("body_truncated", truncated)
                    }.toString()
                }
            } catch (e: IOException) {
                buildJsonObject {
                    put("error", "network_error")
                    put("detail", e.message ?: e::class.java.simpleName)
                    put("recovery", "Check connectivity and that the host is reachable, then retry.")
                }.toString()
            }
        } ?: buildJsonObject {
            put("error", "timeout")
            put("detail", "Request exceeded the 30s limit.")
            put("recovery", "The host is slow or unreachable; try a smaller request or a different URL.")
        }.toString()

        fmTextPart(result)
    },
)

/**
 * Read at most [cap] bytes from [ins], plus one probe byte to detect overflow. Returns the
 * accumulated bytes (up to cap+1) and a truncated flag set when the stream had more than
 * [cap] bytes. Bounds memory regardless of Content-Length or a missing/lying one.
 */
internal fun readBounded(ins: InputStream, cap: Int): Pair<ByteArray, Boolean> {
    val out = ByteArrayOutputStream(minOf(cap, 8 * 1024))
    val buf = ByteArray(8192)
    // Stop once we have cap+1 bytes: the (cap+1)th byte is enough to flag truncation
    // without buffering the rest of the response.
    val limit = cap.toLong() + 1
    var total = 0L
    while (total < limit) {
        val want = minOf(buf.size.toLong(), limit - total).toInt()
        val read = ins.read(buf, 0, want)
        if (read < 0) break
        out.write(buf, 0, read)
        total += read
    }
    val bytes = out.toByteArray()
    return bytes to (bytes.size > cap)
}
