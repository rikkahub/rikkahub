package me.rerere.rikkahub.data.ai.agent.compact

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT

/**
 * 上下文压缩策略接口（学 CC/Codex compact）。
 * 由 [me.rerere.rikkahub.service.ChatService.compressConversation] 调用。
 */
interface CompactPolicy {
    /**
     * 是否应在生成前触发压缩（默认永不自动压缩，保持现状）。
     */
    fun shouldAutoCompact(messages: List<UIMessage>): Boolean = false

    /**
     * 构建压缩用提示。
     * @param template 用户可配置的模板（settings.compressPrompt）；默认 [DEFAULT_COMPRESS_PROMPT]
     */
    fun buildCompressPrompt(
        content: String,
        targetTokens: Int,
        locale: String,
        additionalContext: String,
        template: String = DEFAULT_COMPRESS_PROMPT,
    ): String = template
        .replace("{content}", content)
        .replace("{target_tokens}", targetTokens.toString())
        .replace("{locale}", locale)
        .replace("{additional_context}", additionalContext)
}

/** 默认：不自动压缩，占位符替换与历史行为一致。 */
object DefaultCompactPolicy : CompactPolicy
