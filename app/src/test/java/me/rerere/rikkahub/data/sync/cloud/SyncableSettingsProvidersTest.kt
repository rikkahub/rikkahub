package me.rerere.rikkahub.data.sync.cloud

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SyncableSettingsProvidersTest {
    @Test
    fun extract_stripsPerryDeviceToken_keepsDirectApiKey() {
        val perryId = PerryCatalog.providerUuid("openai")
        val directId = Uuid.parse("11111111-1111-1111-1111-111111111111")
        val settings = Settings(
            init = false,
            providers = listOf(
                ProviderSetting.OpenAI(
                    id = perryId,
                    name = "Perry / OpenAI",
                    baseUrl = "http://192.168.3.44:8787/v1/ai/openai/v1",
                    apiKey = "device-token-A",
                    models = listOf(Model(modelId = "gpt-4o-mini", displayName = "mini")),
                ),
                ProviderSetting.OpenAI(
                    id = directId,
                    name = "Local OpenAI",
                    baseUrl = "https://api.openai.com/v1",
                    apiKey = "sk-user-key",
                ),
            ),
            perryDeviceToken = "device-token-A",
            perryConfig = PerryServerConfig(host = "192.168.3.44", port = 8787),
        )

        val extracted = SyncableSettings.extract(settings)[SyncableSettings.PROVIDERS]!!
        val roundTrip = SyncableSettings.applyKey(
            settings.copy(
                providers = emptyList(),
                perryDeviceToken = "device-token-B",
                perryConfig = PerryServerConfig(host = "10.0.0.2", port = 8787),
            ),
            SyncableSettings.PROVIDERS,
            extracted,
        )

        val perry = roundTrip.providers.filterIsInstance<ProviderSetting.OpenAI>()
            .first { it.id == perryId }
        val direct = roundTrip.providers.filterIsInstance<ProviderSetting.OpenAI>()
            .first { it.id == directId }

        assertEquals("device-token-B", perry.apiKey)
        assertTrue(perry.baseUrl.contains("10.0.0.2"))
        assertTrue(perry.baseUrl.contains("/v1/ai/openai/v1"))
        assertEquals(1, perry.models.size)
        assertEquals("gpt-4o-mini", perry.models.first().modelId)
        assertEquals("sk-user-key", direct.apiKey)
        assertFalse(extracted.toString().contains("device-token-A"))
    }

    @Test
    fun sanitize_clearsOnlyPerryApiKey() {
        val perry = ProviderSetting.OpenAI(
            baseUrl = "http://h/v1/ai/x/v1",
            apiKey = "tok",
        )
        val direct = ProviderSetting.OpenAI(
            baseUrl = "https://api.openai.com/v1",
            apiKey = "sk",
        )
        assertEquals("", (SyncableSettings.sanitizeProviderForSync(perry) as ProviderSetting.OpenAI).apiKey)
        assertEquals("sk", (SyncableSettings.sanitizeProviderForSync(direct) as ProviderSetting.OpenAI).apiKey)
    }
}
