package me.rerere.rikkahub.web.a2a

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
data class JsonRpcSuccess(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val result: JsonElement,
)

@Serializable
data class JsonRpcFailure(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val error: JsonRpcError,
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

@Serializable
data class AgentCard(
    val name: String,
    val description: String,
    val url: String,
    val version: String = "1.0.0",
    val capabilities: AgentCapabilities,
    val defaultInputModes: List<String> = listOf("text/plain"),
    val defaultOutputModes: List<String> = listOf("text/plain"),
    val skills: List<AgentSkill>,
    val securitySchemes: Map<String, AgentSecurityScheme> = emptyMap(),
    val security: List<Map<String, List<String>>> = emptyList(),
)

@Serializable
data class AgentCapabilities(
    val streaming: Boolean = true,
    val pushNotifications: Boolean = false,
)

@Serializable
data class AgentSkill(
    val id: String,
    val name: String,
    val description: String,
    val inputModes: List<String> = listOf("text/plain"),
    val outputModes: List<String> = listOf("text/plain"),
)

@Serializable
data class AgentSecurityScheme(
    val type: String = "http",
    val scheme: String = "bearer",
    val bearerFormat: String = "static",
)

@Serializable
enum class A2aTaskState {
    @SerialName("submitted")
    SUBMITTED,

    @SerialName("working")
    WORKING,

    @SerialName("input-required")
    INPUT_REQUIRED,

    @SerialName("completed")
    COMPLETED,

    @SerialName("canceled")
    CANCELED,

    @SerialName("failed")
    FAILED,
}

@Serializable
data class A2aTaskStatus(
    val state: A2aTaskState,
    val message: A2aMessage? = null,
    val input: List<A2aInputRequest> = emptyList(),
    val timestamp: Long,
)

@Serializable
data class A2aTask(
    val id: String,
    val contextId: String,
    val status: A2aTaskStatus,
    val artifacts: List<A2aArtifact> = emptyList(),
    val metadata: JsonObject? = null,
)

@Serializable
data class A2aArtifact(
    val artifactId: String,
    val name: String? = null,
    val parts: List<A2aPart>,
    val append: Boolean = false,
    val lastChunk: Boolean = false,
    val metadata: JsonObject? = null,
)

@Serializable
data class A2aMessage(
    val messageId: String,
    val role: A2aRole,
    val parts: List<A2aPart>,
    val taskId: String? = null,
    val contextId: String? = null,
    val metadata: JsonObject? = null,
)

@Serializable
enum class A2aRole {
    @SerialName("user")
    USER,

    @SerialName("agent")
    AGENT,
}

@Serializable(with = A2aPartSerializer::class)
sealed class A2aPart {
    abstract val metadata: JsonObject?

    @Serializable
    @SerialName("text")
    data class TextPart(val text: String, override val metadata: JsonObject? = null) : A2aPart()
}

@Serializable
data class MessageSendParams(
    val skillId: String? = null,
    val contextId: String? = null,
    val taskId: String? = null,
    val message: A2aMessage,
    val approval: A2aToolApproval? = null,
)

@Serializable
data class MessageSendResult(val task: A2aTask)

@Serializable
data class TasksGetParams(val id: String)

@Serializable
data class TasksGetResult(val task: A2aTask)

@Serializable
data class TasksCancelParams(val id: String)

@Serializable
data class TasksCancelResult(val task: A2aTask)

@Serializable
data class A2aToolApproval(
    val toolCallId: String,
    val approved: Boolean = true,
    val reason: String = "",
    val answer: String? = null,
)

@Serializable
data class A2aInputRequest(
    val toolCallId: String,
    val toolName: String,
    val prompt: String? = null,
)

@Serializable
sealed class A2aStreamEvent {
    abstract val taskId: String
    abstract val contextId: String

    @Serializable
    @SerialName("task-status-update")
    data class TaskStatusUpdateEvent(
        override val taskId: String,
        override val contextId: String,
        val status: A2aTaskStatus,
        val final: Boolean = false,
    ) : A2aStreamEvent()

    @Serializable
    @SerialName("task-artifact-update")
    data class TaskArtifactUpdateEvent(
        override val taskId: String,
        override val contextId: String,
        val artifact: A2aArtifact,
        val append: Boolean,
    ) : A2aStreamEvent()
}
