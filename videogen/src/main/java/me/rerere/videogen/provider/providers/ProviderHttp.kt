package me.rerere.videogen.provider.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.rerere.common.http.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal val videoGenerationJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

internal val jsonMediaType = "application/json".toMediaType()

internal suspend fun OkHttpClient.executeJson(request: Request, provider: String): JsonObject =
    newCall(request).await().use { response ->
        val text = response.body.string()
        val body = runCatching { videoGenerationJson.parseToJsonElement(text).jsonObject }
            .getOrElse { JsonObject(emptyMap()) }
        if (!response.isSuccessful) {
            val error = body.obj("error")
            throw VideoGenerationApiException(
                provider = provider,
                statusCode = response.code,
                code = error?.string("code") ?: body.string("code") ?: error?.string("type"),
                message = error?.string("message") ?: body.string("message") ?: text,
            )
        }
        body
    }

internal fun Request.Builder.postJson(body: JsonObject): Request.Builder =
    post(
        videoGenerationJson.encodeToString(JsonObject.serializer(), body)
            .toRequestBody(jsonMediaType)
    )

internal fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

internal fun JsonObject.long(name: String): Long? =
    this[name]?.jsonPrimitive?.longOrNull

internal fun JsonObject.int(name: String): Int? =
    this[name]?.jsonPrimitive?.intOrNull

internal fun JsonObject.double(name: String): Double? =
    this[name]?.jsonPrimitive?.doubleOrNull

internal fun JsonObject.obj(name: String): JsonObject? =
    this[name] as? JsonObject

class VideoGenerationApiException(
    val provider: String,
    val statusCode: Int,
    val code: String?,
    override val message: String,
) : Exception(
    "$provider video generation failed ($statusCode${
        code?.let { ", $it" }.orEmpty()
    }): $message"
)
