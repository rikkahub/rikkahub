package me.rerere.ai.util

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.Response
import okhttp3.internal.closeQuietly
import okio.IOException
import kotlin.coroutines.resumeWithException

suspend fun Call.await(): Response {
    return suspendCancellableCoroutine { continuation ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { cause, _, _ ->
                    response.closeQuietly()
                }
            }
        })
    }
}

fun List<CustomHeader>.toHeaders(): Headers {
    return Headers.Builder().apply {
        this@toHeaders
            .filter { it.name.isNotBlank() }
            .forEach {
                add(it.name, it.value)
            }
    }.build()
}

fun JsonObject.mergeCustomBody(bodies: List<CustomBody>): JsonObject {
    if (bodies.isEmpty()) return this

    val content = toMutableMap()
    bodies.forEach { body ->
        if (body.key.isNotBlank()) {
            // 如果已存在相同键且两者都是JsonObject，则需要递归合并
            val existingValue = content[body.key]
            val newValue = body.value

            if (existingValue is JsonObject && newValue is JsonObject) {
                // 递归合并两个JsonObject
                content[body.key] = mergeJsonObjects(existingValue, newValue)
            } else {
                // 直接替换或添加
                content[body.key] = newValue
            }
        }
    }
    return JsonObject(content)
}

/**
 * 递归合并两个JsonObject
 */
private fun mergeJsonObjects(base: JsonObject, overlay: JsonObject): JsonObject {
    val result = base.toMutableMap()

    for ((key, value) in overlay) {
        val baseValue = result[key]

        result[key] = if (baseValue is JsonObject && value is JsonObject) {
            // 如果两者都是JsonObject，递归合并
            mergeJsonObjects(baseValue, value)
        } else {
            // 否则使用新值替换旧值
            value
        }
    }

    return JsonObject(result)
}

/**
 * 从 JsonElement 中移除或保留指定的键
 * @param keys 要操作的键列表
 * @param keepOnly 如果为 true，则只保留指定的键；如果为 false，则移除指定的键
 * @return 处理后的 JsonElement
 */
fun JsonElement.removeElements(keys: List<String>, keepOnly: Boolean = false): JsonElement {
    return when (this) {
        is JsonObject -> {
            val newContent = if (keepOnly) {
                // 只保留指定的键（且键存在）
                keys.mapNotNull { key ->
                    get(key)?.let { key to it }
                }.toMap()
            } else {
                // 移除指定的键
                toMap().filterKeys { key -> key !in keys }
            }
            
            // 递归处理嵌套的 JsonElement
            JsonObject(newContent.mapValues { (_, value) -> 
                value.removeElements(keys, keepOnly) 
            })
        }

        is JsonArray -> {
            JsonArray(map { it.removeElements(keys, keepOnly) })
        }

        else -> this // 基本类型直接返回
    }
}