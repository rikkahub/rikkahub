package me.rerere.ai.runtime.memory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.runtime.contract.AssistantMemory
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.time.toLocalString
import java.time.LocalDate

/**
 * Callback-based `memory_tool` builder (issue #243 slice 8/10), moved to `:ai-runtime` so the
 * tool-assembly path no longer reaches into the app for it. The store is injected as suspend
 * callbacks returning the neutral [AssistantMemory] (the composition root binds them over
 * `MemoryRepository`), so no Room/app type leaks across the boundary.
 *
 * The tool result carries `{id, content}` built explicitly via [buildJsonObject] rather than
 * `AssistantMemory.serializer()` — the contract [AssistantMemory] is deliberately NON-`@Serializable`
 * (issue #243 §B), and a hand-built object keeps the wire shape the model sees identical to the old
 * app tool without forcing the contract type to become serializable.
 */
fun buildMemoryTools(
    json: Json,
    onCreation: suspend (String) -> AssistantMemory,
    onUpdate: suspend (Int, String) -> AssistantMemory,
    onDelete: suspend (Int) -> Unit
): List<Tool> = listOf(
    Tool(
        name = "memory_tool",
        description = """
            The memory tool stores long-term information across conversations.
            Use `action` to control the operation: `create` (add), `edit` (update), `delete` (remove).
            Store only durable facts that are likely to help in later conversations. Do not store one-off
            task details, raw file/document contents, temporary debugging output, or exact private paths
            unless the user explicitly asks you to remember them.
            - No relevant record on the same user/entity/project/preference: `create` + `content`
            - Existing record on the same subject that the new fact corrects, refines, or extends without
              conflict: `edit` + `id` + merged `content`
            - Existing record is false, obsolete, or the user asks you to forget it: `delete` + `id`
            Memories will automatically appear in the <memory> tag in later conversations.
            Do not store sensitive information (e.g., ethnicity, religion, sexual orientation, political
            views, sex life, criminal records) or secrets (e.g., passwords, tokens, API keys).
            You may store: preferred name, durable preferences, recurring plans, stable work/project notes,
            chat style preferences, first chat time, and durable technical environment preferences.
            Do not show memory content directly in the conversation unless the user explicitly asks.
            Today is ${LocalDate.now().toLocalString(true)}.
            Prefer updating an existing record over creating a duplicate when the new content is about the
            same subject.

            Examples:
            {"action":"create","content":"User prefers brief replies and is more active on weekends."}
            {"action":"edit","id":12,"content":"User prefers the name A-Xing and Chinese replies."}
            {"action":"delete","id":7}
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("create")
                                add("edit")
                                add("delete")
                            }
                        )
                        put("description", "Operation to perform: create, edit, or delete")
                    })
                    put("id", buildJsonObject {
                        put("type", "integer")
                        put("description", "The id of the memory record (required for edit/delete)")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "The content of the memory record (required for create/edit)")
                    })
                },
                required = listOf("action")
            )
        },
        execute = {
            val params = it.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
            val payload = when (action) {
                "create" -> {
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                    onCreation(content).toResultJson()
                }

                "edit" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                    onUpdate(id, content).toResultJson()
                }

                "delete" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    onDelete(id)
                    buildJsonObject {
                        put("success", true)
                        put("id", id)
                    }
                }

                else -> error("unknown action: $action, must be one of [create, edit, delete]")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
)

private fun AssistantMemory.toResultJson() = buildJsonObject {
    put("id", id)
    put("content", content)
}
