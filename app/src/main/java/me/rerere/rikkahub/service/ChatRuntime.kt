package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

enum class NetworkFailureKind {
    UnknownHost,
    Timeout,
    Unreachable,
    Disconnected,
}

sealed interface GenerationProgress {
    data object RecognizingImages : GenerationProgress

    data class NetworkRetry(
        val kind: NetworkFailureKind,
        val attempt: Int,
        val maxAttempts: Int,
    ) : GenerationProgress
}

enum class ChatFailureCode {
    InvalidRequest,
    NotFound,
    Conflict,
    Configuration,
    Network,
    Tool,
    StepLimit,
    PostProcessing,
    Internal,
}

data class ChatFailure(
    val id: Uuid = Uuid.random(),
    val code: ChatFailureCode,
    val message: String,
    val retryable: Boolean = false,
    val conversationId: Uuid? = null,
    val generationId: Uuid? = null,
)

class ChatCommandException(
    val failure: ChatFailure,
    cause: Throwable? = null,
) : RuntimeException(failure.message, cause)

sealed interface GenerationState {
    data object Idle : GenerationState

    data class Queued(
        val generationId: Uuid,
    ) : GenerationState

    data class Running(
        val generationId: Uuid,
        val progress: GenerationProgress? = null,
    ) : GenerationState

    data class AwaitingApproval(
        val generationId: Uuid,
        val toolCallIds: List<String>,
    ) : GenerationState

    data class Completed(
        val generationId: Uuid,
    ) : GenerationState

    data class Failed(
        val generationId: Uuid,
        val failure: ChatFailure,
    ) : GenerationState

    data class Cancelled(
        val generationId: Uuid,
    ) : GenerationState
}

val GenerationState.generationIdOrNull: Uuid?
    get() = when (this) {
        GenerationState.Idle -> null
        is GenerationState.Queued -> generationId
        is GenerationState.Running -> generationId
        is GenerationState.AwaitingApproval -> generationId
        is GenerationState.Completed -> generationId
        is GenerationState.Failed -> generationId
        is GenerationState.Cancelled -> generationId
    }

val GenerationState.isBusy: Boolean
    get() = this is GenerationState.Queued ||
        this is GenerationState.Running ||
        this is GenerationState.AwaitingApproval

val GenerationState.isExecuting: Boolean
    get() = this is GenerationState.Queued || this is GenerationState.Running

data class ConversationRuntimeSnapshot(
    val conversation: Conversation,
    val revision: Long = 0,
    val generation: GenerationState = GenerationState.Idle,
)

data class GenerationHandle(
    val conversationId: Uuid,
    val generationId: Uuid,
    val state: GenerationState,
)
