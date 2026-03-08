package me.rerere.rikkahub.ui.pages.setting.components

import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.model.Avatar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderAvatarAutoFillTest {
    @Test
    fun `dummy avatar should become auto favicon when base url is valid`() {
        val result = resolveAvatarOnBaseUrlChanged(
            current = Avatar.Dummy,
            newBaseUrl = "https://gateway.example.com/v1"
        )

        assertEquals(
            Avatar.Image("https://favicone.com/gateway.example.com?rh_auto=1"),
            result
        )
    }

    @Test
    fun `auto favicon should follow host when base url host changes`() {
        val result = resolveAvatarOnBaseUrlChanged(
            current = Avatar.Image("https://favicone.com/old.example.com?rh_auto=1"),
            newBaseUrl = "https://new.example.com/v1"
        )

        assertEquals(
            Avatar.Image("https://favicone.com/new.example.com?rh_auto=1"),
            result
        )
    }

    @Test
    fun `manual image should stay unchanged when base url host changes`() {
        val current = Avatar.Image("https://cdn.example.com/custom-logo.png")
        val result = resolveAvatarOnBaseUrlChanged(
            current = current,
            newBaseUrl = "https://another.example.com/v1"
        )

        assertEquals(current, result)
    }

    @Test
    fun `emoji should stay unchanged when base url host changes`() {
        val current = Avatar.Emoji("brain")
        val result = resolveAvatarOnBaseUrlChanged(
            current = current,
            newBaseUrl = "https://another.example.com/v1"
        )

        assertEquals(current, result)
    }

    @Test
    fun `reset picked dummy should fallback to auto favicon when base url is valid`() {
        val result = resolveAvatarOnAvatarPicked(
            picked = Avatar.Dummy,
            currentBaseUrl = "https://proxy.example.com/v1"
        )

        assertEquals(
            Avatar.Image("https://favicone.com/proxy.example.com?rh_auto=1"),
            result
        )
    }

    @Test
    fun `reset picked dummy should stay dummy when base url is invalid`() {
        val result = resolveAvatarOnAvatarPicked(
            picked = Avatar.Dummy,
            currentBaseUrl = "not-a-valid-url"
        )

        assertEquals(Avatar.Dummy, result)
    }

    @Test
    fun `buildAutoFaviconUrl should return null for invalid url`() {
        assertNull(buildAutoFaviconUrl("invalid-url"))
    }

    @Test
    fun `isAutoFaviconUrl should only match favicone url with marker`() {
        assertTrue(isAutoFaviconUrl("https://favicone.com/example.com?rh_auto=1"))
        assertFalse(isAutoFaviconUrl("https://favicone.com/example.com"))
        assertFalse(isAutoFaviconUrl("https://favicone.com/example.com?rh_auto=0"))
        assertFalse(isAutoFaviconUrl("https://cdn.example.com/example.com?rh_auto=1"))
    }

    @Test
    fun `resetBaseUrlToDefault should update auto avatar but keep manual avatar`() {
        val autoProvider = ProviderSetting.OpenAI(
            baseUrl = "https://proxy.example.com/v1",
            avatar = Avatar.Image("https://favicone.com/proxy.example.com?rh_auto=1")
        )
        val autoReset = autoProvider.resetBaseUrlToDefault() as ProviderSetting.OpenAI
        assertEquals("https://api.openai.com/v1", autoReset.baseUrl)
        assertEquals(
            Avatar.Image("https://favicone.com/api.openai.com?rh_auto=1"),
            autoReset.avatar
        )

        val manualProvider = ProviderSetting.OpenAI(
            baseUrl = "https://proxy.example.com/v1",
            avatar = Avatar.Image("https://favicone.com/proxy.example.com")
        )
        val manualReset = manualProvider.resetBaseUrlToDefault() as ProviderSetting.OpenAI
        assertEquals("https://api.openai.com/v1", manualReset.baseUrl)
        assertEquals(Avatar.Image("https://favicone.com/proxy.example.com"), manualReset.avatar)
    }
}
