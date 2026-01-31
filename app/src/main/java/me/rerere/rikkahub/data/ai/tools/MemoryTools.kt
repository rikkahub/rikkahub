package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.model.AssistantMemory

fun buildMemoryTools(
    json: Json,
    onCreation: suspend (String) -> AssistantMemory,
    onUpdate: suspend (Int, String) -> AssistantMemory,
    onDelete: suspend (Int) -> Unit
): List<Tool> = listOf(
    Tool(
        name = "create_memory",
        description = "create a memory record",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "The content of the memory record")
                    })
                },
                required = listOf("content")
            )
        },
        execute = {
            val params = it.jsonObject
            val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
            json.encodeToJsonElement(AssistantMemory.serializer(), onCreation(content))
        }
    ),
    Tool(
        name = "edit_memory",
        description = "update a memory record",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("id", buildJsonObject {
                        put("type", "integer")
                        put("description", "The id of the memory record")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "The content of the memory record")
                    })
                },
                required = listOf("id", "content"),
            )
        },
        execute = {
            val params = it.jsonObject
            val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
            val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
            json.encodeToJsonElement(AssistantMemory.serializer(), onUpdate(id, content))
        }
    ),
    Tool(
        name = "delete_memory",
        description = "delete a memory record",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("id", buildJsonObject {
                        put("type", "integer")
                        put("description", "The id of the memory record")
                    })
                },
                required = listOf("id")
            )
        },
        execute = {
            val params = it.jsonObject
            val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
            onDelete(id)
            JsonPrimitive(true)
        }
    )
)
