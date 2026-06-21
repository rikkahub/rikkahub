package me.rerere.ai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.providers.ANTIGRAVITY_IMAGE_MODEL_ID
import me.rerere.ai.provider.providers.antigravityImageModels
import me.rerere.ai.provider.providers.buildAntigravityEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AntigravityImageWebTest {

    @Test
    fun `image catalog is one IMAGE model with a stable id`() {
        val models = antigravityImageModels()
        assertEquals(1, models.size)
        val m = models.single()
        assertEquals(ANTIGRAVITY_IMAGE_MODEL_ID, m.modelId)
        assertEquals(ModelType.IMAGE, m.type)
        assertTrue(Modality.IMAGE in m.outputModalities)
        // Stable id across calls — so a selected imageGenerationModelId keeps resolving.
        assertEquals(m.id, antigravityImageModels().single().id)
    }

    @Test
    fun `envelope carries the requestType verbatim and strips safetySettings`() {
        val inner = buildJsonObject {
            put("contents", buildJsonObject { put("x", 1) })
            put("safetySettings", buildJsonObject { put("y", 2) })
        }
        val env = buildAntigravityEnvelope(inner, "gemini-3.1-flash-image", "proj-123", "image_gen", "ua")

        assertEquals("image_gen", env["requestType"]!!.jsonPrimitive.content)
        assertEquals("gemini-3.1-flash-image", env["model"]!!.jsonPrimitive.content)
        assertEquals("proj-123", env["project"]!!.jsonPrimitive.content)
        assertEquals("ua", env["userAgent"]!!.jsonPrimitive.content)
        assertTrue(env["requestId"]!!.jsonPrimitive.content.startsWith("image_gen/"))
        // safetySettings is dropped from the wrapped request; contents survives.
        assertNull(env["request"]!!.jsonObject["safetySettings"])
        assertFalse(env["request"]!!.jsonObject["contents"] == null)
    }

    @Test
    fun `envelope web_search prefix`() {
        val env = buildAntigravityEnvelope(buildJsonObject { }, "gemini-3.1-flash-lite", "p", "web_search", "ua")
        assertEquals("web_search", env["requestType"]!!.jsonPrimitive.content)
        assertTrue(env["requestId"]!!.jsonPrimitive.content.startsWith("web_search/"))
    }
}
