package me.rerere.rikkahub.ui.pages.setting.components

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class CodexProviderConfigureTest {
    @Test
    fun `model refresh preserves local settings and missing models`() {
        val existingId = Uuid.random()
        val existing = Model(
            modelId = "gpt-5-codex",
            displayName = "My Codex",
            id = existingId,
            type = ModelType.EMBEDDING,
            customHeaders = listOf(CustomHeader("X-Test", "value")),
            customBodies = listOf(CustomBody("test", JsonPrimitive(true))),
            inputModalities = listOf(Modality.TEXT),
            abilities = emptyList(),
            tools = setOf(BuiltInTools.Search),
        )
        val missing = Model(modelId = "locally-kept", displayName = "Local model")
        val refreshed = Model(
            modelId = "gpt-5-codex",
            displayName = "Updated name",
            inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
            abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
        )
        val added = Model(modelId = "gpt-5.1-codex")

        val merged = mergeCodexModels(
            existing = listOf(existing, missing),
            refreshed = listOf(refreshed, added),
        )

        assertEquals(existingId, merged[0].id)
        assertEquals("My Codex", merged[0].displayName)
        assertEquals(ModelType.EMBEDDING, merged[0].type)
        assertEquals(existing.customHeaders, merged[0].customHeaders)
        assertEquals(existing.customBodies, merged[0].customBodies)
        assertEquals(existing.tools, merged[0].tools)
        assertEquals(refreshed.inputModalities, merged[0].inputModalities)
        assertEquals(refreshed.abilities, merged[0].abilities)
        assertEquals(missing, merged[1])
        assertEquals(added, merged[2])
    }
}
