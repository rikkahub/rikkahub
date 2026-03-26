package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegexApplyPhase
import me.rerere.rikkahub.data.model.AssistantRegexPlacement
import me.rerere.rikkahub.data.model.chatMessageDepthFromEndMap
import me.rerere.rikkahub.data.model.effectiveRegexes
import me.rerere.rikkahub.data.model.replaceRegexes

object RegexPromptOnlyTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val assistant = ctx.assistant
        if (ctx.settings.effectiveRegexes(assistant).isEmpty()) return messages

        val depthMap = messages.chatMessageDepthFromEndMap()

        return messages.mapIndexed { index, message ->
            val scope = when (message.role) {
                MessageRole.SYSTEM -> AssistantAffectScope.SYSTEM
                MessageRole.USER -> AssistantAffectScope.USER
                MessageRole.ASSISTANT -> AssistantAffectScope.ASSISTANT
                else -> return@mapIndexed message
            }
            val placement = when (message.role) {
                MessageRole.USER -> AssistantRegexPlacement.USER_INPUT
                MessageRole.ASSISTANT -> AssistantRegexPlacement.AI_OUTPUT
                else -> null
            }

            val messageDepth = depthMap[index]
            message.copy(
                parts = message.parts.map { part ->
                    when (part) {
                        is UIMessagePart.Text -> {
                            part.copy(
                                text = part.text.replaceRegexes(
                                    assistant = assistant,
                                    settings = ctx.settings,
                                    scope = scope,
                                    phase = AssistantRegexApplyPhase.PROMPT_ONLY,
                                    messageDepthFromEnd = messageDepth,
                                    placement = placement,
                                )
                            )
                        }

                        is UIMessagePart.Reasoning -> {
                            part.copy(
                                reasoning = part.reasoning.replaceRegexes(
                                    assistant = assistant,
                                    settings = ctx.settings,
                                    scope = scope,
                                    phase = AssistantRegexApplyPhase.PROMPT_ONLY,
                                    messageDepthFromEnd = messageDepth,
                                    placement = AssistantRegexPlacement.REASONING,
                                )
                            )
                        }

                        else -> part
                    }
                }
            )
        }
    }
}
