package me.rerere.rikkahub.voiceagent

import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.hermesvoice.HermesVoiceCloudflareAccessCredentials
import me.rerere.rikkahub.voiceagent.hermesvoice.HermesVoiceCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class VoiceAgentConfigResolverTest {
    @Test
    fun `resolves Hermes Voice config from the conversation chat provider`() {
        val assistantId = Uuid.random()
        val modelId = Uuid.random()
        val model = Model(
            id = modelId,
            modelId = "hermes-agent",
            displayName = "Hermes Agent",
        )
        val settings = Settings(
            assistantId = assistantId,
            chatModelId = Uuid.random(),
            assistants = listOf(
                Assistant(
                    id = assistantId,
                    name = "Hermes",
                    chatModelId = modelId,
                )
            ),
            providers = listOf(
                ProviderSetting.OpenAI(
                    id = Uuid.parse("00000000-0000-0000-0000-000000000001"),
                    name = "RMS Hermes",
                    apiKey = "profile-api-key",
                    baseUrl = "https://hermes.example.test/v1",
                    models = listOf(model),
                )
            ),
        )
        val conversation = Conversation.ofId(id = Uuid.random(), assistantId = assistantId)

        val result = VoiceAgentConfigResolver(baseUrlOverride = "")
            .resolve(settings = settings, conversation = conversation)

        assertTrue(result is VoiceAgentConfigResult.Available)
        val config = (result as VoiceAgentConfigResult.Available).config
        assertEquals("https://hermes.example.test", config.hermesVoiceBaseUrl)
        assertEquals("profile-api-key", config.credentials.deviceApiKey)
        assertEquals("gemini-flash", config.voiceModelId)
        assertEquals("Hermes", config.assistantName)
        assertEquals(
            "sha256:8c9d46b59acb04a022fe63445de0e91617f450fed6b601483b11f713b6589fb3",
            config.directAccountConfigurationHash,
        )

        val changedAccount = settings.copy(
            providers = listOf(
                (settings.providers.single() as ProviderSetting.OpenAI).copy(
                    apiKey = "different-profile-api-key",
                ),
            ),
        )
        val changedConfig = (
            VoiceAgentConfigResolver(baseUrlOverride = "")
                .resolve(changedAccount, conversation) as VoiceAgentConfigResult.Available
            ).config
        assertTrue(
            config.directAccountConfigurationHash != changedConfig.directAccountConfigurationHash,
        )
    }

    @Test
    fun `uses Hermes Voice base URL and voice model overrides from custom headers`() {
        val assistantId = Uuid.random()
        val modelId = Uuid.random()
        val model = Model(
            id = modelId,
            modelId = "hermes-agent",
            displayName = "Hermes Agent",
            customHeaders = listOf(
                CustomHeader(
                    "X-Hermes-Voice-Base-Url",
                    "https://dev-remote-machine-1.tail83108.ts.net:9447/api/mobile/",
                ),
                CustomHeader("X-Voice-Agent-Model-Id", "gemini-live-2.5-flash-preview"),
            ),
        )
        val settings = Settings(
            assistantId = assistantId,
            chatModelId = modelId,
            assistants = listOf(Assistant(id = assistantId)),
            providers = listOf(
                ProviderSetting.OpenAI(
                    name = "RMS Hermes",
                    apiKey = "profile-api-key",
                    baseUrl = "https://dev-remote-machine-1.tail83108.ts.net:8642/v1",
                    models = listOf(model),
                )
            ),
        )
        val conversation = Conversation.ofId(id = Uuid.random(), assistantId = assistantId)

        val result = VoiceAgentConfigResolver(baseUrlOverride = "")
            .resolve(settings = settings, conversation = conversation)

        assertTrue(result is VoiceAgentConfigResult.Available)
        val config = (result as VoiceAgentConfigResult.Available).config
        assertEquals("https://dev-remote-machine-1.tail83108.ts.net:9447", config.hermesVoiceBaseUrl)
        assertEquals("profile-api-key", config.credentials.deviceApiKey)
        assertEquals("gemini-live-2.5-flash-preview", config.voiceModelId)
    }

    @Test
    fun `passes paired Cloudflare Access headers to Hermes Voice without forwarding unrelated headers`() {
        val assistantId = Uuid.random()
        val modelId = Uuid.random()
        val model = Model(
            id = modelId,
            modelId = "hermes-agent",
            displayName = "Hermes Agent",
            customHeaders = listOf(
                CustomHeader("CF-Access-Client-Id", "voice-client-id"),
                CustomHeader("CF-Access-Client-Secret", "voice-client-secret"),
                CustomHeader("X-Unrelated-Provider-Secret", "must-not-forward"),
            ),
        )
        val settings = Settings(
            assistantId = assistantId,
            chatModelId = modelId,
            assistants = listOf(Assistant(id = assistantId)),
            providers = listOf(
                ProviderSetting.OpenAI(
                    name = "RMS Hermes",
                    apiKey = "profile-api-key",
                    baseUrl = "https://muly-hermes-api.example.test/v1",
                    models = listOf(model),
                )
            ),
        )
        val conversation = Conversation.ofId(id = Uuid.random(), assistantId = assistantId)

        val config = (
            VoiceAgentConfigResolver(baseUrlOverride = "")
                .resolve(settings = settings, conversation = conversation) as VoiceAgentConfigResult.Available
            ).config

        assertEquals(
            HermesVoiceCredentials(
                deviceApiKey = "profile-api-key",
                cloudflareAccess = HermesVoiceCloudflareAccessCredentials(
                    clientId = "voice-client-id",
                    clientSecret = "voice-client-secret",
                ),
            ),
            config.credentials,
        )

        val changedModel = model.copy(
            customHeaders = model.customHeaders.map {
                if (it.name == "CF-Access-Client-Secret") it.copy(value = "changed-secret") else it
            },
        )
        val changedSettings = settings.copy(
            providers = listOf(
                (settings.providers.single() as ProviderSetting.OpenAI).copy(models = listOf(changedModel))
            ),
        )
        val changedConfig = (
            VoiceAgentConfigResolver(baseUrlOverride = "")
                .resolve(settings = changedSettings, conversation = conversation) as VoiceAgentConfigResult.Available
            ).config
        assertTrue(config.directAccountConfigurationHash != changedConfig.directAccountConfigurationHash)
    }

    @Test
    fun `rejects an incomplete Cloudflare Access header pair`() {
        val assistantId = Uuid.random()
        val modelId = Uuid.random()
        val model = Model(
            id = modelId,
            modelId = "hermes-agent",
            displayName = "Hermes Agent",
            customHeaders = listOf(CustomHeader("CF-Access-Client-Id", "voice-client-id")),
        )
        val settings = Settings(
            assistantId = assistantId,
            chatModelId = modelId,
            assistants = listOf(Assistant(id = assistantId)),
            providers = listOf(
                ProviderSetting.OpenAI(
                    name = "RMS Hermes",
                    apiKey = "profile-api-key",
                    baseUrl = "https://muly-hermes-api.example.test/v1",
                    models = listOf(model),
                )
            ),
        )

        val result = VoiceAgentConfigResolver(baseUrlOverride = "")
            .resolve(settings, Conversation.ofId(Uuid.random(), assistantId))

        assertTrue(result is VoiceAgentConfigResult.Unavailable)
    }

    @Test
    fun `keeps production Hermes API host for proxied Voice Agent routes`() {
        val assistantId = Uuid.random()
        val modelId = Uuid.random()
        val model = Model(
            id = modelId,
            modelId = "hermes-agent",
            displayName = "Hermes Agent",
        )
        val settings = Settings(
            assistantId = assistantId,
            chatModelId = modelId,
            assistants = listOf(Assistant(id = assistantId)),
            providers = listOf(
                ProviderSetting.OpenAI(
                    name = "RMS Hermes",
                    apiKey = "profile-api-key",
                    baseUrl = "https://muly-hermes-api.core8.co/v1",
                    models = listOf(model),
                )
            ),
        )
        val conversation = Conversation.ofId(id = Uuid.random(), assistantId = assistantId)

        val result = VoiceAgentConfigResolver(baseUrlOverride = "")
            .resolve(settings = settings, conversation = conversation)

        assertTrue(result is VoiceAgentConfigResult.Available)
        val config = (result as VoiceAgentConfigResult.Available).config
        assertEquals("https://muly-hermes-api.core8.co", config.hermesVoiceBaseUrl)
    }

    @Test
    fun `uses only the provider bearer credential when custom headers are absent`() {
        val assistantId = Uuid.random()
        val modelId = Uuid.random()
        val model = Model(
            id = modelId,
            modelId = "hermes-agent",
            displayName = "Hermes Agent",
        )
        val settings = Settings(
            assistantId = assistantId,
            chatModelId = modelId,
            assistants = listOf(Assistant(id = assistantId)),
            providers = listOf(
                ProviderSetting.OpenAI(
                    name = "RMS Hermes",
                    apiKey = "profile-api-key",
                    baseUrl = "https://muly-hermes-api.example.test/v1",
                    models = listOf(model),
                )
            ),
        )
        val conversation = Conversation.ofId(id = Uuid.random(), assistantId = assistantId)

        val result = VoiceAgentConfigResolver(baseUrlOverride = "")
            .resolve(settings = settings, conversation = conversation)

        assertTrue(result is VoiceAgentConfigResult.Available)
        val config = (result as VoiceAgentConfigResult.Available).config
        assertEquals("https://muly-hermes-api.example.test", config.hermesVoiceBaseUrl)
        assertEquals("profile-api-key", config.credentials.deviceApiKey)
    }

    @Test
    fun `uses explicit build base URL override when custom header is absent`() {
        val assistantId = Uuid.random()
        val modelId = Uuid.random()
        val model = Model(
            id = modelId,
            modelId = "hermes-agent",
            displayName = "Hermes Agent",
        )
        val settings = Settings(
            assistantId = assistantId,
            chatModelId = modelId,
            assistants = listOf(Assistant(id = assistantId)),
            providers = listOf(
                ProviderSetting.OpenAI(
                    name = "RMS Hermes",
                    apiKey = "profile-api-key",
                    baseUrl = "https://muly-hermes-api.example.test/v1",
                    models = listOf(model),
                )
            ),
        )
        val conversation = Conversation.ofId(id = Uuid.random(), assistantId = assistantId)

        val result = VoiceAgentConfigResolver(
            baseUrlOverride = "http://100.83.49.15:18787/api/mobile",
        ).resolve(settings = settings, conversation = conversation)

        assertTrue(result is VoiceAgentConfigResult.Available)
        val config = (result as VoiceAgentConfigResult.Available).config
        assertEquals("http://100.83.49.15:18787", config.hermesVoiceBaseUrl)
    }

    @Test
    fun `model custom headers override assistant Voice Agent headers`() {
        val assistantId = Uuid.random()
        val modelId = Uuid.random()
        val model = Model(
            id = modelId,
            modelId = "hermes-agent",
            displayName = "Hermes Agent",
            customHeaders = listOf(
                CustomHeader("X-Hermes-Voice-Base-Url", "https://model-hermes-voice.example.test"),
                CustomHeader("X-Voice-Agent-Model-Id", "model-voice"),
            ),
        )
        val settings = Settings(
            assistantId = assistantId,
            chatModelId = modelId,
            assistants = listOf(
                Assistant(
                    id = assistantId,
                    customHeaders = listOf(
                        CustomHeader("X-Hermes-Voice-Base-Url", "https://assistant-hermes-voice.example.test"),
                        CustomHeader("X-Voice-Agent-Model-Id", "assistant-voice"),
                    ),
                )
            ),
            providers = listOf(
                ProviderSetting.OpenAI(
                    name = "RMS Hermes",
                    apiKey = "profile-api-key",
                    baseUrl = "https://muly-hermes-api.example.test/v1",
                    models = listOf(model),
                )
            ),
        )
        val conversation = Conversation.ofId(id = Uuid.random(), assistantId = assistantId)

        val result = VoiceAgentConfigResolver(baseUrlOverride = "")
            .resolve(settings = settings, conversation = conversation)

        assertTrue(result is VoiceAgentConfigResult.Available)
        val config = (result as VoiceAgentConfigResult.Available).config
        assertEquals("https://model-hermes-voice.example.test", config.hermesVoiceBaseUrl)
        assertEquals("model-voice", config.voiceModelId)
    }

    @Test
    fun `reports a clear error when the current provider is not OpenAI compatible`() {
        val assistantId = Uuid.random()
        val modelId = Uuid.random()
        val model = Model(id = modelId, modelId = "gemini-pro", displayName = "Gemini")
        val settings = Settings(
            assistantId = assistantId,
            chatModelId = modelId,
            assistants = listOf(Assistant(id = assistantId)),
            providers = listOf(
                ProviderSetting.Google(
                    apiKey = "google-key",
                    models = listOf(model),
                )
            ),
        )
        val conversation = Conversation.ofId(id = Uuid.random(), assistantId = assistantId)

        val result = VoiceAgentConfigResolver(baseUrlOverride = "")
            .resolve(settings = settings, conversation = conversation)

        assertTrue(result is VoiceAgentConfigResult.Unavailable)
        assertTrue((result as VoiceAgentConfigResult.Unavailable).message.contains("OpenAI-compatible"))
    }

    @Test
    fun `rejects a blank bearer credential`() {
        val assistantId = Uuid.random()
        val modelId = Uuid.random()
        val model = Model(id = modelId, modelId = "hermes-agent", displayName = "Hermes Agent")
        val settings = Settings(
            assistantId = assistantId,
            chatModelId = modelId,
            assistants = listOf(Assistant(id = assistantId)),
            providers = listOf(
                ProviderSetting.OpenAI(
                    name = "RMS Hermes",
                    apiKey = " ",
                    baseUrl = "http://100.83.49.15:8642/v1",
                    models = listOf(model),
                )
            ),
        )

        val result = VoiceAgentConfigResolver(baseUrlOverride = "")
            .resolve(settings, Conversation.ofId(Uuid.random(), assistantId))

        assertTrue(result is VoiceAgentConfigResult.Unavailable)
    }

    @Test
    fun `rejects a blank Hermes origin`() {
        val assistantId = Uuid.random()
        val modelId = Uuid.random()
        val model = Model(id = modelId, modelId = "hermes-agent", displayName = "Hermes Agent")
        val settings = Settings(
            assistantId = assistantId,
            chatModelId = modelId,
            assistants = listOf(Assistant(id = assistantId)),
            providers = listOf(
                ProviderSetting.OpenAI(
                    name = "RMS Hermes",
                    apiKey = "device-key",
                    baseUrl = " ",
                    models = listOf(model),
                )
            ),
        )

        val result = VoiceAgentConfigResolver(baseUrlOverride = "")
            .resolve(settings, Conversation.ofId(Uuid.random(), assistantId))

        assertTrue(result is VoiceAgentConfigResult.Unavailable)
    }
}
