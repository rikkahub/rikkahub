package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterModelParseTest {
    private val imageToolModel = """
        {
          "id": "google/gemini-2.5-flash-image",
          "name": "Gemini 2.5 Flash Image",
          "context_length": 1048576,
          "architecture": {
            "input_modalities": ["image", "text"],
            "output_modalities": ["image", "text"]
          },
          "supported_parameters": ["tools", "tool_choice", "reasoning"],
          "pricing": { "prompt": "0.0000003", "completion": "0.0000025" }
        }
    """.trimIndent()

    @Test
    fun parses_image_tool_reasoning_and_pricing() {
        val obj = Json.parseToJsonElement(imageToolModel).jsonObject
        val m = openRouterModelFromJson(obj)!!
        assertEquals("google/gemini-2.5-flash-image", m.modelId)
        assertEquals("Gemini 2.5 Flash Image", m.displayName)
        assertTrue(Modality.IMAGE in m.outputModalities)
        assertTrue(Modality.IMAGE in m.inputModalities)
        assertTrue(ModelAbility.TOOL in m.abilities)
        assertTrue(ModelAbility.REASONING in m.abilities)
        assertEquals(1048576, m.contextLength)
        assertEquals(0.0000003, m.pricePromptPerToken!!, 1e-12)
    }

    @Test
    fun text_only_model_has_no_image_or_tool() {
        val json = """
            {"id":"x/text-only","name":"Text Only",
             "architecture":{"input_modalities":["text"],"output_modalities":["text"]},
             "supported_parameters":["max_tokens","temperature"]}
        """.trimIndent()
        val m = openRouterModelFromJson(Json.parseToJsonElement(json).jsonObject)!!
        assertTrue(Modality.IMAGE !in m.outputModalities)
        assertTrue(ModelAbility.TOOL !in m.abilities)
    }
}
