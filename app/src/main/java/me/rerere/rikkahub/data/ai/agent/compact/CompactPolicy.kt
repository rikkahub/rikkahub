package me.rerere.rikkahub.data.ai.agent.compact

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT

/**
 * 上下文压缩策略接口（学 CC/Codex compact）。
 * 现有压缩 UI 对话框仍走原路径；本接口为可插拔扩展点。
 */
interface CompactPolicy {
    /**
     * 是否应在生成前触发压缩（默认永不自动压缩，保持现状）。
     */
    fun shouldAutoCompact(messages: List<UIMessage>): Boolean = false

    /**
     * 构建压缩用 system/user 提示（占位符与现有 [DEFAULT_COMPRESS_PROMPT] 对齐）。
     */
    fun buildCompressPrompt(
        content: String,
        targetTokens: Int,
        locale: String,
        additionalContext: String,
    ): String = DEFAULT_COMPRESS_PROMPT
        .replace("{content}", content)
        .replace("{target_tokens}", targetTokens.toString())
        .replace("{locale}", locale)
        .replace("{additional_context}", additionalContext)
}

/** 默认：不自动压缩，提示模板与现状一致。 */
object DefaultCompactPolicy : CompactPolicy
