package me.rerere.common.http

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

val JsonElement.jsonObjectOrNull: JsonObject?
    get() = this as? JsonObject

val JsonElement.jsonArrayOrNull: JsonArray?
    get() = this as? JsonArray

val JsonElement.jsonPrimitiveOrNull: JsonPrimitive?
    get() = this as? JsonPrimitive

/** [key] 对应的值是基本类型时返回它的字符串内容，不存在、是 null 或是对象/数组时返回 null */
fun JsonObject.stringOrNull(key: String): String? = this[key]?.jsonPrimitiveOrNull?.contentOrNull

fun JsonObject.getByKey(key: String): String {
    return evaluateJsonExpr(key, this)
}
