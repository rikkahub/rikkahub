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
import org.koin.core.component.KoinComponent

object RegexOutputTransformer : OutputMessageTransformer, KoinComponent {
    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return applyAssistantOutputRegexes(
            ctx = ctx,
            messages = messages,
            phases = listOf(
                AssistantRegexApplyPhase.ACTUAL_MESSAGE,
                AssistantRegexApplyPhase.VISUAL_ONLY,
            ),
        )
    }

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return applyAssistantOutputRegexes(
            ctx = ctx,
            messages = messages,
            phases = listOf(AssistantRegexApplyPhase.ACTUAL_MESSAGE),
        )
    }

    private fun applyAssistantOutputRegexes(
        ctx: TransformerContext,
        messages: List<UIMessage>,
        phases: List<AssistantRegexApplyPhase>,
    ): List<UIMessage> {
        val assistant = ctx.assistant
        if (ctx.settings.effectiveRegexes(assistant).isEmpty()) return messages
        val depthMap = messages.chatMessageDepthFromEndMap()
        return messages.mapIndexed { index, message ->
            val scope = when (message.role) {
                MessageRole.ASSISTANT -> AssistantAffectScope.ASSISTANT
                else -> return@mapIndexed message // Skip non-assistant messages
            }
            val messageDepth = depthMap[index]
            message.copy(
                parts = message.parts.map { part ->
                    when (part) {
                        is UIMessagePart.Text -> {
                            part.copy(
                                text = phases.fold(part.text) { acc, phase ->
                                    acc.replaceRegexes(
                                        assistant = assistant,
                                        settings = ctx.settings,
                                        scope = scope,
                                        phase = phase,
                                        messageDepthFromEnd = messageDepth,
                                        placement = AssistantRegexPlacement.AI_OUTPUT,
                                    )
                                }
                            )
                        }

                        is UIMessagePart.Reasoning -> {
                            part.copy(
                                reasoning = phases.fold(part.reasoning) { acc, phase ->
                                    acc.replaceRegexes(
                                        assistant = assistant,
                                        settings = ctx.settings,
                                        scope = scope,
                                        phase = phase,
                                        messageDepthFromEnd = messageDepth,
                                        placement = AssistantRegexPlacement.REASONING,
                                    )
                                }
                            )
                        }

                        else -> part
                    }
                }
            )
        }
    }
}
