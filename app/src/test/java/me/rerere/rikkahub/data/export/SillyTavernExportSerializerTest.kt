package me.rerere.rikkahub.data.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.SillyTavernCharacterData
import me.rerere.rikkahub.data.model.SillyTavernPreset
import me.rerere.rikkahub.data.model.SillyTavernPresetSampling
import me.rerere.rikkahub.data.model.defaultSillyTavernPromptTemplate
import org.junit.Assert.assertEquals
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
