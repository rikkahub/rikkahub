package me.rerere.rikkahub.data.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.InputSchema

private const val REF = "\$ref"

/**
 * 将 MCP 工具的 inputSchema 转成内部 [InputSchema]。
 *
 * [InputSchema.Obj] 不携带 `$defs`，且部分 provider（如 Gemini）不接受 `$ref`，
 * 因此在这里把文档内引用（`#/...`）内联展开，避免发出悬空引用导致整个请求被拒绝。
 */
internal fun ToolSchema.toSchema(): InputSchema {
    val properties = properties ?: JsonObject(emptyMap())
    val root = JsonObject(buildMap {
        put("type", type ?: JsonPrimitive("object"))
        put("properties", properties)
        defs?.let { put("\$defs", it) }
    })
    return InputSchema.Obj(
        properties = inlineSchemaMap(properties, root, emptySet()),
        required = required,
    )
}

// 值为子 Schema（或子 Schema 数组）的关键字
private val SCHEMA_KEYWORDS = setOf(
    "items", "prefixItems", "additionalItems", "unevaluatedItems", "contains",
    "additionalProperties", "unevaluatedProperties", "propertyNames",
    "allOf", "anyOf", "oneOf", "not", "if", "then", "else", "contentSchema",
)

// 值为「名称 -> 子 Schema」映射的关键字
private val SCHEMA_MAP_KEYWORDS = setOf(
    "properties", "patternProperties", "dependentSchemas", "\$defs", "definitions",
)

private fun inlineSchemaMap(schemas: JsonObject, root: JsonObject, resolving: Set<String>): JsonObject =
    JsonObject(schemas.mapValues { inlineSchema(it.value, root, resolving) })

/**
 * 只沿承载子 Schema 的关键字向下遍历；`enum`、`const`、`default`、`examples` 等字面量数据
 * 即使含有 `$ref` 字段也原样保留。
 */
private fun inlineSchema(schema: JsonElement, root: JsonObject, resolving: Set<String>): JsonElement {
    if (schema !is JsonObject) return schema
    val ref = (schema[REF] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val inlined = (if (ref != null) schema - REF else schema).mapValues { (key, value) ->
        when {
            key in SCHEMA_KEYWORDS && value is JsonArray ->
                JsonArray(value.map { inlineSchema(it, root, resolving) })

            key in SCHEMA_KEYWORDS -> inlineSchema(value, root, resolving)
            key in SCHEMA_MAP_KEYWORDS && value is JsonObject -> inlineSchemaMap(value, root, resolving)
            else -> value
        }
    }
    if (ref == null) return JsonObject(inlined)

    val target = root.resolvePointer(ref) as? JsonObject
    val expanded = when {
        // 无法解析的引用（外部 URL、指针不存在）退化为不限制类型
        target == null -> emptyMap()
        // 循环引用无法完全展开，在回到自身的位置只保留类型
        ref in resolving -> target.filterKeys { it == "type" }
        else -> inlineSchema(target, root, resolving + ref) as JsonObject
    }
    // 与 $ref 同级的关键字（description、default 等）覆盖被引用的定义
    return JsonObject(expanded + inlined)
}

/** 解析文档内 JSON Pointer 引用（如 `#/$defs/Foo`），找不到时返回 null。 */
private fun JsonObject.resolvePointer(ref: String): JsonElement? {
    if (!ref.startsWith("#")) return null
    val tokens = ref.removePrefix("#").split("/").drop(1)
        .map { it.replace("~1", "/").replace("~0", "~") }
    return tokens.fold<String, JsonElement?>(this) { current, token ->
        when (current) {
            is JsonObject -> current[token]
            is JsonArray -> token.toIntOrNull()?.let(current::getOrNull)
            else -> null
        }
    }
}
