package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.MessageRole

data class ChatPromptPreviewMessage(
    val role: MessageRole,
    val content: String,
    val tokenEstimate: Int,
)

data class ChatRuntimeInspection(
    val assistantName: String,
    val characterName: String,
    val modelName: String,
    val presetName: String,
    val generationType: String,
    val promptMessages: List<ChatPromptPreviewMessage>,
    val promptTokenEstimate: Int,
    val localVariables: Map<String, String>,
    val globalVariables: Map<String, String>,
    val contextVariables: JsonObject,
)
