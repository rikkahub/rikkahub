package me.rerere.rikkahub.data.ai

/**
 * Single place for assembling the system prompt that is sent to every provider.
 *
 * Callers provide pre-rendered sections so the ordering and formatting live in one spot
 * rather than being reimplemented in GenerationHandler and the provider adapters.
 *
 * Ordering is **stable-first**: the assistant prompt and tool prompts (byte-identical turn
 * to turn) come first, then the volatile sections (memory, recent chats, per-call
 * addendum) that change between turns. This lets prompt caching work: auto-caching
 * providers (OpenAI/DeepSeek/Grok/Gemini) reuse the stable byte-prefix, and OpenRouter's
 * explicit cache_control breakpoint is placed at the stable/volatile boundary (see
 * ChatCompletionsAPI). Volatile text in the prefix busts the cache every turn, which is
 * what happened before when memory was enabled.
 */
class SystemPromptBuilder {

    /**
     * Returns the system prompt split into `(stable, volatile)`.
     * - stable: assistant prompt + tool cost guidance + tool prompts.
     * - volatile: memory + recent chats + per-call addendum.
     * Either may be blank.
     */
    fun buildSections(
        assistantPrompt: String,
        memoryPrompt: String = "",
        recentChatsPrompt: String = "",
        toolPrompts: List<String> = emptyList(),
        systemAddendum: String? = null,
    ): Pair<String, String> {
        val stable = buildString {
            if (assistantPrompt.isNotBlank()) append(assistantPrompt)
            if (toolPrompts.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                appendLine("Tool cost guidance: prefer low-cost text tools before expensive visual or broad tools. Use read_window_tree/browser_get_text before screenshots when text is enough, and avoid repeating high-cost tools unless the state likely changed.")
                toolPrompts.forEachIndexed { index, toolPrompt ->
                    if (index > 0) appendLine()
                    append(toolPrompt)
                }
            }
        }.trim()

        val volatile = buildString {
            if (memoryPrompt.isNotBlank()) append(memoryPrompt)
            if (recentChatsPrompt.isNotBlank()) {
                if (isNotEmpty()) appendLine()
                append(recentChatsPrompt)
            }
            if (!systemAddendum.isNullOrBlank()) {
                if (isNotEmpty()) appendLine()
                append(systemAddendum)
            }
        }.trim()

        return stable to volatile
    }

    /** Combined single-string prompt (stable then volatile), for callers/providers that do
     *  not split into cache blocks. */
    fun build(
        assistantPrompt: String,
        memoryPrompt: String = "",
        recentChatsPrompt: String = "",
        toolPrompts: List<String> = emptyList(),
        systemAddendum: String? = null,
    ): String {
        val (stable, volatile) = buildSections(
            assistantPrompt, memoryPrompt, recentChatsPrompt, toolPrompts, systemAddendum
        )
        return listOf(stable, volatile).filter { it.isNotBlank() }.joinToString("\n")
    }
}
