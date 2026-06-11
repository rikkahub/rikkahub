package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.common.json.JsonInstant
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Serialization-compatibility GATE for the #243 `:ai-runtime` carve-out (slice 4/10).
 *
 * The upcoming package moves (slices 5-10) relocate `@Serializable` model types across modules.
 * kotlinx-serialization keys polymorphic (sealed) members and the default class discriminator off
 * STRING names, not the Kotlin FQCN:
 *   - the default `classDiscriminator` is the literal key `"type"`,
 *   - each sealed subtype is tagged by its `@SerialName(...)` value.
 * Neither is affected by which package the class lives in — UNLESS a move accidentally edits a
 * `@SerialName`, drops one, or swaps the discriminator key. Any of those silently breaks every
 * already-persisted on-disk blob (a DataStore Settings/Assistant sub-blob or a Room Conversation/
 * MessageNode column), because the OLD bytes carry the OLD discriminator string.
 *
 * This test pins the OLD on-disk shape as FROZEN string literals (never built from the live data
 * classes, so they cannot drift when a model changes) and asserts the production-persistence
 * serializer `me.rerere.common.json.JsonInstant` (ConversationRepository persists Conversation/
 * MessageNode through it; PreferencesStore persists the providers/assistants sub-blobs through it):
 *   (a) DECODES the frozen fixture without throwing (guards MissingFieldException + unknown
 *       discriminator), and
 *   (b) RE-ENCODES it while keeping every nested polymorphic `"type"` discriminator value intact.
 *
 * The assertion is structural, NOT raw-string identity: app `JsonInstant` uses the default
 * `explicitNulls = true`, so re-encode emits omitted nulls and reorders keys — expected, not a
 * regression. So the test walks the re-encoded tree and asserts the ORDERED list of `"type"`
 * discriminator values found at each SPECIFIC polymorphic array path against a frozen expected list
 * (exact equality — extra/wrong/colliding discriminators are rejected, not tolerated).
 *
 * Pure JVM JUnit4, no Robolectric: none of these types touch android.* at encode/decode time
 * (Uri/Log live only in non-serialized helpers/@Transient fields; kotlin.uuid.Uuid serializes as a
 * plain string).
 */
class RuntimeSerializationCompatibilityTest {

    // ---- helpers -------------------------------------------------------------------------------

    private fun reencodeTree(decodedReencoded: String): JsonObject =
        JsonInstant.parseToJsonElement(decodedReencoded) as JsonObject

    /**
     * Pull the ordered list of `"type"` discriminator values found at a SPECIFIC array path.
     *
     * Path segments are object keys; the final segment must address a `JsonArray` whose elements are
     * `JsonObject`s carrying (or omitting) a `"type"` key. Returns the discriminator of each element
     * in array order (null for an element without `"type"`).
     *
     * Path-scoped (vs. a recursive whole-tree collect) is the load-bearing property of this gate: it
     * (a) pins each discriminator to the field that actually persists it, so a wrong/extra `"type"`
     * elsewhere cannot vacuously satisfy the assertion, and (b) is immune to default sub-blobs that
     * re-emit a colliding discriminator under `encodeDefaults = true` (e.g. the `ttsProviders`
     * default contains a `TTSProviderSetting.OpenAI` whose `@SerialName` is also `"openai"`, which a
     * whole-tree collect would let stand in for the `providers` `ProviderSetting.OpenAI`).
     */
    private fun typesAtArray(root: JsonObject, vararg path: String): List<String?> {
        var current: JsonElement = root
        for (segment in path) {
            current = when (current) {
                is JsonArray -> current[segment.toInt()]
                is JsonObject -> current.getValue(segment)
                else -> error("path segment '$segment' cannot index into $current")
            }
        }
        return (current as JsonArray).map { element ->
            (element as JsonObject)["type"]?.let { it.jsonPrimitive.content }
        }
    }

    // ---- (1) Settings --------------------------------------------------------------------------

