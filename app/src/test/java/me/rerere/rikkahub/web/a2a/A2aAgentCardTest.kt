package me.rerere.rikkahub.web.a2a

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class A2aAgentCardTest {

    @Test
    fun `agent card exposes only spawnable assistants and no secrets`() {
        val spawnable = Assistant(
            id = Uuid.parse("11111111-1111-1111-1111-111111111111"),
            name = "Spawn",
            description = "Spawn me",
            spawnable = true,
        )
        val blocked = Assistant(
            id = Uuid.parse("22222222-2222-2222-2222-222222222222"),
            name = "Blocked",
            description = "Blocked",
            spawnable = false,
        )
        val settings = Settings(
            webServerAccessPassword = "password",
            providers = listOf(
                ProviderSetting.OpenAI(apiKey = "openai-secret"),
            ),
            assistants = listOf(spawnable, blocked),
        )

        val card = settings.toA2aAgentCard(baseUrl = "http://localhost:9000", bearerRequired = true)
        assertEquals("http://localhost:9000/a2a", card.url)
        assertEquals(1, card.skills.size)
        assertEquals(spawnable.id.toString(), card.skills.first().id)
        assertFalse(card.skills.any { it.id == blocked.id.toString() })
        assertEquals("static", card.securitySchemes["bearerAuth"]?.bearerFormat)
        assertTrue(card.security.any { it.containsKey("bearerAuth") })

        val json = Json.encodeToString(card)
        assertFalse(json.contains("openai-secret"))
        assertFalse(json.contains("password"))
        assertFalse(json.contains("customHeaders"))
        assertFalse(json.contains("apiKeys"))
    }

    @Test
    fun `agent card omits security when localhost compatibility needs no token`() {
        val card = Settings().toA2aAgentCard(baseUrl = "http://127.0.0.1:9000", bearerRequired = false)

        assertEquals("http://127.0.0.1:9000/a2a", card.url)
        assertTrue(card.securitySchemes.isEmpty())
        assertTrue(card.security.isEmpty())
    }
}
