package me.rerere.rikkahub.data.datastore.migration

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.common.json.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROPERTY #5 — CONSERVATION over [migrateAssistantsJson] (PreferenceStoreV2Migration.kt).
 *
 * The migration rewrites ONLY the part `type` string token (canonicalizing legacy / FQ class names
 * to a lowercase form). It must NEITHER LOSE NOR DUPLICATE any carried item. This is strictly
 * stronger than the idempotence proven in PR #34: a lossy-but-stable migration still satisfies
 * idempotence but FAILS conservation.
 *
 * Invariants asserted across input vs migrated (both reparsed as JsonElement):
 *   1. assistant count conserved.
 *   2. per-assistant presetMessages count conserved.
 *   3. per-message parts count conserved.
 *   4. RECURSIVE total part count (including nested Tool.output parts) conserved.
 *   5. NO content loss: every part keeps its non-`type` keys verbatim, and each migrated `type` is
 *      the canonical mapping value (lowercase) or the original token if unmapped — never dropped.
 *   6. never throws (getOrElse returns the input).
 */
class MigrateAssistantsConservationPropertyTest {

    // Legacy + already-migrated part type tokens the mapping accepts (mirrors MigrationIdempotenceTest).
    private val partTypeTokens = listOf(
        "Text", "UIMessagePart.Text", "me.rerere.ai.ui.UIMessagePart.Text", "text",
        "Image", "UIMessagePart.Image", "image",
        "Tool", "UIMessagePart.Tool", "me.rerere.ai.ui.UIMessagePart.Tool", "tool",
        "ToolResult", "UIMessagePart.ToolResult", "tool_result",
    )

    private val arbPartType: Arb<String> = Arb.element(partTypeTokens)

    private fun part(type: String, withOutput: Boolean): JsonObject = JsonObject(
        buildMap {
            put("type", JsonPrimitive(type))
            put("text", JsonPrimitive("sample"))
            if (withOutput) {
                put(
                    "output",
                    JsonArray(
                        listOf(
                            JsonObject(mapOf("type" to JsonPrimitive("UIMessagePart.Text"), "text" to JsonPrimitive("o"))),
                            JsonObject(mapOf("type" to JsonPrimitive("Text"), "text" to JsonPrimitive("o2"))),
                        )
                    )
                )
            }
        }
    )

    private val arbPart: Arb<JsonObject> = arbitrary {
        val type = arbPartType.bind()
        val withOutput = type.contains("Tool", ignoreCase = true) && Arb.int(0..1).bind() == 1
        part(type, withOutput)
    }

    private val arbMessage: Arb<JsonObject> = arbitrary {
        JsonObject(
            mapOf(
                "role" to JsonPrimitive(Arb.element(listOf("user", "assistant")).bind()),
                "parts" to JsonArray(Arb.list(arbPart, 0..4).bind()),
            )
        )
    }

    private val arbAssistant: Arb<JsonObject> = arbitrary {
        JsonObject(
            buildMap {
                put("id", JsonPrimitive(Arb.string(4..10).bind()))
                put("name", JsonPrimitive(Arb.string(0..10).bind()))
                put("presetMessages", JsonArray(Arb.list(arbMessage, 0..3).bind()))
            }
        )
    }

    private val arbAssistantsArray: Arb<JsonArray> = arbitrary {
        JsonArray(Arb.list(arbAssistant, 0..3).bind())
    }

    // The set of canonical (post-migration) lowercase type values.
    private val canonicalTypes = setOf("text", "image", "video", "audio", "document", "reasoning", "search", "tool_call", "tool_result", "tool")

    /** Recursively count every part object (a part = an object holding a `type`), descending into `output` arrays. */
    private fun countParts(parts: JsonArray): Int {
        var total = 0
        for (p in parts) {
            val obj = p as? JsonObject ?: continue
            if (obj["type"] == null) continue
            total += 1
            (obj["output"] as? JsonArray)?.let { total += countParts(it) }
        }
        return total
    }

    /** All non-`type` keys of every part, recursively — used to assert no surrounding data is dropped. */
    private fun assertNonTypeKeysPreserved(before: JsonArray, after: JsonArray) {
        assertEquals(before.size, after.size)
        for (i in before.indices) {
            val b = before[i] as? JsonObject ?: continue
            val a = after[i] as JsonObject
            val bKeys = b.keys.filter { it != "type" && it != "output" }
            val aKeys = a.keys.filter { it != "type" && it != "output" }
            assertEquals("non-type key SET changed for part $i", bKeys.toSet(), aKeys.toSet())
            for (key in bKeys) {
                assertEquals("value of '$key' changed for part $i", b[key], a[key])
            }
            // Migrated type must be a canonical lowercase value (the mapping covers every generated token).
            val migratedType = a["type"]?.jsonPrimitive?.contentOrNull
            assertTrue("migrated type '$migratedType' is not canonical", migratedType in canonicalTypes)
            val bOut = b["output"] as? JsonArray
            val aOut = a["output"] as? JsonArray
            if (bOut != null) {
                assertTrue("output array dropped for part $i", aOut != null)
                assertNonTypeKeysPreserved(bOut, aOut!!)
            } else {
                assertEquals("output array spuriously added for part $i", null, aOut)
            }
        }
    }

    private fun assistantsOf(root: JsonElement): List<JsonObject> =
        (root as JsonArray).map { it as JsonObject }

    private fun presetMessagesOf(assistant: JsonObject): List<JsonObject> =
        (assistant["presetMessages"] as JsonArray).map { it as JsonObject }

    private fun partsOf(message: JsonObject): JsonArray = message["parts"] as JsonArray

    @Test
    fun `migrateAssistantsJson conserves every assistant, message and part`() {
        runBlocking {
            checkAll(200, arbAssistantsArray) { assistants ->
                val input = JsonInstant.encodeToString(assistants)
                val migratedJson = migrateAssistantsJson(input) // must never throw
                val before = JsonInstant.parseToJsonElement(input)
                val after = JsonInstant.parseToJsonElement(migratedJson)

                val beforeAssistants = assistantsOf(before)
                val afterAssistants = assistantsOf(after)

                // (1) assistant count conserved.
                assertEquals("assistant count changed", beforeAssistants.size, afterAssistants.size)

                for (ai in beforeAssistants.indices) {
                    val bMsgs = presetMessagesOf(beforeAssistants[ai])
                    val aMsgs = presetMessagesOf(afterAssistants[ai])
                    // (2) presetMessages count conserved.
                    assertEquals("presetMessages count changed for assistant $ai", bMsgs.size, aMsgs.size)

                    for (mi in bMsgs.indices) {
                        val bParts = partsOf(bMsgs[mi])
                        val aParts = partsOf(aMsgs[mi])
                        // (3) top-level parts count conserved.
                        assertEquals("parts count changed for a$ai m$mi", bParts.size, aParts.size)
                        // (4) recursive part count (incl. nested output) conserved.
                        assertEquals("recursive part count changed for a$ai m$mi", countParts(bParts), countParts(aParts))
                        // (5) no content loss + canonical type.
                        assertNonTypeKeysPreserved(bParts, aParts)
                    }
                }
            }
        }
    }
}
