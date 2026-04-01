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
import me.rerere.rikkahub.data.model.SillyTavernPromptOrderItem
import me.rerere.rikkahub.data.model.defaultSillyTavernPromptTemplate
import me.rerere.rikkahub.data.model.findPrompt
import me.rerere.rikkahub.data.model.withPromptOrder
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
    fun `preset export should use edited prompt order for imported presets`() {
        val payload = parseAssistantImportFromJson(
            jsonString = """
                {
                  "name": "Edited Order Preset",
                  "prompts": [
                    {
                      "identifier": "main",
                      "name": "Main Prompt",
                      "role": "system",
                      "content": "Main"
                    },
                    {
                      "identifier": "jailbreak",
                      "name": "Jailbreak Prompt",
                      "role": "system",
                      "content": "Jailbreak"
                    }
                  ],
                  "prompt_order": [
                    {
                      "character_id": 100001,
                      "xiaobai_ext": {
                        "slot": 7
                      },
                      "order": [
                        { "identifier": "main", "enabled": true },
                        { "identifier": "jailbreak", "enabled": false, "custom_order_field": "keep-me" }
                      ]
                    }
                  ]
                }
            """.trimIndent(),
            sourceName = "edited-order",
        )

        val preset = payload.toSillyTavernPreset()
        val exported = Json.parseToJsonElement(
            SillyTavernPresetExportSerializer.exportToJson(
                preset.copy(
                    template = preset.template.withPromptOrder(
                        listOf(
                            SillyTavernPromptOrderItem(identifier = "jailbreak", enabled = true),
                            SillyTavernPromptOrderItem(identifier = "main", enabled = false),
                        )
                    )
                )
            )
        ).jsonObject

        val exportedOrderList = exported["prompt_order"]
            ?.jsonArray
            ?.first()
            ?.jsonObject
        val exportedOrder = exportedOrderList
            ?.get("order")
            ?.jsonArray
            .orEmpty()

        assertEquals("7", exportedOrderList?.get("xiaobai_ext")?.jsonObject?.get("slot")?.jsonPrimitive?.content)
        assertEquals("jailbreak", exportedOrder[0].jsonObject["identifier"]?.jsonPrimitive?.content)
        assertEquals("true", exportedOrder[0].jsonObject["enabled"]?.jsonPrimitive?.content)
        assertEquals("keep-me", exportedOrder[0].jsonObject["custom_order_field"]?.jsonPrimitive?.content)
        assertEquals("main", exportedOrder[1].jsonObject["identifier"]?.jsonPrimitive?.content)
        assertEquals("false", exportedOrder[1].jsonObject["enabled"]?.jsonPrimitive?.content)
    }

    @Test
    fun `preset export should drop deleted prompt definitions from imported presets`() {
        val payload = parseAssistantImportFromJson(
            jsonString = """
                {
                  "name": "Deleted Prompt Preset",
                  "prompts": [
                    {
                      "identifier": "main",
                      "name": "Main Prompt",
                      "role": "system",
                      "content": "Main",
                      "custom_prompt_field": "keep-me"
                    },
                    {
                      "identifier": "deleted",
                      "name": "Deleted Prompt",
                      "role": "system",
                      "content": "Remove me",
                      "custom_prompt_field": "drop-me"
                    }
                  ],
                  "prompt_order": [
                    {
                      "character_id": 100001,
                      "order": [
                        { "identifier": "main", "enabled": true },
                        { "identifier": "deleted", "enabled": true }
                      ]
                    }
                  ]
                }
            """.trimIndent(),
            sourceName = "deleted-prompt",
        )

        val preset = payload.toSillyTavernPreset()
        val editedTemplate = preset.template.copy(
            prompts = preset.template.prompts.filterNot { it.identifier == "deleted" },
        ).withPromptOrder(
            listOf(SillyTavernPromptOrderItem(identifier = "main", enabled = true))
        )
        val exported = Json.parseToJsonElement(
            SillyTavernPresetExportSerializer.exportToJson(
                preset.copy(template = editedTemplate)
            )
        ).jsonObject

        val exportedPrompts = exported["prompts"]?.jsonArray.orEmpty()
        val exportedOrder = exported["prompt_order"]
            ?.jsonArray
            ?.first()
            ?.jsonObject
            ?.get("order")
            ?.jsonArray
            .orEmpty()

        assertEquals(1, exportedPrompts.size)
        assertEquals("main", exportedPrompts.first().jsonObject["identifier"]?.jsonPrimitive?.content)
        assertEquals("keep-me", exportedPrompts.first().jsonObject["custom_prompt_field"]?.jsonPrimitive?.content)
        assertEquals(1, exportedOrder.size)
        assertEquals("main", exportedOrder.first().jsonObject["identifier"]?.jsonPrimitive?.content)
    }

    @Test
    fun `preset export should clear stop strings when imported preset removes them`() {
        val payload = parseAssistantImportFromJson(
            jsonString = """
                {
                  "name": "Cleared Stops Preset",
                  "enable_stop_string": true,
                  "stop_string": "User:",
                  "stop_strings": ["User:", "Assistant:"],
                  "prompts": [
                    {
                      "identifier": "main",
                      "name": "Main Prompt",
                      "role": "system",
                      "content": "Main"
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
                    "SPreset": {
                      "ChatSquash": {
                        "enable_stop_string": true,
                        "stop_string": "Legacy:"
                      }
                    }
                  }
                }
            """.trimIndent(),
            sourceName = "cleared-stops",
        )

        val preset = payload.toSillyTavernPreset()
        val exportedJson = SillyTavernPresetExportSerializer.exportToJson(
            preset.copy(
                sampling = preset.sampling.copy(stopSequences = emptyList())
            )
        )
        val exported = Json.parseToJsonElement(exportedJson).jsonObject
        val chatSquash = exported["extensions"]
            ?.jsonObject
            ?.get("SPreset")
            ?.jsonObject
            ?.get("ChatSquash")
            ?.jsonObject

        assertEquals("false", exported["enable_stop_string"]?.jsonPrimitive?.content)
        assertNull(exported["stop_string"])
        assertNull(exported["stop_strings"])
        assertEquals("false", chatSquash?.get("enable_stop_string")?.jsonPrimitive?.content)
        assertNull(chatSquash?.get("stop_string"))
        assertTrue(
            parseAssistantImportFromJson(exportedJson, sourceName = "cleared-stops-roundtrip")
                .assistant
                .stopSequences
                .isEmpty()
        )
    }

    @Test
    fun `preset export should clear removed sampling overrides from imported presets`() {
        val payload = parseAssistantImportFromJson(
            jsonString = """
                {
                  "name": "Cleared Sampling Preset",
                  "temperature": 0.8,
                  "top_p": 0.9,
                  "openai_max_tokens": 512,
                  "frequency_penalty": 0.2,
                  "presence_penalty": 0.3,
                  "min_p": 0.05,
                  "top_k": 40,
                  "top_a": 0.1,
                  "repetition_penalty": 1.1,
                  "seed": 1234,
                  "reasoning_effort": "high",
                  "verbosity": "low",
                  "stream_openai": false,
                  "prompts": [
                    {
                      "identifier": "main",
                      "name": "Main Prompt",
                      "role": "system",
                      "content": "Main"
                    }
                  ],
                  "prompt_order": [
                    {
                      "character_id": 100001,
                      "order": [
                        { "identifier": "main", "enabled": true }
                      ]
                    }
                  ]
                }
            """.trimIndent(),
            sourceName = "cleared-sampling",
        )

        val preset = payload.toSillyTavernPreset()
        val exportedJson = SillyTavernPresetExportSerializer.exportToJson(
            preset.copy(
                sampling = preset.sampling.copy(
                    temperature = null,
                    topP = null,
                    maxTokens = null,
                    frequencyPenalty = null,
                    presencePenalty = null,
                    minP = null,
                    topK = null,
                    topA = null,
                    repetitionPenalty = null,
                    seed = null,
                    openAIReasoningEffort = "",
                    openAIVerbosity = "",
                )
            )
        )
        val exported = Json.parseToJsonElement(exportedJson).jsonObject
        val roundTrippedAssistant = parseAssistantImportFromJson(
            exportedJson,
            sourceName = "cleared-sampling-roundtrip",
        ).assistant

        assertNull(exported["temperature"])
        assertNull(exported["top_p"])
        assertNull(exported["openai_max_tokens"])
        assertNull(exported["frequency_penalty"])
        assertNull(exported["presence_penalty"])
        assertNull(exported["min_p"])
        assertNull(exported["top_k"])
        assertNull(exported["top_a"])
        assertNull(exported["repetition_penalty"])
        assertNull(exported["seed"])
        assertNull(exported["reasoning_effort"])
        assertNull(exported["verbosity"])
        assertEquals("false", exported["stream_openai"]?.jsonPrimitive?.content)

        assertNull(roundTrippedAssistant.temperature)
        assertNull(roundTrippedAssistant.topP)
        assertNull(roundTrippedAssistant.maxTokens)
        assertNull(roundTrippedAssistant.frequencyPenalty)
        assertNull(roundTrippedAssistant.presencePenalty)
        assertNull(roundTrippedAssistant.minP)
        assertNull(roundTrippedAssistant.topK)
        assertNull(roundTrippedAssistant.topA)
        assertNull(roundTrippedAssistant.repetitionPenalty)
        assertNull(roundTrippedAssistant.seed)
        assertEquals("", roundTrippedAssistant.openAIReasoningEffort)
        assertEquals("", roundTrippedAssistant.openAIVerbosity)
    }

    @Test
    fun `preset export should clear deleted regex scripts from extensions`() {
        val payload = parseAssistantImportFromJson(
            jsonString = """
                {
                  "name": "Cleared Regex Preset",
                  "prompts": [
                    {
                      "identifier": "main",
                      "name": "Main Prompt",
                      "role": "system",
                      "content": "Main"
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
                    ],
                    "tavern_helper": {
                      "enabled": true
                    }
                  }
                }
            """.trimIndent(),
            sourceName = "cleared-regex",
        )

        val preset = payload.toSillyTavernPreset()
        val exportedJson = SillyTavernPresetExportSerializer.exportToJson(
            preset.copy(regexes = emptyList())
        )
        val exported = Json.parseToJsonElement(exportedJson).jsonObject

        assertNull(exported["extensions"]?.jsonObject?.get("regex_scripts"))
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
        assertTrue(parseAssistantImportFromJson(exportedJson, sourceName = "cleared-regex-roundtrip").regexes.isEmpty())
    }

    @Test
    fun `preset export should clear legacy regex bindings from extensions`() {
        val payload = parseAssistantImportFromJson(
            jsonString = """
                {
                  "name": "Legacy Regex Binding Preset",
                  "prompts": [
                    {
                      "identifier": "main",
                      "name": "Main Prompt",
                      "role": "system",
                      "content": "Main"
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
                    "SPreset": {
                      "custom_flag": true,
                      "RegexBinding": {
                        "regexes": [
                          {
                            "scriptName": "Legacy Regex",
                            "findRegex": "alpha",
                            "replaceString": "beta",
                            "placement": [2]
                          }
                        ]
                      }
                    },
                    "tavern_helper": {
                      "enabled": true
                    }
                  }
                }
            """.trimIndent(),
            sourceName = "legacy-regex-binding",
        )

        val preset = payload.toSillyTavernPreset()
        val exportedJson = SillyTavernPresetExportSerializer.exportToJson(
            preset.copy(regexes = emptyList())
        )
        val exported = Json.parseToJsonElement(exportedJson).jsonObject
        val sPreset = exported["extensions"]
            ?.jsonObject
            ?.get("SPreset")
            ?.jsonObject

        assertNull(exported["extensions"]?.jsonObject?.get("regex_scripts"))
        assertNull(sPreset?.get("RegexBinding"))
        assertEquals("true", sPreset?.get("custom_flag")?.jsonPrimitive?.content)
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
        assertTrue(
            parseAssistantImportFromJson(exportedJson, sourceName = "legacy-regex-binding-roundtrip")
                .regexes
                .isEmpty()
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
