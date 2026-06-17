package me.rerere.rikkahub.web.a2a

internal const val MAX_A2A_RPC_BODY_BYTES = 1_048_576
internal const val MAX_A2A_MESSAGE_PARTS = 32
internal const val MAX_A2A_TEXT_PART_CHARS = 262_144
internal const val MAX_A2A_ID_CHARS = 128
internal const val MAX_A2A_APPROVAL_REASON_CHARS = 4_096
internal const val MAX_A2A_APPROVAL_ANSWER_CHARS = 262_144

internal class A2aRpcRequestBodyTooLargeException(message: String) : IllegalArgumentException(message)

internal fun validateA2aMessageBounds(params: MessageSendParams) {
    params.contextId?.let { validateA2aIdLength("contextId", it) }
    params.taskId?.let { validateA2aIdLength("taskId", it) }
    params.skillId?.let { validateA2aIdLength("skillId", it) }

    validateA2aIdLength("message.messageId", params.message.messageId)
    params.message.taskId?.let { validateA2aIdLength("message.taskId", it) }
    params.message.contextId?.let { validateA2aIdLength("message.contextId", it) }

    if (params.message.parts.size > MAX_A2A_MESSAGE_PARTS) {
        throw IllegalArgumentException("message.parts too many: ${params.message.parts.size}")
    }

    params.message.parts.forEachIndexed { index, part ->
        if (part is A2aPart.TextPart) {
            validateTextLength("message.parts[$index].text", part.text)
        }
    }

    params.approval?.let { approval ->
        validateA2aIdLength("approval.toolCallId", approval.toolCallId)
        validateTextLength("approval.reason", approval.reason, MAX_A2A_APPROVAL_REASON_CHARS)
        approval.answer?.let {
            validateTextLength("approval.answer", it, MAX_A2A_APPROVAL_ANSWER_CHARS)
        }
    }
}

internal fun validateA2aTasksGetParams(params: TasksGetParams) {
    validateA2aIdLength("id", params.id)
}

internal fun validateA2aTasksCancelParams(params: TasksCancelParams) {
    validateA2aIdLength("id", params.id)
}

internal fun validateA2aRpcBody(bytesRead: Int) {
    if (bytesRead > MAX_A2A_RPC_BODY_BYTES) {
        throw A2aRpcRequestBodyTooLargeException("request body too large")
    }
}

private fun validateA2aIdLength(name: String, value: String) {
    validateTextLength(name, value, MAX_A2A_ID_CHARS)
}

private fun validateTextLength(name: String, value: String, max: Int = MAX_A2A_TEXT_PART_CHARS) {
    if (value.length > max) {
        throw IllegalArgumentException("$name too long: ${value.length}")
    }
}
