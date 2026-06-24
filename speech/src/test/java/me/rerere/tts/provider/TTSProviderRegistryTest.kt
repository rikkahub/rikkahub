package me.rerere.tts.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replaces the compiler's `when`-exhaustiveness guarantee that the old TTSManager dispatcher had: with
 * the registry, a [TTSProviderSetting] subtype added without a [TTS_PROVIDER_REGISTRY] entry would
 * compile but crash at runtime. This pins the registry to cover EXACTLY [TTSProviderSetting.Types] in
 * both directions — every setting subtype has a provider, and no provider is registered for a type the
 * picker doesn't list — so adding a subtype without wiring its provider reddens here.
 */
class TTSProviderRegistryTest {

    @Test
    fun `every TTS setting subtype has a registered provider`() {
        TTSProviderSetting.Types.forEach { type ->
            assertTrue(
                "no TTS provider registered for ${type.simpleName}",
                TTS_PROVIDER_REGISTRY.containsKey(type),
            )
        }
    }

    @Test
    fun `the registry has no orphan provider without a matching setting type`() {
        val known = TTSProviderSetting.Types.toSet()
        TTS_PROVIDER_REGISTRY.keys.forEach { key ->
            assertTrue("registry has a provider for an unlisted type ${key.simpleName}", key in known)
        }
    }

    @Test
    fun `registry size matches the number of setting types`() {
        assertEquals(TTSProviderSetting.Types.size, TTS_PROVIDER_REGISTRY.size)
    }
}
