package me.rerere.ai.registry

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the bundled models.dev catalog and its GAP-FILL integration into [ModelRegistry].
 *
 * Contract: the registry's curated/conditional family value always wins (e.g. Claude's base 200k —
 * the 1M window is a gated beta, NOT universal, so models.dev's flat 1M must not override it);
 * models.dev only supplies a window for the long tail the registry doesn't hardcode, before the
 * 128k default. Assertions check that contract + the snapshot's stable facts.
 */
class ModelsDevCatalogTest {

    @Test
    fun `lookup resolves a known model, with provider-prefix normalization`() {
        val bare = ModelsDevCatalog.lookup("claude-sonnet-4-5")
        val prefixed = ModelsDevCatalog.lookup("anthropic/claude-sonnet-4-5")
        assertNotNull("a flagship model must be in the bundled snapshot", bare)
        assertEquals("provider-prefixed id must normalize to the same spec", bare, prefixed)
    }

    @Test
    fun `known model carries a context window and capabilities`() {
        val spec = ModelsDevCatalog.lookup("claude-sonnet-4-5")!!
        assertTrue("context window should be a real positive value", (spec.contextWindow ?: 0) > 0)
        assertTrue("claude is reasoning-capable", ModelAbility.REASONING in spec.abilities)
    }

    @Test
    fun `unknown model id returns null so the registry fallback runs`() {
        assertNull(ModelsDevCatalog.lookup("definitely-not-a-real-model-xyz-123"))
    }

    @Test
    fun `registry curated value wins over models_dev (Claude 1M is gated, not universal)`() {
        // models.dev lists claude-opus-4-8 at its 1M ceiling; rikkahub's registry keeps the base 200k
        // and gates 1M separately (supportsClaude1MContext + the beta header). Gap-fill must NOT
        // override that with the flat 1M.
        assertEquals(200_000, ModelRegistry.getContextWindowForModel(Model(modelId = "claude-opus-4-8")))
    }

    @Test
    fun `models_dev fills the window for a model the registry pattern misses`() {
        // The registry has no mistral family; without models.dev this would fall to the 128k default.
        val fromCatalog = ModelsDevCatalog.lookup("mistral-large-latest")?.contextWindow
        assertNotNull("snapshot must list mistral-large-latest for this test", fromCatalog)
        val resolved = ModelRegistry.getContextWindowForModel(Model(modelId = "mistral-large-latest"))
        assertEquals("gap-fill must surface the models.dev window", fromCatalog, resolved)
        assertNotEquals("and it must not be the conservative default", ModelRegistry.DEFAULT_CONTEXT_WINDOW, resolved)
    }

    @Test
    fun `explicit Model contextWindow override still wins over everything`() {
        assertEquals(
            12_345,
            ModelRegistry.getContextWindowForModel(Model(modelId = "claude-sonnet-4-5", contextWindow = 12_345))
        )
    }

    @Test
    fun `a fully unlisted model falls back to the registry default, not a crash`() {
        val resolved = ModelRegistry.getContextWindowForModel(Model(modelId = "totally-made-up-proxy-model"))
        assertTrue("must return a usable positive window", resolved >= 2)
    }
}
