package me.rerere.rikkahub.data.ai.openai

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAICodexAuthServiceTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `device code accepts string poll interval returned by OpenAI`() {
        val response = json.decodeFromString<DeviceCodeResponse>(
            """{"device_auth_id":"deviceauth_123","user_code":"ABCD-EFGH","interval":"5"}"""
        )

        assertEquals(5L, response.intervalSeconds)
    }

    @Test
    fun `device code accepts numeric poll interval`() {
        val response = json.decodeFromString<DeviceCodeResponse>(
            """{"device_auth_id":"deviceauth_123","user_code":"ABCD-EFGH","interval":7}"""
        )

        assertEquals(7L, response.intervalSeconds)
    }

    @Test
    fun `auth error extracts nested OpenAI message`() {
        val body = """{"error":{"code":"access_denied","message":"Region is not supported"}}"""

        assertEquals("Region is not supported", extractOpenAIAuthError(body))
    }

    @Test
    fun `auth error converts html response to readable text`() {
        val body = """<html><body><h1>Access denied</h1><p>Request blocked</p></body></html>"""

        assertEquals("Access denied Request blocked", extractOpenAIAuthError(body))
    }
}
