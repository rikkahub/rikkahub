package me.rerere.ai.runtime.hooks

import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Property suite over [matches] / [matchesIf] — the pure predicates that decide which
 * [HookMatcher]s fire for a given tool name (spec open Q #3: exact tool-name OR regex,
 * `null` matcher = always matches).
 */
class HookPrimitivesPropertyTest {

    // Alphanumeric names contain no regex metacharacters, so as a pattern such a name
    // matches exactly itself — which lets exact-vs-regex properties stay crisp.
    private val arbToolName: Arb<String> = Arb.string(1..8, Codepoint.alphanumeric())

    @Test
    fun `null matcher always matches any tool name`() {
        runBlocking {
            checkAll(500, arbToolName.orNull(0.2)) { toolName ->
                assertTrue(matches(null, toolName))
            }
        }
    }

    @Test
    fun `exact tool-name matcher matches itself even when it is not a valid regex`() {
        runBlocking {
            // Arbitrary strings include regex metacharacters and invalid patterns;
            // exact equality must match regardless.
            checkAll(500, Arb.string(0..12)) { name ->
                assertTrue(matches(name, name))
            }
        }
    }

    @Test
    fun `literal matcher does not match a different literal name`() {
        runBlocking {
            checkAll(500, arbToolName, arbToolName) { a, b ->
                if (a != b) {
                    assertFalse(matches(a, b))
                }
            }
        }
    }

    @Test
    fun `regex matcher matches per regex semantics`() {
        runBlocking {
            checkAll(500, arbToolName, Arb.string(0..8, Codepoint.alphanumeric())) { prefix, suffix ->
                assertTrue(matches("$prefix.*", "$prefix$suffix"))
            }
        }
    }

    @Test
    fun `regex matcher requires a full match not a substring match`() {
        runBlocking {
            checkAll(500, arbToolName, arbToolName) { core, extra ->
                // "core" as a pattern fully matches only "core"; "core+extra" is longer.
                assertFalse(matches(core, core + extra))
            }
        }
    }

    @Test
    fun `non-null matcher never matches a null tool name`() {
        runBlocking {
            // Events without a tool name (UserPromptSubmit/Stop) only fire null-matcher
            // hooks — a named matcher against no name is fail-closed.
            checkAll(500, Arb.string(0..12)) { matcher ->
                assertFalse(matches(matcher, null))
            }
        }
    }

    @Test
    fun `invalid regex pattern matches only its exact-equal name`() {
        assertFalse(matches("[", "anything"))
        assertTrue(matches("[", "["))
    }

    @Test
    fun `matchesIf delegates to the matcher field`() {
        runBlocking {
            checkAll(500, arbToolName.orNull(0.3), arbToolName.orNull(0.2)) { matcher, toolName ->
                val hookMatcher = HookMatcher(matcher = matcher)
                assertEquals(matches(matcher, toolName), matchesIf(hookMatcher, toolName))
            }
        }
    }
}

/**
 * Property suite over [parseHookOutput] — the boundary that turns untrusted handler text into a
 * typed decision. Contract: never throws (failures are values), rejects event spoofing, and
 * round-trips every valid decision shape.
 */
class HookOutputParsePropertyTest {

    private val arbEvent: Arb<HookEvent> = Arb.enum<HookEvent>()

    private val arbDecision: Arb<HookDecision> = Arb.choice(
        Arb.constant(HookDecision.Allow),
        Arb.constant(HookDecision.Ask),
        Arb.string(0..24).map { HookDecision.Deny(it) },
    )

    private val arbHookOutput: Arb<HookOutput> = Arb.bind(
        arbDecision,
        Arb.string(0..24).orNull(0.5),
        Arb.string(0..24).orNull(0.5),
        Arb.boolean(),
    ) { decision, updatedInput, additionalContext, preventContinuation ->
        HookOutput(decision, updatedInput, additionalContext, preventContinuation)
    }

