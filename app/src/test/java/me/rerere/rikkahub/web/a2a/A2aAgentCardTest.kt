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

    // Regression: the card's RPC base URL is derived ONLY from the server's bind config, never from an
    // inbound Host header. The previous builder read `request.origin.serverHost` (the raw Host), so an
    // attacker who controlled the Host a client sent could poison `card.url` and exfiltrate the bearer to
    // their own host (card-url poisoning -> token exfiltration / SSRF). `a2aCardBaseUrl` takes no request
    // at all, so Host reflection is structurally impossible.
    @Test
    fun `card base url for localhost bind is always loopback`() {
        assertEquals("http://127.0.0.1:9000", a2aCardBaseUrl(localhostOnly = true, lanIp = "10.0.0.5", port = 9000))
        assertEquals("http://127.0.0.1:8080", a2aCardBaseUrl(localhostOnly = true, lanIp = null, port = 8080))
    }

    @Test
    fun `card base url for LAN bind uses the resolved LAN ip`() {
        assertEquals(
            "http://192.168.1.42:9000",
            a2aCardBaseUrl(localhostOnly = false, lanIp = "192.168.1.42", port = 9000),
        )
    }

    @Test
    fun `card base url falls back to loopback when the LAN ip is unknown, never an external host`() {
        assertEquals("http://127.0.0.1:9000", a2aCardBaseUrl(localhostOnly = false, lanIp = null, port = 9000))
        assertEquals("http://127.0.0.1:9000", a2aCardBaseUrl(localhostOnly = false, lanIp = "  ", port = 9000))
    }

    @Test
    fun `card url is the bind-derived base plus a2a, independent of any Host`() {
        val base = a2aCardBaseUrl(localhostOnly = false, lanIp = "192.168.1.42", port = 9000)
        val card = Settings().toA2aAgentCard(baseUrl = base, bearerRequired = true)
        assertEquals("http://192.168.1.42:9000/a2a", card.url)
    }
}
