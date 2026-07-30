package me.rerere.rikkahub.data.ai.agent.routing

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.data.model.AgentRunConfigSnapshot
import me.rerere.rikkahub.utils.JsonInstant

enum class AgentRoutingSnapshotError {
    MALFORMED_CONFIG,
    CONFIG_TOO_LARGE,
    UNSUPPORTED_VERSION,
    INVALID_ROUTING,
}

sealed interface AgentRoutingSnapshotDecodeResult {
    data class Auto(
        val config: AgentRunConfigSnapshot,
        val routing: AgentRoutingSnapshot,
    ) : AgentRoutingSnapshotDecodeResult

    data class Legacy(val config: AgentRunConfigSnapshot) : AgentRoutingSnapshotDecodeResult

    data class Invalid(val error: AgentRoutingSnapshotError) : AgentRoutingSnapshotDecodeResult
}

/** Strict execution decoder: malformed AUTO snapshots never fall back to legacy mode. */
object AgentRoutingSnapshotCodec {
    const val MAX_CONFIG_BYTES = 64 * 1024

    fun encode(config: AgentRunConfigSnapshot): String {
        require(
            config.routing != null || config.toolPolicyVersion != AgentRoutingSnapshot.CURRENT_VERSION,
        ) { "AUTO agent run config requires a routing snapshot" }
        val normalizedConfig = config.routing?.let { routing ->
            require(routing.version == AgentRoutingSnapshot.CURRENT_VERSION) {
                "Unsupported agent routing snapshot version"
            }
            require(routing.isValid()) { "Agent routing snapshot is invalid" }
            config.copy(routing = routing.normalized())
        } ?: config
        val encoded = JsonInstant.encodeToString(normalizedConfig)
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_CONFIG_BYTES) {
            "Agent run JSON field exceeds $MAX_CONFIG_BYTES bytes"
        }
        return encoded
    }

    fun decode(encoded: String): AgentRoutingSnapshotDecodeResult {
        if (encoded.toByteArray(Charsets.UTF_8).size > MAX_CONFIG_BYTES) {
            return AgentRoutingSnapshotDecodeResult.Invalid(AgentRoutingSnapshotError.CONFIG_TOO_LARGE)
        }
        val root = runCatching { JsonInstant.parseToJsonElement(encoded) }.getOrNull() as? JsonObject
            ?: return AgentRoutingSnapshotDecodeResult.Invalid(AgentRoutingSnapshotError.MALFORMED_CONFIG)
        val routingElement = root["routing"]
        val declaresAutoPolicy = (root["toolPolicyVersion"] as? JsonPrimitive)?.contentOrNull ==
            AgentRoutingSnapshot.CURRENT_VERSION
        val config = runCatching { JsonInstant.decodeFromString<AgentRunConfigSnapshot>(encoded) }.getOrNull()
            ?: return AgentRoutingSnapshotDecodeResult.Invalid(
                if (routingElement == null || routingElement is JsonNull) {
                    AgentRoutingSnapshotError.MALFORMED_CONFIG
                } else {
                    AgentRoutingSnapshotError.INVALID_ROUTING
                },
            )
        if (routingElement == null || routingElement is JsonNull) {
            return if (declaresAutoPolicy) {
                AgentRoutingSnapshotDecodeResult.Invalid(AgentRoutingSnapshotError.INVALID_ROUTING)
            } else {
                AgentRoutingSnapshotDecodeResult.Legacy(config)
            }
        }
        val routing = config.routing
            ?: return AgentRoutingSnapshotDecodeResult.Invalid(AgentRoutingSnapshotError.INVALID_ROUTING)
        if (routing.version != AgentRoutingSnapshot.CURRENT_VERSION) {
            return AgentRoutingSnapshotDecodeResult.Invalid(AgentRoutingSnapshotError.UNSUPPORTED_VERSION)
        }
        if (!routing.isValid()) {
            return AgentRoutingSnapshotDecodeResult.Invalid(AgentRoutingSnapshotError.INVALID_ROUTING)
        }
        val normalized = runCatching { routing.normalized() }.getOrNull()
            ?: return AgentRoutingSnapshotDecodeResult.Invalid(AgentRoutingSnapshotError.INVALID_ROUTING)
        return AgentRoutingSnapshotDecodeResult.Auto(config.copy(routing = normalized), normalized)
    }
}
