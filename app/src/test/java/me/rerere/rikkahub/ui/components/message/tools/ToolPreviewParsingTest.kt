package me.rerere.rikkahub.ui.components.message.tools

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for the parsing / JSON-argument helpers extracted out of
 * ChatMessageTools.kt into ToolPreviewParsing.kt (issue #106). These lock the exact
 * behaviour previously inlined in the tool-step composables so the mechanical extraction
 * is proven behaviour-preserving.
 */
class ToolPreviewParsingTest {

    // ---- getStringContent ----

    @Test
    fun `getStringContent returns nested string value`() {
        val element = buildJsonObject { put("query", JsonPrimitive("hello")) }
        assertEquals("hello", element.getStringContent("query"))
    }

    @Test
    fun `getStringContent returns null on missing key`() {
        val element = buildJsonObject { put("query", JsonPrimitive("hello")) }
        assertNull(element.getStringContent("answer"))
    }

    @Test
    fun `getStringContent returns null on non-object element`() {
        val element = JsonPrimitive("not-an-object")
        assertNull(element.getStringContent("query"))
    }

    @Test
    fun `getStringContent returns null on null receiver`() {
        val element: kotlinx.serialization.json.JsonElement? = null
        assertNull(element.getStringContent("query"))
    }

    // ---- parseAskUserQuestions ----

    @Test
    fun `parseAskUserQuestions parses fields and options`() {
        val arguments = buildJsonObject {
            put("questions", buildJsonArray {
                add(buildJsonObject {
                    put("id", JsonPrimitive("q1"))
                    put("question", JsonPrimitive("Pick one"))
                    put("options", buildJsonArray {
                        add(JsonPrimitive("a"))
                        add(JsonPrimitive("b"))
                    })
                    put("selection_type", JsonPrimitive("single"))
                })
            })
        }
        val result = parseAskUserQuestions(arguments)
        assertEquals(1, result.size)
        assertEquals("q1", result[0].id)
        assertEquals("Pick one", result[0].question)
        assertEquals(listOf("a", "b"), result[0].options)
        assertEquals("single", result[0].selectionType)
    }

    @Test
    fun `parseAskUserQuestions defaults selectionType to text when absent`() {
        val arguments = buildJsonObject {
            put("questions", buildJsonArray {
                add(buildJsonObject {
                    put("id", JsonPrimitive("q1"))
                    put("question", JsonPrimitive("Free text?"))
                })
            })
        }
        val result = parseAskUserQuestions(arguments)
        assertEquals(1, result.size)
        assertEquals("text", result[0].selectionType)
        assertEquals(emptyList<String>(), result[0].options)
    }

    @Test
    fun `parseAskUserQuestions returns emptyList when questions key missing`() {
        val arguments = buildJsonObject { put("other", JsonPrimitive("x")) }
        assertTrue(parseAskUserQuestions(arguments).isEmpty())
    }

    @Test
    fun `parseAskUserQuestions returns emptyList on malformed questions`() {
        // questions is a primitive, not an array -> jsonArray throws -> getOrElse path
        val arguments = buildJsonObject { put("questions", JsonPrimitive("nope")) }
        assertTrue(parseAskUserQuestions(arguments).isEmpty())
    }

    // ---- buildAskUserAnswerPayload ----

    @Test
    fun `buildAskUserAnswerPayload uses text answers for non-multi`() {
        val questions = listOf(
            AskUserQuestion(id = "q1", question = "Q1", options = emptyList(), selectionType = "text"),
            AskUserQuestion(id = "q2", question = "Q2", options = emptyList(), selectionType = "single"),
        )
        val payload = buildAskUserAnswerPayload(
            questions = questions,
            answers = mapOf("q1" to "hello", "q2" to "a"),
            multiAnswers = emptyMap(),
        )
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(payload).jsonObject
        val answersObj = parsed["answers"]!!.jsonObject
        assertEquals("hello", answersObj["q1"]!!.jsonPrimitive.content)
        assertEquals("a", answersObj["q2"]!!.jsonPrimitive.content)
    }

    @Test
    fun `buildAskUserAnswerPayload comma-joins multi answers`() {
        val questions = listOf(
            AskUserQuestion(id = "q1", question = "Q1", options = emptyList(), selectionType = "multi"),
        )
        val payload = buildAskUserAnswerPayload(
            questions = questions,
            answers = emptyMap(),
            multiAnswers = mapOf("q1" to linkedSetOf("a", "b", "c")),
        )
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(payload).jsonObject
        assertEquals("a, b, c", parsed["answers"]!!.jsonObject["q1"]!!.jsonPrimitive.content)
    }

    @Test
    fun `buildAskUserAnswerPayload defaults to empty string for missing answers`() {
        val questions = listOf(
            AskUserQuestion(id = "q1", question = "Q1", options = emptyList(), selectionType = "text"),
            AskUserQuestion(id = "q2", question = "Q2", options = emptyList(), selectionType = "multi"),
        )
        val payload = buildAskUserAnswerPayload(
            questions = questions,
            answers = emptyMap(),
            multiAnswers = emptyMap(),
        )
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(payload).jsonObject
        val answersObj = parsed["answers"]!!.jsonObject
        assertEquals("", answersObj["q1"]!!.jsonPrimitive.content)
        assertEquals("", answersObj["q2"]!!.jsonPrimitive.content)
    }

    // ---- extractAskUserAnsweredText ----

    @Test
    fun `extractAskUserAnsweredText returns per-question answer`() {
        val raw = buildJsonObject {
            put("answers", buildJsonObject {
                put("q1", JsonPrimitive("the answer"))
            })
        }.toString()
        assertEquals("the answer", extractAskUserAnsweredText(raw, "q1"))
    }

    @Test
    fun `extractAskUserAnsweredText falls back to raw on unparseable json`() {
        val raw = "not json at all"
        assertEquals(raw, extractAskUserAnsweredText(raw, "q1"))
    }

    @Test
    fun `extractAskUserAnsweredText falls back to raw when key absent`() {
        val raw = buildJsonObject {
            put("answers", buildJsonObject {
                put("other", JsonPrimitive("x"))
            })
        }.toString()
        assertEquals(raw, extractAskUserAnsweredText(raw, "q1"))
    }
}
