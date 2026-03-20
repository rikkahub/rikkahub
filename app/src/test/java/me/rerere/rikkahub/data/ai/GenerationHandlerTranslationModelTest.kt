package me.rerere.rikkahub.data.ai

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationHandlerTranslationModelTest {
    @Test
    fun disableReasoningForTranslation_removesReasoningAbility() {
        val model = Model(
            modelId = "test-model",
            displayName = "Test Model",
            abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING)
        )

        val translationModel = model.disableReasoningForTranslation()

        assertFalse(translationModel.abilities.contains(ModelAbility.REASONING))
        assertTrue(translationModel.abilities.contains(ModelAbility.TOOL))
        assertEquals(model.modelId, translationModel.modelId)
        assertEquals(model.displayName, translationModel.displayName)
        assertEquals(model.id, translationModel.id)
    }

    @Test
    fun disableReasoningForTranslation_keepsModelWithoutReasoningUnchanged() {
        val model = Model(
            modelId = "test-model",
            displayName = "Test Model",
            abilities = listOf(ModelAbility.TOOL)
        )

        val translationModel = model.disableReasoningForTranslation()

        assertEquals(model, translationModel)
    }
}
