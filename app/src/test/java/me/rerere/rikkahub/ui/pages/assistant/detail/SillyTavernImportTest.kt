package me.rerere.rikkahub.ui.pages.assistant.detail

import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.findPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
                  "enabled": true
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
        assertEquals(listOf("main", "chatHistory"), payload.assistant.stPromptTemplate?.orderedPromptIds)
        assertEquals(2, payload.regexes.size)
        assertFalse(payload.assistant.stPromptTemplate?.findPrompt("main")?.content?.contains("<regex>") == true)
        assertEquals("Prefill", payload.assistant.stPromptTemplate?.assistantPrefill)
        assertEquals(true, payload.assistant.stPromptTemplate?.continuePrefill)
        assertEquals("\n", payload.assistant.stPromptTemplate?.continuePostfix)
        assertEquals("[Start]", payload.assistant.stPromptTemplate?.newChatPrompt)
        assertEquals("[Examples]", payload.assistant.stPromptTemplate?.newExampleChatPrompt)
        assertEquals(true, payload.assistant.stPromptTemplate?.squashSystemMessages)
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
        assertNotNull(payload.assistant.stPromptTemplate)
        assertEquals(InjectionPosition.AT_DEPTH, entry.position)
        assertEquals(MessageRole.ASSISTANT, entry.role)
        assertEquals(2, entry.injectDepth)
    }
}