    // Test-local encoder, deliberately independent of any production serializer so a
    // round-trip cannot hide a matching encode/decode bug.
    private fun wireJson(output: HookOutput, claimedEvent: HookEvent?): String = buildJsonObject {
        claimedEvent?.let { put("hookEventName", it.name) }
        when (val decision = output.decision) {
            HookDecision.Allow -> put("decision", "allow")
            HookDecision.Ask -> put("decision", "ask")
            is HookDecision.Deny -> {
                put("decision", "deny")
                put("reason", decision.reason)
            }
        }
        output.updatedInput?.let { put("updatedInput", it) }
        output.additionalContext?.let { put("additionalContext", it) }
        put("preventContinuation", output.preventContinuation)
    }.toString()

    @Test
    fun `never throws on arbitrary garbage input`() {
        runBlocking {
            checkAll(1000, Arb.string(0..64), arbEvent) { garbage, event ->
                // The property IS "returns a value instead of throwing" — any
                // exception escaping here fails the test.
                parseHookOutput(garbage, event)
            }
        }
    }

    @Test
    fun `malformed JSON returns a typed Malformed failure`() {
        val cases = listOf("", "not json", "{", "[1,2]", "\"a string\"", "123", "null", "{\"decision\":}")
        for (raw in cases) {
            val result = parseHookOutput(raw, HookEvent.Stop)
            assertTrue(
                "expected Malformed for $raw, got $result",
                result is HookOutputParseResult.Failure.Malformed,
            )
        }
    }

    @Test
    fun `unknown decision value is a typed failure not a silent allow`() {
        runBlocking {
            checkAll(500, Arb.string(1..12, Codepoint.alphanumeric()), arbEvent) { word, event ->
                if (word !in setOf("allow", "ask", "deny")) {
                    val raw = buildJsonObject { put("decision", word) }.toString()
                    assertTrue(parseHookOutput(raw, event) is HookOutputParseResult.Failure.Malformed)
                }
            }
        }
    }

    @Test
    fun `output claiming a different event than dispatched is rejected`() {
        runBlocking {
            checkAll(500, arbHookOutput, arbEvent, arbEvent) { output, claimed, dispatched ->
                if (claimed != dispatched) {
                    val result = parseHookOutput(wireJson(output, claimed), dispatched)
                    assertEquals(
                        HookOutputParseResult.Failure.EventMismatch(claimed.name, dispatched),
                        result,
                    )
                }
            }
        }
    }

    @Test
    fun `arbitrary claimed event name that is not the dispatched event is rejected`() {
        runBlocking {
            checkAll(500, Arb.string(0..16), arbEvent) { claimed, dispatched ->
                if (claimed != dispatched.name) {
                    val raw = buildJsonObject {
                        put("hookEventName", claimed)
                        put("decision", "allow")
                    }.toString()
                    assertTrue(parseHookOutput(raw, dispatched) is HookOutputParseResult.Failure.EventMismatch)
                }
            }
        }
    }

    @Test
    fun `valid output round-trips with a matching claimed event`() {
        runBlocking {
            checkAll(1000, arbHookOutput, arbEvent) { output, event ->
                val result = parseHookOutput(wireJson(output, claimedEvent = event), event)
                assertEquals(HookOutputParseResult.Parsed(output), result)
            }
        }
    }

    @Test
    fun `valid output round-trips when no event is claimed`() {
        runBlocking {
            checkAll(1000, arbHookOutput, arbEvent) { output, event ->
                val result = parseHookOutput(wireJson(output, claimedEvent = null), event)
                assertEquals(HookOutputParseResult.Parsed(output), result)
            }
        }
    }

    @Test
    fun `unknown extra keys are ignored`() {
        val raw = """{"decision":"ask","somethingElse":42,"nested":{"a":1}}"""
        assertEquals(
            HookOutputParseResult.Parsed(HookOutput(decision = HookDecision.Ask)),
            parseHookOutput(raw, HookEvent.PreToolUse),
        )
    }

    @Test
    fun `absent decision defaults to a context-only allow`() {
        // UserPromptSubmit/Stop hooks may only inject context; no decision key = no gate.
        val raw = """{"additionalContext":"remember the project conventions"}"""
        assertEquals(
            HookOutputParseResult.Parsed(
                HookOutput(
                    decision = HookDecision.Allow,
                    additionalContext = "remember the project conventions",
                ),
            ),
            parseHookOutput(raw, HookEvent.UserPromptSubmit),
        )
    }
}
