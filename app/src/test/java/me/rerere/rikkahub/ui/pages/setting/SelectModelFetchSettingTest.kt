package me.rerere.rikkahub.ui.pages.setting

import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertSame
import org.junit.Test

class SelectModelFetchSettingTest {
    @Test
    fun `uses draft credentials when draft is the same provider type`() {
        val persisted = ProviderSetting.OpenAI(
            name = "My OpenAI",
            apiKey = "old-key",
            baseUrl = "https://api.openai.com/v1"
        )
        // Same type, fresh key just typed in the Config tab (not yet saved).
        val draft = persisted.copy(apiKey = "just-typed-key")

        val result = selectModelFetchSetting(persisted = persisted, draft = draft)

        // The just-typed key must drive the fetch without a Save/restart.
        assertSame(draft, result)
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
