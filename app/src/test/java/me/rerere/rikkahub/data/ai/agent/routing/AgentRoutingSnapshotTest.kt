package me.rerere.rikkahub.data.ai.agent.routing

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.model.AgentRunConfigSnapshot
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentRoutingSnapshotTest {
    @Test
    fun `auto snapshot round trips with canonical tool names`() {
        val routing = routingSnapshot(
            resolvedToolNames = listOf("workspace_write_file", "artifact_read", "workspace_write_file"),
        )

        val encoded = AgentRoutingSnapshotCodec.encode(
            AgentRunConfigSnapshot(
                runtimeVersion = "agent-loop-v3",
                modelId = "model",
                routing = routing,
            ),
        )
        val decoded = AgentRoutingSnapshotCodec.decode(encoded)

        assertTrue(decoded is AgentRoutingSnapshotDecodeResult.Auto)
        decoded as AgentRoutingSnapshotDecodeResult.Auto
        assertEquals("model", decoded.config.modelId)
        assertEquals(listOf("artifact_read", "workspace_write_file"), decoded.routing.resolvedToolNames)
        assertEquals(45_000, decoded.routing.providerIdleTimeoutMillis)
        assertEquals(EXECUTION_CONTEXT_DIGEST, decoded.routing.executionContextDigest)
        assertEquals(routing, decoded.routing)
    }

    @Test
    fun `snapshot without routing is classified as legacy`() {
        val decoded = AgentRoutingSnapshotCodec.decode(
            """{"runtimeVersion":"agent-loop-v2","modelId":"legacy-model","agentMode":"PLAN"}""",
        )

        assertTrue(decoded is AgentRoutingSnapshotDecodeResult.Legacy)
        decoded as AgentRoutingSnapshotDecodeResult.Legacy
        assertEquals("legacy-model", decoded.config.modelId)
        assertEquals("PLAN", decoded.config.agentMode)
    }

    @Test
    fun `only explicit historical modes are classified as legacy`() {
        listOf("CHAT", "PLAN", "AGENT").forEach { mode ->
            assertTrue(
                AgentRoutingSnapshotCodec.decode("""{"agentMode":"$mode"}""") is
                    AgentRoutingSnapshotDecodeResult.Legacy,
            )
        }

        listOf("{}", """{"agentMode":null}""", """{"agentMode":""}""", """{"agentMode":"AUTO"}""").forEach {
            assertInvalid(
                AgentRoutingSnapshotCodec.decode(it),
                AgentRoutingSnapshotError.INVALID_LEGACY_MODE,
            )
        }
    }

    @Test
    fun `auto policy marker without routing is invalid and never legacy`() {
        val explicitNull = JsonInstant.encodeToString(
            AgentRunConfigSnapshot(
                toolPolicyVersion = AgentRoutingSnapshot.CURRENT_VERSION,
                routing = null,
            ),
        )
        val missing = """{"toolPolicyVersion":"auto-intent-v1"}"""

        listOf(explicitNull, missing).forEach { encoded ->
            assertInvalid(
                AgentRoutingSnapshotCodec.decode(encoded),
                AgentRoutingSnapshotError.INVALID_ROUTING,
            )
        }
    }

    @Test
    fun `malformed snapshot is invalid and never legacy`() {
        val decoded = AgentRoutingSnapshotCodec.decode("{not-json")

        assertInvalid(decoded, AgentRoutingSnapshotError.MALFORMED_CONFIG)
    }

    @Test
    fun `unknown routing version is invalid and never legacy`() {
        val encoded = JsonInstant.encodeToString(
            AgentRunConfigSnapshot(routing = routingSnapshot().copy(version = "auto-intent-v999")),
        )

        assertInvalid(
            AgentRoutingSnapshotCodec.decode(encoded),
            AgentRoutingSnapshotError.UNSUPPORTED_VERSION,
        )
    }

    @Test
    fun `empty permission digest is invalid and never legacy`() {
        val encoded = JsonInstant.encodeToString(
            AgentRunConfigSnapshot(routing = routingSnapshot().copy(permissionDigest = "")),
        )

        assertInvalid(
            AgentRoutingSnapshotCodec.decode(encoded),
            AgentRoutingSnapshotError.INVALID_ROUTING,
        )
    }

    @Test
    fun `execution context digest is required and accepts only canonical sha256`() {
        val config = AgentRunConfigSnapshot(routing = routingSnapshot())
        val root = JsonInstant.parseToJsonElement(JsonInstant.encodeToString(config)).jsonObject
        val routing = root.getValue("routing").jsonObject
        val withoutDigest = JsonObject(
            root.toMutableMap().apply {
                put("routing", JsonObject(routing.toMutableMap().apply { remove("executionContextDigest") }))
            },
        ).toString()
        val rawContext = JsonInstant.encodeToString(
            config.copy(routing = config.routing?.copy(executionContextDigest = "system prompt body")),
        )
        val uppercaseHash = JsonInstant.encodeToString(
            config.copy(routing = config.routing?.copy(executionContextDigest = EXECUTION_CONTEXT_DIGEST.uppercase())),
        )

        listOf(withoutDigest, rawContext, uppercaseHash).forEach { encoded ->
            assertInvalid(
                AgentRoutingSnapshotCodec.decode(encoded),
                AgentRoutingSnapshotError.INVALID_ROUTING,
            )
        }
    }

    @Test
    fun `zero provider idle timeout is invalid`() {
        val encoded = JsonInstant.encodeToString(
            AgentRunConfigSnapshot(routing = routingSnapshot().copy(providerIdleTimeoutMillis = 0)),
        )

        assertInvalid(
            AgentRoutingSnapshotCodec.decode(encoded),
            AgentRoutingSnapshotError.INVALID_ROUTING,
        )
    }

    @Test
    fun `reason code and permission digest reject display-unsafe values`() {
        val invalidReason = JsonInstant.encodeToString(
            AgentRunConfigSnapshot(routing = routingSnapshot().copy(reasonCode = "Please execute this request.")),
        )
        val oversizedDigest = JsonInstant.encodeToString(
            AgentRunConfigSnapshot(routing = routingSnapshot().copy(permissionDigest = "a".repeat(257))),
        )

        assertInvalid(
            AgentRoutingSnapshotCodec.decode(invalidReason),
            AgentRoutingSnapshotError.INVALID_ROUTING,
        )
        assertInvalid(
            AgentRoutingSnapshotCodec.decode(oversizedDigest),
            AgentRoutingSnapshotError.INVALID_ROUTING,
        )
    }

    @Test
    fun `tool names are deduplicated and sorted deterministically`() {
        val routing = routingSnapshot(
            resolvedToolNames = listOf("z_tool", "a_tool", "m_tool", "a_tool", "z_tool"),
        )

        assertEquals(listOf("a_tool", "m_tool", "z_tool"), routing.resolvedToolNames)
    }

    @Test
    fun `encoding normalizes directly constructed routing snapshots`() {
        val direct = routingSnapshot().copy(
            resolvedToolNames = listOf("z_tool", "a_tool", "z_tool"),
        )

        val decoded = AgentRoutingSnapshotCodec.decode(
            AgentRoutingSnapshotCodec.encode(AgentRunConfigSnapshot(routing = direct)),
        ) as AgentRoutingSnapshotDecodeResult.Auto

        assertEquals(listOf("a_tool", "z_tool"), decoded.routing.resolvedToolNames)
    }

    @Test
    fun `config encoding accepts exactly 64 KiB and rejects the next byte`() {
        var low = 0
        var high = AgentRoutingSnapshotCodec.MAX_CONFIG_BYTES * 2
        while (low < high) {
            val candidate = (low + high + 1) / 2
            if (runCatching { encodeWithPadding(candidate) }.isSuccess) {
                low = candidate
            } else {
                high = candidate - 1
            }
        }

        val boundary = encodeWithPadding(low)
        assertEquals(AgentRoutingSnapshotCodec.MAX_CONFIG_BYTES, boundary.toByteArray(Charsets.UTF_8).size)
        try {
            encodeWithPadding(low + 1)
            fail("A config larger than 64 KiB must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    private fun routingSnapshot(
        resolvedToolNames: List<String> = listOf("artifact_read"),
    ): AgentRoutingSnapshot = AgentRoutingSnapshot.create(
        intent = AgentIntent.EXECUTE,
        inputTrust = InputTrust.USER_DIRECT,
        reasonCode = "explicit_mutation",
        resolvedToolNames = resolvedToolNames,
        permissionDigest = "sha256:test-policy",
        executionContextDigest = EXECUTION_CONTEXT_DIGEST,
        providerIdleTimeoutMillis = 45_000,
        toolTimeoutMillis = 30_000,
        runTimeoutMillis = 30 * 60_000,
    )

    private fun encodeWithPadding(length: Int): String = AgentRoutingSnapshotCodec.encode(
        AgentRunConfigSnapshot(budgetPlaceholder = "x".repeat(length)),
    )

    private fun assertInvalid(
        result: AgentRoutingSnapshotDecodeResult,
        expectedError: AgentRoutingSnapshotError,
    ) {
        assertTrue(result is AgentRoutingSnapshotDecodeResult.Invalid)
        result as AgentRoutingSnapshotDecodeResult.Invalid
        assertEquals(expectedError, result.error)
    }

    private companion object {
        const val EXECUTION_CONTEXT_DIGEST =
            "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
