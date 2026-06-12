package me.rerere.rikkahub.ui.pages.assistant.detail

import kotlinx.coroutines.runBlocking
import me.rerere.ai.runtime.hooks.HookConfig
import me.rerere.ai.runtime.hooks.HookEvent
import me.rerere.ai.runtime.hooks.HookExecutor
import me.rerere.ai.runtime.hooks.HookHandler
import me.rerere.ai.runtime.hooks.HookMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure editor/trust logic behind AssistantHooksPage (#200 T10). The page is a thin Compose shell
 * over these helpers, so the trust-flow invariants (authored hooks are trusted; imported hooks
 * stay gated until an explicit grant; the grant only flips the bit) are provable on the JVM.
 */
class AssistantHooksPageTest {

    private fun llm(prompt: String = "gate") = HookHandler.Llm(prompt = prompt)

    private fun entry(prompt: String = "gate", matcher: String? = null) =
        HookMatcher(matcher = matcher, handlers = listOf(llm(prompt)))

    private fun importedUntrusted() = HookConfig(
        hooks = mapOf(HookEvent.PreToolUse to listOf(entry("imported"))),
        trusted = false,
    )

    // --- authoring ---

    @Test
    fun `authoring a hook on an empty config stores it trusted`() {
        val config = HookConfig().withAuthoredHook(HookEvent.PreToolUse, entry("mine", matcher = "search"))

        assertTrue(config.trusted)
        assertEquals(listOf(entry("mine", matcher = "search")), config.hooks[HookEvent.PreToolUse])
    }

    @Test
    fun `authoring on an already trusted config appends and stays trusted`() {
        val base = HookConfig(
            hooks = mapOf(HookEvent.Stop to listOf(entry("first"))),
            trusted = true,
        )

        val config = base.withAuthoredHook(HookEvent.Stop, entry("second"))

        assertTrue(config.trusted)
        assertEquals(listOf(entry("first"), entry("second")), config.hooks[HookEvent.Stop])
    }

    @Test
    fun `authoring while imported hooks await review is rejected`() {
        // Authoring sets trusted=true; doing so on an unreviewed import would silently trust the
        // imported hooks too. The page never offers the editor in this state — the helper enforces it.
        assertThrows(IllegalArgumentException::class.java) {
            importedUntrusted().withAuthoredHook(HookEvent.Stop, entry("mine"))
        }
    }

    // --- edit / delete ---

    @Test
    fun `editing a hook replaces it in place`() {
        val base = HookConfig(
            hooks = mapOf(HookEvent.PreToolUse to listOf(entry("a"), entry("b"))),
            trusted = true,
        )

        val config = base.withUpdatedHook(HookEvent.PreToolUse, index = 1, entry = entry("edited"))

        assertEquals(listOf(entry("a"), entry("edited")), config.hooks[HookEvent.PreToolUse])
        assertTrue(config.trusted)
    }

    @Test
    fun `deleting the last hook of an event drops the event key`() {
        val base = HookConfig(
            hooks = mapOf(
                HookEvent.PreToolUse to listOf(entry("only")),
                HookEvent.Stop to listOf(entry("keep")),
            ),
            trusted = true,
        )

        val config = base.withRemovedHook(HookEvent.PreToolUse, index = 0)

        assertFalse(config.hooks.containsKey(HookEvent.PreToolUse))
        assertEquals(listOf(entry("keep")), config.hooks[HookEvent.Stop])
    }

    // --- trust review / grant ---

    @Test
    fun `requiresTrustReview only for untrusted non-empty configs`() {
        assertTrue(importedUntrusted().requiresTrustReview())
        assertFalse(HookConfig().requiresTrustReview())
        assertFalse(importedUntrusted().copy(trusted = true).requiresTrustReview())
    }

    @Test
    fun `granting trust flips the bit and preserves hook definitions`() {
        val imported = importedUntrusted()

        val granted = imported.withGrantedTrust()

        assertTrue(granted.trusted)
        assertEquals(imported.hooks, granted.hooks)
    }

    // --- test-hook runner ---

    private class CannedExecutor(private val body: suspend () -> String) : HookExecutor {
        var calls = 0
        override suspend fun execute(event: HookEvent, handler: HookHandler, input: String): String {
            calls++
            return body()
        }
    }

    @Test
    fun `test-run reports the parsed decision of a hook output`() = runBlocking {
        val executor = CannedExecutor {
            """{"hookEventName":"PreToolUse","decision":"deny","reason":"not allowed"}"""
        }

        val result = runHookTest(executor, HookEvent.PreToolUse, llm(), toolName = "search")

        assertEquals(1, executor.calls)
        assertTrue("result should surface the decision: $result", result.contains("deny", ignoreCase = true))
        assertTrue("result should surface the reason: $result", result.contains("not allowed"))
    }

    @Test
    fun `test-run rejects output that spoofs a different event`() = runBlocking {
        val executor = CannedExecutor { """{"hookEventName":"Stop","decision":"allow"}""" }

        val result = runHookTest(executor, HookEvent.PreToolUse, llm(), toolName = "search")

        assertTrue("spoofed event must be surfaced as a failure: $result", result.contains("Stop"))
    }

    @Test
    fun `test-run surfaces an executor failure instead of crashing`() = runBlocking {
        val executor = CannedExecutor { error("provider unreachable") }

        val result = runHookTest(executor, HookEvent.UserPromptSubmit, llm(), toolName = null)

        assertTrue("failure must be visible: $result", result.contains("provider unreachable"))
    }

    @Test
    fun `sample input claims the event being tested`() {
        for (event in HookEvent.entries) {
            val input = sampleHookInput(event, toolName = "search")
            assertTrue(
                "sample input for $event must carry its own event name: $input",
                input.contains("\"hookEventName\":\"${event.name}\""),
            )
        }
    }
}
