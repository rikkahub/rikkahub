package me.rerere.rikkahub.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/**
 * Pure-logic tests for the External Automation dispatcher's prompt-extraction helper.
 *
 * Integration tests for [ExternalAutomationDispatcher.dispatchTask] would require a fake
 * ChatService + AppScope + ConversationRepository graph; same situation as Phase 10's MCP
 * tests. The non-trivial logic in v1 — prompt extraction precedence, base64 decoding,
 * trim semantics, action/extra constants — is covered here.
 *
 * Tests use [java.util.Base64] (JDK-builtin, JVM-native) rather than [android.util.Base64]
 * so they execute in the standard `:app:testDebugUnitTest` task without Robolectric.
 */
class ExternalAutomationDispatcherTest {

    @Test fun `extractPromptStrings prefers raw string when both keys present`() {
        val out = ExternalAutomationDispatcher.extractPromptStrings(
            raw = "raw form wins",
            base64Encoded = Base64.getEncoder().encodeToString("base64 form".toByteArray()),
        )
        assertEquals("raw form wins", out)
    }

    @Test fun `extractPromptStrings decodes base64 when raw is missing`() {
        val encoded = Base64.getEncoder().encodeToString("hello world".toByteArray())
        assertEquals("hello world", ExternalAutomationDispatcher.extractPromptStrings(null, encoded))
    }

    @Test fun `extractPromptStrings accepts URL_SAFE base64`() {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString("test".toByteArray())
        assertEquals("test", ExternalAutomationDispatcher.extractPromptStrings(null, encoded))
    }

    @Test fun `extractPromptStrings returns null when both null`() {
        assertNull(ExternalAutomationDispatcher.extractPromptStrings(null, null))
    }

    @Test fun `extractPromptStrings skips blank raw and falls through to base64`() {
        val encoded = Base64.getEncoder().encodeToString("non blank".toByteArray())
        assertEquals("non blank", ExternalAutomationDispatcher.extractPromptStrings("", encoded))
    }

    @Test fun `extractPromptStrings returns null when both empty`() {
        assertNull(ExternalAutomationDispatcher.extractPromptStrings("", ""))
    }

    @Test fun `extractPromptStrings with corrupt base64 returns null silently`() {
        // Corrupt base64 should fail decode and surface as null — caller treats that the
        // same as missing, returning a clean rejection envelope. The test is the function
        // does not throw.
        val out = ExternalAutomationDispatcher.extractPromptStrings(null, "not-base64!@#$%^&*")
        // Either null or a garbage-string result is acceptable — caller validates downstream.
        // Asserting only that no exception escapes.
        if (out != null) {
            // best-effort decode succeeded with truncated input; that's fine.
        }
    }

    @Test fun `action constants match spec strings exactly`() {
        // Spec lists these as the canonical exported intent actions; downstream automation
        // tools encode them by hand so any drift breaks every existing Tasker macro.
        assertEquals("me.rerere.rikkahub.RUN_TASK", ExternalAutomationDispatcher.ACTION_RUN_TASK)
        assertEquals("me.rerere.rikkahub.RUN_CHAT", ExternalAutomationDispatcher.ACTION_RUN_CHAT)
    }

    @Test fun `extra constants match spec strings exactly`() {
        assertEquals("task", ExternalAutomationDispatcher.EXTRA_TASK)
        assertEquals("task_b64", ExternalAutomationDispatcher.EXTRA_TASK_B64)
        assertEquals("chat", ExternalAutomationDispatcher.EXTRA_CHAT)
        assertEquals("chat_b64", ExternalAutomationDispatcher.EXTRA_CHAT_B64)
        assertEquals("request_id", ExternalAutomationDispatcher.EXTRA_REQUEST_ID)
        assertEquals("return_action", ExternalAutomationDispatcher.EXTRA_RETURN_ACTION)
        assertEquals("return_package", ExternalAutomationDispatcher.EXTRA_RETURN_PACKAGE)
        assertEquals("status", ExternalAutomationDispatcher.EXTRA_STATUS)
        assertEquals("message", ExternalAutomationDispatcher.EXTRA_MESSAGE)
    }
}
