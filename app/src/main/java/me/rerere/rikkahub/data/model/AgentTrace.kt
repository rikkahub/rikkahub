package me.rerere.rikkahub.data.model

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Content-free event vocabulary used for diagnostics and deterministic replay. */
@Serializable
enum class AgentTraceEventType {
    RUN_STARTED,
    PREFLIGHT,
    CONTEXT_PLANNED,
    MODEL_CALL_STARTED,
    MODEL_CALL_FINISHED,
    POLICY_DECISION,
    APPROVAL,
    TOOL_STARTED,
    TOOL_FINISHED,
    CHILD_RUN,
    CHECKPOINT,
    TRACE_TRUNCATED,
    RUN_FINISHED,
}

@Serializable
enum class AgentTraceStatus {
    STARTED,
    FINISHED,
    ALLOWED,
    ASK,
    DENIED,
    APPROVED,
    REJECTED,
    SUCCEEDED,
    FAILED,
    BLOCKED,
    CANCELLED,
    INTERRUPTED,
    TRUNCATED,
}

@Serializable
enum class AgentTraceErrorCategory {
    NONE,
    PREFLIGHT,
    CONTEXT_BUDGET,
    POLICY,
    APPROVAL,
    TOOL,
    PROVIDER,
    RUNTIME,
    LIFECYCLE,
    CHILD_RUN,
    UNKNOWN,
}

/**
 * Fixed, allow-listed attribute schema. Identifiers are SHA-256 values; no free-form text,
 * prompt, message, tool argument/result, header, credential, or path is representable here.
 */
@Serializable
data class AgentTraceAttributes(
    val schemaVersion: Int = 1,
    val modelIdHash: String? = null,
    val toolNameHash: String? = null,
    val toolExecutionIdHash: String? = null,
    val childRunIdHash: String? = null,
    val inputSha256: String? = null,
    val outputSha256: String? = null,
    val artifactSha256: String? = null,
    val policyCodeHash: String? = null,
    val contextInputTokens: Int? = null,
    val contextWindowTokens: Int? = null,
    val outputReserveTokens: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val byteCount: Long? = null,
    val queuePeak: Int? = null,
    val stepIndex: Int? = null,
    val childCount: Int? = null,
) {
    fun validated(): AgentTraceAttributes {
        require(schemaVersion == 1)
        listOf(modelIdHash, toolNameHash, toolExecutionIdHash, childRunIdHash, inputSha256, outputSha256, artifactSha256, policyCodeHash)
            .filterNotNull().forEach { require(HASH.matches(it)) }
        listOf(contextInputTokens, contextWindowTokens, outputReserveTokens, inputTokens, outputTokens, queuePeak, stepIndex, childCount)
            .filterNotNull().forEach { require(it in 0..MAX_COUNT) }
        require(byteCount == null || byteCount in 0..MAX_BYTES)
        return this
    }

    companion object {
        private val HASH = Regex("[0-9a-f]{64}")
        private const val MAX_COUNT = 100_000_000
        private const val MAX_BYTES = 1L shl 40
    }
}

/** Converts untrusted runtime identifiers to bounded opaque values before they reach persistence. */
object AgentTraceRedactor {
    private val strictJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun hash(value: String?): String? = value?.takeIf(String::isNotBlank)?.let { value ->
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** Rejects future unreviewed telemetry fields rather than silently dropping them. */
    fun encodeAttributes(attributes: AgentTraceAttributes): String {
        attributes.validated()
        return strictJson.encodeToString(AgentTraceAttributes.serializer(), attributes)
    }

    fun decodeAttributes(encoded: String): AgentTraceAttributes {
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_ATTRIBUTES_BYTES) { "Trace attributes exceed the byte limit" }
        val element = strictJson.parseToJsonElement(encoded)
        val attributes = element as? JsonObject ?: throw IllegalArgumentException("Trace attributes must be an object")
        require(attributes.keys.all(TRACE_ATTRIBUTE_KEYS::contains)) { "Trace attributes contain an unknown key" }
        return strictJson.decodeFromJsonElement(AgentTraceAttributes.serializer(), attributes).validated()
    }

    fun errorCategory(value: String?): AgentTraceErrorCategory = when (value?.lowercase()) {
        null -> AgentTraceErrorCategory.NONE
        "preflight", "provider_capability" -> AgentTraceErrorCategory.PREFLIGHT
        "context_budget" -> AgentTraceErrorCategory.CONTEXT_BUDGET
        "policy", "capability_policy" -> AgentTraceErrorCategory.POLICY
        "approval" -> AgentTraceErrorCategory.APPROVAL
        "tool" -> AgentTraceErrorCategory.TOOL
        "provider" -> AgentTraceErrorCategory.PROVIDER
        "runtime" -> AgentTraceErrorCategory.RUNTIME
        "lifecycle" -> AgentTraceErrorCategory.LIFECYCLE
        "controlled_child" -> AgentTraceErrorCategory.CHILD_RUN
        else -> AgentTraceErrorCategory.UNKNOWN
    }

    private const val MAX_ATTRIBUTES_BYTES = 2 * 1024
    private val TRACE_ATTRIBUTE_KEYS = setOf(
        "schemaVersion", "modelIdHash", "toolNameHash", "toolExecutionIdHash", "childRunIdHash", "inputSha256", "outputSha256",
        "artifactSha256", "policyCodeHash", "contextInputTokens", "contextWindowTokens", "outputReserveTokens",
        "inputTokens", "outputTokens", "byteCount", "queuePeak", "stepIndex", "childCount",
    )
}
