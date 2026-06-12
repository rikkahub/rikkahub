package me.rerere.ai.runtime.hooks

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hook-denial provenance marker (#200 T10). A hook deny reuses the existing
 * `ToolApprovalState.Denied` machine, so the UI needs an out-of-band marker on the tool part's
 * metadata to tell "blocked by hook" apart from a user denial — never silently.
 */
class HookToolMetadataTest {

    @Test
    fun `marking null metadata yields a detectable hook denial`() {
        val marked = markDeniedByHook(null)

        assertTrue(isDeniedByHook(marked))
    }

    @Test
    fun `marking preserves existing metadata keys`() {
        val existing = buildJsonObject { put("other", JsonPrimitive("kept")) }

        val marked = markDeniedByHook(existing)

        assertTrue(isDeniedByHook(marked))
        assertEquals(JsonPrimitive("kept"), marked["other"])
    }

    @Test
    fun `absent or unrelated metadata is not a hook denial`() {
        assertFalse(isDeniedByHook(null))
        assertFalse(isDeniedByHook(buildJsonObject { put("other", JsonPrimitive(true)) }))
    }
}
