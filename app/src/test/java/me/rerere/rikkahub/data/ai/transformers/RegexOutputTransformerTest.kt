package me.rerere.rikkahub.data.ai.transformers

import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.AssistantRegexPlacement
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class RegexOutputTransformerTest {
    @Test
    fun `visual transform should include markdown-only output regex but finish should only persist actual output`() = runBlocking {
        val assistant = Assistant(
            regexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    name = "Actual Output",
                    findRegex = "foo",
                    replaceString = "bar",
                    affectingScope = setOf(AssistantAffectScope.ASSISTANT),
                    stPlacements = setOf(AssistantRegexPlacement.AI_OUTPUT),
                ),
                AssistantRegex(
                    id = Uuid.random(),
                    name = "Markdown Output",
                    findRegex = "bar",
                    replaceString = "baz",
                    affectingScope = setOf(AssistantAffectScope.ASSISTANT),
                    visualOnly = true,
                    stPlacements = setOf(AssistantRegexPlacement.AI_OUTPUT),
                ),
            )
        )
        val ctx = TransformerContext(
            context = ContextWrapper(null),
            model = Model(),
            assistant = assistant,
            settings = Settings(),
        )
        val messages = listOf(UIMessage.assistant("foo"))

        val visual = RegexOutputTransformer.visualTransform(ctx, messages)
        val finished = RegexOutputTransformer.onGenerationFinish(ctx, messages)

        assertEquals(listOf("baz"), visual.map { it.toText() })
        assertEquals(listOf("bar"), finished.map { it.toText() })
    }
}
