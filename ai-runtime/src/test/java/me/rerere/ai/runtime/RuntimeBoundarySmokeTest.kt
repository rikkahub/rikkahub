package me.rerere.ai.runtime

import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slice-2 skeleton test: proves the `:ai` dependency is wired onto the
 * `:ai-runtime` classpath and that the module is exercised by CI's
 * `testDebugUnitTest` aggregate. Later slices grow the determinism /
 * cancellation harness in this package. Pure JVM, deterministic, no Android.
 */
class RuntimeBoundarySmokeTest {

    @Test
    fun aiNeutralTypeResolvesOnRuntimeClasspath() {
        val provider: ProviderSetting = ProviderSetting.OpenAI()
        assertTrue(provider.name.isNotEmpty())
    }
}
