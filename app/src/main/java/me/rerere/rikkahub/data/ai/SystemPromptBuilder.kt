package me.rerere.rikkahub.data.ai

/**
 * Single place for assembling the system prompt that is sent to every provider.
 *
 * Callers provide pre-rendered sections so the ordering and formatting live in one spot
 * rather than being reimplemented in GenerationHandler and the provider adapters.
 */
class SystemPromptBuilder {

    fun build(
        assistantPrompt: String,
        memoryPrompt: String = "",
        recentChatsPrompt: String = "",
        toolPrompts: List<String> = emptyList(),
        systemAddendum: String? = null,
    ): String = buildString {
        if (assistantPrompt.isNotBlank()) {
            append(assistantPrompt)
        }

        if (memoryPrompt.isNotBlank()) {
            appendLine()
            append(memoryPrompt)
        }

        if (recentChatsPrompt.isNotBlank()) {
            appendLine()
            append(recentChatsPrompt)
        }

        if (toolPrompts.isNotEmpty()) {
            appendLine()
            appendLine("Tool cost guidance: prefer low-cost text tools before expensive visual or broad tools. Use read_window_tree/browser_get_text before screenshots when text is enough, and avoid repeating high-cost tools unless the state likely changed.")
            toolPrompts.forEach { toolPrompt ->
                appendLine()
                append(toolPrompt)
            }
        }

        if (!systemAddendum.isNullOrBlank()) {
            appendLine()
            append(systemAddendum)
        }
    }
}
