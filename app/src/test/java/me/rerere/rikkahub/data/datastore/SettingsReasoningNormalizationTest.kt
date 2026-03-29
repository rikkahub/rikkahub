package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class SettingsReasoningNormalizationTest {
    @Test
    fun `assistant model switch should normalize gpt only budgets for non gpt model`() {
        val gptModel = chatModel(modelId = "gpt-5.4-mini")
        val nonGptModel = chatModel(modelId = "o3")
        val assistant = Assistant(chatModelId = gptModel.id, thinkingBudget = 1)

        val normalized = settings(
            chatModelId = gptModel.id,
            assistants = listOf(assistant.copy(chatModelId = nonGptModel.id)),
            providers = listOf(provider(gptModel, nonGptModel))
        ).normalizeAssistantThinkingBudgets()

        assertEquals(1024, normalized.assistants.single().thinkingBudget)
    }

    @Test
    fun `global chat model switch should normalize inherited assistant budgets`() {
        val gptModel = chatModel(modelId = "gpt-5.4-mini")
        val nonGptModel = chatModel(modelId = "o3")
        val assistant = Assistant(chatModelId = null, thinkingBudget = 64_000)

        val normalized = settings(
            chatModelId = nonGptModel.id,
            assistants = listOf(assistant),
            providers = listOf(provider(gptModel, nonGptModel))
        ).normalizeAssistantThinkingBudgets()

        assertEquals(32_000, normalized.assistants.single().thinkingBudget)
    }

    @Test
    fun `normalization should preserve ordinary custom budgets`() {
        val nonGptModel = chatModel(modelId = "o3")
        val assistant = Assistant(chatModelId = nonGptModel.id, thinkingBudget = 5_000)

        val normalized = settings(
            chatModelId = nonGptModel.id,
            assistants = listOf(assistant),
            providers = listOf(provider(nonGptModel))
        ).normalizeAssistantThinkingBudgets()

        assertEquals(5_000, normalized.assistants.single().thinkingBudget)
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
