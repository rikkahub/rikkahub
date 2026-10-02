package me.rerere.mediagen.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaGenerationProviderSettingTest {
    @Test
    fun settingKeepsImageAndVideoModelsThroughSerialization() {
        val setting: MediaGenerationProviderSetting = MediaGenerationProviderSetting.Volcengine(
            apiKey = "key",
            models = listOf(
                MediaGenerationModel(modelId = "doubao-seedance-2-0-260128", kind = MediaKind.VIDEO),
                MediaGenerationModel(modelId = "doubao-seedream-5-0", kind = MediaKind.IMAGE, displayName = "Seedream"),
            ),
        )

        val encoded = Json.encodeToString(MediaGenerationProviderSetting.serializer(), setting)
        val decoded = Json.decodeFromString(MediaGenerationProviderSetting.serializer(), encoded)

        assertEquals(setting, decoded)
        assertEquals(listOf(MediaKind.VIDEO, MediaKind.IMAGE), decoded.models.map { it.kind })
        // supportedKinds 描述的是适配器，不随配置保存
        assertFalse("supportedKinds" in Json.parseToJsonElement(encoded).jsonObject)
    }

    @Test
    fun copyProviderReplacesModelsAndKeepsCredentials() {
        val setting = MediaGenerationProviderSetting.OpenAI(apiKey = "sk-test")
        val models = setting.models + MediaGenerationModel(modelId = "gpt-image-1", kind = MediaKind.IMAGE)

        val copy = setting.copyProvider(name = "中转", models = models)

        assertTrue(copy is MediaGenerationProviderSetting.OpenAI)
        assertEquals(setting.id, copy.id)
        assertEquals("sk-test", copy.apiKey)
        assertEquals("中转", copy.name)
        assertEquals(listOf("gpt-image-2", "gpt-image-1"), copy.models.map { it.modelId })
    }
}