    @Test
    fun `settings legacy json round-trips and keeps SerialName discriminator semantics`() {
        // Frozen pre-move Settings exercising the persisted polymorphic members: ProviderSetting
        // (openai/google/claude), Assistant.localTools (time_info/clipboard/tts), a presetMessages
        // UIMessage with a text part, an AssistantRegex, plus a modeInjections entry and a Lorebook
        // entry.
        //
        // NOTE on modeInjections / lorebooks[].entries: their persisted field types are the CONCRETE
        // subtypes (`List<PromptInjection.ModeInjection>` / `List<PromptInjection.RegexInjection>`),
        // NOT the sealed base `PromptInjection`. kotlinx-serialization therefore does NOT emit a
        // "type" discriminator for them on disk (a discriminator is only written when the static
        // type is the sealed base, which never happens in persistence — `List<PromptInjection>` is
        // used only in transient transformer logic). So the frozen fixture omits "type" on those
        // entries, and the path-scoped discriminator assertions below do NOT cover "mode"/"regex";
        // their compatibility is instead guarded by direct literal assertions on the decoded value
        // (decoded.modeInjections.single().name == "study" etc.) plus the value-equality round-trip.
        val legacySettings = """
            {
              "providers": [
                { "type": "openai", "name": "OpenAI", "apiKey": "k", "baseUrl": "https://api.openai.com/v1" },
                { "type": "google", "name": "Google", "apiKey": "k" },
                { "type": "claude", "name": "Claude", "apiKey": "k" }
              ],
              "assistants": [
                {
                  "id": "11111111-1111-1111-1111-111111111111",
                  "name": "Legacy Assistant",
                  "localTools": [ { "type": "time_info" }, { "type": "clipboard" }, { "type": "tts" } ],
                  "presetMessages": [
                    {
                      "id": "22222222-2222-2222-2222-222222222222",
                      "role": "user",
                      "parts": [ { "type": "text", "text": "hello" } ]
                    }
                  ],
                  "regexes": [
                    {
                      "id": "33333333-3333-3333-3333-333333333333",
                      "name": "r",
                      "findRegex": "a",
                      "replaceString": "b",
                      "affectingScope": [ "USER", "ASSISTANT" ]
                    }
                  ]
                }
              ],
              "modeInjections": [
                { "id": "44444444-4444-4444-4444-444444444444", "name": "study" }
              ],
              "lorebooks": [
                {
                  "id": "55555555-5555-5555-5555-555555555555",
                  "name": "lb",
                  "entries": [
                    { "id": "66666666-6666-6666-6666-666666666666", "name": "e" }
                  ]
                }
              ]
            }
        """.trimIndent()

        val decoded = JsonInstant.decodeFromString<Settings>(legacySettings)

        // The fixture freezes the OLD on-disk shape; assert the decode actually mapped those literals
        // (not silently dropped by `ignoreUnknownKeys = true` on a future @SerialName rename) BEFORE
        // the round-trip. Without this, decode→encode→decode would be tautologically equal even if a
        // rename had thrown the fixture's data away on the first decode.
        assertEquals("study", decoded.modeInjections.single().name)
        assertEquals("lb", decoded.lorebooks.single().name)
        assertEquals(
            listOf(
                me.rerere.rikkahub.data.ai.tools.LocalToolOption.TimeInfo,
                me.rerere.rikkahub.data.ai.tools.LocalToolOption.Clipboard,
                me.rerere.rikkahub.data.ai.tools.LocalToolOption.Tts,
            ),
            decoded.assistants.single().localTools
        )

        val tree = reencodeTree(JsonInstant.encodeToString(decoded))

        // The load-bearing assertion: every PERSISTED discriminator survives the round-trip, asserted
        // at its SPECIFIC array path so a colliding discriminator elsewhere (e.g. the `ttsProviders`
        // default's `TTSProviderSetting.OpenAI` under `encodeDefaults = true`) cannot stand in for it,
        // and an EXTRA/wrong discriminator is rejected (exact list, not containsAll).
        assertEquals(
            listOf("openai", "google", "claude"),
            typesAtArray(tree, "providers")
        )
        assertEquals(
            listOf("time_info", "clipboard", "tts"),
            typesAtArray(tree, "assistants", "0", "localTools")
        )

        // Value-equality round-trip guards the concrete-subtype fields (modeInjections /
        // lorebooks[].entries) that carry no on-disk "type" discriminator: a package move that
        // broke their structural shape would flip this even without a discriminator change.
        val redecoded = JsonInstant.decodeFromString<Settings>(JsonInstant.encodeToString(decoded))
        assertEquals(decoded.modeInjections, redecoded.modeInjections)
        assertEquals(decoded.lorebooks, redecoded.lorebooks)
        assertEquals(decoded.assistants, redecoded.assistants)
        assertEquals(decoded.providers, redecoded.providers)
    }

    // ---- (2) Assistant -------------------------------------------------------------------------

    @Test
    fun `assistant legacy json round-trips and keeps localTools + affectingScope SerialNames`() {
        // Sparse on purpose (most fields omitted) to prove defaults still apply on decode.
        val legacyAssistant = """
            {
              "id": "77777777-7777-7777-7777-777777777777",
              "name": "Sparse",
              "localTools": [ { "type": "javascript_engine" }, { "type": "ask_user" } ],
              "regexes": [
                {
                  "id": "88888888-8888-8888-8888-888888888888",
                  "affectingScope": [ "USER" ]
                }
              ]
            }
        """.trimIndent()

        val decoded = JsonInstant.decodeFromString<Assistant>(legacyAssistant)
        // localTools discriminators decoded into the right subtypes.
        assertEquals(
            listOf(
                me.rerere.rikkahub.data.ai.tools.LocalToolOption.JavascriptEngine,
                me.rerere.rikkahub.data.ai.tools.LocalToolOption.AskUser,
            ),
            decoded.localTools
        )
        assertEquals(setOf(AssistantAffectScope.USER), decoded.regexes.single().affectingScope)

        val tree = reencodeTree(JsonInstant.encodeToString(decoded))
        assertEquals(
            listOf("javascript_engine", "ask_user"),
            typesAtArray(tree, "localTools")
        )
        // affectingScope enum SerialNames (enum entries serialize by name) survive.
        val scope = (tree["regexes"] as JsonArray)
            .map { it as JsonObject }
            .single()["affectingScope"] as JsonArray
        assertEquals(listOf("USER"), scope.map { it.jsonPrimitive.content })
    }

