package me.rerere.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Test
import me.rerere.ai.util.json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class ProviderSettingApiKeyTest {
    @Test
    fun legacy_keys_are_split_and_deduplicated() {
        val provider = ProviderSetting.OpenAI(apiKey = " one, two\nthree one ")

        assertEquals(listOf("one", "two", "three"), provider.apiKeys())
        assertEquals("one", provider.selectedApiKey())
    }

    @Test
    fun selected_index_is_clamped_and_legacy_key_is_synchronized() {
        val provider = ProviderSetting.OpenAI(
            apiKeys = listOf("first", "second"),
            selectedApiKeyIndex = 99,
        )

        assertEquals("first", provider.selectedApiKey())
        val normalized = provider.withApiKeys(provider.apiKeys(), 1) as ProviderSetting.OpenAI
        assertEquals("second", normalized.apiKey)
        assertEquals(1, normalized.selectedApiKeyIndex)
    }

    @Test
    fun empty_keys_return_empty_selected_key() {
        assertEquals("", ProviderSetting.Claude().selectedApiKey())
    }

    @Test
    fun multiple_keys_survive_serialization() {
        val original = ProviderSetting.OpenAI(
            apiKeys = listOf("first", "second"),
            selectedApiKeyIndex = 1,
            apiKeyInfos = listOf(
                ApiKeyInfo("first", "Primary", 1f),
                ApiKeyInfo("second", "Backup", 0.5f),
            ),
        )
        val encoded = json.encodeToString(ProviderSetting.serializer(), original)
        val decoded = json.decodeFromString<ProviderSetting>(encoded) as ProviderSetting.OpenAI

        assertEquals(listOf("first", "second"), decoded.apiKeys)
        assertEquals(1, decoded.selectedApiKeyIndex)
        assertEquals("Backup", decoded.apiKeyInfos[1].name)
        assertEquals(0.5f, decoded.apiKeyInfos[1].multiplier)
    }

    @Test
    fun metadata_is_normalized_and_legacy_keys_get_names() {
        val provider = ProviderSetting.OpenAI(
            apiKey = "a,b",
            apiKeyInfos = listOf(ApiKeyInfo("a", "Main", 2f)),
        )

        assertEquals(listOf("Main", "Key 2"), provider.apiKeyInfos().map { it.name })
        assertEquals(listOf(2f, 1f), provider.apiKeyInfos().map { it.multiplier })
    }
}
