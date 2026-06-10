package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I-DELIMIT gating (#197 HP-2, design note security-model-design:197 §4.5). The untrusted-tool-content
 * clause is part of the system prompt exactly when the generation exposes tools; a no-tool chat has no
 * untrusted-tool-content channel to fence, so it must stay clause-free (no behavior change for plain
 * chats). The string content is pinned so the directive cannot be silently softened.
 */
class UntrustedToolContentClauseTest {

    @Test
    fun `clause is present only when tools are exposed`() {
        assertEquals("a no-tool generation must not carry the clause", "", untrustedToolContentClauseFor(false))
        assertEquals(
            "a tool-using generation must carry the clause",
            UNTRUSTED_TOOL_CONTENT_CLAUSE,
            untrustedToolContentClauseFor(true),
        )
    }

    @Test
    fun `clause states the data-not-instructions directive`() {
        val clause = untrustedToolContentClauseFor(true)
        assertTrue("must mark tool content as untrusted data", clause.contains("untrusted DATA", ignoreCase = true))
        assertTrue("must forbid following embedded instructions", clause.contains("not as instructions"))
        // The user-consent carve-out: the model may still act when the USER asked for it.
        assertTrue("must keep the user-initiated carve-out", clause.contains("unless the user"))
    }
}
