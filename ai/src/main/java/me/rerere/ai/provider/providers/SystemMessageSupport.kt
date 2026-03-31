package me.rerere.ai.provider.providers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

internal data class LeadingSystemMessages(
    val systemMessages: List<UIMessage>,
    val remainingMessages: List<UIMessage>,
)

internal fun splitLeadingSystemMessages(messages: List<UIMessage>): LeadingSystemMessages {
    val leadingCount = messages.takeWhile { it.role == MessageRole.SYSTEM }.size
    return LeadingSystemMessages(
        systemMessages = messages.take(leadingCount),
        remainingMessages = messages.drop(leadingCount),
    )
}

internal fun demoteSystemMessages(messages: List<UIMessage>): List<UIMessage> {
    return messages.map { message ->
        if (message.role == MessageRole.SYSTEM) {
            message.copy(role = MessageRole.USER)
        } else {
            message
        }
    }
}

internal fun collectLeadingSystemTextParts(messages: List<UIMessage>): List<UIMessagePart.Text> {
    return splitLeadingSystemMessages(messages)
        .systemMessages
        .flatMap { message -> message.parts.filterIsInstance<UIMessagePart.Text>() }
}
