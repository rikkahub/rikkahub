package me.rerere.rikkahub.subagent

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Phase 11 — sub-agent run record. Lives in [SubAgentRegistry]'s in-memory map for the
 * lifetime of the app process. Persistence intentionally out of scope for v1: spec says
 * "Background sub-agents survive only as long as the parent process is alive" and
 * documents that user-visibly. WorkManager-backed persistence is a v2 concern.
 *
 * The run is FROZEN once it reaches a terminal status. Mutations are done by replacing
 * the entry in the registry's StateFlow rather than mutating in place.
 */
@Serializable
data class SubAgentRun(
    val id: String,
    val parentChatId: String?,         // the parent assistant chat that dispatched this — used for /stop cascade
    val parentAssistantId: String,
    val label: String,
    val task: String,
    val modelId: String?,              // null = inherited from parent
    val tools: List<String>?,          // null = inherited from parent
    val runInBackground: Boolean,
    val timeoutSeconds: Int,
    val maxTrips: Int,
    val status: SubAgentStatus,
    val result: String? = null,
    val error: String? = null,
    val startedAtMs: Long,
    val finishedAtMs: Long? = null,
    val tokensIn: Long = 0,
    val tokensOut: Long = 0,
    val tripCount: Int = 0,
)

@Serializable
enum class SubAgentStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED,
}

object SubAgentDefaults {
    const val DEFAULT_TIMEOUT_SECONDS = 300
    const val MAX_TIMEOUT_SECONDS = 1800
    const val DEFAULT_MAX_TRIPS = 12
    const val MAX_MAX_TRIPS = 30
    const val MAX_LABEL_LENGTH = 60
    const val GLOBAL_CONCURRENCY_CAP = 16
    const val MIN_PER_ASSISTANT_CAP = 1
    const val MAX_PER_ASSISTANT_CAP = 8
    const val REGISTRY_LRU_CAP = 50

    /** Default system prompt used when the assistant's per-sub-agent prompt is empty. */
    val DEFAULT_SYSTEM_PROMPT = """
        You are a focused sub-agent dispatched by a parent assistant to complete a single
        task and return a concise summary.

        Rules:
        - Stay tightly scoped to the task you were given. Do not expand scope.
        - Use tools to gather facts before answering when accuracy matters.
        - Return a clear, structured final summary as your last message — that summary is
          what the parent will see. Aim for 100-500 words unless the task asks otherwise.
        - If the task is impossible, return a single short paragraph explaining why.
        - Do not ask the parent for clarification — make the best judgment call you can
          and proceed.
    """.trimIndent()
}

@Serializable
data class SubAgentRequest(
    val task: String,
    val modelId: String? = null,
    val systemPrompt: String? = null,
    val tools: List<String>? = null,
    val runInBackground: Boolean = false,
    val timeoutSeconds: Int = SubAgentDefaults.DEFAULT_TIMEOUT_SECONDS,
    val maxTrips: Int = SubAgentDefaults.DEFAULT_MAX_TRIPS,
    val label: String? = null,
)

object SubAgentRequestValidator {

    sealed class Result {
        data class Ok(val request: SubAgentRequest) : Result()
        data class Reject(val error: String, val detail: String) : Result()
    }

    fun validate(request: SubAgentRequest): Result {
        val task = request.task.trim()
        if (task.isEmpty()) {
            return Result.Reject("invalid_task", "task is required and may not be blank")
        }
        if (request.timeoutSeconds < 1) {
            return Result.Reject(
                "invalid_timeout",
                "timeout_seconds must be at least 1; got ${request.timeoutSeconds}"
            )
        }
        if (request.timeoutSeconds > SubAgentDefaults.MAX_TIMEOUT_SECONDS) {
            return Result.Reject(
                "invalid_timeout",
                "timeout_seconds exceeds max ${SubAgentDefaults.MAX_TIMEOUT_SECONDS}; got ${request.timeoutSeconds}"
            )
        }
        if (request.maxTrips < 1) {
            return Result.Reject(
                "invalid_max_trips",
                "max_trips must be at least 1; got ${request.maxTrips}"
            )
        }
        if (request.maxTrips > SubAgentDefaults.MAX_MAX_TRIPS) {
            return Result.Reject(
                "invalid_max_trips",
                "max_trips exceeds max ${SubAgentDefaults.MAX_MAX_TRIPS}; got ${request.maxTrips}"
            )
        }
        request.label?.let {
            if (it.length > SubAgentDefaults.MAX_LABEL_LENGTH) {
                return Result.Reject(
                    "invalid_label",
                    "label exceeds ${SubAgentDefaults.MAX_LABEL_LENGTH} chars; got ${it.length}"
                )
            }
        }
        return Result.Ok(request.copy(task = task))
    }
}
