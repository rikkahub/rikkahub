package me.rerere.rikkahub.web.a2a

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.uuid.Uuid

class A2aSkillValidationTest {

    @Test
    fun `unknown skill is rejected`() {
        val settings = Settings(
            assistants = listOf(
                Assistant(name = "Spawn", spawnable = true, id = Uuid.parse("11111111-1111-1111-1111-111111111111")),
            ),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            validateSpawnableSkill(settings, "99999999-9999-9999-9999-999999999999")
        }
        assertEquals("skill not found: 99999999-9999-9999-9999-999999999999", error.message)
    }

    @Test
    fun `non-spawnable skill is rejected`() {
        val assistant = Assistant(name = "NoSpawn", spawnable = false)
        val settings = Settings(
            assistants = listOf(assistant),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            validateSpawnableSkill(settings, assistant.id.toString())
        }
        assertEquals("skill is not spawnable: ${assistant.id}", error.message)
    }
}
