package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class SettingsReasoningNormalizationTest {
    @Test
    fun `assistant model switch should downgrade unsupported preset but keep cache and restore later`() {
        val gptModel = chatModel(modelId = "gpt-5")
        val nonGptModel = chatModel(modelId = "o3")
        val assistant = Assistant(chatModelId = gptModel.id, thinkingBudget = 1)

        val downgraded = settings(
            chatModelId = gptModel.id,
            assistants = listOf(assistant.copy(chatModelId = nonGptModel.id)),
            providers = listOf(provider(gptModel, nonGptModel))
        ).normalizeAssistantThinkingBudgets()

        assertEquals(1024, downgraded.assistants.single().thinkingBudget)
        assertEquals(1, downgraded.assistants.single().thinkingBudgetCache)

        val restored = downgraded.copy(
            assistants = listOf(downgraded.assistants.single().copy(chatModelId = gptModel.id))
        ).normalizeAssistantThinkingBudgets()

        assertEquals(1, restored.assistants.single().thinkingBudget)
        assertEquals(null, restored.assistants.single().thinkingBudgetCache)
    }

    @Test
    fun `global chat model switch should downgrade inherited assistant budgets and restore from cache`() {
        val gptModel = chatModel(modelId = "gpt-5.4-mini")
        val nonGptModel = chatModel(modelId = "o3")
        val assistant = Assistant(chatModelId = null, thinkingBudget = 64_000)

        val downgraded = settings(
            chatModelId = nonGptModel.id,
            assistants = listOf(assistant),
            providers = listOf(provider(gptModel, nonGptModel))
        ).normalizeAssistantThinkingBudgets()

        assertEquals(32_000, downgraded.assistants.single().thinkingBudget)
        assertEquals(64_000, downgraded.assistants.single().thinkingBudgetCache)

        val restored = downgraded.copy(chatModelId = gptModel.id).normalizeAssistantThinkingBudgets()
        assertEquals(64_000, restored.assistants.single().thinkingBudget)
        assertEquals(null, restored.assistants.single().thinkingBudgetCache)
    }

    @Test
    fun `normalization should clear cache for custom budgets`() {
        val nonGptModel = chatModel(modelId = "o3")
        val assistant = Assistant(chatModelId = nonGptModel.id, thinkingBudget = 5_000, thinkingBudgetCache = 1)

        val normalized = settings(
            chatModelId = nonGptModel.id,
            assistants = listOf(assistant),
            providers = listOf(provider(nonGptModel))
        ).normalizeAssistantThinkingBudgets()

        assertEquals(5_000, normalized.assistants.single().thinkingBudget)
        assertEquals(null, normalized.assistants.single().thinkingBudgetCache)
    }

    private fun settings(
        chatModelId: Uuid,
        assistants: List<Assistant>,
        providers: List<ProviderSetting>
    ): Settings {
        return Settings(
            chatModelId = chatModelId,
            titleModelId = chatModelId,
            imageGenerationModelId = chatModelId,
            translateModeId = chatModelId,
            suggestionModelId = chatModelId,
            ocrModelId = chatModelId,
            compressModelId = chatModelId,
            assistantId = assistants.first().id,
            assistants = assistants,
            providers = providers
        )
    }

    private fun chatModel(modelId: String): Model {
        return Model(
            id = Uuid.random(),
            modelId = modelId,
            displayName = modelId
        )
    }

    private fun provider(vararg models: Model): ProviderSetting.OpenAI {
        return ProviderSetting.OpenAI(
            models = models.toList()
        )
    }
}
