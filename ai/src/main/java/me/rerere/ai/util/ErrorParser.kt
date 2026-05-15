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
                HttpException(Json.encodeToString(JsonElement.serializer(), this))
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
            // 对于基本类型，直接使用其内容 (covers JsonNull too — it's a JsonPrimitive subclass)
            HttpException(this.jsonPrimitive.content)
        }
    }
}
