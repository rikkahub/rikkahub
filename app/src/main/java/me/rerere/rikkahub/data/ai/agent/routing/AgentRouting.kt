package me.rerere.rikkahub.data.ai.agent.routing

import kotlinx.serialization.Serializable

private val SAFE_REASON_CODE = Regex("[a-z0-9_]{1,64}")
private val SAFE_PERMISSION_DIGEST = Regex("[A-Za-z0-9:_-]{1,256}")
private val SAFE_EXECUTION_CONTEXT_DIGEST = Regex("sha256:[0-9a-f]{64}")

@Serializable
enum class AgentIntent {
    ANSWER,
    EXPLORE,
    EXECUTE,
    CLARIFY,
}

@Serializable
enum class InputTrust {
    USER_DIRECT,
    DERIVED_UNTRUSTED,
}

/** Immutable, content-free policy facts selected once for an agent run. */
@Serializable
data class AgentRoutingSnapshot(
    val version: String = CURRENT_VERSION,
    val intent: AgentIntent,
    val inputTrust: InputTrust,
    val reasonCode: String,
    val resolvedToolNames: List<String>,
    val permissionDigest: String,
    /** Digest of execution-affecting settings only. Prompt, header and tool bodies are excluded. */
    val executionContextDigest: String,
    val providerIdleTimeoutMillis: Long,
    val toolTimeoutMillis: Long,
    val runTimeoutMillis: Long,
) {
    fun normalized(): AgentRoutingSnapshot = copy(
        resolvedToolNames = canonicalToolNames(resolvedToolNames),
    )

    internal fun isValid(): Boolean =
        SAFE_REASON_CODE.matches(reasonCode) &&
            SAFE_PERMISSION_DIGEST.matches(permissionDigest) &&
            SAFE_EXECUTION_CONTEXT_DIGEST.matches(executionContextDigest) &&
            (intent != AgentIntent.EXECUTE || inputTrust == InputTrust.USER_DIRECT) &&
            providerIdleTimeoutMillis > 0 &&
            toolTimeoutMillis > 0 &&
            runTimeoutMillis > 0 &&
            resolvedToolNames.all(String::isNotBlank)

    companion object {
        const val CURRENT_VERSION = "auto-intent-v1"

        fun create(
            intent: AgentIntent,
            inputTrust: InputTrust,
            reasonCode: String,
            resolvedToolNames: Iterable<String>,
            permissionDigest: String,
            executionContextDigest: String,
            providerIdleTimeoutMillis: Long,
            toolTimeoutMillis: Long,
            runTimeoutMillis: Long,
        ): AgentRoutingSnapshot = AgentRoutingSnapshot(
            intent = intent,
            inputTrust = inputTrust,
            reasonCode = reasonCode,
            resolvedToolNames = canonicalToolNames(resolvedToolNames),
            permissionDigest = permissionDigest,
            executionContextDigest = executionContextDigest,
            providerIdleTimeoutMillis = providerIdleTimeoutMillis,
            toolTimeoutMillis = toolTimeoutMillis,
            runTimeoutMillis = runTimeoutMillis,
        ).also { require(it.isValid()) { "Agent routing snapshot is invalid" } }

        fun canonicalToolNames(toolNames: Iterable<String>): List<String> {
            val names = toolNames.toList()
            require(names.all(String::isNotBlank)) { "Resolved tool names cannot be blank" }
            return names.distinct().sorted()
        }
    }
}
