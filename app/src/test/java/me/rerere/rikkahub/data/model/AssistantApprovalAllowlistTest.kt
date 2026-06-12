package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.jsonArray
import me.rerere.common.json.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T10 (SPEC.md M4, maintainer decision #2): the additive [Assistant.subagentApprovalAllowlist]
 * field — the explicit child-tool approval-forward allowlist surfaced into
 * `AgentTypeSpec.toolPolicy`. ONLY tools on this list forward their approval request to the
 * parent's surface when this assistant runs as a subagent; every other approval-gated child tool
 * auto-denies.
 *
 * The load-bearing invariant: the field is ADDITIVE with an EMPTY default, so a legacy assistant
 * JSON written before T10 (omitting the key) must still decode — and must do so with the empty
 * allowlist, matching today's strip-all-approval-tools subagent behavior (decision #2's
 * conservative default = forward nothing). Without the default, kotlinx-serialization throws
 * MissingFieldException, which is exactly the regression this test guards.
 */
class AssistantApprovalAllowlistTest {

    @Test
    fun `legacy assistant JSON without the allowlist decodes with an empty default (no migration)`() {
        // A minimal payload as persisted before T10 — no subagentApprovalAllowlist key.
        val legacyJson = """
            {
              "id": "22222222-2222-2222-2222-222222222222",
              "name": "Legacy",
              "spawnable": true
            }
        """.trimIndent()

        val decoded = JsonInstant.decodeFromString<Assistant>(legacyJson)

        assertEquals("Legacy", decoded.name)
        assertTrue(decoded.spawnable)
        // The conservative default: empty allowlist = forward no approval-gated child tool.
        assertEquals(emptyList<String>(), decoded.subagentApprovalAllowlist)
    }

    @Test
    fun `a non-empty allowlist survives an encode then decode round-trip`() {
        val original = Assistant(
            name = "Researcher",
            spawnable = true,
            subagentApprovalAllowlist = listOf("ask_user", "read_file"),
        )

        val roundTripped = JsonInstant.decodeFromString<Assistant>(
            JsonInstant.encodeToString(original)
        )

        assertEquals(original.id, roundTripped.id)
        assertEquals(listOf("ask_user", "read_file"), roundTripped.subagentApprovalAllowlist)
    }

    @Test
    fun `a default-constructed assistant forwards nothing`() {
        assertEquals(emptyList<String>(), Assistant().subagentApprovalAllowlist)
    }

    @Test
    fun `encoded assistant emits the allowlist key (encodeDefaults forward-compat)`() {
        val json = JsonInstant.parseToJsonElement(
            JsonInstant.encodeToString(Assistant(name = "X"))
        )
        val obj = json as kotlinx.serialization.json.JsonObject
        // encodeDefaults=true: the key is present even at its empty default, proving it is wired
        // into the serializer (forward-compat insurance for older readers that ignore unknowns).
        assertEquals(0, obj["subagentApprovalAllowlist"]!!.jsonArray.size)
    }
}
