package me.rerere.rikkahub.ui.pages.setting.providerdetail

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Regression test for: "editing a provider's API key does not take effect".
 *
 * The Config tab edits a config draft (apiKey/baseUrl/headers) while the Models tab
 * persists model-list ops (reorder/add/delete) immediately. On Save we must persist the
 * edited config from the draft AND keep the currently-persisted model list — so a reorder
 * done before pressing Save is not reverted by the draft's stale snapshot of models.
 *
 * This exercises [mergeConfigKeepingModels], the one pure seam the fix introduces. The
 * broader stale-apiKey -> 401 wiring (the listModels produceState keyed on the shared
 * draft so retrieve authenticates with the live-edited key) is Compose state with no pure
 * JVM seam; it is covered by compileDebugKotlin + lintDebug + a manual on-device check
 * documented in the PR body, not faked into a hollow Compose test here.
 */
class MergeConfigKeepingModelsTest {

    @Test
    fun editedConfigWins_and_persistedModelOrderIsPreserved() {
        val id = Uuid.random()
        val modelA = Model(modelId = "a", displayName = "A")
        val modelB = Model(modelId = "b", displayName = "B")

        // Draft = the in-progress Config-tab edit: fresh apiKey/baseUrl, stale model snapshot [A, B].
        val draft = ProviderSetting.OpenAI(
            id = id,
            apiKey = "NEW-edited-key",
            baseUrl = "https://edited",
            models = listOf(modelA, modelB),
        )

        // Persisted = what's in settings right now: old key, but models reordered to [B, A]
        // by a Models-tab op done before the user pressed Save.
        val persisted = ProviderSetting.OpenAI(
            id = id,
            apiKey = "OLD-stale-key",
            baseUrl = "https://api.openai.com/v1",
            models = listOf(modelB, modelA),
        )

        val merged = mergeConfigKeepingModels(draft, persisted) as ProviderSetting.OpenAI

        // The fix: edited config wins over the persisted (stale) config.
        assertEquals("NEW-edited-key", merged.apiKey)
        assertEquals("https://edited", merged.baseUrl)

        // And the reorder done before Save is preserved, not reverted by the draft's [A, B].
        assertEquals(
            listOf(modelB.id, modelA.id),
            merged.models.map { it.id },
        )
    }
}
