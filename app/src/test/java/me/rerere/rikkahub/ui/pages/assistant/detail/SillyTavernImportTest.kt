package me.rerere.rikkahub.ui.pages.assistant.detail

import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantRegexPlacement
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.findPrompt
import me.rerere.rikkahub.data.model.findPromptOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(2, payload.regexes.size)
        assertFalse(payload.presetTemplate?.findPrompt("main")?.content?.contains("<regex>") == true)
        assertEquals(listOf("normal", "continue"), payload.presetTemplate?.findPrompt("main")?.injectionTriggers)
        assertEquals("Prefill", payload.presetTemplate?.assistantPrefill)
        assertEquals(true, payload.presetTemplate?.continuePrefill)
        assertEquals("\n", payload.presetTemplate?.continuePostfix)
        assertEquals("[Start]", payload.presetTemplate?.newChatPrompt)
        assertEquals("[Examples]", payload.presetTemplate?.newExampleChatPrompt)
        assertEquals(true, payload.presetTemplate?.squashSystemMessages)
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
    fun `should map character book positions 3 and 6 to bottom of chat`() {
        val json = """
            {
              "spec": "chara_card_v2",
              "data": {
                "name": "Seraphina",
                "character_book": {
                  "entries": [
                    {
                      "comment": "Bottom A",
                      "content": "Bottom A content",
                      "keys": ["alpha"],
                      "extensions": {
                        "position": 3
                      }
                    },
                    {
                      "comment": "Bottom B",
                      "content": "Bottom B content",
                      "keys": ["beta"],
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
            listOf(InjectionPosition.BOTTOM_OF_CHAT, InjectionPosition.BOTTOM_OF_CHAT),
            payload.lorebooks.single().entries.map { it.position }
        )
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
            existingGlobalRegexes = emptyList(),
            includeRegexes = true,
        )

        assertEquals(1, application.assistant.regexes.size)
        assertEquals(0, application.globalRegexes.size)
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
                            "triggers": ["continue"],
                            "ignore_budget": true,
                            "outlet_name": "memory"
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
        assertEquals("facts", entry.stMetadata["group"])
        assertEquals("true", entry.stMetadata["group_override"])
        assertEquals("250", entry.stMetadata["group_weight"])
        assertEquals("true", entry.stMetadata["use_group_scoring"])
        assertEquals("[\"continue\"]", entry.stMetadata["triggers"])
        assertEquals("true", entry.stMetadata["ignore_budget"])
        assertEquals("memory", entry.stMetadata["outlet_name"])
        assertEquals("false", entry.stMetadata["useProbability"])
    }
}
