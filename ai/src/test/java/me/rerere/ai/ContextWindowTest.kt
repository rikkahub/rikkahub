package me.rerere.ai

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.Model
import me.rerere.ai.registry.ModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6 (design #193, architecture-design:114): [ModelRegistry.getContextWindowForModel] resolution
 * order is `Model.contextWindow override -> registry family lookup -> DEFAULT_CONTEXT_WINDOW`, and it
 * never returns a value < 2 — the floor every downstream consumer (`tokenPressure` /
 * `computeAllowedTokens` both `require(window >= 2)`) needs. Pure, JVM-testable: the resolver depends
 * only on a [Model] value, no Android/network.
 */
class ContextWindowTest {

    @Test
    fun `explicit positive override wins over registry and default`() {
        // gpt-5 has a seeded family window (400k); an explicit override must still take priority.
        val model = Model(modelId = "gpt-5", contextWindow = 12_345)
        assertEquals(12_345, ModelRegistry.getContextWindowForModel(model))
    }

    @Test
    fun `sub-2 override is ignored and falls through`() {
        // A bad config (0, negative, or 1) must NOT disable the downstream safety guard: every
        // downstream consumer requires window >= 2, so an override < 2 falls through to the registry
        // value for a known family rather than poisoning the trigger. Regression: contextWindow = 1
        // passed the old `> 0` floor and then threw IllegalArgumentException in tokenPressure.
        assertEquals(
            400_000,
            ModelRegistry.getContextWindowForModel(Model(modelId = "gpt-5", contextWindow = 0))
        )
        assertEquals(
            400_000,
            ModelRegistry.getContextWindowForModel(Model(modelId = "gpt-5", contextWindow = -1))
        )
        assertEquals(
            400_000,
            ModelRegistry.getContextWindowForModel(Model(modelId = "gpt-5", contextWindow = 1))
        )
    }

    @Test
    fun `registry family lookup supplies window when no override`() {
        assertEquals(
            200_000,
            ModelRegistry.getContextWindowForModel(Model(modelId = "claude-opus-4-8", contextWindow = null))
        )
        assertEquals(
            1_000_000,
            ModelRegistry.getContextWindowForModel(Model(modelId = "gemini-2.5-pro", contextWindow = null))
        )
        assertEquals(
            400_000,
            ModelRegistry.getContextWindowForModel(Model(modelId = "gpt-5-mini", contextWindow = null))
        )
    }

    @Test
    fun `unknown model falls back to conservative default`() {
        assertEquals(
            ModelRegistry.DEFAULT_CONTEXT_WINDOW,
            ModelRegistry.getContextWindowForModel(Model(modelId = "totally-unknown-model-xyz"))
        )
        // A blank id matches no family either.
        assertEquals(
            ModelRegistry.DEFAULT_CONTEXT_WINDOW,
            ModelRegistry.getContextWindowForModel(Model(modelId = ""))
        )
    }

    @Test
    fun `P6 resolver always returns a window at least 2 and honors overrides at least 2`() {
        // Property: regardless of id or override sign, the resolved window is >= 2 — the floor
        // tokenPressure/computeAllowedTokens require — and equals the override exactly when the
        // override is itself >= 2. Composes the resolver with its downstream contract so a sub-2
        // override can never leak through (the prior `> 0` property blessed an illegal `1`).
        runBlocking {
            checkAll(
                Arb.string(0..20),
                Arb.int(-5_000..2_000_000),
            ) { id, override ->
                val model = Model(modelId = id, contextWindow = override)
                val resolved = ModelRegistry.getContextWindowForModel(model)
                assertTrue("resolved window must be >= 2 (id='$id', override=$override)", resolved >= 2)
                if (override >= 2) {
                    assertEquals(override, resolved)
                }
            }
        }
    }
}
