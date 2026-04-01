package me.rerere.rikkahub.data.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.AssistantRegexSourceKind
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.SillyTavernCharacterData
import me.rerere.rikkahub.data.model.SillyTavernPreset
import me.rerere.rikkahub.data.model.SillyTavernPresetSampling
import me.rerere.rikkahub.data.model.SillyTavernPromptItem
import me.rerere.rikkahub.data.model.defaultSillyTavernPromptTemplate
import me.rerere.rikkahub.data.model.findPrompt
import me.rerere.rikkahub.ui.pages.assistant.detail.parseAssistantImportFromJson
import me.rerere.rikkahub.ui.pages.assistant.detail.toSillyTavernPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SillyTavernExportSerializerTest {
    @Test
    fun `preset export should match sillytavern structure`() {
        val preset = SillyTavernPreset(
            template = defaultSillyTavernPromptTemplate().copy(sourceName = "Test Preset"),
            regexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    name = "Normalize",
                    findRegex = "foo",
                    replaceString = "bar",
                    affectingScope = setOf(AssistantAffectScope.ASSISTANT),
                    stPlacements = setOf(2),
                )
            ),
            sampling = SillyTavernPresetSampling(
                temperature = 0.8f,
                topP = 0.9f,
                stopSequences = listOf("</END>"),
            ),
        )

        val exported = Json.parseToJsonElement(
            SillyTavernPresetExportSerializer.exportToJson(preset)
        ).jsonObject

        assertEquals("Test Preset", exported["name"]?.jsonPrimitive?.content)
        assertEquals("0.8", exported["temperature"]?.jsonPrimitive?.content)
        assertEquals("0.9", exported["top_p"]?.jsonPrimitive?.content)
        assertEquals("</END>", exported["stop_strings"]?.jsonArray?.first()?.jsonPrimitive?.content)
        assertTrue(exported["prompts"]?.jsonArray?.isNotEmpty() == true)
        assertTrue(exported["prompt_order"]?.jsonArray?.isNotEmpty() == true)
        assertEquals(
            "Normalize",
            exported["extensions"]
                ?.jsonObject
                ?.get("regex_scripts")
                ?.jsonArray
                ?.first()
                ?.jsonObject
                ?.get("scriptName")
                ?.jsonPrimitive
                ?.content
        )
    }

    @Test
    fun `preset export should preserve markdown flag when regex is also prompt only`() {
        val preset = SillyTavernPreset(
            template = defaultSillyTavernPromptTemplate().copy(sourceName = "Dual Phase Preset"),
            regexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    name = "Dual Phase",
                    findRegex = "foo",
                    replaceString = "bar",
                    affectingScope = setOf(AssistantAffectScope.ASSISTANT),
                    visualOnly = true,
                    promptOnly = true,
                    stPlacements = setOf(2),
                )
            ),
        )

        val regexJson = Json.parseToJsonElement(
            SillyTavernPresetExportSerializer.exportToJson(preset)
        ).jsonObject["extensions"]
            ?.jsonObject
            ?.get("regex_scripts")
            ?.jsonArray
            ?.first()
            ?.jsonObject

        assertEquals("true", regexJson?.get("markdownOnly")?.jsonPrimitive?.content)
        assertEquals("true", regexJson?.get("promptOnly")?.jsonPrimitive?.content)
    }

    @Test
    fun `preset export should preserve literal regex tags from normal preset imports`() {
        val payload = parseAssistantImportFromJson(
            jsonString = """
                {
                  "name": "Inline Prompt Preset",
                  "prompts": [
                    {
                      "identifier": "main",
                      "name": "Main Prompt",
                      "role": "system",
                      "content": "Main body<regex>\"foo\":\"baz\"</regex>"
                    }
                  ],
                  "prompt_order": [
                    {
                      "character_id": 100001,
                      "order": [
                        { "identifier": "main", "enabled": true }
                      ]
                    }
                  ],
                  "extensions": {
                    "regex_scripts": [
                      {
                        "scriptName": "Preset Regex",
                        "findRegex": "alpha",
                        "replaceString": "beta",
                        "placement": [2]
                      }
                    ]
                  }
                }
            """.trimIndent(),
            sourceName = "inline-roundtrip",
        )

        val preset = payload.toSillyTavernPreset()
        val exported = Json.parseToJsonElement(
            SillyTavernPresetExportSerializer.exportToJson(preset)
        ).jsonObject

        val mainPromptContent = exported["prompts"]
            ?.jsonArray
            ?.first()
            ?.jsonObject
            ?.get("content")
            ?.jsonPrimitive
            ?.content
            .orEmpty()
        val regexScripts = exported["extensions"]
            ?.jsonObject
            ?.get("regex_scripts")
            ?.jsonArray
            .orEmpty()

        assertFalse(payload.regexes.any { it.sourceKind == AssistantRegexSourceKind.ST_INLINE_PROMPT })
        assertTrue(mainPromptContent.contains("<regex>"))
        assertTrue(mainPromptContent.contains("\"foo\":\"baz\""))
        assertEquals(1, regexScripts.size)
        assertEquals("Preset Regex", regexScripts.first().jsonObject["scriptName"]?.jsonPrimitive?.content)
    }

    @Test
    fun `preset export should keep inline regex blocks inside prompts on reimport`() {
        val preset = SillyTavernPreset(
            template = defaultSillyTavernPromptTemplate().copy(
                sourceName = "Inline Regex Export",
                prompts = listOf(
                    SillyTavernPromptItem(
                        identifier = "main",
                        name = "Main Prompt",
                        role = MessageRole.SYSTEM,
                        content = "Main body",
                    )
                ),
                orderedPromptIds = listOf("main"),
            ),
            regexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    name = "Inline Prompt Regex",
                    findRegex = "foo",
                    replaceString = "baz",
                    affectingScope = setOf(AssistantAffectScope.SYSTEM),
                    promptOnly = true,
                    sourceKind = AssistantRegexSourceKind.ST_INLINE_PROMPT,
                    sourceRef = "main",
                )
            ),
        )

        val exportedJson = SillyTavernPresetExportSerializer.exportToJson(preset)
        val exported = Json.parseToJsonElement(exportedJson).jsonObject
        val mainPromptContent = exported["prompts"]
            ?.jsonArray
            ?.first()
            ?.jsonObject
            ?.get("content")
            ?.jsonPrimitive
            ?.content
            .orEmpty()

        assertEquals(
            "true",
            exported["extensions"]
                ?.jsonObject
                ?.get("rikkahub_inline_prompt_regexes")
                ?.jsonPrimitive
                ?.content,
        )
        assertTrue(mainPromptContent.contains("<regex>"))

        val payload = parseAssistantImportFromJson(
            jsonString = exportedJson,
            sourceName = "inline-export",
        )

        assertFalse(payload.regexes.any { it.sourceKind == AssistantRegexSourceKind.ST_INLINE_PROMPT })
        assertTrue(payload.presetTemplate?.findPrompt("main")?.content?.contains("<regex>") == true)
        assertTrue(payload.presetTemplate?.findPrompt("main")?.content?.contains("\"foo\":\"baz\"") == true)
    }

    @Test
    fun `preset export should preserve raw preset fields that app does not model`() {
        val payload = parseAssistantImportFromJson(
            jsonString = """
                {
                  "name": "Raw Preservation Preset",
                  "stream_openai": false,
                  "bias_preset_selected": "Mirostat",
                  "function_calling": true,
                  "prompts": [
                    {
                      "identifier": "main",
                      "name": "Main Prompt",
                      "role": "system",
                      "content": "Main",
                      "custom_prompt_field": "keep-me"
                    }
                  ],
                  "prompt_order": [
                    {
                      "character_id": 100001,
                      "xiaobai_ext": {
                        "slot": 7
                      },
                      "order": [
                        { "identifier": "main", "enabled": true }
                      ]
                    }
                  ],
                  "extensions": {
                    "tavern_helper": {
                      "enabled": true
                    }
                  }
                }
            """.trimIndent(),
            sourceName = "raw-preservation",
        )

        val exported = Json.parseToJsonElement(
            SillyTavernPresetExportSerializer.exportToJson(payload.toSillyTavernPreset())
        ).jsonObject

        assertEquals("false", exported["stream_openai"]?.jsonPrimitive?.content)
        assertEquals("Mirostat", exported["bias_preset_selected"]?.jsonPrimitive?.content)
        assertEquals("true", exported["function_calling"]?.jsonPrimitive?.content)
        assertEquals(
            "keep-me",
            exported["prompts"]
                ?.jsonArray
                ?.first()
                ?.jsonObject
                ?.get("custom_prompt_field")
                ?.jsonPrimitive
                ?.content
        )
        assertEquals(
            "7",
            exported["prompt_order"]
                ?.jsonArray
                ?.first()
                ?.jsonObject
                ?.get("xiaobai_ext")
                ?.jsonObject
                ?.get("slot")
                ?.jsonPrimitive
                ?.content
        )
        assertEquals(
            "true",
            exported["extensions"]
                ?.jsonObject
                ?.get("tavern_helper")
                ?.jsonObject
                ?.get("enabled")
                ?.jsonPrimitive
                ?.content
        )
    }

    @Test
    fun `character card export should embed lorebooks and regexes`() {
        val assistant = Assistant(
            name = "Tester",
            systemPrompt = "System prompt",
            regexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    name = "Output Clean",
                    findRegex = "foo",
                    replaceString = "bar",
                    affectingScope = setOf(AssistantAffectScope.ASSISTANT),
                    stPlacements = setOf(2),
                )
            ),
            stCharacterData = SillyTavernCharacterData(
                name = "Tester",
                description = "desc",
                personality = "kind",
                scenario = "scenario",
                firstMessage = "hello",
                exampleMessagesRaw = "<START>example",
                creatorNotes = "notes",
            ),
        )
        val lorebook = Lorebook(
            name = "World",
            entries = listOf(
                PromptInjection.RegexInjection(
                    name = "World Entry",
                    keywords = listOf("moon"),
                    content = "The moon is red.",
                    role = MessageRole.SYSTEM,
                    position = InjectionPosition.OUTLET,
                    stMetadata = mapOf("outlet_name" to "memory"),
                )
            ),
        )

        val exported = Json.parseToJsonElement(
            SillyTavernCharacterCardSerializer.exportToJson(
                SillyTavernCharacterCardExportData(
                    assistant = assistant,
                    lorebooks = listOf(lorebook),
                )
            )
        ).jsonObject

        assertEquals("chara_card_v2", exported["spec"]?.jsonPrimitive?.content)
        assertEquals(
            "World",
            exported["data"]?.jsonObject?.get("character_book")?.jsonObject?.get("name")?.jsonPrimitive?.content
        )
        assertEquals(
            "World Entry",
            exported["data"]
                ?.jsonObject
                ?.get("character_book")
                ?.jsonObject
                ?.get("entries")
                ?.jsonArray
                ?.first()
                ?.jsonObject
                ?.get("name")
                ?.jsonPrimitive
                ?.content
        )
        assertEquals(
            "7",
            exported["data"]
                ?.jsonObject
                ?.get("character_book")
                ?.jsonObject
                ?.get("entries")
                ?.jsonArray
                ?.first()
                ?.jsonObject
                ?.get("extensions")
                ?.jsonObject
                ?.get("position")
                ?.jsonPrimitive
                ?.content
        )
        assertEquals(
            "Output Clean",
            exported["data"]
                ?.jsonObject
                ?.get("extensions")
                ?.jsonObject
                ?.get("regex_scripts")
                ?.jsonArray
                ?.first()
                ?.jsonObject
                ?.get("scriptName")
                ?.jsonPrimitive
                ?.content
        )
    }

    @Test
    fun `character card export should preserve disabled probability metadata without leaking internal keys`() {
        val exportedEntryExtensions = Json.parseToJsonElement(
            SillyTavernCharacterCardSerializer.exportToJson(
                SillyTavernCharacterCardExportData(
                    assistant = Assistant(
                        name = "Tester",
                        stCharacterData = SillyTavernCharacterData(name = "Tester"),
                    ),
                    lorebooks = listOf(
                        Lorebook(
                            name = "World",
                            entries = listOf(
                                PromptInjection.RegexInjection(
                                    name = "World Entry",
                                    keywords = listOf("moon"),
                                    content = "The moon is red.",
                                    stMetadata = mapOf(
                                        "probability" to "40",
                                        "useProbability" to "false",
                                        "display_index" to "9",
                                        "entry_index" to "3",
                                        "custom_toggle" to "true",
                                    ),
                                )
                            ),
                        )
                    ),
                )
            )
        ).jsonObject["data"]
            ?.jsonObject
            ?.get("character_book")
            ?.jsonObject
            ?.get("entries")
            ?.jsonArray
            ?.first()
            ?.jsonObject
            ?.get("extensions")
            ?.jsonObject

        assertEquals("40", exportedEntryExtensions?.get("probability")?.jsonPrimitive?.content)
        assertEquals("false", exportedEntryExtensions?.get("useProbability")?.jsonPrimitive?.content)
        assertEquals("true", exportedEntryExtensions?.get("custom_toggle")?.jsonPrimitive?.content)
        assertEquals("0", exportedEntryExtensions?.get("display_index")?.jsonPrimitive?.content)
        assertNull(exportedEntryExtensions?.get("displayIndex"))
        assertNull(exportedEntryExtensions?.get("entry_index"))
    }

    @Test
    fun `character card png export file name should sanitize reserved characters`() {
        val fileName = SillyTavernCharacterCardPngSerializer.getExportFileName(
            SillyTavernCharacterCardExportData(
                assistant = Assistant(
                    name = "Fallback",
                    stCharacterData = SillyTavernCharacterData(
                        name = "A/B:C",
                    ),
                ),
                lorebooks = emptyList(),
            )
        )

        assertEquals("A_B_C.png", fileName)
    }
}
