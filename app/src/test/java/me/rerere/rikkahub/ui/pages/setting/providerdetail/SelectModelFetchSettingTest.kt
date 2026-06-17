package me.rerere.rikkahub.ui.pages.setting.providerdetail

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SelectModelFetchSettingTest {
    @Test
    fun `uses draft credentials but persisted models when draft is the same provider type`() {
        val persisted = ProviderSetting.OpenAI(
            name = "My OpenAI",
            apiKey = "old-key",
            baseUrl = "https://api.openai.com/v1",
            // A model persisted during the session; the draft's snapshot predates it.
            models = listOf(Model(modelId = "gpt-4o", displayName = "gpt-4o")),
        )
        // Same type, fresh key just typed in the Config tab (not yet saved), carrying the STALE
        // (empty) model snapshot the draft was created with.
        val draft = persisted.copy(apiKey = "just-typed-key", models = emptyList())

        val result = selectModelFetchSetting(persisted = persisted, draft = draft) as ProviderSetting.OpenAI

        // The just-typed key drives the fetch without a Save/restart...
        assertEquals("just-typed-key", result.apiKey)
        // ...but models come from persisted (the draft snapshot is stale), so the chat-probe model
        // id stays current.
        assertEquals(listOf("gpt-4o"), result.models.map { it.modelId })
    }

    @Test
    fun `falls back to persisted provider when draft is a different type (pending unsaved type change)`() {
        val persisted = ProviderSetting.OpenAI(
            name = "My OpenAI",
            apiKey = "openai-key",
            baseUrl = "https://api.openai.com/v1"
        )
        // User switched provider TYPE in the Config tab but has NOT saved yet.
        val draft = ProviderSetting.Google(
            id = persisted.id,
            name = persisted.name,
            apiKey = "google-key",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta"
        )

        val result = selectModelFetchSetting(persisted = persisted, draft = draft)

        // Writes (add/del/edit/move) target the persisted OpenAI provider; fetching Google's models
        // from the draft and persisting them into the OpenAI provider would cross-contaminate.
        // The fetch must stay on the persisted provider until the type change is saved.
        assertSame(persisted, result)
    }
}
