package me.rerere.mediagen.provider

import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaGenerationRequest
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.providers.aliyun.AliyunImageGeneration
import me.rerere.mediagen.provider.providers.aliyun.AliyunVideoGeneration
import me.rerere.mediagen.provider.providers.minimax.MiniMaxMediaGenerationProvider
import me.rerere.mediagen.provider.providers.openai.OpenAIMediaGenerationProvider
import me.rerere.mediagen.provider.providers.openrouter.OpenRouterImageGeneration
import me.rerere.mediagen.provider.providers.openrouter.OpenRouterVideoGeneration
import me.rerere.mediagen.provider.providers.volcengine.VolcengineImageGeneration
import me.rerere.mediagen.provider.providers.volcengine.VolcengineVideoGeneration
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaGenerationCapabilitiesTest {
    private val client = OkHttpClient()

    private val settings = listOf(
        MediaGenerationProviderSetting.OpenAI(),
        MediaGenerationProviderSetting.Aliyun(),
        MediaGenerationProviderSetting.Volcengine(),
        MediaGenerationProviderSetting.MiniMax(),
        MediaGenerationProviderSetting.OpenRouter(),
    )

    // 各适配器组装请求体的入口，不支持的公共字段会在这里被拒绝
    private fun buildBody(setting: MediaGenerationProviderSetting, kind: MediaKind, request: MediaGenerationRequest) {
        val model = MediaGenerationModel(modelId = "model", kind = kind)
        when (setting) {
            is MediaGenerationProviderSetting.OpenAI ->
                OpenAIMediaGenerationProvider(client).buildGenerationBody(setting, model, request)

            is MediaGenerationProviderSetting.Aliyun -> when (kind) {
                MediaKind.IMAGE -> AliyunImageGeneration(client).buildBody(model, request)
                MediaKind.VIDEO -> AliyunVideoGeneration(client).buildCreateBody(model, request)
            }

            is MediaGenerationProviderSetting.Volcengine -> when (kind) {
                MediaKind.IMAGE -> VolcengineImageGeneration(client).buildBody(model, request)
                MediaKind.VIDEO -> VolcengineVideoGeneration(client).buildCreateBody(model, request)
            }

            is MediaGenerationProviderSetting.MiniMax ->
                MiniMaxMediaGenerationProvider(client).buildCreateBody(model, request)

            is MediaGenerationProviderSetting.OpenRouter -> when (kind) {
                MediaKind.IMAGE -> OpenRouterImageGeneration(client).buildBody(model, request)
                MediaKind.VIDEO -> OpenRouterVideoGeneration(client).buildCreateBody(model, request)
            }
        }
    }

    private fun requestWith(parameter: MediaGenerationParameter): MediaGenerationRequest = when (parameter) {
        MediaGenerationParameter.COUNT -> MediaGenerationRequest(prompt = "cat", count = 2)
        MediaGenerationParameter.RESOLUTION -> MediaGenerationRequest(prompt = "cat", resolution = "1024x1024")
        MediaGenerationParameter.ASPECT_RATIO -> MediaGenerationRequest(prompt = "cat", aspectRatio = "16:9")
        MediaGenerationParameter.DURATION -> MediaGenerationRequest(prompt = "cat", durationSeconds = 5)
        MediaGenerationParameter.GENERATE_AUDIO -> MediaGenerationRequest(prompt = "cat", generateAudio = true)
        MediaGenerationParameter.WATERMARK -> MediaGenerationRequest(prompt = "cat", watermark = false)
        MediaGenerationParameter.SEED -> MediaGenerationRequest(prompt = "cat", seed = 1)
        MediaGenerationParameter.PROMPT_ENHANCEMENT -> MediaGenerationRequest(prompt = "cat", promptEnhancement = true)
    }

    @Test
    fun capabilitiesExistExactlyForSupportedKinds() {
        settings.forEach { setting ->
            val declared = MediaKind.entries.filter { setting.capabilities(it) != null }.toSet()
            assertEquals(setting.name, setting.supportedKinds, declared)
        }
    }

    @Test
    fun adaptersAcceptDeclaredParametersAndRejectTheRest() {
        settings.forEach { setting ->
            setting.supportedKinds.forEach { kind ->
                val capabilities = setting.capabilities(kind)
                assertNotNull(capabilities)
                MediaGenerationParameter.entries.forEach { parameter ->
                    val request = requestWith(parameter)
                    if (parameter in capabilities!!.parameters) {
                        buildBody(setting, kind, request)
                    } else {
                        assertThrows("${setting.name} $kind $parameter", IllegalArgumentException::class.java) {
                            buildBody(setting, kind, request)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun requiredParametersAreSupported() {
        settings.forEach { setting ->
            setting.supportedKinds.forEach { kind ->
                val capabilities = setting.capabilities(kind)!!
                assertTrue(
                    "${setting.name} $kind",
                    capabilities.parameters.containsAll(capabilities.requiredParameters),
                )
            }
        }
    }
}
