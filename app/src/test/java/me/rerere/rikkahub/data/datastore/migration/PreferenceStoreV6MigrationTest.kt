package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import me.rerere.common.json.JsonInstant
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceStoreV6MigrationTest {

    private val migration = PreferenceStoreV6Migration()

    private fun providerIds(prefs: androidx.datastore.preferences.core.Preferences): List<String> =
        JsonInstant.parseToJsonElement(prefs[SettingsStore.PROVIDERS]!!).jsonArray
            .filterIsInstance<JsonObject>()
            .mapNotNull { (it["id"] as? JsonPrimitive)?.contentOrNull }

    private fun providerName(prefs: androidx.datastore.preferences.core.Preferences, id: String): String? =
        JsonInstant.parseToJsonElement(prefs[SettingsStore.PROVIDERS]!!).jsonArray
            .filterIsInstance<JsonObject>()
            .firstOrNull { (it["id"] as? JsonPrimitive)?.contentOrNull == id }
            ?.let { (it["name"] as? JsonPrimitive)?.contentOrNull }

    private val anthropicId = "7e1d3a2b-9c4f-4e8a-b6d1-0a1b2c3d4e5f"
    private val openaiId = "1eeea727-9ee5-4cae-93e6-6fb01a4d051e"
    private val rikkahubId = "a8d2d463-e8c0-41f2-b89e-f5eb8e716cce"
    private val geminiId = "6ab18148-c138-4394-a46f-1cd8c8ceaa6d"
    private val deepseekId = "f099ad5b-ef03-446d-8e78-7e36787f780b"

    @Test
    fun `shouldMigrate only below v6`() = runBlocking {
        assertTrue(migration.shouldMigrate(mutablePreferencesOf()))
        assertTrue(migration.shouldMigrate(preferencesOf(SettingsStore.VERSION to 5)))
        assertFalse(migration.shouldMigrate(preferencesOf(SettingsStore.VERSION to 6)))
        assertFalse(migration.shouldMigrate(preferencesOf(SettingsStore.VERSION to 7)))
    }

    @Test
    fun `fresh install seeds Anthropic then OpenAI and stamps the version`() = runBlocking {
        val out = migration.migrate(mutablePreferencesOf())

        val ids = providerIds(out)
        assertEquals(listOf(anthropicId, openaiId), ids)
        assertEquals("Anthropic", providerName(out, anthropicId))
        assertEquals(6, out[SettingsStore.VERSION])
    }

    @Test
    fun `pristine legacy built-in is removed, defaults are seeded`() = runBlocking {
        val providers = """
            [{"type":"openai","id":"$rikkahubId","name":"RikkaHub",
              "baseUrl":"https://api.rikka-ai.com/v1","apiKey":"","models":[]}]
        """.trimIndent()
        val out = migration.migrate(preferencesOf(SettingsStore.PROVIDERS to providers))

        val ids = providerIds(out)
        assertFalse("pristine RikkaHub must be dropped", rikkahubId in ids)
        assertTrue(anthropicId in ids)
        assertTrue(openaiId in ids)
    }

    @Test
    fun `pristine RikkaHub is dropped even though it ships the seeded auto model`() = runBlocking {
        // The hosted gateway shipped the "auto" model baked in; it must NOT count as a user model, or a
        // credential-free RikkaHub survives and stale pointers keep resolving to the removed gateway.
        val providers = """
            [{"type":"openai","id":"$rikkahubId","name":"RikkaHub","baseUrl":"https://api.rikka-ai.com/v1",
              "apiKey":"","models":[{"id":"b7055fb4-39f9-4042-a88a-0d80ed76cf08","modelId":"auto"}]}]
        """.trimIndent()
        val out = migration.migrate(preferencesOf(SettingsStore.PROVIDERS to providers))

        assertFalse("RikkaHub-with-only-auto must be dropped", rikkahubId in providerIds(out))
    }

    @Test
    fun `a legacy provider the user configured (apiKey) is kept`() = runBlocking {
        val providers = """
            [{"type":"openai","id":"$deepseekId","name":"DeepSeek",
              "baseUrl":"https://api.deepseek.com/v1","apiKey":"sk-secret","models":[]}]
        """.trimIndent()
        val out = migration.migrate(preferencesOf(SettingsStore.PROVIDERS to providers))

        assertTrue("a keyed legacy provider survives as user-owned", deepseekId in providerIds(out))
    }

    @Test
    fun `a legacy provider with models is kept even without a key`() = runBlocking {
        val providers = """
            [{"type":"openai","id":"$deepseekId","name":"DeepSeek","baseUrl":"https://api.deepseek.com/v1",
              "apiKey":"","models":[{"id":"11111111-1111-4111-8111-111111111111","modelId":"deepseek-chat"}]}]
        """.trimIndent()
        val out = migration.migrate(preferencesOf(SettingsStore.PROVIDERS to providers))

        assertTrue(deepseekId in providerIds(out))
    }

    @Test
    fun `a Gemini configured via Vertex service account (no apiKey) is NOT removed`() = runBlocking {
        // Configured through a non-apiKey credential surface: the pristine check must look beyond apiKey
        // (privateKey / serviceAccountEmail / the vertexAI flag) so a real config isn't read as empty
        // and wrongly removed.
        val providers = """
            [{"type":"google","id":"$geminiId","name":"Gemini","apiKey":"","models":[],
              "vertexAI":true,"useServiceAccount":true,
              "privateKey":"-----BEGIN PRIVATE KEY-----abc","serviceAccountEmail":"svc@proj.iam.example"}]
        """.trimIndent()
        val out = migration.migrate(preferencesOf(SettingsStore.PROVIDERS to providers))

        assertTrue("a Vertex-configured Gemini must survive", geminiId in providerIds(out))
    }

    @Test
    fun `existing OpenAI default is not duplicated`() = runBlocking {
        val providers = """
            [{"type":"openai","id":"$openaiId","name":"OpenAI","baseUrl":"https://api.openai.com/v1",
              "apiKey":"sk-mine","models":[]}]
        """.trimIndent()
        val out = migration.migrate(preferencesOf(SettingsStore.PROVIDERS to providers))

        val ids = providerIds(out)
        assertEquals("OpenAI present exactly once", 1, ids.count { it == openaiId })
        assertTrue("Anthropic still seeded", anthropicId in ids)
    }

    @Test
    fun `malformed providers json degrades to a clean seed`() = runBlocking {
        val out = migration.migrate(preferencesOf(SettingsStore.PROVIDERS to "not json {"))
        assertEquals(listOf(anthropicId, openaiId), providerIds(out))
    }
}
