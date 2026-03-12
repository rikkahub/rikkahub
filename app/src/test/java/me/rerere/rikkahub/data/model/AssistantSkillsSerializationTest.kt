package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantSkillsSerializationTest {
    @Test
    fun `decode old assistant json should keep skills defaults`() {
        val assistant = JsonInstant.decodeFromString<Assistant>(
            """
                {
                  "name": "Legacy Assistant",
                  "termuxNeedsApproval": false
                }
            """.trimIndent()
        )

        assertEquals("Legacy Assistant", assistant.name)
        assertFalse(assistant.termuxNeedsApproval)
        assertFalse(assistant.skillsEnabled)
        assertTrue(assistant.selectedSkills.isEmpty())
    }
}
