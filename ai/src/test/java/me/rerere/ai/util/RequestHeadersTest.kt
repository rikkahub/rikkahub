package me.rerere.ai.util

import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Test

class RequestHeadersTest {
    @Test
    fun `mergeCustomHeaders should use provider headers when no request headers`() {
        val provider = ProviderSetting.OpenAI(
            customHeaders = listOf(
                CustomHeader("User-Agent", "provider-agent"),
                CustomHeader("X-Title", "provider-title"),
            )
        )

        val headers = provider.mergeCustomHeaders()

        assertEquals(listOf("provider-agent"), headers.values("User-Agent"))
        assertEquals(listOf("provider-title"), headers.values("X-Title"))
    }

    @Test
    fun `mergeCustomHeaders should let request headers override provider headers`() {
        val provider = ProviderSetting.OpenAI(
            customHeaders = listOf(
                CustomHeader("User-Agent", "provider-agent"),
                CustomHeader("X-Title", "provider-title"),
            )
        )

        val headers = provider.mergeCustomHeaders(
            listOf(CustomHeader("user-agent", "model-agent"))
        )

        assertEquals(listOf("model-agent"), headers.values("User-Agent"))
        assertEquals(listOf("provider-title"), headers.values("X-Title"))
    }

    @Test
    fun `mergeCustomHeaders should ignore blank header names`() {
        val provider = ProviderSetting.OpenAI(
            customHeaders = listOf(CustomHeader("", "value"))
        )

        assertEquals(0, provider.mergeCustomHeaders().size)
    }
}