    // ---- (3) Conversation ----------------------------------------------------------------------

    @Test
    fun `conversation legacy json round-trips and keeps part + approval + annotation discriminators`() {
        // Cover the persisted UIMessagePart subtypes plus a Tool whose approvalState is the
        // polymorphic Denied/Answered, and a url_citation annotation.
        val legacyConversation = """
            {
              "id": "99999999-9999-9999-9999-999999999999",
              "assistantId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
              "title": "Legacy chat",
              "createAt": "2024-01-01T00:00:00Z",
              "updateAt": "2024-01-02T00:00:00Z",
              "messageNodes": [
                {
                  "id": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                  "selectIndex": 0,
                  "messages": [
                    {
                      "id": "cccccccc-cccc-cccc-cccc-cccccccccccc",
                      "role": "user",
                      "parts": [
                        { "type": "text", "text": "look at this" },
                        { "type": "image", "url": "file:///x.png" },
                        { "type": "video", "url": "file:///v.mp4" },
                        { "type": "audio", "url": "file:///a.mp3" },
                        { "type": "document", "url": "file:///d.pdf", "fileName": "d.pdf" }
                      ]
                    },
                    {
                      "id": "dddddddd-dddd-dddd-dddd-dddddddddddd",
                      "role": "assistant",
                      "parts": [
                        { "type": "reasoning", "reasoning": "thinking" },
                        {
                          "type": "tool",
                          "toolCallId": "call_1",
                          "toolName": "t",
                          "input": "{}",
                          "output": [ { "type": "text", "text": "result" } ],
                          "approvalState": { "type": "denied", "reason": "no" }
                        },
                        {
                          "type": "tool",
                          "toolCallId": "call_2",
                          "toolName": "ask",
                          "input": "{}",
                          "output": [],
                          "approvalState": { "type": "answered", "answer": "yes" }
                        }
                      ],
                      "annotations": [
                        { "type": "url_citation", "title": "src", "url": "https://example.com" }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val decoded = JsonInstant.decodeFromString<Conversation>(legacyConversation)
        val tree = reencodeTree(JsonInstant.encodeToString(decoded))

        // Path-scoped discriminator assertions: each polymorphic member is pinned at the exact array
        // it persists in, so a colliding "type" elsewhere cannot vacuously satisfy the gate and an
        // extra/wrong discriminator is rejected (exact list equality, not containsAll).
        assertEquals(
            listOf("text", "image", "video", "audio", "document"), // UIMessagePart subtypes (user)
            typesAtArray(tree, "messageNodes", "0", "messages", "0", "parts")
        )
        assertEquals(
            listOf("reasoning", "tool", "tool"), // UIMessagePart subtypes (assistant)
            typesAtArray(tree, "messageNodes", "0", "messages", "1", "parts")
        )
        // approvalState is a single polymorphic OBJECT (not an array) nested in each tool part.
        val assistantParts =
            (((tree["messageNodes"] as JsonArray)[0] as JsonObject)["messages"] as JsonArray)
                .let { it[1] as JsonObject }["parts"] as JsonArray
        assertEquals(
            listOf("denied", "answered"), // ToolApprovalState
            listOf(assistantParts[1] as JsonObject, assistantParts[2] as JsonObject)
                .map { (it["approvalState"] as JsonObject)["type"]!!.jsonPrimitive.content }
        )
        assertEquals(
            listOf("url_citation"), // UIMessageAnnotation
            typesAtArray(tree, "messageNodes", "0", "messages", "1", "annotations")
        )
        // Value-equality cross-check: data classes round-trip identically.
        val redecoded = JsonInstant.decodeFromString<Conversation>(JsonInstant.encodeToString(decoded))
        assertEquals(decoded, redecoded)
    }

    // ---- (4) MessageNode (standalone column blob) ----------------------------------------------

    @Test
    fun `messageNode legacy json round-trips and keeps part discriminators`() {
        val legacyNode = """
            {
              "id": "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee",
              "selectIndex": 0,
              "messages": [
                {
                  "id": "ffffffff-ffff-ffff-ffff-ffffffffffff",
                  "role": "assistant",
                  "parts": [
                    { "type": "reasoning", "reasoning": "r" },
                    { "type": "text", "text": "answer" },
                    {
                      "type": "tool",
                      "toolCallId": "c",
                      "toolName": "t",
                      "input": "{}",
                      "output": [ { "type": "text", "text": "ok" } ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val decoded = JsonInstant.decodeFromString<MessageNode>(legacyNode)
        val tree = reencodeTree(JsonInstant.encodeToString(decoded))

        assertEquals(
            listOf("reasoning", "text", "tool"),
            typesAtArray(tree, "messages", "0", "parts")
        )
        val redecoded = JsonInstant.decodeFromString<MessageNode>(JsonInstant.encodeToString(decoded))
        assertEquals(decoded, redecoded)
    }
}
