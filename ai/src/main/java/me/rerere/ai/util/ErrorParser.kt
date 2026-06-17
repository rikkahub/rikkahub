package me.rerere.ai.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

class HttpException(
    message: String
) : RuntimeException(message)

/**
 * Resolves a non-2xx response body into the typed [HttpException] the streaming paths already
 * produce (their onFailure handlers route the body through [parseErrorDetail]). A non-JSON body
 * (e.g. an HTML proxy error page) falls back to the raw code+body shape so nothing is lost — but
 * the body is bounded first: a verbose HTML error page or a multi-megabyte upstream dump must not
 * be copied verbatim into an exception message (and from there into logs / any surfaced error).
 */
fun parseHttpErrorBody(code: Int, body: String): HttpException =
    runCatching { Json.parseToJsonElement(body).parseErrorDetail() }
        .getOrElse { HttpException("Failed to get response: $code ${body.truncateForErrorMessage()}") }

internal const val MAX_ERROR_BODY_CHARS = 2000

/** Cap an untrusted upstream error body so it stays a readable diagnostic, not a memory/log bomb. */
internal fun String.truncateForErrorMessage(): String =
    if (length <= MAX_ERROR_BODY_CHARS) this
    else take(MAX_ERROR_BODY_CHARS) + "… (${length - MAX_ERROR_BODY_CHARS} more chars truncated)"

fun JsonElement.parseErrorDetail(): HttpException {
    return when (this) {
        is JsonObject -> {
            // 尝试获取常见的错误字段
            val errorFields = listOf("error", "detail", "message", "description")

            // 查找第一个存在的错误字段
            val foundField = errorFields.firstOrNull { this[it] != null }

            if (foundField != null) {
                // 递归解析找到的字段值
                this[foundField]!!.parseErrorDetail()
            } else {
                // 如果没有找到任何错误字段，序列化整个对象
                HttpException(Json.encodeToString(JsonElement.serializer(), this).truncateForErrorMessage())
            }
        }

        is JsonArray -> {
            if (this.isEmpty()) {
                HttpException("Unknown error: Empty JSON array")
            } else {
                // 递归解析数组的第一个元素
                this.first().parseErrorDetail()
            }
        }

        is JsonPrimitive -> {
            // 对于基本类型，直接使用其内容
            // A non-JSON body (HTML proxy page) parses to a bare string primitive and lands here,
            // so this is the actual verbatim-copy path — bound it like the other leaves.
            HttpException(this.jsonPrimitive.content.truncateForErrorMessage())
        }
    }
}
