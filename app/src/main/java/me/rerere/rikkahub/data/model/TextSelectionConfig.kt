package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class TextSelectionConfig(
    val assistantId: Uuid? = null,
    val actions: List<TextSelectionAction> = DEFAULT_TEXT_SELECTION_ACTIONS,
    val translateLanguage: String = "English",
)

@Serializable
data class TextSelectionAction(
    val id: String = Uuid.random().toString(),
    val name: String,
    val icon: String,
    val prompt: String,
    val enabled: Boolean = true,
    val isCustomPrompt: Boolean = false,
)

val DEFAULT_TEXT_SELECTION_ACTIONS = listOf(
    TextSelectionAction(
        id = "translate",
        name = "Translate",
        icon = "translate",
        prompt = """
            You are a translator. Translate the user's text to {{language}}.
            Only output the translation, nothing else. Do not include any explanations or notes.
        """.trimIndent(),
    ),
    TextSelectionAction(
        id = "explain",
        name = "Explain",
        icon = "lightbulb",
        prompt = """
            Explain the following text in simple, easy-to-understand terms.
            Be concise but thorough. Use examples if helpful.
        """.trimIndent(),
    ),
    TextSelectionAction(
        id = "summarize",
        name = "Summarize",
        icon = "summarize",
        prompt = """
            Provide a clear, concise summary of the following text.
            Capture the key points and main ideas. Be brief but complete.
        """.trimIndent(),
    ),
    TextSelectionAction(
        id = "custom",
        name = "Ask",
        icon = "ask",
        prompt = """
            Answer the user's question about the provided text.
            User's question: {{custom_prompt}}
        """.trimIndent(),
        isCustomPrompt = true,
    ),
)
