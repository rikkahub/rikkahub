package me.rerere.rikkahub.ui.pages.assistant.detail

import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantRegexPlacement
import me.rerere.rikkahub.data.model.AssistantRegexSourceKind
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.applyActiveStPresetSampling
import me.rerere.rikkahub.data.model.findPrompt
import me.rerere.rikkahub.data.model.findPromptOrder
import me.rerere.rikkahub.data.model.stExtension
import me.rerere.rikkahub.utils.base64Encode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SillyTavernImportTest {
    @Test
    fun `should parse preset prompt order and bundled regexes`() {
        val json = """
            {
              "name": "Preset A",
              "temperature": 1.1,
              "assistant_prefill": "Prefill",
              "continue_prefill": true,
              "continue_postfix": "\n",
              "new_chat_prompt": "[Start]",
              "new_example_chat_prompt": "[Examples]",
              "squash_system_messages": true,
              "prompts": [
                {
                  "identifier": "main",
                  "name": "Main Prompt",
                  "role": "system",
                  "content": "Main body <regex>\"foo\":\"baz\"</regex>",
                  "system_prompt": true,
                  "enabled": false,
                  "injection_trigger": ["normal", "continue"]
                },
                {
                  "identifier": "chatHistory",
                  "name": "Chat History",
                  "role": "system",
                  "content": "",
                  "marker": true,
                  "enabled": true
                }
              ],
              "prompt_order": [
                {
                  "character_id": 100000,
                  "order": [
                    { "identifier": "chatHistory", "enabled": true },
                    { "identifier": "main", "enabled": true }
                  ]
                },
                {
                  "character_id": 100001,
                  "order": [
                    { "identifier": "main", "enabled": true },
                    { "identifier": "chatHistory", "enabled": true }
                  ]
                }
              ],
              "extensions": {
                "regex_scripts": [
                  {
                    "scriptName": "Preset Regex",
                    "findRegex": "foo",
                    "replaceString": "bar",
                    "placement": [1],
                    "promptOnly": true
                  }
                ]
              }
            }
        """.trimIndent()

        val payload = parseAssistantImportFromJson(
            jsonString = json,
            sourceName = "preset",
        )

        assertEquals(AssistantImportKind.PRESET, payload.kind)
        assertEquals(listOf("main", "chatHistory"), payload.presetTemplate?.orderedPromptIds)
        assertEquals(true, payload.presetTemplate?.findPromptOrder("main")?.enabled)
        assertEquals(1, payload.regexes.size)
        assertEquals(
            "Main body <regex>\"foo\":\"baz\"</regex>",
            payload.presetTemplate?.findPrompt("main")?.content,
        )
        assertEquals(
            AssistantRegexSourceKind.ST_SCRIPT,
            payload.regexes.firstOrNull { it.name == "Preset Regex" }?.sourceKind,
        )
        assertNull(payload.regexes.firstOrNull { it.sourceKind == AssistantRegexSourceKind.ST_INLINE_PROMPT })
        assertEquals(listOf("normal", "continue"), payload.presetTemplate?.findPrompt("main")?.injectionTriggers)
        assertEquals("Prefill", payload.presetTemplate?.assistantPrefill)
        assertEquals(true, payload.presetTemplate?.continuePrefill)
        assertEquals("\n", payload.presetTemplate?.continuePostfix)
        assertEquals("[Start]", payload.presetTemplate?.newChatPrompt)
        assertEquals("[Examples]", payload.presetTemplate?.newExampleChatPrompt)
        assertEquals(true, payload.presetTemplate?.squashSystemMessages)
    }

    @Test
    fun `preset import should map advanced request params`() {
        val payload = parseAssistantImportFromJson(
            jsonString = """
                {
                  "name": "Preset Params",
                  "frequency_penalty": -0.5,
                  "presence_penalty": 0.25,
                  "min_p": 0.1,
                  "top_k": 40,
                  "top_a": 0.2,
                  "repetition_penalty": 1.2,
                  "seed": 123,
                  "verbosity": "high",
                  "prompts": [
                    {
                      "identifier": "main",
                      "role": "system",
                      "content": "Main"
                    }
                  ],
                  "prompt_order": [
                    {
                      "character_id": 100000,
                      "order": [
                        { "identifier": "main", "enabled": true }
                      ]
                    }
                  ]
                }
            """.trimIndent(),
            sourceName = "preset-params",
        )

        assertEquals(-0.5f, payload.assistant.frequencyPenalty)
        assertEquals(0.25f, payload.assistant.presencePenalty)
        assertEquals(0.1f, payload.assistant.minP)
        assertEquals(40, payload.assistant.topK)
        assertEquals(0.2f, payload.assistant.topA)
        assertEquals(1.2f, payload.assistant.repetitionPenalty)
        assertEquals(123L, payload.assistant.seed)
        assertEquals("high", payload.assistant.openAIVerbosity)
    }

    @Test
    fun `preset import should preserve explicit zero top k`() {
        val payload = parseAssistantImportFromJson(
            jsonString = """
                {
                  "name": "Preset Zero Top K",
                  "top_k": 0,
                  "prompts": [
                    {
                      "identifier": "main",
                      "role": "system",
                      "content": "Main"
                    }
                  ],
                  "prompt_order": [
                    {
                      "character_id": 100000,
                      "order": [
                        { "identifier": "main", "enabled": true }
                      ]
                    }
                  ]
                }
            """.trimIndent(),
            sourceName = "preset-zero-top-k",
        )

        assertEquals(0, payload.assistant.topK)
    }

    @Test
    fun `preset import should preserve neutral advanced request params`() {
        val payload = parseAssistantImportFromJson(
            jsonString = """
                {
                  "name": "Preset Defaults",
                  "frequency_penalty": 0,
                  "presence_penalty": 0,
                  "min_p": 0,
                  "top_a": 0,
                  "repetition_penalty": 1,
                  "seed": -1,
                  "verbosity": "auto",
                  "prompts": [
                    {
                      "identifier": "main",
                      "role": "system",
                      "content": "Main"
                    }
                  ],
                  "prompt_order": [
                    {
                      "character_id": 100000,
                      "order": [
                        { "identifier": "main", "enabled": true }
                      ]
                    }
                  ]
                }
            """.trimIndent(),
            sourceName = "preset-defaults",
        )

        assertEquals(0f, payload.assistant.frequencyPenalty!!, 0f)
        assertEquals(0f, payload.assistant.presencePenalty!!, 0f)
        assertEquals(0f, payload.assistant.minP!!, 0f)
        assertNull(payload.assistant.topK)
        assertEquals(0f, payload.assistant.topA!!, 0f)
        assertEquals(1f, payload.assistant.repetitionPenalty!!, 0f)
        assertNull(payload.assistant.seed)
        assertEquals("", payload.assistant.openAIVerbosity)

        val importedPreset = payload.toSillyTavernPreset()
        val applied = Settings(
            stPresetEnabled = true,
            stPresets = listOf(importedPreset),
            selectedStPresetId = importedPreset.id,
        ).applyActiveStPresetSampling(
            Assistant(
                frequencyPenalty = 0.5f,
                presencePenalty = 0.75f,
                minP = 0.2f,
                topA = 0.6f,
                repetitionPenalty = 1.3f,
            )
        )

        assertEquals(0f, applied.frequencyPenalty!!, 0f)
        assertEquals(0f, applied.presencePenalty!!, 0f)
        assertEquals(0f, applied.minP!!, 0f)
        assertEquals(0f, applied.topA!!, 0f)
        assertEquals(1f, applied.repetitionPenalty!!, 0f)
    }

    @Test
    fun `preset import should map stop string from chat squash`() {
        val payload = parseAssistantImportFromJson(
            jsonString = """
                {
                  "name": "Preset Stop",
                  "prompts": [
                    {
                      "identifier": "main",
                      "role": "system",
                      "content": "Main"
                    }
                  ],
                  "prompt_order": [
                    {
                      "character_id": 100000,
                      "order": [
                        { "identifier": "main", "enabled": true }
                      ]
                    }
                  ],
                  "extensions": {
                    "SPreset": {
                      "ChatSquash": {
                        "enable_stop_string": true,
                        "stop_string": "User:"
                      }
                    }
                  }
                }
            """.trimIndent(),
            sourceName = "preset-stop",
        )

        assertEquals(listOf("User:"), payload.assistant.stopSequences)
    }

    @Test
    fun `preset import should ignore disabled top level stop strings`() {
        val payload = parseAssistantImportFromJson(
            jsonString = """
                {
                  "name": "Preset Disabled Stops",
                  "enable_stop_string": false,
                  "stop_strings": ["User:", " Assistant: "],
                  "prompts": [
                    {
                      "identifier": "main",
                      "role": "system",
                      "content": "Main"
                    }
                  ],
                  "prompt_order": [
                    {
                      "character_id": 100000,
                      "order": [
                        { "identifier": "main", "enabled": true }
                      ]
                    }
                  ]
                }
            """.trimIndent(),
            sourceName = "preset-disabled-stops",
        )

        assertEquals(emptyList<String>(), payload.assistant.stopSequences)
    }

    @Test
    fun `preset import should preserve raw preset json for unknown fields`() {
        val payload = parseAssistantImportFromJson(
            jsonString = """
                {
                  "name": "Preset Raw",
                  "stream_openai": false,
                  "bias_preset_selected": "Mirostat",
                  "prompts": [
                    {
                      "identifier": "main",
                      "role": "system",
                      "content": "Main"
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
            sourceName = "preset-raw",
        )

        assertEquals("Mirostat", payload.presetRawJson["bias_preset_selected"]?.toString()?.trim('"'))
        assertEquals("false", payload.presetRawJson["stream_openai"]?.toString())
        assertNotNull(payload.presetRawJson["extensions"])
        assertNotNull(payload.presetRawJson["prompt_order"])
    }

    @Test
    fun `should parse character card with embedded lorebook`() {
        val json = """
            {
              "spec": "chara_card_v2",
              "data": {
                "name": "Seraphina",
                "description": "Guardian of the forest",
                "personality": "Warm",
                "scenario": "Forest glade",
                "first_mes": "Welcome.",
                "mes_example": "<START>\n{{char}}: Hello there",
                "system_prompt": "Card main override",
                "post_history_instructions": "Card jailbreak",
                "creator_notes": "Keep her gentle",
                "alternate_greetings": ["Greetings."],
                "extensions": {
                  "depth_prompt": {
                    "prompt": "Depth note",
                    "depth": 2,
                    "role": "assistant"
                  }
                },
                "character_book": {
                  "name": "Seraphina Book",
                  "entries": [
                    {
                      "comment": "Glade",
                      "content": "The glade is protected.",
                      "keys": ["glade"],
                      "extensions": {
                        "position": 4,
                        "match_persona_description": true,
                        "depth": 2,
                        "role": 2
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val payload = parseAssistantImportFromJson(
            jsonString = json,
            sourceName = "character",
        )

        assertEquals(AssistantImportKind.CHARACTER_CARD, payload.kind)
        assertEquals("Welcome.", payload.assistant.presetMessages.single().toText())
        assertEquals("Depth note", payload.assistant.stCharacterData?.depthPrompt?.prompt)
        assertEquals(MessageRole.ASSISTANT, payload.assistant.stCharacterData?.depthPrompt?.role)
        assertEquals(1, payload.lorebooks.size)
        val entry = payload.lorebooks.single().entries.single()
        assertNotNull(payload.presetTemplate)
        assertEquals(InjectionPosition.AT_DEPTH, entry.position)
        assertEquals(MessageRole.ASSISTANT, entry.role)
        assertEquals(true, entry.matchPersonaDescription)
        assertEquals(2, entry.injectDepth)
    }

    @Test
    fun `character card import should defer avatar copy until explicitly materialized`() {
        val payload = parseAssistantImportFromJson(
            jsonString = """
                {
                  "spec": "chara_card_v2",
                  "data": {
                    "name": "Seraphina"
                  }
                }
            """.trimIndent(),
            sourceName = "character",
            avatarImportSourceUri = "content://imports/seraphina.png",
        )

        assertEquals(Avatar.Dummy, payload.assistant.avatar)
        assertEquals("content://imports/seraphina.png", payload.avatarImportSourceUri)

        val materialized = payload.withMaterializedImportedAvatar("file:///tmp/seraphina.png")

        assertEquals(Avatar.Image("file:///tmp/seraphina.png"), materialized.assistant.avatar)
        assertNull(materialized.avatarImportSourceUri)
    }

    @Test
    fun `should preserve core character book regex semantics`() {
        val json = """
            {
              "spec": "chara_card_v2",
              "data": {
                "name": "Seraphina",
                "character_book": {
                  "scan_depth": 9,
                  "token_budget": 256,
                  "recursive_scanning": true,
                  "entries": [
                    {
                      "comment": "Regex Entry",
                      "content": "Regex content",
                      "keys": ["gl.*"],
                      "secondary_keys": ["/forest/i"],
                      "use_regex": true,
                      "extensions": {}
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val payload = parseAssistantImportFromJson(
            jsonString = json,
            sourceName = "character",
        )

        val lorebook = payload.lorebooks.single()
        val entry = lorebook.entries.single()
        assertEquals(true, entry.useRegex)
        assertEquals(9, entry.scanDepth)
        assertEquals(true, lorebook.recursiveScanning)
        assertEquals(256, lorebook.tokenBudget)
    }

    @Test
    fun `should preserve ST character book anchor positions`() {
        val json = """
            {
              "spec": "chara_card_v2",
              "data": {
                "name": "Seraphina",
                "character_book": {
                  "entries": [
                    {
                      "comment": "AN Top",
                      "content": "AN Top content",
                      "keys": ["alpha"],
                      "extensions": {
                        "position": 2
                      }
                    },
                    {
                      "comment": "AN Bottom",
                      "content": "AN Bottom content",
                      "keys": ["beta"],
                      "extensions": {
                        "position": 3
                      }
                    },
                    {
                      "comment": "EM Top",
                      "content": "EM Top content",
                      "keys": ["gamma"],
                      "extensions": {
                        "position": 5
                      }
                    },
                    {
                      "comment": "EM Bottom",
                      "content": "EM Bottom content",
                      "keys": ["delta"],
                      "extensions": {
                        "position": 6
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val payload = parseAssistantImportFromJson(
            jsonString = json,
            sourceName = "character",
        )

        assertEquals(
            listOf(
                InjectionPosition.AUTHOR_NOTE_TOP,
                InjectionPosition.AUTHOR_NOTE_BOTTOM,
                InjectionPosition.EXAMPLE_MESSAGES_TOP,
                InjectionPosition.EXAMPLE_MESSAGES_BOTTOM,
            ),
            payload.lorebooks.single().entries.map { it.position }
        )
    }

    @Test
    fun `should preserve ST outlet position`() {
        val payload = parseAssistantImportFromJson(
            jsonString = """
                {
                  "spec": "chara_card_v2",
                  "data": {
                    "name": "Seraphina",
                    "character_book": {
                      "entries": [
                        {
                          "comment": "Memory",
                          "content": "Stored memory",
                          "keys": ["alpha"],
                          "extensions": {
                            "position": 7,
                            "outlet_name": "memory"
                          }
                        }
                      ]
                    }
                  }
                }
            """.trimIndent(),
            sourceName = "character-outlet",
        )

        val entry = payload.lorebooks.single().entries.single()
        assertEquals(InjectionPosition.OUTLET, entry.position)
        assertEquals("memory", entry.stMetadata["outlet_name"])
    }

    @Test
    fun `character card import should keep regexes on assistant level`() {
        val payload = parseAssistantImportFromJson(
            jsonString = """
                {
                  "spec": "chara_card_v2",
                  "data": {
                    "name": "Regex Character",
                    "extensions": {
                      "regex_scripts": [
                        {
                          "scriptName": "Card Regex",
                          "findRegex": "hello",
                          "replaceString": "hi",
                          "placement": [2]
                        }
                      ]
                    }
                  }
                }
            """.trimIndent(),
            sourceName = "character-routing",
        )

        val currentAssistant = Assistant()
        val application = applyImportedAssistantToExisting(
            currentAssistant = currentAssistant,
            payload = payload,
            existingLorebooks = emptyList(),
            existingSharedRegexes = emptyList(),
            includeRegexes = true,
        )

        assertEquals(1, application.assistant.regexes.size)
        assertEquals(0, application.sharedRegexes.size)
    }

    @Test
    fun `character card png metadata decoder should keep raw json`() {
        val rawJson = """{"spec":"chara_card_v2","data":{"name":"Seraphina"}}"""

        val decoded = decodeImportedCharacterCardJson(rawJson)

        assertEquals(rawJson, decoded)
    }

    @Test
    fun `character card png metadata decoder should decode legacy base64 payload`() {
        val rawJson = """{"spec":"chara_card_v2","data":{"name":"Seraphina"}}"""
        val encoded = rawJson.base64Encode()

        val decoded = decodeImportedCharacterCardJson(encoded)

        assertEquals(rawJson, decoded)
    }

    @Test
    fun `preset regex import should preserve trim edit and placement semantics`() {
        val payload = parseAssistantImportFromJson(
            jsonString = """
                {
                  "name": "Preset Regex Semantics",
                  "prompts": [
                    { "identifier": "main", "role": "system", "content": "Main" }
                  ],
                  "prompt_order": [
                    {
                      "character_id": 100000,
                      "order": [
                        { "identifier": "main", "enabled": true }
                      ]
                    }
                  ],
                  "extensions": {
                    "regex_scripts": [
                      {
                        "scriptName": "Reasoning Trim",
                        "findRegex": "(foo)",
                        "replaceString": "$1",
                        "placement": [6],
                        "trimStrings": ["f"],
                        "runOnEdit": false,
                        "substituteRegex": 2
                      }
                    ]
                  }
                }
            """.trimIndent(),
            sourceName = "preset-semantics",
        )

        val regex = payload.regexes.single()
        assertEquals(listOf("f"), regex.trimStrings)
        assertEquals(false, regex.runOnEdit)
        assertEquals(2, regex.substituteRegex)
        assertEquals(setOf(AssistantRegexPlacement.REASONING), regex.stPlacements)
    }

    @Test
    fun `preset regex import should preserve markdown and prompt phases together`() {
        val payload = parseAssistantImportFromJson(
            jsonString = """
                {
                  "name": "Preset Regex Dual Phase",
                  "prompts": [
                    { "identifier": "main", "role": "system", "content": "Main" }
                  ],
                  "prompt_order": [
                    {
                      "character_id": 100000,
                      "order": [
                        { "identifier": "main", "enabled": true }
                      ]
                    }
                  ],
                  "extensions": {
                    "regex_scripts": [
                      {
                        "scriptName": "Dual Phase",
                        "findRegex": "foo",
                        "replaceString": "bar",
                        "placement": [2],
                        "promptOnly": true,
                        "markdownOnly": true
                      }
                    ]
                  }
                }
            """.trimIndent(),
            sourceName = "preset-dual-phase",
        )

        val regex = payload.regexes.single()
        assertEquals(true, regex.promptOnly)
        assertEquals(true, regex.visualOnly)
        assertEquals(setOf(AssistantRegexPlacement.AI_OUTPUT), regex.stPlacements)
    }

    @Test
    fun `character book import should preserve extended worldbook metadata`() {
        val payload = parseAssistantImportFromJson(
            jsonString = """
                {
                  "spec": "chara_card_v2",
                  "data": {
                    "name": "Metadata Character",
                    "character_book": {
                      "entries": [
                        {
                          "comment": "Metadata Entry",
                          "content": "Metadata content",
                          "keys": ["alpha"],
                          "extensions": {
                            "probability": 25,
                            "useProbability": false,
                            "group": "facts",
                            "group_override": true,
                            "group_weight": 250,
                            "use_group_scoring": true,
                            "delay_until_recursion": 2,
                            "triggers": ["continue"],
                            "ignore_budget": true,
                            "outlet_name": "memory",
                            "custom_toggle": true
                          }
                        }
                      ]
                    }
                  }
                }
            """.trimIndent(),
            sourceName = "character-metadata",
        )

        val entry = payload.lorebooks.single().entries.single()
        assertNull(entry.probability)
        assertEquals("facts", entry.stExtension().group)
        assertEquals(true, entry.stExtension().groupOverride)
        assertEquals(250, entry.stExtension().groupWeight)
        assertEquals(true, entry.stExtension().useGroupScoring)
        assertEquals("2", entry.stExtension().delayUntilRecursion)
        assertEquals(2, entry.stExtension().recursionDelayLevel())
        assertEquals(listOf("continue"), entry.stExtension().triggers)
        assertEquals(true, entry.stExtension().ignoreBudget)
        assertEquals("memory", entry.stExtension().outletName)
        assertEquals("[\"continue\"]", entry.stMetadata["triggers"])
        assertEquals("false", entry.stMetadata["useProbability"])
        assertEquals("true", entry.stMetadata["custom_toggle"])
    }
}
