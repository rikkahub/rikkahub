package me.rerere.ai

import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.providers.codexImageModels
import me.rerere.ai.provider.providers.extractCodexImageResults
import me.rerere.ai.provider.providers.parseCodexResponsesSse
import me.rerere.ai.util.HttpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexBackendParseTest {

    @Test
    fun `parseCodexResponsesSse builds the answer and collects opened urls + queries`() {
        val raw = """
            event: response.output_text.delta
            data: {"type":"response.output_text.delta","delta":"Hello "}

            data: {"type":"response.output_text.delta","delta":"world [src](https://example.com/a)"}

            data: {"type":"response.output_item.done","item":{"type":"web_search_call","action":{"type":"search","url":"https://example.com/b","queries":["q1","q2"]}}}

            data: {"type":"response.completed","response":{"usage":{}}}

            data: [DONE]
        """.trimIndent()

        val parsed = parseCodexResponsesSse(raw)
        assertEquals("Hello world [src](https://example.com/a)", parsed.answer)
        assertEquals(listOf("https://example.com/b"), parsed.opened)
        assertEquals(listOf("q1", "q2"), parsed.queries)
    }

    @Test
    fun `parseCodexResponsesSse dedupes opened urls and queries`() {
        val raw = """
            data: {"type":"response.output_item.done","item":{"type":"web_search_call","action":{"url":"https://x.com","queries":["q"]}}}

            data: {"type":"response.output_item.done","item":{"type":"web_search_call","action":{"url":"https://x.com","queries":["q","r"]}}}
        """.trimIndent()

        val parsed = parseCodexResponsesSse(raw)
        assertEquals(listOf("https://x.com"), parsed.opened)
        assertEquals(listOf("q", "r"), parsed.queries)
    }

    @Test
    fun `parseCodexResponsesSse throws on an error event embedded in a 200 stream`() {
        val raw = """data: {"type":"error","message":"boom"}"""
        val ex = assertThrows(HttpException::class.java) { parseCodexResponsesSse(raw) }
        assertTrue(ex.message!!.contains("boom"))
    }

    @Test
    fun `extractCodexImageResults pulls the base64 result of each image_generation_call`() {
        val raw = """
            data: {"type":"response.output_item.added","item":{"type":"image_generation_call","id":"ig_1"}}

            data: {"type":"response.output_item.done","item":{"type":"image_generation_call","id":"ig_1","result":"QUJD"}}

            data: [DONE]
        """.trimIndent()

        assertEquals(listOf("QUJD"), extractCodexImageResults(raw))
    }

    @Test
    fun `codex image catalog is one IMAGE model with a stable id`() {
        val models = codexImageModels()
        assertEquals(1, models.size)
        val m = models.single()
        assertEquals(ModelType.IMAGE, m.type)
        assertTrue(Modality.IMAGE in m.outputModalities)
        // Stable id across calls — so a selected imageGenerationModelId keeps resolving.
        assertEquals(m.id, codexImageModels().single().id)
    }
}
