package me.rerere.ai.runtime.hooks

import kotlinx.serialization.SerializationException
import me.rerere.common.json.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * The hooks config (#200 v1) persists as JSON inside the Assistant record, so its wire shape IS
 * the contract: defaults must be fail-closed (trusted=false, failClosed handled per-handler), the
 * polymorphic discriminator must be the stable "llm" name, and v1 must reject any handler type it
 * does not know — js/subagent/http are v2 and must not decode here.
 */
class HookConfigSerializationTest {

    @Test
    fun `default HookConfig is empty and untrusted`() {
        val config = HookConfig()

        assertTrue(config.hooks.isEmpty())
        assertFalse("hooks must default untrusted (import-trust gate H4)", config.trusted)
    }

    @Test
    fun `llm handler defaults to fast model and fail-open`() {
        val handler = HookHandler.Llm(prompt = "check the tool call")

        assertNull("null model must mean the settings fast model", handler.model)
        assertFalse("failClosed must be opt-in", handler.failClosed)
    }

    @Test
    fun `llm handler config round-trips through JSON`() {
        val original = HookConfig(
            hooks = mapOf(
                HookEvent.PreToolUse to listOf(
                    HookMatcher(
                        matcher = "create_memory",
                        handlers = listOf(
                            HookHandler.Llm(
                                prompt = "deny writes containing secrets",
                                model = Uuid.random(),
                                failClosed = true,
                            )
                        ),
                    )
                ),
                HookEvent.Stop to listOf(HookMatcher()),
            ),
            trusted = true,
        )

        val roundTripped = JsonInstant.decodeFromString<HookConfig>(
            JsonInstant.encodeToString(original)
        )

        assertEquals(original, roundTripped)
    }

    @Test
    fun `null matcher round-trips as match-everything`() {
        val original = HookMatcher(matcher = null, handlers = listOf(HookHandler.Llm(prompt = "p")))

        val roundTripped = JsonInstant.decodeFromString<HookMatcher>(
            JsonInstant.encodeToString(original)
        )

        assertNull(roundTripped.matcher)
        assertEquals(original, roundTripped)
    }

    @Test
    fun `llm handler serializes with the llm type discriminator`() {
        val json = JsonInstant.encodeToString(
            HookMatcher(handlers = listOf(HookHandler.Llm(prompt = "p")))
        )

        assertTrue("expected \"type\":\"llm\" in $json", json.contains("\"type\":\"llm\""))
    }

    @Test
    fun `unknown handler types are rejected - only llm exists in v1`() {
        val v2Json = """{"matcher":null,"handlers":[{"type":"js","code":"while(true){}"}]}"""

        try {
            JsonInstant.decodeFromString<HookMatcher>(v2Json)
            throw AssertionError("decoding a v2 'js' handler must fail in v1")
        } catch (expected: SerializationException) {
            // v1 has no js variant by design (owner mustFix: no preemptive QuickJS timeout)
        }
    }

    @Test
    fun `v1 event set is exactly PreToolUse, UserPromptSubmit, Stop`() {
        assertEquals(
            listOf(HookEvent.PreToolUse, HookEvent.UserPromptSubmit, HookEvent.Stop),
            HookEvent.entries.toList(),
        )
    }
}
