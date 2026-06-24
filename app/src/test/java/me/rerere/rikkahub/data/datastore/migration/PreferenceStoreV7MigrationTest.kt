package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import me.rerere.ai.provider.OpenAIMode
import me.rerere.ai.provider.ProviderSetting
import me.rerere.common.json.JsonInstant
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v6 -> v7: the standalone ChatGPT provider (`{"type":"chatgpt"}`) folds into OpenAI as
 * [OpenAIMode.ChatGPT]. The migration rewrites the raw JSON before any decode; this proves the rewrite
 * is lossless (the result decodes to a ChatGPT-mode OpenAI with the same token/baseUrl) and that a
 * non-ChatGPT provider is untouched.
 */
class PreferenceStoreV7MigrationTest {

    private val migration = PreferenceStoreV7Migration()

    private fun providers(prefs: Preferences): List<JsonObject> =
        JsonInstant.parseToJsonElement(prefs[SettingsStore.PROVIDERS]!!).jsonArray
            .filterIsInstance<JsonObject>()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    @Test
    fun `shouldMigrate only below v7`() = runBlocking {
        assertTrue(migration.shouldMigrate(mutablePreferencesOf()))
        assertTrue(migration.shouldMigrate(preferencesOf(SettingsStore.VERSION to 6)))
        assertFalse(migration.shouldMigrate(preferencesOf(SettingsStore.VERSION to 7)))
    }

    @Test
    fun `a chatgpt provider becomes an openai provider in chatgpt mode, losslessly`() = runBlocking {
        val chatgptJson =
            """[{"type":"chatgpt","id":"11111111-1111-1111-1111-111111111111","enabled":true,""" +
                """"name":"ChatGPT","models":[],"accessToken":"sk-codex-tok",""" +
                """"baseUrl":"https://chatgpt.com/backend-api/codex"}]"""
        val input = preferencesOf(
            SettingsStore.VERSION to 6,
            SettingsStore.PROVIDERS to chatgptJson,
        )

        val out = migration.migrate(input)

        // Raw JSON: discriminator flipped + mode added.
        val obj = providers(out).single()
        assertEquals("openai", obj.str("type"))
        assertEquals("chatgpt", obj.str("mode"))
        assertEquals(7, out[SettingsStore.VERSION])

        // And it now decodes cleanly as a ChatGPT-mode OpenAI carrying the original credential.
        val decoded = JsonInstant.decodeFromString<List<ProviderSetting>>(out[SettingsStore.PROVIDERS]!!).single()
        assertTrue(decoded is ProviderSetting.OpenAI)
        decoded as ProviderSetting.OpenAI
        assertEquals(OpenAIMode.ChatGPT, decoded.mode)
        assertEquals("sk-codex-tok", decoded.accessToken)
        assertEquals("https://chatgpt.com/backend-api/codex", decoded.baseUrl)
        assertEquals("ChatGPT", decoded.name)
    }

    @Test
    fun `a non-chatgpt provider is left untouched`() = runBlocking {
        val openaiJson =
            """[{"type":"openai","id":"22222222-2222-2222-2222-222222222222","enabled":true,""" +
                """"name":"OpenAI","models":[],"apiKey":"sk-plain","baseUrl":"https://api.openai.com/v1"}]"""
        val input = preferencesOf(
            SettingsStore.VERSION to 6,
            SettingsStore.PROVIDERS to openaiJson,
        )

        val out = migration.migrate(input)

        val obj = providers(out).single()
        assertEquals("openai", obj.str("type"))
        assertNull("a standard OpenAI gains no mode key from this migration", obj.str("mode"))
        assertEquals("sk-plain", obj.str("apiKey"))
        assertEquals(7, out[SettingsStore.VERSION])
    }

    @Test
    fun `empty provider list still advances the version`() = runBlocking {
        val out = migration.migrate(preferencesOf(SettingsStore.VERSION to 6))
        assertEquals(7, out[SettingsStore.VERSION])
    }
}
