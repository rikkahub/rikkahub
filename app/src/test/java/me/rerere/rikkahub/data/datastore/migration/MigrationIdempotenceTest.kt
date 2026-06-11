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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.common.json.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TARGET 5: idempotence of the deterministic migration core.
 *
 * Property A (migrateAssistantsJson): migrate(migrate(x)) == migrate(x), and it never throws.
 *   Inputs are well-formed assistants arrays whose presetMessages[].parts[].type is drawn from the
 *   legacy keys in partTypeMapping (short / fully-qualified / already-lowercase), including nested
 *   Tool.output arrays — so the first pass actually rewrites types and the second pass is a no-op.
 *
 * Property B (SettingsJsonMigrator.migrate): migrate(migrate(x)) == migrate(x) compared as PARSED
 *   JsonElement (key order is irrelevant). The seed deliberately OMITS any raw `quickMessages` field
 *   inside assistants: migrateAssistantsQuickMessages mints a fresh random UUID per quickMessage on
 *   the first pass, which is non-idempotent BY CONSTRUCTION for such inputs — that V3 step is only
 *   idempotent once quickMessages have been extracted. We exercise the V1 (mcpServers) + V2
 *   (assistants part types) paths, which are pure rewrites.
 */
class MigrationIdempotenceTest {

    // Legacy + already-migrated part type tokens that the mapping accepts.
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
                // Nested output array exercises migratePartsArray recursion.
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
        // Only Tool-ish parts get an output array (matches real shape closely enough for migration).
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

    @Test
    fun `migrateAssistantsJson is idempotent`() {
        runBlocking {
            checkAll(200, arbAssistantsArray) { assistants ->
                val input = JsonInstant.encodeToString(assistants)
                val once = migrateAssistantsJson(input)
                val twice = migrateAssistantsJson(once)
                assertEquals(
                    JsonInstant.parseToJsonElement(once),
                    JsonInstant.parseToJsonElement(twice),
                )
            }
        }
    }

    private val arbMcpServer: Arb<JsonObject> = arbitrary {
        JsonObject(
            mapOf(
                // V1 migration rewrites these two legacy FQ class names to short forms; the
                // already-migrated short forms are passed through unchanged. Both are idempotent.
                "type" to JsonPrimitive(
                    Arb.element(
                        listOf(
                            "me.rerere.rikkahub.data.mcp.McpServerConfig.SseTransportServer",
                            "me.rerere.rikkahub.data.mcp.McpServerConfig.StreamableHTTPServer",
                            "sse",
                            "streamable_http",
                        )
                    ).bind()
                ),
                "name" to JsonPrimitive(Arb.string(0..8).bind()),
            )
        )
    }

    private val arbSettings: Arb<JsonObject> = arbitrary {
        JsonObject(
            buildMap {
                put("version", JsonPrimitive(3))
                put("mcpServers", JsonArray(Arb.list(arbMcpServer, 0..3).bind()))
                // assistants carry legacy part types but NO raw quickMessages (see class doc).
                put("assistants", arbAssistantsArray.bind())
            }
        )
    }

    @Test
    fun `SettingsJsonMigrator migrate is idempotent on quickMessage-free seeds`() {
        runBlocking {
            checkAll(200, arbSettings) { settings ->
                val input = JsonInstant.encodeToString(settings)
                val once = SettingsJsonMigrator.migrate(input)
                val twice = SettingsJsonMigrator.migrate(once)
                assertEquals(
                    JsonInstant.parseToJsonElement(once),
                    JsonInstant.parseToJsonElement(twice),
                )
            }
        }
    }
}
