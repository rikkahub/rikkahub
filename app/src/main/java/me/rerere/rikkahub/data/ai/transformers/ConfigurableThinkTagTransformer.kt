package me.rerere.rikkahub.data.ai.transformers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.ThinkingModeConfig
import kotlin.time.Clock

/**
 * 可配置的思考标签转换器
 * 
 * 支持自定义开始/结束标签，替代默认的 ThinkTagTransformer
 */
object ConfigurableThinkTagTransformer : OutputMessageTransformer {
    
    /**
     * 根据配置创建匹配正则表达式
     */
    private fun createThinkingRegex(config: ThinkingModeConfig): Regex {
        val startTag = Regex.escape(config.startTag)
        val endTag = Regex.escape(config.endTag)
        return Regex("$startTag([\\s\\S]*?)(?:$endTag|$)", RegexOption.DOT_MATCHES_ALL)
    }
    
    /**
     * 默认的思考标签正则表达式 (兼容 <think> 标签)
     */
    private val DEFAULT_THINKING_REGEX = Regex("<think>([\\s\\S]*?)(?:</think>|$)", RegexOption.DOT_MATCHES_ALL)
    
    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val thinkingConfig = ctx.assistant.thinkingMode
        
        // 选择使用的正则表达式
        val thinkingRegex = if (thinkingConfig.enabled) {
            createThinkingRegex(thinkingConfig)
        } else {
            DEFAULT_THINKING_REGEX
        }
        
        return messages.map { message ->
            if (message.role == MessageRole.ASSISTANT && message.hasPart<UIMessagePart.Text>()) {
                message.copy(
                    parts = message.parts.flatMap { part ->
                        if (part is UIMessagePart.Text && thinkingRegex.containsMatchIn(part.text)) {
                            // 提取思考内容，并替换为空字符串
                            val stripped = part.text.replace(thinkingRegex, "")
                            val reasoning =
                                thinkingRegex.find(part.text)?.groupValues?.getOrNull(1)?.trim()
                                    ?: ""
                            val now = Clock.System.now()
                            
                            // 构建 metadata，包含显示名称
                            val metadata: JsonObject? = if (thinkingConfig.enabled) {
                                buildJsonObject {
                                    put("displayName", JsonPrimitive(thinkingConfig.displayName))
                                }
                            } else {
                                null
                            }
                            
                            listOf(
                                UIMessagePart.Reasoning(
                                    reasoning = reasoning,
                                    finishedAt = now, // 这是 visual 的，没有思考时间，finishedAt = createdAt
                                    createdAt = now,
                                    metadata = metadata,
                                ),
                                part.copy(text = stripped),
                            )
                        } else {
                            listOf(part)
                        }
                    }
                )
            } else {
                message
            }
        }
    }
}
