package me.rerere.rikkahub.data.ai.memory

/**
 * Recall-path carrier for a single memory, holding the timestamp fields the age render needs.
 *
 * Deliberately a PLAIN (non-`@Serializable`) data class, NOT [me.rerere.rikkahub.data.model.AssistantMemory]:
 * `AssistantMemory` is the `memory_tool` result DTO and the app `Json` has `encodeDefaults = true`,
 * so enriching it would leak `createdAt`/`updatedAt` into every tool result the model sees. The two
 * concerns are split — `AssistantMemory` stays lean `{id, content}` for the tool result, this type
 * carries the render fields on the recall path only and is never serialized.
 */
data class RecalledMemory(
    val id: Int,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
)
