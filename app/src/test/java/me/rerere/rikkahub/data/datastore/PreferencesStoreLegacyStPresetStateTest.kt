package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.SillyTavernPromptTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferencesStoreLegacyStPresetStateTest {
    @Test
    fun `normalize should migrate selected assistant st preset into global preset`() {
        val selectedTemplate = SillyTavernPromptTemplate(sourceName = "Selected")
        val otherTemplate = SillyTavernPromptTemplate(sourceName = "Other")
        val selectedAssistant = Assistant(
            name = "Selected",
            stPromptTemplate = selectedTemplate,
        )
        val otherAssistant = Assistant(
            name = "Other",
            stPromptTemplate = otherTemplate,
        )

        val result = Settings(
            assistantId = selectedAssistant.id,
            assistants = listOf(selectedAssistant, otherAssistant),
        ).normalizeLegacyStPresetState()

        assertEquals(selectedTemplate, result.stPresetTemplate)
        assertEquals(true, result.stPresetEnabled)
        assertTrue(result.assistants.all { it.stPromptTemplate == null })
    }

    @Test
    fun `normalize should keep existing global preset and clear assistant copies`() {
        val globalTemplate = SillyTavernPromptTemplate(sourceName = "Global")
        val legacyAssistantTemplate = SillyTavernPromptTemplate(sourceName = "Legacy Assistant")
        val assistant = Assistant(
            name = "Assistant",
            stPromptTemplate = legacyAssistantTemplate,
        )
        val settings = Settings(
            assistantId = assistant.id,
            assistants = listOf(assistant),
            stPresetEnabled = false,
            stPresetTemplate = globalTemplate,
        )

        val result = settings.normalizeLegacyStPresetState()

        assertEquals(globalTemplate, result.stPresetTemplate)
        assertFalse(result.stPresetEnabled)
        assertNull(result.assistants.single().stPromptTemplate)
    }

    @Test
    fun `normalize should return same instance when no legacy state exists`() {
        val settings = Settings()

        val result = settings.normalizeLegacyStPresetState()

        assertSame(settings, result)
    }
}
