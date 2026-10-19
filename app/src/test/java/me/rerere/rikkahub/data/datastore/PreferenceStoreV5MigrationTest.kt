package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.QuotaQuery
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.data.datastore.migration.migrateProvidersQuotaQuery
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PreferenceStoreV5MigrationTest {
    private val bearer = listOf(CustomHeader("Authorization", "Bearer {{apiKey}}"))

    private fun migrate(json: String): List<ProviderSetting> =
        JsonInstant.decodeFromString<List<ProviderSetting>>(migrateProvidersQuotaQuery(json))

    @Test
    fun `openai balance option with relative path becomes base url template`() {
        val (provider) = migrate(
            """[{"type":"openai","name":"DeepSeek","baseUrl":"https://api.deepseek.com/v1",
                "balanceOption":{"enabled":true,"apiPath":"/user/balance","resultPath":"balance_infos[0].total_balance"}}]"""
        )

        assertEquals(
            QuotaQuery(
                enabled = true,
                url = "{{baseUrl}}/user/balance",
                headers = bearer,
                resultPath = "balance_infos[0].total_balance",
            ),
            (provider as ProviderSetting.OpenAI).quotaQuery,
        )
    }

    @Test
    fun `openai balance option with absolute url keeps the url`() {
        val (provider) = migrate(
            """[{"type":"openai","balanceOption":{"enabled":false,"apiPath":"https://billing.example.com/credits","resultPath":"total"}}]"""
        )

        assertEquals(
            QuotaQuery(enabled = false, url = "https://billing.example.com/credits", headers = bearer, resultPath = "total"),
            (provider as ProviderSetting.OpenAI).quotaQuery,
        )
    }

    @Test
    fun `balance option on other types is dropped`() {
        val json = """[
            {"type":"google","balanceOption":{"enabled":true,"apiPath":"/credits","resultPath":"x"}},
            {"type":"claude","balanceOption":{"enabled":true,"apiPath":"/credits","resultPath":"x"}}
        ]"""

        val raw = JsonInstant.parseToJsonElement(migrateProvidersQuotaQuery(json)).jsonArray.map { it.jsonObject }
        raw.forEach {
            assertFalse("balanceOption" in it)
            assertFalse("quotaQuery" in it)
        }
        val (google, claude) = migrate(json)
        assertEquals(ProviderSetting.Google().quotaQuery, (google as ProviderSetting.Google).quotaQuery)
        assertEquals(ProviderSetting.Claude().quotaQuery, (claude as ProviderSetting.Claude).quotaQuery)
    }

    @Test
    fun `provider override nested in models is migrated`() {
        val (provider) = migrate(
            """[{"type":"claude","models":[{"modelId":"m","providerOverwrite":
                {"type":"openai","balanceOption":{"enabled":true,"apiPath":"/credits","resultPath":"total"}}}]}]"""
        )

        val override = provider.models.single().providerOverwrite as ProviderSetting.OpenAI
        assertEquals(
            QuotaQuery(enabled = true, url = "{{baseUrl}}/credits", headers = bearer, resultPath = "total"),
            override.quotaQuery,
        )
    }

    @Test
    fun `migration can be applied again without changing the result`() {
        val once = migrateProvidersQuotaQuery(
            """[{"type":"openai","balanceOption":{"enabled":true,"apiPath":"/credits","resultPath":"total"}}]"""
        )

        assertEquals(once, migrateProvidersQuotaQuery(once))
    }

    @Test
    fun `unparseable providers are left untouched`() {
        assertEquals("not json", migrateProvidersQuotaQuery("not json"))
    }

    @Test
    fun `backup settings json is migrated`() {
        val migrated = SettingsJsonMigrator.migrate(
            """{"providers":[{"type":"openai","balanceOption":{"enabled":true,"apiPath":"/credits","resultPath":"total"}}]}"""
        )

        val provider = JsonInstant.parseToJsonElement(migrated).jsonObject["providers"] as JsonArray
        assertFalse("balanceOption" in provider.single().jsonObject)
        assertEquals(
            "{{baseUrl}}/credits",
            JsonInstant.decodeFromJsonElement(ProviderSetting.serializer(), provider.single())
                .let { (it as ProviderSetting.OpenAI).quotaQuery.url },
        )
    }
}
